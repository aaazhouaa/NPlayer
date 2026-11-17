package com.nplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.nplayer.core.common.Logger
import com.nplayer.core.common.di.DiQualifiers
import com.nplayer.core.data.repository.NetworkConnectionRepository
import com.nplayer.core.media.network.keys.SshKeyStore
import com.nplayer.crash.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinApplication
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.startKoin

@KoinApplication(modules = [AppModule::class])
class NPlayerApplication :
    Application(),
    SingletonImageLoader.Factory {

    private val imageLoader: ImageLoader by inject()

    private val networkConnectionRepository: NetworkConnectionRepository by inject()

    private val sshKeyStore: SshKeyStore by inject()

    private val applicationScope: CoroutineScope by inject(
        qualifier = named(DiQualifiers.APPLICATION_SCOPE),
    )

    override fun onCreate() {
        super.onCreate()
        Logger.install(context = applicationContext)
        startKoin<NPlayerApplication> {
            allowOverride(override = false)
            androidContext(androidContext = this@NPlayerApplication)
        }
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler())
        applicationScope.launch {
            runCatching {
                initializeSshKeyStore(
                    repository = networkConnectionRepository,
                    sshKeyStore = sshKeyStore,
                )
            }.onFailure { error ->
                Logger.logError(
                    tag = TAG,
                    message = "Couldn't reconcile SSH keys: ${error.message}",
                )
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    private companion object {
        const val TAG = "NPlayerApplication"
    }
}
