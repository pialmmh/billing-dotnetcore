// Ported from legacy LibraryExtensions (ValueToSqlFieldConverter / ValueToNotNullSqlFieldConverter /
// MySqlFieldToString / OtherExtensions.EncloseWith / NullAlternative.ReplaceNullWith). Just the SQL field
// formatters the cdr-summary writers use: numbers as-is, dates/strings quoted, null/empty string -> ''.
//
// C# extension methods (`this Foo f`) become plain static methods: callers must use
// MySqlFieldExtensions.method(receiver, ...) instead of receiver.method(...).
//
// FAITHFUL-PORT NOTE: C# distinguished `decimal` from `decimal?` and `DateTime` from `DateTime?` as
// separate overloads. Both members of each pair map to the SAME Java type (BigDecimal / LocalDateTime,
// which are reference types and may be null), so the two overloads in each pair are MERGED into one
// null-aware method. The merged method returns "null" for a null arg (the C# nullable behaviour) and the
// formatted value otherwise (the C# non-null behaviour) — identical for every input either C# overload
// could actually receive.
package com.telcobright.billing.mediation.summary.cache;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class MySqlFieldExtensions {
    private MySqlFieldExtensions() {}

    private static final DateTimeFormatter MySqlDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String EncloseWith(String text, String encloseWith) {
        return encloseWith + text + encloseWith;
    }

    public static String ReplaceNullWith(String val, String nullAlternate) {
        return (val == null || val.isEmpty()) ? nullAlternate : val;
    }

    public static String ToMySqlField(long val) {
        return Long.toString(val);
    }

    public static String ToMySqlField(int val) {
        return Integer.toString(val);
    }

    // merged from C# `ToMySqlField(this decimal)` + `ToMySqlField(this decimal?)`
    public static String ToMySqlField(BigDecimal val) {
        return val != null ? val.toString() : "null";
    }

    // merged from C# `ToMySqlField(this DateTime)` + `ToMySqlField(this DateTime?)`
    public static String ToMySqlField(LocalDateTime val) {
        return val != null ? EncloseWith(val.format(MySqlDateFormat), "'") : "null";
    }

    // Nullable + string overloads (legacy ToSqlField): null -> unquoted SQL null; non-null value -> its
    // string; a non-null string -> quoted (the legacy "null"-string quirk is preserved).
    public static String ToMySqlField(String val) {
        String str = ReplaceNullWith(val, "null");
        return str.equalsIgnoreCase("null") ? str : EncloseWith(str, "'");
    }

    public static String ToMySqlField(Long val) {
        return val != null ? val.toString() : "null";
    }

    public static String ToMySqlField(Integer val) {
        return val != null ? val.toString() : "null";
    }

    public static String ToMySqlField(Float val) {
        return val != null ? val.toString() : "null";
    }

    public static String ToMySqlField(Byte val) {
        return val != null ? val.toString() : "null";
    }

    /** Non-null string -&gt; quoted; null/empty -&gt; '' (legacy ToNotNullSqlField). */
    public static String ToNotNullSqlField(String val) {
        return EncloseWith(ReplaceNullWith(val, ""), "'");
    }
}
