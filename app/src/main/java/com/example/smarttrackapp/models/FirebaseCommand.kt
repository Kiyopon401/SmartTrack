package com.example.smarttrackapp.models

data class FirebaseCommand(
    val type: String, // "TRACK", "START", "STOP", etc.
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending" // "pending", "processed", "failed"
)