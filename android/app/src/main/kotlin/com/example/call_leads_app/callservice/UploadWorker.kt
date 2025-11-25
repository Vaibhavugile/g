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
 * /tenants/{tenantId}/leads/{leadId}/calls/{callId}/events/{eventId}
 *
 * This variant is defensive:
 *  - Builds ops only for items that have a usable phone number (tries to recover using callId).
 *  - Commits in conservative chunks (well under Firestore limits).
 *  - Removes only the contiguous prefix of queued items that were actually uploaded,
 *    preventing accidental deletion of later items when some early items were skipped.
 *  - If an item lacks tenantId, routes it to `tenants/default_tenant/review/queued_items` and marks it.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val TAG = "UploadWorker"
    private val queue = EventQueue(appContext)
    private val PREFS = "call_leads_prefs"

    // Keep these in sync with CallService / receivers
    private val REUSE_WINDOW_MS = 120_000L            // 2 minutes fallback
    private val ACTIVE_CALL_TTL_MS = 60 * 60 * 1000L // 1 hour active TTL

    override suspend fun doWork(): Result {
        try {
            // Garbage-collect stale head entries so they don't block the queue indefinitely.
            // Remove contiguous head items older than 60s (configurable here).
            try {
                val removed = queue.removeOldEntriesOlderThan(60_000L)
                if (removed > 0) Log.w(TAG, "Removed $removed stale head items before processing to avoid blocking.")
            } catch (e: Exception) {
                Log.w(TAG, "removeOldEntriesOlderThan failed: ${e.localizedMessage}")
            }

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
            data class IndexedOp(
                val originalIndex: Int,
                val tenantId: String,
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
                    var phoneRaw = (item["phoneNumber"] as? String) ?: ""

                    // If phone missing, try to recover using callId mapping (fast path)
                    if (phoneRaw.isEmpty()) {
                        val callId = (item["callId"] as? String)
                        if (!callId.isNullOrEmpty()) {
                            val recovered = tryRecoverPhoneForCallId(applicationContext, callId)
                            if (!recovered.isNullOrEmpty()) {
                                phoneRaw = recovered
                                Log.d(TAG, "Recovered phone for queued item idx=$idx via callId=$callId -> $recovered")
                            }
                        }
                    }

                    val phone = normalizeNumber(if (phoneRaw == null) "" else phoneRaw)
                    if (phone.isEmpty()) {
                        // Skip items without usable phone, but keep them in queue for later investigation/recovery.
                        Log.w(TAG, "Skipping queued item with empty phone: $item")
                        continue
                    }

                    val direction = (item["direction"] as? String) ?: "inbound"
                    val outcome = (item["outcome"] as? String) ?: (item["event"] as? String) ?: "unknown"
                    val ts = (item["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    val durNum = item["durationInSeconds"]
                    val duration = when (durNum) {
                        is Number -> durNum.toInt()
                        is String -> durNum.toIntOrNull()
                        else -> null
                    }
                    val callIdFromEvent = (item["callId"] as? String)

                    // tenant handling: prefer explicit tenantId on item, else default to "default_tenant"
                    var tenant = (item["tenantId"] as? String)?.takeIf { it.isNotEmpty() } ?: ""
                    val needsTenantReview = (item["needsTenantReview"] as? Boolean) == true
                    if (tenant.isEmpty()) {
                        if (needsTenantReview) {
                            // route to admin review tenant so data isn't lost
                            tenant = "default_tenant"
                            Log.w(TAG, "Item idx=$idx missing tenantId; routing to default_tenant for review.")
                        } else {
                            // Last-chance attempt: try to recover tenant from local prefs (best-effort)
                            try {
                                val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                val localTenant = prefs.getString("tenantId", null)
                                if (!localTenant.isNullOrEmpty()) {
                                    tenant = localTenant
                                    Log.d(TAG, "Recovered tenant from prefs for idx=$idx: $tenant")
                                } else {
                                    tenant = "default_tenant"
                                    Log.w(TAG, "No tenant available for idx=$idx; using default_tenant fallback.")
                                }
                            } catch (e: Exception) {
                                tenant = "default_tenant"
                                Log.w(TAG, "Error reading tenant from prefs; using default_tenant for idx=$idx: ${e.localizedMessage}")
                            }
                        }
                    }

                    // If callId missing, try to find an open call doc for this phone; else generate one
                    val leadId = leadIdFromPhone(phone)
                    val callId = if (!callIdFromEvent.isNullOrEmpty()) {
                        callIdFromEvent
                    } else {
                        // BEST-EFFORT: query recent calls for this lead under the tenant and reuse an open one
                        findOpenCallIdForLeadOrGenerate(firestore, tenant, leadId, phone, ts)
                    }

                    // If the worker generated a callId (i.e. callIdFromEvent == null and find returned a gen),
                    // ensure we persist a reverse mapping so later retries/EnqueueEventWorker can recover phone.
                    if (callIdFromEvent.isNullOrEmpty()) {
                        try {
                            markCallActiveForPhone(applicationContext, phone, callId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist reverse mapping for generated callId: ${e.localizedMessage}")
                        }
                    }

                    // Build tenant-scoped paths
                    val leadRefPath = "tenants/$tenant/leads/$leadId"
                    val callRefPath = "$leadRefPath/calls/$callId"

                    val leadUpsert = mapOf(
                        "phoneNumber" to phone,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "tenantId" to tenant
                    )

                    val callBase = mapOf(
                        "phoneNumber" to phone,
                        "direction" to direction,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "tenantId" to tenant
                    )

                    val eventData = mutableMapOf<String, Any?>(
                        "outcome" to outcome,
                        "timestamp" to ts,
                        "receivedAt" to (item["receivedAt"] ?: FieldValue.serverTimestamp()),
                        "callId" to callId,
                        "tenantId" to tenant
                    )
                    if (duration != null) eventData["durationInSeconds"] = duration
                    if (callIdFromEvent == null) {
                        // if worker generated or selected callId, record that for traceability
                        eventData["callIdGeneratedByWorker"] = true
                    }
                    if (needsTenantReview) {
                        eventData["needsTenantReview"] = true
                    }

                    val isFinal = (outcome == "ended" || duration != null)
                    val finalizeFields = if (isFinal) {
                        val ff = mutableMapOf<String, Any?>( //
                            "finalOutcome" to outcome,
                            "finalizedAt" to FieldValue.serverTimestamp()
                        )
                        if (duration != null) ff["durationInSeconds"] = duration
                        ff
                    } else null

                    ops.add(
                        IndexedOp(
                            originalIndex = idx,
                            tenantId = tenant,
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
            val CHUNK_SIZE = 200 // conservative
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
        val digestFull = sha1(phoneDigits)
        val safeSub = if (digestFull.length >= 12) digestFull.substring(0, 12) else digestFull
        return "phone_$safeSub"
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
     * Best-effort: find a recent open call doc for the given lead+phone (under tenant).
     * Queries the calls subcollection for the lead ordered by createdAt descending,
     * then picks the first doc that appears not-finalized (no finalizedAt / no finalOutcome).
     * If query fails or nothing suitable is found, returns a generated callId.
     */
    private suspend fun findOpenCallIdForLeadOrGenerate(
        firestore: FirebaseFirestore,
        tenant: String,
        leadId: String,
        phone: String,
        ts: Long
    ): String {
        try {
            val callsRef = firestore.collection("tenants").document(tenant).collection("leads")
                .document(leadId).collection("calls")
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
                        Log.d(TAG, "Reusing open call doc ${doc.id} for phone=$phone under tenant=$tenant")
                        return doc.id
                    }
                }
            }
        } catch (e: Exception) {
            // Query could fail on missing index or network; fallback to generate
            Log.w(TAG, "Open-call lookup failed for tenant=$tenant lead=$leadId phone=$phone : ${e.localizedMessage}")
        }
        val gen = generateCallId(ts)
        Log.d(TAG, "No open call found; generated callId=$gen for phone=$phone under tenant=$tenant")
        return gen
    }

    /**
     * Attempt to recover a phone number using the shared prefs mapping for callId.
     * This is a fast synchronous lookup used as a last-ditch attempt before skipping an item.
     *
     * Improved: prefers direct mapping and then scans legacy keys but only returns candidates
     * that are still active (callid_active_until > now) or very recent (callid_ts within REUSE_WINDOW_MS).
     */
    private fun tryRecoverPhoneForCallId(ctx: Context, callId: String): String? {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // direct reverse mapping
            val direct = prefs.getString("callid_to_phone_$callId", null)
            if (!direct.isNullOrEmpty()) return direct

            // legacy fallback: look for keys named callid_<phone> == callId but check activity/recency
            val all = prefs.all
            val now = System.currentTimeMillis()
            for ((k, v) in all) {
                if (!k.startsWith("callid_")) continue
                // skip helper keys
                if (k.startsWith("callid_to_phone_") || k.startsWith("callid_active_until_") || k.startsWith("callid_ts_")) continue
                val value = v as? String ?: continue
                if (value != callId) continue

                val normalized = k.removePrefix("callid_")
                val activeUntil = prefs.getLong("callid_active_until_$normalized", 0L)
                if (activeUntil > now) {
                    return normalized
                }
                val ts = prefs.getLong("callid_ts_$normalized", 0L)
                if (ts != 0L && (now - ts) <= REUSE_WINDOW_MS) {
                    return normalized
                }
                // else too old, continue scanning
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryRecoverPhoneForCallId failed: ${e.localizedMessage}")
        }
        return null
    }

    /**
     * Persist reverse & forward mappings for an active call so other components can recover phone by callId.
     * Called when the worker generates a callId to ensure later recovery.
     */
    private fun markCallActiveForPhone(ctx: Context, phoneDigitsOrRaw: String, callId: String) {
        try {
            val normalized = normalizeNumber(phoneDigitsOrRaw)
            if (normalized.isEmpty()) return
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("callid_$normalized", callId)
                .putLong("callid_ts_$normalized", now)
                .putLong("callid_active_until_$normalized", now + ACTIVE_CALL_TTL_MS)
                .putString("callid_to_phone_$callId", normalized)
                .apply()
            Log.d(TAG, "Worker-marked call active: $normalized -> $callId")
        } catch (e: Exception) {
            Log.w(TAG, "markCallActiveForPhone failed in worker: ${e.localizedMessage}")
        }
    }
}