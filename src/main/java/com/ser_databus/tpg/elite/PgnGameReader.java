package com.ser_databus.tpg.elite;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams games from a PGN file one by one without loading the whole file into memory.
 * Only the tags needed for indexing and the move text are kept.
 */
public class PgnGameReader implements Closeable {
    /** A game as it is in the PGN file: the Result tag and the raw move text. */
    public record RawGame(String result, String moveText) {
    }

    private static final int BUFFER_SIZE = 1 << 20;

    private final BufferedReader reader;
    /** First line of the next game's tag section that was read while looking for the end of the current game. */
    private String pendingLine;
    private boolean eof;

    public PgnGameReader(Path file) throws IOException {
        // SAN and tags we need are ASCII; ISO-8859-1 never fails on unexpected bytes (e.g. in player names)
        this.reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.ISO_8859_1), BUFFER_SIZE);
    }

    /** @return the next game, or null at the end of the file */
    public RawGame next() throws IOException {
        if (eof) {
            return null;
        }
        String result = null;
        StringBuilder moveText = new StringBuilder();
        boolean inMoveText = false;
        boolean anyContent = false;
        boolean sawEvent = false;

        String line = pendingLine != null ? pendingLine : reader.readLine();
        pendingLine = null;
        while (line != null) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) {
                boolean isEvent = trimmed.startsWith("[Event ");
                if (inMoveText || (isEvent && sawEvent)) {
                    // The tag section of the next game has started (the second case is a game without move text)
                    pendingLine = line;
                    return new RawGame(result, moveText.toString());
                }
                sawEvent |= isEvent;
                anyContent = true;
                if (trimmed.startsWith("[Result ")) {
                    result = extractTagValue(trimmed);
                }
            } else if (!trimmed.isEmpty()) {
                anyContent = true;
                inMoveText = true;
                if (moveText.length() > 0) {
                    moveText.append(' ');
                }
                moveText.append(trimmed);
            }
            line = reader.readLine();
        }
        eof = true;
        return anyContent ? new RawGame(result, moveText.toString()) : null;
    }

    /** Skips games without parsing their moves. @return the number of games actually skipped */
    public long skip(long games) throws IOException {
        long skipped = 0;
        while (skipped < games && next() != null) {
            skipped++;
        }
        return skipped;
    }

    private static String extractTagValue(String tagLine) {
        int start = tagLine.indexOf('"');
        int end = tagLine.lastIndexOf('"');
        return start >= 0 && end > start ? tagLine.substring(start + 1, end) : null;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
