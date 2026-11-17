package com.nplayer.core.media

import com.nplayer.core.common.di.DispatchersModule
import com.nplayer.core.database.DatabaseModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [DatabaseModule::class, DispatchersModule::class])
@ComponentScan
class MediaModule
