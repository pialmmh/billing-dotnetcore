// Ported from legacy LibraryExtensions (ValueToSqlFieldConverter / ValueToNotNullSqlFieldConverter /
// MySqlFieldToString / OtherExtensions.EncloseWith / NullAlternative.ReplaceNullWith). Just the SQL field
// formatters the cdr-summary writers use: numbers as-is, dates/strings quoted, null/empty string -> ''.
#nullable disable
using System;

namespace LibraryExtensions
{
    public static class MySqlFieldExtensions
    {
        private const string MySqlDateFormat = "yyyy-MM-dd HH:mm:ss";

        public static string EncloseWith(this string text, string encloseWith) => encloseWith + text + encloseWith;

        public static string ReplaceNullWith(this string val, string nullAlternate) =>
            string.IsNullOrEmpty(val) ? nullAlternate : val;

        public static string ToMySqlField(this long val) => val.ToString();
        public static string ToMySqlField(this int val) => val.ToString();
        public static string ToMySqlField(this decimal val) => val.ToString();
        public static string ToMySqlField(this DateTime val) => val.ToString(MySqlDateFormat).EncloseWith("'");

        // Nullable + string overloads (legacy ToSqlField): null -> unquoted SQL null; non-null value -> its
        // string; a non-null string -> quoted (the legacy "null"-string quirk is preserved).
        public static string ToMySqlField(this string val)
        {
            var str = val.ReplaceNullWith("null");
            return string.Equals(str, "null", StringComparison.InvariantCultureIgnoreCase) ? str : str.EncloseWith("'");
        }
        public static string ToMySqlField(this long? val) => val.HasValue ? val.Value.ToString() : "null";
        public static string ToMySqlField(this int? val) => val.HasValue ? val.Value.ToString() : "null";
        public static string ToMySqlField(this decimal? val) => val.HasValue ? val.Value.ToString() : "null";
        public static string ToMySqlField(this sbyte? val) => val.HasValue ? val.Value.ToString() : "null";
        public static string ToMySqlField(this DateTime? val) =>
            val.HasValue ? val.Value.ToString(MySqlDateFormat).EncloseWith("'") : "null";

        /// <summary>Non-null string -> quoted; null/empty -> '' (legacy ToNotNullSqlField).</summary>
        public static string ToNotNullSqlField(this string val) => val.ReplaceNullWith("").EncloseWith("'");
    }
}
