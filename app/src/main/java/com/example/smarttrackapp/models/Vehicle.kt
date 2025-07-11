package com.example.smarttrackapp.models

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nickname: String,
    val phoneNumber: String,
    val color: String,
    val icon: String,
    val isImmobilized: Boolean = false,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastUpdate: String? = null,
    val deviceId: String = "",
    val isOnline: Boolean = false,
    val lastConnection: Long = 0L
) : Parcelable {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "nickname" to nickname,
            "phoneNumber" to phoneNumber,
            "color" to color,
            "icon" to icon,
            "isImmobilized" to isImmobilized,
            "lastLatitude" to lastLatitude,
            "lastLongitude" to lastLongitude,
            "lastUpdate" to lastUpdate,
            "deviceId" to deviceId,
            "isOnline" to isOnline,
            "lastConnection" to lastConnection
        )
    }
}