package com.example.smarttrackapp.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.smarttrackapp.databinding.ActivityRegisterVehicleBinding
import com.example.smarttrackapp.ViewModels.VehicleDetailViewModel
import com.example.smarttrackapp.ViewModels.VehicleViewModel
import com.example.smarttrackapp.models.Vehicle
import com.google.android.material.snackbar.Snackbar
import com.example.smarttrackapp.di.ViewModelFactory
import com.example.smarttrackapp.App

class RegisterVehicleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterVehicleBinding
    private val vehicleViewModel: VehicleViewModel by viewModels {
        ViewModelFactory(
            App.database.vehicleDao(),
            App.database.tripHistoryDao()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterVehicleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener {
            val nickname = binding.etNickname.text.toString().trim()
            val phoneNumber = binding.etPhoneNumber.text.toString().trim()
            val color = "blue"

            if (nickname.isEmpty() || phoneNumber.isEmpty()) {
                Snackbar.make(binding.root, "Please fill all fields", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!phoneNumber.startsWith("+") || phoneNumber.length < 10) {
                Snackbar.make(binding.root, "Invalid phone number format", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val vehicle = Vehicle(
                nickname = nickname,
                phoneNumber = phoneNumber,
                color = color,
                icon = "car"
            )

            vehicleViewModel.insertVehicle(vehicle)
            Snackbar.make(binding.root, "Vehicle saved!", Snackbar.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release any resources if needed (none for now)
    }
}