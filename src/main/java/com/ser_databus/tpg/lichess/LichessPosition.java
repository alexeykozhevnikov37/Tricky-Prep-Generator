package com.ser_databus.tpg.lichess;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LichessPosition {
     private long white;
     private long draws;
     private long black;
     private List<LichessMoveStats> moves;

     @JsonIgnore
     public long getGames() {
          return white + draws + black;
     }
}
