// Replace your setupFirebaseListeners function with this enhanced version:

private fun setupFirebaseListeners(deviceId: String) {
    Log.d(TAG, "Setting up Firebase listeners for deviceId: $deviceId")
    // Clean up previous listeners
    firebaseListener?.let {
        firebaseDb.reference.removeEventListener(it)
    }
    locationListener?.let {
        firebaseDb.reference.removeEventListener(it)
    }

    // Listener for device status (online/offline)
    firebaseDb.reference.child("devices").child(deviceId).child("active")
        .addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isActive = snapshot.getValue(Boolean::class.java) ?: false
                isFirebaseConnected = isActive
                updateConnectionStatus()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Device status listener cancelled", error.toException())
                isFirebaseConnected = false
                updateConnectionStatus()
            }
        })

    // Listener for location updates
    firebaseListener = firebaseDb.reference.child("vehicles").child(deviceId).child("location")
        .addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    lastFirebaseUpdateTime = System.currentTimeMillis()
                    // Defensive: check if snapshot exists and is not too large
                    if (!snapshot.exists() || snapshot.childrenCount > 100) {
                        Log.w(TAG, "Skipping location update: invalid or too many children in snapshot")
                        return
                    }
                    val lat = snapshot.child("latitude").getValue(Double::class.java)
                    val lng = snapshot.child("longitude").getValue(Double::class.java)
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java)
                    Log.d(TAG, "onDataChange: lat=$lat, lng=$lng, timestamp=$timestamp for deviceId=$deviceId")
                    if (lat != null && lng != null && timestamp != null) {
                        // Always save trip points (no throttling for data)
                        viewModel.saveTripPoints(currentVehicle.id, listOf(Pair(lat, lng)))
                        // Throttle UI updates to prevent lag/crashes
                        val now = System.currentTimeMillis()
                        if (now - lastUIUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                            lastUIUpdateTime = now
                            runOnUiThread {
                                throttledMapUpdate {
                                    // Update map marker with tracker location
                                    MapUtils.updateMapLocation(
                                        binding.mapWebView,
                                        lat, lng,
                                        currentVehicle.nickname,
                                        true // Always show as inside since Arduino handles geofence
                                    )

                                    // Update the location above the map to match the tracked device
                                    binding.lastLocation.text = "Lat: %.6f, Lng: %.6f".format(lat, lng)
                                    isFirebaseConnected = true
                                    updateConnectionStatus()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Firebase location onDataChange", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Location listener cancelled", error.toException())
                isFirebaseConnected = false
                updateConnectionStatus()
            }
        })

    // ENHANCED: Listener for Arduino geofence alerts
    firebaseDb.reference.child("vehicles").child(deviceId).child("alerts")
        .addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val alert = snapshot.getValue(String::class.java)
                if (alert != null && alert.isNotEmpty()) {
                    Log.d(TAG, "Received alert: $alert")
                    
                    // Clear the alert immediately to prevent re-triggering
                    firebaseDb.reference.child("vehicles").child(deviceId).child("alerts").removeValue()

                    // Process different types of alerts
                    when {
                        alert.contains("GEOFENCE_EXIT") -> {
                            handleGeofenceExitAlert(alert)
                        }
                        alert.contains("GEOFENCE_ENTER") -> {
                            handleGeofenceEnterAlert(alert)
                        }
                        alert.contains("TAMPER") -> {
                            handleTamperAlert(alert)
                        }
                        else -> {
                            Log.w(TAG, "Unknown alert type: $alert")
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Alert listener cancelled", error.toException())
            }
        })
}

// Add these new alert handling functions:

private fun handleGeofenceExitAlert(alert: String) {
    val message = "🚨 Vehicle left geofence area!"
    
    // Update UI
    runOnUiThread {
        binding.geofenceAlert.text = message
        binding.geofenceAlert.visibility = View.VISIBLE
        binding.geofenceAlert.setTextColor(ContextCompat.getColor(this, R.color.red))
    }
    
    // Show toast
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    
    // Play alert sound
    playAlertSound()
    
    // Show push notification
    showGeofenceNotification("Geofence Alert", message, true)
    
    Log.w(TAG, "Geofence EXIT alert: $alert")
}

private fun handleGeofenceEnterAlert(alert: String) {
    val message = "✅ Vehicle returned to geofence area"
    
    // Update UI
    runOnUiThread {
        binding.geofenceAlert.text = message
        binding.geofenceAlert.visibility = View.VISIBLE
        binding.geofenceAlert.setTextColor(ContextCompat.getColor(this, R.color.green))
        
        // Hide alert after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            binding.geofenceAlert.visibility = View.GONE
        }, 5000)
    }
    
    // Show toast
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    
    // Show push notification
    showGeofenceNotification("Geofence Alert", message, false)
    
    Log.d(TAG, "Geofence ENTER alert: $alert")
}

private fun handleTamperAlert(alert: String) {
    val message = "⚠️ TAMPER ALERT: Device enclosure opened!"
    
    // Update UI
    runOnUiThread {
        binding.geofenceAlert.text = message
        binding.geofenceAlert.visibility = View.VISIBLE
        binding.geofenceAlert.setTextColor(ContextCompat.getColor(this, R.color.orange))
    }
    
    // Show toast
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    
    // Play alert sound
    playAlertSound()
    
    // Show push notification
    showGeofenceNotification("Tamper Alert", message, true)
    
    Log.w(TAG, "Tamper alert: $alert")
}

// Add these new utility functions:

private fun playAlertSound() {
    try {
        val mediaPlayer = MediaPlayer.create(this, R.raw.alert_buzzer)
        mediaPlayer?.let {
            it.setOnCompletionListener { mp ->
                mp.release()
            }
            it.start()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error playing alert sound", e)
    }
}

private fun showGeofenceNotification(title: String, message: String, isUrgent: Boolean) {
    val notificationId = if (isUrgent) NOTIFICATION_ID + 1 else NOTIFICATION_ID + 2
    
    val intent = Intent(this, VehicleDetailActivity::class.java).apply {
        putExtra("vehicle_id", currentVehicle.id)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    
    val pendingIntent = PendingIntent.getActivity(
        this,
        notificationId,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    
    val notification = NotificationCompat.Builder(this, "geofence_channel")
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_track)
        .setContentIntent(pendingIntent)
        .setPriority(if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setSound(if (isUrgent) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null)
        .setVibrate(if (isUrgent) longArrayOf(0, 1000, 500, 1000) else null)
        .build()
    
    NotificationManagerCompat.from(this).notify(notificationId, notification)
}

// Add this function to clear alerts when geofence is disabled:
private fun clearGeofenceState() {
    // Remove geofence circle from map
    MapUtils.removeGeofenceCircle(binding.mapWebView)

    // Clear geofence variables
    geofenceLat = null
    geofenceLng = null
    hasPlayedAlert = false

    // Clear any existing alert messages
    runOnUiThread {
        binding.geofenceAlert.text = ""
        binding.geofenceAlert.visibility = View.GONE
    }

    // Send clear command to tracker
    clearGeofenceCommandToTracker()
}