package com.telcobright.billing.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.UnaryOperator;

/**
 * The local MySQL the MySQL integration tests run against ({@code CdrBatchAtomicityTests}, {@code CdrWriterTests},
 * {@code ChargeableWriterMySqlIntegrationTests}): a developer's own server on 127.0.0.1:3306.
 *
 * <p><b>Its password is in no source and no document of this repository.</b> It is read from the environment
 * variable {@value #PasswordVariable}. Put it in the shell's environment — never on a command line, never in a file
 * that is committed:
 *
 * <pre>
 *   read -rs BC_LAB_MYSQL_PASSWORD &amp;&amp; export BC_LAB_MYSQL_PASSWORD
 *   mvn -f java/pom.xml test
 * </pre>
 *
 * Without the variable those tests are SKIPPED — never passed, never failed — exactly as they already were when no
 * MySQL answered. There is no default value.
 */
public final class MySqlLab {
    private MySqlLab() {}

    public static final String PasswordVariable = "BC_LAB_MYSQL_PASSWORD";

    /** A connection to the lab's MySQL, or null: the variable is not set, or nothing answered (the test then skips). */
    public static Connection OpenOrNull(String url, String user) {
        String password = PasswordFrom(System::getenv);
        if (password == null) return null;
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException unreachable) {
            return null;
        }
    }

    /** The password out of the environment; null when the variable is absent or empty. There is no fallback. */
    static String PasswordFrom(UnaryOperator<String> environment) {
        String value = environment.apply(PasswordVariable);
        return value == null || value.isEmpty() ? null : value;
    }

    /** The words of the skip: which of the two it was. */
    public static String WhySkipped() {
        return PasswordFrom(System::getenv) == null
                ? "no " + PasswordVariable + " in the environment: the MySQL lab tests are skipped, never passed"
                : "local MySQL not reachable (127.0.0.1:3306, the password of " + PasswordVariable + ") — skipping";
    }
}
