package com.panoluna.app.proto;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Framing UCD2, il protocollo di controllo della Insta360 Luna Ultra su TCP/6666.
 *
 * <pre>
 *  0   4   'U' 'C' 'D' '2'      magic
 *  4   1   0x01                 versione
 *  5   1   0x0c                 flag
 *  6   1   tipo                 0x01 media, 0x04 comando/risposta, 0x05 stream
 *  7   1   seq                  contatore a 8 bit, non correlato alle risposti
 *  8   4   len (LE)             lunghezza del corpo
 * 12   len corpo
 *      4   trailer (LE)         checksum del frame (magic..corpo compreso)
 * </pre>
 *
 * Nei frame di comando il corpo si apre con 9 byte:
 * <pre>
 *  0   2   code (LE)            codice messaggio
 *  2   1   direzione            0x02 richiesta, 0x03 risposta
 *  3   2   requestId (LE)       e' questo che correla richiesta e risposta, non il seq
 *  5   4   0x00008000 (LE)      costante
 *  9   ..  messaggio protobuf
 * </pre>
 *
 * La sessione non si apre con un comando: si autorizza inviando un frame di tipo stream a
 * lunghezza zero con il token costante f6 cc 4f 09, ripetuto ogni 3 secondi come keep-alive.
 */
public final class Ucd2 {

    public static final byte[] MAGIC = new byte[]{0x55, 0x43, 0x44, 0x32}; // "UCD2"
    public static final int VERSION = 0x01;
    public static final int FLAGS = 0x0c;
    public static final int TYPE_MEDIA = 0x01;
    public static final int TYPE_FILE = 0x04;
    public static final int TYPE_STREAM = 0x05;
    public static final int DIRECTION_REQUEST = 0x02;
    public static final int DIRECTION_RESPONSE = 0x03;
    public static final int HEADER_SIZE = 8;
    public static final int LENGTH_SIZE = 4;
    public static final int TRAILER_SIZE = 4;
    public static final int COMMAND_HEADER_SIZE = 9;
    public static final int MEDIA_HEADER_SIZE = 9;
    public static final int MEDIA_VIDEO = 0x20;
    public static final int COMMAND_CONSTANT = 0x8000;
    public static final int MAX_DECLARED = 8 * 1024 * 1024;

    /** Token del frame di handshake: occupa la posizione del trailer a lunghezza zero. */
    public static final byte[] HELLO_TOKEN = new byte[]{(byte) 0xf6, (byte) 0xcc, 0x4f, 0x09};

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int round = 0; round < 8; round++) {
                value = ((value & 0x80000000) != 0)
                        ? ((value << 1) ^ 0x04C11DB7)
                        : (value << 1);
            }
            CRC_TABLE[i] = value;
        }
    }

    private Ucd2() {
    }

    /**
     * Checksum dei frame UCD2: variante non standard di CRC-32 (polinomio 0x04C11DB7) in cui
     * ogni byte viene messo in XOR con il byte BASSO e seguito da quattro round di tabella sul
     * byte alto. Non "correggerla" per farla combaciare con un CRC-32 standard: la camera
     * scarta i frame il cui trailer non corrisponde.
     */
    public static int checksum(byte[] data, int from, int until) {
        int checksum = -1;
        for (int i = from; i < until; i++) {
            checksum = checksum ^ (data[i] & 0xFF);
            for (int round = 0; round < 4; round++) {
                checksum = (checksum << 8) ^ CRC_TABLE[(checksum >>> 24) & 0xFF];
            }
        }
        return checksum;
    }

    /** Frame generico: header + lunghezza + corpo + checksum. */
    public static byte[] frame(int type, int sequence, byte[] body) {
        byte[] out = new byte[HEADER_SIZE + LENGTH_SIZE + body.length + TRAILER_SIZE];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = (byte) VERSION;
        out[5] = (byte) FLAGS;
        out[6] = (byte) type;
        out[7] = (byte) (sequence & 0xFF);
        putIntLe(out, 8, body.length);
        System.arraycopy(body, 0, out, HEADER_SIZE + LENGTH_SIZE, body.length);
        int checksum = checksum(out, 0, HEADER_SIZE + LENGTH_SIZE + body.length);
        putIntLe(out, HEADER_SIZE + LENGTH_SIZE + body.length, checksum);
        return out;
    }

    /** Frame di handshake/keep-alive: lunghezza zero e token costante al posto del checksum. */
    public static byte[] hello(int sequence) {
        byte[] out = new byte[HEADER_SIZE + LENGTH_SIZE + TRAILER_SIZE];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = (byte) VERSION;
        out[5] = (byte) FLAGS;
        out[6] = (byte) TYPE_STREAM;
        out[7] = (byte) (sequence & 0xFF);
        putIntLe(out, 8, 0);
        System.arraycopy(HELLO_TOKEN, 0, out, HEADER_SIZE + LENGTH_SIZE, HELLO_TOKEN.length);
        return out;
    }

    /** Frame di comando: intestazione a 9 byte + messaggio protobuf. */
    public static byte[] command(int sequence, int code, int requestId, byte[] body) {
        byte[] raw = new byte[COMMAND_HEADER_SIZE + body.length];
        putShortLe(raw, 0, code);
        raw[2] = (byte) DIRECTION_REQUEST;
        putShortLe(raw, 3, requestId);
        putIntLe(raw, 5, COMMAND_CONSTANT);
        System.arraycopy(body, 0, raw, COMMAND_HEADER_SIZE, body.length);
        return frame(TYPE_FILE, sequence, raw);
    }

    public static void putIntLe(byte[] dest, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            dest[offset + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
    }

    public static void putShortLe(byte[] dest, int offset, int value) {
        for (int i = 0; i < 2; i++) {
            dest[offset + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
    }

    public static int intLe(byte[] src, int offset) {
        return (src[offset] & 0xFF)
                | ((src[offset + 1] & 0xFF) << 8)
                | ((src[offset + 2] & 0xFF) << 16)
                | ((src[offset + 3] & 0xFF) << 24);
    }

    public static int shortLe(byte[] src, int offset) {
        return (src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8);
    }

    /** Un frame UCD2 decodificato. */
    public static final class Frame {
        public final int type;
        public final int code;
        public final int requestId;
        public final int direction;
        public final int substream;
        public final byte[] payload;

        Frame(int type, int code, int requestId, int direction, int substream, byte[] payload) {
            this.type = type;
            this.code = code;
            this.requestId = requestId;
            this.direction = direction;
            this.substream = substream;
            this.payload = payload;
        }

        public boolean isCommandFrame() {
            return type == TYPE_FILE;
        }

        public boolean isNotification() {
            return isCommandFrame() && code >= ProtoCodes.NOTIFICATION_BEGIN;
        }
    }

    /** Estrae i frame completi da un flusso di byte, riagganciandosi al magic dopo byte spuri. */
    public static final class FrameAssembler {
        private byte[] buffer = new byte[16 * 1024];
        private int size = 0;

        public synchronized void append(byte[] data, int length) {
            ensureCapacity(size + length);
            System.arraycopy(data, 0, buffer, size, length);
            size += length;
        }

        public synchronized List<Frame> drain() {
            List<Frame> frames = new ArrayList<Frame>();
            int offset = 0;
            while (true) {
                int start = indexOfMagic(offset);
                if (start < 0) {
                    offset = Math.max(offset, size - (MAGIC.length - 1));
                    if (offset < 0) {
                        offset = 0;
                    }
                    break;
                }
                if (start > offset) {
                    offset = start;
                }
                if (size - offset < HEADER_SIZE + LENGTH_SIZE) {
                    break;
                }
                int type = buffer[offset + 6] & 0xFF;
                int declared = intLe(buffer, offset + 8);
                if (declared < 0 || declared > MAX_DECLARED) {
                    offset += MAGIC.length;
                    continue;
                }
                int total = HEADER_SIZE + LENGTH_SIZE + declared + TRAILER_SIZE;
                if (size - offset < total) {
                    break;
                }
                int bodyStart = offset + HEADER_SIZE + LENGTH_SIZE;
                frames.add(decodeBody(type, bodyStart, declared));
                offset += total;
            }
            if (offset > 0) {
                System.arraycopy(buffer, offset, buffer, 0, size - offset);
                size -= offset;
            }
            return frames;
        }

        public synchronized void reset() {
            size = 0;
        }

        private Frame decodeBody(int type, int bodyStart, int declared) {
            if (type == TYPE_FILE && declared >= COMMAND_HEADER_SIZE) {
                byte[] payload = new byte[declared - COMMAND_HEADER_SIZE];
                System.arraycopy(buffer, bodyStart + COMMAND_HEADER_SIZE, payload, 0, payload.length);
                return new Frame(type,
                        shortLe(buffer, bodyStart),
                        shortLe(buffer, bodyStart + 3),
                        buffer[bodyStart + 2] & 0xFF,
                        0,
                        payload);
            }
            if (type == TYPE_MEDIA && declared > MEDIA_HEADER_SIZE) {
                byte[] payload = new byte[declared - MEDIA_HEADER_SIZE];
                System.arraycopy(buffer, bodyStart + MEDIA_HEADER_SIZE, payload, 0, payload.length);
                return new Frame(type, 0, 0, 0, buffer[bodyStart] & 0xFF, payload);
            }
            byte[] payload = new byte[declared];
            System.arraycopy(buffer, bodyStart, payload, 0, declared);
            return new Frame(type, 0, 0, 0, 0, payload);
        }

        private int indexOfMagic(int from) {
            for (int i = from; i <= size - MAGIC.length; i++) {
                boolean match = true;
                for (int j = 0; j < MAGIC.length; j++) {
                    if (buffer[i + j] != MAGIC[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return i;
                }
            }
            return -1;
        }

        private void ensureCapacity(int needed) {
            if (needed <= buffer.length) {
                return;
            }
            int newSize = buffer.length;
            while (newSize < needed) {
                newSize *= 2;
            }
            byte[] grown = new byte[newSize];
            System.arraycopy(buffer, 0, grown, 0, size);
            buffer = grown;
        }
    }

    /** Buffer circolare minimo per leggere dal socket. */
    public static final class BytePipe {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);

        public void write(byte[] data, int length) {
            out.write(data, 0, length);
        }

        public byte[] take() {
            byte[] snapshot = out.toByteArray();
            out.reset();
            return snapshot;
        }
    }
}
