package com.panoluna.app;

import android.app.Application;
import android.content.Context;
import android.net.Network;
import android.util.Log;

import com.panoluna.app.camera.CameraClient;
import com.panoluna.app.camera.Gimbal;
import com.panoluna.app.camera.PanoShooter;
import com.panoluna.app.data.Settings;
import com.panoluna.app.media.CameraDownloader;
import com.panoluna.app.net.CameraConnection;
import com.panoluna.app.proto.Ucd2;

import java.util.ArrayList;
import java.util.List;

/**
 * Unica istanza dell'app: detiene le impostazioni, la connessione alla camera, il gimbal e la
 * macchina della panoramica. Le activity le prendono da qui, cosi' lo stato sopravvive al
 * cambio di schermata.
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
    private CameraDownloader downloader;

    private Network wifiNetwork;
    private final List<ConnectionListener> listeners = new ArrayList<ConnectionListener>();

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        connection = new CameraConnection();
        camera = new CameraClient(settings, connection);
        gimbal = new Gimbal(settings);
        shooter = new PanoShooter(settings, camera, gimbal);
        downloader = new CameraDownloader();

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

    public CameraDownloader downloader() {
        return downloader;
    }

    public Network getWifiNetwork() {
        return wifiNetwork;
    }

    public void setWifiNetwork(Network network) {
        wifiNetwork = network;
    }

    public void addConnectionListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeConnectionListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    /** Apre la sessione di controllo UCD2 verso la camera. */
    public boolean connectCamera() {
        try {
            connection.connect(settings.getHost(), settings.getPort(), wifiNetwork, 6000L);
            dispatchState(true, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connessione alla camera fallita: " + e.getMessage());
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
                    listener.onConnectionStateChanged(connected, reason);
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
                    listener.onNotification(frame);
                }
            }
        });
    }
}
