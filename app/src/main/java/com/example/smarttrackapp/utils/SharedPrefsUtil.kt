package com.example.smarttrackapp.utils

import android.content.Context
import android.content.SharedPreferences

object SharedPrefsUtil {
    private const val PREFS_NAME = "smart_track_prefs"
    private const val KEY_VEHICLE_ID = "current_vehicle_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentVehicleId(context: Context): Long {
        return getPrefs(context).getLong(KEY_VEHICLE_ID, -1L)
    }

    fun setCurrentVehicleId(context: Context, vehicleId: Long) {
        getPrefs(context).edit().putLong(KEY_VEHICLE_ID, vehicleId).apply()
    }

    fun clearCurrentVehicleId(context: Context) {
        getPrefs(context).edit().remove(KEY_VEHICLE_ID).apply()
    }
}