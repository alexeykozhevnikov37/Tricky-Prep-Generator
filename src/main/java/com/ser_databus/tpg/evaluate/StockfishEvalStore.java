package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.util.RocksDbMemory;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.ser_databus.tpg.util.MoveUtil.stockfishUciToMove;

/**
 * RocksDB storage of the evaluations calculated by the local Stockfish, so they don't have to be kept in memory.
 * <ul>
 *     <li>"evals": "FEN without move counters|depth" (UTF-8) -> encoded {@link LineEval} or "no legal moves"</li>
 *     <li>"moveEvals": "FEN without move counters|depth|moves" (UTF-8) -> encoded {@link LineEval} list: the evals of
 *     a set of sibling moves calculated in one search ({@link StockfishClient#evaluateMovesAsync})</li>
 *     <li>default column family: metadata</li>
 * </ul>
 * On the first start, the old text cache ({@link #LEGACY_CACHE_FILE}) is imported. The text file is left as it is
 * and isn't written any more; if the database folder is deleted, it is imported again.
 * <p>
 * Only one process can write the database. A second process (e.g. a console run while the explorer is open) opens
 * it read-only: it reads the stored evaluations, but its own new ones aren't stored.
 * <p>
 * Thread-safe. Evaluations are written as soon as they arrive (the write-ahead log keeps them safe), so there is
 * nothing to flush.
 */
@Slf4j
public class StockfishEvalStore implements AutoCloseable {
    public static final String DEFAULT_DIR = "data/stockfishEvals";
    public static final String LEGACY_CACHE_FILE = "src/main/resources/evalCache.txt";

    private static final byte[] EVALS_CF = "evals".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MOVE_EVALS_CF = "moveEvals".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEGACY_IMPORTED = "legacyCacheImported".getBytes(StandardCharsets.UTF_8);
    private static final int IMPORT_BATCH = 10_000;

    private static final byte NO_LEGAL_MOVES = 0;
    private static final byte HAS_EVAL = 1;
    /** Instead of the move squares when the engine gave a score but no move */
    private static final byte NO_MOVE = -1;
    /** flag, eval (double), depth, from, to, promotion */
    private static final int EVAL_VALUE_SIZE = 1 + 8 + 1 + 1 + 1 + 1;
    /** One line of a move set: eval (double), depth, from, to, promotion. The value starts with the line count. */
    private static final int MOVE_EVAL_LINE_SIZE = 8 + 1 + 1 + 1 + 1;
    private static final Square[] SQUARES = Square.values();
    private static final Piece[] PIECES = Piece.values();

    static {
        RocksDB.loadLibrary();
    }

    private final ColumnFamilyOptions evalsOptions;
    private final ColumnFamilyOptions moveEvalsOptions;
    private final ColumnFamilyOptions defaultOptions;
    private final DBOptions dbOptions;
    private final WriteOptions writeOptions;
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB db;
    private final ColumnFamilyHandle meta;
    private final ColumnFamilyHandle evals;
    /** Null if the database was opened read-only and was written by a version without move sets */
    private final ColumnFamilyHandle moveEvals;
    private final boolean readOnly;
    /** Reads and writes share the lock; close() takes it exclusively, so it never closes the DB under a request. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    public static StockfishEvalStore open() {
        return new StockfishEvalStore(Paths.get(DEFAULT_DIR), Paths.get(LEGACY_CACHE_FILE));
    }

    public StockfishEvalStore(Path dir, Path legacyCacheFile) {
        evalsOptions = new ColumnFamilyOptions()
                // Evaluations arrive one by one and slowly: small memtables are plenty
                .setWriteBufferSize(8L << 20)
                .setMaxWriteBufferNumber(2)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setTableFormatConfig(RocksDbMemory.tableConfig());
        moveEvalsOptions = new ColumnFamilyOptions()
                .setWriteBufferSize(8L << 20)
                .setMaxWriteBufferNumber(2)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setTableFormatConfig(RocksDbMemory.tableConfig());
        defaultOptions = new ColumnFamilyOptions();
        dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxBackgroundJobs(2)
                .setKeepLogFileNum(3);
        writeOptions = new WriteOptions();

        List<ColumnFamilyDescriptor> descriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
                new ColumnFamilyDescriptor(EVALS_CF, evalsOptions),
                new ColumnFamilyDescriptor(MOVE_EVALS_CF, moveEvalsOptions));
        String path = dir.toAbsolutePath().toString();
        RocksDB opened;
        boolean openedReadOnly = false;
        try {
            Files.createDirectories(dir);
            opened = RocksDB.open(dbOptions, path, descriptors, handles);
        } catch (RocksDBException e) {
            // Most likely another process has the database open for writing
            log.warn("Couldn't open the Stockfish eval database at {} for writing ({}); opening it read-only, "
                    + "new evaluations won't be stored", path, e.getMessage());
            handles.clear();
            try {
                opened = openReadOnly(path, descriptors);
                openedReadOnly = true;
            } catch (RocksDBException e2) {
                closeOptions();
                throw new IllegalStateException("Couldn't open the Stockfish eval database at " + path, e2);
            }
        } catch (IOException e) {
            closeOptions();
            throw new IllegalStateException("Couldn't create the Stockfish eval database folder " + path, e);
        }
        db = opened;
        readOnly = openedReadOnly;
        meta = handles.get(0);
        evals = handles.get(1);
        moveEvals = handles.size() > 2 ? handles.get(2) : null;

        if (!readOnly) {
            try {
                importLegacyCache(legacyCacheFile);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
        log.info("Stockfish eval database: about {} evaluations{}", estimateEvals(), readOnly ? " (read-only)" : "");
    }

    /**
     * @return the stored evaluation; {@code Optional.empty()} if it's stored that the side to move has no legal
     * moves; null if the position isn't stored at this depth
     */
    @SuppressWarnings("OptionalAssignedToNull")
    public Optional<LineEval> get(String key) {
        lock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("The Stockfish eval database is closed");
            }
            byte[] value = db.get(evals, key.getBytes(StandardCharsets.UTF_8));
            return value == null ? null : decode(value, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Couldn't read " + key + " from the Stockfish eval database", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Opens the database read-only. A database written by an older version has no move evals column family (it can't
     * be created read-only), so it's opened without it then.
     */
    private RocksDB openReadOnly(String path, List<ColumnFamilyDescriptor> descriptors) throws RocksDBException {
        try {
            return RocksDB.openReadOnly(dbOptions, path, descriptors, handles);
        } catch (RocksDBException e) {
            handles.clear();
            return RocksDB.openReadOnly(dbOptions, path, descriptors.subList(0, 2), handles);
        }
    }

    /**
     * @return the stored evals of a move set (see {@link StockfishClient#evaluateMovesAsync}) in the stored order,
     * or null if the move set isn't stored at this depth
     */
    public List<LineEval> getMoveEvals(String key) {
        if (moveEvals == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("The Stockfish eval database is closed");
            }
            byte[] value = db.get(moveEvals, key.getBytes(StandardCharsets.UTF_8));
            return value == null ? null : decodeMoveEvals(value, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Couldn't read " + key + " from the Stockfish eval database", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Stores the evals of a move set. Ignored if the database is read-only or already closed (shutting down). */
    public void putMoveEvals(String key, List<LineEval> lines) {
        if (readOnly || moveEvals == null) {
            return;
        }
        lock.readLock().lock();
        try {
            if (closed) {
                log.debug("The Stockfish eval database is closed, {} isn't stored", key);
                return;
            }
            db.put(moveEvals, writeOptions, key.getBytes(StandardCharsets.UTF_8), encodeMoveEvals(lines));
        } catch (RocksDBException e) {
            log.error("Couldn't store {} in the Stockfish eval database", key, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Stores an evaluation. Ignored if the database is read-only or already closed (shutting down). */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public void put(String key, Optional<LineEval> lineEval) {
        if (readOnly) {
            return;
        }
        lock.readLock().lock();
        try {
            if (closed) {
                log.debug("The Stockfish eval database is closed, {} isn't stored", key);
                return;
            }
            db.put(evals, writeOptions, key.getBytes(StandardCharsets.UTF_8), encode(lineEval));
        } catch (RocksDBException e) {
            log.error("Couldn't store {} in the Stockfish eval database", key, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** Approximate number of stored evaluations (RocksDB estimate). */
    public long estimateEvals() {
        try {
            return Long.parseLong(db.getProperty(evals, "rocksdb.estimate-num-keys").trim());
        } catch (RocksDBException | NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (ColumnFamilyHandle handle : handles) {
                handle.close();
            }
            db.close();
            closeOptions();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void closeOptions() {
        writeOptions.close();
        dbOptions.close();
        evalsOptions.close();
        moveEvalsOptions.close();
        defaultOptions.close();
        // the block cache and the Bloom filter are shared by all databases and stay open (RocksDbMemory)
    }

    /** Imports the old text cache once ("FEN|depth=eval=move" lines, "null" for no legal moves). */
    private void importLegacyCache(Path file) {
        try {
            if (db.get(meta, LEGACY_IMPORTED) != null || !Files.isRegularFile(file)) {
                return;
            }
            log.info("Importing the old Stockfish eval cache {} into the database...", file.toAbsolutePath());
            long start = System.nanoTime();
            long imported = 0;
            long skipped = 0;
            Board board = new Board();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                WriteBatch batch = new WriteBatch();
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        String[] parts = line.split("=");
                        int depthIndex = parts.length == 3 ? parts[0].lastIndexOf('|') : -1;
                        if (depthIndex < 0) {
                            if (!line.isEmpty()) {
                                skipped++;
                            }
                            continue;
                        }
                        String key = parts[0];
                        try {
                            Optional<LineEval> lineEval;
                            if ("null".equals(parts[1])) {
                                lineEval = Optional.empty();
                            } else {
                                int depth = Integer.parseInt(key.substring(depthIndex + 1));
                                board.loadFromFen(key.substring(0, depthIndex));
                                lineEval = Optional.of(new LineEval(1, Double.parseDouble(parts[1]),
                                        stockfishUciToMove(board, parts[2]), depth));
                            }
                            batch.put(evals, key.getBytes(StandardCharsets.UTF_8), encode(lineEval));
                            imported++;
                        } catch (RuntimeException e) {
                            skipped++;
                            continue;
                        }
                        if (batch.count() >= IMPORT_BATCH) {
                            db.write(writeOptions, batch);
                            batch.close();
                            batch = new WriteBatch();
                        }
                    }
                    batch.put(meta, LEGACY_IMPORTED, Long.toString(imported).getBytes(StandardCharsets.UTF_8));
                    db.write(writeOptions, batch);
                } finally {
                    batch.close();
                }
            }
            log.info("Imported {} evaluations ({} unreadable lines skipped) in {} ms", imported, skipped,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RocksDBException e) {
            throw new IllegalStateException("Couldn't import the old Stockfish eval cache " + file.toAbsolutePath(), e);
        }
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static byte[] encode(Optional<LineEval> lineEval) {
        if (lineEval.isEmpty()) {
            return new byte[]{NO_LEGAL_MOVES};
        }
        LineEval le = lineEval.get();
        Move move = le.move;
        return ByteBuffer.allocate(EVAL_VALUE_SIZE)
                .put(HAS_EVAL)
                .putDouble(le.eval)
                .put((byte) Math.min(le.depth, Byte.MAX_VALUE))
                .put(move == null ? NO_MOVE : (byte) move.getFrom().ordinal())
                .put(move == null ? NO_MOVE : (byte) move.getTo().ordinal())
                .put(move == null ? NO_MOVE : (byte) move.getPromotion().ordinal())
                .array();
    }

    private static byte[] encodeMoveEvals(List<LineEval> lines) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + lines.size() * MOVE_EVAL_LINE_SIZE).put((byte) lines.size());
        for (LineEval le : lines) {
            buffer.putDouble(le.eval)
                    .put((byte) Math.min(le.depth, Byte.MAX_VALUE))
                    .put((byte) le.move.getFrom().ordinal())
                    .put((byte) le.move.getTo().ordinal())
                    .put((byte) le.move.getPromotion().ordinal());
        }
        return buffer.array();
    }

    private static List<LineEval> decodeMoveEvals(byte[] value, String key) {
        int count = value.length == 0 ? -1 : value[0] & 0xFF;
        if (count < 0 || value.length != 1 + count * MOVE_EVAL_LINE_SIZE) {
            throw new IllegalStateException("Unreadable Stockfish move evals stored for " + key);
        }
        ByteBuffer buffer = ByteBuffer.wrap(value, 1, value.length - 1);
        List<LineEval> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double eval = buffer.getDouble();
            int depth = buffer.get();
            Square from = SQUARES[buffer.get()];
            Square to = SQUARES[buffer.get()];
            Piece promotion = PIECES[buffer.get()];
            lines.add(new LineEval(i + 1, eval, new Move(from, to, promotion), depth));
        }
        return lines;
    }

    private static Optional<LineEval> decode(byte[] value, String key) {
        if (value.length == 1 && value[0] == NO_LEGAL_MOVES) {
            return Optional.empty();
        }
        if (value.length != EVAL_VALUE_SIZE || value[0] != HAS_EVAL) {
            throw new IllegalStateException("Unreadable Stockfish eval stored for " + key);
        }
        ByteBuffer buffer = ByteBuffer.wrap(value, 1, EVAL_VALUE_SIZE - 1);
        double eval = buffer.getDouble();
        int depth = buffer.get();
        byte from = buffer.get();
        byte to = buffer.get();
        byte promotion = buffer.get();
        Move move = from == NO_MOVE ? null : new Move(SQUARES[from], SQUARES[to], PIECES[promotion]);
        return Optional.of(new LineEval(1, eval, move, depth));
    }
}
