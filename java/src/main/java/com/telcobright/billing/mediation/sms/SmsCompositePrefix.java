package com.telcobright.billing.mediation.sms;

/**
 * One SMS rate prefix split into its two sides — routesphere's {@code RatePrefixMatcher} format:
 * {@code rate.Prefix = <callingPrefix> 0x1F <calledPrefix>}. Either side may be empty; a prefix with NO 0x1F
 * is a legacy destination-only row (callingPrefix empty), so pre-composite rates keep matching as fallbacks.
 *
 * <p>{@link #Display()} replaces the delimiter with {@code |} for persistence/reporting ({@code BRAND|8801});
 * matching always uses {@link #Raw()}.</p>
 */
public record SmsCompositePrefix(String Raw, String CallingPrefix, String CalledPrefix) {

    /** ASCII Unit Separator joining the calling and called halves (routesphere {@code UNIT_SEPARATOR}). */
    public static final char UnitSeparator = '\u001F';
    public static final char DisplaySeparator = '|';

    public static SmsCompositePrefix Parse(String raw) {
        String r = raw == null ? "" : raw;
        int sep = r.indexOf(UnitSeparator);
        return sep < 0
                ? new SmsCompositePrefix(r, "", r)
                : new SmsCompositePrefix(r, r.substring(0, sep), r.substring(sep + 1));
    }

    public boolean IsComposite() {
        return Raw.indexOf(UnitSeparator) >= 0;
    }

    /** Routesphere's match predicate: each NON-empty side must prefix its number (case-sensitive startsWith). */
    public boolean Matches(String calling, String called) {
        String a = calling == null ? "" : calling;
        String b = called == null ? "" : called;
        if (!CallingPrefix.isEmpty() && !a.startsWith(CallingPrefix)) return false;
        return CalledPrefix.isEmpty() || b.startsWith(CalledPrefix);
    }

    public int TotalLength() {
        return CallingPrefix.length() + CalledPrefix.length();
    }

    /** Human-readable form: {@code BRAND|8801}; a destination-only prefix is returned unchanged. */
    public String Display() {
        return ToDisplay(Raw);
    }

    public static String ToDisplay(String raw) {
        return raw == null ? null : raw.replace(UnitSeparator, DisplaySeparator);
    }
}
