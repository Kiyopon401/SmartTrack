package com.example.smarttrackapp.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smarttrackapp.databinding.ActivityMainBinding
import com.example.smarttrackapp.adapters.VehicleAdapter
import com.example.smarttrackapp.di.ViewModelFactory
import com.example.smarttrackapp.models.Vehicle
import com.example.smarttrackapp.utils.SMSUtils
import com.example.smarttrackapp.ViewModels.VehicleViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import com.example.smarttrackapp.App

class MainActivity : AppCompatActivity(), VehicleAdapter.OnVehicleClickListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var vehicleAdapter: VehicleAdapter
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null

    private val vehicleViewModel: VehicleViewModel by viewModels {
        ViewModelFactory(
            App.database.vehicleDao(),
            App.database.tripHistoryDao()
        )
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val SAMPLE_VEHICLE_ID = 1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationUpdates()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        checkLocationPermission()
        if (!SMSUtils.checkSmsPermission(this)) {
            SMSUtils.requestSmsPermission(this)
        }
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                currentLocation = locationResult.lastLocation
            }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun setupRecyclerView() {
        vehicleAdapter = VehicleAdapter(this)
        binding.vehicleRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = vehicleAdapter
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            if (vehicleViewModel.getVehicleCount() == 0) {
                val sampleVehicle = Vehicle(
                    id = SAMPLE_VEHICLE_ID,
                    nickname = "My Tracker",
                    phoneNumber = "09632861017",
                    color = "blue",
                    icon = "car"
                )
                vehicleViewModel.insertVehicle(sampleVehicle)
            }

            vehicleViewModel.allVehicles.collect { vehicles ->
                vehicleAdapter.submitList(vehicles)
            }
        }
    }

    private fun setupClickListeners() {
        binding.fabAddVehicle.setOnClickListener {
            startActivity(Intent(this, RegisterVehicleActivity::class.java))
        }
    }

    override fun onVehicleClick(vehicle: Vehicle) {
        val intent = Intent(this, VehicleDetailActivity::class.java).apply {
            putExtra("vehicle_id", vehicle.id)
            currentLocation?.let {
                putExtra("current_lat", it.latitude)
                putExtra("current_lng", it.longitude)
            }
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocationUpdates()
                }
            }
            SMSUtils.SMS_PERMISSION_REQUEST_CODE -> {
                // Handle SMS permission result
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Release adapter resources if needed
        binding.vehicleRecyclerView.adapter = null
    }
}