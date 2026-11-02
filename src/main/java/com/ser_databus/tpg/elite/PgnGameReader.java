package com.ser_databus.tpg.elite;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams games from a PGN file (or any PGN stream) one by one without loading the whole file into memory.
 * Only the tags needed for indexing and the move text are kept.
 */
public class PgnGameReader implements Closeable {
    /** A game as it is in the PGN file: the Result tag and the raw move text. */
    public record RawGame(String result, String moveText) {
    }

    /**
     * A game with the tags the Lichess dump index needs. Tag values are as in the file (null if the tag is missing).
     */
    public record TaggedGame(String result, String moveText, String whiteElo, String blackElo, String timeControl,
                             String whiteTitle, String blackTitle, String variant, String fen) {
    }

    private static final int BUFFER_SIZE = 1 << 20;

    private final BufferedReader reader;
    /** First line of the next game's tag section that was read while looking for the end of the current game. */
    private String pendingLine;
    private boolean eof;

    public PgnGameReader(Path file) throws IOException {
        this(Files.newInputStream(file));
    }

    /** Reads PGN from a stream (e.g. a decompressing one); the stream is closed by {@link #close()}. */
    public PgnGameReader(InputStream in) {
        // SAN and tags we need are ASCII; ISO-8859-1 never fails on unexpected bytes (e.g. in player names)
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1), BUFFER_SIZE);
    }

    /** @return the next game, or null at the end of the file */
    public RawGame next() throws IOException {
        TaggedGame game = nextTagged();
        return game == null ? null : new RawGame(game.result(), game.moveText());
    }

    /** @return the next game with its tags, or null at the end of the file */
    public TaggedGame nextTagged() throws IOException {
        if (eof) {
            return null;
        }
        String result = null;
        String whiteElo = null;
        String blackElo = null;
        String timeControl = null;
        String whiteTitle = null;
        String blackTitle = null;
        String variant = null;
        String fen = null;
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
                    return new TaggedGame(result, moveText.toString(), whiteElo, blackElo, timeControl, whiteTitle,
                            blackTitle, variant, fen);
                }
                sawEvent |= isEvent;
                anyContent = true;
                String name = tagName(trimmed);
                switch (name) {
                    case "Result" -> result = extractTagValue(trimmed);
                    case "WhiteElo" -> whiteElo = extractTagValue(trimmed);
                    case "BlackElo" -> blackElo = extractTagValue(trimmed);
                    case "TimeControl" -> timeControl = extractTagValue(trimmed);
                    case "WhiteTitle" -> whiteTitle = extractTagValue(trimmed);
                    case "BlackTitle" -> blackTitle = extractTagValue(trimmed);
                    case "Variant" -> variant = extractTagValue(trimmed);
                    case "FEN" -> fen = extractTagValue(trimmed);
                    default -> {
                        // not needed
                    }
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
        return anyContent
                ? new TaggedGame(result, moveText.toString(), whiteElo, blackElo, timeControl, whiteTitle, blackTitle,
                variant, fen)
                : null;
    }

    /** Skips games without parsing their moves. @return the number of games actually skipped */
    public long skip(long games) throws IOException {
        long skipped = 0;
        while (skipped < games && nextTagged() != null) {
            skipped++;
        }
        return skipped;
    }

    /** "[WhiteElo "1500"]" -> "WhiteElo" */
    private static String tagName(String tagLine) {
        int end = 1;
        while (end < tagLine.length() && !Character.isWhitespace(tagLine.charAt(end)) && tagLine.charAt(end) != '"'
                && tagLine.charAt(end) != ']') {
            end++;
        }
        return tagLine.substring(1, end);
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
