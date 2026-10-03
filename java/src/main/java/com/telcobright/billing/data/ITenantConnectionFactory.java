package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.SqlDialect;

import java.sql.Connection;

/**
 * Opens the one connection a tenant's batch is written through — to the tenant's schema, on the datasource the
 * active profile names ({@code billing.datasource.kind}). A tenant is a database on MySQL
 * ({@link MySqlConnectionFactory}) and a schema of the one switch database on PostgreSQL
 * ({@link PostgresConnectionFactory}); the caller neither knows nor cares which.
 */
public interface ITenantConnectionFactory {

    /** True once the datasource has what a connection needs (so an entry point can refuse cleanly otherwise). */
    boolean IsConfigured();

    /** Open a connection whose unqualified table names are the given tenant's. The caller closes it. */
    Connection Open(String tenant);

    SqlDialect Dialect();
}
