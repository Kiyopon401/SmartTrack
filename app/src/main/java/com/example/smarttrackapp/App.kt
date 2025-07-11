package com.example.smarttrackapp

import android.app.Application
import android.util.Log
import com.example.smarttrackapp.data.AppDatabase
import com.example.smarttrackapp.utils.FirebaseManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ktx.Firebase

class App : Application() {
    companion object {
        lateinit var database: AppDatabase
            private set
        private const val TAG = "AppInitialization"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize database first
        database = AppDatabase.getDatabase(this)

        // Initialize Firebase before anything else
        initializeFirebase()

        // Initialize FirebaseManager to ensure persistence is set
        FirebaseManager.initialize(this)
    }

    private fun initializeFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d(TAG, "Firebase initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Standard Firebase init failed", e)
            initializeFirebaseManually()
        }
    }

    private fun initializeFirebaseManually() {
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:773561398800:android:1116056605d5f5052b7c95")
                .setApiKey("AIzaSyBU5ZOEzGabKDKfBgL0PxmcNNdu10qcRC0")
                .setDatabaseUrl("https://smarttrackbackup-d19e8-default-rtdb.asia-southeast1.firebasedatabase.app")
                .setProjectId("smarttrackbackup-d19e8")
                .build()

            FirebaseApp.initializeApp(this, options, "secondary")
            Log.d(TAG, "Firebase manual initialization successful")
        } catch (e: Exception) {
            Log.e(TAG, "Manual Firebase initialization failed", e)
        }
    }
}