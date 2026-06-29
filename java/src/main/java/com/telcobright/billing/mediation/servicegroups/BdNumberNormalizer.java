package com.telcobright.billing.mediation.servicegroups;

/**
 * Normalizes a Bangladesh dialed/calling number to its national-significant form for prefix matching.
 * This is the shared normalization both legacy SgDomOffnet* Execute methods ran inline (SG10 on the
 * terminating/called number, SG11 on the originating/calling number): strip a leading '+', then a
 * leading trunk/international prefix — a single '0' (with an optional '0880' that together spell the
 * '00880' IDD form), or the '880880' teletalk quirk, or a plain '880' country code.
 */
public final class BdNumberNormalizer {

    private BdNumberNormalizer() {}

    public static String Normalize(String number) {
        if (number == null || number.isBlank()) return number != null ? number : "";

        var n = number;

        // Strip a leading '+'. (The legacy SG10 branch indexed by OriginatingCallingNumber.Length here —
        // a latent bug in a rarely-hit path; we just drop the '+', as the correct SG11 branch did.)
        if (n.startsWith("+"))
            n = n.substring(1);

        if (n.length() == 0) return n;

        if (n.charAt(0) == '0') {
            n = n.substring(1);                                          // drop the leading 0
            if (n.length() >= 4 && n.substring(0, 4).equals("0880"))     // the remaining '0880' of a '00880' IDD form
                n = n.substring(4);
        } else if (n.length() >= 3) {
            if (n.length() >= 6 && n.substring(0, 6).equals("880880"))   // teletalk double-880 quirk
                n = n.substring(6);
            else if (n.substring(0, 3).equals("880"))                    // plain 880 country code
                n = n.substring(3);
        }

        return n;
    }
}
