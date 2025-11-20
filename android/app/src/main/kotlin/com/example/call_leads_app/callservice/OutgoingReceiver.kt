package com.example.call_leads_app.callservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class OutgoingReceiver : BroadcastReceiver() {
    private val TAG = "OutgoingReceiver"

    private val PREFS = "call_leads_prefs"
    private val KEY_LAST_OUTGOING = "last_outgoing_number"
    private val KEY_LAST_OUTGOING_TS = "last_outgoing_ts"

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "📞 ACTION_NEW_OUTGOING_CALL received")

        if (context == null || intent == null) {
            Log.e(TAG, "Context or Intent null")
            return
        }

        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)

        if (number.isNullOrEmpty()) {
            Log.w(TAG, "Outgoing Number empty/null. Ignoring.")
            return
        }

        Log.d(TAG, "📞 Outgoing Number (raw): $number")

        // normalize number to digits-only to keep canonical form across components
        val normalized = normalizeNumber(number)
        if (normalized.isNullOrEmpty()) {
            Log.w(TAG, "Normalized outgoing number empty after cleanup. Using raw number instead.")
        } else {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_LAST_OUTGOING, normalized).putLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis()).apply()
                Log.d(TAG, "Saved outgoing marker: $normalized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist outgoing marker: ${e.localizedMessage}", e)
            }
        }

        // create callId and persist callId marker (so other components can find it)
        val callId = generateCallId()
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val markerKey = if (!normalized.isNullOrEmpty()) normalized else number
            prefs.edit()
                .putString("callid_$markerKey", callId)
                .putLong("callid_ts_$markerKey", System.currentTimeMillis())
                .putString("callid_to_phone_$callId", markerKey)
                .apply()
            Log.d(TAG, "Saved callId marker for $markerKey -> $callId (and reverse mapping)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving callId marker: ${e.localizedMessage}")
        }

        val serviceIntent = Intent(context, CallService::class.java).apply {
            putExtra("direction", "outbound")
            // prefer normalized if available, otherwise send raw
            putExtra("phoneNumber", if (!normalized.isNullOrEmpty()) normalized else number)
            putExtra("event", "outgoing_start")
            putExtra("callId", callId)
            putExtra("receivedAt", System.currentTimeMillis())
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallService for outgoing_start: ${e.localizedMessage}", e)
        }
    }

    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits
    }

    private fun generateCallId(): String {
        return "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
