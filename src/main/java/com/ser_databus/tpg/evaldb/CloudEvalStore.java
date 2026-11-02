package com.ser_databus.tpg.evaldb;

import com.ser_databus.tpg.elite.PositionKey;
import com.ser_databus.tpg.util.RocksDbMemory;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RocksDB storage of the Lichess cloud evaluations.
 * <ul>
 *     <li>"positions": position key ({@link PositionKey}, UTF-8) -> {@link CloudEvalCodec} value</li>
 *     <li>default column family: metadata (key format, build settings, progress)</li>
 * </ul>
 */
public class CloudEvalStore implements AutoCloseable {
    private static final byte[] POSITIONS_CF = "positions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_FORMAT = "keyFormat".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BUILD_SETTINGS = "buildSettings".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROGRESS = "progress".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    /**
     * What the index keeps from the dump. Fixed when the index is created: an index can't mix settings.
     *
     * @param lineMoves maximum moves stored per line, 0 = the whole line
     * @param minPieces positions with fewer pieces (kings and pawns included) are not stored
     */
    public record BuildSettings(int lineMoves, int minPieces) {
        String encode() {
            return lineMoves + "|" + minPieces;
        }

        static BuildSettings decode(byte[] bytes) {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|");
            return new BuildSettings(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        public String describe() {
            return (minPieces > 0 ? "positions with at least " + minPieces + " pieces" : "all positions")
                    + ", " + (lineMoves > 0 ? "lines cut to " + lineMoves + " moves" : "full lines");
        }
    }

    private final ColumnFamilyOptions positionsOptions;
    private final ColumnFamilyOptions defaultOptions;
    private final DBOptions dbOptions;
    private final WriteOptions writeOptions;
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB db;
    private final ColumnFamilyHandle meta;
    private final ColumnFamilyHandle positions;

    /**
     * @param readOnly open an existing index for lookups only. A read-only store doesn't lock the index, so any number
     *                 of processes can read it, even while {@link CloudEvalIndexBuilder} is adding to it (a read-only
     *                 store sees the data as of the moment it was opened).
     */
    public CloudEvalStore(Path dir, boolean readOnly) throws IOException, RocksDBException {
        if (readOnly) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("Cloud eval index not found: " + dir.toAbsolutePath()
                        + ". Run CloudEvalIndexBuilder first.");
            }
        } else {
            Files.createDirectories(dir);
        }
        positionsOptions = new ColumnFamilyOptions()
                .optimizeLevelStyleCompaction()
                .setWriteBufferSize(128L << 20)
                .setTargetFileSizeBase(128L << 20)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setTableFormatConfig(RocksDbMemory.tableConfig());
        defaultOptions = new ColumnFamilyOptions();
        dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setIncreaseParallelism(Math.max(2, Runtime.getRuntime().availableProcessors()))
                .setMaxBackgroundJobs(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        writeOptions = new WriteOptions();

        List<ColumnFamilyDescriptor> descriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
                new ColumnFamilyDescriptor(POSITIONS_CF, positionsOptions));
        String path = dir.toAbsolutePath().toString();
        db = readOnly
                ? RocksDB.openReadOnly(dbOptions, path, descriptors, handles)
                : RocksDB.open(dbOptions, path, descriptors, handles);
        meta = handles.get(0);
        positions = handles.get(1);
    }

    /** Fails if the index was built with another position key format: its keys wouldn't match the lookups. */
    public void checkKeyFormat() throws RocksDBException {
        byte[] format = db.get(meta, KEY_FORMAT);
        if (format == null) {
            throw new IllegalStateException("The cloud eval index is empty. Run CloudEvalIndexBuilder first.");
        }
        if (!PositionKey.FORMAT.equals(new String(format, StandardCharsets.UTF_8))) {
            throw new IllegalStateException("The cloud eval index was built with another position key format. "
                    + "Delete the index folder and run CloudEvalIndexBuilder again.");
        }
    }

    /**
     * Initializes a new index with the given settings, or checks that an existing index was built with them
     * (a resumed build must keep the same settings).
     */
    public void initOrCheck(BuildSettings settings) throws RocksDBException {
        BuildSettings stored = getBuildSettings();
        if (stored == null) {
            db.put(meta, KEY_FORMAT, PositionKey.FORMAT.getBytes(StandardCharsets.UTF_8));
            db.put(meta, BUILD_SETTINGS, settings.encode().getBytes(StandardCharsets.UTF_8));
            return;
        }
        checkKeyFormat();
        if (!stored.equals(settings)) {
            throw new IllegalStateException("The index is being built with other settings (" + stored.describe()
                    + "). Run with --line-moves=" + stored.lineMoves() + " --min-pieces=" + stored.minPieces()
                    + ", or delete the index folder to start over with new settings.");
        }
    }

    /** @return the build settings, or null for a new index */
    public BuildSettings getBuildSettings() throws RocksDBException {
        byte[] value = db.get(meta, BUILD_SETTINGS);
        return value == null ? null : BuildSettings.decode(value);
    }

    /** @return the build progress, or null if nothing is indexed yet */
    public CloudEvalProgress getProgress() throws RocksDBException {
        byte[] value = db.get(meta, PROGRESS);
        return value == null ? null : CloudEvalProgress.decode(value);
    }

    /** Reads one position, or null if it isn't in the index. */
    public CloudEvalPosition get(String key) throws RocksDBException {
        byte[] value = db.get(positions, key.getBytes(StandardCharsets.UTF_8));
        return value == null ? null : CloudEvalCodec.decode(key, value);
    }

    /**
     * Writes a batch of positions and the progress in one atomic write.
     *
     * @param puts   new positions, already encoded
     * @param merges positions whose evaluations must be added to what's stored under the same key (positions that
     *               occur in the dump under several FENs)
     */
    public void write(Map<String, byte[]> puts, Map<String, CloudEvalPosition> merges, CloudEvalProgress progress)
            throws RocksDBException {
        try (WriteBatch batch = new WriteBatch()) {
            for (Map.Entry<String, byte[]> e : puts.entrySet()) {
                batch.put(positions, e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue());
            }
            for (Map.Entry<String, CloudEvalPosition> e : merges.entrySet()) {
                byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] existing = puts.get(e.getKey());
                if (existing == null) {
                    existing = db.get(positions, key);
                }
                CloudEvalPosition merged = e.getValue();
                if (existing != null) {
                    merged = CloudEvalCodec.decode(e.getKey(), existing).plus(merged);
                }
                batch.put(positions, key, CloudEvalCodec.encode(merged));
            }
            batch.put(meta, PROGRESS, progress.encode());
            db.write(writeOptions, batch);
        }
    }

    /** Approximate number of stored positions (RocksDB estimate). */
    public long estimatePositions() {
        try {
            return Long.parseLong(db.getProperty(positions, "rocksdb.estimate-num-keys").trim());
        } catch (RocksDBException | NumberFormatException e) {
            return -1;
        }
    }

    /** Flushes memtables to disk so the next start doesn't need to replay the write-ahead log. */
    public void flushToDisk() throws RocksDBException {
        try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            db.flush(flushOptions, handles);
        }
    }

    /**
     * Rewrites all positions with the current table settings ({@link RocksDbMemory#tableConfig()}). Takes long and
     * needs free disk space about the size of the index; the index must be opened for writing.
     */
    public void compactAll() throws RocksDBException {
        try (CompactRangeOptions options = new CompactRangeOptions()) {
            options.setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kForce);
            options.setExclusiveManualCompaction(true);
            options.setMaxSubcompactions(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            db.compactRange(positions, null, null, options);
        }
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle handle : handles) {
            handle.close();
        }
        db.close();
        writeOptions.close();
        dbOptions.close();
        positionsOptions.close();
        defaultOptions.close();
        // the block cache and the Bloom filter are shared by all databases and stay open (RocksDbMemory)
    }
}
