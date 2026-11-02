package com.ser_databus.tpg.evaldb;

import java.util.List;

/**
 * One Stockfish search of a position from the Lichess evaluation dump.
 *
 * @param depth  search depth
 * @param knodes thousands of nodes searched
 * @param pvs    principal variations, best first (MultiPV = {@code pvs.size()})
 */
public record CloudEval(int depth, long knodes, List<CloudPv> pvs) {
    public CloudEval {
        pvs = List.copyOf(pvs);
    }

    public CloudPv best() {
        return pvs.get(0);
    }
}
