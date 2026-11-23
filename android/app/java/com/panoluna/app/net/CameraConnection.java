package com.panoluna.app.net;

import android.net.Network;
import android.util.Log;

import com.panoluna.app.proto.Ucd2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sessione di controllo UCD2: handshake, keep-alive e correlazione richiesta/risposta.
 *
 * Due dettagli del protocollo non sono intuitivi:
 * - la sessione si autorizza inviando il frame di stream hello, non un comando;
 * - a correlare risposta e richiesta e' il requestId dell'intestazione di comando, non il seq.
 *
 * La camera accetta una sola connessione di controllo alla volta: se l'app Insta360 ufficiale
 * e' attiva, chiude questa connessione.
 */
public final class CameraConnection {

    private static final String TAG = "PanoLuna";

    /** Ascolta le notifiche della camera (batteria, stato cattura, esposizione). */
    public interface Listener {
        void onNotification(Ucd2.Frame frame);

        void onDisconnected(String reason);

        /** Un fotogramma video e' arrivato (sottostream principale 0x20), payload Annex-B. */
        void onVideoFrame(byte[] annexB);
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<Integer, CompletableFuture<Ucd2.Frame>> pending =
            new ConcurrentHashMap<Integer, CompletableFuture<Ucd2.Frame>>();
    private final AtomicInteger sequence = new AtomicInteger(0x24);
    private final AtomicInteger requestId = new AtomicInteger(1);
    private final Ucd2.FrameAssembler assembler = new Ucd2.FrameAssembler();

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread readerThread;
    private ScheduledFuture<?> keepAlive;
    private volatile Listener listener;
    private volatile boolean connected;
    private volatile boolean userClosed;

    public CameraConnection() {
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Apre il TCP verso la camera e autorizza la sessione con l'hello stream. */
    public void connect(String host, int port, Network bindNetwork, long connectTimeoutMs)
            throws IOException {
        disconnect();
        userClosed = false;
        Socket s = new Socket();
        if (bindNetwork != null) {
            try {
                bindNetwork.bindSocket(s);
            } catch (IOException e) {
                Log.w(TAG, "Не удалось привязать socket к сети Wi-Fi: " + e.getMessage());
            }
        }
        s.connect(new InetSocketAddress(host, port), (int) connectTimeoutMs);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        socket = s;
        input = s.getInputStream();
        output = s.getOutputStream();
        connected = true;
        assembler.reset();

        readerThread = new Thread(new Reader(), "luna-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        sendRaw(Ucd2.hello(nextSequence()));
        scheduleKeepAlive();
    }

    public void disconnect() {
        userClosed = true;
        connected = false;
        if (keepAlive != null) {
            keepAlive.cancel(false);
            keepAlive = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        closeQuietly(socket);
        socket = null;
        input = null;
        output = null;
        failPending("Сеанс закрыт");
    }

    /** Invia un comando e attende la risposta correlata dal requestId. */
    public CompletableFuture<Ucd2.Frame> request(final int code, final byte[] payload,
                                                 final long timeoutMs) {
        if (!connected) {
            CompletableFuture<Ucd2.Frame> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Non connesso"));
            return failed;
        }
        final int id = nextRequestId();
        final CompletableFuture<Ucd2.Frame> future = new CompletableFuture<Ucd2.Frame>();
        pending.put(id, future);
        try {
            sendRaw(Ucd2.command(nextSequence(), code, id, payload == null ? new byte[0] : payload));
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
            return future;
        }
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (pending.remove(id) != null) {
                    future.completeExceptionally(new IllegalStateException(
                            "Нет ответа на команду " + code + " за " + timeoutMs + " мс"));
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return future;
    }

    /** Invio senza attesa: per i comandi ripetuti ad alta frequenza, come il gimbal. */
    public boolean fire(int code, byte[] payload) {
        if (!connected) {
            return false;
        }
        try {
            sendRaw(Ucd2.command(nextSequence(), code, nextRequestId(),
                    payload == null ? new byte[0] : payload));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Не удалось отправить команду " + code + ": " + e.getMessage());
            return false;
        }
    }

    private synchronized void sendRaw(byte[] data) throws IOException {
        OutputStream stream = output;
        if (stream == null) {
            throw new IOException("Socket non aperto");
        }
        stream.write(data);
        stream.flush();
    }

    private void scheduleKeepAlive() {
        if (keepAlive != null) {
            keepAlive.cancel(false);
        }
        keepAlive = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!connected || userClosed) {
                    return;
                }
                try {
                    sendRaw(Ucd2.hello(nextSequence()));
                } catch (IOException e) {
                    Log.w(TAG, "Keep-alive non inviato: " + e.getMessage());
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private void failPending(String reason) {
        for (CompletableFuture<Ucd2.Frame> future : pending.values()) {
            future.completeExceptionally(new IllegalStateException(reason));
        }
        pending.clear();
    }

    private int nextSequence() {
        return sequence.getAndIncrement() & 0xFF;
    }

    private int nextRequestId() {
        int next = requestId.getAndIncrement() & 0xFFFF;
        if (next == 0) {
            next = requestId.getAndIncrement() & 0xFFFF;
        }
        return next;
    }

    private void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // niente da fare
        }
    }

    private final class Reader implements Runnable {
        @Override
        public void run() {
            byte[] buffer = new byte[16 * 1024];
            try {
                while (!Thread.currentThread().isInterrupted() && connected) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    assembler.append(buffer, read);
                    List<Ucd2.Frame> frames = assembler.drain();
                    for (Ucd2.Frame frame : frames) {
                        dispatch(frame);
                    }
                }
            } catch (IOException e) {
                if (!userClosed) {
                    Log.w(TAG, "Socket закрыт: " + e.getMessage());
                }
            } catch (Throwable t) {
                // Ничего неожиданное в цикле чтения не должно ронять приложение.
                Log.w(TAG, "Ошибка в цикле чтения: " + t);
            } finally {
                connected = false;
                failPending("Connessione persa");
                if (!userClosed) {
                    Listener l = listener;
                    if (l != null) {
                        l.onDisconnected("Камера закрыла соединение");
                    }
                }
            }
        }

        private void dispatch(Ucd2.Frame frame) {
            try {
                if (frame.type == Ucd2.TYPE_MEDIA) {
                    if (frame.substream == Ucd2.MEDIA_VIDEO && frame.payload.length > 0) {
                        Listener l = listener;
                        if (l != null) {
                            l.onVideoFrame(frame.payload);
                        }
                    }
                    return;
                }
                if (!frame.isCommandFrame()) {
                    return;
                }
                CompletableFuture<Ucd2.Frame> waiter = pending.remove(frame.requestId);
                if (waiter != null) {
                    waiter.complete(frame);
                    return;
                }
                Listener l = listener;
                if (l != null) {
                    l.onNotification(frame);
                }
            } catch (Throwable t) {
                // NISSUNA eccezione nel gestore del frame deve far cadere l'app:
                // il thread di lettura continua a lavorare.
                Log.w(TAG, "Gestore del frame terminato con eccezione: " + t);
            }
        }
    }
}
