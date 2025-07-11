package com.example.smarttrackapp.ViewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttrackapp.data.VehicleDao
import com.example.smarttrackapp.models.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class VehicleViewModel(private val vehicleDao: VehicleDao) : ViewModel() {
    val allVehicles: Flow<List<Vehicle>> = vehicleDao.getAllVehicles()

    fun insertVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            vehicleDao.insert(vehicle)
        }
    }

    fun updateVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            vehicleDao.update(vehicle)
        }
    }

    fun deleteVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            vehicleDao.delete(vehicle)
        }
    }

    suspend fun getVehicleCount(): Int {
        return vehicleDao.getVehicleCount()
    }
}