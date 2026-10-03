package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.CdrRowSql;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.SqlDialect;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a {@code cdr}/{@code cdrerror} row back into a {@link cdr} object — the reverse of {@code CdrWriter}.
 * Reflection-based: the {@link cdr#ExtInsertColumns} names mirror the public field names 1:1, so each column
 * is read into its like-named field by the field's declared type. Columns with no matching field (e.g.
 * {@code SignalingStartTime}) are skipped in BOTH the SELECT list and the mapping, so they round-trip as null.
 * Used only by the error-reprocess path (re-rate a cdrerror row through the normal pipeline).
 */
public final class CdrRowMapper {
    private CdrRowMapper() {}

    // column name -> field, only for columns that HAVE a like-named public field. Built once per engine: the
    // PostgreSQL table also has the ratified wire's six columns, and a row read back must keep them.
    private static final Map<String, Field> FIELDS = FieldsOf(SqlDialect.MySql);
    private static final Map<String, Field> POSTGRES_FIELDS = FieldsOf(SqlDialect.PostgreSql);

    private static Map<String, Field> FieldsOf(SqlDialect dialect) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String raw : CdrRowSql.Columns(dialect).split(",")) {
            String col = raw.trim();
            try {
                fields.put(col, cdr.class.getField(col));
            } catch (NoSuchFieldException ignored) {
                // a header column with no engine field — not read/written, skip.
            }
        }
        return fields;
    }

    private static Map<String, Field> Fields(SqlDialect dialect) {
        return dialect == SqlDialect.PostgreSql ? POSTGRES_FIELDS : FIELDS;
    }

    /** The comma-separated column list to SELECT (only the columns we can map back). */
    public static String SelectColumns() {
        return SelectColumns(SqlDialect.MySql);
    }

    public static String SelectColumns(SqlDialect dialect) {
        return String.join(",", Fields(dialect).keySet());
    }

    /** Map the CURRENT row of {@code rs} (selected via {@link #SelectColumns()}) into a fresh cdr. */
    public static cdr FromResultSet(ResultSet rs) throws SQLException {
        return FromResultSet(rs, SqlDialect.MySql);
    }

    public static cdr FromResultSet(ResultSet rs, SqlDialect dialect) throws SQLException {
        cdr c = new cdr();
        for (Map.Entry<String, Field> e : Fields(dialect).entrySet()) {
            Object v = ReadValue(rs, e.getKey(), e.getValue().getType(), dialect);
            try {
                e.getValue().set(c, v);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException("cdr mapper: cannot set field " + e.getKey(), ex);
            }
        }
        return c;
    }

    private static Object ReadValue(ResultSet rs, String col, Class<?> type, SqlDialect dialect) throws SQLException {
        // PostgreSQL: the column is a timestamp WITHOUT time zone and is read as the wall clock it holds — no zone,
        // not even the JVM's, takes part (a java.sql.Timestamp would go through the JVM's zone and back).
        if (type == LocalDateTime.class && dialect == SqlDialect.PostgreSql) return rs.getObject(col, LocalDateTime.class);
        if (type == String.class)        return rs.getString(col);
        if (type == BigDecimal.class)    return rs.getBigDecimal(col);
        if (type == LocalDateTime.class) { Timestamp t = rs.getTimestamp(col); return t == null ? null : t.toLocalDateTime(); }
        if (type == Integer.class)       { int v = rs.getInt(col);   return rs.wasNull() ? null : v; }
        if (type == Long.class)          { long v = rs.getLong(col);  return rs.wasNull() ? null : v; }
        if (type == Float.class)         { float v = rs.getFloat(col); return rs.wasNull() ? null : v; }
        if (type == Double.class)        { double v = rs.getDouble(col); return rs.wasNull() ? null : v; }
        if (type == Short.class)         { short v = rs.getShort(col); return rs.wasNull() ? null : v; }
        if (type == Byte.class)          { byte v = rs.getByte(col);  return rs.wasNull() ? null : v; }
        if (type == Boolean.class)       { boolean v = rs.getBoolean(col); return rs.wasNull() ? null : v; }
        if (type == int.class)           return rs.getInt(col);
        if (type == long.class)          return rs.getLong(col);
        if (type == float.class)         return rs.getFloat(col);
        if (type == double.class)        return rs.getDouble(col);
        if (type == short.class)         return rs.getShort(col);
        if (type == byte.class)          return rs.getByte(col);
        if (type == boolean.class)       return rs.getBoolean(col);
        throw new IllegalStateException("cdr mapper: unhandled field type " + type + " for column " + col);
    }
}
