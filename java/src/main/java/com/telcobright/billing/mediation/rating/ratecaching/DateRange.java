// Ported VERBATIM from legacy LibraryExtensions.DateRange (OtherExtensions.cs) — the day key of the
// RateCache's DateRangeWiseRateDic. Value-equality on (StartDate, EndDate) + the EqualityComparer the
// dictionary uses.
package com.telcobright.billing.mediation.rating.ratecaching;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DateRange {
    public LocalDateTime StartDate = LocalDateTime.of(1, 1, 1, 0, 0);
    public LocalDateTime EndDate = LocalDateTime.of(1, 1, 1, 0, 0);

    private static final DateTimeFormatter Fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DateRange() { }

    public DateRange(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        this.StartDate = startDateTime;
        this.EndDate = endDateTime;
    }

    @Override
    public String toString() {
        return this.StartDate.format(Fmt) + " to " + this.EndDate.format(Fmt);
    }

    public List<LocalDateTime> GetInvolvedHours() {
        var hours = new ArrayList<LocalDateTime>();
        var current = LocalDateTime.of(StartDate.getYear(), StartDate.getMonthValue(), StartDate.getDayOfMonth(), StartDate.getHour(), 0, 0);
        while (!current.isAfter(EndDate)) {
            hours.add(current);
            current = current.plusHours(1);
        }
        return hours;
    }

    public boolean WithinRange(LocalDateTime dateTime) {
        return !dateTime.isBefore(this.StartDate) && dateTime.isBefore(this.EndDate);
    }

    public DateRange NewRangeByAddingDays(int daysToAdd) {
        DateRange r = new DateRange();
        r.StartDate = this.StartDate.plusDays(daysToAdd);
        r.EndDate = this.EndDate.plusDays(daysToAdd);
        return r;
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = hash * 29 + this.StartDate.hashCode();
        hash = hash * 29 + this.EndDate.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        return equals(obj instanceof DateRange ? (DateRange) obj : null);
    }

    public boolean equals(DateRange obj) {
        return obj != null && Objects.equals(obj.StartDate, this.StartDate) && Objects.equals(obj.EndDate, this.EndDate);
    }

    // Mirrors the legacy IEqualityComparer<DateRange>. Retained for fidelity; Java's HashMap uses the
    // key's own equals()/hashCode() (identical logic), so this is not wired into the cache map directly.
    public static class EqualityComparer {
        public boolean equals(DateRange x, DateRange y) {
            return x != null && y != null && Objects.equals(x.StartDate, y.StartDate) && Objects.equals(x.EndDate, y.EndDate);
        }

        public int getHashCode(DateRange x) {
            int hash = 17;
            hash = hash * 29 + x.StartDate.hashCode();
            hash = hash * 29 + x.EndDate.hashCode();
            return hash;
        }
    }
}
