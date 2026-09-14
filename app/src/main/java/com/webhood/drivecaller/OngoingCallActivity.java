package com.webhood.drivecaller;

import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class OngoingCallActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ongoing_call);

        String number = getIntent().getStringExtra("number");
        TextView name = findViewById(R.id.ongoingName);
        TextView phone = findViewById(R.id.ongoingNumber);

        String callerName = ContactUtils.lookupName(this, number);
        name.setText(callerName == null ? "Call" : callerName);
        phone.setText(number == null ? "" : PhoneNumberUtils.formatNumber(number));

        Button end = findViewById(R.id.endCallButton);
        end.setOnClickListener(v -> {
            DriveInCallService.endCurrentCall();
            finish();
        });
    }
}
