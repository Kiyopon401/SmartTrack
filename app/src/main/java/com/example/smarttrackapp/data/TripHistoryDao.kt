package com.example.smarttrackapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.smarttrackapp.models.TripHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface TripHistoryDao {
    @Insert
    suspend fun insert(tripHistory: TripHistory)

    @Insert
    suspend fun insertBatch(history: List<TripHistory>)

    @Query("SELECT * FROM trip_history WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun getTripHistoryForVehicle(vehicleId: Long): Flow<List<TripHistory>>

    @Query("SELECT * FROM trip_history WHERE vehicleId = :vehicleId AND isSynced = 0")
    suspend fun getUnsyncedTrips(vehicleId: Long): List<TripHistory>

    @Query("UPDATE trip_history SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("DELETE FROM trip_history WHERE vehicleId = :vehicleId")
    suspend fun deleteHistoryForVehicle(vehicleId: Long)

    @Transaction
    @Query("DELETE FROM trip_history WHERE id IN " +
            "(SELECT id FROM trip_history WHERE vehicleId = :vehicleId " +
            "ORDER BY timestamp DESC LIMIT -1 OFFSET 1000)")
    suspend fun trimHistory(vehicleId: Long)
}