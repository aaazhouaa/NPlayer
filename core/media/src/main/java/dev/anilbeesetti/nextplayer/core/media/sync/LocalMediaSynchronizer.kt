package dev.anilbeesetti.nextplayer.core.media.sync

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil3.ImageLoader
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.extensions.getStorageVolumes
import dev.anilbeesetti.nextplayer.core.common.extensions.scanPaths
import dev.anilbeesetti.nextplayer.core.common.extensions.scanStorage
import dev.anilbeesetti.nextplayer.core.common.millisSince
import dev.anilbeesetti.nextplayer.core.common.storagePermission
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.dao.PlaylistDao
import dev.anilbeesetti.nextplayer.core.media.services.MediaService
import dev.anilbeesetti.nextplayer.core.media.services.MediaVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class LocalMediaSynchronizer(
    private val mediumStateDao: MediumStateDao,
    private val playlistDao: PlaylistDao,
    private val imageLoader: ImageLoader,
    private val mediaService: MediaService,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
    private val context: Context,
) : MediaSynchronizer {

    private var mediaSyncingJob: Job? = null

    private companion object {
        private const val TAG = "MediaSynchronizer"
    }

    override suspend fun refresh(path: String?): Boolean {
        val startedAt = System.nanoTime()
        mediaService.invalidateHiddenCache()
        val scanned = path?.let { context.scanPaths(paths = listOf(element = path)) }
            ?: context.getStorageVolumes().all { context.scanStorage(it.path) }
        Logger.logInfo(
            tag = TAG,
            message = "media refresh ${if (scanned) "requested" else "failed"} for " +
                "${path ?: "all volumes"} in ${millisSince(startedAt)}ms",
        )
        return scanned
    }

    override fun startSync() {
        if (mediaSyncingJob?.isActive == true) return
        mediaSyncingJob = mediaService.observeVideos()
            .onEach { media -> updateMedia(media = media) }
            .catch { error ->
                // A failed MediaStore query is not a complete snapshot. Leave stored state alone;
                // startSync can retry after storage permission or the provider becomes available.
                Logger.logError(
                    tag = TAG,
                    message = "media sync collection failed, keeping stored state: $error",
                    throwable = error,
                )
            }
            .launchIn(scope = applicationScope)
    }

    override fun stopSync() {
        mediaSyncingJob?.cancel()
    }

    private fun Context.hasStoragePermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        storagePermission,
    ) ==
        PackageManager.PERMISSION_GRANTED

    private suspend fun updateMedia(media: List<MediaVideo>) = withContext(Dispatchers.Default) {
        if (!context.hasStoragePermission()) return@withContext

        val currentMediaUris = media.mapTo(destination = mutableSetOf()) { it.uri.toString() }

        playlistDao.removeMissingLocalItems(existingUris = currentMediaUris)

        val (wantedMediaStates, unwantedMediaStates) = mediumStateDao.getAll().first().partition {
            it.uriString in currentMediaUris ||
                !ContentResolver.SCHEME_CONTENT.equals(
                    it.uriString.toUri().scheme,
                    ignoreCase = true,
                )
        }

        mediumStateDao.delete(unwantedMediaStates.map { it.uriString })

        // Delete unwanted thumbnails
        unwantedMediaStates.forEach { mediaState ->
            try {
                imageLoader.diskCache?.remove(mediaState.uriString)
            } catch (e: Exception) {
                Logger.logWarning(
                    tag = TAG,
                    message = "thumbnail cache removal failed for ${mediaState.uriString}",
                    throwable = e,
                )
            }
        }

        // Release external subtitle uri permission if not used by any other media
        launch {
            val currentMediaExternalSubs = wantedMediaStates.flatMap {
                UriListConverter.fromStringToList(it.externalSubs)
            }.toSet()

            unwantedMediaStates.onEach { mediaState ->
                for (sub in UriListConverter.fromStringToList(mediaState.externalSubs)) {
                    if (sub !in currentMediaExternalSubs) {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                sub,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (e: Exception) {
                            Logger.logWarning(
                                tag = TAG,
                                message = "releasing subtitle uri permission failed for $sub",
                                throwable = e,
                            )
                        }
                    }
                }
            }
        }
    }
}
