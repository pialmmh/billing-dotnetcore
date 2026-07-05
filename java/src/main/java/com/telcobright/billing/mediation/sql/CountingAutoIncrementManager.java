// Split from legacy Mediation/Summary/ISqlExecutor.cs.
package com.telcobright.billing.mediation.sql;

import java.util.HashMap;
import java.util.Map;

import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

/**
 * A simple monotonic id source per table (legacy IAutoIncrementManager). The live implementation seeds each
 * counter from {@code max(id)} of the tier table; this default just counts from a base.
 */
public final class CountingAutoIncrementManager implements IAutoIncrementManager {
    private final Map<String, Long> _next = new HashMap<>();
    private final long _base;

    public CountingAutoIncrementManager() {
        this(1);
    }

    public CountingAutoIncrementManager(long start) {
        _base = start;
    }

    @Override
    public long GetNewCounter(String entityOrTableName) {
        long value = _next.containsKey(entityOrTableName) ? _next.get(entityOrTableName) : _base;
        _next.put(entityOrTableName, value + 1);
        return value;
    }
}
