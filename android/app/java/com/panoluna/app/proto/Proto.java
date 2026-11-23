package com.panoluna.app.proto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Codifica/decodifica protobuf sul filo, senza generare classi dai .proto.
 *
 * La camera Insta360 Luna Ultra parla protobuf (namespace {@code insta360.messages}) dentro i
 * frame UCD2. Serve poco: scrivere i campi dei comandi e leggere quelli delle risposte, per
 * numero di campo e tipo. Il decoder e' tollerante: si ferma al primo byte non valido invece
 * di lanciare, cosi' un payload sconosciuto resta ispezionabile.
 *
 * I numeri dei campi non sono inventati: provengono dall'estrazione pubblica dei .proto del
 * progetto RigacciOrg/insta360-wifi-api e dal lavoro di Persoft su Insta360luna.
 */
public final class Proto {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_FIXED64 = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_FIXED32 = 5;

    private Proto() {
    }

    /** Un campo decodificato, senza schema: numero, tipo e valore grezzo. */
    public static final class Field {
        public final int number;
        public final int wireType;
        public final long varint;
        public final int fixed32;
        public final long fixed64;
        public final byte[] length;

        Field(int number, int wireType, long varint, int fixed32, long fixed64, byte[] length) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.fixed32 = fixed32;
            this.fixed64 = fixed64;
            this.length = length;
        }

        public boolean isVarint() {
            return wireType == WIRE_VARINT;
        }

        public boolean isLength() {
            return wireType == WIRE_LENGTH;
        }

        public String asString() {
            return length == null ? "" : new String(length, Charset.forName("UTF-8"));
        }

        public int asInt() {
            return (int) varint;
        }

        /** Interpretazione zig-zag, per i campi dichiarati {@code sint32}. */
        public int asSInt() {
            return (int) ((varint >>> 1) ^ -(varint & 1L));
        }

        public float asFloat() {
            return Float.intBitsToFloat(fixed32);
        }

        public double asDouble() {
            return Double.longBitsToDouble(fixed64);
        }
    }

    /** Scrittore di messaggi protobuf. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public Writer int32(int field, int value) {
            tag(field, WIRE_VARINT);
            writeVarint(value & 0xFFFFFFFFL);
            return this;
        }

        public Writer int64(int field, long value) {
            tag(field, WIRE_VARINT);
            writeVarint(value);
            return this;
        }

        public Writer bool(int field, boolean value) {
            return int32(field, value ? 1 : 0);
        }

        /** {@code sint32}: zig-zag, indispensabile per gli angoli negativi di pan/tilt. */
        public Writer sint32(int field, int value) {
            long zigzag = ((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL;
            return int64(field, zigzag);
        }

        public Writer float32(int field, float value) {
            tag(field, WIRE_FIXED32);
            int bits = Float.floatToIntBits(value);
            for (int i = 0; i < 4; i++) {
                out.write((bits >>> (8 * i)) & 0xFF);
            }
            return this;
        }

        public Writer double64(int field, double value) {
            tag(field, WIRE_FIXED64);
            long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < 8; i++) {
                out.write((int) ((bits >>> (8 * i)) & 0xFF));
            }
            return this;
        }

        public Writer bytes(int field, byte[] value) {
            tag(field, WIRE_LENGTH);
            writeVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(Charset.forName("UTF-8")));
        }

        /** Messaggio annidato: scrive un blocco prodotto da un altro Writer. */
        public Writer message(int field, byte[] nested) {
            return bytes(field, nested);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        private void tag(int field, int wireType) {
            if (field <= 0) {
                throw new IllegalArgumentException("numero di campo protobuf non valido: " + field);
            }
            writeVarint(((long) field << 3) | wireType);
        }

        private void writeVarint(long value) {
            long v = value;
            while (true) {
                int b = (int) (v & 0x7F);
                v >>>= 7;
                if (v == 0L) {
                    out.write(b);
                    return;
                }
                out.write(b | 0x80);
            }
        }
    }

    /** Lettore di messaggi protobuf. */
    public static final class Reader {
        private final byte[] buffer;

        public Reader(byte[] buffer) {
            this.buffer = buffer == null ? new byte[0] : buffer;
        }

        public List<Field> fields() {
            List<Field> result = new ArrayList<Field>();
            int pos = 0;
            while (pos < buffer.length) {
                long[] tag = readVarint(pos);
                if (tag == null) {
                    break;
                }
                pos = (int) tag[1];
                int fieldNumber = (int) (tag[0] >>> 3);
                int wireType = (int) (tag[0] & 0x07L);
                if (fieldNumber <= 0) {
                    break;
                }
                if (wireType == WIRE_VARINT) {
                    long[] v = readVarint(pos);
                    if (v == null) {
                        break;
                    }
                    result.add(new Field(fieldNumber, wireType, v[0], 0, 0L, null));
                    pos = (int) v[1];
                } else if (wireType == WIRE_FIXED64) {
                    if (pos + 8 > buffer.length) {
                        break;
                    }
                    long v = 0L;
                    for (int i = 0; i < 8; i++) {
                        v |= (buffer[pos + i] & 0xFFL) << (8 * i);
                    }
                    result.add(new Field(fieldNumber, wireType, 0L, 0, v, null));
                    pos += 8;
                } else if (wireType == WIRE_LENGTH) {
                    long[] len = readVarint(pos);
                    if (len == null) {
                        break;
                    }
                    int start = (int) len[1];
                    int size = (int) len[0];
                    if (size < 0 || start + size > buffer.length) {
                        break;
                    }
                    byte[] copy = new byte[size];
                    System.arraycopy(buffer, start, copy, 0, size);
                    result.add(new Field(fieldNumber, wireType, 0L, 0, 0L, copy));
                    pos = start + size;
                } else if (wireType == WIRE_FIXED32) {
                    if (pos + 4 > buffer.length) {
                        break;
                    }
                    int v = 0;
                    for (int i = 0; i < 4; i++) {
                        v |= (buffer[pos + i] & 0xFF) << (8 * i);
                    }
                    result.add(new Field(fieldNumber, wireType, 0L, v, 0L, null));
                    pos += 4;
                } else {
                    break;
                }
            }
            return result;
        }

        /** Cerca un campo per numero, opzionalmente dentro un messaggio annidato ({@code path}). */
        public Field find(int... path) {
            if (path == null || path.length == 0) {
                return null;
            }
            List<Field> current = fields();
            Field found = null;
            for (int i = 0; i < path.length; i++) {
                found = null;
                for (Field f : current) {
                    if (f.number == path[i]) {
                        found = f;
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                if (i == path.length - 1) {
                    return found;
                }
                if (!found.isLength()) {
                    return null;
                }
                current = new Reader(found.length).fields();
            }
            return null;
        }

        public Integer intOrNull(int... path) {
            Field f = find(path);
            if (f == null) {
                return null;
            }
            if (f.wireType == WIRE_VARINT) {
                return f.asInt();
            }
            if (f.wireType == WIRE_FIXED32) {
                return f.fixed32;
            }
            if (f.wireType == WIRE_FIXED64) {
                return (int) f.fixed64;
            }
            return null;
        }

        public Long longOrNull(int... path) {
            Field f = find(path);
            if (f == null) {
                return null;
            }
            if (f.wireType == WIRE_VARINT) {
                return f.varint;
            }
            if (f.wireType == WIRE_FIXED32) {
                return (long) f.fixed32;
            }
            if (f.wireType == WIRE_FIXED64) {
                return f.fixed64;
            }
            return null;
        }

        public Float floatOrNull(int... path) {
            Field f = find(path);
            if (f == null) {
                return null;
            }
            if (f.wireType == WIRE_FIXED32) {
                return f.asFloat();
            }
            if (f.wireType == WIRE_FIXED64) {
                return (float) f.asDouble();
            }
            if (f.wireType == WIRE_VARINT) {
                return (float) f.asSInt();
            }
            return null;
        }

        public Double doubleOrNull(int... path) {
            Field f = find(path);
            if (f == null) {
                return null;
            }
            if (f.wireType == WIRE_FIXED64) {
                return f.asDouble();
            }
            if (f.wireType == WIRE_FIXED32) {
                return (double) f.asFloat();
            }
            if (f.wireType == WIRE_VARINT) {
                return (double) f.asSInt();
            }
            return null;
        }

        public String stringOrNull(int... path) {
            Field f = find(path);
            if (f == null || !f.isLength()) {
                return null;
            }
            return f.asString();
        }

        /** Primo campo con questo numero, a qualunque livello di annidamento (ricerca in ampiezza). */
        public Field findShallow(int number) {
            for (Field f : fields()) {
                if (f.number == number) {
                    return f;
                }
            }
            return null;
        }

        private long[] readVarint(int start) {
            long result = 0L;
            int shift = 0;
            int pos = start;
            while (pos < buffer.length && shift <= 63) {
                int b = buffer[pos] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                pos++;
                if ((b & 0x80) == 0) {
                    return new long[]{result, pos};
                }
                shift += 7;
            }
            return null;
        }
    }
}
