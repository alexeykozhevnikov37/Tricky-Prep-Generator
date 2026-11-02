package com.ser_databus.tpg.elite;

import com.ser_databus.tpg.util.RocksDbMemory;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * RocksDB storage of the elite games index.
 * <ul>
 *     <li>"positions": cropped FEN (UTF-8) -> {@link PositionCodec} value</li>
 *     <li>"progress": PGN file name (UTF-8) -> {@link FileProgress}</li>
 * </ul>
 */
public class EliteIndexStore implements AutoCloseable {
    private static final byte[] POSITIONS_CF = "positions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROGRESS_CF = "progress".getBytes(StandardCharsets.UTF_8);
    /** Keys per multiGet call while merging a buffer into the DB. */
    private static final int MULTI_GET_CHUNK = 10_000;

    static {
        RocksDB.loadLibrary();
    }

    private final ColumnFamilyOptions positionsOptions;
    private final ColumnFamilyOptions defaultOptions;
    private final DBOptions dbOptions;
    private final WriteOptions writeOptions;
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB db;
    private final ColumnFamilyHandle positions;
    private final ColumnFamilyHandle progress;
    /** Default column family: index metadata. */
    private final ColumnFamilyHandle meta;

    /** Opens the index for reading and writing, creating it if needed. Only one process can do this at a time. */
    public EliteIndexStore(Path dir) throws IOException, RocksDBException {
        this(dir, false);
    }

    /**
     * @param readOnly open an existing index for lookups only. A read-only store doesn't lock the index, so any number
     *                 of processes can read it, even while {@link EliteIndexBuilder} is adding files (a read-only store
     *                 sees the data as of the moment it was opened).
     */
    public EliteIndexStore(Path dir, boolean readOnly) throws IOException, RocksDBException {
        if (readOnly) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("Elite index not found: " + dir.toAbsolutePath()
                        + ". Run EliteIndexBuilder first.");
            }
        } else {
            Files.createDirectories(dir);
        }
        positionsOptions = new ColumnFamilyOptions()
                .optimizeLevelStyleCompaction()
                .setWriteBufferSize(128L << 20)
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
                new ColumnFamilyDescriptor(POSITIONS_CF, positionsOptions),
                new ColumnFamilyDescriptor(PROGRESS_CF, defaultOptions));
        String path = dir.toAbsolutePath().toString();
        db = readOnly
                ? RocksDB.openReadOnly(dbOptions, path, descriptors, handles)
                : RocksDB.open(dbOptions, path, descriptors, handles);
        meta = handles.get(0);
        positions = handles.get(1);
        progress = handles.get(2);
    }

    private static final byte[] KEY_FORMAT = "keyFormat".getBytes(StandardCharsets.UTF_8);

    /** @return the {@link PositionKey#FORMAT} the index was built with, or null for an index built before it existed */
    public String getKeyFormat() throws RocksDBException {
        byte[] value = db.get(meta, KEY_FORMAT);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public void setKeyFormat(String format) throws RocksDBException {
        db.put(meta, KEY_FORMAT, format.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fails if the index was built with another position key format: its keys wouldn't match the lookups.
     * A new empty index gets the current format.
     */
    public void checkKeyFormat(boolean initializeIfEmpty) throws RocksDBException {
        String format = getKeyFormat();
        if (PositionKey.FORMAT.equals(format)) {
            return;
        }
        if (format == null && initializeIfEmpty && getAllProgress().isEmpty()) {
            setKeyFormat(PositionKey.FORMAT);
            return;
        }
        throw new IllegalStateException("The elite index was built with an older position key format ("
                + (format == null ? "1" : format) + "). Delete the index folder and run EliteIndexBuilder again.");
    }

    /** @return progress of all files the index has seen, by file name */
    public Map<String, FileProgress> getAllProgress() {
        Map<String, FileProgress> result = new TreeMap<>();
        try (RocksIterator it = db.newIterator(progress)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                result.put(new String(it.key(), StandardCharsets.UTF_8), FileProgress.decode(it.value()));
            }
        }
        return result;
    }

    /** Reads one stored position, or null if the position isn't in the index. */
    public PositionAgg getPosition(String croppedFen) throws RocksDBException {
        byte[] value = db.get(positions, croppedFen.getBytes(StandardCharsets.UTF_8));
        return value == null ? null : PositionCodec.decode(value);
    }

    /**
     * Adds the buffered statistics to the stored ones and saves the file progress, all in one atomic write.
     *
     * @return number of positions that weren't in the index before
     */
    public long merge(Map<String, PositionAgg> buffer, String fileName, FileProgress fileProgress)
            throws RocksDBException {
        List<String> fens = new ArrayList<>(buffer.keySet());
        // Sorted keys make the lookups and the write noticeably more cache friendly
        Collections.sort(fens);
        long newPositions = 0;
        try (WriteBatch batch = new WriteBatch()) {
            for (int start = 0; start < fens.size(); start += MULTI_GET_CHUNK) {
                List<String> chunk = fens.subList(start, Math.min(fens.size(), start + MULTI_GET_CHUNK));
                List<byte[]> keys = new ArrayList<>(chunk.size());
                for (String fen : chunk) {
                    keys.add(fen.getBytes(StandardCharsets.UTF_8));
                }
                List<byte[]> existing = db.multiGetAsList(Collections.nCopies(keys.size(), positions), keys);
                for (int i = 0; i < keys.size(); i++) {
                    PositionAgg merged = buffer.get(chunk.get(i));
                    byte[] stored = existing.get(i);
                    if (stored != null) {
                        PositionAgg old = PositionCodec.decode(stored);
                        old.addAll(merged);
                        merged = old;
                    } else {
                        newPositions++;
                    }
                    batch.put(positions, keys.get(i), PositionCodec.encode(merged));
                }
            }
            batch.put(progress, fileName.getBytes(StandardCharsets.UTF_8), fileProgress.encode());
            db.write(writeOptions, batch);
        }
        return newPositions;
    }

    /** Approximate number of stored positions (RocksDB estimate). */
    public long estimatePositions() {
        try {
            String value = db.getProperty(positions, "rocksdb.estimate-num-keys");
            return Long.parseLong(value.trim());
        } catch (RocksDBException | NumberFormatException e) {
            return -1;
        }
    }

    /** Flushes memtables to disk so the next start doesn't need to replay the write-ahead log. */
    public void flushToDisk() throws RocksDBException {
        try (org.rocksdb.FlushOptions flushOptions = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
            db.flush(flushOptions, Arrays.asList(positions, progress));
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
