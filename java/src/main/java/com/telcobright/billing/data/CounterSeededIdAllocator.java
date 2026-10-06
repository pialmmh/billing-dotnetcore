package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.IAutoIncrementManager;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The id source for the OUTGOING-SMS path: EVERY table it writes ({@code cdr} IdCall, {@code acc_chargeable},
 * {@code acc_transaction}, {@code account}, {@code acc_ledger_summary}) takes its ids from the schema's legacy
 * {@code autoincrementcounter} through {@link MySqlIdBlockReserver}'s atomic reservation — the counter legacy
 * and its portal also allocate from. A per-batch {@code max(id)} would not see a block another counter user has
 * reserved but not yet written; the shared counter does.
 *
 * <p>Each table is seeded ONCE per process (lazily, on first use): the counter is raised to at least the table's
 * live max id ({@code cdr}: the greater of {@code cdr.IdCall} and {@code cdrerror.IdCall}). {@code greatest()}
 * means seeding can only move a counter up.</p>
 */
public final class CounterSeededIdAllocator implements IAutoIncrementManager {
    private static final Logger log = Logger.getLogger(CounterSeededIdAllocator.class);

    /** table -> the (table, id column) pairs whose max seeds its counter. */
    private static final Map<String, String[][]> SeedSources = Map.of(
            "cdr", new String[][] { {"cdr", "idcall"}, {"cdrerror", "idcall"} });

    private final MySqlIdBlockReserver _reserver;
    private final BlockReservingAutoIncrementManager _blocks;
    private final Set<String> _seeded = new HashSet<>();

    public CounterSeededIdAllocator(MySqlIdBlockReserver reserver, int blockSize) {
        _reserver = reserver;
        _blocks = new BlockReservingAutoIncrementManager(reserver, blockSize);
    }

    @Override
    public synchronized long GetNewCounter(String table) {
        if (!_seeded.contains(table)) {
            Seed(table);
            _seeded.add(table);
        }
        return _blocks.GetNewCounter(table);
    }

    private void Seed(String table) {
        try {
            long max = 0;
            for (String[] src : SeedSources.getOrDefault(table, new String[][] { {table, "id"} }))
                max = Math.max(max, _reserver.CurrentMaxId(src[0], src[1]));
            long counter = _reserver.SeedAtLeast(table, max);
            log.infof("SMS id counter %s.%s seeded: max=%d, counter now %d", _reserver.Schema(), table, max, counter);
        } catch (SQLException e) {
            // never guess an id: the batch fails, rolls back, and the Kafka batch is redelivered
            throw new RuntimeException("seeding the id counter for " + _reserver.Schema() + "." + table + " failed", e);
        }
    }
}
