package com.nplayer.core.media.network

import com.nplayer.core.model.NetworkConnection

fun interface NetworkClientFactory {
    fun create(connection: NetworkConnection): NetworkClient
}
