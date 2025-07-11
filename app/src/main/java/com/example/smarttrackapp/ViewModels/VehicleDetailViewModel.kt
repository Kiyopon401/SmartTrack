package com.example.smarttrackapp.ViewModels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttrackapp.data.TripHistoryDao
import com.example.smarttrackapp.data.VehicleDao
import com.example.smarttrackapp.models.TripHistory
import com.example.smarttrackapp.models.Vehicle
import com.google.firebase.Firebase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class VehicleDetailViewModel(
    private val vehicleDao: VehicleDao,
    private val tripHistoryDao: TripHistoryDao,
    private val vehicleId: Long
) : ViewModel() {

    suspend fun updateDeviceId(vehicleId: Long, deviceId: String) {
        vehicleDao.updateDeviceId(vehicleId, deviceId)
    }

    fun listenForVehicleUpdates(vehicleId: Long): Flow<Vehicle?> {
        return vehicleDao.getVehicleById(vehicleId)
    }

    fun setupFirebaseListener(deviceId: String) {
        Firebase.database.reference.child("status").child(deviceId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                    viewModelScope.launch {
                        vehicleDao.updateConnectionStatus(
                            vehicleId,
                            isOnline,
                            System.currentTimeMillis()
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("VehicleDetailVM", "Status listen failed", error.toException())
                }
            })
    }

    fun getVehicle(vehicleId: Long): Flow<Vehicle?> {
        return vehicleDao.getVehicleById(vehicleId)
    }

    fun saveTripPoints(vehicleId: Long, points: List<Pair<Double, Double>>) {
        viewModelScope.launch(Dispatchers.IO) {
            val BATCH_SIZE = 100
            val tripPoints = points.map { (lat, lng) ->
                TripHistory(
                    vehicleId = vehicleId,
                    latitude = lat,
                    longitude = lng,
                    timestamp = System.currentTimeMillis(),
                    eventType = "LOCATION_UPDATE"
                )
            }
            // Insert in batches
            tripPoints.chunked(BATCH_SIZE).forEach { batch ->
                tripHistoryDao.insertBatch(batch)
            }
            // Update with the last known location
            points.lastOrNull()?.let { (lat, lng) ->
                vehicleDao.updateVehicleLocation(
                    vehicleId,
                    lat,
                    lng,
                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
                )
            }
        }
    }

    suspend fun updateImmobilizationStatus(vehicleId: Long, isImmobilized: Boolean) {
        vehicleDao.updateImmobilizationStatus(vehicleId, isImmobilized)
    }

    fun getTripHistory(vehicleId: Long): Flow<List<TripHistory>> {
        return tripHistoryDao.getTripHistoryForVehicle(vehicleId)
    }


}