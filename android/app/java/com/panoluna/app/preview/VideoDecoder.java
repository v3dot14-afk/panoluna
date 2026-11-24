package com.panoluna.app.preview;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

/**
 * Декодирует поток прямого эфира камеры (annexB H.265/H.264) на Surface через MediaCodec.
 *
 * Это второй способ вывода preview, как в официальном приложении Insta360: когда OSC-MJPEG
 * недоступен, прямой эфир идёт по TCP-потоку (START_LIVE_STREAM), и видео декодируется
 * здесь. На прошивке 1.0.288 эфир — **H.265**, определение кодека динамическое.
 *
 * Два важных нюанса:
 * - декодёр не стартует до появления keyframe: кадры, зависящие от неполученного
 *   ключевого кадра, дают только артефакты;
 * - кодек не задан снаружи, он определяется из наборов параметров (SPS/VPS) в потоке.
 *
 * Портировано из verified-референса apatassini/Insta360luna.
 */
public final class VideoDecoder {

    private static final String TAG = "PanoLuna";

    private static final long INPUT_TIMEOUT_US = 10_000L;
    private static final long FRAME_INTERVAL_US = 33_333L;

    private MediaCodec codec;
    private AnnexB.Codec codecKind;
    private Surface codecSurface;
    private boolean sawKeyframe;
    private long presentationUs;

    private volatile long framesDecoded;
    private volatile long bytesReceived;

    public boolean isRunning() {
        return codec != null;
    }

    public long getFramesDecoded() {
        return framesDecoded;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    /**
     * Отдаёт декодёру кусок потока. Первый полезный блок запускает декодёр: до этого
     * он служит для определения кодека.
     */
    public synchronized void feed(Surface surface, byte[] chunk) {
        bytesReceived += chunk.length;

        if (codec != null && codecSurface != surface) {
            Log.i(TAG, "Preview: Surface изменилась, переконфигурируем декодёр");
            release();
        }

        if (codec == null) {
            AnnexB.Codec kind = codecKind != null ? codecKind : AnnexB.detectCodec(chunk);
            if (kind == null) {
                return;
            }
            codecKind = kind;
            if (!AnnexB.containsKeyframe(chunk, kind)) {
                return;
            }
            start(surface, kind);
        }

        MediaCodec decoder = codec;
        AnnexB.Codec kind = codecKind;
        if (decoder == null || kind == null) {
            return;
        }

        if (!sawKeyframe) {
            if (!AnnexB.containsKeyframe(chunk, kind)) {
                return;
            }
            sawKeyframe = true;
        }

        try {
            queue(decoder, chunk);
            drain(decoder);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Декодер видео упал, перезапуск: " + e.getMessage());
            release();
        }
    }

    private void start(Surface surface, AnnexB.Codec kind) {
        try {
            // Размеры ориентировочные: декодёр заменяет их по SPS.
            MediaFormat format = MediaFormat.createVideoFormat(kind.mime, 1280, 720);
            MediaCodec created = MediaCodec.createDecoderByType(kind.mime);
            created.configure(format, surface, null, 0);
            created.start();
            codec = created;
            codecSurface = surface;
            sawKeyframe = false;
            framesDecoded = 0;
            Log.i(TAG, "Preview: декодёр " + kind.mime + " запущен");
        } catch (Exception e) {
            Log.e(TAG, "Не удалось запустить декодёр " + kind.mime + ": " + e.getMessage());
            codec = null;
            codecSurface = null;
        }
    }

    private void queue(MediaCodec decoder, byte[] chunk) {
        int index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US);
        if (index < 0) {
            return;
        }
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        java.nio.ByteBuffer buffer = decoder.getInputBuffer(index);
        if (buffer == null) {
            return;
        }
        buffer.clear();
        if (buffer.remaining() < chunk.length) {
            decoder.queueInputBuffer(index, 0, 0, 0, 0);
            return;
        }
        buffer.put(chunk);
        long pts = presentationUs;
        presentationUs += FRAME_INTERVAL_US;
        decoder.queueInputBuffer(index, 0, chunk.length, pts, 0);
    }

    private void drain(MediaCodec decoder) {
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        while (true) {
            int index = decoder.dequeueOutputBuffer(info, 0);
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true);
                framesDecoded++;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                updateDisplayFormat(decoder.getOutputFormat());
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // ничему
            } else {
                return;
            }
        }
    }

    private void updateDisplayFormat(MediaFormat format) {
        try {
            int codedWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            int codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            int cropLeft = getInt(format, MediaFormat.KEY_CROP_LEFT, 0);
            int cropRight = getInt(format, MediaFormat.KEY_CROP_RIGHT, codedWidth - 1);
            int cropTop = getInt(format, MediaFormat.KEY_CROP_TOP, 0);
            int cropBottom = getInt(format, MediaFormat.KEY_CROP_BOTTOM, codedHeight - 1);
            int width = Math.max(1, cropRight - cropLeft + 1);
            int height = Math.max(1, cropBottom - cropTop + 1);
            Log.i(TAG, "Preview: видимый формат " + width + "×" + height);
            FormatListener l = formatListener;
            if (l != null) {
                l.onVideoSize(width, height);
            }
        } catch (Exception e) {
            // размеры опциональны
        }
    }

    /**
     * Сообщает реальный размер декодированного кадра.
     *
     * SurfaceView сам растягивает картинку на всю свою площадь, поэтому чтобы превью
     * не искажалось, размер SurfaceView подгоняется под пропорции кадра (в панораме
     * это ландшафт, а не портрет экрана).
     */
    public interface FormatListener {
        void onVideoSize(int width, int height);
    }

    private volatile FormatListener formatListener;

    public void setFormatListener(FormatListener listener) {
        this.formatListener = listener;
    }

    private static int getInt(MediaFormat format, String key, int def) {
        try {
            if (format.containsKey(key)) {
                return format.getInteger(key);
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    public synchronized void release() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }
        codec = null;
        codecSurface = null;
        sawKeyframe = false;
        presentationUs = 0;
    }
}
