package com.example.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = SyncCoordinator(applicationContext)
        return try {
            val flushed = coordinator.flushPendingQueue()
            coordinator.publishPresence(isOnline = true)
            if (flushed) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
