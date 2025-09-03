// Add this test function to your VehicleDetailActivity for testing:

private fun testGeofenceAlerts() {
    if (currentVehicle.deviceId.isNotEmpty()) {
        // Test different alert types
        val testAlerts = listOf(
            "GEOFENCE_EXIT: Vehicle left geofence area",
            "GEOFENCE_ENTER: Vehicle entered geofence area", 
            "TAMPER: Device enclosure opened or cable cut"
        )
        
        testAlerts.forEachIndexed { index, alert ->
            Handler(Looper.getMainLooper()).postDelayed({
                // Simulate receiving an alert from Firebase
                handleTestAlert(alert)
            }, (index + 1) * 3000L) // 3 seconds between each test
        }
    } else {
        Toast.makeText(this, "No device paired for testing", Toast.LENGTH_SHORT).show()
    }
}

private fun handleTestAlert(alert: String) {
    Log.d(TAG, "Testing alert: $alert")
    
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
    }
}

// Add this button to your layout for testing (optional):
// In your setupClickListeners(), add:
binding.btnTestAlerts.setOnClickListener {
    testGeofenceAlerts()
}