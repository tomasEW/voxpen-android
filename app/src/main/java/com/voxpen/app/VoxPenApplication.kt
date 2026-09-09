package com.voxpen.app

import android.app.Application
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.util.DownloadLogTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VoxPenApplication : Application() {
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var transcriptionRepository: TranscriptionRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        val downloadLogTree = DownloadLogTree(applicationContext)
        Timber.plant(downloadLogTree)
        applicationScope.launch {
            preferencesManager.downloadLoggingEnabledFlow.collect { enabled ->
                downloadLogTree.setEnabled(enabled)
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            runCatching { transcriptionRepository.cleanupOrphanedRecordings() }
                .onFailure { Timber.w(it, "Failed to clean up orphaned recordings") }
        }
        // Google Play billing and license validation are retained as legacy source only.
        // This fork has no active purchase or license flow.
    }
}
