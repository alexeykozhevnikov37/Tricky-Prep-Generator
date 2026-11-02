package com.ser_databus.tpg.lichessdb;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DumpCatalogTest {
    @Test
    void monthsAreNewestFirst() {
        assertEquals(List.of(YearMonth.of(2026, 2), YearMonth.of(2026, 1), YearMonth.of(2025, 12),
                        YearMonth.of(2025, 11)),
                DumpCatalog.monthsNewestFirst(YearMonth.of(2026, 2), YearMonth.of(2025, 11)));
        assertEquals(List.of(YearMonth.of(2026, 2)),
                DumpCatalog.monthsNewestFirst(YearMonth.of(2026, 2), YearMonth.of(2026, 2)));
    }

    @Test
    void withoutOldestMonthGoesDownToTheFirstDump() {
        assertEquals(List.of(YearMonth.of(2013, 2), YearMonth.of(2013, 1)),
                DumpCatalog.monthsNewestFirst(YearMonth.of(2013, 2), null));
    }

    @Test
    void newestBeforeOldestIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> DumpCatalog.monthsNewestFirst(YearMonth.of(2025, 1), YearMonth.of(2025, 2)));
    }

    @Test
    void fileNamesAndUrls() {
        assertEquals("lichess_db_standard_rated_2026-09.pgn.zst", DumpCatalog.fileName(YearMonth.of(2026, 9)));
        assertEquals("https://database.lichess.org/standard/lichess_db_standard_rated_2013-01.pgn.zst",
                DumpCatalog.url(YearMonth.of(2013, 1)).toString());
    }
}
