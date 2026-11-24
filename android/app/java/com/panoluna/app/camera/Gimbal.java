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

    /**
     * Самая быстрая команда гимбала — НЕ 100, а 90.
     *
     * Измерено на Luna Ultra (apatassini, GimbalCalibration.kt): команда 100 даёт ~11 °/с,
     * а 90 — 57 °/с по горизонтали и 41 °/с по вертикали. 100 firmware обрабатывает
     * по-своему, и просить «на максимум», отправляя 100, значит ехать ВЧЕТВЕРО медленнее.
     * Отсюда и 39 секунд на первый кадр вместо ~1 секунды.
     */
    public static final float FASTEST_SPEED = 0.90f;

    /**
     * Скорость, которую реально даёт FASTEST_SPEED: по ней считаем оценку позиции.
     * Раньше считали 30°/с при команде 100 — вдвое оптимистичнее правды (11°/с).
     */
    private static final float PAN_DEG_PER_SEC = 57f;
    private static final float TILT_DEG_PER_SEC = 41f;

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
    /**
     * Отправляет вектор скорости и интегрирует движение ТОЛЬКО если он реально ушёл.
     *
     * Раньше оценка интегрировала команду всегда. При оборванном соединении
     * fire() возвращает false, камера вектор не получает, а оценка всё равно
     * «докручивает» позицию — и driveTo отрапортует «достиг точки», хотя гимбал
     * не сдвинулся. Именно так панорама «проходила» точки при мёртвой сессии.
     *
     * @return true, если команда действительно отправлена
     */
    public boolean setVelocity(float pan, float tilt, CameraClient client) {
        float clampedPan = clamp(pan, -1f, 1f);
        float clampedTilt = clamp(tilt, -1f, 1f);
        boolean sent = client.gimbalVelocity(clampedPan, clampedTilt);
        synchronized (lock) {
            integrateLocked();
            if (!sent) {
                // Команда не ушла: двигаться нечему, оценку не трогаем.
                panVelocity = 0f;
                tiltVelocity = 0f;
                moving = false;
                lastIntegrationMs = System.currentTimeMillis();
                return false;
            }
            panVelocity = clampedPan;
            tiltVelocity = clampedTilt;
            moving = panVelocity != 0f || tiltVelocity != 0f;
            if (moving) {
                lastIntegrationMs = System.currentTimeMillis();
            }
        }
        return true;
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

    /**
     * Скорость при ЕДИНИЧНОМ нормированном векторе (1.0), то есть при команде 100.
     *
     * Замер на реальной Luna Ultra: команда 90 даёт 57 °/с pan и 41 °/с tilt. Единичный
     * вектор соответствует команде 100, поэтому пересчитываем: 57 / 0.9 = 63.3 °/с.
     * Тогда при команде 90 (нормированный вектор 0.9) интегрирование даст ровно 57 °/с —
     * оценка позиции перестаёт врать, и driveTo не «доезжает» раньше времени.
     * settings.getPanScale() — поправка под конкретный экземпляр.
     */
    public float panSpeedDegPerSec() {
        return (PAN_DEG_PER_SEC / FASTEST_SPEED) * settings.getPanScale();
    }

    public float tiltSpeedDegPerSec() {
        return (TILT_DEG_PER_SEC / FASTEST_SPEED) * settings.getTiltScale();
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
        int moveTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                integrateLocked();
            }
            float panDelta = normalize(targetPan - getPan());
            float tiltDelta = normalize(targetTilt - getTilt());
            // Допуск 3°: при опросе каждые 40 мс и 57 °/с гимбал проходит ~2.3° за тик,
            // поэтому 1.2° он гарантированно проскакивал и дёргался вокруг цели.
            if (Math.abs(panDelta) < 3f && Math.abs(tiltDelta) < 3f) {
                reached = true;
                break;
            }
            float panCommand = clamp(panDelta / 25f, -FASTEST_SPEED, FASTEST_SPEED);
            float tiltCommand = clamp(tiltDelta / 25f, -FASTEST_SPEED, FASTEST_SPEED);
            if (Math.abs(panCommand) < 0.08f && Math.abs(tiltCommand) < 0.08f) {
                // Sotto questa soglia il motore non parte: si forza un minimo.
                panCommand = panCommand == 0f ? 0f : (panCommand > 0f ? 0.08f : -0.08f);
                tiltCommand = tiltCommand == 0f ? 0f : (tiltCommand > 0f ? 0.08f : -0.08f);
            }
            if (moveTicks == 0) {
                logSend(panCommand, tiltCommand);
            }
            boolean sent = setVelocity(panCommand, tiltCommand, client);
            moveTicks++;
            if (!sent) {
                // Соединение мертво: команды не уходят. Нет смысла ждать 20 секунд,
                // молотя в мёртвый сокет — сдаёмся сразу, и панорама сообщит об ошибке.
                stop(client);
                callback.onDone(false);
                return;
            }
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

    /**
     * Сообщает в отладочный канал первый вектор движения: если камера не двигается,
     * по нему видно, дошли ли команды до гимбала и с какими значениями.
     */
    private void logSend(float panCommand, float tiltCommand) {
        if (listener == null) {
            return;
        }
        listener.onGimbalCommand(Math.round(panCommand * 100f), Math.round(tiltCommand * 100f),
                Math.round(panSpeedDegPerSec()), Math.round(tiltSpeedDegPerSec()));
    }

    /** Слушатель отладочных событий гимбала (для экранного лога). */
    public interface LogListener {
        void onGimbalCommand(int panPercent, int tiltPercent, int panDegPerSec, int tiltDegPerSec);
    }

    private LogListener listener;

    public void setLogListener(LogListener listener) {
        this.listener = listener;
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
