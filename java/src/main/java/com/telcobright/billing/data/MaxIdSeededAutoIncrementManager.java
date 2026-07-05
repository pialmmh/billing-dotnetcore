package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The LIVE id source for a batch: each table's counter is seeded lazily from the table's current
 * {@code max(id)} through the batch's own connection/transaction, then counts monotonically in memory
 * — so explicit-id inserts never collide with rows already in the schema. This is the port of the
 * legacy {@code AutoIncrementManager}, whose counters lived in the {@code autoincrementcounter} table;
 * per-batch max-seeding is equivalent because the batch runner's per-tenant lock guarantees one batch
 * at a time per schema (the legacy guarantee came from the single job runner per tenant).
 *
 * <p>{@code "cdr"} seeds from the greater of {@code cdr.idcall} and {@code cdrerror.idcall}: an error
 * cdr may be reprocessed later, and its IdCall must not be reissued to a new call in the meantime.</p>
 */
public final class MaxIdSeededAutoIncrementManager implements IAutoIncrementManager {
    private static final Map<String, String> SeedQueries = Map.of(
            "cdr", "select greatest(coalesce((select max(idcall) from cdr), 0),"
                    + " coalesce((select max(idcall) from cdrerror), 0))");

    private final Connection _conn;
    private final Map<String, Long> _next = new HashMap<>();

    public MaxIdSeededAutoIncrementManager(Connection conn) {
        _conn = conn;
    }

    @Override
    public long GetNewCounter(String entityOrTableName) {
        Long value = _next.get(entityOrTableName);
        if (value == null) value = Seed(entityOrTableName) + 1;
        _next.put(entityOrTableName, value + 1);
        return value;
    }

    /** Table names are internal literals ("acc_chargeable", "cdr"), never external input. */
    private long Seed(String table) {
        String query = SeedQueries.getOrDefault(table, "select coalesce(max(id), 0) from " + table);
        try (var stmt = _conn.createStatement(); var rs = stmt.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("id seed failed for table " + table, e);
        }
    }
}
