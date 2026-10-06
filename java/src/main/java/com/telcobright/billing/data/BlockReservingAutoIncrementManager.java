package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * A PROCESS-LIFETIME {@link IAutoIncrementManager} that hands out ids from durably reserved blocks — the
 * replacement for {@link MaxIdSeededAutoIncrementManager}'s per-batch {@code select max(id)} on the tables
 * where that scan is unaffordable.
 *
 * <p>{@link MaxIdSeededAutoIncrementManager} is correct but is bound to the batch's {@link java.sql.Connection},
 * so it dies with the connection and re-seeds on the next batch. On {@code acc_chargeable} that seed is a full
 * scan of 8.85 M rows across ~2,368 partitions (no PK, no index on {@code id}), measured at 3.68 s — 79% of
 * every batch. This manager seeds ONCE per process per schema and thereafter touches the database only when a
 * block runs out.</p>
 *
 * <p><b>Why a durable reservation and not just a cached counter.</b> A process-local counter seeded once at
 * startup is NOT safe here, and {@code GET_LOCK('billing_batch_&lt;schema&gt;')} does not make it safe: that
 * lock is taken and released per batch, so two instances that both seeded to X before either wrote will each
 * take the lock in turn and each issue ids from X. The lock serialises the writes; it does nothing about two
 * caches that are independently stale. Because MySQL forbids a UNIQUE key that does not contain the
 * partitioning column, {@code acc_chargeable} cannot have {@code UNIQUE(id)}, so such a collision would be
 * silent. Reserving through the shared counter row removes the possibility instead of narrowing it: every
 * block comes from one atomic statement against one row, so no two allocators anywhere can be handed the same
 * id.</p>
 *
 * <p>Ids are consumed from the block whether or not the batch commits, so a rollback or a crash burns the rest
 * of the block and leaves a gap. That is deliberate — see {@link IIdBlockReserver}.</p>
 *
 * <p>Instances are per SCHEMA (a tenant's ids must never come from another tenant's counter) and are shared by
 * every thread in the process, so {@link #GetNewCounter} is synchronized.</p>
 */
public final class BlockReservingAutoIncrementManager implements IAutoIncrementManager {

    /**
     * Ids reserved per round trip. Sized deliberately CONSERVATIVELY at ~3 batches' worth (a batch writes at
     * most 1,000 chargeables, and averages ~667), for one reason: while a block is held, an id written by some
     * other writer could in principle land inside it. The old per-batch {@code max(id)} re-read narrowed that
     * window to one batch (~5 s); a 2,000-id block keeps it to ~15 s rather than the ~70 s a 10,000-id block
     * would give. Evidence says no such other writer exists (over the whole retained history,
     * {@code acc_chargeable} INSERTs and {@code max(id)} seeds match exactly 30,294 : 30,294, so every insert
     * was billing's), but the cost of staying close to the old exposure is one extra primary-key UPDATE per
     * ~3 batches, which is nothing against the 3.68 s this removes.
     */
    public static final int DefaultBlockSize = 2_000;

    private final IIdBlockReserver _reserver;
    private final int _blockSize;
    private final Map<String, Block> _blocks = new HashMap<>();

    /** The half-open remainder of a reserved block: {@code next} is the id to issue, {@code last} is inclusive. */
    private static final class Block {
        long next;
        long last;

        Block(long next, long last) {
            this.next = next;
            this.last = last;
        }

        boolean exhausted() {
            return next > last;
        }
    }

    public BlockReservingAutoIncrementManager(IIdBlockReserver reserver) {
        this(reserver, DefaultBlockSize);
    }

    public BlockReservingAutoIncrementManager(IIdBlockReserver reserver, int blockSize) {
        if (blockSize < 1) throw new IllegalArgumentException("blockSize must be >= 1, got " + blockSize);
        _reserver = reserver;
        _blockSize = blockSize;
    }

    @Override
    public synchronized long GetNewCounter(String entityOrTableName) {
        Block block = _blocks.get(entityOrTableName);
        if (block == null || block.exhausted()) {
            block = Reserve(entityOrTableName);
            _blocks.put(entityOrTableName, block);
        }
        return block.next++;
    }

    private Block Reserve(String table) {
        try {
            long first = _reserver.ReserveBlock(table, _blockSize);
            return new Block(first, first + _blockSize - 1);
        } catch (SQLException e) {
            // FAIL THE BATCH, never fall back to a guessed id: an id we are not certain is ours is exactly the
            // failure this class exists to prevent. The batch rolls back and Kafka redelivers it.
            throw new RuntimeException("id block reservation failed for table " + table, e);
        }
    }

    /** How many ids of the current block are still unissued — for tests and diagnostics. */
    public synchronized long RemainingInBlock(String entityOrTableName) {
        Block block = _blocks.get(entityOrTableName);
        return block == null ? 0 : Math.max(0, block.last - block.next + 1);
    }
}
