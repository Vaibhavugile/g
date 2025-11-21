package com.example.call_leads_app.callservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

class IncomingReceiver : BroadcastReceiver() {

    private val TAG = "IncomingReceiver"
    private val PREFS = "call_leads_prefs"
    private val KEY_LAST_OUTGOING = "last_outgoing_number"
    private val KEY_LAST_OUTGOING_TS = "last_outgoing_ts"
    private val OUTGOING_MARKER_WINDOW_MS = 10_000L // 10 seconds

    // Active/recency semantics (keep in sync with CallService)
    private val REUSE_WINDOW_MS = 120_000L            // 2 minutes fallback
    private val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL

    private val NOTIF_CHANNEL_ID = "call_lead_channel"
    private val NOTIF_ID_LEAD = 2401

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val tmState = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            var incomingNumber: String? = null
            if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
                incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            }

            Log.d(TAG, "📞 Triggered by Phone State Change - state=$tmState incoming=$incomingNumber")

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastOutgoing = prefs.getString(KEY_LAST_OUTGOING, null)
            val lastTs = prefs.getLong(KEY_LAST_OUTGOING_TS, 0L)
            val now = System.currentTimeMillis()
            val isRecentOutgoing = !lastOutgoing.isNullOrEmpty() && (now - lastTs) <= OUTGOING_MARKER_WINDOW_MS

            // normalize incoming early
            val normalizedIncoming = normalizeNumber(incomingNumber)

            // read tenant once and attach to intents
            val tenantId = try {
                prefs.getString("tenantId", null)
            } catch (e: Exception) {
                null
            }

            if (isRecentOutgoing && normalizedIncoming != null) {
                if (numbersLikelyMatch(lastOutgoing, normalizedIncoming)) {
                    Log.d(TAG, "ℹ️ Detected recent outgoing marker for $normalizedIncoming — treating as outbound and clearing marker.")
                    prefs.edit().remove(KEY_LAST_OUTGOING).remove(KEY_LAST_OUTGOING_TS).apply()

                    // Try to reuse existing callId if present (active-or-recent), otherwise create & persist (active)
                    val existingCallId = readActiveOrRecentCallId(context, normalizedIncoming)
                    val callId = existingCallId ?: ensureCallIdForPhone(context, normalizedIncoming)
                    if (existingCallId != null) {
                        Log.d(TAG, "Reusing existing callId for $normalizedIncoming -> $existingCallId (recent outgoing)")
                    }

                    val outIntent = Intent(context, CallService::class.java).apply {
                        putExtra("event", "outgoing_start")
                        putExtra("direction", "outbound")
                        putExtra("phoneNumber", normalizedIncoming)
                        putExtra("callId", callId)
                        putExtra("receivedAt", now)
                        // attach tenant if present
                        tenantId?.let { putExtra("tenantId", it) }
                    }
                    safeStartServiceOrEnqueue(context, outIntent, normalizedIncoming)
                    return
                }
            }

            when (tmState) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    Log.d(TAG, "RINGING — new incoming call: $incomingNumber")
                    if (!normalizedIncoming.isNullOrEmpty()) {
                        // reuse existing active/recent callId if present
                        val existing = readActiveOrRecentCallId(context, normalizedIncoming)
                        val callId = existing ?: ensureCallIdForPhone(context, normalizedIncoming)
                        if (existing != null) Log.d(TAG, "Reusing existing callId for RINGING: $normalizedIncoming -> $existing")

                        val i = Intent(context, CallService::class.java).apply {
                            putExtra("event", "ringing")
                            putExtra("direction", "inbound")
                            putExtra("phoneNumber", normalizedIncoming)
                            putExtra("callId", callId)
                            putExtra("receivedAt", now)
                            tenantId?.let { putExtra("tenantId", it) }
                        }
                        safeStartServiceOrEnqueue(context, i, normalizedIncoming)
                    } else {
                        Log.w(TAG, "Incoming number is null/empty for state RINGING. Ignoring event.")
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.d(TAG, "OFFHOOK — call answered or started: $incomingNumber")
                    // prefer active/recent mapping; if none, create & mark active for normalizedIncoming or raw incomingNumber
                    val callId = normalizedIncoming?.let { readActiveOrRecentCallId(context, it) } ?: run {
                        val cid = ensureCallIdForPhone(context, normalizedIncoming ?: incomingNumber)
                        cid ?: generateCallId()
                    }

                    // persist reverse mapping in case it wasn't present
                    try {
                        val markerPhone = normalizedIncoming ?: incomingNumber
                        if (!markerPhone.isNullOrEmpty() && !callId.isNullOrEmpty()) {
                            // ensure reverse mapping exists
                            val prefsLocal = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            prefsLocal.edit().putString("callid_to_phone_$callId", markerPhone).apply()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist reverse mapping for OFFHOOK: ${e.localizedMessage}")
                    }

                    val i = Intent(context, CallService::class.java).apply {
                        putExtra("event", "answered")
                        putExtra("direction", "inbound")
                        putExtra("phoneNumber", normalizedIncoming)
                        putExtra("callId", callId)
                        putExtra("receivedAt", now)
                        tenantId?.let { putExtra("tenantId", it) }
                    }
                    safeStartServiceOrEnqueue(context, i, normalizedIncoming)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d(TAG, "IDLE — finalizing call for $incomingNumber")
                    val callId = normalizedIncoming?.let { readActiveOrRecentCallId(context, it) } ?: ensureCallIdForPhone(context, normalizedIncoming ?: incomingNumber)
                    try {
                        val markerPhone = normalizedIncoming ?: incomingNumber
                        if (!markerPhone.isNullOrEmpty() && !callId.isNullOrEmpty()) {
                            val prefsLocal = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            prefsLocal.edit().putString("callid_to_phone_$callId", markerPhone).apply()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist reverse mapping for IDLE: ${e.localizedMessage}")
                    }

                    val i = Intent(context, CallService::class.java).apply {
                        putExtra("event", "ended")
                        putExtra("direction", "inbound")
                        putExtra("phoneNumber", normalizedIncoming)
                        putExtra("callId", callId)
                        putExtra("receivedAt", now)
                        tenantId?.let { putExtra("tenantId", it) }
                    }
                    safeStartServiceOrEnqueue(context, i, normalizedIncoming)
                }
                else -> {
                    Log.d(TAG, "Unhandled telephony state: $tmState")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onReceive: ${e.localizedMessage}", e)
        }
    }

    private fun safeStartServiceOrEnqueue(context: Context, svcIntent: Intent, normalizedPhone: String?) {
        try {
            ContextCompat.startForegroundService(context, svcIntent)
        } catch (ex: Exception) {
            // Foreground start not allowed in this context on some devices / OEMs.
            Log.w(TAG, "startForegroundService failed (${ex.javaClass.simpleName}) — enqueueing WorkManager job and posting notification.")
            // enqueue a lightweight worker that will persist the event
            val dataBuilder = Data.Builder()
            svcIntent.extras?.keySet()?.forEach { key ->
                val v = svcIntent.extras?.get(key)
                when (v) {
                    is String -> dataBuilder.putString(key, v)
                    is Long -> dataBuilder.putLong(key, v)
                    is Int -> dataBuilder.putInt(key, v)
                    is Double -> dataBuilder.putDouble(key, v)
                    is Boolean -> dataBuilder.putBoolean(key, v)
                    else -> v?.toString()?.let { dataBuilder.putString(key, it) }
                }
            }
            // defensive: if tenantId present in prefs but somehow not in extras, add it
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val tenant = prefs.getString("tenantId", null)
                if (!tenant.isNullOrEmpty() && !dataBuilder.build().keyValueMap.containsKey("tenantId")) {
                    dataBuilder.putString("tenantId", tenant)
                    Log.d(TAG, "Added tenantId to Worker input: $tenant")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error while adding tenantId to worker input: ${e.localizedMessage}")
            }

            if (!svcIntent.hasExtra("receivedAt")) {
                dataBuilder.putLong("receivedAt", System.currentTimeMillis())
            }

            val work = OneTimeWorkRequestBuilder<EnqueueEventWorker>()
                .setInputData(dataBuilder.build())
                .build()
            WorkManager.getInstance(context).enqueue(work)

            // post a notification so user can tap to open the app (and we will pass lead id when available)
            postTapNotification(context, normalizedPhone)
        }
    }

    private fun postTapNotification(context: Context, phone: String?) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(NOTIF_CHANNEL_ID, "Call leads", NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(ch)
            }

            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launch?.putExtra("open_lead_phone", phone)
            val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

            val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setContentTitle("Call detected")
                .setContentText("Tap to open lead for $phone")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIF_ID_LEAD, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.localizedMessage}", e)
        }
    }

    // tiny helpers
    private fun normalizeNumber(n: String?): String? {
        if (n == null) return null
        val digits = n.filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits
    }

    private fun numbersLikelyMatch(a: String?, b: String?): Boolean {
        val na = normalizeNumber(a) ?: return false
        val nb = normalizeNumber(b) ?: return false
        if (na == nb) return true
        val len = 7
        val sa = if (na.length > len) na.substring(na.length - len) else na
        val sb = if (nb.length > len) nb.substring(nb.length - len) else nb
        return sa == sb
    }

    private fun generateCallId(): String {
        return "call_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }

    // -----------------------
    // CallId lifecycle helpers (active + recent semantics)
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

    private fun clearCallIdMapping(ctx: Context, phoneDigitsOrRaw: String) {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .remove("callid_$normalized")
                .remove("callid_ts_$normalized")
                .remove("callid_active_until_$normalized")
                .apply()
            Log.d(TAG, "Cleared callId mapping for $normalized")
        } catch (e: Exception) {
            Log.w(TAG, "clearCallIdMapping failed: ${e.localizedMessage}")
        }
    }

    /**
     * Backward-compatible ensure: returns existing mapping if present, otherwise creates a new callId
     * and marks it active.
     *
     * NOTE: This function now accepts a Context so it can call markCallActiveForPhone(ctx, ...)
     * and properly persist activity metadata.
     */
    private fun ensureCallIdForPhone(ctx: Context, phoneDigitsOrRaw: String?): String? {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            if (normalized.isNullOrEmpty()) return null
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString("callid_$normalized", null)
            if (!existing.isNullOrEmpty()) {
                // backfill timestamp if missing
                val ts = prefs.getLong("callid_ts_$normalized", 0L)
                if (ts == 0L) prefs.edit().putLong("callid_ts_$normalized", System.currentTimeMillis()).apply()
                return existing
            }

            val newId = generateCallId()
            // prefer to mark active using the Context-aware helper
            try {
                markCallActiveForPhone(ctx, normalized, newId)
            } catch (e: Exception) {
                Log.w(TAG, "markCallActiveForPhone invocation failed: ${e.localizedMessage}")
                // fallback: write directly
                try {
                    prefs.edit()
                        .putString("callid_$normalized", newId)
                        .putLong("callid_ts_$normalized", System.currentTimeMillis())
                        .putLong("callid_active_until_$normalized", System.currentTimeMillis() + ACTIVE_CALL_TTL_MS)
                        .putString("callid_to_phone_$newId", normalized)
                        .apply()
                } catch (ex: Exception) {
                    Log.w(TAG, "Fallback persist of callId marker failed: ${ex.localizedMessage}")
                }
            }

            Log.d(TAG, "Saved callId marker for $normalized -> $newId (ensureCallIdForPhone)")
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        return null
    }

    // NOTE: Because SharedPreferences doesn't expose its Context, callers of ensureCallIdForPhone
    // must pass the Android Context (as this file now does). This keeps the logic safe and testable.
}
