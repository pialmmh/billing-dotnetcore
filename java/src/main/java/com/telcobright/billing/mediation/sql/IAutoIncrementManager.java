// Split from legacy Mediation/Summary/Cache/ICacheble.cs.
package com.telcobright.billing.mediation.sql;

/**
 * Source of new row ids per entity/table (legacy IAutoIncrementManager; keyed by table name here instead
 * of the AutoIncrementCounterType enum).
 */
public interface IAutoIncrementManager {
    long GetNewCounter(String entityOrTableName);
}
