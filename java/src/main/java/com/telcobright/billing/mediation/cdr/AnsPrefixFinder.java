package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.cdr;

import java.util.Map;

/**
 * Port of legacy {@code TelcobrightMediation.Cdr.AnsPrefixFinder}. Longest-prefix-matches a phone number against
 * the tenant's {@code partnerprefix} table ({@code prefix -> idPartner}, served as {@code prefixWisePartnerPrefixes})
 * to identify the number's Access Network Service operator, stamping the ANS prefix + partner id onto the cdr.
 *
 * <p>Legacy runs this in each service group's detection: SG10 stamps the TERMINATING (called) operator, SG11/SG15
 * the ORIGINATING (calling) operator. The Kafka ingest path already carries these off the envelope; the finalize
 * (multi-tier/reseller) path does not, so the rater runs this as a fallback when the field is unset.
 */
public final class AnsPrefixFinder {
    private AnsPrefixFinder() {}

    /** Stamp {@code AnsPrefixTerm}/{@code AnsIdTerm} from the TERMINATING (called) number's longest partnerprefix. */
    public static void FindTerminatingAnsPrefix(cdr cdr, Map<String, Integer> ansPrefixes, String terminatingCalledNumber) {
        Match m = LongestMatch(ansPrefixes, terminatingCalledNumber);
        if (m != null) {
            cdr.AnsPrefixTerm = m.prefix;
            cdr.AnsIdTerm = m.idPartner;
        }
    }

    /** Stamp {@code AnsPrefixOrig}/{@code AnsIdOrig} from the ORIGINATING (calling) number's longest partnerprefix. */
    public static void FindOriginatingAnsPrefix(cdr cdr, Map<String, Integer> ansPrefixes, String originatingCallingNumber) {
        Match m = LongestMatch(ansPrefixes, originatingCallingNumber);
        if (m != null) {
            cdr.AnsPrefixOrig = m.prefix;
            cdr.AnsIdOrig = m.idPartner;
        }
    }

    // legacy: grow the prefix one digit at a time, keep the LONGEST that is a key in the partnerprefix dict.
    private static Match LongestMatch(Map<String, Integer> ansPrefixes, String number) {
        if (number == null || number.isEmpty() || ansPrefixes == null || ansPrefixes.isEmpty()) return null;
        String bestPrefix = null;
        Integer bestId = null;
        int n = number.length();
        for (int i = 1; i <= n; i++) {
            Integer id = ansPrefixes.get(number.substring(0, i));
            if (id != null) {
                bestPrefix = number.substring(0, i);
                bestId = id;
            }
        }
        return bestPrefix != null ? new Match(bestPrefix, bestId) : null;
    }

    private record Match(String prefix, Integer idPartner) {}
}
