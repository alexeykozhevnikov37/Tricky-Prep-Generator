package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.graph.PrepTreeView.PrepBox;
import com.ser_databus.tpg.model.MyNode;
import com.ser_databus.tpg.model.Node;
import com.ser_databus.tpg.util.MoveUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a prep, as it's shown in {@link PrepTreeView} (with the user's move choices applied), as text or PGN.
 */
public final class PrepExporter {
    private static final String STANDARD_START_FEN = new Board().getFen();
    private static final int PGN_LINE_LENGTH = 80;

    private PrepExporter() {
    }

    // ---------- text ----------

    /**
     * The prep as an indented tree, one box per line: the opponent's move, my reply, and the chance, eval, EP and LP
     * of the box. Lines where the user chose another move than the generator are marked with "[your choice]".
     *
     * @param header lines printed before the tree
     */
    public static String toText(PrepBox root, List<String> header) {
        StringBuilder sb = new StringBuilder();
        for (String line : header) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        sb.append(boxText(root)).append('\n');
        appendTextChildren(sb, root, "");
        return sb.toString();
    }

    private static void appendTextChildren(StringBuilder sb, PrepBox box, String indent) {
        List<PrepBox> children = box.children();
        for (int i = 0; i < children.size(); i++) {
            boolean isLast = i == children.size() - 1;
            PrepBox child = children.get(i);
            sb.append(indent).append(isLast ? "└─ " : "├─ ").append(boxText(child)).append('\n');
            appendTextChildren(sb, child, indent + (isLast ? "   " : "│  "));
        }
    }

    private static String boxText(PrepBox box) {
        StringBuilder sb = new StringBuilder();
        if (box.opponentMoveText() != null) {
            sb.append(box.opponentMoveText()).append(' ');
        }
        sb.append(box.myMoveText() == null ? "(opponent to move)" : box.myMoveText());
        Node stats = box.statsNode();
        sb.append("   | Chance ").append(PrepTreeView.formatPercent(stats.chance))
                .append(" | Eval ").append(PrepTreeView.formatEval(stats.normEval))
                .append(" | EP ").append(PrepTreeView.formatPercent(stats.vp))
                .append(" | LP ").append(PrepTreeView.formatPercent(stats.lichessVp));
        if (!box.followsGenerator()) {
            sb.append("   [your choice]");
        }
        return sb.toString();
    }

    // ---------- PGN ----------

    /** A move of the PGN tree: the main continuation is the first of the next moves, the others are variations */
    private static final class Ply {
        final Board before;
        final Move move;
        /** Numeric annotation glyph, e.g. "$2" for "?", or null */
        final String nag;
        /** Comment after the move, or null */
        final String comment;
        final List<Ply> next = new ArrayList<>();

        Ply(Board before, Move move, String nag, String comment) {
            this.before = before;
            this.move = move;
            this.nag = nag;
            this.comment = comment;
        }
    }

    /**
     * The prep as one PGN game with variations, starting from the initial position of the explorer: first the moves
     * that led to the starting position of the prep, then the prep. The likeliest opponent's reply is the main line,
     * the other replies are variations. My moves have comments with the values of their boxes (chance, eval, ...),
     * the opponent's mistakes the usual annotations (?!, ?, ??).
     *
     * @param initialBoard the initial position of the explorer
     * @param setupMoves   the moves from it to the starting position of the prep
     * @param event        the Event tag, e.g. "Prep for White"
     * @param description  a comment at the start of the game (e.g. the iteration), or null
     */
    public static String toPgn(PrepBox root, Board initialBoard, List<Move> setupMoves, String event,
                               String description) {
        String initialFen = initialBoard.getFen();
        // the moves leading to the prep, then the prep
        List<Ply> start = new ArrayList<>();
        List<Ply> attachTo = start;
        Board board = initialBoard.clone();
        for (Move move : setupMoves) {
            Ply ply = new Ply(board.clone(), move, null, null);
            attachTo.add(ply);
            attachTo = ply.next;
            board.doMove(move);
        }
        attachTo.addAll(toPlies(root));

        StringBuilder sb = new StringBuilder();
        appendTag(sb, "Event", event);
        appendTag(sb, "Site", "?");
        appendTag(sb, "Date", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")));
        appendTag(sb, "Round", "-");
        appendTag(sb, "White", "?");
        appendTag(sb, "Black", "?");
        appendTag(sb, "Result", "*");
        appendTag(sb, "Annotator", "Tricky prep generator");
        if (!STANDARD_START_FEN.equals(initialFen)) {
            appendTag(sb, "SetUp", "1");
            appendTag(sb, "FEN", initialFen);
        }
        sb.append('\n');

        PgnWriter writer = new PgnWriter();
        if (description != null) {
            writer.token("{" + cleanComment(description) + "}");
        }
        if (!start.isEmpty()) {
            writer.writeLine(start, true);
        }
        writer.token("*");
        sb.append(writer.text()).append('\n');
        return sb.toString();
    }

    /**
     * The moves of a box and its subtree: the opponent's move leading to the box (none for the root), then my move,
     * followed by the boxes of the opponent's replies. The root with the opponent to move gives the replies only.
     */
    private static List<Ply> toPlies(PrepBox box) {
        List<Ply> first = new ArrayList<>();
        List<Ply> attachTo = first;
        if (box.opponentMove() != null && box.node() instanceof MyNode myNode && myNode.parent != null) {
            Ply opponentPly = new Ply(boardOf(myNode.parent.fen), box.opponentMove(), nag(box.annotation()), null);
            attachTo.add(opponentPly);
            attachTo = opponentPly.next;
        }
        if (box.myMove() != null) {
            String comment = String.join(", ", box.statLines()) + (box.followsGenerator() ? "" : ", your choice");
            Ply myPly = new Ply(boardOf(box.node().fen), box.myMove(), null, comment);
            attachTo.add(myPly);
            attachTo = myPly.next;
        }
        if (box.myMove() != null || box.opponentMove() == null) {
            for (PrepBox child : box.children()) {
                attachTo.addAll(toPlies(child));
            }
        }
        return first;
    }

    private static Board boardOf(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }

    private static String nag(String annotation) {
        return switch (annotation == null ? "" : annotation) {
            case "?!" -> "$6";
            case "?" -> "$2";
            case "??" -> "$4";
            default -> null;
        };
    }

    private static void appendTag(StringBuilder sb, String name, String value) {
        sb.append('[').append(name).append(" \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"]\n");
    }

    /** A PGN comment can't contain "}" */
    private static String cleanComment(String comment) {
        return comment.replace('}', ')').replace('{', '(');
    }

    /** Writes the move text, wrapped at {@link #PGN_LINE_LENGTH} characters */
    private static final class PgnWriter {
        private final StringBuilder text = new StringBuilder();
        private int lineLength;
        private String previous = "";

        /**
         * Writes the first of the alternatives with the others as variations after it, then the continuation of
         * the first one.
         *
         * @param needsNumber true if a Black move needs its number ("5..."): at the start, after a comment or a
         *                    variation
         */
        void writeLine(List<Ply> alternatives, boolean needsNumber) {
            Ply main = alternatives.get(0);
            boolean interrupted = writeMove(main, needsNumber);
            for (int i = 1; i < alternatives.size(); i++) {
                token("(");
                writeLine(List.of(alternatives.get(i)), true);
                token(")");
                interrupted = true;
            }
            if (!main.next.isEmpty()) {
                writeLine(main.next, interrupted);
            }
        }

        /** @return true if a comment follows the move, so the next Black move needs its number */
        private boolean writeMove(Ply ply, boolean needsNumber) {
            boolean isWhite = ply.before.getSideToMove() == Side.WHITE;
            int number = ply.before.getMoveCounter();
            String san;
            try {
                san = MoveUtil.moveToSan(ply.before, ply.move);
            } catch (RuntimeException e) {
                san = ply.move.toString();
            }
            if (isWhite) {
                token(number + ". " + san);
            } else if (needsNumber) {
                token(number + "... " + san);
            } else {
                token(san);
            }
            if (ply.nag != null) {
                token(ply.nag);
            }
            if (ply.comment != null) {
                token("{" + cleanComment(ply.comment) + "}");
                return true;
            }
            return false;
        }

        /** Adds a token, separated by a space, except after "(" and before ")" */
        void token(String token) {
            boolean space = !text.isEmpty() && !previous.equals("(") && !token.equals(")");
            if (space && lineLength + 1 + token.length() > PGN_LINE_LENGTH) {
                text.append('\n');
                lineLength = 0;
                space = false;
            }
            if (space) {
                text.append(' ');
                lineLength++;
            }
            text.append(token);
            lineLength += token.length();
            previous = token;
        }

        String text() {
            return text.toString();
        }
    }
}
