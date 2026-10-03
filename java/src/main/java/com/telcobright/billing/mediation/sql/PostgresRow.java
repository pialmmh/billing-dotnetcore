package com.telcobright.billing.mediation.sql;

import java.lang.reflect.Field;

/**
 * Builds the {@code (v1,v2,…)} tuple of one row for PostgreSQL from an ordered column list — each column is the
 * public field of the same name on the row's class, written as its {@link PostgresLiterals literal}.
 *
 * <p>The legacy models ({@code cdr}, {@code acc_chargeable}) carry a hand-written, verbatim-ported tuple builder for
 * MySQL ({@code GetExtInsertValues}); those stay untouched and stay MySQL's. This class is the PostgreSQL edge of
 * the same writers: same columns, same order, the other engine's literals — and it can carry columns the legacy
 * tuple does not have (the ratified wire's six on {@code cdr}).
 *
 * <p>A column with no field of its name is a programming error and fails at class-load time, not at the first row.
 */
public final class PostgresRow<T> {
    private final String columns;
    private final Field[] fields;

    public PostgresRow(Class<T> type, String columns) {
        this.columns = columns;
        String[] names = columns.split(",");
        this.fields = new Field[names.length];
        for (int i = 0; i < names.length; i++) {
            try {
                fields[i] = type.getField(names[i].trim());
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(type.getSimpleName() + " has no field for column '" + names[i].trim() + "'", e);
            }
        }
    }

    /** The column list, as given: {@code A,B,C}. */
    public String Columns() {
        return columns;
    }

    /** {@code (v1,v2,…)} for one row. */
    public StringBuilder Values(T row) {
        StringBuilder tuple = new StringBuilder(fields.length * 8).append('(');
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) tuple.append(',');
            tuple.append(PostgresLiterals.Of(ValueOf(fields[i], row)));
        }
        return tuple.append(')');
    }

    private static Object ValueOf(Field field, Object row) {
        try {
            return field.get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read field " + field.getName(), e);
        }
    }
}
