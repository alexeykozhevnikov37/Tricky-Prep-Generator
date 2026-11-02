package com.ser_databus.tpg.lichessdb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DumpDownloaderTest {
    private static final YearMonth M1 = YearMonth.of(2026, 3);
    private static final YearMonth M2 = YearMonth.of(2026, 2);
    private static final YearMonth M3 = YearMonth.of(2026, 1);

    @TempDir
    Path tmp;

    private HttpServer server;
    /** File name -> content served. */
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** "GET name range" of every request. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        for (YearMonth m : List.of(M1, M2, M3)) {
            files.put(DumpCatalog.fileName(m), content(m));
        }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsNewestFirstWithAtMostTwoFilesAhead() throws Exception {
        Path dir = tmp.resolve("dl");
        try (DumpDownloader downloader = new DumpDownloader(client, dir, List.of(M1, M2, M3), this::url)) {
            downloader.start();
            Path f1 = downloader.await(M1);
            Path f2 = downloader.await(M2);
            assertArrayEquals(content(M1), Files.readAllBytes(f1));
            assertArrayEquals(content(M2), Files.readAllBytes(f2));
            // Both slots are taken: the third month waits until one is released
            Thread.sleep(300);
            assertFalse(Files.exists(dir.resolve(DumpCatalog.fileName(M3))));
            assertFalse(Files.exists(dir.resolve(DumpCatalog.fileName(M3) + ".part")));

            Files.delete(f1);
            downloader.release(M1);
            Path f3 = downloader.await(M3);
            assertArrayEquals(content(M3), Files.readAllBytes(f3));
        }
        assertEquals("GET " + DumpCatalog.fileName(M1) + " -", requests.get(0));
        assertEquals("GET " + DumpCatalog.fileName(M2) + " -", requests.get(1));
    }

    @Test
    void continuesPartialDownload() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("dl"));
        byte[] full = content(M1);
        Files.write(dir.resolve(DumpCatalog.fileName(M1) + ".part"), Arrays.copyOf(full, 10));
        try (DumpDownloader downloader = new DumpDownloader(client, dir, List.of(M1), this::url)) {
            downloader.start();
            Path f1 = downloader.await(M1);
            assertArrayEquals(full, Files.readAllBytes(f1));
        }
        assertEquals(List.of("GET " + DumpCatalog.fileName(M1) + " bytes=10-"), requests);
        assertFalse(Files.exists(dir.resolve(DumpCatalog.fileName(M1) + ".part")));
    }

    @Test
    void completePartFileIsJustRenamed() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("dl"));
        byte[] full = content(M1);
        Files.write(dir.resolve(DumpCatalog.fileName(M1) + ".part"), full);
        try (DumpDownloader downloader = new DumpDownloader(client, dir, List.of(M1), this::url)) {
            downloader.start();
            assertArrayEquals(full, Files.readAllBytes(downloader.await(M1)));
        }
    }

    @Test
    void alreadyDownloadedFileIsNotDownloadedAgain() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("dl"));
        Path existing = Files.write(dir.resolve(DumpCatalog.fileName(M1)), content(M1));
        try (DumpDownloader downloader = new DumpDownloader(client, dir, List.of(M1), this::url)) {
            downloader.start();
            assertEquals(existing, downloader.await(M1));
        }
        assertTrue(requests.isEmpty());
    }

    private URI url(YearMonth month) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + DumpCatalog.fileName(month));
    }

    private static byte[] content(YearMonth month) {
        return ("dump of " + month + " ").repeat(1000).getBytes(StandardCharsets.US_ASCII);
    }

    /** Serves the files with HEAD and "Range: bytes=N-" support. */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String name = exchange.getRequestURI().getPath().substring(1);
            byte[] data = files.get(name);
            String method = exchange.getRequestMethod();
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (method.equals("GET")) {
                requests.add("GET " + name + " " + (range == null ? "-" : range));
            }
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(data.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            int from = 0;
            if (range != null) {
                from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                if (from >= data.length) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206, data.length - from);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data, from, data.length - from);
            }
        } finally {
            exchange.close();
        }
    }
}
