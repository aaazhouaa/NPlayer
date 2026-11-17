package com.nplayer.core.data

import com.nplayer.core.data.repository.NetworkConnectionRepository
import com.nplayer.core.database.DatabaseModule
import com.nplayer.core.datastore.di.DataStoreModule
import com.nplayer.core.media.MediaModule
import com.nplayer.core.media.network.NetworkConnectionResolver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(includes = [DatabaseModule::class, DataStoreModule::class, MediaModule::class])
@ComponentScan
class DataModule {
    /** Lets `core:media` resolve a playback uri's connection id without depending on `core:data`. */
    @Single
    fun providesNetworkConnectionResolver(
        repository: NetworkConnectionRepository,
    ): NetworkConnectionResolver = NetworkConnectionResolver { id ->
        repository.getConnection(id = id)
    }
}
