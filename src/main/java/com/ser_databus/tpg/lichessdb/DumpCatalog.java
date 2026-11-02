package com.ser_databus.tpg.lichessdb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** The monthly standard rated dumps of database.lichess.org: names, URLs, and which months to index. */
public final class DumpCatalog {
    public static final String BASE_URL = "https://database.lichess.org/standard/";
    /** The first month of the standard dumps. */
    public static final YearMonth FIRST_MONTH = YearMonth.of(2013, 1);
    public static final String USER_AGENT = "PrepProfitEvaluator-LichessIndexBuilder";

    /** How far back from the current month the latest published dump is looked for. */
    private static final int MAX_MONTHS_BACK = 12;

    private DumpCatalog() {
    }

    /** "lichess_db_standard_rated_2026-09.pgn.zst" */
    public static String fileName(YearMonth month) {
        return "lichess_db_standard_rated_" + month + ".pgn.zst";
    }

    public static URI url(YearMonth month) {
        return URI.create(BASE_URL + fileName(month));
    }

    /**
     * Months from {@code from} down to {@code to}, <b>newest first</b>.
     *
     * @param to the oldest month, or null for {@link #FIRST_MONTH}
     */
    public static List<YearMonth> monthsNewestFirst(YearMonth from, YearMonth to) {
        YearMonth oldest = to == null ? FIRST_MONTH : to;
        if (oldest.isBefore(FIRST_MONTH)) {
            oldest = FIRST_MONTH;
        }
        if (from.isBefore(oldest)) {
            throw new IllegalArgumentException("The newest month " + from + " is before the oldest month " + oldest);
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = from; !m.isBefore(oldest); m = m.minusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    /**
     * Finds the latest published dump: checks the current month's file (UTC) and goes back one month at a time until
     * a file exists. Lichess publishes a month's dump in the first days of the next month.
     */
    public static YearMonth findLatestPublished(HttpClient client) throws IOException, InterruptedException {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= MAX_MONTHS_BACK; i++, month = month.minusMonths(1)) {
            int status = head(client, url(month)).statusCode();
            if (status == 200) {
                return month;
            }
            if (status != 404) {
                throw new IOException("Unexpected HTTP " + status + " for " + url(month));
            }
        }
        throw new IOException("No dump found at " + BASE_URL + " for the last " + MAX_MONTHS_BACK + " months");
    }

    /** HEAD request; the response has no body. */
    static HttpResponse<Void> head(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(1))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
