package com.panoluna.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.data.Settings;
import com.panoluna.app.net.WifiConnector;

import java.util.ArrayList;
import java.util.List;

/**
 * Schermo 1: ricerca della Wi-Fi della camera e connessione.
 *
 * La ricerca usa scanAll() (tutte le SSID visibili) per evitare la scelta automatica
 * sbagliata e mostrare tutte le reti; la connessione avvia la sessione UCD2 verso la camera.
 */
public final class ConnectActivity extends Activity implements PanoLunaApp.ConnectionListener {

    private PanoLunaApp app;
    private WifiConnector wifi;

    private TextView scanState;
    private LinearLayout networkList;
    private TextView networkLabel;
    private ImageView networkIcon;
    private EditText password;
    private TextView connectState;
    private Button btnConnect;
    private Button btnForget;

    private List<String> scanned = new ArrayList<String>();
    private String selectedSsid;
    private boolean connecting;
    private boolean cameraReachable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        setContentView(R.layout.activity_connect);
        wifi = new WifiConnector(this);

        scanState = findViewById(R.id.scanState);
        networkList = findViewById(R.id.networkList);
        networkLabel = findViewById(R.id.networkLabel);
        networkIcon = findViewById(R.id.networkIcon);
        password = findViewById(R.id.password);
        connectState = findViewById(R.id.connectState);
        btnConnect = findViewById(R.id.btnConnect);
        btnForget = findViewById(R.id.btnForget);

        findViewById(R.id.btnScan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        findViewById(R.id.bottomSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, SettingsActivity.class));
            }
        });

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });

        btnForget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgetWifi();
            }
        });

        app.addConnectionListener(this);
        applyRememberedNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.applyTheme(this);
        app.addConnectionListener(this);
        updateConnectState();
        // Если уже подключено к сети Luna — показать это сразу.
        checkActiveLuna();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.removeConnectionListener(this);
        wifi.release();
    }

    // ---- Поиск ----

    private void startScan() {
        scanState.setText(R.string.connect_search_scanning);
        networkList.removeAllViews();
        scanned.clear();

        List<String> needed = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        String[] perms = needed.toArray(new String[0]);
        if (anyDenied(perms)) {
            requestPermissions(perms, 1);
            return;
        }
        wifi.scanAll(new WifiConnector.ScanCallback() {
            @Override
            public void onResults(List<android.net.wifi.ScanResult> results) {
                onScanResults(results);
            }

            @Override
            public void onFailed(String reason) {
                scanState.setText(reason);
                showToast(reason);
            }
        });
    }

    private void onScanResults(final List<android.net.wifi.ScanResult> results) {
        scanned.clear();
        networkList.removeAllViews();
        scanState.setText(R.string.connect_search_waiting);

        for (final android.net.wifi.ScanResult result : results) {
            final String ssid = WifiConnector.cleanSsid(result.SSID);
            if (ssid == null) {
                continue;
            }
            if (scanned.contains(ssid)) {
                continue;
            }
            scanned.add(ssid);

            View row = LayoutInflater.from(ConnectActivity.this)
                    .inflate(R.layout.item_network, networkList, false);
            TextView name = row.findViewById(R.id.networkName);
            TextView signal = row.findViewById(R.id.networkSignal);
            ImageView icon = row.findViewById(R.id.networkIcon);
            name.setText(ssid);
            signal.setText(signalText(result.level));
            icon.setImageResource(
                    WifiConnector.isLunaSsid(ssid) ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
            if (WifiConnector.isLunaSsid(ssid)) {
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.accent)));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectNetwork(ssid);
                }
            });
            networkList.addView(row);
        }

        if (scanned.isEmpty()) {
            scanState.setText(R.string.connect_search_none);
        } else {
            scanState.setText(getString(R.string.connect_search_found, scanned.size()));
        }
        // Авто-выбор первой сети Luna, если найдена.
        String luna = firstLuna(results);
        if (luna != null) {
            selectNetwork(luna);
        }
    }

    private String firstLuna(List<android.net.wifi.ScanResult> results) {
        for (android.net.wifi.ScanResult result : results) {
            if (result != null && WifiConnector.isLunaSsid(result.SSID)) {
                return WifiConnector.cleanSsid(result.SSID);
            }
        }
        return null;
    }

    private void selectNetwork(String ssid) {
        selectedSsid = ssid;
        networkLabel.setText(ssid);
        boolean luna = WifiConnector.isLunaSsid(ssid);
        networkIcon.setImageResource(luna ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
        networkIcon.setImageTintList(luna
                ? android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.accent))
                : android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.text_dim)));
        if (luna && !password.getText().toString().trim().isEmpty()) {
            updateConnectState();
        }
    }

    private String signalText(int level) {
        if (level <= -100) {
            return "очень слабо";
        }
        if (level <= -85) {
            return " слабо";
        }
        if (level <= -70) {
            return "нормально";
        }
        if (level <= -55) {
            return "хорошо";
        }
        return "сильно";
    }

    // ---- Подключение ----

    private void connect() {
        if (connecting) {
            return;
        }
        if (selectedSsid == null) {
            showToast(getString(R.string.connect_no_network));
            return;
        }
        final String pass = password.getText().toString();
        connecting = true;
        connectState.setText(getString(R.string.connect_state_connecting, selectedSsid));

        wifi.connect(selectedSsid, pass, app.settings().getRequestTimeoutMs(),
                new WifiConnector.ConnectCallback() {
                    @Override
                    public void onConnected(android.net.Network network) {
                        connecting = false;
                        app.setWifiNetwork(network);
                        connectState.setText(getString(R.string.connect_state_opening));
                        // Сохраняем упомниваемые данные, затем открываем сессию камеры.
                        app.settings().rememberWifi(selectedSsid, pass);
                        showRemembered();
                        app.connectCamera();
                    }

                    @Override
                    public void onFailed(String reason) {
                        connecting = false;
                        if ("manual".equals(reason)) {
                            connectState.setText(R.string.connect_need_manual);
                        } else {
                            connectState.setText(getString(R.string.connect_state_failed)
                                    + ": " + reason);
                        }
                        showToast(reason);
                    }
                });
    }

    private void checkActiveLuna() {
        if (app.getWifiNetwork() != null || app.isCameraConnected()) {
            if (selectedSsid == null) {
                String cur = wifi.currentSsid();
                if (cur != null) {
                    selectNetwork(cur);
                }
            }
        }
    }

    private void updateConnectState() {
        if (app.isCameraConnected()) {
            cameraReachable = true;
            connectState.setText(getString(R.string.connect_state_connected, selectedSsid != null
                    ? selectedSsid : ""));
            btnConnect.setEnabled(false);
        } else {
            cameraReachable = false;
            btnConnect.setEnabled(selectedSsid != null);
        }
    }

    // ---- Запоминание сети ----

    private void applyRememberedNetwork() {
        Settings s = app.settings();
        if (s.hasRememberedWifi()) {
            selectedSsid = s.getWifiSsid();
            networkLabel.setText(selectedSsid);
            boolean luna = WifiConnector.isLunaSsid(selectedSsid);
            networkIcon.setImageResource(luna ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
            if (!s.getWifiPassword().isEmpty()) {
                password.setText(s.getWifiPassword());
            }
            showRemembered();
        }
    }

    private void showRemembered() {
        btnForget.setVisibility(View.VISIBLE);
        if (selectedSsid != null && !password.getText().toString().trim().isEmpty()) {
            btnConnect.setEnabled(true);
        }
    }

    private void forgetWifi() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_wifi_forget)
                .setMessage(R.string.connect_forget)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        app.settings().forgetWifi();
                        selectedSsid = null;
                        networkLabel.setText(R.string.connect_no_network);
                        networkIcon.setImageResource(R.drawable.ic_wifi_off);
                        password.setText("");
                        btnForget.setVisibility(View.GONE);
                        btnConnect.setEnabled(false);
                        showToast(R.string.connect_saved);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onConnectionStateChanged(final boolean connected, final String reason) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateConnectState();
                if (connected) {
                    finish();
                }
            }
        });
    }

    @Override
    public void onNotification(com.panoluna.app.proto.Ucd2.Frame frame) {
        // Управление камерой на следующих экранах.
    }

    private boolean anyDenied(String[] perms) {
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            boolean ok = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                startScan();
            } else {
                scanState.setText(R.string.connect_search_denied);
                showToast(R.string.connect_search_denied);
            }
        }
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void showToast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }
}
