package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.sql.PostgresRow;
import com.telcobright.billing.mediation.sql.SqlDialect;

/**
 * The value tuple of an {@code acc_chargeable} row, per engine. The columns are the same 33 on both
 * ({@code acc_chargeable.ExtInsertColumns}); only the literals differ — MySQL's from the model's own verbatim tuple,
 * PostgreSQL's from {@link PostgresRow}.
 */
public final class ChargeableRowSql {
    private ChargeableRowSql() {}

    private static final PostgresRow<acc_chargeable> Postgres =
            new PostgresRow<>(acc_chargeable.class, acc_chargeable.ExtInsertColumns);

    public static StringBuilder Values(acc_chargeable row, SqlDialect dialect) {
        return dialect == SqlDialect.PostgreSql ? Postgres.Values(row) : row.GetExtInsertValues();
    }
}
