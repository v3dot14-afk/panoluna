package com.panoluna.app.media;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scarica le foto dalla scheda della camera.
 *
 * I file della Luna Ultra si scaricano via HTTP dal loro percorso: l'indirizzo e'
 * {@code http://192.168.42.1<percorso>}, per esempio
 * {@code /storage_internal/DCIM/Camera01/IMG_20260718_142012_00_002.jpg}.
 *
 * La connessione va associata alla rete Wi-Fi della camera: quel Wi-Fi non ha Internet e,
 * lasciata a se, Android instraderebbe la richiesta sui dati mobili.
 */
public final class CameraDownloader {

    private static final String TAG = "PanoLuna";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_BYTES = 90 * 1024 * 1024;

    public interface Progress {
        void onProgress(int done, int total, String currentName);

        void onDone(int ok, int failed);

        void onError(String message);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile Progress progress;

    public void downloadAll(final Context context, final List<String> paths, final String host,
                            final Network network, final String jobFolder,
                            final Progress progress) {
        this.progress = progress;
        io.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int failed = 0;
                int total = paths.size();
                for (int i = 0; i < total; i++) {
                    String path = paths.get(i);
                    String name = fileName(path);
                    postProgress(i, total, name);
                    byte[] data = download(path, host, network);
                    if (data == null) {
                        failed++;
                        continue;
                    }
                    if (MediaSaver.saveJpeg(context, jobFolder, name, data) != null) {
                        ok++;
                    } else {
                        failed++;
                    }
                }
                postDone(ok, failed);
            }
        });
    }

    /** Scarica un percorso (o un URL completo) dalla camera. */
    public static byte[] download(String pathOrUrl, String host, Network network) {
        if (pathOrUrl == null || pathOrUrl.length() == 0) {
            return null;
        }
        String url = pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")
                ? pathOrUrl
                : "http://" + host + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl);
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            if (network != null) {
                connection = (HttpURLConnection) network.openConnection(target);
            } else {
                connection = (HttpURLConnection) target.openConnection();
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Download " + url + " -> HTTP " + code);
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) {
                    break;
                }
            }
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось скачать " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.length() == 0) {
            return "image.jpg";
        }
        return name;
    }

    private void postProgress(final int done, final int total, final String name) {
        main.post(new Runnable() {
            @Override
            public void run() {
                progress.onProgress(done, total, name);
            }
        });
    }

    private void postDone(final int ok, final int failed) {
        main.post(new Runnable() {
            @Override
            public void run() {
                progress.onDone(ok, failed);
            }
        });
    }
}
