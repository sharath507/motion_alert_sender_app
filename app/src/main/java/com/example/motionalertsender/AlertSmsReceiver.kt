package com.example.motionalertsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import java.util.regex.Pattern

class AlertSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val from = messages.firstOrNull()?.displayOriginatingAddress ?: ""
        val body = buildString {
            messages.forEach { append(it.messageBody) }
        }

        // Parse lat/lon from a Google Maps URL like: https://www.google.com/maps?q=LAT,LON
        val pattern = Pattern.compile("https?://www\\.google\\.com/maps\\?q=([-0-9.]+),([-0-9.]+)")
        val matcher = pattern.matcher(body)
        if (!matcher.find()) return

        val lat = matcher.group(1)?.toDoubleOrNull() ?: return
        val lon = matcher.group(2)?.toDoubleOrNull() ?: return

        // Parse time line if present: Time: yyyy-MM-dd HH:mm:ss
        var time = ""
        val timePattern = Pattern.compile("Time:\\s*(.+)")
        val timeMatcher = timePattern.matcher(body)
        if (timeMatcher.find()) {
            time = timeMatcher.group(1)?.trim() ?: ""
        }

        // Broadcast internally to MainActivity
        val internal = Intent("com.example.motionalertsender.ALERT_RECEIVED").apply {
            putExtra("lat", lat)
            putExtra("lon", lon)
            putExtra("time", time)
            putExtra("from", from)
        }
        context.sendBroadcast(internal)
    }
}
