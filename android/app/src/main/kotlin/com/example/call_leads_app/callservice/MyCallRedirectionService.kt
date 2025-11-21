package com.example.call_leads_app.callservice

import android.content.Intent
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class MyCallRedirectionService : CallRedirectionService() {

    private val TAG = "MyCallRedirectionService"
    private val PREFS = "call_leads_prefs"
    private val KEY_LAST_OUTGOING = "last_outgoing_number"
    private val KEY_LAST_OUTGOING_TS = "last_outgoing_ts"

    // Active/recency semantics (keep in sync with CallService)
    private val REUSE_WINDOW_MS = 120_000L            // 2 minutes fallback
    private val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL

    private val CALL_SERVICE_CLASS_NAME = "com.example.call_leads_app.callservice.CallService"

    override fun onPlaceCall(handle: Uri, phoneAccount: PhoneAccountHandle, allowInteractiveResponse: Boolean) {
        try {
            val phoneNumber = handle.schemeSpecificPart
            Log.d(TAG, "onPlaceCall: $phoneNumber")

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

            // normalize first
            val normalized = normalizeNumber(phoneNumber) ?: phoneNumber

            // save outgoing marker
            prefs.edit()
                .putString(KEY_LAST_OUTGOING, normalized)
                .putLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Saved outgoing marker for $normalized")

            // lookup-first: reuse callId if exists (active-or-recent), otherwise create & mark active
            val existing = readActiveOrRecentCallId(this, normalized)
            val callId = existing ?: ensureCallIdForPhone(this, normalized)

            if (existing != null) {
                Log.d(TAG, "Reused existing callId for $normalized -> $existing")
            } else {
                Log.d(TAG, "Saved callId marker for $normalized -> $callId (and reverse mapping)")
            }

            // read tenant and attach if present
            val tenant = try {
                prefs.getString("tenantId", null)
            } catch (e: Exception) {
                null
            }

            val intent = Intent().apply {
                setClassName(packageName, CALL_SERVICE_CLASS_NAME)
                putExtra("event", "outgoing_start")
                putExtra("direction", "outbound")
                putExtra("phoneNumber", normalized)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
                tenant?.let { putExtra("tenantId", it) }
            }

            Log.d(TAG, "Starting CallService for outgoing_start with callId=$callId and tenant=$tenant")
            ContextCompat.startForegroundService(this, intent)

            placeCallUnmodified()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPlaceCall: ${e.localizedMessage}", e)
            cancelCall()
        }
    }

    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits
    }

    // -----------------------
    // CallId lifecycle helpers (active + recent semantics)
    // -----------------------
    private fun markCallActiveForPhone(ctx: android.content.Context, phoneDigitsOrRaw: String, callId: String) {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
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

    private fun readActiveOrRecentCallId(ctx: android.content.Context, phoneDigitsOrRaw: String): String? {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
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

    private fun ensureCallIdForPhone(ctx: android.content.Context, phoneDigitsOrRaw: String?): String {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw ?: return generateCallId()
            val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val existing = prefs.getString("callid_$normalized", null)
            if (!existing.isNullOrEmpty()) return existing

            val newId = generateCallId()
            // mark active (this writes callid_, ts and active_until and reverse mapping)
            markCallActiveForPhone(ctx, normalized, newId)
            Log.d(TAG, "ensureCallIdForPhone created and marked active: $normalized -> $newId")
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        return generateCallId()
    }

    private fun generateCallId(): String {
        return "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
