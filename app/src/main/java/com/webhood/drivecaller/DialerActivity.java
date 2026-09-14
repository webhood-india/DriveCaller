package com.webhood.drivecaller;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class DialerActivity extends AppCompatActivity {

    private TextView numberDisplay;
    private final StringBuilder number = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        numberDisplay = findViewById(R.id.numberDisplay);

        int[] ids = {
                R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
                R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9,
                R.id.keyStar, R.id.keyHash
        };
        String[] values = {"0","1","2","3","4","5","6","7","8","9","*","#"};

        for (int i = 0; i < ids.length; i++) {
            Button button = findViewById(ids[i]);
            final String value = values[i];
            button.setOnClickListener(v -> {
                number.append(value);
                refreshNumber();
            });
        }

        findViewById(R.id.backspaceButton).setOnClickListener(v -> {
            if (number.length() > 0) {
                number.deleteCharAt(number.length() - 1);
                refreshNumber();
            }
        });

        findViewById(R.id.callButton).setOnClickListener(v -> placeCall());

        Uri incoming = getIntent().getData();
        if (incoming != null && incoming.getSchemeSpecificPart() != null) {
            number.append(incoming.getSchemeSpecificPart());
            refreshNumber();
        }
    }

    private void refreshNumber() {
        numberDisplay.setText(number.toString());
    }

    private void placeCall() {
        if (number.length() == 0) return;

        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom != null) {
            try {
                telecom.placeCall(
                        Uri.fromParts("tel", number.toString(), null),
                        new android.os.Bundle()
                );
            } catch (SecurityException e) {
                Intent dial = new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + number));
                startActivity(dial);
            }
        }
    }
}
