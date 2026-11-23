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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Подключение к точке доступа камеры: камера не предлагает интернет, поэтому без привязки
 * Android идёт на мобилу и 192.168.42.1 недоступен. Здесь ищутся SSID Luna, запрашивается
 * подключение через WifiNetworkSpecifier (Android 10+), и сеть удерживается:
 * Wi-Fi без интернета оставляется, только пока приложением активно используется.
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

    /**true, если SSID относится к камере Luna.*/
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

    /** Нормальная очистка SSID из сканирования. */
    public static String cleanSsid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace("\"", "");
        if (value.length() == 0 || value.equalsIgnoreCase("<unknown ssid>")
                || value.equalsIgnoreCase("unknown ssid")) {
            return null;
        }
        return value;
    }

    /** Положение Wi-Fi выключено. */
    public boolean isWifiEnabled() {
        if (wifi == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return wifi.isWifiEnabled();
            }
            return wifi.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Разрешения на сканирование Wi-Fi есть. */
    public boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /**
     * Сканирование и Return ALL видимых сетей (без фильтра по Luna), сортированных по силе
     * сигнала. Используется для универсального поиска: даже если SSID камеры отличается,
     * пользователь может выбрать нужную вручную.
     */
    public void scanAll(final ScanCallback callback) {
        if (wifi == null) {
            callback.onFailed("Wi-Fi недоступен");
            return;
        }
        if (!isWifiEnabled()) {
            callback.onFailed("Wi-Fi выключен. Включите его в настройках телефона.");
            return;
        }
        if (!hasScanPermission()) {
            callback.onFailed("Не дано разрешение на сканирование Wi-Fi.");
            return;
        }
        try {
            List<ScanResult> cached = allResults();
            if (!cached.isEmpty()) {
                callback.onResults(cached);
            }
            if (!wifi.startScan()) {
                callback.onFailed("Не удалось запустить сканирование Wi-Fi.");
                return;
            }
            final long deadline = System.currentTimeMillis() + 8000L;
            pollScanAll(cached, deadline, callback);
        } catch (SecurityException e) {
            callback.onFailed("Не получено разрешение на сканирование Wi-Fi");
        } catch (Exception e) {
            callback.onFailed("Ошибка сканирования: " + e.getMessage());
        }
    }

    private void pollScanAll(final List<ScanResult> cached, final long deadline,
                             final ScanCallback callback) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<ScanResult> results = allResults();
                if (!results.isEmpty() || System.currentTimeMillis() >= deadline) {
                    callback.onResults(results);
                    return;
                }
                pollScanAll(cached, deadline, callback);
            }
        }, 700L);
    }

    /** Сканирование, оставлено только одно лучшее сеть Luna (для авто-подключения). */
    public void scan(final ScanCallback callback) {
        if (wifi == null) {
            callback.onFailed("Wi-Fi недоступен");
            return;
        }
        if (!isWifiEnabled()) {
            callback.onFailed("Wi-Fi выключен. Включите его в настройках телефона.");
            return;
        }
        if (!hasScanPermission()) {
            callback.onFailed("Не дано разрешение на сканирование Wi-Fi.");
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

    /** Все видимые сети, отсортированные по силе сигнала (сильные — первые). */
    private List<ScanResult> allResults() {
        List<ScanResult> out = new ArrayList<ScanResult>();
        if (wifi == null) {
            return out;
        }
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) {
                return out;
            }
            for (ScanResult result : results) {
                if (result == null || result.SSID == null) {
                    continue;
                }
                String ssid = cleanSsid(result.SSID);
                if (ssid == null) {
                    continue;
                }
                out.add(result);
            }
            Collections.sort(out, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "Risultati della scansione non leggibili: " + e.getMessage());
        }
        return out;
    }

    /** Лучшая из видимых сетей Luna (одна, сильнее всего). */
    private List<ScanResult> bestResults() {
        List<ScanResult> out = new ArrayList<ScanResult>();
        ScanResult best = null;
        for (ScanResult result : allResults()) {
            if (result != null && isLunaSsid(result.SSID)) {
                if (best == null || result.level > best.level) {
                    best = result;
                }
            }
        }
        if (best != null) {
            out.add(best);
        }
        return out;
    }

    /**
     * Подключается к точке доступа Luna. Если телефон уже на этой сети — переиспользует её
     * и привязывает.
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
        } else if (!security.equals("OPEN")) {
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

    /** Если есть активная Luna-сеть, подключается к ней без запроса. */
    public Network getActiveLunaNetwork() {
        return currentLunaNetwork();
    }

    /** SSID текущей активной сети. */
    public String currentSsid() {
        if (wifi == null) {
            return null;
        }
        try {
            WifiInfo info = wifi.getConnectionInfo();
            return info == null ? null : cleanSsid(info.getSSID());
        } catch (Exception e) {
            return null;
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

    /** Уже активная Luna-сеть не требует ввода. */
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

    /** Пример безопасности точки доступа по SSID (WAPA2/WPA3/OPEN). */
    private String guessSecurity(String ssid) {
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                String target = cleanSsid(ssid);
                for (ScanResult result : results) {
                    if (result == null) {
                        continue;
                    }
                    String s = cleanSsid(result.SSID);
                    if (target != null && target.equalsIgnoreCase(s)) {
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
            // без разрешений предполагаем WPA2
        }
        return "WPA2";
    }

    /**
     * Удерживает текущую Wi-Fi запросом без specifier: это нужно для сети, к которой
     * пользователь подключился из системных настроек.
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
