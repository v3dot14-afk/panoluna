package com.panoluna.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.panoluna.app.BuildConfig;
import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;

/**
 * Impostazioni dell'app: fotocamera, credenziali Wi-Fi (con eliminazione), gimbal, dati.
 *
 * "Elimina" qui vuol dire due cose: dimenticare la rete e la password della camera, e cancellare
 * tutti i dati dell'app. Sono due pulsanti distinti, cosi' non si perdono le impostazioni per
 * sbaglio.
 */
public final class SettingsActivity extends Activity {

    private PanoLunaApp app;
    private EditText host;
    private EditText port;
    private EditText timeout;
    private EditText panMin;
    private EditText panMax;
    private EditText tiltMin;
    private EditText tiltMax;
    private EditText scale;
    private CheckBox invertPan;
    private CheckBox invertTilt;
    private TextView wifiState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        app = PanoLunaApp.get(this);

        host = findViewById(R.id.set_host);
        port = findViewById(R.id.set_port);
        timeout = findViewById(R.id.set_timeout);
        panMin = findViewById(R.id.set_pan_min);
        panMax = findViewById(R.id.set_pan_max);
        tiltMin = findViewById(R.id.set_tilt_min);
        tiltMax = findViewById(R.id.set_tilt_max);
        scale = findViewById(R.id.set_scale);
        invertPan = findViewById(R.id.set_invert_pan);
        invertTilt = findViewById(R.id.set_invert_tilt);
        wifiState = findViewById(R.id.set_wifi_state);

        host.setText(app.settings().getHost());
        port.setText(String.valueOf(app.settings().getPort()));
        timeout.setText(String.valueOf(app.settings().getRequestTimeoutMs()));
        panMin.setText(String.valueOf(app.settings().getPanMin()));
        panMax.setText(String.valueOf(app.settings().getPanMax()));
        tiltMin.setText(String.valueOf(app.settings().getTiltMin()));
        tiltMax.setText(String.valueOf(app.settings().getTiltMax()));
        scale.setText(String.valueOf(app.settings().getPanScale()));
        invertPan.setChecked(app.settings().isInvertPan());
        invertTilt.setChecked(app.settings().isInvertTilt());

        paintWifi();

        Button forget = findViewById(R.id.set_wifi_forget);
        forget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.settings().forgetWifi();
                paintWifi();
                Toast.makeText(SettingsActivity.this, R.string.settings_wifi_forget_done,
                        Toast.LENGTH_SHORT).show();
            }
        });

        Button clear = findViewById(R.id.set_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.settings_clear)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok,
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog,
                                                        int which) {
                                        app.settings().clearAll();
                                        Toast.makeText(SettingsActivity.this,
                                                R.string.settings_clear_done,
                                                Toast.LENGTH_SHORT).show();
                                        finish();
                                    }
                                })
                        .show();
            }
        });

        TextView version = findViewById(R.id.set_version);
        version.setText(getString(R.string.settings_version) + " "
                + BuildConfig.VERSION_NAME + "  ·  " + app.settings().getHost());
    }

    @Override
    protected void onStop() {
        super.onStop();
        save();
    }

    private void save() {
        app.settings().setHost(host.getText().toString());
        app.settings().setPort(parseInt(port.getText().toString(), 6666));
        app.settings().setRequestTimeoutMs(parseLong(timeout.getText().toString(), 6000L));
        app.settings().setPanLimits(parseFloat(panMin.getText().toString(), -57f),
                parseFloat(panMax.getText().toString(), 235f));
        app.settings().setTiltLimits(parseFloat(tiltMin.getText().toString(), -57f),
                parseFloat(tiltMax.getText().toString(), 120f));
        app.settings().setAngularScale(parseFloat(scale.getText().toString(), 1.235f),
                parseFloat(scale.getText().toString(), 1.235f));
        app.settings().raw().edit()
                .putBoolean("invert_pan", invertPan.isChecked())
                .putBoolean("invert_tilt", invertTilt.isChecked())
                .apply();
    }

    private void paintWifi() {
        if (app.settings().hasRememberedWifi()) {
            wifiState.setText(app.settings().getWifiSsid());
        } else {
            wifiState.setText(R.string.connect_state_idle);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
