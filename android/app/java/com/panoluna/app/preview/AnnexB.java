package com.panoluna.app.preview;

/**
 * Помощники для потока Annex-B прямого эфира.
 *
 * Камера отдаёт видео без контейнера: какой кодек, где конец кадра и где начало следующего
 * — всё читается из байт. Эта часть чистая и проверяема без камеры.
 *
 * Портировано из verified-референса apatassini/Insta360luna: на прошивке 1.0.288 прямой эфир
 * идёт в **H.265**, поэтому определение кодека динамическое (H.264 или H.265).
 */
public final class AnnexB {

    public enum Codec {
        H264("video/avc"),
        H265("video/hevc");

        public final String mime;

        Codec(String mime) {
            this.mime = mime;
        }
    }

    private static final int H264_SPS = 7;
    private static final int H264_PPS = 8;
    private static final int H264_IDR = 5;

    private static final int H265_VPS = 32;
    private static final int H265_SPS = 33;
    private static final int H265_PPS = 34;
    private static final int H265_IRAP_MIN = 16;
    private static final int H265_IRAP_MAX = 23;

    private AnnexB() {
    }

    /** Позиции каждого start code (`00 00 01`) в буфере. */
    public static java.util.List<Integer> startCodes(byte[] bytes, int from, int until) {
        java.util.List<Integer> positions = new java.util.ArrayList<Integer>();
        int i = from;
        while (i + 2 < until) {
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1) {
                positions.add(i > from && bytes[i - 1] == 0 ? i - 1 : i);
                i += 3;
            } else {
                i++;
            }
        }
        return positions;
    }

    /** Длина start code, начинающегося в at: 3 или 4 байта. */
    public static int startCodeLength(byte[] bytes, int at) {
        return at + 3 < bytes.length && bytes[at] == 0 && bytes[at + 1] == 0
                && bytes[at + 2] == 0 && bytes[at + 3] == 1 ? 4 : 3;
    }

    /** Тип NAL H.264: 5 старших бит первого байта payload. */
    public static int h264NalType(byte firstByte) {
        return firstByte & 0x1F;
    }

    /** Тип NAL H.265: биты 1..6 первого байта payload. */
    public static int h265NalType(byte firstByte) {
        return (firstByte & 0xFF) >> 1 & 0x3F;
    }

    /** Определяет кодек по NAL. null, пока достаточного фрагмента не видно. */
    public static Codec detectCodec(byte[] bytes) {
        for (int start : startCodes(bytes, 0, bytes.length)) {
            int payload = start + startCodeLength(bytes, start);
            if (payload >= bytes.length) {
                continue;
            }
            byte first = bytes[payload];
            if (is(h265NalType(first), H265_VPS, H265_SPS, H265_PPS)) {
                return Codec.H265;
            }
            if (is(h264NalType(first), H264_SPS, H264_PPS)) {
                return Codec.H264;
            }
        }
        return null;
    }

    /** Есть ли в буфере NAL, открывающий keyframe: до его появления декодёр не стартует. */
    public static boolean containsKeyframe(byte[] bytes, Codec codec) {
        for (int start : startCodes(bytes, 0, bytes.length)) {
            int payload = start + startCodeLength(bytes, start);
            if (payload >= bytes.length) {
                continue;
            }
            byte first = bytes[payload];
            boolean isKey;
            if (codec == Codec.H264) {
                int t = h264NalType(first);
                isKey = t == H264_IDR || t == H264_SPS;
            } else {
                int t = h265NalType(first);
                isKey = (t >= H265_IRAP_MIN && t <= H265_IRAP_MAX)
                        || t == H265_VPS || t == H265_SPS;
            }
            if (isKey) {
                return true;
            }
        }
        return false;
    }

    private static boolean is(int value, int... targets) {
        for (int t : targets) {
            if (value == t) {
                return true;
            }
        }
        return false;
    }
}
