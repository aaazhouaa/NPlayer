package com.nplayer.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.nplayer.core.common.Utils
import com.nplayer.core.common.extensions.mapAsync
import com.nplayer.core.data.mappers.toAudioStreamInfo
import com.nplayer.core.data.mappers.toFolder
import com.nplayer.core.data.mappers.toSubtitleStreamInfo
import com.nplayer.core.data.mappers.toVideo
import com.nplayer.core.data.mappers.toVideoState
import com.nplayer.core.data.mappers.toVideoStreamInfo
import com.nplayer.core.data.models.VideoState
import com.nplayer.core.database.converter.UriListConverter
import com.nplayer.core.database.dao.MediumStateDao
import com.nplayer.core.database.entities.MediumStateEntity
import com.nplayer.core.datastore.datasource.AppPreferencesDataSource
import com.nplayer.core.media.network.NetworkUri
import com.nplayer.core.media.services.MediaFolder
import com.nplayer.core.media.services.MediaService
import com.nplayer.core.media.services.MediaVideo
import com.nplayer.core.model.ApplicationPreferences
import com.nplayer.core.model.Folder
import com.nplayer.core.model.MediaInfo
import com.nplayer.core.model.Video
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.util.Date
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class LocalMediaRepository(
    private val mediumStateDao: MediumStateDao,
    private val mediaService: MediaService,
    private val appPreferencesDataSource: AppPreferencesDataSource,
    private val context: Context,
) : MediaRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFolders(folderPath: String?): Flow<List<Folder>> =
        appPreferencesDataSource.preferences
            .flatMapLatest { preferences ->
                combine(
                    scanRootFlows(
                        preferences,
                        folderPath,
                    ),
                ) { flows ->
                    flows.flatMap { it }.distinctBy { it.path }.map { it.toFolder() }
                }
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeVideos(folderPath: String?): Flow<List<Video>> =
        appPreferencesDataSource.preferences
            .flatMapLatest { preferences ->
                combine(
                    combine(
                        scanVideoFlows(
                            preferences,
                            folderPath,
                        ),
                    ) { flows ->
                        flows.flatMap { it }.distinctBy { it.uri }
                    },
                    mediumStateDao.getAll(),
                ) { mediaVideos, mediumStates ->
                    val statesMap = mediumStates.associateBy { it.uriString }
                    mediaVideos.map { mediaVideo ->
                        mediaVideo.toVideo(statesMap[mediaVideo.uri.toString()])
                    }
                }
            }

    override suspend fun fetchFolders(folderPath: String?): List<Folder> {
        val preferences = appPreferencesDataSource.preferences.first()
        return scanRoots(
            preferences,
            folderPath,
        )
            .map { root ->
                mediaService.fetchFolders(
                    folderPath = root,
                    includeHidden = preferences.showHiddenFiles,
                    respectNoMedia = preferences.respectNoMedia,
                )
            }
            .flatten()
            .distinctBy { it.path }
            .map { it.toFolder() }
    }

    override suspend fun fetchVideos(folderPath: String?): List<Video> {
        val preferences = appPreferencesDataSource.preferences.first()
        val mediaVideos = scanRoots(
            preferences,
            folderPath,
        )
            .map { root ->
                mediaService.fetchVideos(
                    folderPath = root,
                    includeHidden = preferences.showHiddenFiles,
                    respectNoMedia = preferences.respectNoMedia,
                )
            }
            .flatten()
            .distinctBy { it.uri }
        return mediaVideos.mapAsync { mediaVideo ->
            val mediaState = mediumStateDao.get(mediaVideo.uri.toString())
            mediaVideo.toVideo(mediaState)
        }
    }

    private fun scanRoots(preferences: ApplicationPreferences, folderPath: String?): List<String?> =
        if (folderPath != null) {
            listOf(folderPath)
        } else if (preferences.scanFolders.isNotEmpty()) {
            preferences.scanFolders
        } else {
            listOf(null)
        }

    private fun scanRootFlows(
        preferences: ApplicationPreferences,
        folderPath: String?,
    ): List<Flow<List<MediaFolder>>> = scanRoots(
        preferences,
        folderPath,
    ).map { root ->
        mediaService.observeFolders(
            folderPath = root,
            includeHidden = preferences.showHiddenFiles,
            respectNoMedia = preferences.respectNoMedia,
        )
    }

    private fun scanVideoFlows(
        preferences: ApplicationPreferences,
        folderPath: String?,
    ): List<Flow<List<MediaVideo>>> = scanRoots(
        preferences,
        folderPath,
    ).map { root ->
        mediaService.observeVideos(
            folderPath = root,
            includeHidden = preferences.showHiddenFiles,
            respectNoMedia = preferences.respectNoMedia,
        )
    }

    override fun observePlaybackHistory(): Flow<List<Video>> = combine(
        mediaService.observeVideos(),
        mediumStateDao.getAll(),
    ) {
            mediaVideos,
            mediumStates,
        ->
        val videosByUri = mediaVideos.associateBy { it.uri.toString() }
        mediumStates
            .filter { it.lastPlayedTime != null && !it.uriString.toUri().isVaultUri() }
            .sortedByDescending { it.lastPlayedTime }
            .map { state ->
                videosByUri[state.uriString]?.toVideo(mediaState = state)
                    ?: state.toHistoryVideo()
            }
    }

    override fun observeTrashVideos(): Flow<List<Video>> =
        mediaService.observeTrashVideos().map { videos -> videos.map { it.toVideo() } }

    override suspend fun getVideoByUri(uri: String): Video? = coroutineScope {
        val mediaVideoDeferred = async { mediaService.findVideo(uri.toUri()) }
        val mediaStateDeferred = async { mediumStateDao.get(uri = uri) }

        val mediaVideo = mediaVideoDeferred.await() ?: return@coroutineScope null
        val mediaState = mediaStateDeferred.await()

        return@coroutineScope mediaVideo.toVideo(mediaState = mediaState)
    }

    override suspend fun getVideoState(uri: String): VideoState? =
        mediumStateDao.get(uri = uri)?.toVideoState()

    override suspend fun getMediaInfo(uri: String): MediaInfo? = withContext(Dispatchers.IO) {
        val video = getVideoByUri(uri = uri) ?: return@withContext null
        val mediaInfo = runCatching {
            MediaInfoBuilder().from(
                context = context,
                uri = uri.toUri(),
            ).build()
        }.getOrNull()
        val result = MediaInfo(
            video = video.copy(format = mediaInfo?.format),
            videoStream = mediaInfo?.videoStream?.toVideoStreamInfo(),
            audioStreams = mediaInfo?.audioStreams?.map { it.toAudioStreamInfo() } ?: emptyList(),
            subtitleStreams =
                mediaInfo?.subtitleStreams?.map { it.toSubtitleStreamInfo() } ?: emptyList(),
        )
        mediaInfo?.release()
        return@withContext result
    }

    override suspend fun clearPlaybackHistory() {
        mediumStateDao.clearPlaybackHistory()
    }

    override suspend fun updateMediumLastPlayedTime(
        uri: String,
        lastPlayedTime: Long,
        duration: Long?,
    ) {
        val preferences = appPreferencesDataSource.preferences.first()
        val mediaUri = uri.toUri()
        val recordHistory = !preferences.isHistoryPaused &&
            !mediaUri.isVaultUri() &&
            (
                preferences.includeNetworkWatchHistory ||
                    !NetworkUri.isNetworkUri(uri = mediaUri) ||
                    NetworkUri.connectionIdOf(uri = mediaUri) == null
                )
        mediumStateDao.update(uri = uri) { state ->
            state.copy(
                lastPlayedTime = if (recordHistory) lastPlayedTime else state.lastPlayedTime,
                duration = duration ?: state.duration,
                // Playing a video marks it read even when history recording is paused: the
                // "new" badge tracks whether the user has seen the video, not whether the
                // play event was recorded in history.
                isRead = true,
            )
        }
    }

    override suspend fun markVideosAsRead(uris: List<String>) {
        if (uris.isEmpty()) return
        val existingStates = mediumStateDao.getAll().first().associateBy { it.uriString }
        mediumStateDao.upsertAll(
            mediaStates = uris.distinct().map { uri ->
                (existingStates[uri] ?: MediumStateEntity(uriString = uri)).copy(isRead = true)
            },
        )
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        mediumStateDao.update(uri = uri) { it.copy(playbackPosition = position) }
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        mediumStateDao.update(uri = uri) { it.copy(audioTrackIndex = audioTrackIndex) }
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleTrackIndex = subtitleTrackIndex) }
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        mediumStateDao.update(uri = uri) { it.copy(videoScale = zoom) }
    }

    override suspend fun addExternalAudioToMedium(uri: String, audioUri: Uri) {
        val stateEntity = mediumStateDao.get(uri = uri) ?: MediumStateEntity(uriString = uri)
        val audio = UriListConverter.fromStringToList(stateEntity.externalAudio)
        if (audioUri in audio) return
        mediumStateDao.upsert(
            stateEntity.copy(
                externalAudio = UriListConverter.fromListToString(
                    urlList =
                        audio + audioUri,
                ),
            ),
        )
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        mediumStateDao.update(uri = uri) { state ->
            val subtitles = UriListConverter.fromStringToList(state.externalSubs)
            state.copy(
                externalSubs = UriListConverter.fromListToString(
                    (subtitles + subtitleUri).distinct(),
                ),
            )
        }
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleDelayMilliseconds = delay) }
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
        mediumStateDao.update(uri = uri) { it.copy(subtitleSpeed = speed) }
    }

    private fun Uri.isVaultUri(): Boolean {
        if (scheme != "content" || authority != "${context.packageName}.fileprovider") return false
        val segments = pathSegments
        val root = segments.firstOrNull()
        return root == "vault" ||
            (
                segments.getOrNull(index = 1) == "vault" &&
                    (root == "external_files_path" || root == "files_path")
                )
    }
}

private fun MediumStateEntity.toHistoryVideo(): Video {
    val uri = uriString.toUri()
    val name = uri.lastPathSegment?.substringAfterLast(delimiter = '/')
        ?: uri.host
        ?: uriString
    return Video(
        id = uriString.hashCode().toLong().absoluteValue,
        path = uriString,
        uriString = uriString,
        nameWithExtension = name,
        duration = duration ?: 0,
        width = 0,
        height = 0,
        size = 0,
        formattedDuration = duration?.let(block = Utils::formatDurationMillis).orEmpty(),
        playbackPosition = playbackPosition,
        lastPlayedAt = lastPlayedTime?.let(block = ::Date),
        isRead = isRead,
    )
}
