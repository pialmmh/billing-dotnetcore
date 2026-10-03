package com.telcobright.billing.mediation.sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The SQL literals of a row on PostgreSQL — the counterpart of {@link MySqlFieldExtensions}. Numbers, dates and
 * NULL are written exactly as on MySQL; a STRING is not:
 *
 * <ul>
 *   <li><b>A backslash is itself.</b> PostgreSQL ({@code standard_conforming_strings = on}; the connection factory
 *       sets it) reads {@code '…'} with no escape character, so only the quote is doubled. The MySQL writer doubles
 *       the backslash too; done here, {@code a\b} would be stored as {@code a\\b}.</li>
 *   <li><b>A NUL character is dropped.</b> PostgreSQL refuses one in {@code text}, and one refused row rolls the
 *       whole batch back — which the ingest then reads again, for ever. A producer's stray NUL must cost one
 *       character, not the pipeline.</li>
 * </ul>
 *
 * The legacy writer's rule for an EMPTY string is kept so that both engines hold the same rows: null, {@code ""}
 * and the text {@code null} (any case) are written as SQL NULL.
 *
 * <p>A date is the literal of its wall clock ({@code 'yyyy-MM-dd HH:mm:ss'}) for a {@code timestamp} WITHOUT time
 * zone column: no zone — the JVM's, the session's or the server's — ever converts it.
 */
public final class PostgresLiterals {
    private PostgresLiterals() {}

    private static final DateTimeFormatter WallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The literal of any value a row's field holds, by its type. */
    public static String Of(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return Text(s);
        if (value instanceof BigDecimal d) return d.toPlainString();        // never 1E-8: see MySqlFieldExtensions
        if (value instanceof LocalDateTime t) return "'" + t.format(WallClock) + "'";
        if (value instanceof Float f) return Real(f.doubleValue(), f.toString());
        if (value instanceof Double d) return Real(d, d.toString());
        return value.toString();                                            // Integer, Long, Short, Byte
    }

    /** A string literal: the quote doubled, a NUL dropped, a backslash left alone. Empty = SQL NULL (see the class note). */
    public static String Text(String value) {
        if (value == null) return "null";
        String text = value.indexOf('\u0000') >= 0 ? value.replace("\u0000", "") : value;
        if (text.isEmpty() || text.equalsIgnoreCase("null")) return "null";
        return "'" + text.replace("'", "''") + "'";
    }

    /** A float: its digits, or the quoted word PostgreSQL knows for what has none. */
    private static String Real(double value, String digits) {
        if (Double.isNaN(value)) return "'NaN'";
        if (Double.isInfinite(value)) return value > 0 ? "'Infinity'" : "'-Infinity'";
        return digits;
    }
}
