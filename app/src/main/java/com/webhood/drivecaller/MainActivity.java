package com.webhood.drivecaller;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 101;
    private static final int REQ_DIALER_ROLE = 102;

    private Switch driveToggle;
    private TextView roleStatus;
    private TextView bluetoothStatus;
    private TextView serviceStatus;
    private TextView modeTitle;
    private TextView modeDescription;
    private Button defaultPhoneButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        driveToggle = findViewById(R.id.driveToggle);
        roleStatus = findViewById(R.id.roleStatus);
        bluetoothStatus = findViewById(R.id.bluetoothStatus);
        serviceStatus = findViewById(R.id.serviceStatus);
        modeTitle = findViewById(R.id.modeTitle);
        modeDescription = findViewById(R.id.modeDescription);
        defaultPhoneButton = findViewById(R.id.defaultPhoneButton);

        requestRuntimePermissions();

        driveToggle.setChecked(DrivePrefs.isDriveModeEnabled(this));
        driveToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                if (!isDefaultDialer()) {
                    driveToggle.setChecked(false);
                    requestDialerRole();
                    return;
                }
                DrivePrefs.setDriveMode(this, true);
                DriveService.start(this);
            } else {
                DrivePrefs.setDriveMode(this, false);
                DriveService.stop(this);
            }
            updateUi();
        });

        defaultPhoneButton.setOnClickListener(v -> requestDialerRole());
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    private void requestRuntimePermissions() {
    if (Build.VERSION.SDK_INT < 23) return;

    java.util.ArrayList<String> permissions = new java.util.ArrayList<>();

    if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
    ) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.READ_CONTACTS);
    }

    if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
    ) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.RECORD_AUDIO);
    }

    if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {

        permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
    }

    if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

        permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    }

    if (!permissions.isEmpty()) {
        ActivityCompat.requestPermissions(
                this,
                permissions.toArray(new String[0]),
                REQ_PERMISSIONS
        );
    }
}

    private boolean isDefaultDialer() {
        TelecomManager telecom =
                (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        return telecom != null && getPackageName().equals(telecom.getDefaultDialerPackage());
    }

    private void requestDialerRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                Intent intent =
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                startActivityForResult(intent, REQ_DIALER_ROLE);
                return;
            }
        }

        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DIALER_ROLE) {
            updateUi();
        }
    }

    private void updateUi() {
        boolean dialer = isDefaultDialer();
        boolean enabled = DrivePrefs.isDriveModeEnabled(this);

        roleStatus.setText(dialer ? "Phone app active" : "Phone role needed");
        roleStatus.setTextColor(getColor(dialer ? R.color.dc_green : R.color.dc_muted));

        bluetoothStatus.setText(
                "Bluetooth headset: " +
                (DriveService.isBluetoothAudioConnected(this) ? "Connected" : "Not connected")
        );

        serviceStatus.setText(
                enabled ? "Drive service is running" : "Drive service is stopped"
        );

        modeTitle.setText(enabled ? "Drive Mode is on" : "Ready to drive");
        modeDescription.setText(enabled
                ? "Caller announcements and voice call controls are active."
                : "Turn Drive Mode on when your Bluetooth headset is connected.");

        defaultPhoneButton.setText(dialer
                ? "Phone app role is active"
                : "Set as default Phone app");
        defaultPhoneButton.setEnabled(!dialer);
    }
}
