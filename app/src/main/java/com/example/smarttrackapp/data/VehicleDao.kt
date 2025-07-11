package com.example.smarttrackapp.data

import androidx.room.*
import com.example.smarttrackapp.models.Vehicle
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vehicle: Vehicle): Long

    @Update
    suspend fun update(vehicle: Vehicle)

    @Delete
    suspend fun delete(vehicle: Vehicle)

    @Query("SELECT * FROM vehicles ORDER BY nickname ASC")
    fun getAllVehicles(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun getVehicleById(id: Long): Flow<Vehicle?>

    @Query("SELECT * FROM vehicles WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getVehicleByPhoneNumber(phoneNumber: String): Vehicle?

    @Query("UPDATE vehicles SET lastLatitude = :lat, lastLongitude = :lng, lastUpdate = :timestamp WHERE id = :vehicleId")
    suspend fun updateVehicleLocation(vehicleId: Long, lat: Double, lng: Double, timestamp: String)

    @Query("""
        UPDATE vehicles 
        SET lastLatitude = :latitude, 
            lastLongitude = :longitude, 
            lastUpdate = :timestamp 
        WHERE id = :vehicleId
    """)
    suspend fun updateLastLocation(
        vehicleId: Long,
        latitude: Double,
        longitude: Double,
        timestamp: Long
    )

    @Query("SELECT COUNT(*) FROM vehicles")
    suspend fun getVehicleCount(): Int

    @Query("UPDATE vehicles SET isImmobilized = :isImmobilized WHERE id = :vehicleId")
    suspend fun updateImmobilizationStatus(vehicleId: Long, isImmobilized: Boolean)

    @Query("SELECT * FROM vehicles WHERE lastUpdate IS NOT NULL")
    suspend fun getAllVehiclesSync(): List<Vehicle>

    @Query("UPDATE vehicles SET deviceId = :deviceId WHERE id = :vehicleId")
    suspend fun updateDeviceId(vehicleId: Long, deviceId: String)

    @Query("UPDATE vehicles SET isOnline = :isOnline, lastConnection = :timestamp WHERE id = :vehicleId")
    suspend fun updateConnectionStatus(vehicleId: Long, isOnline: Boolean, timestamp: Long)

    @Query("SELECT * FROM vehicles WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getVehicleByDeviceId(deviceId: String): Vehicle?
}