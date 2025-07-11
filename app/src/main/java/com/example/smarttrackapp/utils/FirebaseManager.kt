package com.example.smarttrackapp.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object FirebaseManager {
    private lateinit var db: FirebaseDatabase

    fun initialize(context: Context) {
        if (!this::db.isInitialized) {
            db = FirebaseDatabase.getInstance().apply {
                setPersistenceEnabled(true)
                Log.d("FirebaseManager", "Database persistence enabled")
            }
        }
    }

    fun sendCommand(
        deviceId: String,
        command: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        checkInitialized()
        db.reference.child("commands").child(deviceId).setValue(command)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Command send failed", e)
                onFailure(e)
            }
    }

    fun listenForLocation(
        deviceId: String,
        callback: (lat: Double, lng: Double, timestamp: Long) -> Unit
    ) {
        db.reference.child("vehicles").child(deviceId).child("location")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lat = snapshot.child("latitude").getValue(Double::class.java)
                    val lng = snapshot.child("longitude").getValue(Double::class.java)
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java)
                    if (lat != null && lng != null && timestamp != null) {
                        callback(lat, lng, timestamp)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Firebase", "Location listen failed", error.toException())
                }
            })
    }

    fun checkDeviceStatus(deviceId: String, callback: (isOnline: Boolean) -> Unit) {
        db.reference.child("devices").child(deviceId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lastSeen = snapshot.child("last_seen").getValue(Long::class.java)
                    val now = System.currentTimeMillis()
                    if (lastSeen != null) {
                        callback(now - lastSeen < 120_000) // 2 minutes
                    } else {
                        // Fallback to active field
                        callback(snapshot.child("active").getValue(Boolean::class.java) ?: false)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Firebase", "Status listen failed", error.toException())
                }
            })
    }

    fun setDeviceOnlineStatus(deviceId: String, isOnline: Boolean) {
        db.reference.child("devices").child(deviceId).child("active").setValue(isOnline)
    }

    fun sendCommandWithTimeout(
        deviceId: String,
        command: String,
        timeoutMs: Long,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val task = db.reference.child("commands").child(deviceId).setValue(command)
        var isCompleted = false

        // Set up timeout
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!isCompleted) {
                onFailure(Exception("Firebase timeout after ${timeoutMs}ms"))
            }
        }

        task.addOnCompleteListener { task ->
            isCompleted = true
            timeoutHandler.removeCallbacks(timeoutRunnable)
            if (task.isSuccessful) {
                onSuccess()
            } else {
                onFailure(task.exception ?: Exception("Unknown Firebase error"))
            }
        }

        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    fun fetchAvailableDeviceIds(callback: (List<String>) -> Unit) {
        checkInitialized()
        Log.d("FirebaseManager", "Fetching available device IDs...")
        db.reference.child("devices").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val deviceIds = mutableListOf<String>()
                Log.d("FirebaseManager", "Found ${snapshot.childrenCount} total devices")
                
                for (child in snapshot.children) {
                    child.key?.let { deviceId ->
                        // Add companion devices (deviceType = "companion")
                        val deviceType = child.child("deviceType").getValue(String::class.java)
                        val isActive = child.child("active").getValue(Boolean::class.java) ?: false
                        Log.d("FirebaseManager", "Device $deviceId: type=$deviceType, active=$isActive")
                        
                        if (deviceType == "companion" && isActive) {
                            deviceIds.add(deviceId)
                            Log.d("FirebaseManager", "Added companion device: $deviceId")
                        }
                    }
                }
                Log.d("FirebaseManager", "Returning ${deviceIds.size} companion devices: $deviceIds")
                callback(deviceIds)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseManager", "Failed to fetch device IDs", error.toException())
                callback(emptyList())
            }
        })
    }

    fun fetchDeviceInfo(deviceId: String, callback: (Map<String, Any>?) -> Unit) {
        checkInitialized()
        db.reference.child("devices").child(deviceId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val deviceInfo = mutableMapOf<String, Any>()
                    for (child in snapshot.children) {
                        child.getValue()?.let { value ->
                            deviceInfo[child.key!!] = value
                        }
                    }
                    callback(deviceInfo)
                } else {
                    callback(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseManager", "Failed to fetch device info", error.toException())
                callback(null)
            }
        })
    }

    private fun checkInitialized() {
        if (!this::db.isInitialized) {
            throw IllegalStateException("FirebaseManager not initialized. Call initialize() first.")
        }
    }

}