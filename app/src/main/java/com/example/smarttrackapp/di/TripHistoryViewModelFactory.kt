package com.example.smarttrackapp.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.smarttrackapp.ViewModels.TripHistoryViewModel
import com.example.smarttrackapp.data.TripHistoryDao

class TripHistoryViewModelFactory(private val dao: TripHistoryDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TripHistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TripHistoryViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
