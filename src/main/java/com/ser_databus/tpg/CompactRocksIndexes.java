package com.ser_databus.tpg;

import com.ser_databus.tpg.elite.EliteIndexStore;
import com.ser_databus.tpg.evaldb.CloudEvalStore;
import com.ser_databus.tpg.evaldb.LocalCloudEvalClient;
import com.ser_databus.tpg.lichess.LocalLichessClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-time conversion of the elite and cloud eval indexes to the table format of
 * {@link com.ser_databus.tpg.util.RocksDbMemory}: partitioned indexes and filters, so lookups load only the small
 * parts they need into the shared cache. The indexes work without it, but their old files load a whole file's index
 * and filter on every cache miss.
 * <p>
 * Close the explorer and don't run the index builders meanwhile: the indexes are opened for writing. Each index is
 * rewritten completely: it takes a while and needs free disk space about the size of the index.
 * <p>
 * Arguments: {@code elite}, {@code cloud}, or nothing for both.
 */
@Slf4j
public class CompactRocksIndexes {
    public static void main(String[] args) throws Exception {
        boolean elite = args.length == 0 || contains(args, "elite");
        boolean cloud = args.length == 0 || contains(args, "cloud");
        if (elite) {
            compact("elite index", Paths.get(LocalLichessClient.DEFAULT_INDEX_DIR), true);
        }
        if (cloud) {
            compact("cloud eval index", Paths.get(LocalCloudEvalClient.DEFAULT_INDEX_DIR), false);
        }
    }

    private static void compact(String name, Path dir, boolean isElite) throws Exception {
        if (!Files.isDirectory(dir)) {
            log.warn("The {} is not found at {}, skipped", name, dir.toAbsolutePath());
            return;
        }
        log.info("Compacting the {} at {}...", name, dir.toAbsolutePath());
        long start = System.nanoTime();
        if (isElite) {
            try (EliteIndexStore store = new EliteIndexStore(dir, false)) {
                store.compactAll();
            }
        } else {
            try (CloudEvalStore store = new CloudEvalStore(dir, false)) {
                store.compactAll();
            }
        }
        log.info("The {} is compacted in {} s", name, (System.nanoTime() - start) / 1_000_000_000L);
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
