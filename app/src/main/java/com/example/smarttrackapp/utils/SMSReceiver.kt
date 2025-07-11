package com.example.smarttrackapp.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
// import com.example.smarttrackapp.data.AppDatabase
// import com.example.smarttrackapp.models.TripHistory
// import kotlinx.coroutines.CoroutineScope
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SMSReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in smsMessages) {
                processSmsMessage(context, message)
            }
        }
    }

    private fun processSmsMessage(context: Context, message: SmsMessage) {
        val messageBody = message.messageBody
        val senderPhone = message.displayOriginatingAddress

        when {
            messageBody.startsWith("LOCATION:") -> {
                // Format: LOCATION:vehicleId:lat,lng:timestamp
                // Location updates are now handled only by DeviceTrackerCompanion. No-op.
            }
            messageBody.startsWith("ERROR:") -> {
                // Handle error messages
                val error = messageBody.substringAfter("ERROR:")
                Log.e("SMSReceiver", "Error from companion: $error")
            }
        }
    }

    // saveLocation is now disabled. Location updates should only be handled by DeviceTrackerCompanion.
}