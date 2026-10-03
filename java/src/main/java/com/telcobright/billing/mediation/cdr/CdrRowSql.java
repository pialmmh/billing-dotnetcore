package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.PostgresRow;
import com.telcobright.billing.mediation.sql.SqlDialect;

/**
 * The columns and the value tuple of a {@code cdr} / {@code cdrerror} row, per engine.
 *
 * <ul>
 *   <li><b>MySQL</b> — the legacy 104 columns ({@code cdr.ExtInsertColumns}) and the model's own verbatim tuple
 *       ({@code GetExtInsertValues}). Unchanged: the MySQL tables do not have the wire's six columns.</li>
 *   <li><b>PostgreSQL</b> — the same 104, in the same order, then the ratified wire's six
 *       ({@link #WireColumns}): the table billing-core makes there has them
 *       ({@code sql/postgres/billing-tables.sql}), so on PostgreSQL the record's amounts, unit, account, cause and
 *       call key are written to their own columns as they came.</li>
 * </ul>
 */
public final class CdrRowSql {
    private CdrRowSql() {}

    /** The ratified wire's own columns (ad-is-a-call §4.1), after the legacy 104. */
    public static final String WireColumns =
            "ResellerHierarchy,ChannelCallUuid,HangupCause,InPartnerUom,IdPackageAccount,PackageAmount";

    public static final String PostgresColumns = cdr.ExtInsertColumns + "," + WireColumns;

    private static final PostgresRow<cdr> Postgres = new PostgresRow<>(cdr.class, PostgresColumns);

    public static String Columns(SqlDialect dialect) {
        return dialect == SqlDialect.PostgreSql ? PostgresColumns : cdr.ExtInsertColumns;
    }

    public static StringBuilder Values(cdr row, SqlDialect dialect) {
        return dialect == SqlDialect.PostgreSql ? Postgres.Values(row) : row.GetExtInsertValues();
    }
}
