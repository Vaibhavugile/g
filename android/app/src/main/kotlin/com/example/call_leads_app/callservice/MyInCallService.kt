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

    // Active/recency semantics (keep in sync with CallService)
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
            Log.e(TAG, "Error registering call callback: ${e.localizedMessage}", e)
        }

        // Best-effort: try to forward an initial event to CallService with a callId
        try {
            val handle = call.details?.handle
            val phone = handle?.schemeSpecificPart
            val normalized = normalizeNumber(phone) ?: phone

            // Try to reuse existing mapping (active/recent), otherwise create one and mark active
            val existing = normalized?.let { readActiveOrRecentCallId(applicationContext, it) }
            val callId = existing ?: ensureCallIdForPhone(applicationContext, normalized ?: phone)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId marker from InCallService for ${normalized ?: phone} -> $existing")
            } else {
                Log.d(TAG, "Persisted callId marker from InCallService for ${normalized ?: phone} -> $callId")
            }

            // read tenantId from prefs (if present) and attach to intent
            val tenant = try {
                applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tenantId", null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading tenantId from prefs: ${e.localizedMessage}")
                null
            }

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ringing")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
                tenant?.let { putExtra("tenantId", it) }
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

            val existing = if (!normalized.isNullOrEmpty()) readActiveOrRecentCallId(applicationContext, normalized) else null
            val callId = existing ?: ensureCallIdForPhone(applicationContext, normalized ?: phone)

            if (existing != null) {
                Log.d(TAG, "Reusing existing callId marker on callRemoved for ${normalized ?: phone} -> $existing")
            } else {
                Log.d(TAG, "Created callId marker on callRemoved for ${normalized ?: phone} -> $callId")
            }

            // ensure reverse mapping exists for callId (markCallActiveForPhone already does this when creating)
            if (!callId.isNullOrEmpty() && !normalized.isNullOrEmpty()) {
                try {
                    val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putString("callid_to_phone_$callId", normalized).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to ensure reverse mapping on callRemoved: ${e.localizedMessage}")
                }
            }

            // read tenantId from prefs (if present) and attach to intent
            val tenant = try {
                applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tenantId", null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading tenantId from prefs: ${e.localizedMessage}")
                null
            }

            val intent = Intent(applicationContext, CallService::class.java).apply {
                putExtra("event", "ended")
                putExtra("direction", "inbound")
                putExtra("phoneNumber", normalized ?: phone)
                if (!callId.isNullOrEmpty()) putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
                tenant?.let { putExtra("tenantId", it) }
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

    // -----------------------
    // CallId lifecycle helpers (active & recent semantics)
    // -----------------------
    private fun markCallActiveForPhone(ctx: Context, phoneDigitsOrRaw: String, callId: String) {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("callid_$normalized", callId)
                .putLong("callid_ts_$normalized", now)
                .putLong("callid_active_until_$normalized", now + ACTIVE_CALL_TTL_MS)
                .putString("callid_to_phone_$callId", normalized)
                .apply()
            Log.d(TAG, "Marked call active for $normalized -> $callId until ${now + ACTIVE_CALL_TTL_MS}")
        } catch (e: Exception) {
            Log.w(TAG, "markCallActiveForPhone failed: ${e.localizedMessage}")
        }
    }

    private fun readActiveOrRecentCallId(ctx: Context, phoneDigitsOrRaw: String): String? {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString("callid_$normalized", null) ?: return null
            val now = System.currentTimeMillis()

            val activeUntil = prefs.getLong("callid_active_until_$normalized", 0L)
            if (activeUntil > now) {
                Log.d(TAG, "Reusing ACTIVE callId for $normalized -> $id (activeUntil=$activeUntil)")
                return id
            }

            val ts = prefs.getLong("callid_ts_$normalized", 0L)
            if (ts != 0L && (now - ts) <= REUSE_WINDOW_MS) {
                Log.d(TAG, "Reusing RECENT callId for $normalized -> $id (ts=$ts)")
                return id
            }

            return null
        } catch (e: Exception) {
            Log.w(TAG, "readActiveOrRecentCallId failed: ${e.localizedMessage}")
            return null
        }
    }

    private fun ensureCallIdForPhone(ctx: Context, phoneDigitsOrRaw: String?): String {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw ?: return generateCallId()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString("callid_$normalized", null)
            if (!existing.isNullOrEmpty()) return existing

            val newId = generateCallId()
            // mark active and persist
            markCallActiveForPhone(ctx, normalized, newId)
            Log.d(TAG, "ensureCallIdForPhone created and marked active: $normalized -> $newId")
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        return generateCallId()
    }
}
