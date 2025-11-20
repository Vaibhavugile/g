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

            if (isRecentOutgoing && normalizedIncoming != null) {
                if (numbersLikelyMatch(lastOutgoing, normalizedIncoming)) {
                    Log.d(TAG, "ℹ️ Detected recent outgoing marker for $normalizedIncoming — treating as outbound and clearing marker.")
                    prefs.edit().remove(KEY_LAST_OUTGOING).remove(KEY_LAST_OUTGOING_TS).apply()

                    // Try to reuse existing callId if present, otherwise create & persist
                    val existingCallId = readCallIdMarker(context, normalizedIncoming)
                    val callId = existingCallId ?: ensureCallIdForPhone(normalizedIncoming, prefs)
                    if (existingCallId != null) {
                        Log.d(TAG, "Reusing existing callId for $normalizedIncoming -> $existingCallId (recent outgoing)")
                    }

                    val outIntent = Intent(context, CallService::class.java).apply {
                        putExtra("event", "outgoing_start")
                        putExtra("direction", "outbound")
                        putExtra("phoneNumber", normalizedIncoming)
                        putExtra("callId", callId)
                        putExtra("receivedAt", now)
                    }
                    safeStartServiceOrEnqueue(context, outIntent, normalizedIncoming)
                    return
                }
            }

            when (tmState) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    Log.d(TAG, "RINGING — new incoming call: $incomingNumber")
                    if (!normalizedIncoming.isNullOrEmpty()) {
                        // reuse existing callId if present
                        val existing = readCallIdMarker(context, normalizedIncoming)
                        val callId = existing ?: ensureCallIdForPhone(normalizedIncoming, prefs)
                        if (existing != null) Log.d(TAG, "Reusing existing callId for RINGING: $normalizedIncoming -> $existing")

                        val i = Intent(context, CallService::class.java).apply {
                            putExtra("event", "ringing")
                            putExtra("direction", "inbound")
                            putExtra("phoneNumber", normalizedIncoming)
                            putExtra("callId", callId)
                            putExtra("receivedAt", now)
                        }
                        safeStartServiceOrEnqueue(context, i, normalizedIncoming)
                    } else {
                        Log.w(TAG, "Incoming number is null/empty for state RINGING. Ignoring event.")
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.d(TAG, "OFFHOOK — call answered or started: $incomingNumber")
                    // prefer existing mapping; if none, create one for normalizedIncoming or raw incomingNumber
                    val callId = normalizedIncoming?.let { readCallIdMarker(context, it) } ?: run {
                        val cid = ensureCallIdForPhone(normalizedIncoming ?: incomingNumber, prefs)
                        cid ?: generateCallId()
                    }

                    // persist reverse mapping in case it wasn't present
                    try {
                        val markerPhone = normalizedIncoming ?: incomingNumber
                        if (!markerPhone.isNullOrEmpty()) {
                            prefs.edit().putString("callid_to_phone_$callId", markerPhone).apply()
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
                    }
                    safeStartServiceOrEnqueue(context, i, normalizedIncoming)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d(TAG, "IDLE — finalizing call for $incomingNumber")
                    val callId = normalizedIncoming?.let { readCallIdMarker(context, it) } ?: ensureCallIdForPhone(normalizedIncoming ?: incomingNumber, prefs)
                    try {
                        val markerPhone = normalizedIncoming ?: incomingNumber
                        if (!markerPhone.isNullOrEmpty() && !callId.isNullOrEmpty()) {
                            prefs.edit().putString("callid_to_phone_$callId", markerPhone).apply()
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

    private fun readCallIdMarker(context: Context, phoneDigitsOrRaw: String): String? {
        return try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("callid_$normalized", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read callId marker: ${e.localizedMessage}")
            null
        }
    }

    private fun ensureCallIdForPhone(phoneDigitsOrRaw: String?, prefs: android.content.SharedPreferences): String? {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw) ?: phoneDigitsOrRaw
            if (normalized.isNullOrEmpty()) return null
            val existing = prefs.getString("callid_$normalized", null)
            if (!existing.isNullOrEmpty()) return existing

            val newId = generateCallId()
            prefs.edit()
                .putString("callid_$normalized", newId)
                .putLong("callid_ts_$normalized", System.currentTimeMillis())
                .putString("callid_to_phone_$newId", normalized)
                .apply()
            Log.d(TAG, "Saved callId marker for $normalized -> $newId (ensureCallIdForPhone)")
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        return null
    }
}