package com.example.call_leads_app.callservice

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class MyInCallService : InCallService() {

    private val TAG = "MyInCallService"

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "onStateChanged: state=$state handle=${call.details?.handle}")
            // Optionally forward relevant events to CallService or a MethodChannel
        }

        override fun onDetailsChanged(call: Call, details: Call.Details?) {
            super.onDetailsChanged(call, details)
            Log.d(TAG, "onDetailsChanged: ${call.details?.handle}")
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: ${call.details?.handle}")
        try {
            call.registerCallback(callCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call callback: ${e.localizedMessage}", e)
        }

        // Best-effort: try to forward an initial event to CallService with a callId
        try {
            val handle = call.details?.handle
            val phone = handle?.schemeSpecificPart
            val normalized = phone?.filter { it.isDigit() } ?: phone

            val callId = generateCallId()
            // persist marker
            if (!normalized.isNullOrEmpty()) {
                try {
                    val prefs = applicationContext.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("callid_$normalized", callId).putLong("callid_ts_$normalized", System.currentTimeMillis()).apply()
                    Log.d(TAG, "Persisted callId marker from InCallService for $normalized -> $callId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist callId marker from InCallService: ${e.localizedMessage}")
                }
            }

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ringing")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
            }
            // try start service (defensive - may fail in some contexts)
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (ex: Exception) {
                // ignore; On many OEMs InCallService may run in a context that can't start foreground services.
                Log.w(TAG, "Couldn't start CallService from InCallService: ${ex.localizedMessage}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to forward callAdded to CallService: ${e.localizedMessage}")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: ${call.details?.handle}")
        try {
            call.unregisterCallback(callCallback)
        } catch (ignored: Exception) {
            // defensive: ignore
        }

        // Best-effort: forward final/ended to CallService with callId if possible
        try {
            val handle = call.details?.handle
            val phone = handle?.schemeSpecificPart
            val normalized = phone?.filter { it.isDigit() } ?: phone

            val prefs = applicationContext.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
            val callId = if (!normalized.isNullOrEmpty()) prefs.getString("callid_$normalized", null) else null

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ended")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                if (!callId.isNullOrEmpty()) putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
            }
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (ex: Exception) {
                Log.w(TAG, "Couldn't start CallService for ended event: ${ex.localizedMessage}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to forward callRemoved to CallService: ${e.localizedMessage}")
        }
    }

    private fun generateCallId(): String {
        return "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
