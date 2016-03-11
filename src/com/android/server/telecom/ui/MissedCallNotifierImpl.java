/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.ui;

import android.content.ComponentName;
import android.telecom.TelecomManager;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallerInfoAsyncQueryFactory;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.Constants;
import com.android.server.telecom.ContactsAsyncHelper;
import com.android.server.telecom.Log;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.R;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.components.TelecomBroadcastReceiver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;

import java.util.ArrayList;

import java.util.Locale;

import java.lang.Override;
import java.lang.String;
import java.util.Locale;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * Creates a notification for calls that the user missed (neither answered nor rejected).
 *
 * TODO: Make TelephonyManager.clearMissedCalls call into this class.
 *
 * TODO: Reduce dependencies in this implementation; remove the need to create a new Call
 *     simply to look up caller metadata, and if possible, make it unnecessary to get a
 *     direct reference to the CallsManager. Try to make this class simply handle the UI
 *     and Android-framework entanglements of missed call notification.
 */
public class MissedCallNotifierImpl extends CallsManagerListenerBase implements MissedCallNotifier {

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.NUMBER_PRESENTATION,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    private static final int CALL_LOG_COLUMN_ID = 0;
    private static final int CALL_LOG_COLUMN_NUMBER = 1;
    private static final int CALL_LOG_COLUMN_NUMBER_PRESENTATION = 2;
    private static final int CALL_LOG_COLUMN_DATE = 3;
    private static final int CALL_LOG_COLUMN_DURATION = 4;
    private static final int CALL_LOG_COLUMN_TYPE = 5;

    private static final int MISSED_CALL_NOTIFICATION_ID = 1;

    // notification light default constants
    public static final int DEFAULT_COLOR = 0xFFFFFF; //White
    public static final int DEFAULT_TIME = 1000; // 1 second

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    private final ComponentName mNotificationComponent;

    // Used to track the number of missed calls.
    private int mMissedCallCount = 0;

    public MissedCallNotifierImpl(Context context) {
        mContext = context;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final String notificationComponent = context.getString(R.string.notification_component);

        mNotificationComponent = notificationComponent != null
                ? ComponentName.unflattenFromString(notificationComponent) : null;
    }

    /** {@inheritDoc} */
    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (oldState == CallState.RINGING && newState == CallState.DISCONNECTED &&
                call.getDisconnectCause().getCode() == DisconnectCause.MISSED) {
            showMissedCallNotification(call);
        }
    }

    /** Clears missed call notification and marks the call log's missed calls as read. */
    @Override
    public void clearMissedCalls() {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                // Clear the list of new missed calls from the call log.
                ContentValues values = new ContentValues();
                values.put(Calls.NEW, 0);
                values.put(Calls.IS_READ, 1);
                StringBuilder where = new StringBuilder();
                where.append(Calls.NEW);
                where.append(" = 1 AND ");
                where.append(Calls.TYPE);
                where.append(" = ?");
                try {
                    mContext.getContentResolver().update(Calls.CONTENT_URI, values,
                            where.toString(), new String[]{ Integer.toString(Calls.
                            MISSED_TYPE) });
                } catch (IllegalArgumentException e) {
                    Log.w(this, "ContactsProvider update command failed", e);
                }
            }
        });
        cancelMissedCallNotification();
    }

    /**
     * Broadcasts missed call notification to custom component if set.
     * @param number The phone number associated with the notification. null if
     *               no call.
     * @param count The number of calls associated with the notification.
     * @return {@code true} if the broadcast was sent. {@code false} otherwise.
     */
    private boolean sendNotificationCustomComponent(Call call, int count) {
        if (mNotificationComponent != null) {
            Intent intent = new Intent();
            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.setComponent(mNotificationComponent);
            intent.setAction(TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION);
            intent.putExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, count);
            intent.putExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER,
                    call != null ? call.getPhoneNumber() : null);
            intent.putExtra(TelecomManager.EXTRA_CLEAR_MISSED_CALLS_INTENT,
                    createClearMissedCallsPendingIntent());


            if (count == 1 && call != null) {
                final Uri handleUri = call.getHandle();
                String handle = handleUri == null ? null : handleUri.getSchemeSpecificPart();

                if (!TextUtils.isEmpty(handle) && !TextUtils.equals(handle,
                        mContext.getString(R.string.handle_restricted))) {
                    intent.putExtra(TelecomManager.EXTRA_CALL_BACK_INTENT,
                            createCallBackPendingIntent(handleUri));
                }
            }

            mContext.sendBroadcast(intent);
            return true;
        }

        return false;
    }

    /**
     * Create a system notification for the missed call.
     *
     * @param call The missed call.
     */
    @Override
    public void showMissedCallNotification(Call call) {
        mMissedCallCount++;

        if (sendNotificationCustomComponent(call, mMissedCallCount)) {
            return;
        }

        final int titleResId;
        final String expandedText;  // The text in the notification's line 1 and 2.

        // Display the first line of the notification:
        // 1 missed call: <caller name || handle>
        // More than 1 missed call: <number of calls> + "missed calls"
        if (mMissedCallCount == 1) {
            titleResId = R.string.notification_missedCallTitle;
            expandedText = getNameForCall(call);
        } else {
            titleResId = R.string.notification_missedCallsTitle;
            expandedText =
                    mContext.getString(R.string.notification_missedCallsMsg, mMissedCallCount);
        }

        // Create a public viewable version of the notification, suitable for display when sensitive
        // notification content is hidden.
        Notification.Builder publicBuilder = new Notification.Builder(mContext);
        publicBuilder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setColor(mContext.getResources().getColor(R.color.theme_color))
                .setWhen(call.getCreationTimeMillis())
                // Show "Phone" for notification title.
                .setContentTitle(mContext.getText(R.string.userCallActivityLabel))
                // Notification details shows that there are missed call(s), but does not reveal
                // the missed caller information.
                .setContentText(mContext.getText(titleResId))
                .setContentIntent(createCallLogPendingIntent())
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsPendingIntent());

        // Create the notification suitable for display when sensitive information is showing.
        Notification.Builder builder = new Notification.Builder(mContext);
        if (Settings.System.getInt(mContext.getContentResolver(),
               Settings.System.KEY_MISSED_CALL_BREATH, 0) == 1) {
                builder.setSmallIcon(R.drawable.stat_notify_missed_call_breath)
                .setWhen(call.getCreationTimeMillis())
                .setContentIntent(createCallLogPendingIntent())
                .setAutoCancel(true)
		.setContentTitle(mContext.getText(titleResId))
		.setContentText(expandedText)
                .setDeleteIntent(createClearMissedCallsPendingIntent());
             } else {
                builder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setWhen(call.getCreationTimeMillis())
                .setContentIntent(createCallLogPendingIntent())
                .setAutoCancel(true)
		.setContentTitle(mContext.getText(titleResId))
		.setContentText(expandedText)
                .setDeleteIntent(createClearMissedCallsPendingIntent())
                // Include a public version of the notification to be shown when the missed call
                // notification is shown on the user's lock screen and they have chosen to hide
                // sensitive notification information.
                .setPublicVersion(publicBuilder.build());
             }

        Uri handleUri = call.getHandle();
        String handle = handleUri == null ? null : handleUri.getSchemeSpecificPart();

        // Add additional actions when there is only 1 missed call, like call-back and SMS.
        if (mMissedCallCount == 1) {
            Log.d(this, "Add actions with number %s.", Log.piiHandle(handle));

            if (!TextUtils.isEmpty(handle)
                    && !TextUtils.equals(handle, mContext.getString(R.string.handle_restricted))) {
                builder.addAction(R.drawable.ic_phone_24dp,
                        mContext.getString(R.string.notification_missedCall_call_back),
                        createCallBackPendingIntent(handleUri));

                if (canRespondViaSms(call)) {
                    builder.addAction(R.drawable.ic_message_24dp,
                            mContext.getString(R.string.notification_missedCall_message),
                            createSendSmsFromNotificationPendingIntent(handleUri));
                }
            }

            Bitmap photoIcon = call.getPhotoIcon();
            if (photoIcon != null) {
                builder.setLargeIcon(photoIcon);
            } else {
                Drawable photo = call.getPhoto();
                if (photo != null && photo instanceof BitmapDrawable) {
                    builder.setLargeIcon(((BitmapDrawable) photo).getBitmap());
                }
            }
        } else {
            Log.d(this, "Suppress actions. handle: %s, missedCalls: %d.", Log.piiHandle(handle),
                    mMissedCallCount);
        }

        Notification notification = builder.build();
        configureLedNotification(mContext, notification);

        Log.i(this, "Adding missed call notification for %s.", call);
        long token = Binder.clearCallingIdentity();
        try {
            mNotificationManager.notifyAsUser(
                    null /* tag */, MISSED_CALL_NOTIFICATION_ID, notification, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Cancels the "missed call" notification. */
    private void cancelMissedCallNotification() {
        // Reset the number of missed calls to 0.
        mMissedCallCount = 0;


        if (sendNotificationCustomComponent(null, mMissedCallCount)) {
            return;
        }

        long token = Binder.clearCallingIdentity();
        try {
            mNotificationManager.cancelAsUser(null, MISSED_CALL_NOTIFICATION_ID,
                    UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the name to use in the missed call notification.
     */
    private String getNameForCall(Call call) {
        String handle = call.getHandle() == null ? null : call.getHandle().getSchemeSpecificPart();
        String name = call.getName();

        if (!TextUtils.isEmpty(handle)) {
            String formattedNumber = PhoneNumberUtils.formatNumber(handle,
                    getCurrentCountryIso(mContext));

            // The formatted number will be null if there was a problem formatting it, but we can
            // default to using the unformatted number instead (e.g. a SIP URI may not be able to
            // be formatted.
            if (!TextUtils.isEmpty(formattedNumber)) {
                handle = formattedNumber;
            }
        }

        if (!TextUtils.isEmpty(name) && TextUtils.isGraphic(name)) {
            return name;
        } else if (!TextUtils.isEmpty(handle)) {
            // A handle should always be displayed LTR using {@link BidiFormatter} regardless of the
            // content of the rest of the notification.
            // TODO: Does this apply to SIP addresses?
            BidiFormatter bidiFormatter = BidiFormatter.getInstance();
            return bidiFormatter.unicodeWrap(handle, TextDirectionHeuristics.LTR);
        } else {
            // Use "unknown" if the call is unidentifiable.
            return mContext.getString(R.string.unknown);
        }
    }

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in based on the
     *      network location.  If the network location does not exist, fall back to the locale
     *      setting.
     */
    private String getCurrentCountryIso(Context context) {
        // Without framework function calls, this seems to be the most accurate location service
        // we can rely on.
        final TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String countryIso = telephonyManager.getNetworkCountryIso().toUpperCase();

        if (countryIso == null) {
            countryIso = Locale.getDefault().getCountry();
            Log.w(this, "No CountryDetector; falling back to countryIso based on locale: "
                    + countryIso);
        }
        return countryIso;
    }

    /**
     * Creates a new pending intent that sends the user to the call log.
     *
     * @return The pending intent.
     */
    private PendingIntent createCallLogPendingIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType(Calls.CONTENT_TYPE);

        TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(mContext);
        taskStackBuilder.addNextIntent(intent);

        return taskStackBuilder.getPendingIntent(0, 0, null, UserHandle.CURRENT);
    }

    /**
     * Creates an intent to be invoked when the missed call notification is cleared.
     */
    private PendingIntent createClearMissedCallsPendingIntent() {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_CLEAR_MISSED_CALLS, null);
    }

    /**
     * Creates an intent to be invoked when the user opts to "call back" from the missed call
     * notification.
     *
     * @param handle The handle to call back.
     */
    private PendingIntent createCallBackPendingIntent(Uri handle) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_BACK_FROM_NOTIFICATION, handle);
    }

    /**
     * Creates an intent to be invoked when the user opts to "send sms" from the missed call
     * notification.
     */
    private PendingIntent createSendSmsFromNotificationPendingIntent(Uri handle) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, handle.getSchemeSpecificPart(), null));
    }

    /**
     * Creates generic pending intent from the specified parameters to be received by
     * {@link TelecomBroadcastIntentProcessor}.
     *
     * @param action The intent action.
     * @param data The intent data.
     */
    private PendingIntent createTelecomPendingIntent(String action, Uri data) {
        Intent intent = new Intent(action, data, mContext, TelecomBroadcastReceiver.class);
        return PendingIntent.getBroadcast(mContext, 0, intent, 0);
    }

    /**
     * Configures a Notification to emit the blinky message-waiting/
     * missed-call signal.
     */
    private static void configureLedNotification(Context context, Notification notification) {
        ContentResolver resolver = context.getContentResolver();

        boolean lightEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE, 0) == 1;
        if (!lightEnabled) {
            return;
        }
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;

        // Get Missed call and Voice mail values if they are to be used
        boolean customEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE, 0) == 1;
        if (!customEnabled) {
            notification.defaults |= Notification.DEFAULT_LIGHTS;
            return;
        }

        notification.ledARGB = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_COLOR, DEFAULT_COLOR);
        notification.ledOnMS = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_ON, DEFAULT_TIME);
        notification.ledOffMS = Settings.System.getInt(resolver,
            Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_OFF, DEFAULT_TIME);
    }

    private boolean canRespondViaSms(Call call) {
        // Only allow respond-via-sms for "tel:" calls.
        return call.getHandle() != null &&
                PhoneAccount.SCHEME_TEL.equals(call.getHandle().getScheme());
    }

    /**
     * Adds the missed call notification on startup if there are unread missed calls.
     */
    @Override
    public void updateOnStartup(
            final TelecomSystem.SyncRoot lock,
            final CallsManager callsManager,
            final ContactsAsyncHelper contactsAsyncHelper,
            final CallerInfoAsyncQueryFactory callerInfoAsyncQueryFactory) {
        Log.d(this, "updateOnStartup()...");

        // instantiate query handler
        AsyncQueryHandler queryHandler = new AsyncQueryHandler(mContext.getContentResolver()) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                Log.d(MissedCallNotifierImpl.this, "onQueryComplete()...");
                if (cursor != null) {
                    try {
                        while (cursor.moveToNext()) {
                            // Get data about the missed call from the cursor
                            final String handleString = cursor.getString(CALL_LOG_COLUMN_NUMBER);
                            final int presentation =
                                    cursor.getInt(CALL_LOG_COLUMN_NUMBER_PRESENTATION);
                            final long date = cursor.getLong(CALL_LOG_COLUMN_DATE);

                            final Uri handle;
                            if (presentation != Calls.PRESENTATION_ALLOWED
                                    || TextUtils.isEmpty(handleString)) {
                                handle = null;
                            } else {
                                handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(handleString) ?
                                        PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL,
                                                handleString, null);
                            }

                            synchronized (lock) {

                                // Convert the data to a call object
                                Call call = new Call(mContext, callsManager, lock,
                                        null, contactsAsyncHelper, callerInfoAsyncQueryFactory,
                                        null, null, null, null, true, false);
                                call.setDisconnectCause(
                                        new DisconnectCause(DisconnectCause.MISSED));
                                call.setState(CallState.DISCONNECTED, "throw away call");
                                call.setCreationTimeMillis(date);

                                // Listen for the update to the caller information before posting
                                // the notification so that we have the contact info and photo.
                                call.addListener(new Call.ListenerBase() {
                                    @Override
                                    public void onCallerInfoChanged(Call call) {
                                        call.removeListener(
                                                this);  // No longer need to listen to call
                                        // changes after the contact info
                                        // is retrieved.
                                        showMissedCallNotification(call);
                                    }
                                });
                                // Set the handle here because that is what triggers the contact
                                // info query.
                                call.setHandle(handle, presentation);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        };

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");
        where.append(" AND is_read=0");

        // start the query
        queryHandler.startQuery(0, null, Calls.CONTENT_URI, CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);
    }
}
