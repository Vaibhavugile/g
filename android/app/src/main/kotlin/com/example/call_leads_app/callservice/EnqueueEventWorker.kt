package com.example.call_leads_app.callservice

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class EnqueueEventWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    private val TAG = "EnqueueEventWorker"

    override fun doWork(): Result {
        return try {
            val data = inputData
            val eventMap = mutableMapOf<String, Any?>()
            data.keyValueMap.forEach { (k, v) ->
                eventMap[k] = v
            }

            // Normalize phoneNumber if present (digits-only)
            val phoneRaw = eventMap["phoneNumber"] as? String
            val normalized = phoneRaw?.filter { it.isDigit() }
            if (!normalized.isNullOrEmpty()) {
                eventMap["phoneNumber"] = normalized
            } else if (phoneRaw != null) {
                // keep raw if normalization removed everything (defensive)
                eventMap["phoneNumber"] = phoneRaw
            }

            // If phoneNumber still missing but we have callId, try to recover phone using persisted callId markers
            if ((eventMap["phoneNumber"] == null || (eventMap["phoneNumber"] as? String).isNullOrEmpty())) {
                val callId = eventMap["callId"] as? String
                if (!callId.isNullOrEmpty()) {
                    val recovered = tryFindPhoneForCallId(applicationContext, callId)
                    if (!recovered.isNullOrEmpty()) {
                        // ensure we store digits-only phone
                        val recoveredNorm = recovered.filter { it.isDigit() }
                        eventMap["phoneNumber"] = if (recoveredNorm.isNotEmpty()) recoveredNorm else recovered
                        Log.d(TAG, "Recovered phoneNumber=$recovered for callId=$callId")
                    } else {
                        Log.w(TAG, "No phone mapping found for callId=$callId; will enqueue without phone (UploadWorker may skip).")
                    }
                }
            }

            // Ensure there's a receivedAt timestamp for ordering/diagnostics
            if (eventMap["receivedAt"] == null) {
                eventMap["receivedAt"] = System.currentTimeMillis()
            }

            // Persist using EventQueue so UploadWorker will pick it up
            val q = EventQueue(applicationContext)
            q.enqueue(eventMap)
            Log.d(TAG, "Enqueued event (fallback) -> $eventMap (queueSize=${q.size()})")

            // schedule UploadWorker (same as CallService does)
            UploadWorker.scheduleOnce(applicationContext)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "EnqueueEventWorker failed: ${e.localizedMessage}", e)
            Result.retry()
        }
    }

    /**
     * Fast lookup: check direct reverse mapping "callid_to_phone_<callId>" first.
     * Fallback: scan legacy "callid_<phone>" keys to find a matching callId.
     */
    private fun tryFindPhoneForCallId(ctx: Context, callId: String): String? {
        try {
            val prefs = ctx.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
            // direct mapping (fast)
            val direct = prefs.getString("callid_to_phone_$callId", null)
            if (!direct.isNullOrEmpty()) return direct

            // fallback: scan keys (backwards compatibility)
            val all = prefs.all
            for ((k, v) in all) {
                if (k.startsWith("callid_") && v is String && v == callId) {
                    val normalized = k.removePrefix("callid_")
                    return normalized
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while looking up callId mapping: ${e.localizedMessage}")
        }
        return null
    }
}
