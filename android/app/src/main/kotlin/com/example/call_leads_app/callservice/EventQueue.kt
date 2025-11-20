package com.example.call_leads_app.callservice

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rock-solid persistent queue stored in SharedPreferences.
 *
 * Improvements:
 *  - Protects against JSON corruption (auto-repair).
 *  - Ensures objects are always JSON-serializable.
 *  - Prevents crashes on null/unsupported values.
 *  - Thread-safe (single lock).
 *  - Maintains strict insertion order.
 *
 * Additional helpers added:
 *  - removeOldEntriesOlderThan: allow garbage-collection of stale head items (by receivedAt).
 */
class EventQueue(private val ctx: Context) {

    private val TAG = "EventQueue"
    private val PREF = "call_leads_queue"
    private val KEY = "pending_events"

    private val prefs get() = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val lock = Any()

    /**
     * Safely parse stored JSON array. If broken, repair by resetting to [].
     */
    private fun loadArray(): JSONArray {
        return try {
            val raw = prefs.getString(KEY, "[]") ?: "[]"
            JSONArray(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupted JSON detected. Resetting queue: ${e.localizedMessage}")
            JSONArray()
        }
    }

    /**
     * Save JSONArray safely.
     */
    private fun saveArray(arr: JSONArray) {
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Convert Kotlin map → safe JSONObject (null → JSONObject.NULL)
     */
    private fun toSafeJson(map: Map<String, Any?>): JSONObject {
        val jo = JSONObject()
        for ((k, v) in map) {
            try {
                when (v) {
                    null -> jo.put(k, JSONObject.NULL)
                    is Number, is String, is Boolean -> jo.put(k, v)
                    else -> jo.put(k, v.toString()) // fallback for any unsupported type
                }
            } catch (e: Exception) {
                // final fallback
                jo.put(k, v?.toString() ?: JSONObject.NULL)
            }
        }
        return jo
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    /**
     * Add event to end of queue.
     */
    fun enqueue(event: Map<String, Any?>) {
        synchronized(lock) {
            val arr = loadArray()
            arr.put(toSafeJson(event))
            saveArray(arr)
        }
    }

    /**
     * Get all events as List<Maps>.
     * Safe conversion: missing/invalid keys won't crash.
     */
    fun peekAll(): List<Map<String, Any?>> {
        synchronized(lock) {
            val arr = loadArray()
            val out = mutableListOf<Map<String, Any?>>()

            for (i in 0 until arr.length()) {
                val jo = arr.optJSONObject(i) ?: continue
                val map = mutableMapOf<String, Any?>()
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jo.opt(key)
                    map[key] = if (value === JSONObject.NULL) null else value
                }
                out.add(map)
            }

            return out
        }
    }

    /**
     * Remove first N events (after successful processing).
     */
    fun removeFirstN(n: Int) {
        if (n <= 0) return

        synchronized(lock) {
            val arr = loadArray()
            val newArr = JSONArray()

            for (i in n until arr.length()) {
                newArr.put(arr.get(i))
            }

            saveArray(newArr)
        }
    }

    /**
     * Clear queue (mainly for debugging).
     */
    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    /**
     * Current number of pending events.
     */
    fun size(): Int {
        synchronized(lock) {
            return loadArray().length()
        }
    }

    /**
     * Remove entries at the head of the queue that are older than `olderThanMs` according to their
     * `receivedAt` field. This helps garbage-collect head items that would otherwise block the queue
     * if they are permanently unresolvable.
     *
     * Returns the number of removed entries.
     */
    fun removeOldEntriesOlderThan(olderThanMs: Long): Int {
        synchronized(lock) {
            val arr = loadArray()
            val now = System.currentTimeMillis()
            var removed = 0
            val newArr = JSONArray()

            for (i in 0 until arr.length()) {
                val jo = arr.optJSONObject(i)
                if (jo == null) continue
                val received = try {
                    // prefer numeric value; tolerate string
                    val v = jo.opt("receivedAt")
                    when (v) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull() ?: now
                        else -> now
                    }
                } catch (e: Exception) {
                    now
                }

                if (i == removed && now - received > olderThanMs) {
                    // only remove contiguous head entries: stop removing once we hit a non-old item
                    removed++
                    continue
                }
                newArr.put(jo)
            }

            if (removed > 0) {
                saveArray(newArr)
                Log.w(TAG, "Removed $removed stale queued entries older than ${olderThanMs}ms to avoid blocking.")
            }
            return removed
        }
    }
}
