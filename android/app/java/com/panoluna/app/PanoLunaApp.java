package com.panoluna.app;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import com.panoluna.app.camera.CameraClient;
import com.panoluna.app.camera.Gimbal;
import com.panoluna.app.camera.PanoShooter;
import com.panoluna.app.data.Settings;
import com.panoluna.app.net.CameraConnection;
import com.panoluna.app.proto.Ucd2;

import java.util.ArrayList;
import java.util.List;

/**
 * Unica istanza dell'app: impostazioni, connessione alla camera, gimbal e macchina della
 * panoramica. Le activity le prendono da qui, cosi' lo stato sopravvive al cambio di schermata.
 *
 * La rete della camera non ha Internet: il socket di controllo e l'anteprima HTTP devono essere
 * legati alla Wi-Fi corrente e la rete mantenuta registrata via NetworkRequest, altrimenti
 * Android la lascia e la connessione a 192.168.42.1 cade (vedi PROTOCOL.md e il riferimento
 * persoft/lunaultra). L'utente si collega alla Wi-Fi della camera manualmente; qui la si
 * cerca, la si lega al socket e la si pianta.
 */
public final class PanoLunaApp extends Application {

    private static final String TAG = "PanoLuna";

    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected, String reason);

        void onNotification(Ucd2.Frame frame);
    }

    private Settings settings;
    private CameraConnection connection;
    private CameraClient camera;
    private Gimbal gimbal;
    private PanoShooter shooter;

    private ConnectivityManager connectivity;
    private Network wifiNetwork;
    private ConnectivityManager.NetworkCallback pinCallback;
    private final List<ConnectionListener> listeners = new ArrayList<ConnectionListener>();

    @Override
    public void onCreate() {
        super.onCreate();
        // Инициализируем здесь, а не в инициализаторе поля: поле конструируется до
        // attachBaseContext, когда getSystemService() бросает NullPointerException
        // и приложение падает на запуске.
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        settings = new Settings(this);
        connection = new CameraConnection();
        camera = new CameraClient(settings, connection);
        gimbal = new Gimbal(settings);
        shooter = new PanoShooter(settings, camera, gimbal);

        connection.setListener(new CameraConnection.Listener() {
            @Override
            public void onNotification(Ucd2.Frame frame) {
                dispatchNotification(frame);
            }

            @Override
            public void onDisconnected(final String reason) {
                Log.w(TAG, "Connessione chiusa: " + reason);
                dispatchState(false, reason);
            }

            @Override
            public void onVideoFrame(byte[] annexB) {
                // L'anteprima usata dall'app e' MJPEG via HTTP.
            }
        });
        // Тему применяют сами активити через applyTheme(activity).
    }

    /** Применяет тёмную/светлую тему из настроек. Вызывается до setContentView в активити. */
    public void applyTheme(android.app.Activity activity) {
        try {
            int mode = settings.themeMode();
            int target;
            switch (mode) {
                case Settings.THEME_LIGHT:
                    target = android.content.res.Configuration.UI_MODE_NIGHT_NO;
                    break;
                case Settings.THEME_SYSTEM:
                    target = (activity.getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
                    break;
                default:
                    target = android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    break;
            }
            android.content.res.Configuration config = activity.getResources().getConfiguration();
            int current = config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (current != target) {
                android.app.UiModeManager um = (android.app.UiModeManager)
                        activity.getSystemService(Context.UI_MODE_SERVICE);
                um.setApplicationNightMode(target);
            }
            boolean dark = settings.themeMode() == Settings.THEME_DARK
                    || (settings.themeMode() == Settings.THEME_SYSTEM
                    && target == android.content.res.Configuration.UI_MODE_NIGHT_YES);
            if (activity.getTheme() != null) {
                activity.setTheme(dark ? com.panoluna.app.R.style.AppTheme
                        : com.panoluna.app.R.style.AppTheme_Light);
            }
        } catch (Exception e) {
            Log.w(TAG, "Применение темы не удалось: " + e.getMessage());
        }
    }

    public static PanoLunaApp get(Context context) {
        return (PanoLunaApp) context.getApplicationContext();
    }

    public Settings settings() {
        return settings;
    }

    public CameraConnection connection() {
        return connection;
    }

    public CameraClient camera() {
        return camera;
    }

    public Gimbal gimbal() {
        return gimbal;
    }

    public PanoShooter shooter() {
        return shooter;
    }

    public Network getWifiNetwork() {
        return wifiNetwork;
    }

    public void addConnectionListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeConnectionListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Cerca la Wi-Fi corrente e la pianta: senza una richiesta in piedi Android la considera
     * inutile (nessun Internet) e la lascia appena l'app passa in secondo piano.
     */
    private Network currentWifiNetwork() {
        if (connectivity == null) {
            return null;
        }
        try {
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Поиск Wi-Fi не удался: " + e.getMessage());
        }
        return null;
    }

    private void pinWifi(Network network) {
        releasePin();
        if (network == null || connectivity == null) {
            return;
        }
        pinCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network value) {
                wifiNetwork = value;
            }

            @Override
            public void onLost(Network lost) {
                if (wifiNetwork == lost) {
                    wifiNetwork = null;
                }
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivity.requestNetwork(request, pinCallback);
            wifiNetwork = network;
        } catch (Exception e) {
            Log.w(TAG, "Привязка Wi-Fi не удалась: " + e.getMessage());
        }
    }

    private void releasePin() {
        if (pinCallback != null && connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(pinCallback);
            } catch (Exception ignored) {
                // уже разрегистрирован
            }
            pinCallback = null;
        }
    }

    /**
     * Apre la sessione di controllo UCD2 verso la camera по IP. La Wi-Fi corrente viene
     * cercata, piantata e il socket ad essa legato: così la sessione resta viva e gli
     * comandi (gimbal, scatto) non precipitano per caduta della rete.
     */
    public boolean connectCamera() {
        try {
            Network network = currentWifiNetwork();
            pinWifi(network);
            connection.connect(settings.getHost(), settings.getPort(), network, 6000L);
            dispatchState(true, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось подключиться к камере: " + e.getMessage());
            dispatchState(false, e.getMessage());
            return false;
        }
    }

    public void disconnectCamera() {
        connection.disconnect();
        dispatchState(false, null);
    }

    public boolean isCameraConnected() {
        return connection.isConnected();
    }

    private void dispatchState(final boolean connected, final String reason) {
        for (final ConnectionListener listener : new ArrayList<ConnectionListener>(listeners)) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onConnectionStateChanged(connected, reason);
                    } catch (Exception e) {
                        Log.w(TAG, "Ошибка слушателя состояния: " + e.getMessage());
                    }
                }
            });
        }
    }

    private void dispatchNotification(final Ucd2.Frame frame) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                for (ConnectionListener listener : new ArrayList<ConnectionListener>(listeners)) {
                    try {
                        listener.onNotification(frame);
                    } catch (Exception e) {
                        Log.w(TAG, "Ошибка слушателя уведомления: " + e.getMessage());
                    }
                }
            }
        });
    }
}
