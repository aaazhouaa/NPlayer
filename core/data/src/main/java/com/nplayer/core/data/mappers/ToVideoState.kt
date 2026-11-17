package com.nplayer.core.data.mappers

import com.nplayer.core.data.models.VideoState
import com.nplayer.core.database.converter.UriListConverter
import com.nplayer.core.database.entities.MediumStateEntity

fun MediumStateEntity.toVideoState(): VideoState = VideoState(
    path = uriString,
    position = playbackPosition.takeIf { it != 0L },
    audioTrackIndex = audioTrackIndex,
    subtitleTrackIndex = subtitleTrackIndex,
    externalSubs = UriListConverter.fromStringToList(urlString = externalSubs),
    externalAudio = UriListConverter.fromStringToList(urlString = externalAudio),
    videoScale = videoScale,
    subtitleDelayMilliseconds = subtitleDelayMilliseconds,
    subtitleSpeed = subtitleSpeed,
)
