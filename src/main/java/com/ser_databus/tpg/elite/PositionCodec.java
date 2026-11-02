package com.ser_databus.tpg.elite;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binary format of a value in the "positions" column family:
 * <pre>
 * version:     byte (1)
 * moveCount:   varint
 * per move:    uciLength byte, uci ASCII bytes,
 *              sanLength byte, san ASCII bytes,
 *              white varint, draws varint, black varint
 * </pre>
 */
public final class PositionCodec {
    private static final byte VERSION = 1;

    private PositionCodec() {
    }

    public static byte[] encode(PositionAgg position) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + position.getMoves().size() * 16);
        out.write(VERSION);
        writeVarLong(out, position.getMoves().size());
        for (PositionAgg.MoveAgg m : position.getMoves()) {
            writeString(out, m.uci);
            writeString(out, m.san);
            writeVarLong(out, m.white);
            writeVarLong(out, m.draws);
            writeVarLong(out, m.black);
        }
        return out.toByteArray();
    }

    public static PositionAgg decode(byte[] bytes) {
        int[] pos = {0};
        byte version = bytes[pos[0]++];
        if (version != VERSION) {
            throw new IllegalStateException("Unknown position value version " + version);
        }
        PositionAgg position = new PositionAgg();
        long moveCount = readVarLong(bytes, pos);
        for (long i = 0; i < moveCount; i++) {
            String uci = readString(bytes, pos);
            String san = readString(bytes, pos);
            long white = readVarLong(bytes, pos);
            long draws = readVarLong(bytes, pos);
            long black = readVarLong(bytes, pos);
            position.add(uci, san, white, draws, black);
        }
        return position;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        if (b.length > 255) {
            throw new IllegalArgumentException("Too long move: " + s);
        }
        out.write(b.length);
        out.write(b, 0, b.length);
    }

    private static String readString(byte[] bytes, int[] pos) {
        int len = bytes[pos[0]++] & 0xFF;
        String s = new String(bytes, pos[0], len, StandardCharsets.US_ASCII);
        pos[0] += len;
        return s;
    }

    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long readVarLong(byte[] bytes, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = bytes[pos[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }
}
