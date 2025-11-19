package com.example.call_leads_app.callservice

import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Custom listener for older Android versions (API < 31/S).
 * Passes call state updates back to CallService.
 */
class CallStateListener(private val service: CallService) : PhoneStateListener() {

    @Deprecated("Deprecated in API 31")
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        super.onCallStateChanged(state, incomingNumber)

        // Pass the state change back to the main CallService logic
        service.handleCallStateUpdate(state, incomingNumber)
    }
}