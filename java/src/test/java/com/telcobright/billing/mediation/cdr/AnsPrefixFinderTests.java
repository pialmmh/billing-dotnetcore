package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.cdr;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AnsPrefixFinder — longest-prefix match of a number against the partnerprefix dict (prefix -> idPartner),
 * stamping the ANS operator onto the cdr. Mirrors the res_261 reseller example: 09646→7, 019 (Banglalink)→25.
 */
class AnsPrefixFinderTests {

    private static final Map<String, Integer> ANS = Map.of(
            "0", 99, "09", 98, "09646", 7, "019", 25, "01911", 26);

    @Test
    void Terminating_longest_prefix_wins() {
        cdr c = new cdr();
        AnsPrefixFinder.FindTerminatingAnsPrefix(c, ANS, "09646991946");
        assertEquals("09646", c.AnsPrefixTerm);
        assertEquals(7, c.AnsIdTerm);   // 09646 (len 5) beats 09 (len 2) and 0 (len 1)
    }

    @Test
    void Originating_banglalink_019() {
        cdr c = new cdr();
        AnsPrefixFinder.FindOriginatingAnsPrefix(c, ANS, "01912020024");
        assertEquals("019", c.AnsPrefixOrig);
        assertEquals(25, c.AnsIdOrig);   // 019 beats 0 (01911 does not match: number is 019120..., not 01911...)
    }

    @Test
    void No_match_leaves_nulls() {
        cdr c = new cdr();
        AnsPrefixFinder.FindTerminatingAnsPrefix(c, ANS, "55500000");
        assertNull(c.AnsIdTerm);
        assertNull(c.AnsPrefixTerm);
    }

    @Test
    void Empty_dict_or_number_is_safe() {
        cdr c = new cdr();
        AnsPrefixFinder.FindTerminatingAnsPrefix(c, Map.of(), "09646991946");
        AnsPrefixFinder.FindOriginatingAnsPrefix(c, ANS, "");
        AnsPrefixFinder.FindOriginatingAnsPrefix(c, ANS, null);
        assertNull(c.AnsIdTerm);
        assertNull(c.AnsIdOrig);
    }
}
