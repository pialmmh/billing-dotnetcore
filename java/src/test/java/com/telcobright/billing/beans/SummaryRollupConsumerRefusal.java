package com.telcobright.billing.beans;

import com.telcobright.billing.mediation.sql.SqlDialect;

/** Test access to {@code SummaryRollupConsumer.RefuseOnPostgres} (package-private) from another package's test. */
public final class SummaryRollupConsumerRefusal {
    private SummaryRollupConsumerRefusal() {}

    public static void On(SqlDialect dialect) {
        SummaryRollupConsumer.RefuseOnPostgres(dialect);
    }
}
