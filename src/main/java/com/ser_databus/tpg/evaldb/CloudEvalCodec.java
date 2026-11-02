package com.ser_databus.tpg.evaldb;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary format of a value in the "positions" column family of the cloud eval index:
 * <pre>
 * version:      byte (1)
 * evalCount:    varint
 * per eval:     depth varint, knodes varint, pvCount varint
 * per pv:       varint (zigzag(score) &lt;&lt; 1 | isMate), moveCount varint, moves: 2 bytes each, little endian
 * move:         from square (6 bits) | to square &lt;&lt; 6 | promotion &lt;&lt; 12  (0 none, 1 n, 2 b, 3 r, 4 q)
 * square:       file + 8 * rank (a1 = 0, h8 = 63)
 * </pre>
 * A UCI move takes 2 bytes instead of 5 characters, which roughly halves the index.
 */
public final class CloudEvalCodec {
    private static final byte VERSION = 1;
    private static final String PROMOTIONS = " nbrq";

    private CloudEvalCodec() {
    }

    public static byte[] encode(CloudEvalPosition position) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write(VERSION);
        writeVarLong(out, position.evals().size());
        for (CloudEval eval : position.evals()) {
            writeVarLong(out, eval.depth());
            writeVarLong(out, eval.knodes());
            writeVarLong(out, eval.pvs().size());
            for (CloudPv pv : eval.pvs()) {
                long score = pv.isMate() ? pv.mate() : pv.cp();
                writeVarLong(out, (zigzag(score) << 1) | (pv.isMate() ? 1 : 0));
                writeVarLong(out, pv.moves().size());
                for (String move : pv.moves()) {
                    int code = encodeMove(move);
                    out.write(code & 0xFF);
                    out.write(code >>> 8);
                }
            }
        }
        return out.toByteArray();
    }

    public static CloudEvalPosition decode(String key, byte[] bytes) {
        int[] pos = {0};
        byte version = bytes[pos[0]++];
        if (version != VERSION) {
            throw new IllegalStateException("Unknown cloud eval value version " + version);
        }
        int evalCount = (int) readVarLong(bytes, pos);
        List<CloudEval> evals = new ArrayList<>(evalCount);
        for (int e = 0; e < evalCount; e++) {
            int depth = (int) readVarLong(bytes, pos);
            long knodes = readVarLong(bytes, pos);
            int pvCount = (int) readVarLong(bytes, pos);
            List<CloudPv> pvs = new ArrayList<>(pvCount);
            for (int p = 0; p < pvCount; p++) {
                long scoreAndType = readVarLong(bytes, pos);
                int score = (int) unzigzag(scoreAndType >>> 1);
                boolean mate = (scoreAndType & 1) != 0;
                int moveCount = (int) readVarLong(bytes, pos);
                List<String> moves = new ArrayList<>(moveCount);
                for (int m = 0; m < moveCount; m++) {
                    int code = (bytes[pos[0]] & 0xFF) | (bytes[pos[0] + 1] & 0xFF) << 8;
                    pos[0] += 2;
                    moves.add(decodeMove(code));
                }
                pvs.add(mate ? new CloudPv(null, score, moves) : new CloudPv(score, null, moves));
            }
            evals.add(new CloudEval(depth, knodes, pvs));
        }
        return new CloudEvalPosition(key, evals);
    }

    static int encodeMove(String uci) {
        if (uci.length() != 4 && uci.length() != 5) {
            throw new IllegalArgumentException("Bad UCI move: " + uci);
        }
        int promotion = 0;
        if (uci.length() == 5) {
            promotion = PROMOTIONS.indexOf(Character.toLowerCase(uci.charAt(4)));
            if (promotion <= 0) {
                throw new IllegalArgumentException("Bad promotion in UCI move: " + uci);
            }
        }
        return square(uci, 0) | square(uci, 2) << 6 | promotion << 12;
    }

    static String decodeMove(int code) {
        StringBuilder sb = new StringBuilder(5);
        appendSquare(sb, code & 63);
        appendSquare(sb, (code >>> 6) & 63);
        int promotion = (code >>> 12) & 7;
        if (promotion != 0) {
            sb.append(PROMOTIONS.charAt(promotion));
        }
        return sb.toString();
    }

    private static int square(String uci, int index) {
        int file = uci.charAt(index) - 'a';
        int rank = uci.charAt(index + 1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) {
            throw new IllegalArgumentException("Bad square in UCI move: " + uci);
        }
        return file + 8 * rank;
    }

    private static void appendSquare(StringBuilder sb, int square) {
        sb.append((char) ('a' + square % 8)).append((char) ('1' + square / 8));
    }

    private static long zigzag(long n) {
        return (n << 1) ^ (n >> 63);
    }

    private static long unzigzag(long z) {
        return (z >>> 1) ^ -(z & 1);
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
