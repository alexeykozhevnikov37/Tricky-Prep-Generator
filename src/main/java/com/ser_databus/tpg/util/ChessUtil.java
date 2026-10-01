package com.ser_databus.tpg.util;

public class ChessUtil {
    public static String getCroppedFen(String fen) {
        int index = 0, n = 0;
        while (n < 4) {
            index = fen.indexOf(" ", index + 1);
            if (index == -1) {
                throw new IllegalStateException("Incorrect FEN: " + fen);
            }
            n++;
        }
        return fen.substring(0, index);
    }
}
