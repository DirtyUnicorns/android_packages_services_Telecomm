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

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemVibrator;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.CallState;
import android.telephony.SubscriptionManager;

import java.util.LinkedList;
import java.util.List;

/**
 * Controls the ringtone player.
 */
final class Ringer extends CallsManagerListenerBase {
    private static final long[] VIBRATION_PATTERN = new long[] {
        0, // No delay before starting
        1000, // How long to vibrate
        1000, // How long to wait before vibrating again
    };

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();

    /** Indicate that we want the pattern to repeat at the step which turns on vibration. */
    private static final int VIBRATION_PATTERN_REPEAT = 1;

    private final AsyncRingtonePlayer mRingtonePlayer;

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */
    private final List<Call> mRingingCalls = new LinkedList<>();

    private final CallAudioManager mCallAudioManager;
    private final CallsManager mCallsManager;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final Context mContext;
    private final Vibrator mVibrator;

    private InCallTonePlayer mCallWaitingPlayer;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private boolean mIsVibrating = false;

    /** Initializes the Ringer. */
    Ringer(
            CallAudioManager callAudioManager,
            CallsManager callsManager,
            InCallTonePlayer.Factory playerFactory,
            Context context) {

        mCallAudioManager = callAudioManager;
        mCallsManager = callsManager;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = new SystemVibrator(context);
        mRingtonePlayer = new AsyncRingtonePlayer(context);
    }

    @Override
    public void onCallAdded(final Call call) {
        if (call.isIncoming() && call.getState() == CallState.RINGING) {
            if (mRingingCalls.contains(call)) {
                Log.wtf(this, "New ringing call is already in list of unanswered calls");
            }
            mRingingCalls.add(call);
            updateRinging();
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        removeFromUnansweredCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (newState != CallState.RINGING) {
            removeFromUnansweredCall(call);
        }
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        onRespondedToIncomingCall(call);
    }

    @Override
    public void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage) {
        onRespondedToIncomingCall(call);
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        if (mRingingCalls.contains(oldForegroundCall) ||
                mRingingCalls.contains(newForegroundCall)) {
            updateRinging();
        }
    }

    /**
     * Silences the ringer for any actively ringing calls.
     */
    void silence() {
        // Remove all calls from the "ringing" set and then update the ringer.
        mRingingCalls.clear();
        updateRinging();
    }

    private void onRespondedToIncomingCall(Call call) {
        // Only stop the ringer if this call is the top-most incoming call.
        if (getTopMostUnansweredCall() == call) {
            removeFromUnansweredCall(call);
        }
    }

    private Call getTopMostUnansweredCall() {
        return mRingingCalls.isEmpty() ? null : mRingingCalls.get(0);
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls and updates the ringer
     * based on the new state of {@link #mRingingCalls}. Safe to call with a call that is not
     * present in the list of incoming calls.
     */
    private void removeFromUnansweredCall(Call call) {
        mRingingCalls.remove(call);
        updateRinging();
    }

    private void updateRinging() {
        if (mRingingCalls.isEmpty()) {
            stopRinging();
            stopCallWaiting();
        } else {
            startRingingOrCallWaiting();
        }
    }

    private void startRingingOrCallWaiting() {
        Call foregroundCall = mCallsManager.getForegroundCall();
        Log.v(this, "startRingingOrCallWaiting, foregroundCall: %s.", foregroundCall);

        if (mRingingCalls.contains(foregroundCall) && (!mCallsManager.hasActiveOrHoldingCall())) {
            // The foreground call is one of incoming calls so play the ringer out loud.
            stopCallWaiting();

            if (!shouldRingForContact(foregroundCall.getContactUri())) {
                return;
            }

            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager.getStreamVolume(AudioManager.STREAM_RING) >= 0) {
                Log.v(this, "startRingingOrCallWaiting");

                float startVolume = 0;
                int rampUpTime = 0;

                final ContentResolver cr = mContext.getContentResolver();
                if (Settings.System.getInt(cr, Settings.System.INCREASING_RING, 0) != 0) {
                    startVolume = Settings.System.getFloat(cr,
                            Settings.System.INCREASING_RING_START_VOLUME, 0.1f);
                    rampUpTime = Settings.System.getInt(cr,
                            Settings.System.INCREASING_RING_RAMP_UP_TIME, 20);
                }

                mCallAudioManager.setIsRinging(true);

                // Because we wait until a contact info query to complete before processing a
                // call (for the purposes of direct-to-voicemail), the information about custom
                // ringtones should be available by the time this code executes. We can safely
                // request the custom ringtone from the call and expect it to be current.
                try {
                    int phoneId = SubscriptionManager.getPhoneId(Integer.valueOf(
                            foregroundCall.getTargetPhoneAccount().getId()));
                    mRingtonePlayer.setPhoneId(phoneId);
                } catch (NumberFormatException e) {
                    Log.w(this,"Subid is not a number " + e);
                }
                mRingtonePlayer.play(foregroundCall.getRingtone(), startVolume, rampUpTime);
            } else {
                Log.v(this, "startRingingOrCallWaiting, skipping because volume is 0");
            }

            if (shouldVibrate(mContext) && !mIsVibrating) {
                mVibrator.vibrate(VIBRATION_PATTERN, VIBRATION_PATTERN_REPEAT,
                        VIBRATION_ATTRIBUTES);
                mIsVibrating = true;
            }
        } else if (foregroundCall != null) {
            // The first incoming call added to Telecom is not a foreground call at this point
            // in time. If the current foreground call is null at point, don't play call-waiting
            // as the call will eventually be promoted to the foreground call and play the
            // ring tone.
            Log.v(this, "Playing call-waiting tone.");

            // All incoming calls are in background so play call waiting.
            stopRinging();

            if (mCallWaitingPlayer == null) {
                mCallWaitingPlayer =
                        mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mCallWaitingPlayer.startTone();
            }
        }
    }

    private boolean shouldRingForContact(Uri contactUri) {
        final NotificationManager manager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final Bundle extras = new Bundle();
        if (contactUri != null) {
            extras.putStringArray(Notification.EXTRA_PEOPLE, new String[] {contactUri.toString()});
        }
        return manager.matchesCallFilter(extras);
    }

    private void stopRinging() {
        Log.v(this, "stopRinging");

        mRingtonePlayer.stop();

        if (mIsVibrating) {
            mVibrator.cancel();
            mIsVibrating = false;
        }

        // Even though stop is asynchronous it's ok to update the audio manager. Things like audio
        // focus are voluntary so releasing focus too early is not detrimental.
        mCallAudioManager.setIsRinging(false);
    }

    private void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    private boolean shouldVibrate(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerModeInternal();
        if (getVibrateWhenRinging(context)) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }
}
