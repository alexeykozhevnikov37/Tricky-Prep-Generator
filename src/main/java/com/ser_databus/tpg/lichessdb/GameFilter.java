package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.elite.PgnGameReader;
import com.ser_databus.tpg.lichess.LichessRating;

/**
 * Decides from the PGN tags whether a game of a Lichess dump is indexed, following the Lichess opening explorer's
 * import rules (lila-openingexplorer, {@code import-pgn/src/bin/import-lichess.rs}):
 * <ul>
 *     <li>only blitz, rapid and classical games; the speed comes from {@code TimeControl} "base+increment" as
 *     {@code base + 40 * increment} seconds: below 30 ultraBullet, below 180 bullet, below 480 blitz, below 1500 rapid,
 *     below 21600 classical, otherwise (or "-") correspondence;</li>
 *     <li>games with a bot ({@code WhiteTitle} or {@code BlackTitle} "BOT") are skipped;</li>
 *     <li>both {@code WhiteElo} and {@code BlackElo} must be known;</li>
 *     <li>the result must be known (not "*");</li>
 *     <li>games of another variant or from a custom start position ({@code FEN} tag) are skipped.</li>
 * </ul>
 * The class is stateless and thread-safe.
 */
public final class GameFilter {
    public enum Speed { ULTRA_BULLET, BULLET, BLITZ, RAPID, CLASSICAL, CORRESPONDENCE }

    /** Why a game isn't indexed. */
    public enum SkipReason {
        SPEED("not blitz/rapid/classical"),
        BOT("bot game"),
        RATING("rating missing"),
        RESULT("result unknown"),
        NON_STANDARD("variant or custom start position");

        private final String description;

        SkipReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** Either an accepted game (bucket and outcome set) or a skipped one (skip set). */
    public record Verdict(LichessRating bucket, GameReplayer.Outcome outcome, SkipReason skip) {
        public boolean accepted() {
            return skip == null;
        }
    }

    private static final String STANDARD_START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private GameFilter() {
    }

    public static Verdict check(PgnGameReader.TaggedGame game) {
        Speed speed = speedOf(game.timeControl());
        if (speed != Speed.BLITZ && speed != Speed.RAPID && speed != Speed.CLASSICAL) {
            return skip(SkipReason.SPEED);
        }
        if ("BOT".equals(game.whiteTitle()) || "BOT".equals(game.blackTitle())) {
            return skip(SkipReason.BOT);
        }
        int whiteElo = parseElo(game.whiteElo());
        int blackElo = parseElo(game.blackElo());
        if (whiteElo < 0 || blackElo < 0) {
            return skip(SkipReason.RATING);
        }
        GameReplayer.Outcome outcome = GameReplayer.parseOutcome(game.result());
        if (outcome == null) {
            return skip(SkipReason.RESULT);
        }
        if (!isStandardVariant(game.variant()) || !isStandardStart(game.fen())) {
            return skip(SkipReason.NON_STANDARD);
        }
        return new Verdict(RatingBuckets.of(whiteElo, blackElo), outcome, null);
    }

    /** @return the speed of a {@code TimeControl} tag value, or null if it's missing or unreadable */
    public static Speed speedOf(String timeControl) {
        if (timeControl == null) {
            return null;
        }
        String tc = timeControl.trim();
        if (tc.equals("-")) {
            return Speed.CORRESPONDENCE;
        }
        int plus = tc.indexOf('+');
        if (plus <= 0 || plus == tc.length() - 1) {
            return null;
        }
        long base;
        long increment;
        try {
            base = Long.parseLong(tc.substring(0, plus));
            increment = Long.parseLong(tc.substring(plus + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (base < 0 || increment < 0) {
            return null;
        }
        long total = base + 40 * increment;
        if (total < 30) {
            return Speed.ULTRA_BULLET;
        } else if (total < 180) {
            return Speed.BULLET;
        } else if (total < 480) {
            return Speed.BLITZ;
        } else if (total < 1500) {
            return Speed.RAPID;
        } else if (total < 21_600) {
            return Speed.CLASSICAL;
        } else {
            return Speed.CORRESPONDENCE;
        }
    }

    /** @return the rating, or -1 if it's missing or "?" */
    static int parseElo(String elo) {
        if (elo == null) {
            return -1;
        }
        try {
            int value = Integer.parseInt(elo.trim());
            return value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isStandardVariant(String variant) {
        return variant == null || variant.isBlank() || variant.equalsIgnoreCase("Standard");
    }

    private static boolean isStandardStart(String fen) {
        // Some games carry the standard start position as a FEN tag (lichess-db issue #40): that's a normal game
        return fen == null || fen.isBlank() || fen.trim().equals(STANDARD_START_FEN);
    }

    private static Verdict skip(SkipReason reason) {
        return new Verdict(null, null, reason);
    }
}
