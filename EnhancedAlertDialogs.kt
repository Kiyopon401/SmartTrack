// Enhanced version with more dialog options:

private fun showAlertDialog(title: String, message: String, isUrgent: Boolean) {
    runOnUiThread {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false) // Prevent dismissing by tapping outside
        
        if (isUrgent) {
            // For urgent alerts, add multiple action buttons
            builder.setPositiveButton("🚗 Check Vehicle") { dialog, _ ->
                dialog.dismiss()
                // Optional: Open maps to navigate to vehicle location
                openVehicleLocation()
            }
            .setNeutralButton("📞 Call Emergency") { dialog, _ ->
                dialog.dismiss()
                // Optional: Open dialer with emergency number
                openEmergencyDialer()
            }
            .setNegativeButton("❌ Dismiss") { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
        } else {
            // For non-urgent alerts, just show OK button
            builder.setPositiveButton("✅ OK") { dialog, _ ->
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

// Add these helper functions:
private fun openVehicleLocation() {
    try {
        // Get current vehicle location from Firebase
        if (currentVehicle.deviceId.isNotEmpty()) {
            firebaseDb.reference.child("vehicles").child(currentVehicle.deviceId).child("location")
                .get().addOnSuccessListener { snapshot ->
                    val lat = snapshot.child("latitude").getValue(Double::class.java)
                    val lng = snapshot.child("longitude").getValue(Double::class.java)
                    
                    if (lat != null && lng != null) {
                        // Open Google Maps with vehicle location
                        val intent = Intent(Intent.ACTION_VIEW, 
                            android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(Vehicle Location)"))
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Vehicle location not available", Toast.LENGTH_SHORT).show()
                    }
                }
        } else {
            Toast.makeText(this, "No vehicle location available", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error opening vehicle location", e)
        Toast.makeText(this, "Could not open vehicle location", Toast.LENGTH_SHORT).show()
    }
}

private fun openEmergencyDialer() {
    try {
        val intent = Intent(Intent.ACTION_DIAL)
        // You can set a specific emergency number here
        // intent.data = android.net.Uri.parse("tel:911")
        startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "Error opening dialer", e)
        Toast.makeText(this, "Could not open dialer", Toast.LENGTH_SHORT).show()
    }
}

// Alternative: Simple version without extra buttons
private fun showSimpleAlertDialog(title: String, message: String, isUrgent: Boolean) {
    runOnUiThread {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
        
        if (isUrgent) {
            builder.setIcon(android.R.drawable.ic_dialog_alert)
        } else {
            builder.setIcon(android.R.drawable.ic_dialog_info)
        }
        
        val dialog = builder.create()
        
        // Make urgent dialogs wake up the screen
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