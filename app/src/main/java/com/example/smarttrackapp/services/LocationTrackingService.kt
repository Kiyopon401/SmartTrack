// DEPRECATED: This service is no longer used. DeviceTrackerCompanion is now responsible for all location tracking and syncing.
// All logic is disabled below.
package com.example.smarttrackapp.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class LocationTrackingService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service created (DEPRECATED)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service starting (DEPRECATED)")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}