/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.CallState;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.ITelephonyRegistry;

/**
 * Send a {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} broadcast when the call state
 * changes.
 */
final class PhoneStateBroadcaster extends CallsManagerListenerBase {

    private final ITelephonyRegistry mRegistry;
    private int mCurrentState = TelephonyManager.CALL_STATE_IDLE;

    public PhoneStateBroadcaster() {
        mRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                "telephony.registry"));
        if (mRegistry == null) {
            Log.w(this, "TelephonyRegistry is null");
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if ((newState == CallState.DIALING || newState == CallState.ACTIVE
                || newState == CallState.ON_HOLD) && !CallsManager.getInstance().hasRingingCall()) {
            /*
             * EXTRA_STATE_RINGING takes precedence over EXTRA_STATE_OFFHOOK, so if there is
             * already a ringing call, don't broadcast EXTRA_STATE_OFFHOOK.
             */
            sendPhoneStateChangedBroadcast(call, TelephonyManager.CALL_STATE_OFFHOOK);
        }
    }

    @Override
    public void onCallAdded(Call call) {
        if (call.getState() == CallState.RINGING) {
            sendPhoneStateChangedBroadcast(call, TelephonyManager.CALL_STATE_RINGING);
        } else if (call.getState() == CallState.PRE_DIAL_WAIT ||
                call.getState() == CallState.CONNECTING) {
            sendPhoneStateChangedBroadcast(call, TelephonyManager.CALL_STATE_OFFHOOK);
        }
    };

    @Override
    public void onCallRemoved(Call call) {
        // Recalculate the current phone state based on the consolidated state of the remaining
        // calls in the call list.
        final CallsManager callsManager = CallsManager.getInstance();
        int callState = TelephonyManager.CALL_STATE_IDLE;
        if (callsManager.hasRingingCall()) {
            callState = TelephonyManager.CALL_STATE_RINGING;
        } else if (callsManager.getFirstCallWithState(CallState.DIALING, CallState.ACTIVE,
                    CallState.ON_HOLD) != null) {
            callState = TelephonyManager.CALL_STATE_OFFHOOK;
        }
        sendPhoneStateChangedBroadcast(call, callState);
    }

    int getCallState() {
        return mCurrentState;
    }

    private void sendPhoneStateChangedBroadcast(Call call, int phoneState) {
        if (phoneState == mCurrentState) {
            return;
        }

        mCurrentState = phoneState;

        String callHandle = null;
        if (call.getHandle() != null) {
            callHandle = call.getHandle().getSchemeSpecificPart();
        }

        try {
            if (mRegistry != null) {
                mRegistry.notifyCallState(phoneState, callHandle);
                Log.i(this, "Broadcasted state change: %s", mCurrentState);
            }
        } catch (RemoteException e) {
            Log.w(this, "RemoteException when notifying TelephonyRegistry of call state change.");
        }
    }
}
