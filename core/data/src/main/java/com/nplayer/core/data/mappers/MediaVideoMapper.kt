package com.nplayer.core.data.mappers

import com.nplayer.core.common.Utils
import com.nplayer.core.database.entities.MediumStateEntity
import com.nplayer.core.media.services.MediaVideo
import com.nplayer.core.model.Video
import java.util.Date

internal fun MediaVideo.toVideo(mediaState: MediumStateEntity? = null) = Video(
    id = id,
    uriString = uri.toString(),
    duration = duration,
    height = height,
    width = width,
    path = path,
    size = size,
    nameWithExtension = displayName,
    parentPath = parentPath,
    dateModified = dateModified,
    dateAdded = dateAdded,
    formattedDuration = Utils.formatDurationMillis(millis = duration),
    formattedFileSize = Utils.formatFileSize(size = size),
    isHidden = isHidden,
    playbackPosition = mediaState?.playbackPosition,
    lastPlayedAt = mediaState?.lastPlayedTime?.let { Date(it) },
    isRead = mediaState?.isRead ?: false,
)
