package com.example.smarttrackapp.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Syncing is now handled by DeviceTrackerCompanion. This worker does nothing.
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return Result.success()
    }
}