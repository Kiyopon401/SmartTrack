package com.example.smarttrackapp.utils

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.smarttrackapp.models.Vehicle
import com.google.firebase.database.FirebaseDatabase

object SMSUtils {
    const val SMS_PERMISSION_REQUEST_CODE = 101
    private const val COMMAND_PREFIX = "PIN:1234:"
    private val approvedNumbers = listOf("+639391233132", "+639632861017","+639512590117","+639078585501")

    fun checkSmsPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestSmsPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.SEND_SMS),
            SMS_PERMISSION_REQUEST_CODE
        )
    }

    fun sendSMS(activity: Activity, phoneNumber: String, message: String) {
        if (!checkSmsPermission(activity)) {
            requestSmsPermission(activity)
            return
        }

        if (!isApprovedNumber(phoneNumber)) {
            Log.w("SMSUtils", "Attempt to send SMS to unapproved number: $phoneNumber")
            return
        }

        try {
            SmsManager.getDefault().sendTextMessage(
                phoneNumber,
                null,
                message,
                null,
                null
            )
            Log.d("SMSUtils", "SMS sent to $phoneNumber: $message")
        } catch (e: Exception) {
            Log.e("SMSUtils", "Failed to send SMS", e)
            throw e
        }
    }

    fun sendHybridCommand(activity: Activity, vehicle: Vehicle, command: String) {
        if (vehicle.deviceId.isNotEmpty()) {
            FirebaseManager.sendCommand(
                vehicle.deviceId,
                command,
                onSuccess = {
                    Log.d("SMSUtils", "Command sent via Firebase")
                },
                onFailure = { e ->
                    Log.e("SMSUtils", "Firebase failed, falling back to SMS", e)
                    if (checkSmsPermission(activity)) {
                        sendSmsCommand(activity, vehicle.phoneNumber, command)
                    }
                }
            )
        } else {
            if (checkSmsPermission(activity)) {
                sendSmsCommand(activity, vehicle.phoneNumber, command)
            }
        }
    }

    private fun sendSmsCommand(activity: Activity, phoneNumber: String, command: String) {
        if (!isApprovedNumber(phoneNumber)) {
            Log.w("SMSUtils", "Sending to unapproved number blocked")
            return
        }

        try {
            SmsManager.getDefault().sendTextMessage(
                phoneNumber,
                null,
                "$COMMAND_PREFIX$command",
                null,
                null
            )
            Log.d("SMSUtils", "SMS command sent: $command")
        } catch (e: Exception) {
            Log.e("SMSUtils", "SMS command failed", e)
            throw e
        }
    }

    private fun isApprovedNumber(number: String): Boolean {
        return approvedNumbers.any { approved ->
            number.endsWith(approved) || number.endsWith(approved.replace("+", ""))
        }
    }
}