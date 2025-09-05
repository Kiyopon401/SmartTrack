package com.example.smarttrack

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.smarttrack.databinding.ActivityVehicleDetailBinding
import com.google.firebase.database.*
import java.util.concurrent.atomic.AtomicBoolean

class VehicleDetailActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VehicleDetailActivity"
        private const val UI_UPDATE_THROTTLE_MS = 2000L // 2 seconds between UI updates
        private const val ALERT_COOLDOWN_MS = 10000L // 10 seconds between same alert types
    }
    
    private lateinit var binding: ActivityVehicleDetailBinding
    private lateinit var viewModel: VehicleDetailViewModel
    private lateinit var firebaseDb: FirebaseDatabase
    private var firebaseListener: ValueEventListener? = null
    private var locationListener: ValueEventListener? = null
    private var alertListener: ValueEventListener? = null
    
    private var isFirebaseConnected = false
    private var lastFirebaseUpdateTime = 0L
    private var lastUIUpdateTime = 0L
    private var currentVehicle: Vehicle? = null
    
    // Alert management - prevent overwhelming
    private val alertCooldowns = mutableMapOf<String, Long>()
    private val isAlertDialogShowing = AtomicBoolean(false)
    private var currentAlertDialog: AlertDialog? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        viewModel = ViewModelProvider(this)[VehicleDetailViewModel::class.java]
        firebaseDb = FirebaseDatabase.getInstance()
        
        setupUI()
        createNotificationChannels()
        
        val deviceId = intent.getStringExtra("deviceId") ?: "tracker_01"
        currentVehicle = Vehicle(id = deviceId, name = "Vehicle $deviceId")
        
        setupFirebaseListeners(deviceId)
    }
    
    private fun setupUI() {
        binding.apply {
            // Initialize UI elements
            geofenceAlert.visibility = View.GONE
            connectionStatus.text = "Connecting..."
        }
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // High priority channel for urgent alerts
            val urgentChannel = NotificationChannel(
                "urgent_alerts",
                "Urgent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts like tamper and geofence exit"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }
            
            // Low priority channel for info alerts
            val infoChannel = NotificationChannel(
                "info_alerts",
                "Info Alerts", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Informational alerts like geofence enter"
                enableLights(false)
                enableVibration(false)
            }
            
            notificationManager.createNotificationChannel(urgentChannel)
            notificationManager.createNotificationChannel(infoChannel)
        }
    }
    
    private fun setupFirebaseListeners(deviceId: String) {
        Log.d(TAG, "Setting up Firebase listeners for deviceId: $deviceId")
        
        // Clean up previous listeners
        firebaseListener?.let { firebaseDb.reference.removeEventListener(it) }
        locationListener?.let { firebaseDb.reference.removeEventListener(it) }
        alertListener?.let { firebaseDb.reference.removeEventListener(it) }
        
        // Device status listener
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
        
        // Location listener
        locationListener = firebaseDb.reference.child("vehicles").child(deviceId).child("location")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        lastFirebaseUpdateTime = System.currentTimeMillis()
                        
                        if (!snapshot.exists() || snapshot.childrenCount > 100) {
                            Log.w(TAG, "Skipping location update: invalid snapshot")
                            return
                        }
                        
                        val lat = snapshot.child("latitude").getValue(Double::class.java)
                        val lng = snapshot.child("longitude").getValue(Double::class.java)
                        val timestamp = snapshot.child("timestamp").getValue(Long::class.java)
                        
                        if (lat != null && lng != null && timestamp != null) {
                            // Throttle UI updates
                            val now = System.currentTimeMillis()
                            if (now - lastUIUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                                lastUIUpdateTime = now
                                updateLocationUI(lat, lng, timestamp)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing location update", e)
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Location listener cancelled", error.toException())
                }
            })
        
        // Alert listener - IMPROVED WITH COOLDOWN
        alertListener = firebaseDb.reference.child("vehicles").child(deviceId).child("alerts")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (!snapshot.exists()) return
                        
                        val alert = snapshot.getValue(String::class.java) ?: return
                        Log.d(TAG, "Alert received: $alert")
                        
                        // Check cooldown to prevent spam
                        val alertType = when {
                            alert.contains("GEOFENCE_EXIT") -> "GEOFENCE_EXIT"
                            alert.contains("GEOFENCE_ENTER") -> "GEOFENCE_ENTER"
                            alert.contains("TAMPER") -> "TAMPER"
                            else -> "UNKNOWN"
                        }
                        
                        val now = System.currentTimeMillis()
                        val lastAlertTime = alertCooldowns[alertType] ?: 0L
                        
                        if (now - lastAlertTime < ALERT_COOLDOWN_MS) {
                            Log.d(TAG, "Alert $alertType ignored due to cooldown")
                            return
                        }
                        
                        alertCooldowns[alertType] = now
                        
                        // Process alert
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
                        
                        // Clear alert from Firebase immediately
                        snapshot.ref.removeValue()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing alert", e)
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Alert listener cancelled", error.toException())
                }
            })
    }
    
    private fun handleGeofenceExitAlert(alert: String) {
        val message = "🚨 Vehicle left geofence area!"
        
        // Update UI
        runOnUiThread {
            binding.geofenceAlert.text = message
            binding.geofenceAlert.visibility = View.VISIBLE
            binding.geofenceAlert.setTextColor(ContextCompat.getColor(this, R.color.red))
        }
        
        // Show message box dialog
        showAlertDialog(
            title = "Geofence Alert",
            message = message,
            isUrgent = true,
            positiveButtonText = "Show Location",
            negativeButtonText = "Dismiss"
        ) { action ->
            when (action) {
                "check" -> {
                    // Show vehicle location on our Mapbox map
                    openGoogleMaps()
                }
                "dismiss" -> {
                    // Just dismiss
                }
            }
        }
        
        // Play alert sound
        playAlertSound()
        
        // Show notification
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
            
            // Auto-hide after 5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                binding.geofenceAlert.visibility = View.GONE
            }, 5000)
        }
        
        // Show simple message box (not urgent)
        showAlertDialog(
            title = "Geofence Alert",
            message = message,
            isUrgent = false,
            positiveButtonText = "OK",
            negativeButtonText = null
        ) { action ->
            // Just dismiss
        }
        
        // Show notification
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
        
        // Show urgent message box dialog
        showAlertDialog(
            title = "TAMPER ALERT",
            message = message,
            isUrgent = true,
            positiveButtonText = "Show Location",
            negativeButtonText = "Call Emergency"
        ) { action ->
            when (action) {
                "check" -> {
                    // Show vehicle location on our Mapbox map
                    openGoogleMaps()
                }
                "emergency" -> {
                    // Open phone dialer
                    openPhoneDialer()
                }
            }
        }
        
        // Play alert sound
        playAlertSound()
        
        // Show notification
        showGeofenceNotification("Tamper Alert", message, true)
        
        Log.w(TAG, "Tamper alert: $alert")
    }
    
    private fun showAlertDialog(
        title: String,
        message: String,
        isUrgent: Boolean,
        positiveButtonText: String?,
        negativeButtonText: String?,
        onAction: (String) -> Unit
    ) {
        // Prevent multiple dialogs
        if (isAlertDialogShowing.get()) {
            Log.d(TAG, "Alert dialog already showing, skipping: $title")
            return
        }
        
        runOnUiThread {
            try {
                // Dismiss any existing dialog
                currentAlertDialog?.dismiss()
                
                val builder = AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(!isUrgent) // Urgent dialogs can't be dismissed by back button
                
                // Add positive button
                positiveButtonText?.let { text ->
                    builder.setPositiveButton(text) { dialog, _ ->
                        isAlertDialogShowing.set(false)
                        currentAlertDialog = null
                        onAction("check")
                        dialog.dismiss()
                    }
                }
                
                // Add negative button
                negativeButtonText?.let { text ->
                    builder.setNegativeButton(text) { dialog, _ ->
                        isAlertDialogShowing.set(false)
                        currentAlertDialog = null
                        onAction(if (text == "Call Emergency") "emergency" else "dismiss")
                        dialog.dismiss()
                    }
                }
                
                // For urgent alerts, wake screen and keep on
                if (isUrgent) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                
                isAlertDialogShowing.set(true)
                currentAlertDialog = builder.create()
                currentAlertDialog?.show()
                
                Log.d(TAG, "Alert dialog shown: $title")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error showing alert dialog", e)
                isAlertDialogShowing.set(false)
                currentAlertDialog = null
            }
        }
    }
    
                    private fun openGoogleMaps() {
                    try {
                        // Since we're using Mapbox, just show the location in our own map
                        val vehicle = currentVehicle
                        if (vehicle != null) {
                            // Get last known location from ViewModel
                            val lastLocation = viewModel.getLastKnownLocation(vehicle.id)
                            if (lastLocation != null) {
                                // Update our Mapbox map to show the vehicle location
                                MapUtils.updateMapLocation(
                                    binding.mapWebView,
                                    lastLocation.first,
                                    lastLocation.second,
                                    currentVehicle.nickname,
                                    true
                                )
                                
                                // Show coordinates in a toast
                                Toast.makeText(
                                    this, 
                                    "Vehicle location: ${String.format("%.6f", lastLocation.first)}, ${String.format("%.6f", lastLocation.second)}", 
                                    Toast.LENGTH_LONG
                                ).show()
                                
                                Log.d(TAG, "Updated Mapbox map to show vehicle location: ${lastLocation.first}, ${lastLocation.second}")
                            } else {
                                Toast.makeText(this, "No location data available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating map location", e)
                        Toast.makeText(this, "Error updating map", Toast.LENGTH_SHORT).show()
                    }
                }
    
    private fun openPhoneDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening phone dialer", e)
        }
    }
    
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
        try {
            val channelId = if (isUrgent) "urgent_alerts" else "info_alerts"
            val notificationId = if (isUrgent) 1 else 2
            
            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(if (isUrgent) androidx.core.app.NotificationCompat.PRIORITY_HIGH else androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
    
    private fun updateLocationUI(lat: Double, lng: Double, timestamp: Long) {
        runOnUiThread {
            binding.apply {
                latitudeValue.text = String.format("%.6f", lat)
                longitudeValue.text = String.format("%.6f", lng)
                lastUpdateValue.text = "Updated: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}"
            }
        }
    }
    
    private fun updateConnectionStatus() {
        runOnUiThread {
            binding.apply {
                if (isFirebaseConnected) {
                    connectionStatus.text = "Connected"
                    connectionStatus.setTextColor(ContextCompat.getColor(this@VehicleDetailActivity, R.color.green))
                } else {
                    connectionStatus.text = "Disconnected"
                    connectionStatus.setTextColor(ContextCompat.getColor(this@VehicleDetailActivity, R.color.red))
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up listeners
        firebaseListener?.let { firebaseDb.reference.removeEventListener(it) }
        locationListener?.let { firebaseDb.reference.removeEventListener(it) }
        alertListener?.let { firebaseDb.reference.removeEventListener(it) }
        
        // Dismiss any open dialogs
        currentAlertDialog?.dismiss()
        
        // Clear screen flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

// Simple Vehicle data class
data class Vehicle(
    val id: String,
    val name: String
)

// Simple ViewModel for location management
class VehicleDetailViewModel : androidx.lifecycle.ViewModel() {
    private val lastKnownLocations = mutableMapOf<String, Pair<Double, Double>>()
    
    fun saveTripPoints(vehicleId: String, points: List<Pair<Double, Double>>) {
        points.lastOrNull()?.let { lastPoint ->
            lastKnownLocations[vehicleId] = lastPoint
        }
    }
    
    fun getLastKnownLocation(vehicleId: String): Pair<Double, Double>? {
        return lastKnownLocations[vehicleId]
    }
}