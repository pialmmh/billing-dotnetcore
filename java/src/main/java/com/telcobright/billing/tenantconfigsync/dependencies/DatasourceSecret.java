package com.telcobright.billing.tenantconfigsync.dependencies;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the datasource's password comes from (brief B10; the owner's secreteer rule): on the wifi bed and wherever a
 * profile says so, from the UNIT'S ENVIRONMENT — never from the YAML. The profile names the variable,
 * {@code billing.datasource.password-ref: env:<VAR>}; the value lives in {@code /etc/secreteer/<tenant>/<app>.env},
 * loaded by the unit's {@code EnvironmentFile=}. billing-core reads its environment and nothing else: no secret
 * store is called.
 *
 * <ul>
 *   <li>{@code password-ref: env:NAME} — the password is the value of {@code NAME}. Not set, or empty = the service
 *       REFUSES TO START and names the variable. There is no fallback to anything.</li>
 *   <li>{@code password: …} — the value inline, for the deployments that still have it there. Unchanged.</li>
 *   <li>both — refused: two sources of one secret is how the wrong one gets used.</li>
 * </ul>
 * The value is never logged and never part of a message: only the variable's NAME is.
 */
public final class DatasourceSecret {
    private DatasourceSecret() {}

    private static final Pattern EnvRef = Pattern.compile("env:([A-Za-z_][A-Za-z0-9_]*)");

    /** The datasource's password of this profile, from the process's own environment. */
    public static String PasswordOf(DatasourceOptions ds) {
        return PasswordOf(ds, System::getenv);
    }

    /** Testable: {@code environment} answers a variable's value, or null. */
    public static String PasswordOf(DatasourceOptions ds, Function<String, String> environment) {
        boolean hasRef = ds.PasswordRef != null && !ds.PasswordRef.isBlank();
        boolean hasInline = ds.Password != null && !ds.Password.isEmpty();
        if (!hasRef) return ds.Password;
        if (hasInline)
            throw new IllegalStateException("billing.datasource names its password twice: 'password' and 'password-ref' ("
                    + ds.PasswordRef + "). Keep one — on a deployment that reads its secrets from the environment, 'password-ref'.");
        return FromTheEnvironment(VariableNamedBy(ds.PasswordRef), environment);
    }

    private static String VariableNamedBy(String ref) {
        Matcher m = EnvRef.matcher(ref.trim());
        if (!m.matches())
            throw new IllegalStateException("billing.datasource.password-ref must be 'env:<VARIABLE NAME>'; '" + ref
                    + "' is not (a secret is read from the unit's environment by its variable's name, nowhere else)");
        return m.group(1);
    }

    private static String FromTheEnvironment(String variable, Function<String, String> environment) {
        String value = environment.apply(variable);
        if (value == null || value.isEmpty())
            throw new IllegalStateException("REFUSING TO START: the datasource password is not in the environment — the variable "
                    + variable + " (billing.datasource.password-ref) is not set. It comes from the unit's EnvironmentFile"
                    + " (secreteer); there is no fallback.");
        return value;
    }
}
