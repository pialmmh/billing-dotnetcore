// Ported VERBATIM from legacy LibraryExtensions.DateRange (OtherExtensions.cs) — the day key of the
// RateCache's DateRangeWiseRateDic. Value-equality on (StartDate, EndDate) + the EqualityComparer the
// dictionary uses.
#nullable disable
using System;
using System.Collections.Generic;

namespace LibraryExtensions
{
    public class DateRange : IEquatable<DateRange>
    {
        public DateTime StartDate = new DateTime(1, 1, 1);
        public DateTime EndDate = new DateTime(1, 1, 1);

        public DateRange() { }

        public DateRange(DateTime startDateTime, DateTime endDateTime)
        {
            this.StartDate = startDateTime;
            this.EndDate = endDateTime;
        }

        public override string ToString() =>
            this.StartDate.ToString("yyyy-MM-dd HH:mm:ss") + " to " + this.EndDate.ToString("yyyy-MM-dd HH:mm:ss");

        public List<DateTime> GetInvolvedHours()
        {
            var hours = new List<DateTime>();
            var current = new DateTime(StartDate.Year, StartDate.Month, StartDate.Day, StartDate.Hour, 0, 0);
            while (current <= EndDate)
            {
                hours.Add(current);
                current = current.AddHours(1);
            }
            return hours;
        }

        public bool WithinRange(DateTime dateTime) => dateTime >= this.StartDate && dateTime < this.EndDate;

        public DateRange NewRangeByAddingDays(int daysToAdd) =>
            new() { StartDate = this.StartDate.AddDays(daysToAdd), EndDate = this.EndDate.AddDays(daysToAdd) };

        public override int GetHashCode()
        {
            unchecked
            {
                int hash = 17;
                hash = hash * 29 + this.StartDate.GetHashCode();
                hash = hash * 29 + this.EndDate.GetHashCode();
                return hash;
            }
        }

        public override bool Equals(object obj) => Equals(obj as DateRange);

        public bool Equals(DateRange obj) =>
            obj != null && obj.StartDate == this.StartDate && obj.EndDate == this.EndDate;

        public class EqualityComparer : IEqualityComparer<DateRange>
        {
            public bool Equals(DateRange x, DateRange y) =>
                x != null && y != null && x.StartDate == y.StartDate && x.EndDate == y.EndDate;

            public int GetHashCode(DateRange x)
            {
                unchecked
                {
                    int hash = 17;
                    hash = hash * 29 + x.StartDate.GetHashCode();
                    hash = hash * 29 + x.EndDate.GetHashCode();
                    return hash;
                }
            }
        }
    }
}
