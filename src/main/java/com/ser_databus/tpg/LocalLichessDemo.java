package com.ser_databus.tpg;

import com.ser_databus.tpg.lichess.LichessMoveStats;
import com.ser_databus.tpg.lichess.LichessPosition;
import com.ser_databus.tpg.lichess.LocalLichessClient;

/** Prints the local Lichess Elite statistics of a hard-coded position. */
public class LocalLichessDemo {
    private static final String FEN = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    public static void main(String[] args) {
        try (LocalLichessClient client = new LocalLichessClient()) {
            long start = System.nanoTime();
            LichessPosition position = client.getPosition(FEN);
            long micros = (System.nanoTime() - start) / 1_000;

            System.out.println("FEN: " + FEN);
            System.out.printf("Lookup: %,d µs%n", micros);
            if (position.getGames() == 0) {
                System.out.println("The position isn't in the local index");
                return;
            }
            System.out.printf("Games: %,d   %s%n%n", position.getGames(),
                    score(position.getWhite(), position.getDraws(), position.getBlack()));
            System.out.printf("%-8s %-6s %10s %7s   %s%n", "Move", "UCI", "Games", "Share", "White / Draw / Black");
            for (LichessMoveStats move : position.getMoves()) {
                System.out.printf("%-8s %-6s %,10d %6.1f%%   %s%n", move.san, move.uci, move.getGames(),
                        100.0 * move.getGames() / position.getGames(),
                        score(move.white, move.draws, move.black));
            }
        }
    }

    private static String score(long white, long draws, long black) {
        double total = white + draws + black;
        return String.format("%5.1f%% / %5.1f%% / %5.1f%%", 100 * white / total, 100 * draws / total,
                100 * black / total);
    }
}
