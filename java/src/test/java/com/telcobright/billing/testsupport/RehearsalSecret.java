package com.telcobright.billing.testsupport;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Where a by-name rehearsal against a real box gets its password: from the ENVIRONMENT, by the NAME of a variable.
 * A system property gives the name, never the value:
 *
 * <pre>
 *   read -rs REHEARSAL_PW &amp;&amp; export REHEARSAL_PW          # the password: typed once, in no history, on no line
 *   mvn … -Drehearsal.pw-env=REHEARSAL_PW
 * </pre>
 *
 * A password on a command line is in the process list of the box and in the shell's history. So the old property,
 * {@code -Drehearsal.pw=…}, is REFUSED when someone still passes it — in words, and without reading or printing what
 * it holds. No message of this class carries a value: only the variable's name, and only when it looks like one.
 */
public final class RehearsalSecret {
    private RehearsalSecret() {}

    /** The property that names the environment variable. */
    public static final String NameProperty = "rehearsal.pw-env";
    /** The old property, which carried the password itself. Refused. */
    public static final String RefusedProperty = "rehearsal.pw";

    private static final Pattern AVariableName = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The rehearsal's password, from this process's own environment. */
    public static String Password() {
        return Password(System::getProperty, System::getenv);
    }

    /** Testable: {@code property} and {@code environment} answer a value, or null. */
    static String Password(UnaryOperator<String> property, UnaryOperator<String> environment) {
        RefuseAPasswordOnTheCommandLine(property);
        return FromTheEnvironment(TheVariableNamedBy(property), environment);
    }

    private static void RefuseAPasswordOnTheCommandLine(UnaryOperator<String> property) {
        if (property.apply(RefusedProperty) == null) return;
        throw new IllegalStateException("-D" + RefusedProperty + " is REFUSED: a password is never on a command line (it is in"
                + " the process list and in the shell's history). What it holds was not used and is not printed. Put the"
                + " password in the environment and name its variable: -D" + NameProperty + "=<VARIABLE NAME>. If the"
                + " password was typed on this line, change it.");
    }

    private static String TheVariableNamedBy(UnaryOperator<String> property) {
        String name = property.apply(NameProperty);
        if (name == null || name.isBlank())
            throw new IllegalStateException("the rehearsal's password is read from the environment: name its variable with -D"
                    + NameProperty + "=<VARIABLE NAME> (the password itself is never a property)");
        if (!AVariableName.matcher(name.trim()).matches())
            throw new IllegalStateException("-D" + NameProperty + " must be the NAME of an environment variable (letters,"
                    + " digits, underscore). What was given is not one, and is not printed: it may be the password itself");
        return name.trim();
    }

    private static String FromTheEnvironment(String variable, UnaryOperator<String> environment) {
        String value = environment.apply(variable);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("the environment variable " + variable + " (named by -D" + NameProperty
                    + ") is not set: the rehearsal has no password. There is no fallback");
        return value;
    }
}
