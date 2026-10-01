package com.ser_databus.tpg.lichess;

import lombok.Builder;

@Builder
public class LichessRatingSelector {
    public boolean isMaster;
    public LichessRating low;
    public LichessRating high;

    public static LichessRatingSelector master() {
        return LichessRatingSelector.builder()
                .isMaster(true)
                .build();
    }

    public static LichessRatingSelector regular(LichessRating low, LichessRating high) {
        return LichessRatingSelector.builder()
                .isMaster(false)
                .low(low)
                .high(high)
                .build();
    }

    @Override
    public String toString() {
        return isMaster ? "masters" : String.format("%d-%d", low.rating, high.rating);
    }
}
