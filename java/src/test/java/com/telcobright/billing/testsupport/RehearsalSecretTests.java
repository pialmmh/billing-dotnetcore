package com.telcobright.billing.testsupport;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BC-0004 F6 — a rehearsal against a real box (`LegacyDedupProdRehearsal`) took a production password as
 * {@code -Drehearsal.pw=…}: on a command line. It reads it from the environment now, by the NAME of a variable; the
 * old property is refused in words, and nothing this class says carries a value.
 */
class RehearsalSecretTests {
    private static final String TheSecret = "pr0d-s3cr3t-never-in-a-message";

    private static String PasswordWith(Map<String, String> properties, Map<String, String> environment) {
        return RehearsalSecret.Password(properties::get, environment::get);
    }

    private static String RefusalWith(Map<String, String> properties, Map<String, String> environment) {
        return assertThrows(IllegalStateException.class, () -> PasswordWith(properties, environment)).getMessage();
    }

    @Test
    void the_password_is_the_value_of_the_variable_the_property_names() {
        assertEquals(TheSecret, PasswordWith(Map.of("rehearsal.pw-env", "REHEARSAL_PW"), Map.of("REHEARSAL_PW", TheSecret)));
    }

    @Test
    void the_old_property_is_refused_in_words_and_what_it_holds_is_not_printed() {
        String refusal = RefusalWith(Map.of("rehearsal.pw", TheSecret), Map.of());

        assertTrue(refusal.startsWith("-Drehearsal.pw is REFUSED: a password is never on a command line"), refusal);
        assertTrue(refusal.contains("-Drehearsal.pw-env=<VARIABLE NAME>"), "it says what to do instead: " + refusal);
        assertFalse(refusal.contains(TheSecret), "the value is not printed");
    }

    @Test
    void the_old_property_is_refused_even_beside_the_new_one_and_even_when_it_is_empty() {
        assertTrue(RefusalWith(Map.of("rehearsal.pw", TheSecret, "rehearsal.pw-env", "REHEARSAL_PW"), Map.of("REHEARSAL_PW", TheSecret))
                .startsWith("-Drehearsal.pw is REFUSED"), "a password on the line is refused whatever else is there");
        assertTrue(RefusalWith(Map.of("rehearsal.pw", ""), Map.of()).startsWith("-Drehearsal.pw is REFUSED"));
    }

    @Test
    void a_rehearsal_that_names_no_variable_is_refused_and_told_how() {
        String refusal = RefusalWith(Map.of(), Map.of("REHEARSAL_PW", TheSecret));

        assertTrue(refusal.contains("name its variable with -Drehearsal.pw-env=<VARIABLE NAME>"), refusal);
        assertFalse(refusal.contains(TheSecret));
    }

    @Test
    void a_variable_that_is_not_set_is_refused_by_its_name_and_there_is_no_fallback() {
        String refusal = RefusalWith(Map.of("rehearsal.pw-env", "REHEARSAL_PW"), Map.of("ANOTHER_VARIABLE", TheSecret));

        assertEquals("the environment variable REHEARSAL_PW (named by -Drehearsal.pw-env) is not set: the rehearsal has no"
                + " password. There is no fallback", refusal);
        assertTrue(RefusalWith(Map.of("rehearsal.pw-env", "REHEARSAL_PW"), Map.of("REHEARSAL_PW", "")).contains("is not set"),
                "an empty variable is no password");
    }

    @Test
    void a_password_given_where_the_name_belongs_is_refused_and_not_printed() {
        for (String notAName : new String[] {TheSecret, "has space", "9STARTS_WITH_A_DIGIT", "A=B"}) {
            String refusal = RefusalWith(Map.of("rehearsal.pw-env", notAName), Map.of());

            assertTrue(refusal.contains("must be the NAME of an environment variable"), refusal);
            assertFalse(refusal.contains(notAName), "what was given is not printed: " + refusal);
        }
    }
}
