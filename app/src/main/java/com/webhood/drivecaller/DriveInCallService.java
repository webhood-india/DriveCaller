package com.webhood.drivecaller;

import android.content.Intent;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.InCallService;

public class DriveInCallService extends InCallService {

    private static DriveInCallService instance;

    private Call currentCall;

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;
    }

    @Override
    public void onCallAdded(Call call) {

        super.onCallAdded(call);

        currentCall = call;

        if (call.getState() == Call.STATE_RINGING) {

            /*
             * Show our incoming-call UI.
             */
            showIncomingUi(call);

            /*
             * Start Drive Mode announcement.
             */
            if (DrivePrefs.isDriveModeEnabled(this)) {

                String number = getNumber(call);

                Intent intent =
                        new Intent(
                                this,
                                DriveService.class
                        );

                intent.setAction(
                        DriveService.RINGING
                );

                intent.putExtra(
                        "number",
                        number
                );

                DriveService.startWithIntent(
                        this,
                        intent
                );
            }

        } else if (
                call.getState() ==
                        Call.STATE_ACTIVE) {

            showOngoingUi(call);
        }

        call.registerCallback(callCallback);
    }

    @Override
    public void onCallRemoved(Call call) {

        try {

            call.unregisterCallback(
                    callCallback
            );

        } catch (Exception ignored) {
        }

        /*
         * Tell DriveService that the call is no longer
         * ringing so all TTS announcements stop immediately.
         */
        if (currentCall == call) {

            currentCall = null;

            try {

                Intent intent =
                        new Intent(
                                this,
                                DriveService.class
                        );

                intent.setAction(
                        DriveService.STOP_ANNOUNCEMENT
                );

                DriveService.startWithIntent(
                        this,
                        intent
                );

            } catch (Exception ignored) {
            }
        }

        super.onCallRemoved(call);
    }

    private final Call.Callback callCallback =
            new Call.Callback() {

                @Override
                public void onStateChanged(
                        Call call,
                        int state) {

                    /*
                     * Call answered.
                     */
                    if (state ==
                            Call.STATE_ACTIVE) {

                        showOngoingUi(call);

                        stopDriveAnnouncements();
                    }

                    /*
                     * Call disconnected/rejected.
                     */
                    if (state ==
                            Call.STATE_DISCONNECTED) {

                        stopDriveAnnouncements();
                    }
                }
            };

    private void stopDriveAnnouncements() {

        try {

            Intent intent =
                    new Intent(
                            this,
                            DriveService.class
                    );

            intent.setAction(
                    DriveService.STOP_ANNOUNCEMENT
            );

            DriveService.startWithIntent(
                    this,
                    intent
            );

        } catch (Exception ignored) {
        }
    }

    private String getNumber(Call call) {

        try {

            Uri handle =
                    call.getDetails()
                            .getHandle();

            return handle == null
                    ? null
                    : handle.getSchemeSpecificPart();

        } catch (Exception e) {

            return null;
        }
    }

    private void showIncomingUi(Call call) {

        Intent intent =
                new Intent(
                        this,
                        IncomingCallActivity.class
                );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                        |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        intent.putExtra(
                "number",
                getNumber(call)
        );

        startActivity(intent);
    }

    private void showOngoingUi(Call call) {

        Intent intent =
                new Intent(
                        this,
                        OngoingCallActivity.class
                );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                        |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        intent.putExtra(
                "number",
                getNumber(call)
        );

        startActivity(intent);
    }

    public static void answerCurrentCall() {

        if (instance == null
                || instance.currentCall == null) {

            return;
        }

        if (instance.currentCall.getState()
                == Call.STATE_RINGING) {

            try {

                instance.currentCall.answer(0);

            } catch (Exception ignored) {
            }
        }
    }

    public static void rejectCurrentCall() {

        if (instance == null
                || instance.currentCall == null) {

            return;
        }

        if (instance.currentCall.getState()
                == Call.STATE_RINGING) {

            try {

                instance.currentCall.disconnect();

            } catch (Exception ignored) {
            }
        }
    }

    public static void endCurrentCall() {

        if (instance == null
                || instance.currentCall == null) {

            return;
        }

        try {

            instance.currentCall.disconnect();

        } catch (Exception ignored) {
        }
    }

    public static boolean hasRingingCall() {

        return instance != null
                && instance.currentCall != null
                && instance.currentCall.getState()
                == Call.STATE_RINGING;
    }

    @Override
    public void onDestroy() {

        /*
         * Make absolutely sure the TTS announcement
         * service is stopped when the telecom service dies.
         */
        try {

            if (instance == this) {

                instance = null;
            }

            currentCall = null;

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }
}
