package com.webhood.drivecaller;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

public final class ContactUtils {

    private ContactUtils() {}

    public static String lookupName(Context context, String number) {
        if (number == null || number.trim().isEmpty()) return null;

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                            .appendPath(number)
                            .build(),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                ContactsContract.PhoneLookup.DISPLAY_NAME
                        )
                );
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        return null;
    }
}
