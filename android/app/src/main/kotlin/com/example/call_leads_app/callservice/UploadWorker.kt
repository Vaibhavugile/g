package com.example.call_leads_app.callservice

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import com.google.firebase.firestore.Query

/**
 * UploadWorker: reads queued events from EventQueue, and writes them into Firestore using the
 * hierarchy:
 *
 * /leads/{leadId}
 *    /calls/{callId}
 *       /events/{eventId}
 *
 * This variant is defensive:
 *  - Builds ops only for items that have a usable phone number.
 *  - Commits in conservative chunks (well under Firestore limits).
 *  - Removes only the contiguous prefix of queued items that were actually uploaded,
 *    preventing accidental deletion of later items when some early items were skipped.
 *  - If an item lacks callId, attempt to find an existing "open" call doc for the lead (most recent,
 *    not finalized) and attach the event to it. If none found, generate a callId.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val TAG = "UploadWorker"
    private val queue = EventQueue(appContext)
    private val PREFS = "call_leads_prefs"

    override suspend fun doWork(): Result {
        try {
            // Initialize Firebase (if necessary)
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                try {
                    FirebaseApp.initializeApp(applicationContext)
                    Log.d(TAG, "FirebaseApp initialized.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize FirebaseApp: ${e.localizedMessage}", e)
                    return Result.failure()
                }
            }

            // Authenticate (anonymous if needed)
            val auth = FirebaseAuth.getInstance()
            try {
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                    Log.d(TAG, "Signed in anonymously to Firebase.")
                }
            } catch (e: FirebaseAuthException) {
                Log.w(TAG, "FirebaseAuthException during anonymous sign-in: ${e.localizedMessage}")
                return Result.retry()
            } catch (e: Exception) {
                Log.e(TAG, "Auth error: ${e.localizedMessage}", e)
                return Result.retry()
            }

            val firestore = FirebaseFirestore.getInstance()

            val items = queue.peekAll()
            if (items.isEmpty()) {
                Log.d(TAG, "No queued events to upload.")
                return Result.success()
            }

            Log.d(TAG, "Preparing to upload ${items.size} queued events.")

            // Prepare UpsertOps only for items with usable phone numbers.
            // Keep original indices so we can safely remove only a contiguous uploaded prefix.
            data class IndexedOp(
                val originalIndex: Int,
                val leadPath: String,
                val leadData: Map<String, Any?>,
                val callPath: String,
                val callBase: Map<String, Any?>,
                val eventData: Map<String, Any?>,
                val finalizeFields: Map<String, Any?>?
            )

            val ops = mutableListOf<IndexedOp>()

            for ((idx, item) in items.withIndex()) {
                try {
                    val phoneRaw = (item["phoneNumber"] as? String) ?: ""
                    val phone = normalizeNumber(phoneRaw)
                    if (phone.isEmpty()) {
                        // Skip items without usable phone, but keep them in queue for later investigation/recovery.
                        Log.w(TAG, "Skipping queued item with empty phone: $item")
                        continue
                    }

                    val direction = (item["direction"] as? String) ?: "inbound"
                    val outcome = (item["outcome"] as? String) ?: "unknown"
                    val ts = (item["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    val durNum = item["durationInSeconds"]
                    val duration = when (durNum) {
                        is Number -> durNum.toInt()
                        is String -> durNum.toIntOrNull()
                        else -> null
                    }
                    val callIdFromEvent = (item["callId"] as? String)
                    // If callId missing, try to find an open call doc for this phone; else generate one
                    val leadId = leadIdFromPhone(phone)
                    val callId = if (!callIdFromEvent.isNullOrEmpty()) {
                        callIdFromEvent
                    } else {
                        // BEST-EFFORT: query recent calls for this lead and reuse an open one
                        findOpenCallIdForLeadOrGenerate(firestore, leadId, phone, ts)
                    }

                    val leadRefPath = "leads/$leadId"
                    val callRefPath = "$leadRefPath/calls/$callId"

                    val leadUpsert = mapOf(
                        "phoneNumber" to phone,
                        "lastSeen" to FieldValue.serverTimestamp()
                    )

                    val callBase = mapOf(
                        "phoneNumber" to phone,
                        "direction" to direction,
                        "createdAt" to FieldValue.serverTimestamp()
                    )

                    val eventData = mutableMapOf<String, Any?>(
                        "outcome" to outcome,
                        "timestamp" to ts,
                        "receivedAt" to (item["receivedAt"] ?: FieldValue.serverTimestamp()),
                        "callId" to callId
                    )
                    if (duration != null) eventData["durationInSeconds"] = duration
                    if (callIdFromEvent == null) {
                        // if worker generated or selected callId, record that for traceability
                        eventData["callIdGeneratedByWorker"] = true
                    }

                    val isFinal = (outcome == "ended" || duration != null)
                    val finalizeFields = if (isFinal) {
                        val ff = mutableMapOf<String, Any?>(
                            "finalOutcome" to outcome,
                            "finalizedAt" to FieldValue.serverTimestamp()
                        )
                        if (duration != null) ff["durationInSeconds"] = duration
                        ff
                    } else null

                    ops.add(
                        IndexedOp(
                            originalIndex = idx,
                            leadPath = leadRefPath,
                            leadData = leadUpsert,
                            callPath = callRefPath,
                            callBase = callBase,
                            eventData = eventData,
                            finalizeFields = finalizeFields
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed queued item at index $idx: $item", e)
                }
            }

            if (ops.isEmpty()) {
                Log.w(TAG, "No valid ops to upload after parsing queued items.")
                // Nothing to upload: return success so worker won't retry repeatedly
                return Result.success()
            }

            // Commit ops in chunks. We'll collect the set of original indices that were uploaded successfully.
            val CHUNK_SIZE = 300 // conservative
            val uploadedOriginalIndices = mutableSetOf<Int>()
            var opIdx = 0
            while (opIdx < ops.size) {
                val end = min(ops.size, opIdx + CHUNK_SIZE)
                val chunk = ops.subList(opIdx, end)

                val batch = firestore.batch()
                // For each op in the chunk, perform the writes
                for (op in chunk) {
                    try {
                        val leadRef = firestore.document(op.leadPath)
                        val callRef = firestore.document(op.callPath)
                        val eventRef = firestore.collection(op.callPath + "/events").document() // create a fresh doc
                        batch.set(leadRef, op.leadData, SetOptions.merge())
                        batch.set(callRef, op.callBase, SetOptions.merge())
                        batch.set(eventRef, op.eventData)
                        op.finalizeFields?.let { batch.set(callRef, it, SetOptions.merge()) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error preparing op for batch (opIdx=${op.originalIndex}): ${e.localizedMessage}", e)
                    }
                }

                try {
                    batch.commit().await()
                    // mark the original indices in this chunk as uploaded
                    for (op in chunk) uploadedOriginalIndices.add(op.originalIndex)
                    Log.d(TAG, "Committed chunk of ${chunk.size} ops to Firestore. Uploaded indices: ${chunk.map { it.originalIndex }}")
                } catch (e: FirebaseException) {
                    Log.w(TAG, "Firestore commit FirebaseException (transient?) : ${e.localizedMessage}", e)
                    return Result.retry()
                } catch (e: Exception) {
                    Log.e(TAG, "Firestore commit failed: ${e.localizedMessage}", e)
                    return Result.retry()
                }

                opIdx = end
            }

            // Determine how many items from the queue head were successfully uploaded (contiguous prefix).
            var removeCount = 0
            for (i in items.indices) {
                if (uploadedOriginalIndices.contains(i)) {
                    removeCount++
                } else {
                    // stop at first gap — keep later items
                    break
                }
            }

            if (removeCount > 0) {
                try {
                    queue.removeFirstN(removeCount)
                    Log.d(TAG, "Removed $removeCount items from EventQueue after successful upload.")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove $removeCount items from queue after upload: ${e.localizedMessage}", e)
                    // don't fail the job — we've uploaded them
                }
            } else {
                Log.w(TAG, "No contiguous prefix of queued items was uploaded; leaving queue intact for later retry.")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in UploadWorker: ${e.localizedMessage}", e)
            return Result.retry()
        }
    }

    companion object {
        /**
         * Schedule a one-off UploadWorker with network constraints and exponential backoff.
         * Use this helper from other code (EnqueueEventWorker, CallService, IncomingReceiver).
         */
        fun scheduleOnce(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10_000L, TimeUnit.MILLISECONDS)
                    .build()

                WorkManager.getInstance(context).enqueue(request)
                Log.d("UploadWorker", "Enqueued UploadWorker (one-time).")
            } catch (e: Exception) {
                Log.e("UploadWorker", "Failed to enqueue UploadWorker: ${e.localizedMessage}", e)
            }
        }
    }

    // -----------------------------
    // Helper types & functions
    // -----------------------------
    private fun normalizeNumber(n: String?): String {
        if (n == null) return ""
        val digits = n.filter { it.isDigit() }
        return digits
    }

    private fun leadIdFromPhone(phoneDigits: String): String {
        // deterministic but short hash of digits
        val digest = sha1(phoneDigits).substring(0, 12)
        return "phone_$digest"
    }

    private fun generateCallId(ts: Long): String {
        val rand = Random.nextInt(1000, 9999)
        return "call_${ts}_$rand"
    }

    private fun sha1(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.take(24) // fallback
        }
    }

    /**
     * Best-effort: find a recent open call doc for the given lead+phone.
     * Queries the calls subcollection for the lead ordered by createdAt descending,
     * then picks the first doc that appears not-finalized (no finalizedAt / no finalOutcome).
     * If query fails or nothing suitable is found, returns a generated callId.
     */
    private suspend fun findOpenCallIdForLeadOrGenerate(firestore: FirebaseFirestore, leadId: String, phone: String, ts: Long): String {
        try {
            val callsRef = firestore.collection("leads").document(leadId).collection("calls")
            // Query most recent calls for this phone. (Order and limit is lightweight.)
            val qSnap = callsRef
    .whereEqualTo("phoneNumber", phone)
    .orderBy("createdAt", Query.Direction.DESCENDING)
    .limit(5)
    .get()
    .await()

            if (!qSnap.isEmpty) {
                for (doc in qSnap.documents) {
                    val finalizedAt = doc.get("finalizedAt")
                    val finalOutcome = doc.get("finalOutcome")
                    if (finalizedAt == null && finalOutcome == null) {
                        Log.d(TAG, "Reusing open call doc ${doc.id} for phone=$phone")
                        return doc.id
                    }
                }
            }
        } catch (e: Exception) {
            // Query could fail on missing index or network; fallback to generate
            Log.w(TAG, "Open-call lookup failed for lead=$leadId phone=$phone : ${e.localizedMessage}")
        }
        val gen = generateCallId(ts)
        Log.d(TAG, "No open call found; generated callId=$gen for phone=$phone")
        return gen
    }
}
