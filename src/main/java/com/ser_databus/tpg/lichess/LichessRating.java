package com.ser_databus.tpg.lichess;

public enum LichessRating {
    R400  ( 400),
    R1000 (1000),
    R1200 (1200),
    R1400 (1400),
    R1600 (1600),
    R1800 (1800),
    R2000 (2000),
    R2200 (2200),
    R2500 (2500);

    final int rating;

    LichessRating(int rating) {
        this.rating = rating;
    }
}
