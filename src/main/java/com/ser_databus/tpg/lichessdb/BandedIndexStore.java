package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.elite.FileProgress;
import com.ser_databus.tpg.elite.PositionKey;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.util.RocksDbMemory;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * RocksDB storage of the rating-banded Lichess index (the counterpart of
 * {@link com.ser_databus.tpg.elite.EliteIndexStore}).
 * <ul>
 *     <li>"positions": position key ({@link PositionKey}, UTF-8) -> {@link BandedPositionCodec} value</li>
 *     <li>"progress": dump file name (UTF-8) -> {@link FileProgress}</li>
 *     <li>default column family: the format the index was built with ({@link #FORMAT})</li>
 * </ul>
 */
public class BandedIndexStore implements AutoCloseable {
    /** Plies of every game that are indexed, as in the Lichess opening explorer. */
    public static final int MAX_PLIES = 50;
    public static final String SPEEDS = "blitz,rapid,classical";

    /**
     * Everything that decides what the stored numbers mean. An index built with other values gives wrong answers when
     * mixed with new data, so it must be rebuilt.
     */
    public static final Map<String, String> FORMAT;

    static {
        Map<String, String> format = new LinkedHashMap<>();
        format.put("keyFormat", PositionKey.FORMAT);
        format.put("valueFormat", BandedPositionCodec.FORMAT);
        format.put("maxPlies", Integer.toString(MAX_PLIES));
        format.put("speeds", SPEEDS);
        format.put("filters", GameFilter.FORMAT);
        format.put("buckets", "lichess-average-rating:" + GameFilter.INDEXED_BUCKETS.stream()
                .map(LichessRating::toString).sorted().collect(Collectors.joining(",")));
        FORMAT = Collections.unmodifiableMap(format);
        RocksDB.loadLibrary();
    }

    private static final byte[] POSITIONS_CF = "positions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROGRESS_CF = "progress".getBytes(StandardCharsets.UTF_8);
    /** Keys per multiGet call while merging a buffer into the DB. */
    private static final int MULTI_GET_CHUNK = 10_000;

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
    public BandedIndexStore(Path dir) throws IOException, RocksDBException {
        this(dir, false);
    }

    /**
     * @param readOnly open an existing index for lookups only; any number of processes can do that, even while the
     *                 builder is running (they see the data as of the moment they opened it)
     */
    public BandedIndexStore(Path dir, boolean readOnly) throws IOException, RocksDBException {
        if (readOnly) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("Lichess index not found: " + dir.toAbsolutePath()
                        + ". Run LichessIndexBuilder first.");
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

    /** @return the stored format values; a key that isn't stored maps to null */
    public Map<String, String> getFormat() throws RocksDBException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : FORMAT.keySet()) {
            byte[] value = db.get(meta, key.getBytes(StandardCharsets.UTF_8));
            result.put(key, value == null ? null : new String(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    /**
     * Fails if the index was built with another {@link #FORMAT}: its data wouldn't match. A new empty index gets the
     * current format when {@code initializeIfEmpty} is set.
     */
    public void checkFormat(boolean initializeIfEmpty) throws RocksDBException {
        Map<String, String> stored = getFormat();
        if (stored.equals(FORMAT)) {
            return;
        }
        boolean nothingStored = stored.values().stream().allMatch(v -> v == null);
        if (nothingStored && initializeIfEmpty && getAllProgress().isEmpty()) {
            try (WriteBatch batch = new WriteBatch()) {
                for (Map.Entry<String, String> e : FORMAT.entrySet()) {
                    batch.put(meta, e.getKey().getBytes(StandardCharsets.UTF_8),
                            e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                db.write(writeOptions, batch);
            }
            return;
        }
        throw new IllegalStateException("The Lichess index was built with another format " + stored
                + ", the current one is " + FORMAT + ". Delete the index folder and run LichessIndexBuilder again.");
    }

    /** @return progress of all dump files the index has seen, by file name */
    public Map<String, FileProgress> getAllProgress() {
        Map<String, FileProgress> result = new TreeMap<>();
        try (RocksIterator it = db.newIterator(progress)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                result.put(new String(it.key(), StandardCharsets.UTF_8), FileProgress.decode(it.value()));
            }
        }
        return result;
    }

    /** Reads one stored position by its {@link PositionKey}, or null if the position isn't in the index. */
    public BandedPositionAgg getPosition(String positionKey) throws RocksDBException {
        byte[] value = db.get(positions, positionKey.getBytes(StandardCharsets.UTF_8));
        return value == null ? null : BandedPositionCodec.decode(value);
    }

    /**
     * Adds the buffered statistics to the stored ones and saves the file progress, all in one atomic write.
     *
     * @return number of positions that weren't in the index before
     */
    public long merge(Map<String, BandedPositionAgg> buffer, String fileName, FileProgress fileProgress)
            throws RocksDBException {
        List<String> keys = new ArrayList<>(buffer.keySet());
        // Sorted keys make the lookups and the write noticeably more cache friendly
        Collections.sort(keys);
        long newPositions = 0;
        try (WriteBatch batch = new WriteBatch()) {
            for (int start = 0; start < keys.size(); start += MULTI_GET_CHUNK) {
                List<String> chunk = keys.subList(start, Math.min(keys.size(), start + MULTI_GET_CHUNK));
                List<byte[]> keyBytes = new ArrayList<>(chunk.size());
                for (String key : chunk) {
                    keyBytes.add(key.getBytes(StandardCharsets.UTF_8));
                }
                List<byte[]> existing = db.multiGetAsList(Collections.nCopies(keyBytes.size(), positions), keyBytes);
                for (int i = 0; i < keyBytes.size(); i++) {
                    BandedPositionAgg merged = buffer.get(chunk.get(i));
                    byte[] stored = existing.get(i);
                    if (stored != null) {
                        BandedPositionAgg old = BandedPositionCodec.decode(stored);
                        old.addAll(merged);
                        merged = old;
                    } else {
                        newPositions++;
                    }
                    batch.put(positions, keyBytes.get(i), BandedPositionCodec.encode(merged));
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
        try (FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
            db.flush(flushOptions, Arrays.asList(positions, progress));
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
