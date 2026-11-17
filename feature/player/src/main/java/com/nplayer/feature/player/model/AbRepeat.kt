package com.nplayer.feature.player.model

/** Playback region that loops back from [endMs] to [startMs] instead of running past it. */
data class AbRepeat(val startMs: Long, val endMs: Long)
