package fr.bebedlastreat.claudeReplay.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Minecraft style variable length integers.
 */
public final class VarInts {

    private VarInts() {
    }

    public static int size(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    public static void write(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int read(DataInput in) throws IOException {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new IOException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    /** Writes {@code value} at {@code offset} and returns the offset after it. */
    public static int write(byte[] target, int offset, int value) {
        while ((value & ~0x7F) != 0) {
            target[offset++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        target[offset++] = (byte) value;
        return offset;
    }

    /** Reads the VarInt at the start of {@code data}. Returns {@code {value, length}}. */
    public static long readLeading(byte[] data) {
        int value = 0;
        int i = 0;
        byte b;
        do {
            if (i >= data.length || i >= 5) {
                throw new IllegalArgumentException("Malformed VarInt");
            }
            b = data[i];
            value |= (b & 0x7F) << (7 * i);
            i++;
        } while ((b & 0x80) != 0);
        return ((long) i << 32) | (value & 0xFFFFFFFFL);
    }

    public static int leadingValue(byte[] data) {
        return (int) readLeading(data);
    }

    /** Returns a copy of {@code data} where the leading VarInt is replaced by {@code newValue}. */
    public static byte[] replaceLeading(byte[] data, int newValue) {
        int oldLength = (int) (readLeading(data) >>> 32);
        byte[] result = new byte[size(newValue) + data.length - oldLength];
        int offset = write(result, 0, newValue);
        System.arraycopy(data, oldLength, result, offset, data.length - oldLength);
        return result;
    }

    /** {@code [VarInt id][payload]} */
    public static byte[] prepend(int id, byte[] payload) {
        byte[] result = new byte[size(id) + payload.length];
        int offset = write(result, 0, id);
        System.arraycopy(payload, 0, result, offset, payload.length);
        return result;
    }
}
