package com.webhood.drivecaller;

import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class IncomingCallActivity extends AppCompatActivity {

    private String number;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        number = getIntent().getStringExtra("number");

        TextView name = findViewById(R.id.callerName);
        TextView phone = findViewById(R.id.callerNumber);

        String callerName = ContactUtils.lookupName(this, number);
        name.setText(callerName == null ? "Unknown caller" : callerName);
        phone.setText(number == null ? "" : PhoneNumberUtils.formatNumber(number));

        Button answer = findViewById(R.id.answerButton);
        Button reject = findViewById(R.id.rejectButton);

        answer.setOnClickListener(v -> {
            DriveInCallService.answerCurrentCall();
            finish();
        });

        reject.setOnClickListener(v -> {
            DriveInCallService.rejectCurrentCall();
            finish();
        });
    }
}
