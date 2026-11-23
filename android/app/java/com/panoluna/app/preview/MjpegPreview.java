package com.panoluna.app.preview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Anteprima MJPEG via l'endpoint OSC della camera.
 *
 * E' la strada piu' semplice quando la camera la offre: una POST a
 * {@code /osc/commands/execute} con {@code camera.getLivePreview} restituisce un multipart di
 * JPEG, che basta ritagliare e decodificare. Nessun decoder video, nessuna superficie da
 * gestire.
 *
 * Il parsing non si fida degli header Content-Length del multipart — alcuni firmware li
 * omettono — e cerca i marcatori JPEG FFD8 ... FFD9 nel flusso.
 */
public final class MjpegPreview {

    private static final String TAG = "PanoLuna";
    private static final String PATH = "/osc/commands/execute";
    private static final String REQUEST = "{\"name\":\"camera.getLivePreview\"}";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    public interface Listener {
        void onFrame(Bitmap frame);

        void onError(String message);

        void onClosed();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;
    private HttpURLConnection connection;

    public void start(final String host, final Network network, final Listener listener) {
        stop();
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                stream(host, network, listener);
            }
        }, "luna-preview");
        thread.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection c = connection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // niente da fare
            }
            connection = null;
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void stream(String host, Network network, Listener listener) {
        try {
            URL url = new URL("http://" + host.trim() + PATH);
            HttpURLConnection conn;
            if (network != null) {
                conn = (HttpURLConnection) network.openConnection(url);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            connection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            conn.setRequestProperty("X-XSRF-Protected", "1");
            conn.getOutputStream().write(REQUEST.getBytes("UTF-8"));
            conn.getOutputStream().flush();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                postError(listener, "L'anteprima ha risposto " + conn.getResponseCode());
                return;
            }
            InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
            while (running && !Thread.currentThread().isInterrupted()) {
                byte[] jpeg = readNextJpeg(in);
                if (jpeg == null) {
                    break;
                }
                final Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFrame(bitmap);
                        }
                    });
                }
            }
            postClosed(listener);
        } catch (Exception e) {
            if (running) {
                postError(listener, "Anteprima non disponibile: " + e.getMessage());
            }
        }
    }

    /** Estrae il prossimo JPEG completo, saltando gli header del multipart. */
    private byte[] readNextJpeg(InputStream input) throws Exception {
        int previous = input.read();
        if (previous < 0) {
            return null;
        }
        while (true) {
            int current = input.read();
            if (current < 0) {
                return null;
            }
            if (previous == 0xFF && current == 0xD8) {
                break;
            }
            previous = current;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        out.write(0xFF);
        out.write(0xD8);
        int last = 0;
        while (out.size() < MAX_FRAME_BYTES) {
            int current = input.read();
            if (current < 0) {
                return null;
            }
            out.write(current);
            if (last == 0xFF && current == 0xD9) {
                return out.toByteArray();
            }
            last = current;
        }
        return null;
    }

    private void postError(final Listener listener, final String message) {
        Log.w(TAG, message);
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(message);
            }
        });
    }

    private void postClosed(final Listener listener) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onClosed();
            }
        });
    }
}
