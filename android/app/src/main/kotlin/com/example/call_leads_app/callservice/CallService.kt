package com.example.call_leads_app.callservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.plugin.common.EventChannel
import kotlin.math.abs
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class CallService : Service() {

    companion object {
        @Volatile
        var eventSink: EventChannel.EventSink? = null

        // In-memory buffer of events that arrived while Flutter wasn't connected.
        // Using a deque so we can flush in-order.
        val pendingEvents: ArrayDeque<Map<String, Any?>> = ArrayDeque()

        private const val TAG = "CallService"

        private const val CALL_COOLDOWN_MS = 2000L
        private const val CALL_LOG_DELAY_MS = 800L
        private const val CALL_LOG_RETRY_DELAY_MS = 900L
        private const val CALL_LOG_RETRY_MAX = 6
        private const val OUTGOING_MARKER_WINDOW_MS = 12_000L

        private const val PREFS = "call_leads_prefs"
        private const val KEY_LAST_OUTGOING = "last_outgoing_number"
        private const val KEY_LAST_OUTGOING_TS = "last_outgoing_ts"

        // only keep a short lock window; we'll also make the lock conditional below
        private const val FINAL_LOCK_TTL_MS = 2500L

        private const val NOTIF_CHANNEL_ID = "call_channel"
        private const val NOTIF_CHANNEL_NAME = "Call Tracking"

        // NEW: reuse window + active TTL to support long calls (10-15min+)
        private const val REUSE_WINDOW_MS = 120_000L            // 2 minutes fallback
        private const val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL
        /**
         * Flush any buffered pendingEvents into the current eventSink if available.
         * This helper is safe to call multiple times and handles exceptions internally.
         */
        fun flushPendingToSink() {
            try {
                val sink = eventSink
                if (sink == null) {
                    Log.d(TAG, "flushPendingToSink: sink is null; nothing to flush.")
                    return
                }

                // Drain the in-memory queue into sink (post to main thread).
                synchronized(pendingEvents) {
                    if (pendingEvents.isEmpty()) {
                        Log.d(TAG, "flushPendingToSink: no in-memory pending events.")
                        return
                    }
                    val toFlush = ArrayList<Map<String, Any?>>()
                    while (pendingEvents.isNotEmpty()) {
                        toFlush.add(pendingEvents.removeFirst())
                    }

                    Handler(Looper.getMainLooper()).post {
                        try {
                            toFlush.forEach { ev ->
                                try {
                                    eventSink?.success(ev)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error while flushing pending event to sink: ${e.localizedMessage}")
                                    // If send fails, put it back to front of queue
                                    synchronized(pendingEvents) { pendingEvents.addFirst(ev) }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error posting flush to main handler: ${e.localizedMessage}")
                            // Put them back into pendingEvents if posting failed
                            synchronized(pendingEvents) { toFlush.reversed().forEach { pendingEvents.addFirst(it) } }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "flushPendingToSink error: ${e.localizedMessage}", e)
            }
        }
    }

    private lateinit var telephonyManager: TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCallNumber: String? = null
    private var currentCallDirection: String? = null
    private var previousCallState: Int = TelephonyManager.CALL_STATE_IDLE
    private var lastCallEndTime: Long = 0
    private var legacyListener: CallStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(1, buildNotification())
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        registerTelephonyCallback()
        Log.d(TAG, "✅ Service created and listening.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "➡️ onStartCommand extras=${intent?.extras}")
        val event = intent?.getStringExtra("event")
        val number = intent?.getStringExtra("phoneNumber")
        val direction = intent?.getStringExtra("direction")
        val callIdFromIntent = intent?.getStringExtra("callId")

        if (event == "ended") {
            Log.d(TAG, "Received 'ended' intent — deferring final result to call log (numberOverride=$number).")
            readCallLogForLastCall(numberOverride = number, directionOverride = null, cooldown = true, retryCount = 0)
            return START_STICKY
        }

        if (!number.isNullOrEmpty() && !direction.isNullOrEmpty()) {
            currentCallNumber = number
            if (currentCallDirection == null || currentCallDirection == "unknown") {
                currentCallDirection = direction
            } else {
                if (currentCallDirection != "outbound") {
                    currentCallDirection = direction
                }
            }

            if (event == "outgoing_start") {
                val payload = mapOf<String, Any?>(
                    "phoneNumber" to number,
                    "direction" to "outbound",
                    "outcome" to "outgoing_start",
                    "timestamp" to System.currentTimeMillis(),
                    "durationInSeconds" to null,
                    "callId" to callIdFromIntent
                )
                Log.d(TAG, "DEBUG: Immediate forward outbound -> $payload")
                // Persist & forward
                persistAndForwardEvent(payload)
            } else if (event != null && event != "state_change") {
                // other immediate event (e.g., answered)
                sendCallEvent(number, currentCallDirection ?: direction, "answered", System.currentTimeMillis(), null)
            }
        }

        return START_STICKY
    }

    private fun registerTelephonyCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback = CallStateCallback(this)
                telephonyManager.registerTelephonyCallback(mainExecutor, modernCallback as CallStateCallback)
                Log.d(TAG, "✅ Registered TelephonyCallback (API S+)")
            } else {
                @Suppress("DEPRECATION")
                legacyListener = CallStateListener(this)
                @Suppress("DEPRECATION")
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "✅ Registered PhoneStateListener (API < S)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering telephony callback: ${e.localizedMessage}", e)
        }
    }

    private fun unregisterTelephonyCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                    modernCallback = null
                    Log.d(TAG, "✅ Unregistered TelephonyCallback")
                }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                    legacyListener = null
                    Log.d(TAG, "✅ Unregistered PhoneStateListener")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony callback: ${e.localizedMessage}", e)
        }
    }

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

    /**
     * Ensure we have a callId for the given normalized phone. Lookup-first: reuse existing mapping
     * if present; otherwise create and persist a new mapping.
     *
     * NOTE: ensureCallIdForPhone now marks the mapping active (sets active-until) so it is reused
     * for the duration of the call. It still returns the existing mapping if present.
     */
    private fun ensureCallIdForPhone(normalizedPhone: String?, prefs: SharedPreferences): String? {
        try {
            if (normalizedPhone.isNullOrEmpty()) return null
            val existing = prefs.getString("callid_$normalizedPhone", null)
            if (!existing.isNullOrEmpty()) {
                // backfill timestamp if missing
                val ts = prefs.getLong("callid_ts_$normalizedPhone", 0L)
                if (ts == 0L) prefs.edit().putLong("callid_ts_$normalizedPhone", System.currentTimeMillis()).apply()
                return existing
            }

            val newId = generateCallId()
            // Use markCallActiveForPhone which sets id, ts and active-until
            markCallActiveForPhone(applicationContext, normalizedPhone, newId)
            Log.d(TAG, "Saved callId marker for $normalizedPhone -> $newId (ensureCallIdForPhone)")
            return newId
        } catch (e: Exception) {
            Log.w(TAG, "ensureCallIdForPhone failed: ${e.localizedMessage}")
        }
        return null
    }

    /**
     * Persist event to on-device queue and attempt to forward to Flutter.
     * Also schedules WorkManager UploadWorker to ensure eventual upload to Firestore.
     *
     * Improvement: lookup existing callId for phone before generating a new one. This reduces
     * multiple callId creation for the same physical call when multiple components race.
     */
    private fun persistAndForwardEvent(payload: Map<String, Any?>) {
        try {
            // Make a mutable copy so we can enrich it
            val mutable = payload.toMutableMap()

            // --- NEW: attach tenantId from SharedPreferences (if present) ---
            try {
                val prefsLocal = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val tenantId = prefsLocal.getString("tenantId", null)
                if (!tenantId.isNullOrEmpty()) {
                    mutable["tenantId"] = tenantId
                    Log.d(TAG, "Attached tenantId=$tenantId to event for phone=${mutable["phoneNumber"]}")
                } else {
                    // defensive: mark event for review later if no tenant set (helps migration/debug)
                    mutable["needsTenantReview"] = true
                    Log.w(TAG, "No tenantId in prefs – event marked needsTenantReview for phone=${mutable["phoneNumber"]}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error while reading tenantId from prefs: ${e.localizedMessage}")
            }
            // --- END tenant attach ---

            // Ensure phone normalized
            val phoneRaw = (mutable["phoneNumber"] as? String)
            val normalizedPhone = normalizeNumber(phoneRaw) ?: phoneRaw

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            // If callId missing, try to reuse existing mapping before generating
            var callId = mutable["callId"] as? String
            if (callId.isNullOrEmpty()) {
                try {
                    if (!normalizedPhone.isNullOrEmpty()) {
                        // NEW: prefer active-or-recent mapping
                        val existing = readActiveOrRecentCallId(applicationContext, normalizedPhone)
                        if (!existing.isNullOrEmpty()) {
                            callId = existing
                            mutable["callId"] = callId
                            Log.d(TAG, "Reused existing active/recent callId marker for $normalizedPhone -> $callId (persistAndForwardEvent)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking existing callId marker: ${e.localizedMessage}")
                }
            }

            if (callId.isNullOrEmpty()) {
                // No existing mapping found — create one (or fallback to reverse mapping for raw phone)
                callId = ensureCallIdForPhone(normalizedPhone, prefs) ?: generateCallId()
                mutable["callId"] = callId
                try {
                    val markerKey = if (!normalizedPhone.isNullOrEmpty()) normalizedPhone else (phoneRaw ?: "")
                    if (markerKey.isNotEmpty()) {
                        // Use markCallActiveForPhone instead of manual puts
                        markCallActiveForPhone(applicationContext, markerKey, callId)
                        Log.d(TAG, "Saved callId marker for $markerKey -> $callId (persistAndForwardEvent)")
                    } else {
                        prefs.edit().putString("callid_to_phone_$callId", phoneRaw).apply()
                        Log.d(TAG, "Saved reverse callId mapping only for callId=$callId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed saving callId marker in persistAndForwardEvent: ${e.localizedMessage}")
                }
            } else {
                // If callId present, ensure reverse mapping exists
                try {
                    val existing = prefs.getString("callid_to_phone_$callId", null)
                    if (existing.isNullOrEmpty() && !normalizedPhone.isNullOrEmpty()) {
                        prefs.edit().putString("callid_to_phone_$callId", normalizedPhone).apply()
                        Log.d(TAG, "Backfilled reverse mapping for callId=$callId -> $normalizedPhone")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed ensuring reverse mapping exists: ${e.localizedMessage}")
                }
            }

            // Persist to EventQueue (durable)
            val q = EventQueue(applicationContext)
            q.enqueue(mutable)
            Log.d(TAG, "Persisted event to EventQueue. queueSize=${q.size()} payload=$mutable")

            // Schedule WorkManager upload (ensures eventual delivery)
            val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10_000L, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(applicationContext).enqueue(workRequest)

            // Forward to Flutter if connected; otherwise buffer in-memory for flush later
            sendToFlutterOrBuffer(mutable)
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting/forwarding event: ${e.localizedMessage}", e)
            // Still attempt to buffer for UI
            sendToFlutterOrBuffer(payload)
        }
    }

    private fun sendToFlutterOrBuffer(payload: Map<String, Any?>) {
        try {
            val sink = eventSink
            if (sink == null) {
                Log.w(TAG, "⚠️ Flutter not connected yet → buffering pending event (in-memory)")
                synchronized(pendingEvents) {
                    pendingEvents.addLast(payload)
                    // cap queue length to prevent unbounded growth
                    if (pendingEvents.size > 200) pendingEvents.removeFirst()
                }
            } else {
                mainHandler.post {
                    try {
                        eventSink?.success(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending event to flutter: ${e.localizedMessage}")
                        // buffer if sending fails
                        synchronized(pendingEvents) { pendingEvents.addLast(payload) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendToFlutterOrBuffer error: ${e.localizedMessage}")
            synchronized(pendingEvents) { pendingEvents.addLast(payload) }
        }
    }

    fun handleCallStateUpdate(state: Int, incomingNumber: String?) {
        Log.d(TAG, "📞 Listener State Change: ${stateToName(state)} (Incoming: $incomingNumber)")

        if (state == TelephonyManager.CALL_STATE_IDLE && System.currentTimeMillis() < lastCallEndTime + CALL_COOLDOWN_MS) {
            Log.d(TAG, "🚫 DEDUPLICATED: IDLE event ignored due to cooldown.")
            return
        }

        try {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK && previousCallState == TelephonyManager.CALL_STATE_IDLE && currentCallNumber.isNullOrEmpty()) {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastOutgoing = prefs.getString(KEY_LAST_OUTGOING, null)
                val lastTs = prefs.getLong(KEY_LAST_OUTGOING_TS, 0L)
                val now = System.currentTimeMillis()
                if (!lastOutgoing.isNullOrEmpty() && now - lastTs <= OUTGOING_MARKER_WINDOW_MS) {
                    if (numbersLikelyMatch(lastOutgoing, incomingNumber) || incomingNumber == null) {
                        currentCallNumber = lastOutgoing
                        currentCallDirection = "outbound"
                        Log.d(TAG, "EARLY: Detected outgoing marker → treating call as OUTBOUND for $currentCallNumber")
                        prefs.edit().remove(KEY_LAST_OUTGOING).remove(KEY_LAST_OUTGOING_TS).apply()

                        sendCallEvent(currentCallNumber ?: "unknown", "outbound", "answered", System.currentTimeMillis(), null)
                        previousCallState = state
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading outgoing marker early: ${e.localizedMessage}")
        }

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (previousCallState == TelephonyManager.CALL_STATE_RINGING) {
                    val dir = if (currentCallDirection == "outbound") "outbound" else "inbound"
                    sendCallEvent(currentCallNumber ?: incomingNumber ?: "unknown", currentCallDirection ?: dir, "answered", System.currentTimeMillis(), null)
                } else if (previousCallState == TelephonyManager.CALL_STATE_IDLE) {
                    if (currentCallNumber.isNullOrEmpty()) {
                        readCallLogForLastCall()
                    } else {
                        sendCallEvent(currentCallNumber!!, currentCallDirection ?: "outbound", "answered", System.currentTimeMillis(), null)
                    }
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (previousCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    handleCallEndedAfterOffhook()
                } else if (previousCallState == TelephonyManager.CALL_STATE_RINGING) {
                    handleCallEndedAfterRinging(incomingNumber)
                }
                currentCallNumber = null
                currentCallDirection = null
                lastCallEndTime = System.currentTimeMillis()
            }

            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "RINGING event via listener.")
                if (currentCallDirection == null) currentCallDirection = "inbound"
                if (currentCallNumber == null && !incomingNumber.isNullOrEmpty()) currentCallNumber = incomingNumber
            }
        }

        previousCallState = state
    }

    private fun handleCallEndedAfterOffhook() {
        if (currentCallNumber.isNullOrEmpty()) {
            Log.e(TAG, "❌ Call ended (OFFHOOK->IDLE) but currentCallNumber is missing.")
            return
        }
        readCallLogForLastCall(numberOverride = currentCallNumber, directionOverride = currentCallDirection, cooldown = true, retryCount = 0)
    }

    private fun handleCallEndedAfterRinging(incomingNumber: String?) {
        val finalNumber = currentCallNumber ?: incomingNumber
        if (finalNumber.isNullOrEmpty()) {
            Log.e(TAG, "❌ Call ended (RINGING->IDLE) but no number available.")
            return
        }
        readCallLogForLastCall(numberOverride = finalNumber, directionOverride = "inbound", cooldown = true, retryCount = 0)
    }

    private fun readCallLogForLastCall(
        numberOverride: String? = null,
        directionOverride: String? = null,
        cooldown: Boolean = false,
        retryCount: Int = 0
    ) {
        if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ READ_CALL_LOG permission not granted for failsafe.")
            val outgoing = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_OUTGOING, null)
            val fallbackNumber = numberOverride ?: outgoing
            if (!fallbackNumber.isNullOrEmpty()) {
                val ts = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                emitFinalCallEventIfNotLocked(fallbackNumber, "ended", ts, null, directionOverride)
            } else {
                Log.w(TAG, "No fallback due to missing number and missing permission.")
            }
            return
        }

        val delay = if (cooldown) CALL_LOG_DELAY_MS else 0L
        mainHandler.postDelayed({
            var cursor: Cursor? = null
            try {
                val recentWindowMs = System.currentTimeMillis() - 5 * 60 * 1000L
                val selection = "${CallLog.Calls.DATE}>=?"
                val selectionArgs = arrayOf(recentWindowMs.toString())
                val limitUri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", "20").build()

                cursor = contentResolver.query(
                    limitUri,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC"
                )

                if (cursor == null || cursor.count == 0) {
                    Log.w(TAG, "Call log query returned empty or null.")
                    if (retryCount < CALL_LOG_RETRY_MAX - 1) {
                        Log.d(TAG, "Retrying call-log read after short delay. retry=${retryCount + 1}")
                        mainHandler.postDelayed({
                            readCallLogForLastCall(numberOverride, directionOverride, cooldown, retryCount + 1)
                        }, CALL_LOG_RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Max retries reached and no usable rows. Attempting fallback if possible.")
                        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        val outgoing = prefs.getString(KEY_LAST_OUTGOING, null)
                        val fallbackNumber = numberOverride ?: outgoing
                        if (!fallbackNumber.isNullOrEmpty()) {
                            val ts = prefs.getLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                            emitFinalCallEventIfNotLocked(fallbackNumber, "ended", ts, null, directionOverride)
                        } else {
                            Log.w(TAG, "No number available to emit final; skipping fallback.")
                        }
                    }
                    return@postDelayed
                }

                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastOutgoing = prefs.getString(KEY_LAST_OUTGOING, null)
                val lastOutgoingTs = prefs.getLong(KEY_LAST_OUTGOING_TS, 0L)
                val outgoingMarker: Pair<String, Long>? = if (!lastOutgoing.isNullOrEmpty() && lastOutgoingTs > 0L) Pair(lastOutgoing, lastOutgoingTs) else null

                val best = pickBestCallLogRow(cursor, outgoingMarker)
                if (best != null) {
                    val num = numberOverride ?: best.number ?: outgoingMarker?.first ?: ""
                    val ts = best.timestamp
                    val dur = if (best.duration >= 0) best.duration else null
                    val outcomeAndDir = getOutcomeAndDirectionFromType(best.type, directionOverride)
                    val outcome = outcomeAndDir.first
                    var direction = outcomeAndDir.second

                    if (outgoingMarker != null && numbersLikelyMatch(outgoingMarker.first, num) && abs(outgoingMarker.second - ts) <= OUTGOING_MARKER_WINDOW_MS) {
                        direction = "outbound"
                    }

                    Log.w(TAG, "🚨 Call Log Result (picked): $outcome ($direction) to $num, Duration: $dur ts:$ts")
                    emitFinalCallEventIfNotLocked(num, outcome, ts, dur, direction)
                } else {
                    Log.w(TAG, "No suitable call-log row found after retries.")
                    val outgoing = prefs.getString(KEY_LAST_OUTGOING, null)
                    val fallbackNumber = numberOverride ?: outgoing
                    if (!fallbackNumber.isNullOrEmpty()) {
                        val ts = prefs.getLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                        emitFinalCallEventIfNotLocked(fallbackNumber, "ended", ts, null, directionOverride)
                    } else {
                        Log.w(TAG, "No number available to emit final; skipping fallback.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reading Call Log: $e")
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val outgoing = prefs.getString(KEY_LAST_OUTGOING, null)
                val fallbackNumber = numberOverride ?: outgoing
                if (!fallbackNumber.isNullOrEmpty()) {
                    val ts = prefs.getLong(KEY_LAST_OUTGOING_TS, System.currentTimeMillis())
                    emitFinalCallEventIfNotLocked(fallbackNumber, "ended", ts, null, directionOverride)
                } else {
                    Log.w(TAG, "No number available to emit final after error; skipping fallback.")
                }
            } finally {
                cursor?.close()
            }
        }, delay)
    }

    private data class _RowPick(val number: String?, val type: Int, val timestamp: Long, val duration: Int)

    private fun pickBestCallLogRow(cursor: Cursor, outgoingMarker: Pair<String, Long>?): _RowPick? {
        val rows = mutableListOf<_RowPick>()
        if (!cursor.moveToFirst()) return null
        do {
            try {
                val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val ts = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val dur = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                rows.add(_RowPick(number, type, ts, dur))
            } catch (e: Exception) {
            }
        } while (cursor.moveToNext())

        if (rows.isEmpty()) return null

        outgoingMarker?.let { (markerNumber, markerTs) ->
            val tol = OUTGOING_MARKER_WINDOW_MS
            val matches = rows.filter { it.number != null && numbersLikelyMatch(it.number, markerNumber) && abs(it.timestamp - markerTs) <= tol }
            if (matches.isNotEmpty()) return matches.maxByOrNull { it.duration }
        }

        val withDur = rows.filter { it.duration > 0 }
        if (withDur.isNotEmpty()) return withDur.maxByOrNull { it.duration }
        return rows.maxByOrNull { it.timestamp }
    }

    private fun getOutcomeAndDirectionFromType(callType: Int, directionOverride: String? = null): Pair<String, String> {
        return when (callType) {
            CallLog.Calls.INCOMING_TYPE -> Pair("ended", "inbound")
            CallLog.Calls.OUTGOING_TYPE -> Pair("outgoing_start", "outbound")
            CallLog.Calls.MISSED_TYPE -> Pair("missed", "inbound")
            CallLog.Calls.VOICEMAIL_TYPE -> Pair("voicemail", "inbound")
            5 -> Pair("rejected", "inbound")
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> Pair("answered_external", "inbound")
            else -> {
                if (!directionOverride.isNullOrEmpty()) {
                    val out = if (directionOverride == "outbound") "outgoing_start" else "ended"
                    Pair(out, directionOverride)
                } else {
                    Pair("ended", "inbound")
                }
            }
        }
    }

    /**
     * Emit final call event but avoid applying it if a short-term lock is active.
     *
     * CHANGE: only create the finalization lock when the event includes an authoritative duration.
     * This prevents provisional/no-duration finals from blocking later authoritative call-log updates.
     */
    private fun emitFinalCallEventIfNotLocked(phoneNumber: String, finalOutcome: String, timestampMs: Long, durationSec: Int?, directionOverride: String? = null) {
        val normalized = normalizeNumber(phoneNumber) ?: ""
        if (normalized.isEmpty()) {
            Log.w(TAG, "emitFinalCallEventIfNotLocked: normalized phone empty → skipping.")
            return
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lockKey = "final_lock_$normalized"
        val lockedUntil = prefs.getLong(lockKey, 0L)
        val now = System.currentTimeMillis()

        // If there is a lock and it's still valid -> skip
        if (now < lockedUntil) {
            Log.d(TAG, "Finalization for $normalized currently locked until $lockedUntil — skipping.")
            return
        }

        // IMPORTANT: Only set the guard lock when this final is authoritative (contains duration).
        // This prevents provisional saves (no duration) from preventing later authoritative call-log updates.
        if (durationSec != null) {
            prefs.edit().putLong(lockKey, now + FINAL_LOCK_TTL_MS).apply()
            Log.d(TAG, "Placed finalization lock for $normalized until ${now + FINAL_LOCK_TTL_MS}")
        } else {
            // Ensure any older lock doesn't persist longer than TTL; remove it to be safe.
            prefs.edit().remove(lockKey).apply()
        }

        emitFinalCallEvent(phoneNumber, finalOutcome, timestampMs, durationSec, directionOverride)
    }

    private fun emitFinalCallEvent(phoneNumber: String, finalOutcome: String, timestampMs: Long, durationSec: Int?, directionOverride: String? = null) {
        val normalized = normalizeNumber(phoneNumber) ?: ""
        if (normalized.isEmpty()) {
            Log.w(TAG, "emitFinalCallEvent: empty phoneNumber — skipping emit.")
            return
        }

        val outgoingMarker = readOutgoingMarker()
        val isOutbound = if (directionOverride != null) {
            directionOverride == "outbound"
        } else {
            outgoingMarker?.first?.let { numbersLikelyMatch(it, phoneNumber) } ?: false
        }

        val lastFinalKey = "last_final_ts_$normalized"
        val lastFinalTs = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(lastFinalKey, 0L)
        val lastDurKey = "last_final_dur_$normalized"
        val lastDur = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(lastDurKey, -1)

        if (lastFinalTs != 0L && abs(lastFinalTs - timestampMs) < 2000L) {
            if (durationSec == null || durationSec == lastDur) {
                Log.d(TAG, "Skipping duplicate final event for $normalized (ts close and dur unchanged)")
                return
            }
        }

        val payload = mapOf(
            "phoneNumber" to phoneNumber,
            "direction" to if (isOutbound) "outbound" else "inbound",
            "outcome" to finalOutcome,
            "timestamp" to timestampMs,
            "durationInSeconds" to durationSec
        )

        Log.d(TAG, "📤 Emitting final event: $payload")
        // Persist to queue and forward to Flutter (or buffer)
        persistAndForwardEvent(payload)

        // record last-final meta
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(lastFinalKey, timestampMs).putInt(lastDurKey, durationSec ?: -1).apply()

        // Clear transient mapping so subsequent calls get a fresh callId
        try {
            clearCallIdMapping(applicationContext, phoneNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear callId mapping after finalization: ${e.localizedMessage}")
        }
    }

    fun sendCallEvent(number: String, direction: String, outcome: String, timestamp: Long, durationInSeconds: Int?) {
        val data = mapOf(
            "phoneNumber" to number,
            "direction" to direction,
            "outcome" to outcome,
            "timestamp" to timestamp,
            "durationInSeconds" to durationInSeconds,
        )
        Log.d(TAG, "📤 Sending event to Flutter (and persisting): $data")
        persistAndForwardEvent(data)
    }

    private fun readOutgoingMarker(): Pair<String, Long>? {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val num = prefs.getString(KEY_LAST_OUTGOING, null)
        val ts = prefs.getLong(KEY_LAST_OUTGOING_TS, 0L)
        if (num == null || ts == 0L) return null
        return Pair(num, ts)
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("Call Tracking Running")
                .setContentText("Detecting call events")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Call Tracking Running")
                .setContentText("Detecting call events")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .build()
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channel: ${e.localizedMessage}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterTelephonyCallback()
        super.onDestroy()
        Log.d(TAG, "🛑 Service destroyed")
    }

    private fun stateToName(state: Int): String {
        return when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN ($state)"
        }
    }

    private fun generateCallId(): String {
        return "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }

    // -----------------------
    // New helpers for callId lifecycle
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

            // check explicit active-until marker first
            val activeUntil = prefs.getLong("callid_active_until_$normalized", 0L)
            if (activeUntil > now) {
                Log.d(TAG, "Reusing ACTIVE callId for $normalized -> $id (activeUntil=$activeUntil)")
                return id
            }

            // fallback: allow short reuse window if active marker expired but ts is recent
            val ts = prefs.getLong("callid_ts_$normalized", 0L)
            if (ts != 0L && (now - ts) <= REUSE_WINDOW_MS) {
                Log.d(TAG, "Reusing RECENT callId for $normalized -> $id (ts=$ts)")
                return id
            }

            // too old / not active
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
}