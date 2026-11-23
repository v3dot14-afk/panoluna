package com.panoluna.app.camera;

import com.panoluna.app.data.Settings;

/**
 * Il gimbal della Luna Ultra: movimento a velocita' e navigazione a stima.
 *
 * La camera NON restituisce la posizione del gimbal. Non c'e' notifica PTZ decodificabile,
 * non c'e' il pan nella coda del file, non c'e' negli EXIF. Quindi la posizione si stima:
 * {@code posizione += velocita * tempo}, con la velocita' nominale di catalogo corretta dal
 * fattore misurato (il gimbal si muove circa 1,235 volte quanto comandato).
 *
 * Il comando e' un vettore continuo: si comanda una velocita' e il motore la mantiene finche'
 * non arriva il vettore nullo. Per questo la lente della UI manda il vettore a 40 Hz e il
 * rilascio marca lo stop.
 */
public final class Gimbal {

    public interface Callback {
        void onDone(boolean reached);
    }

    private final Settings settings;
    private final Object lock = new Object();

    private float estimatedPan;
    private float estimatedTilt;
    private float panVelocity;
    private float tiltVelocity;
    private long lastIntegrationMs;
    private boolean moving;

    public Gimbal(Settings settings) {
        this.settings = settings;
    }

    /** Zero del gimbal: dove guarda la camera al momento del primo comando. */
    public void resetPosition(float pan, float tilt) {
        synchronized (lock) {
            estimatedPan = pan;
            estimatedTilt = tilt;
            lastIntegrationMs = System.currentTimeMillis();
        }
    }

    public float getPan() {
        synchronized (lock) {
            return estimatedPan;
        }
    }

    public float getTilt() {
        synchronized (lock) {
            return estimatedTilt;
        }
    }

    public boolean isMoving() {
        synchronized (lock) {
            return moving;
        }
    }

    /** Comanda una velocita' (-1..1) e aggiorna la stima. */
    public void setVelocity(float pan, float tilt, CameraClient client) {
        synchronized (lock) {
            integrateLocked();
            panVelocity = clamp(pan, -1f, 1f);
            tiltVelocity = clamp(tilt, -1f, 1f);
            moving = panVelocity != 0f || tiltVelocity != 0f;
            if (moving) {
                lastIntegrationMs = System.currentTimeMillis();
            }
        }
        client.gimbalVelocity(panVelocity, tiltVelocity);
    }

    public void stop(CameraClient client) {
        synchronized (lock) {
            integrateLocked();
            panVelocity = 0f;
            tiltVelocity = 0f;
            moving = false;
        }
        client.gimbalStop();
    }

    /** Integra la posizione stimata col tempo trascorso. Da chiamare di tanto in tanto. */
    public void tick() {
        synchronized (lock) {
            integrateLocked();
        }
    }

    private void integrateLocked() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastIntegrationMs;
        if (elapsed <= 0 || !moving) {
            lastIntegrationMs = now;
            return;
        }
        float seconds = elapsed / 1000f;
        estimatedPan += panVelocity * panSpeedDegPerSec() * seconds;
        estimatedTilt += tiltVelocity * tiltSpeedDegPerSec() * seconds;
        lastIntegrationMs = now;
    }

    /** Velocita' nominale al 100%, con il fattore misurato che corregge la corsa. */
    public float panSpeedDegPerSec() {
        return 30f * settings.getPanScale();
    }

    public float tiltSpeedDegPerSec() {
        return 20f * settings.getTiltScale();
    }

    /**
     * Porta il gimbal a un angolo, a stima. Bloccante: va chiamato da un thread di lavoro.
     *
     * Il controllo e' proporzionale sul resto: piu' si e' vicini, piu' piano si va, cosi' non
     * si supera il bersaglio. Se la camera rifiuta (fine corsa, temperatura) il timeout
     * consegna comunque, e lo scatto avviene: meglio una foto mossa che una panoramica ferma.
     */
    public void driveTo(float targetPan, float targetTilt, long timeoutMs, CameraClient client,
                        Callback callback) {
        long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
        boolean reached = false;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                integrateLocked();
            }
            float panDelta = normalize(targetPan - getPan());
            float tiltDelta = normalize(targetTilt - getTilt());
            if (Math.abs(panDelta) < 1.2f && Math.abs(tiltDelta) < 1.2f) {
                reached = true;
                break;
            }
            float panCommand = clamp(panDelta / 25f, -1f, 1f);
            float tiltCommand = clamp(tiltDelta / 25f, -1f, 1f);
            if (Math.abs(panCommand) < 0.08f && Math.abs(tiltCommand) < 0.08f) {
                // Sotto questa soglia il motore non parte: si forza un minimo.
                panCommand = panCommand == 0f ? 0f : (panCommand > 0f ? 0.08f : -0.08f);
                tiltCommand = tiltCommand == 0f ? 0f : (tiltCommand > 0f ? 0.08f : -0.08f);
            }
            setVelocity(panCommand, tiltCommand, client);
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop(client);
        callback.onDone(reached);
    }

    /** Angolo normalizzato in -180..+180. */
    public static float normalize(float degrees) {
        float value = degrees % 360f;
        if (value > 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
