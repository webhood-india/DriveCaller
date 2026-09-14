package com.webhood.drivecaller;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DriveService extends Service {

    public static final String RINGING =
            "com.webhood.drivecaller.RINGING";

    public static final String STOP_ANNOUNCEMENT =
            "com.webhood.drivecaller.STOP_ANNOUNCEMENT";

    private static final String CHANNEL = "drive_mode";
    private static final int NOTIFICATION_ID = 7001;

    private static final long ANNOUNCEMENT_INTERVAL = 5000;

    // Max time we'll wait for the Bluetooth SCO link to come up
    // before speaking anyway (better late than never / silent).
    private static final long SCO_CONNECT_TIMEOUT = 2000;

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;

    private boolean listening = false;
    private boolean announcementActive = false;

    private String currentCallerAnnouncement;
    private String pendingAnnouncement;

    // True once we've confirmed (via the SCO broadcast) that audio
    // is actually flowing to the Bluetooth device.
    private boolean scoReady = false;
    private boolean scoReceiverRegistered = false;

    // Original ring stream volume, saved so we can restore it once
    // the announcement is done. -1 means "nothing saved".
    private int savedRingVolume = -1;

    private final Handler handler =
            new Handler(android.os.Looper.getMainLooper());

    public static void start(Context context) {
        Intent intent = new Intent(context, DriveService.class);

        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void startWithIntent(
            Context context,
            Intent intent
    ) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(
                new Intent(context, DriveService.class)
        );
    }

    public static boolean isEnabled(Context context) {
        return DrivePrefs.isDriveModeEnabled(context);
    }

    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            int state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
            );

            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {

                scoReady = true;
                handler.removeCallbacks(scoTimeoutRunnable);
                speakNowIfPending();

            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {

                scoReady = false;
            }
        }
    };

    private final Runnable scoTimeoutRunnable = this::speakNowIfPending;

    // Holds a speak request that's waiting on the SCO link.
    private String awaitingSpeak;

    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification("Drive Mode is active")
        );

        registerScoReceiver();

        tts = new TextToSpeech(this, status -> {

            if (status == TextToSpeech.SUCCESS) {

                tts.setLanguage(Locale.getDefault());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

                    AudioAttributes audioAttributes =
                            new AudioAttributes.Builder()
                                    .setUsage(
                                            AudioAttributes.USAGE_VOICE_COMMUNICATION
                                    )
                                    .setContentType(
                                            AudioAttributes.CONTENT_TYPE_SPEECH
                                    )
                                    .build();

                    tts.setAudioAttributes(audioAttributes);
                }

                // If a call arrived before TTS finished initializing,
                // speak the queued announcement now.
                if (pendingAnnouncement != null
                        && announcementActive
                        && DriveInCallService.hasRingingCall()) {

                    requestBluetoothAudioThenSpeak(pendingAnnouncement);

                    pendingAnnouncement = null;
                }
            }
        });
    }

    private void registerScoReceiver() {

        if (scoReceiverRegistered) return;

        IntentFilter filter = new IntentFilter(
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scoReceiver, filter);
        }

        scoReceiverRegistered = true;
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent != null) {

            if (RINGING.equals(intent.getAction())) {

                String number =
                        intent.getStringExtra("number");

                announceCaller(number);

            } else if (STOP_ANNOUNCEMENT.equals(intent.getAction())) {

                stopCallerAnnouncements();
            }
        }

        return START_STICKY;
    }

    private void announceCaller(String number) {

        String name =
                ContactUtils.lookupName(this, number);

        String spoken;

        if (name == null || name.trim().isEmpty()) {
            spoken = "Call coming from unknown.";
        } else {
            spoken = "Call coming from " + name + ".";
        }

        currentCallerAnnouncement = spoken;
        announcementActive = true;

        // Turn the system ringtone down so the announcement is
        // actually audible instead of being masked by it. Being the
        // default dialer does NOT silence the system ring - Telecom
        // plays it independently of our app.
        duckRingtone();

        if (tts != null
                && tts.isLanguageAvailable(Locale.getDefault())
                        >= TextToSpeech.LANG_AVAILABLE) {

            requestBluetoothAudioThenSpeak(spoken);

        } else {

            pendingAnnouncement = spoken;
        }

        // Repeat announcement every 5 seconds while the phone
        // is still ringing.
        scheduleNextAnnouncement();

        // Give TTS time to finish before listening.
        handler.postDelayed(
                this::startListening,
                2500
        );
    }

    private void speakCallerAnnouncement() {

        if (!announcementActive) return;

        if (!DrivePrefs.isDriveModeEnabled(this)) return;

        if (!DriveInCallService.hasRingingCall()) {
            stopCallerAnnouncements();
            return;
        }

        if (tts == null) return;

        // SCO/communication routing was already set up for the first
        // announcement and should still be active - speak directly
        // instead of re-running the whole handshake every repeat.
        if (scoReady || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tts.speak(
                    currentCallerAnnouncement,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "caller"
            );
        } else {
            requestBluetoothAudioThenSpeak(currentCallerAnnouncement);
        }
    }

    /**
     * Route audio to the Bluetooth device, then speak - waiting for
     * confirmation that the SCO link is actually up (with a timeout
     * fallback) rather than guessing with a fixed delay.
     */
    private void requestBluetoothAudioThenSpeak(String text) {

        awaitingSpeak = text;

        boolean startedHandshake = configureCommunicationAudio();

        if (!startedHandshake) {
            // No Bluetooth path available at all (earbuds not
            // connected, or connected but without a calling
            // profile) - speak through whatever output is default
            // rather than staying silent.
            speakNowIfPending();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // setCommunicationDevice() takes effect immediately once
            // a matching device is found - no need to wait on SCO
            // broadcasts (those are for the classic path).
            speakNowIfPending();
        } else {
            handler.postDelayed(scoTimeoutRunnable, SCO_CONNECT_TIMEOUT);
        }
    }

    private void speakNowIfPending() {

        if (awaitingSpeak == null || tts == null) return;

        String text = awaitingSpeak;
        awaitingSpeak = null;

        tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "caller"
        );
    }

    private void scheduleNextAnnouncement() {

        handler.postDelayed(() -> {

            if (!announcementActive) {
                return;
            }

            if (!DrivePrefs.isDriveModeEnabled(this)) {
                stopCallerAnnouncements();
                return;
            }

            if (!DriveInCallService.hasRingingCall()) {
                stopCallerAnnouncements();
                return;
            }

            speakCallerAnnouncement();

            scheduleNextAnnouncement();

        }, ANNOUNCEMENT_INTERVAL);
    }

    private void stopCallerAnnouncements() {

        announcementActive = false;
        currentCallerAnnouncement = null;
        pendingAnnouncement = null;
        awaitingSpeak = null;

        handler.removeCallbacksAndMessages(null);

        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }

        releaseCommunicationAudio();
        restoreRingtone();
    }

    /**
     * Lower the ring stream so the spoken announcement is actually
     * audible over it. We deliberately stop at the lowest NON-ZERO
     * step rather than fully muting: taking the ring stream to 0
     * requires "Do Not Disturb" / Notification Policy Access, which
     * most users won't have granted, and setStreamVolume() throws a
     * SecurityException without it. A near-silent ring plus a clear
     * announcement in the earbuds is good enough and needs no extra
     * permission.
     */
    private void duckRingtone() {

        try {

            AudioManager audioManager =
                    (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (audioManager == null) return;

            if (savedRingVolume == -1) {
                savedRingVolume =
                        audioManager.getStreamVolume(AudioManager.STREAM_RING);
            }

            audioManager.setStreamVolume(
                    AudioManager.STREAM_RING,
                    1,
                    0
            );

        } catch (SecurityException ignored) {
            // No permission to touch the ring stream - the
            // announcement will have to compete with the ring as-is.
        } catch (Exception ignored) {
        }
    }

    private void restoreRingtone() {

        if (savedRingVolume == -1) return;

        try {

            AudioManager audioManager =
                    (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (audioManager != null) {

                audioManager.setStreamVolume(
                        AudioManager.STREAM_RING,
                        savedRingVolume,
                        0
                );
            }

        } catch (Exception ignored) {
        } finally {
            savedRingVolume = -1;
        }
    }

    /**
     * Route communication audio (TTS output + speech recognizer
     * input) to the connected Bluetooth device.
     *
     * Returns true if a Bluetooth routing attempt was actually
     * started (either the modern setCommunicationDevice path or the
     * classic SCO handshake). Returns false if there's no usable
     * Bluetooth audio device at all, so the caller can fall back to
     * speaking through the default output instead of going silent.
     */
    private boolean configureCommunicationAudio() {

        try {

            AudioManager audioManager =
                    (AudioManager) getSystemService(
                            Context.AUDIO_SERVICE
                    );

            if (audioManager == null) {
                return false;
            }

            audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                List<AudioDeviceInfo> devices =
                        audioManager.getAvailableCommunicationDevices();

                AudioDeviceInfo bluetoothDevice = null;

                for (AudioDeviceInfo device : devices) {

                    int type = device.getType();

                    boolean isBluetooth =
                            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                            && type == AudioDeviceInfo.TYPE_BLE_HEADSET);

                    if (isBluetooth) {
                        bluetoothDevice = device;
                        break;
                    }
                }

                if (bluetoothDevice != null) {
                    audioManager.setCommunicationDevice(bluetoothDevice);
                    return true;
                }

                // No Bluetooth communication device exposed - fall
                // through to the legacy SCO handshake as a second
                // attempt, some OEMs only populate this list after
                // startBluetoothSco() has been called at least once.
            }

            return startLegacyBluetoothSco(audioManager);

        } catch (Exception e) {
            return false;
        }
    }

    private boolean startLegacyBluetoothSco(AudioManager audioManager) {

        try {

            if (!isBluetoothAudioConnected(this)) {
                return false;
            }

            if (!audioManager.isBluetoothScoOn()) {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private void releaseCommunicationAudio() {

        try {

            AudioManager audioManager =
                    (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (audioManager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }

            if (audioManager.isBluetoothScoOn()) {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }

            audioManager.setMode(AudioManager.MODE_NORMAL);

        } catch (Exception ignored) {
        }

        scoReady = false;
    }

    private void startListening() {

        if (!DrivePrefs.isDriveModeEnabled(this)) {
            return;
        }

        if (!DriveInCallService.hasRingingCall()) {
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return;
        }

        if (listening) {
            return;
        }

        if (recognizer == null) {

            recognizer =
                    SpeechRecognizer.createSpeechRecognizer(this);

            recognizer.setRecognitionListener(
                    new RecognitionListener() {

                        @Override
                        public void onReadyForSpeech(
                                android.os.Bundle params
                        ) {
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                        }

                        @Override
                        public void onRmsChanged(float rmsdB) {
                        }

                        @Override
                        public void onBufferReceived(
                                byte[] buffer
                        ) {
                        }

                        @Override
                        public void onEndOfSpeech() {
                            listening = false;
                        }

                        @Override
                        public void onPartialResults(
                                android.os.Bundle partialResults
                        ) {
                        }

                        @Override
                        public void onEvent(
                                int eventType,
                                android.os.Bundle params
                        ) {
                        }

                        @Override
                        public void onError(int error) {

                            listening = false;

                            if (DriveInCallService.hasRingingCall()
                                    && DrivePrefs.isDriveModeEnabled(
                                    DriveService.this)) {

                                handler.postDelayed(
                                        DriveService.this::startListening,
                                        1000
                                );
                            }
                        }

                        @Override
                        public void onResults(
                                android.os.Bundle results
                        ) {

                            listening = false;

                            ArrayList<String> matches =
                                    results.getStringArrayList(
                                            SpeechRecognizer
                                                    .RESULTS_RECOGNITION
                                    );

                            if (matches == null) {
                                startListeningAgain();
                                return;
                            }

                            for (String raw : matches) {

                                if (raw == null) {
                                    continue;
                                }

                                String text =
                                        raw.toLowerCase(
                                                Locale.ROOT
                                        ).trim();

                                // ANSWER COMMANDS
                                if (containsAny(
                                        text,
                                        "accept",
                                        "yes",
                                        "answer",
                                        "pick up",
                                        "accept call",
                                        "answer call",
                                        "take call"
                                )) {

                                    stopCallerAnnouncements();

                                    DriveInCallService
                                            .answerCurrentCall();

                                    return;
                                }

                                // REJECT COMMANDS
                                if (containsAny(
                                        text,
                                        "reject",
                                        "no",
                                        "decline",
                                        "hang up",
                                        "hangup",
                                        "reject call",
                                        "decline call",
                                        "cut call"
                                )) {

                                    stopCallerAnnouncements();

                                    DriveInCallService
                                            .rejectCurrentCall();

                                    return;
                                }
                            }

                            startListeningAgain();
                        }
                    }
            );
        }

        Intent speech =
                new Intent(
                        RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                );

        speech.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        speech.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
        );

        speech.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                5
        );

        speech.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
        );

        try {

            listening = true;

            recognizer.startListening(speech);

        } catch (Exception ignored) {

            listening = false;
        }
    }

    private void startListeningAgain() {

        if (!DriveInCallService.hasRingingCall()) {
            return;
        }

        if (!DrivePrefs.isDriveModeEnabled(this)) {
            return;
        }

        handler.postDelayed(
                this::startListening,
                700
        );
    }

    private boolean containsAny(
            String value,
            String... terms
    ) {

        for (String term : terms) {

            if (value.contains(term)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Broad check used for the UI status text: true if the phone has
     * ANY Bluetooth audio device connected that could plausibly carry
     * calls (classic headset/handsfree, A2DP, or LE Audio). Checking
     * HEADSET alone is unreliable - a lot of true-wireless earbuds
     * report themselves through A2DP, or through LE Audio on newer
     * phones, without a classic HFP connection showing up at all.
     */
    public static boolean isBluetoothAudioConnected(
            Context context
    ) {

        try {

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S) {

                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED) {

                    return false;
                }
            }

            BluetoothAdapter adapter =
                    BluetoothAdapter.getDefaultAdapter();

            if (adapter == null
                    || !adapter.isEnabled()) {

                return false;
            }

            int headsetState = adapter.getProfileConnectionState(
                    BluetoothProfile.HEADSET
            );

            int a2dpState = adapter.getProfileConnectionState(
                    BluetoothProfile.A2DP
            );

            if (headsetState == BluetoothProfile.STATE_CONNECTED
                    || a2dpState == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                int leAudioState = adapter.getProfileConnectionState(
                        BluetoothProfile.LE_AUDIO
                );

                if (leAudioState == BluetoothProfile.STATE_CONNECTED) {
                    return true;
                }
            }

            return false;

        } catch (SecurityException e) {

            return false;

        } catch (Exception e) {

            return false;
        }
    }

    /**
     * Stricter check for whether the connected Bluetooth device can
     * actually carry call audio (i.e. supports HFP/SCO), as opposed
     * to being media-only (A2DP-only earbuds, which do exist and
     * genuinely cannot be routed call audio by any app).
     */
    public static boolean isBluetoothCallCapable(Context context) {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

            if (adapter == null || !adapter.isEnabled()) {
                return false;
            }

            if (adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                    == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && adapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO)
                            == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    private Notification buildNotification(String text) {

        Intent open =
                new Intent(
                        this,
                        MainActivity.class
                );

        PendingIntent pi =
                PendingIntent.getActivity(
                        this,
                        1,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                |
                                (Build.VERSION.SDK_INT >= 23
                                        ? PendingIntent.FLAG_IMMUTABLE
                                        : 0)
                );

        return new NotificationCompat.Builder(
                this,
                CHANNEL
        )
                .setSmallIcon(
                        android.R.drawable.sym_action_call
                )
                .setContentTitle("Drive Caller")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL,
                            "Drive Mode",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    @Override
    public void onDestroy() {

        stopCallerAnnouncements();

        if (recognizer != null) {

            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }

            recognizer = null;
        }

        if (tts != null) {

            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }

            tts = null;
        }

        if (scoReceiverRegistered) {

            try {
                unregisterReceiver(scoReceiver);
            } catch (Exception ignored) {
            }

            scoReceiverRegistered = false;
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(
            Intent intent
    ) {
        return null;
    }
}
