package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The process-lifetime, per-SCHEMA id sources for the OUTGOING-SMS path: one {@link CounterSeededIdAllocator} per
 * tenant schema, so every SMS-written table takes its ids from that schema's shared {@code autoincrementcounter}.
 * A tenant's ids are never drawn from another tenant's counter row.
 */
public final class SmsIdAllocators {
    private final MySqlConnectionFactory _connections;
    private final Map<String, CounterSeededIdAllocator> _perSchema = new ConcurrentHashMap<>();

    public SmsIdAllocators(MySqlConnectionFactory connections) {
        _connections = connections;
    }

    /** The id source for {@code schema}; {@code blockSize} applies when the schema's allocator is first built. */
    public IAutoIncrementManager For(String schema, int blockSize) {
        return _perSchema.computeIfAbsent(schema,
                s -> new CounterSeededIdAllocator(new MySqlIdBlockReserver(_connections, s), Math.max(1, blockSize)));
    }
}
