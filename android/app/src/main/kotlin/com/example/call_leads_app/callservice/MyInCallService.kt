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
    private val PREFS = "call_leads_prefs"

    // Active/recency semantics
    private val REUSE_WINDOW_MS = 120_000L            // 2 minutes fallback
    private val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL

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
            Log.e(TAG, "Error registering callback: ${e.localizedMessage}", e)
        }

        try {
            val handle = call.details?.handle
            val phone = handle?.schemeSpecificPart
            val normalized = normalizeNumber(phone) ?: phone

            val existing = normalized?.let { readActiveOrRecentCallId(applicationContext, it) }
            val callId = existing ?: ensureCallIdForPhone(applicationContext, normalized ?: phone)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId for inbound ring: $normalized -> $existing")
            } else {
                Log.d(TAG, "Created new callId for inbound ring: $normalized -> $callId")
            }

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val tenant = prefs.getString("tenantId", null)
            val recordingEnabled = prefs.getBoolean("recording_enabled", false)

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ringing")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
                putExtra("recording_enabled", recordingEnabled)
                tenant?.let { putExtra("tenantId", it) }
            }

            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start CallService from InCallService: ${e.localizedMessage}")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle onCallAdded: ${e.localizedMessage}")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: ${call.details?.handle}")

        try {
            call.unregisterCallback(callCallback)
        } catch (_: Exception) {}

        try {
            val handle = call.details?.handle
            val phone = handle?.schemeSpecificPart
            val normalized = normalizeNumber(phone) ?: phone

            val existing = if (!normalized.isNullOrEmpty())
                readActiveOrRecentCallId(applicationContext, normalized)
            else null

            val callId = existing ?: ensureCallIdForPhone(applicationContext, normalized ?: phone)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId for ended inbound call: $normalized -> $existing")
            } else {
                Log.d(TAG, "Created callId for ended inbound call: $normalized -> $callId")
            }

            // Ensure reverse phone mapping is saved
            if (!callId.isNullOrEmpty() && !normalized.isNullOrEmpty()) {
                try {
                    val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putString("callid_to_phone_$callId", normalized).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reverse mapping on callRemoved: ${e.localizedMessage}")
                }
            }

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val tenant = prefs.getString("tenantId", null)
            val recordingEnabled = prefs.getBoolean("recording_enabled", false)

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ended")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
                putExtra("recording_enabled", recordingEnabled)
                tenant?.let { putExtra("tenantId", it) }
            }

            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start CallService for ended: ${e.localizedMessage}")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle onCallRemoved: ${e.localizedMessage}")
        }
    }

    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits
    }

    private fun generateCallId(): String =
        "call_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun ensureCallIdForPhone(ctx: Context, phone: String?): String {
        try {
            val normalized = normalizeNumber(phone) ?: phone ?: return generateCallId()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            prefs.getString("callid_$normalized", null)?.let { return it }

            val newId = generateCallId()
            markCallActiveForPhone(ctx, normalized, newId)
            return newId
        } catch (_: Exception) {}
        return generateCallId()
    }

    private fun readActiveOrRecentCallId(ctx: Context, phoneRaw: String): String? {
        return try {
            val normalized = normalizeNumber(phoneRaw) ?: phoneRaw
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString("callid_$normalized", null) ?: return null
            val now = System.currentTimeMillis()

            val activeUntil = prefs.getLong("callid_active_until_$normalized", 0L)
            if (activeUntil > now) return id

            val ts = prefs.getLong("callid_ts_$normalized", 0L)
            if (ts != 0L && (now - ts) <= REUSE_WINDOW_MS) return id

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun markCallActiveForPhone(ctx: Context, phone: String, callId: String) {
        try {
            val normalized = normalizeNumber(phone) ?: phone
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            prefs.edit()
                .putString("callid_$normalized", callId)
                .putLong("callid_ts_$normalized", now)
                .putLong("callid_active_until_$normalized", now + ACTIVE_CALL_TTL_MS)
                .putString("callid_to_phone_$callId", normalized)
                .apply()

        } catch (_: Exception) {}
    }
}
