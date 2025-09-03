// Replace your existing alert handling functions with these enhanced versions:

private fun handleGeofenceExitAlert(alert: String) {
    val message = "🚨 Vehicle left geofence area!"
    
    // Update UI
    runOnUiThread {
        binding.geofenceAlert.text = message
        binding.geofenceAlert.visibility = View.VISIBLE
        binding.geofenceAlert.setTextColor(ContextCompat.getColor(this, R.color.red))
    }
    
    // Show dialog box
    showAlertDialog(
        title = "Geofence Alert",
        message = "Your vehicle has left the geofence area!\n\nThis could indicate:\n• Vehicle theft\n• Unauthorized movement\n• Device tampering\n\nPlease check your vehicle immediately.",
        isUrgent = true
    )
    
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
    
    // Show dialog box
    showAlertDialog(
        title = "Geofence Status",
        message = "Your vehicle has returned to the geofence area.\n\nLocation is now secure.",
        isUrgent = false
    )
    
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
    
    // Show dialog box
    showAlertDialog(
        title = "Tamper Alert",
        message = "⚠️ SECURITY ALERT ⚠️\n\nYour tracking device has been tampered with!\n\nPossible causes:\n• Device enclosure opened\n• Cable cut or disconnected\n• Unauthorized access\n\nPlease check your vehicle and device immediately.",
        isUrgent = true
    )
    
    // Play alert sound
    playAlertSound()
    
    // Show push notification
    showGeofenceNotification("Tamper Alert", message, true)
    
    Log.w(TAG, "Tamper alert: $alert")
}

// Add this new function to show alert dialogs:
private fun showAlertDialog(title: String, message: String, isUrgent: Boolean) {
    runOnUiThread {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false) // Prevent dismissing by tapping outside
        
        if (isUrgent) {
            // For urgent alerts, add action buttons
            builder.setPositiveButton("I'll Check Now") { dialog, _ ->
                dialog.dismiss()
                // Optional: Open camera or call emergency number
                Toast.makeText(this, "Please check your vehicle immediately", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss") { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
        } else {
            // For non-urgent alerts, just show OK button
            builder.setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_info)
        }
        
        val dialog = builder.create()
        
        // Make urgent dialogs more prominent
        if (isUrgent) {
            dialog.window?.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        dialog.show()
    }
}