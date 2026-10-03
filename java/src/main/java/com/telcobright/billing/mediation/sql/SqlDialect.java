package com.telcobright.billing.mediation.sql;

/**
 * The SQL engine a batch's statements are written for. The pipeline and its writers are the same on both; only
 * the EDGE differs (brief B6): the literals the writers emit and the columns of the {@code cdr} row here, the
 * connection, the per-tenant batch lock and the tables in the {@code data} package ({@code DatasourceEdge}).
 *
 * <p>An {@link ISqlExecutor} says which engine its connection speaks ({@link ISqlExecutor#Dialect()}); the writers
 * ask it. MySQL is the default, so every existing executor and test is MySQL without saying so.
 */
public enum SqlDialect {
    MySql,
    PostgreSql
}
