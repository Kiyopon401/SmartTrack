package com.example.smarttrackapp.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_history")
data class TripHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val eventType: String,
    val accuracy: Double = 0.0,
    val speed: Double = 0.0,
    val batteryLevel: Int? = null,
    val address: String? = null,
    val isSynced: Boolean = false
)