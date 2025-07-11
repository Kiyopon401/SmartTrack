package com.example.smarttrackapp.ViewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttrackapp.data.TripHistoryDao
import com.example.smarttrackapp.models.TripHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TripHistoryViewModel(private val tripHistoryDao: TripHistoryDao) : ViewModel() {
    private val _forceRefresh = MutableStateFlow(false)
    val forceRefresh = _forceRefresh.asStateFlow()

    fun getTripHistory(vehicleId: Long): Flow<List<TripHistory>> {
        return tripHistoryDao.getTripHistoryForVehicle(vehicleId)
    }

    fun refreshTripHistory(vehicleId: Long) {
        viewModelScope.launch {
            // This triggers a refresh by toggling the forceRefresh state
            _forceRefresh.update { !it }
            // Alternatively, you could force a database requery here if needed
            // For Room databases, the Flow will automatically update when data changes
        }
    }

    fun clearHistory(vehicleId: Long) {
        viewModelScope.launch {
            tripHistoryDao.deleteHistoryForVehicle(vehicleId)
        }
    }
}