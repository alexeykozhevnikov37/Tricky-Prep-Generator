package com.ser_databus.tpg.util;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.IndexType;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;

/**
 * Memory settings shared by all RocksDB databases of the application (the elite games index, the cloud eval index
 * and the Stockfish evals).
 * <p>
 * All databases use one block cache, so the total RocksDB read memory is capped by {@link #CACHE_MB} however many
 * databases are open. Index and filter blocks are kept in that cache too: by default RocksDB keeps the index and the
 * Bloom filter of every open file outside of any cache, which for indexes of many GB costs hundreds of MB.
 * <p>
 * New files are written with partitioned indexes and filters, so a lookup only loads the small partitions it needs
 * instead of a whole file's index and filter. Existing files keep their format until they are rewritten by a
 * compaction ({@link com.ser_databus.tpg.CompactRocksIndexes} rewrites them all).
 * <p>
 * The cache size can be changed with {@code -Dtpg.rocksdb.cacheMb=...}, e.g. raised while building an index.
 */
public final class RocksDbMemory {
    static {
        RocksDB.loadLibrary();
    }

    /** Total block cache size of all databases, MB. */
    public static final long CACHE_MB = Long.getLong("tpg.rocksdb.cacheMb", 192L);

    /**
     * Half of the cache is reserved for index and filter blocks (high priority), so a burst of data block reads
     * doesn't evict them.
     */
    private static final double HIGH_PRIORITY_POOL_RATIO = 0.5;

    /** Process-wide, never closed: the databases that use them may be closed in any order by shutdown hooks. */
    private static final LRUCache SHARED_CACHE = new LRUCache(CACHE_MB << 20, -1, false, HIGH_PRIORITY_POOL_RATIO);
    private static final BloomFilter BLOOM_FILTER = new BloomFilter(10, false);

    private RocksDbMemory() {
    }

    /** Table settings for a column family: shared cache, index and filters in the cache, partitioned for new files. */
    public static BlockBasedTableConfig tableConfig() {
        return new BlockBasedTableConfig()
                .setBlockCache(SHARED_CACHE)
                .setFilterPolicy(BLOOM_FILTER)
                .setBlockSize(16 * 1024)
                // index and filter blocks are charged to the cache instead of being kept for every open file
                .setCacheIndexAndFilterBlocks(true)
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                // the newest files (L0) are small and read on every lookup: keep their index and filter loaded
                .setPinL0FilterAndIndexBlocksInCache(true)
                // new files: two-level index and partitioned filters, only the needed partitions are loaded
                .setIndexType(IndexType.kTwoLevelIndexSearch)
                .setPartitionFilters(true)
                .setMetadataBlockSize(4096)
                .setPinTopLevelIndexAndFilter(true);
    }
}
