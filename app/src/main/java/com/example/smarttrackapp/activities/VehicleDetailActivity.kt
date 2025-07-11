package com.example.smarttrackapp.activities

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smarttrackapp.ViewModels.VehicleDetailViewModel
import com.example.smarttrackapp.di.ViewModelFactory
import com.example.smarttrackapp.models.Vehicle
import com.example.smarttrackapp.services.LocationTrackingService
import com.example.smarttrackapp.utils.MapUtils
import com.example.smarttrackapp.utils.SMSUtils
import com.google.android.gms.location.*
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.annotation.RequiresApi
import com.example.smarttrackapp.databinding.ActivityVehicleDetailBinding
import com.example.smarttrackapp.App
import com.example.smarttrackapp.R
import com.example.smarttrackapp.utils.FirebaseManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import android.app.AlertDialog


class VehicleDetailActivity : AppCompatActivity() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_LOCATION_PERMISSION = 100
        private const val SMS_PERMISSION_REQUEST_CODE = 1002
        private const val TAG = "VehicleDetailActivity"
        private const val FIREBASE_TIMEOUT_MS = 5000L
        private const val MIN_UPDATE_INTERVAL_MS = 5000L // 5 seconds minimum between updates
        private const val MIN_DISTANCE_METERS = 10f // Minimum distance between points
        private const val LOCATION_UPDATE_INTERVAL = 10000L // 10 seconds
        private const val FASTEST_UPDATE_INTERVAL = 15000L // 15 seconds
        private const val MAX_WAIT_TIME = 30000L // 30 seconds
    }

    private lateinit var binding: ActivityVehicleDetailBinding
    private lateinit var currentVehicle: Vehicle
    private val firebaseDb = Firebase.database
    private var firebaseListener: ValueEventListener? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isContinuousTracking = false
    private var geofenceRadiusMeters = 100.0
    private var hasPlayedAlert = false
    private var isGeofenceEnabled = false
    private var geofenceLat: Double? = null
    private var geofenceLng: Double? = null
    private var locationListener: ValueEventListener? = null
    private var isFirebaseConnected = false
    private var lastFirebaseUpdateTime = 0L
    private var lastUpdateTime = 0L
    private var lastLocation: Location? = null
    private val locationBuffer = mutableListOf<Pair<Double, Double>>()
    private var lastUIUpdateTime = 0L // Add this for UI throttling
    private val UI_UPDATE_THROTTLE_MS = 1000L // 1 second throttle for UI updates
    // Add a throttle for map/UI updates
    private var lastMapUpdateTime = 0L
    private val MAP_UPDATE_THROTTLE_MS = 1000L // 1 second

    private fun throttledMapUpdate(updateBlock: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastMapUpdateTime >= MAP_UPDATE_THROTTLE_MS) {
            lastMapUpdateTime = now
            updateBlock()
        }
    }

    private val viewModel: VehicleDetailViewModel by viewModels(factoryProducer = {
        val vehicleId = intent.getLongExtra("vehicle_id", -1L).takeIf { it != -1L }
            ?: throw IllegalStateException("Missing vehicle_id")
        ViewModelFactory(
            App.database.vehicleDao(),
            App.database.tripHistoryDao(),
            vehicleId
        )
    })

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                if (checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    startLocationUpdates()
                    if (binding.switchGeofence.isChecked) {
                        setupGeofence()
                    }
                    currentVehicle.id.let { startLocationService(it) }
                }
            }
            else -> {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                binding.switchGeofence.isChecked = false
            }
        }
    }

    private val smsPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "SMS permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "SMS permission denied - some features may not work",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val vehicleId = intent.getLongExtra("vehicle_id", -1L)
        if (vehicleId == -1L) {
            finish()
            return
        }

        MapUtils.initializeMap(
            binding.mapWebView,
            null,
            0.0,
            0.0,
            rawResId = R.raw.map_live_tracking
        )

        // In onCreate, set the SeekBar range and default for geofence radius
        binding.radiusSeekBar.max = 190 // Range: 10 to 200 meters
        binding.radiusSeekBar.progress = 40 // Default: 50 meters
        geofenceRadiusMeters = 50.0

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupObservers(vehicleId)
        setupClickListeners()
        setupSeekBar()
        setupGeofenceToggle()
        setupConnectionStatusChecker()

        if (checkLocationPermissions()) {
            startLocationUpdates()
            startLocationService(vehicleId)
        } else {
            requestLocationPermissions()
        }
    }

    private fun setupObservers(vehicleId: Long) {
        lifecycleScope.launch {
            viewModel.getVehicle(vehicleId).collect { vehicle ->
                vehicle?.let {
                    currentVehicle = it
                    updateConnectionStatus()
                    if (it.deviceId.isNotEmpty()) {
                        setupFirebaseListeners(it.deviceId)
                    } else {
                        showSmsOnlyWarning()
                    }
                }
            }
        }
    }

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
                            MapUtils.updateMapLocation(
                                binding.mapWebView,
                                lat, lng,
                                currentVehicle.nickname,
                                true
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
    }

    private fun setupConnectionStatusChecker() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000) // Check every 5 seconds
                checkFirebaseConnection()
            }
        }
    }

    private fun checkFirebaseConnection() {
        val timeSinceLastUpdate = System.currentTimeMillis() - lastFirebaseUpdateTime
        if (timeSinceLastUpdate > TimeUnit.SECONDS.toMillis(30)) {
            isFirebaseConnected = false
            updateConnectionStatus()
        }
    }

    private fun updateConnectionStatus() {
        runOnUiThread {
            when {
                currentVehicle.deviceId.isEmpty() -> {
                    binding.tvConnectionStatus.text = "No DeviceTrackerCompanion paired"
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                    binding.btnPairDevice.visibility = View.VISIBLE
                    binding.btnPairDevice.text = "Pair DeviceTrackerCompanion"
                }
                isFirebaseConnected -> {
                    binding.tvConnectionStatus.text = "✅ Connected to DeviceTrackerCompanion: ${currentVehicle.deviceId}"
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                    binding.btnPairDevice.visibility = View.VISIBLE
                    binding.btnPairDevice.text = "Change/Unpair Device"
                }
                else -> {
                    binding.tvConnectionStatus.text = "⚠️ DeviceTrackerCompanion offline: ${currentVehicle.deviceId}"
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
                    binding.btnPairDevice.visibility = View.VISIBLE
                    binding.btnPairDevice.text = "Change/Unpair Device"
                }
            }
        }
    }

    private fun showSmsOnlyWarning() {
        Toast.makeText(
            this,
            "No paired device - using SMS only",
            Toast.LENGTH_SHORT
        ).show()
        binding.tvConnectionStatus.text = "No paired device - Using SMS"
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
    }

    private fun sendCommandToCompanion(command: String) {
        if (currentVehicle.deviceId.isNotEmpty()) {
            // Try Firebase first with timeout
            FirebaseManager.sendCommandWithTimeout(
                currentVehicle.deviceId,
                command,
                FIREBASE_TIMEOUT_MS,
                onSuccess = {
                    Log.d(TAG, "Firebase command succeeded: $command")
                    isFirebaseConnected = true
                    updateConnectionStatus()
                },
                onFailure = { e ->
                    Log.e(TAG, "Firebase failed, falling back to SMS", e)
                    isFirebaseConnected = false
                    updateConnectionStatus()
                    sendSMSWithPermissionCheck(currentVehicle.phoneNumber, command)
                }
            )
        } else {
            sendSMSWithPermissionCheck(currentVehicle.phoneNumber, command)
        }
    }

    private fun updateTrackingButtons() {
        binding.btnStartTracking.isEnabled = !isContinuousTracking
        binding.btnStopTracking.isEnabled = isContinuousTracking
        binding.btnStartTracking.alpha = if (isContinuousTracking) 0.5f else 1.0f
        binding.btnStopTracking.alpha = if (isContinuousTracking) 1.0f else 0.5f
    }

    private fun setupClickListeners() {
        binding.switchImmobilize.setOnCheckedChangeListener { _, isChecked ->
            if (!::currentVehicle.isInitialized) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                viewModel.updateImmobilizationStatus(currentVehicle.id, isChecked)
                sendCommandToCompanion(if (isChecked) "IMMOBILIZE" else "UNLOCK")
            }
        }

        binding.btnTrackNow.setOnClickListener {
            sendCommandToCompanion("TRACK")
        }

        binding.btnStartTracking.setOnClickListener {
            if (!isContinuousTracking) {
            isContinuousTracking = true
            sendCommandToCompanion("START_TRACKING")
            Toast.makeText(this, "Continuous tracking started", Toast.LENGTH_SHORT).show()
                updateTrackingButtons()
            }
        }

        binding.btnStopTracking.setOnClickListener {
            if (isContinuousTracking) {
            isContinuousTracking = false
            sendCommandToCompanion("STOP")
                Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
                updateTrackingButtons()
            }
        }

        binding.btnViewHistory.setOnClickListener {
            val intent = Intent(this, TripHistoryActivity::class.java).apply {
                putExtra("vehicle_id", currentVehicle.id)
            }
            startActivity(intent)
        }
        binding.btnPairDevice.setOnClickListener {
            if (currentVehicle.deviceId.isEmpty()) {
                // Show loading state
                binding.btnPairDevice.text = "Searching..."
                binding.btnPairDevice.isEnabled = false
                
                // Fetch device IDs from Firebase and show dialog
                FirebaseManager.fetchAvailableDeviceIds { deviceIds ->
                    runOnUiThread {
                        binding.btnPairDevice.text = "Pair DeviceTrackerCompanion"
                        binding.btnPairDevice.isEnabled = true
                        
                        Log.d(TAG, "Found ${deviceIds.size} available devices: $deviceIds")
                        
                        if (deviceIds.isEmpty()) {
                            showNoDevicesDialog()
                            return@runOnUiThread
                        }
                        showDeviceSelectionDialog(deviceIds)
                    }
                }
                
                // Add timeout to prevent hanging
                Handler(Looper.getMainLooper()).postDelayed({
                    if (binding.btnPairDevice.text == "Searching...") {
                        binding.btnPairDevice.text = "Pair DeviceTrackerCompanion"
                        binding.btnPairDevice.isEnabled = true
                        Toast.makeText(this, "Device search timed out. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                }, 10000) // 10 second timeout
            } else {
                showUnpairDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateTrackingButtons()
    }

    private fun sendSMSWithPermissionCheck(phoneNumber: String, message: String) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            SMSUtils.sendSMS(this, phoneNumber, message)
        } else {
            smsPermissionRequest.launch(Manifest.permission.SEND_SMS)
            Toast.makeText(
                this,
                "SMS permission required to send commands",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupSeekBar() {
        binding.radiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = (progress + 10) // Range: 10 to 200 meters
                geofenceRadiusMeters = radius.toDouble()
                binding.geofenceRadiusValue.text = "$radius m"
                binding.mapWebView.evaluateJavascript(
                    "setGeofenceRadius($geofenceRadiusMeters);",
                    null
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Set initial value
        binding.geofenceRadiusValue.text = "50 m"
    }

    private fun setupGeofenceToggle() {
        binding.switchGeofence.setOnCheckedChangeListener { _, isChecked ->
            isGeofenceEnabled = isChecked
            if (isChecked) {
                if (checkLocationPermissions()) {
                    setupGeofence()
                } else {
                    binding.switchGeofence.isChecked = false
                    requestLocationPermissions()
                }
            } else {
                MapUtils.removeGeofenceCircle(binding.mapWebView)
                binding.geofenceAlert.text = ""
            }
        }
    }

    private fun setupGeofence() {
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocationPermissions()
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    geofenceLat = location.latitude
                    geofenceLng = location.longitude
                    MapUtils.drawGeofenceCircle(
                        binding.mapWebView,
                        geofenceLat!!,
                        geofenceLng!!,
                        geofenceRadiusMeters
                    )
                    // Immediately check and display geofence status
                    try {
                        lastLocation?.let { loc ->
                            val results = FloatArray(1)
                            Location.distanceBetween(
                                geofenceLat!!, geofenceLng!!,
                                loc.latitude, loc.longitude,
                                results
                            )
                            val isOutside = results[0] > geofenceRadiusMeters
                            val color = if (isOutside) "red" else "green"
                            MapUtils.drawGeofenceCircle(binding.mapWebView, geofenceLat!!, geofenceLng!!, geofenceRadiusMeters, color)
                            if (isOutside) {
                                val message = "🚨 Alert: Your vehicle '${currentVehicle.nickname}' has left the virtual area!"
                                Log.w(TAG, "Geofence alarm triggered (setup): $message")
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                binding.geofenceAlert.text = message
                            } else {
                                binding.geofenceAlert.text = "✅ Vehicle is within virtual area"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in geofence setup check", e)
                    }
                    Toast.makeText(
                        this,
                        "Geofence set at current location",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Location is currently unavailable",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.switchGeofence.isChecked = false
                }
            }.addOnFailureListener {
                Toast.makeText(
                    this,
                    "Failed to get location: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
                binding.switchGeofence.isChecked = false
            }
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Location permission required",
                Toast.LENGTH_SHORT
            ).show()
            binding.switchGeofence.isChecked = false
        }
    }

    // The following function is now disabled. Tracking is handled by DeviceTrackerCompanion.
    private fun startLocationService(vehicleId: Long) {
        // No-op. Tracking is now handled by DeviceTrackerCompanion.
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showForegroundServiceNotification(vehicleId: Long) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VehicleDetailActivity::class.java).apply {
                putExtra("vehicle_id", vehicleId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "service_channel")
            .setContentTitle("Location Tracking Required")
            .setContentText("Tap to enable vehicle tracking")
            .setSmallIcon(R.drawable.ic_track)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates() {
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, // Changed from HIGH_ACCURACY
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            setWaitForAccurateLocation(true)
            setMaxUpdateDelayMillis(MAX_WAIT_TIME)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val now = System.currentTimeMillis()
                val currentLocation = result.lastLocation ?: return

                // Throttle updates by time and distance
                if (now - lastUpdateTime < MIN_UPDATE_INTERVAL_MS &&
                    lastLocation?.distanceTo(currentLocation) ?: Float.MAX_VALUE < MIN_DISTANCE_METERS) {
                    return
                }

                lastUpdateTime = now
                lastLocation = currentLocation

                // Process the location on a background thread
                lifecycleScope.launch(Dispatchers.IO) {
                    updateLocationUI(currentLocation.latitude, currentLocation.longitude)
                }
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            } else {
                requestLocationPermissions()
            }
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Location permission required",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateLocationUI(lat: Double, lng: Double) {
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
            return
        }

        if (!::currentVehicle.isInitialized) {
            Log.w("VehicleDetail", "Vehicle not initialized yet")
            return
        }

        // Update UI on main thread
        runOnUiThread {
            throttledMapUpdate {
            binding.lastLocation.text = "Lat: ${"%.6f".format(lat)}, Lng: ${"%.6f".format(lng)}"

            // Check geofence status
            try {
                if (isGeofenceEnabled && geofenceLat != null && geofenceLng != null) {
                val results = FloatArray(1)
                    Location.distanceBetween(geofenceLat!!, geofenceLng!!, lat, lng, results)
                    val isOutside = results[0] > geofenceRadiusMeters
                    val color = if (isOutside) "red" else "green"
                    MapUtils.drawGeofenceCircle(binding.mapWebView, geofenceLat!!, geofenceLng!!, geofenceRadiusMeters, color)
                    if (isOutside && !hasPlayedAlert) {
                        val message = "🚨 Alert: Your vehicle '${currentVehicle.nickname}' has left the virtual area!"
                        Log.w(TAG, "Geofence alarm triggered: $message")
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    sendSMSWithPermissionCheck(currentVehicle.phoneNumber, message)
                    binding.geofenceAlert.text = message
                        val mediaPlayer = MediaPlayer.create(this, R.raw.alert_buzzer)
                        mediaPlayer.start()
                        hasPlayedAlert = true
                    } else if (!isOutside) {
                        binding.geofenceAlert.text = "✅ Vehicle is within virtual area"
                        hasPlayedAlert = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in geofence check", e)
            }

            val isInsideGeofence = isInsideGeofence(lat, lng)
            MapUtils.updateMapLocationDebounced(
                binding.mapWebView,
                lat,
                lng,
                currentVehicle.nickname,
                isInsideGeofence
            )
            }
        }

        // Buffer locations and save in batches
        synchronized(locationBuffer) {
            locationBuffer.add(Pair(lat, lng))
            if (locationBuffer.size >= 10) { // Save every 10 points
                viewModel.saveTripPoints(currentVehicle.id, locationBuffer.toList())
                locationBuffer.clear()
            }
        }
    }

    private fun checkSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isInsideGeofence(currentLat: Double, currentLng: Double): Boolean {
        if (!isGeofenceEnabled || geofenceLat == null || geofenceLng == null) return true

        return try {
            val results = FloatArray(1)
            Location.distanceBetween(
                geofenceLat!!, geofenceLng!!,
                currentLat, currentLng,
                results
            )
            results[0] <= geofenceRadiusMeters
        } catch (e: SecurityException) {
            true
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkLocationPermissions(): Boolean {
        return checkPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    fun listenForLocationUpdates(vehicleId: String, callback: (lat: Double, lng: Double) -> Unit) {
        locationListener = firebaseDb.reference.child("vehicles").child(vehicleId).child("location")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lat = snapshot.child("latitude").getValue(Double::class.java)
                    val lng = snapshot.child("longitude").getValue(Double::class.java)
                    if (lat != null && lng != null) {
                        callback(lat, lng)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("VehicleDetailVM", "Location listen failed", error.toException())
                }
            })
    }

    override fun onStop() {
        locationListener?.let {
            firebaseDb.reference.removeEventListener(it)
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // Ignore if callback wasn't registered
        }
        MapUtils.clearInitialized(binding.mapWebView)
    }

    private fun showNoDevicesDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Devices Found")
            .setMessage("No DeviceTrackerCompanion devices are currently online. Make sure:\n\n" +
                    "• The companion app is installed and running\n" +
                    "• Location permissions are granted\n" +
                    "• The device has internet connection\n\n" +
                    "Would you like to try again or install the companion app?")
            .setPositiveButton("Try Again") { _, _ ->
                binding.btnPairDevice.performClick()
            }
            .setNeutralButton("Install Companion App") { _, _ ->
                // Open companion app installation (you can add a link to your app store)
                Toast.makeText(this, "Please install DeviceTrackerCompanion app", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeviceSelectionDialog(deviceIds: List<String>) {
        Log.d(TAG, "Showing device selection dialog for ${deviceIds.size} devices")
        
        // Fetch device info for each device
        val deviceInfoList = mutableListOf<Pair<String, Map<String, Any>?>>()
        var loadedCount = 0
        
        deviceIds.forEach { deviceId ->
            Log.d(TAG, "Fetching info for device: $deviceId")
            FirebaseManager.fetchDeviceInfo(deviceId) { deviceInfo ->
                Log.d(TAG, "Received info for device $deviceId: $deviceInfo")
                deviceInfoList.add(deviceId to deviceInfo)
                loadedCount++
                
                if (loadedCount == deviceIds.size) {
                    runOnUiThread {
                        Log.d(TAG, "All device info loaded, showing dialog")
                        showDeviceListDialog(deviceInfoList)
                    }
                }
            }
        }
    }

    private fun showDeviceListDialog(deviceInfoList: List<Pair<String, Map<String, Any>?>>) {
        val items = deviceInfoList.map { (deviceId, info) ->
            val model = info?.get("model") as? String ?: "Unknown"
            val manufacturer = info?.get("manufacturer") as? String ?: "Unknown"
            val lastSeen = info?.get("last_seen") as? Long
            val status = if (info?.get("active") == true) "Online" else "Offline"
            
            val lastSeenText = if (lastSeen != null) {
                val minutesAgo = (System.currentTimeMillis() - lastSeen) / 60000
                "Last seen: ${if (minutesAgo < 1) "Just now" else "$minutesAgo min ago"}"
            } else "Unknown"
            
            "$manufacturer $model\n$deviceId\n$status • $lastSeenText"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select DeviceTrackerCompanion")
            .setItems(items) { _, which ->
                val selectedDeviceId = deviceInfoList[which].first
                pairWithDevice(selectedDeviceId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairWithDevice(deviceId: String) {
        lifecycleScope.launch {
            viewModel.updateDeviceId(currentVehicle.id, deviceId)
            
            // Send pairing command to the companion device
            FirebaseManager.sendCommand(deviceId, "PAIR:${currentVehicle.id}")
            
            Toast.makeText(
                this@VehicleDetailActivity,
                "Paired with device: $deviceId",
                Toast.LENGTH_SHORT
            ).show()
            // Update UI
            currentVehicle = currentVehicle.copy(deviceId = deviceId)
            updateConnectionStatus()
        }
    }

    private fun showUnpairDialog() {
        AlertDialog.Builder(this)
            .setTitle("Unpair Device")
            .setMessage("This vehicle is currently paired with: ${currentVehicle.deviceId}\n\n" +
                    "Would you like to unpair this device?")
            .setPositiveButton("Unpair") { _, _ ->
                lifecycleScope.launch {
                    viewModel.updateDeviceId(currentVehicle.id, "")
                    Toast.makeText(
                        this@VehicleDetailActivity,
                        "Device unpaired successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    currentVehicle = currentVehicle.copy(deviceId = "")
                    updateConnectionStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}