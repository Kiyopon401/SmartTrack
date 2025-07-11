package com.example.smarttrackapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// No longer starts any service. Tracking is handled by DeviceTrackerCompanion.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "Device booted, but location tracking is now handled by DeviceTrackerCompanion.")
        // No-op
    }
}