package com.ser_databus.tpg.evaldb;

import java.nio.charset.StandardCharsets;

/**
 * How much of the dump is in the index. Written in the same atomic batch as the positions it accounts for.
 *
 * @param linesConsumed    lines from the start of the dump that are reflected in the index (including skipped ones),
 *                         so a resumed run skips exactly this many lines
 * @param recordsWritten   positions written (approximately the number of stored positions)
 * @param fileSize         size of the dump file when it was last read; it grows while the dump is downloading
 * @param fileName         name of the dump file when it was last read
 */
public record CloudEvalProgress(Status status, long linesConsumed, long recordsWritten, long fileSize,
                                String fileName) {
    public enum Status { IN_PROGRESS, DONE }

    public byte[] encode() {
        return (status + "|" + linesConsumed + "|" + recordsWritten + "|" + fileSize + "|" + fileName)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static CloudEvalProgress decode(byte[] bytes) {
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", 5);
        return new CloudEvalProgress(Status.valueOf(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                Long.parseLong(parts[3]), parts[4]);
    }
}
