package com.panoluna.app.net;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * L'access point della camera non offre accesso a Internet: senza un binding esplicito Android
 * continua a instradare i socket sui dati mobili e la connessione a 192.168.42.1 fallisce.
 *
 * Qui si cerca il SSID della Luna, ci si collega con il WifiNetworkSpecifier (Android 10+) e
 * si tiene la rete registrata: una Wi-Fi senza Internet viene abbandonata appena l'app passa
 * in secondo piano, se nessuno la sta usando.
 */
public final class WifiConnector {

    private static final String TAG = "PanoLuna";
    private static final String SSID_PREFIX = "Luna Ultra";

    public interface ScanCallback {
        void onResults(List<ScanResult> results);

        void onFailed(String reason);
    }

    public interface ConnectCallback {
        void onConnected(Network network);

        void onFailed(String reason);
    }

    private final Context appContext;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Network network;
    private ConnectivityManager.NetworkCallback callback;

    public WifiConnector(Context context) {
        this.appContext = context.getApplicationContext();
        this.connectivity = (ConnectivityManager) appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
    }

    public static boolean isLunaSsid(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim().replace("\"", "");
        if (value.length() == 0 || value.equalsIgnoreCase("<unknown ssid>")
                || value.equalsIgnoreCase("unknown ssid")) {
            return false;
        }
        return value.toLowerCase().startsWith(SSID_PREFIX.toLowerCase());
    }

    /** Scansione dei risultati: tiene solo gli SSID che iniziano con "Luna Ultra". */
    public void scan(final ScanCallback callback) {
        if (wifi == null) {
            callback.onFailed("Wi-Fi недоступен");
            return;
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback.onFailed("Нет разрешения на определение местоположения");
            return;
        }
        try {
            List<ScanResult> cached = bestResults();
            if (!cached.isEmpty()) {
                callback.onResults(cached);
            }
            if (!wifi.startScan()) {
                if (cached.isEmpty()) {
                    callback.onResults(new ArrayList<ScanResult>());
                }
                return;
            }
            final long deadline = System.currentTimeMillis() + 8000L;
            pollScan(cached, deadline, callback);
        } catch (SecurityException e) {
            callback.onFailed("Не получено разрешение на сканирование Wi-Fi");
        }
    }

    private void pollScan(final List<ScanResult> cached, final long deadline,
                          final ScanCallback callback) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<ScanResult> results = bestResults();
                if (!results.isEmpty() || System.currentTimeMillis() >= deadline) {
                    callback.onResults(results);
                    return;
                }
                pollScan(cached, deadline, callback);
            }
        }, 700L);
    }

    private List<ScanResult> bestResults() {
        List<ScanResult> out = new ArrayList<ScanResult>();
        if (wifi == null) {
            return out;
        }
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) {
                return out;
            }
            ScanResult best = null;
            for (ScanResult result : results) {
                if (result == null || !isLunaSsid(result.SSID)) {
                    continue;
                }
                if (best == null || result.level > best.level) {
                    best = result;
                }
            }
            if (best != null) {
                out.add(best);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Risultati della scansione non leggibili: " + e.getMessage());
        }
        return out;
    }

    /**
     * Si collega all'access point della Luna. Se il telefono e' gia' collegato, riusa quella
     * rete e la rivendica.
     */
    public void connect(final String ssid, final String password, final long timeoutMs,
                        final ConnectCallback onDone) {
        Network current = currentLunaNetwork();
        if (current != null) {
            pin();
            network = current;
            onDone.onConnected(current);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDone.onFailed("manual");
            return;
        }
        release();

        String security = guessSecurity(ssid);
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder();
        specifier.setSsid(ssid);
        if ("WPA3".equals(security)) {
            specifier.setWpa3Passphrase(password);
        } else if (!"OPEN".equals(security)) {
            specifier.setWpa2Passphrase(password);
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier.build())
                .build();

        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network value) {
                network = value;
                pin();
                onDone.onConnected(value);
            }

            @Override
            public void onUnavailable() {
                release();
                onDone.onFailed("Точка доступа не отвечает. Проверьте пароль.");
            }
        };
        callback = networkCallback;
        try {
            connectivity.requestNetwork(request, networkCallback,
                    (int) Math.max(5000L, timeoutMs));
        } catch (Exception e) {
            onDone.onFailed("Не удалось запросить сеть: " + e.getMessage());
        }
    }

    /** Libera la richiesta di rete, ma non la rete gia' collegata. */
    public void release() {
        if (callback != null) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
                // gia' deregistrato
            }
            callback = null;
        }
    }

    public Network getNetwork() {
        return network;
    }

    /** Una rete Luna gia' attiva non deve passare da nessuna finestra di scelta. */
    private Network currentLunaNetwork() {
        try {
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                if (isLunaSsid(ssidOf(caps))) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось прочитать состояние сети Wi-Fi: " + e.getMessage());
        }
        return null;
    }

    private String ssidOf(NetworkCapabilities caps) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return wifi == null ? null : wifi.getConnectionInfo().getSSID();
        }
        android.net.TransportInfo info = caps.getTransportInfo();
        if (info instanceof WifiInfo) {
            return ((WifiInfo) info).getSSID();
        }
        return null;
    }

    private String guessSecurity(String ssid) {
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    if (result != null && ssid != null && ssid.equals(result.SSID)) {
                        String caps = result.capabilities == null ? "" : result.capabilities.toUpperCase();
                        if (caps.contains("SAE")) {
                            return "WPA3";
                        }
                        if (caps.contains("OWE")) {
                            return "OPEN";
                        }
                        if (caps.contains("PSK") || caps.contains("WPA")) {
                            return "WPA2";
                        }
                        return "OPEN";
                    }
                }
            }
        } catch (SecurityException ignored) {
            // senza permessi si Assume WPA2
        }
        return "WPA2";
    }

    /**
     * Tiene in vita la Wi-Fi corrente con una richiesta senza specifier: serve alla rete a cui
     * l'utente si e' collegato dalle impostazioni di sistema.
     */
    private void pin() {
        release();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network value) {
                network = value;
            }

            @Override
            public void onLost(Network lost) {
                if (network == lost) {
                    network = null;
                }
            }
        };
        try {
            connectivity.requestNetwork(request, callback);
        } catch (Exception e) {
            Log.w(TAG, "Rivendicazione della Wi-Fi non riuscita: " + e.getMessage());
        }
    }

    private boolean hasPermission(String permission) {
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
