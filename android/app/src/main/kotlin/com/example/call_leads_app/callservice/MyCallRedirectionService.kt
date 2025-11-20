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

            // lookup-first: reuse callId if exists
            val existing = prefs.getString("callid_$normalized", null)
            val callId = existing ?: ensureCallIdForPhone(normalized, prefs)

            if (existing != null) {
                Log.d(TAG, "Reused existing callId for $normalized -> $existing")
            } else {
                Log.d(TAG, "Saved callId marker for $normalized -> $callId (and reverse mapping)")
            }

            val intent = Intent().apply {
                setClassName(packageName, CALL_SERVICE_CLASS_NAME)
                putExtra("event", "outgoing_start")
                putExtra("direction", "outbound")
                putExtra("phoneNumber", normalized)
                putExtra("callId", callId)
                putExtra("receivedAt", System.currentTimeMillis())
            }

            Log.d(TAG, "Starting CallService for outgoing_start with callId=$callId")
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

    private fun ensureCallIdForPhone(phoneDigitsOrRaw: String?, prefs: android.content.SharedPreferences): String {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw ?: return generateCallId()
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
        return generateCallId()
    }

    private fun generateCallId(): String {
        return "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}