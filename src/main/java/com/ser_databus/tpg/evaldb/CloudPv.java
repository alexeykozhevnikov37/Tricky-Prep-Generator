package com.ser_databus.tpg.evaldb;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.ser_databus.tpg.util.Const.VICTORY_SCORE;
import static com.ser_databus.tpg.util.MoveUtil.lichessUciToMove;

/**
 * One principal variation of a Lichess cloud evaluation. Exactly one of {@code cp} / {@code mate} is set.
 * <p>
 * Scores are from <b>White's</b> point of view, as in the Lichess dump. Stockfish's own UCI output (and therefore
 * {@link com.ser_databus.tpg.evaluate.StockfishClient}) is from the side to move: use {@link #score(Side)} with the
 * side to move to get that convention.
 * <p>
 * Moves are UCI_Chess960 strings as in the dump: castling is written as "king takes own rook" ({@code e1h1}, not
 * {@code e1g1}). {@link #toMoves(Board)} converts the line to chesslib moves. The line may be shorter than in the dump:
 * see {@link CloudEvalStore.BuildSettings#lineMoves()}.
 *
 * @param cp    centipawns, White's point of view; null when the position is a forced mate
 * @param mate  moves to mate, positive = White mates; null when there is no forced mate
 * @param moves the line, UCI_Chess960
 */
public record CloudPv(Integer cp, Integer mate, List<String> moves) {
    public CloudPv {
        if ((cp == null) == (mate == null)) {
            throw new IllegalArgumentException("Exactly one of cp and mate must be set");
        }
        moves = List.copyOf(moves);
    }

    public boolean isMate() {
        return mate != null;
    }

    /** Score in pawns from White's point of view. Mates follow the {@code StockfishClient} convention (±327). */
    public double whiteScore() {
        if (cp != null) {
            return cp / 100.0;
        }
        return mate > 0 ? VICTORY_SCORE - mate / 100. : -VICTORY_SCORE - mate / 100.;
    }

    /** Score in pawns from the given side's point of view. Pass the side to move to get Stockfish's convention. */
    public double score(Side pointOfView) {
        return pointOfView == Side.WHITE ? whiteScore() : -whiteScore();
    }

    /** First move of the line (UCI_Chess960), or null for an empty line. */
    public String firstMove() {
        return moves.isEmpty() ? null : moves.get(0);
    }

    /**
     * Replays the line on a copy of {@code position} and returns it as chesslib moves; castling is converted to the
     * standard king move (e1g1).
     */
    public List<Move> toMoves(Board position) {
        Board board = position.clone();
        List<Move> result = new ArrayList<>(moves.size());
        for (String uci : moves) {
            Move move = lichessUciToMove(board, uci);
            result.add(move);
            board.doMove(move);
        }
        return result;
    }

    /** "+0.69", "-1.20", "#15" (White mates), "#-3" (Black mates). */
    public String scoreText() {
        return mate != null ? "#" + mate : String.format(Locale.ROOT, "%+.2f", cp / 100.0);
    }
}
