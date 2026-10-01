package com.ser_databus.tpg.lichess;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class LichessMoveStats {
    public String uci;
    public String san;
    public int averageRating;
    public long white;
    public long draws;
    public long black;

    @JsonIgnore
    public long getGames() {
        return white + draws + black;
    }

    @Override
    public String toString() {
        return String.format("%s (%s): %d games — %dW/%dD/%dB avg %d",
                san, uci, getGames(), white, draws, black, averageRating);
    }
}