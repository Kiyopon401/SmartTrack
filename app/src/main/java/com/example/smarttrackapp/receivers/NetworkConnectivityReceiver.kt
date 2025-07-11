package com.example.smarttrackapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.smarttrackapp.workers.SyncWorker

class NetworkConnectivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork

        if (network != null) {
            // Internet is back, trigger sync
            val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(syncWorkRequest)
        }
    }
}