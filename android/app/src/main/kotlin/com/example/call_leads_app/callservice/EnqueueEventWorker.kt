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

            // Ensure phoneNumber is normalized if available
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
                        eventMap["phoneNumber"] = recovered
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

    // Try to find a phone number that was previously saved as "callid_<normalized>" -> callId
    private fun tryFindPhoneForCallId(ctx: Context, callId: String): String? {
        return try {
            val prefs = ctx.getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
            val all = prefs.all
            // Keys we wrote are like "callid_<normalized>" -> callId
            for ((k, v) in all) {
                if (k.startsWith("callid_") && v is String && v == callId) {
                    // extract normalized phone part from key
                    val normalized = k.removePrefix("callid_")
                    return normalized
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error while scanning prefs for callId mapping: ${e.localizedMessage}")
            null
        }
    }
}
