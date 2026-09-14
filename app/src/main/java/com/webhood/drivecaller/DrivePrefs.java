package com.webhood.drivecaller;

import android.content.Context;

public final class DrivePrefs {
    private static final String PREFS = "drive_caller";
    private static final String DRIVE_MODE = "drive_mode";

    private DrivePrefs() {}

    public static boolean isDriveModeEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(DRIVE_MODE, false);
    }

    public static void setDriveMode(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DRIVE_MODE, enabled)
                .apply();
    }
}
