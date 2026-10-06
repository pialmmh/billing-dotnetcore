package com.telcobright.billing.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jsonbillingrule parsing: the JsonExpression is authoritative (legacy deserialized it); the column must agree. */
class MySqlSmsAccountingStoreTests {
    // the live rule 2 / rule 1 expressions, trimmed to the fields that matter
    private static final String Rule2 = "{\"Id\":2,\"RuleName\":\"OnFirstDayOfEachMonth,ForPreviousMonth\",\"IsPrepaid\":false,\"InvoiceOverdueInDay\":30}";
    private static final String Rule1 = "{\"Id\":1,\"RuleName\":\"Prepaid\",\"IsPrepaid\":true}";

    @Test
    void rule_2_is_postpaid_from_its_json() {
        var r = MySqlSmsAccountingStore.ParseRule(2, "x", Rule2, false);
        assertFalse(r.IsPrepaid());
    }

    @Test
    void rule_1_is_prepaid_from_its_json() {
        assertTrue(MySqlSmsAccountingStore.ParseRule(1, "Prepaid", Rule1, true).IsPrepaid());
        assertTrue(MySqlSmsAccountingStore.ParseRule(1, "Prepaid", Rule1, null).IsPrepaid(), "column absent: json decides");
    }

    @Test
    void json_and_column_disagreeing_leaves_the_rule_out() {
        assertNull(MySqlSmsAccountingStore.ParseRule(2, "x", Rule2, true));
    }

    @Test
    void unreadable_json_leaves_the_rule_out() {
        assertNull(MySqlSmsAccountingStore.ParseRule(2, "x", "{not json", false));
    }

    @Test
    void no_prepaid_information_at_all_leaves_the_rule_out() {
        assertNull(MySqlSmsAccountingStore.ParseRule(3, "x", "{\"Id\":3}", null));
    }

    @Test
    void column_only_is_used_when_the_json_has_no_flag() {
        assertFalse(MySqlSmsAccountingStore.ParseRule(3, "x", "{\"Id\":3}", false).IsPrepaid());
    }
}
