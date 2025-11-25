package com.example.call_leads_app.callservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

class OutgoingReceiver : BroadcastReceiver() {

    private val TAG = "OutgoingReceiver"

    private val PREFS = "call_leads_prefs"
    private val KEY_LAST_OUTGOING = "last_outgoing_number"
    private val KEY_LAST_OUTGOING_TS = "last_outgoing_ts"

    private val REUSE_WINDOW_MS = 120_000L            // 2 minutes reuse window
    private val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "📞 ACTION_NEW_OUTGOING_CALL received")

        if (context == null || intent == null) {
            Log.e(TAG, "Context or Intent null")
            return
        }

        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
        if (number.isNullOrEmpty()) {
            Log.w(TAG, "Outgoing number empty/null")
            return
        }

        Log.d(TAG, "📞 Outgoing raw number: $number")

        val normalized = normalizeNumber(number)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Save outgoing marker
        if (!normalized.isNullOrEmpty()) {
            prefs.edit()
                .putString(KEY_LAST_OUTGOING, normalized)
                .putLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                .apply()

            Log.d(TAG, "Saved outgoing marker: $normalized")
        }

        // Load tenantId + recording flag
        val tenantId = prefs.getString("tenantId", null)
        val recordingEnabled = prefs.getBoolean("recording_enabled", false)

        if (tenantId != null) Log.d(TAG, "OutgoingReceiver: tenantId=$tenantId")
        Log.d(TAG, "OutgoingReceiver: recordingEnabled=$recordingEnabled")

        val lookupKey = normalized ?: number

        // try to reuse active or recent callId
        val existing = readActiveOrRecentCallId(context, lookupKey)
        val callId = existing ?: ensureCallIdForPhone(context, lookupKey)

        if (existing != null)
            Log.d(TAG, "Reusing callId for outgoing: $lookupKey -> $existing")
        else
            Log.d(TAG, "Created new callId for outgoing: $lookupKey -> $callId")

        val svcIntent = Intent(context, CallService::class.java).apply {
            putExtra("event", "outgoing_start")
            putExtra("direction", "outbound")
            putExtra("phoneNumber", lookupKey)
            putExtra("callId", callId)
            putExtra("receivedAt", System.currentTimeMillis())
            putExtra("recording_enabled", recordingEnabled)
            tenantId?.let { putExtra("tenantId", it) }
        }

        // === TRY FOREGROUND START ===
        try {
            ContextCompat.startForegroundService(context, svcIntent)
            return
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed (${e.localizedMessage}) — falling back to EnqueueEventWorker", e)
        }

        // === FALLBACK: WorkManager ===
        try {
            val builder = Data.Builder()
            svcIntent.extras?.keySet()?.forEach { key ->
                when (val v = svcIntent.extras?.get(key)) {
                    is String -> builder.putString(key, v)
                    is Long -> builder.putLong(key, v)
                    is Int -> builder.putInt(key, v)
                    is Boolean -> builder.putBoolean(key, v)
                    else -> v?.toString()?.let { builder.putString(key, it) }
                }
            }

            // ensure missing tenantId or recording flag are included
            if (!builder.build().keyValueMap.containsKey("tenantId") && tenantId != null)
                builder.putString("tenantId", tenantId)
            if (!builder.build().keyValueMap.containsKey("recording_enabled"))
                builder.putBoolean("recording_enabled", recordingEnabled)

            // ensure timestamp
            if (!builder.build().keyValueMap.containsKey("receivedAt"))
                builder.putLong("receivedAt", System.currentTimeMillis())

            val req = OneTimeWorkRequestBuilder<EnqueueEventWorker>()
                .setInputData(builder.build())
                .build()

            WorkManager.getInstance(context).enqueue(req)
            Log.d(TAG, "Outgoing event enqueued via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue fallback worker: ${e.localizedMessage}", e)
        }
    }

    // ------------------ Helpers ------------------

    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return digits.ifEmpty { null }
    }

    private fun generateCallId(): String =
        "call_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun markCallActiveForPhone(ctx: Context, phone: String, callId: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("callid_$phone", callId)
            .putLong("callid_ts_$phone", now)
            .putLong("callid_active_until_$phone", now + ACTIVE_CALL_TTL_MS)
            .putString("callid_to_phone_$callId", phone)
            .apply()
    }

    private fun readActiveOrRecentCallId(ctx: Context, phoneRaw: String): String? {
        val phone = normalizeNumber(phoneRaw) ?: phoneRaw
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("callid_$phone", null) ?: return null
        val now = System.currentTimeMillis()

        val activeUntil = prefs.getLong("callid_active_until_$phone", 0L)
        if (activeUntil > now) return id

        val ts = prefs.getLong("callid_ts_$phone", 0L)
        if (ts != 0L && (now - ts) <= REUSE_WINDOW_MS) return id

        return null
    }

    private fun ensureCallIdForPhone(ctx: Context, phoneRaw: String): String {
        val phone = normalizeNumber(phoneRaw) ?: phoneRaw
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("callid_$phone", null)
        if (!existing.isNullOrEmpty()) return existing

        val newId = generateCallId()
        markCallActiveForPhone(ctx, phone, newId)
        return newId
    }
}
