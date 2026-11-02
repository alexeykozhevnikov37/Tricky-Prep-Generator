package com.ser_databus.tpg.elite;

import java.nio.charset.StandardCharsets;

/**
 * Indexing progress of one PGN file, stored in the "progress" column family under the file name.
 * It is written in the same atomic batch as the position statistics it accounts for, so the index never contains
 * games that aren't reflected here (and vice versa).
 *
 * @param gamesConsumed number of games from the start of the file that are already reflected in the index
 *                      (including skipped ones), so a resumed run skips exactly this many games
 */
public record FileProgress(Status status, long gamesConsumed, long fileSize, long lastModified) {
    public enum Status { IN_PROGRESS, DONE }

    /** Human-readable value, e.g. "DONE|369772|320738299|1591740309497". */
    public byte[] encode() {
        return (status + "|" + gamesConsumed + "|" + fileSize + "|" + lastModified).getBytes(StandardCharsets.UTF_8);
    }

    public static FileProgress decode(byte[] bytes) {
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|");
        return new FileProgress(Status.valueOf(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                Long.parseLong(parts[3]));
    }
}
