package com.telcobright.billing.data;

import java.sql.SQLException;

/**
 * Durably reserves a CONTIGUOUS BLOCK of row ids for one table in one schema — the seam that lets
 * {@link BlockReservingAutoIncrementManager} be unit-tested (including two-writer races) with no database.
 *
 * <p>The contract is deliberately narrow and has exactly one safety rule: <b>two calls must never hand out
 * overlapping ranges</b>, whatever the caller does — same process, another process, or a concurrent thread.
 * That is why the reservation is a single atomic statement in the MySQL implementation rather than a
 * read-then-write pair.</p>
 *
 * <p>Reservation is INTENTIONALLY not part of the caller's batch transaction. It advances monotonically and
 * is never rolled back, so a rolled-back or crashed batch BURNS its ids and leaves a gap. Gaps are already
 * normal in this table (7,301 of them on the live PBX schema as of 2026-09-23, from rolled-back batches), and
 * burning a block is the price of never reissuing one.</p>
 */
public interface IIdBlockReserver {

    /**
     * Atomically reserve {@code count} ids and return the FIRST id of the reserved block; the block is
     * {@code [first, first + count - 1]} inclusive.
     *
     * @param table the logical table name (the {@code autoincrementcounter.tableName} key)
     * @param count how many ids to reserve; must be &gt;= 1
     */
    long ReserveBlock(String table, int count) throws SQLException;
}
