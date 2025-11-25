package com.example.call_leads_app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CALL_EVENTS = "com.example.call_leads_app/callEvents"
    private val NATIVE_CHANNEL = "com.example.call_leads_app/native"
    private val OPEN_LEAD_CHANNEL = "com.example.call_leads_app/openLead"
    private val TAG = "MainActivity"
    private val REQUEST_ROLE_DIALER = 32123
    private val PREFS = "call_leads_prefs"
    private val KEY_DIALER_PROMPTED = "dialer_role_prompted_v1"

    // MethodChannel used to forward "openLeadByPhone" calls into Flutter
    private var openLeadMethodChannel: MethodChannel? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // -------------------
        // EVENT CHANNEL SETUP
        // -------------------
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CALL_EVENTS
        ).setStreamHandler(object : EventChannel.StreamHandler {

            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                try {
                    Log.d(TAG, "Flutter EventChannel LISTEN attached.")

                    // Attach event sink to CallService
                    com.example.call_leads_app.callservice.CallService.eventSink = events

                    // Flush pending native events (those buffered while Flutter was detached)
                    com.example.call_leads_app.callservice.CallService.flushPendingToSink()

                } catch (e: Exception) {
                    Log.e(TAG, "onListen error: ${e.localizedMessage}", e)
                }
            }

            override fun onCancel(arguments: Any?) {
                try {
                    Log.d(TAG, "Flutter EventChannel CANCEL called — clearing sink.")
                    com.example.call_leads_app.callservice.CallService.eventSink = null
                } catch (e: Exception) {
                    Log.e(TAG, "onCancel error: ${e.localizedMessage}", e)
                }
            }
        })

        // -------------------
        // METHOD CHANNEL SETUP (existing native channel)
        // -------------------
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NATIVE_CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "requestDialerRole" -> {
                    val ok = requestDialerRole()
                    result.success(ok)
                }

                // NEW! Flutter can call this to flush pending events.
                "flushPendingEvents" -> {
                    try {
                        Log.d(TAG, "Manual flushPendingEvents() called from Flutter")
                        com.example.call_leads_app.callservice.CallService.flushPendingToSink()
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "flushPendingEvents error: ${e.localizedMessage}", e)
                        result.success(false)
                    }
                }

                // NEW: allow Flutter to persist tenantId into native SharedPreferences
                "setTenantId" -> {
                    try {
                        val tenantId = call.argument<String>("tenantId")
                        if (!tenantId.isNullOrEmpty()) {
                            val prefs = getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("tenantId", tenantId).apply()
                            Log.d(TAG, "Native: saved tenantId=$tenantId")
                            result.success(true)
                        } else {
                            Log.w(TAG, "setTenantId called with empty tenantId")
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "setTenantId error: ${e.localizedMessage}", e)
                        result.success(false)
                    }
                }

                // NEW: clear tenantId from native prefs
                "clearTenantId" -> {
                    try {
                        val prefs = getSharedPreferences("call_leads_prefs", Context.MODE_PRIVATE)
                        prefs.edit().remove("tenantId").apply()
                        Log.d(TAG, "Native: cleared tenantId from prefs")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "clearTenantId error: ${e.localizedMessage}", e)
                        result.success(false)
                    }
                }

                else -> result.notImplemented()
            }
        }

        // -------------------
        // OPEN LEAD METHOD CHANNEL (native -> flutter one-off)
        // -------------------
        openLeadMethodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, OPEN_LEAD_CHANNEL)

        // If the activity was launched with extras, forward them to Flutter
        handleIntentForOpenLead(intent)

        // Small delay to allow engine to finish warm-up; then prompt for dialer role once
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ensurePromptForDialerRoleIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "ensurePromptForDialerRoleIfNeeded failed: ${e.localizedMessage}")
            }
        }, 600)
    }

    /**
     * IMPORTANT: signature must be non-null Intent (this is the correct override).
     * onNewIntent is called when the activity is relaunched with a new intent (e.g. tapping notification).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // update the activity intent reference
        setIntent(intent)
        handleIntentForOpenLead(intent)
    }

    private fun handleIntentForOpenLead(intent: Intent?) {
        val phone = intent?.getStringExtra("open_lead_phone")
        if (!phone.isNullOrEmpty()) {
            Log.d(TAG, "handleIntentForOpenLead: forwarding open_lead_phone=$phone to Flutter")
            // Try immediate invoke; if engine not ready, retry shortly.
            try {
                openLeadMethodChannel?.invokeMethod("openLeadByPhone", mapOf("phone" to phone))
            } catch (e: Exception) {
                Log.w(TAG, "invokeMethod failed (engine might be warming). Scheduling retry: ${e.localizedMessage}")
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        openLeadMethodChannel?.invokeMethod("openLeadByPhone", mapOf("phone" to phone))
                    } catch (ex: Exception) {
                        Log.e(TAG, "Retry invokeMethod failed: ${ex.localizedMessage}", ex)
                    }
                }, 300)
            }
        }
    }

    // -------------------
    // REQUEST ROLE DIALER
    // -------------------
    private fun ensurePromptForDialerRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "ensurePromptForDialerRoleIfNeeded: API < 29, skipping.")
            return
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean(KEY_DIALER_PROMPTED, false)
        if (alreadyPrompted) {
            Log.d(TAG, "Dialer role already prompted previously (skip).")
            return
        }

        // Only prompt when activity is in foreground (safety)
        if (!isFinishing && !isChangingConfigurations) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                // Mark that we prompted so we don't spam the user repeatedly
                prefs.edit().putBoolean(KEY_DIALER_PROMPTED, true).apply()

                try {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_ROLE_DIALER)
                    Log.d(TAG, "Launched system role request for DIALER (user will see dialog).")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch role request intent: ${e.localizedMessage}", e)
                }
            } else {
                Log.d(TAG, "RoleManager null or role already held; not prompting.")
            }
        } else {
            Log.d(TAG, "Activity not in foreground; skipping dialer prompt.")
        }
    }

    /**
     * Exposed method to programmatically request the dialer role from Flutter
     */
    private fun requestDialerRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "ROLE_DIALER requires API 29+. Skipped.")
            return false
        }

        val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false

        return try {
            if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivityForResult(intent, REQUEST_ROLE_DIALER)
                true
            } else {
                Log.d(TAG, "Already default dialer.")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestDialerRole error: ${e.localizedMessage}", e)
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ROLE_DIALER) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "User granted default dialer role.")
            } else {
                Log.d(TAG, "User rejected default dialer role.")
            }
        }
    }
}
