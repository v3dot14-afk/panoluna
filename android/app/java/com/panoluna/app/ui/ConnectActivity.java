package com.panoluna.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.data.Settings;
import com.panoluna.app.net.WifiConnector;

import java.util.ArrayList;
import java.util.List;

/**
 * Prima schermata: si cerca la Wi-Fi della camera, si inserisce la password e si ricorda.
 *
 * La rete della Luna non ha Internet: serve il WifiNetworkSpecifier di Android 10+ per
 * associarla senza permessi di localizzazione particolari; prima di Android 10 l'app dice di
 * collegarla dalle impostazioni di sistema.
 */
public final class ConnectActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1001;

    private PanoLunaApp app;
    private Settings settings;
    private WifiConnector wifi;

    private TextView status;
    private TextView network;
    private TextView saved;
    private EditText password;
    private EditText host;
    private Button scanButton;
    private Button connectButton;

    private ScanResult found;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect);
        app = PanoLunaApp.get(this);
        settings = app.settings();
        wifi = new WifiConnector(this);

        status = findViewById(R.id.connect_status);
        network = findViewById(R.id.connect_network);
        saved = findViewById(R.id.connect_saved);
        password = findViewById(R.id.connect_password);
        host = findViewById(R.id.connect_host);
        scanButton = findViewById(R.id.connect_scan);
        connectButton = findViewById(R.id.connect_button);

        host.setText(settings.getHost());
        if (settings.hasRememberedWifi()) {
            password.setText(settings.getWifiPassword());
            saved.setVisibility(View.VISIBLE);
            saved.setText(getString(R.string.connect_saved) + ": " + settings.getWifiSsid());
            network.setText(settings.getWifiSsid());
        } else {
            saved.setVisibility(View.GONE);
            network.setText(R.string.connect_state_idle);
        }

        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        ImageButton settingsButton = findViewById(R.id.connect_settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, SettingsActivity.class));
            }
        });

        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        host.setText(settings.getHost());
    }

    private void ensurePermissions() {
        List<String> needed = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_PERMISSIONS) {
            startScan();
        }
    }

    private void startScan() {
        status.setText(R.string.connect_state_scanning);
        network.setText("");
        wifi.scan(new WifiConnector.ScanCallback() {
            @Override
            public void onResults(List<ScanResult> results) {
                if (results.isEmpty()) {
                    found = null;
                    network.setText(R.string.connect_no_network);
                    status.setText(R.string.connect_state_idle);
                    return;
                }
                found = results.get(0);
                network.setText(found.SSID + "  (" + found.level + " dBm)");
                status.setText(R.string.connect_state_idle);
            }

            @Override
            public void onFailed(String reason) {
                found = null;
                network.setText(reason);
                status.setText(R.string.connect_state_failed);
            }
        });
    }

    private void connect() {
        final String ssid;
        if (found != null) {
            ssid = found.SSID;
        } else if (settings.hasRememberedWifi()) {
            ssid = settings.getWifiSsid();
        } else {
            status.setText(R.string.connect_no_network);
            return;
        }
        final String pass = password.getText().toString();

        settings.setHost(host.getText().toString());

        status.setText(getString(R.string.connect_state_connecting, ssid));
        wifi.connect(ssid, pass, 20000L, new WifiConnector.ConnectCallback() {
            @Override
            public void onConnected(Network value) {
                // La password si ricorda solo se la rete ha risposto: altrimenti si
                // salvava quella sbagliata e il tentativo successivo partiva male.
                settings.rememberWifi(ssid, pass);
                app.setWifiNetwork(value);
                openSession(ssid);
            }

            @Override
            public void onFailed(String reason) {
                if ("manual".equals(reason)) {
                    status.setText(R.string.connect_need_manual);
                } else {
                    status.setText(reason);
                }
            }
        });
    }

    private void openSession(final String ssid) {
        status.setText(R.string.connect_state_opening);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = app.connectCamera();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            status.setText(getString(R.string.connect_state_connected, ssid));
                            startActivity(new Intent(ConnectActivity.this, MainActivity.class));
                            finish();
                        } else {
                            status.setText(getString(R.string.connect_state_failed)
                                    + ": " + app.camera().getLastError());
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        // Dalla prima schermata non si torna indietro: l'app vive nel mirino.
        finishAffinity();
    }
}
