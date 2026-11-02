package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A rule of a system. Rules are applied in this order: exact positions, then reactions, then setups; where none
 * applies, I play like the crowd.
 */
public interface Rule {
    String describe();

    /**
     * In this exact position, play this move.
     *
     * @param lineText the line that leads to the position, for printing
     */
    record ExactRule(String positionKey, Move move, String moveSan, String lineText) implements Rule {
        @Override
        public String describe() {
            return lineText.equals("root") ? "Start with " + moveSan : "After " + lineText + " play " + moveSan;
        }
    }

    /** When the opponent has a piece of this type on this square, play the response (if it's legal). */
    record ReactionRule(PieceType triggerPiece, Square triggerSquare, Token response) implements Rule {
        @Override
        public String describe() {
            return "If his " + Token.pieceName(triggerPiece) + " is on " + triggerSquare.value().toLowerCase()
                    + " -> " + response;
        }
    }

    /**
     * Play the first token of the list that I haven't played yet in this game and that is legal now. A guarded setup
     * applies only while the opponent has a piece of the guard type on the guard square.
     *
     * @param guardPiece null for an unguarded setup
     */
    record SetupRule(PieceType guardPiece, Square guardSquare, List<Token> tokens) implements Rule {
        public SetupRule {
            tokens = List.copyOf(tokens);
            if (tokens.size() > 64) {
                throw new IllegalArgumentException("A setup can have at most 64 moves");
            }
        }

        public boolean isGuarded() {
            return guardPiece != null;
        }

        public SetupRule withToken(Token token) {
            List<Token> newTokens = new ArrayList<>(tokens);
            newTokens.add(token);
            return new SetupRule(guardPiece, guardSquare, newTokens);
        }

        @Override
        public String describe() {
            String moves = tokens.stream().map(Token::toString).collect(Collectors.joining(", "));
            return isGuarded()
                    ? "If his " + Token.pieceName(guardPiece) + " is on " + guardSquare.value().toLowerCase()
                    + ", setup: " + moves
                    : "Setup: " + moves;
        }
    }
}
