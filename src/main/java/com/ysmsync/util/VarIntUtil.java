package com.ysmsync.util;

import java.nio.ByteBuffer;

/**
 * Minecraft VarInt/VarLong 编解码工具。
 * 与 Minecraft 网络协议中的 VarInt 格式完全一致。
 */
public final class VarIntUtil {

    private VarIntUtil() {}

    public static int readVarInt(byte[] data, int[] offset) {
        int value = 0;
        int shift = 0;
        byte current;
        do {
            current = data[offset[0]++];
            value |= (current & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        } while ((current & 0x80) != 0);
        return value;
    }

    public static int readVarInt(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        byte current;
        do {
            current = buf.get();
            value |= (current & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        } while ((current & 0x80) != 0);
        return value;
    }

    public static long readVarLong(ByteBuffer buf) {
        long value = 0;
        int shift = 0;
        byte current;
        do {
            current = buf.get();
            value |= (long) (current & 0x7F) << shift;
            shift += 7;
            if (shift > 70) throw new RuntimeException("VarLong too big");
        } while ((current & 0x80) != 0);
        return value;
    }

    public static byte[] writeVarInt(int value) {
        ByteBuffer buf = ByteBuffer.allocate(5);
        writeVarInt(buf, value);
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    public static void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    public static void writeVarLong(ByteBuffer buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    public static int varIntSize(int value) {
        int size = 0;
        do {
            value >>>= 7;
            size++;
        } while (value != 0);
        return size;
    }

    public static String readString(ByteBuffer buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException(
                    "String length " + length + " exceeds remaining bytes " + buf.remaining());
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuffer buf, String str) {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.put(bytes);
    }

    public static int[] readVarIntArray(ByteBuffer buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException(
                    "VarInt array length " + length + " exceeds remaining bytes " + buf.remaining());
        }
        int[] result = new int[length];
        for (int i = 0; i < length; i++) {
            result[i] = readVarInt(buf);
        }
        return result;
    }

    public static void writeVarIntArray(ByteBuffer buf, int[] array) {
        writeVarInt(buf, array.length);
        for (int v : array) {
            writeVarInt(buf, v);
        }
    }
}
