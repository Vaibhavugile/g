package com.example.call_leads_app.callservice

import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Custom callback for modern Android versions (API >= 31/S).
 * Note: Does not provide the phone number, so CallService must track it internally.
 */
@RequiresApi(Build.VERSION_CODES.S)
class CallStateCallback(private val service: CallService) : TelephonyCallback(), TelephonyCallback.CallStateListener {

    override fun onCallStateChanged(state: Int) {
        // TelephonyCallback.CallStateListener does NOT provide the phone number.
        // We pass null for the number and rely on the CallService to use its internal tracking number.
        service.handleCallStateUpdate(state, null)
    }
}