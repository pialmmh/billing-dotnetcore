namespace Billing.Mediation.ServiceGroups;

/// <summary>
/// Normalizes a Bangladesh dialed/calling number to its national-significant form for prefix matching.
/// This is the shared normalization both legacy SgDomOffnet* Execute methods ran inline (SG10 on the
/// terminating/called number, SG11 on the originating/calling number): strip a leading '+', then a
/// leading trunk/international prefix — a single '0' (with an optional '0880' that together spell the
/// '00880' IDD form), or the '880880' teletalk quirk, or a plain '880' country code.
/// </summary>
public static class BdNumberNormalizer
{
    public static string Normalize(string? number)
    {
        if (string.IsNullOrWhiteSpace(number)) return number ?? string.Empty;

        var n = number;

        // Strip a leading '+'. (The legacy SG10 branch indexed by OriginatingCallingNumber.Length here —
        // a latent bug in a rarely-hit path; we just drop the '+', as the correct SG11 branch did.)
        if (n.StartsWith("+", StringComparison.Ordinal))
            n = n.Substring(1);

        if (n.Length == 0) return n;

        if (n[0] == '0')
        {
            n = n.Substring(1);                                     // drop the leading 0
            if (n.Length >= 4 && n.Substring(0, 4) == "0880")       // the remaining '0880' of a '00880' IDD form
                n = n.Substring(4);
        }
        else if (n.Length >= 3)
        {
            if (n.Length >= 6 && n.Substring(0, 6) == "880880")     // teletalk double-880 quirk
                n = n.Substring(6);
            else if (n.Substring(0, 3) == "880")                    // plain 880 country code
                n = n.Substring(3);
        }

        return n;
    }
}
