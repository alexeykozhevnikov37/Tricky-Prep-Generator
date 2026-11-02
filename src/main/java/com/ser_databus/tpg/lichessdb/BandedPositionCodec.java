package com.ser_databus.tpg.lichessdb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binary format of a value in the "positions" column family of the banded index:
 * <pre>
 * version:        byte (1)
 * bucketMask:     varint, bit i = bucket i has games that reached the position
 * per set bucket: white varint, draws varint, black varint   (in bucket order)
 * moveCount:      varint
 * per move:       uciLength byte, uci ASCII bytes,
 *                 sanLength byte, san ASCII bytes,
 *                 bucketMask varint,
 *                 per set bucket: count varint
 * </pre>
 */
public final class BandedPositionCodec {
    /** Stored in the index meta data; an index with another value format must be rebuilt. */
    public static final String FORMAT = "banded-1";

    private static final byte VERSION = 1;

    private BandedPositionCodec() {
    }

    public static byte[] encode(BandedPositionAgg position) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + position.getMoves().size() * 16);
        out.write(VERSION);
        int mask = position.bucketMask();
        writeVarLong(out, mask);
        for (int b = 0; b < RatingBuckets.COUNT; b++) {
            if ((mask & (1 << b)) != 0) {
                writeVarLong(out, position.white(b));
                writeVarLong(out, position.draws(b));
                writeVarLong(out, position.black(b));
            }
        }
        writeVarLong(out, position.getMoves().size());
        for (BandedPositionAgg.MoveAgg m : position.getMoves()) {
            writeString(out, m.uci);
            writeString(out, m.san);
            int moveMask = m.bucketMask();
            writeVarLong(out, moveMask);
            for (int b = 0; b < RatingBuckets.COUNT; b++) {
                if ((moveMask & (1 << b)) != 0) {
                    writeVarLong(out, m.counts[b]);
                }
            }
        }
        return out.toByteArray();
    }

    public static BandedPositionAgg decode(byte[] bytes) {
        int[] pos = {0};
        byte version = bytes[pos[0]++];
        if (version != VERSION) {
            throw new IllegalStateException("Unknown banded position value version " + version);
        }
        BandedPositionAgg position = new BandedPositionAgg();
        int mask = (int) readVarLong(bytes, pos);
        for (int b = 0; b < RatingBuckets.COUNT; b++) {
            if ((mask & (1 << b)) != 0) {
                long white = readVarLong(bytes, pos);
                long draws = readVarLong(bytes, pos);
                long black = readVarLong(bytes, pos);
                position.addReached(b, white, draws, black);
            }
        }
        long moveCount = readVarLong(bytes, pos);
        for (long i = 0; i < moveCount; i++) {
            String uci = readString(bytes, pos);
            String san = readString(bytes, pos);
            BandedPositionAgg.MoveAgg move = position.getOrAddMove(uci, san);
            int moveMask = (int) readVarLong(bytes, pos);
            for (int b = 0; b < RatingBuckets.COUNT; b++) {
                if ((moveMask & (1 << b)) != 0) {
                    move.counts[b] += readVarLong(bytes, pos);
                }
            }
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
