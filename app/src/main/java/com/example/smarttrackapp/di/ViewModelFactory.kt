package com.example.smarttrackapp.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.smarttrackapp.ViewModels.VehicleDetailViewModel
import com.example.smarttrackapp.ViewModels.VehicleViewModel
import com.example.smarttrackapp.data.TripHistoryDao
import com.example.smarttrackapp.data.VehicleDao

class ViewModelFactory(
    private val vehicleDao: VehicleDao,
    private val tripHistoryDao: TripHistoryDao,
    private val vehicleId: Long? = null // Make vehicleId optional
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(VehicleViewModel::class.java) -> {
                VehicleViewModel(vehicleDao) as T
            }
            modelClass.isAssignableFrom(VehicleDetailViewModel::class.java) -> {
                VehicleDetailViewModel(
                    vehicleDao,
                    tripHistoryDao,
                    vehicleId ?: throw IllegalArgumentException("Vehicle ID required for VehicleDetailViewModel")
                ) as T
            }
            else -> {
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}