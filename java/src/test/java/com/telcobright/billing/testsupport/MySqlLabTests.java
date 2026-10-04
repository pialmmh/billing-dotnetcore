package com.telcobright.billing.testsupport;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W16 — the lab MySQL's password is in no source: the MySQL integration tests read it from the environment variable
 * {@code BC_LAB_MYSQL_PASSWORD}, and without it they are skipped. There is no default to fall back to.
 */
class MySqlLabTests {

    @Test
    void the_password_is_what_the_environment_variable_holds() {
        assertEquals("from-the-environment", MySqlLab.PasswordFrom(Map.of("BC_LAB_MYSQL_PASSWORD", "from-the-environment")::get));
    }

    @Test
    void without_the_variable_there_is_no_password_and_no_default() {
        assertNull(MySqlLab.PasswordFrom(Map.<String, String>of()::get), "absent: the tests skip");
        assertNull(MySqlLab.PasswordFrom(Map.of("BC_LAB_MYSQL_PASSWORD", "")::get), "empty counts as absent");
        assertNull(MySqlLab.PasswordFrom(Map.of("MYSQL_PWD", "another-variable")::get), "no other variable is read");
    }
}
