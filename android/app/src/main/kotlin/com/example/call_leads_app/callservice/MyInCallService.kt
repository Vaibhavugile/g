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
            val normalized = normalizeNumber(phone) ?: phone

            // Try to reuse existing mapping, otherwise create one
            val prefs = applicationContext.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
            val existing = normalized?.let { prefs.getString("callid_$it", null) }
            val callId = existing ?: ensureCallIdForPhone(normalized ?: phone, prefs)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId marker from InCallService for ${normalized ?: phone} -> $existing")
            } else {
                Log.d(TAG, "Persisted callId marker from InCallService for ${normalized ?: phone} -> $callId")
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
            val normalized = normalizeNumber(phone) ?: phone

            val prefs = applicationContext.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
            val existing = if (!normalized.isNullOrEmpty()) prefs.getString("callid_$normalized", null) else null
            val callId = existing ?: ensureCallIdForPhone(normalized ?: phone, prefs)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId marker on callRemoved for ${normalized ?: phone} -> $existing")
            } else {
                Log.d(TAG, "Created callId marker on callRemoved for ${normalized ?: phone} -> $callId")
            }

            // ensure reverse mapping exists for callId
            if (!callId.isNullOrEmpty() && !normalized.isNullOrEmpty()) {
                try {
                    prefs.edit().putString("callid_to_phone_$callId", normalized).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to ensure reverse mapping on callRemoved: ${e.localizedMessage}")
                }
            }

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

    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits
    }

    private fun ensureCallIdForPhone(phoneDigitsOrRaw: String?, prefs: android.content.SharedPreferences): String {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            if (normalized.isNullOrEmpty()) return generateCallId()
            val existing = prefs.getString("callid_$normalized", null)
            if (!existing.isNullOrEmpty()) return existing

            val newId = generateCallId()
            prefs.edit()
                .putString("callid_$normalized", newId)
                .putLong("callid_ts_$normalized", System.currentTimeMillis())
                .putString("callid_to_phone_$newId", normalized)
                .apply()
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        // fallback
        return generateCallId()
    }
}