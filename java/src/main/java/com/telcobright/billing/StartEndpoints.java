package com.telcobright.billing;

import com.telcobright.billing.tenantconfigsync.dependencies.CdrIngestOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.DatasourceOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import org.eclipse.microprofile.config.Config;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The addresses this process is about to dial — SAID before the first of them is dialed and, on a lab start, CHECKED.
 *
 * <p><b>Why.</b> The jar carries the tenant registry and the profiles of real deployments ({@code ccl78}). A start
 * that does not find the configuration it was meant to run with falls back to them: it would fetch a tree from, read
 * a topic of and write into a database of a box that runs today. So:
 * <ul>
 *   <li><b>every start</b> logs, before anything else, where the active profile was read from and one line per
 *       endpoint — what it is, the address, the profile key that named it;</li>
 *   <li><b>a lab start</b> sets {@code billing.lab.local-only=true} (the lab launcher passes it as {@code -D}, so it
 *       holds also when the run directory's configuration was not found). The start is then REFUSED unless every
 *       endpoint's host is this box: the word {@code localhost}, a loopback address, or an address one of this
 *       box's own interfaces holds (a lab's bridge). A host NAME is never looked up — the lookup itself would
 *       leave the box — and is refused.</li>
 * </ul>
 * <b>Nothing is dialed first, by construction.</b> The four blocks of the profile that hold an address reach the
 * rest of the application only through this object: {@code BillingConfig} makes it — reads the blocks, then
 * {@link #SaidAndJudged} — and its four option producers hand the blocks out FROM it. No bean can hold an address
 * that was not said first.
 */
public final class StartEndpoints {
    public static final String LocalOnlyKey = "billing.lab.local-only";

    /** One address this process connects to: what it is, the profile key that named it, the address as resolved. */
    public record Endpoint(String What, String Key, String Address) {
    }

    private final TenantConfigSyncOptions sync;
    private final DatasourceOptions datasource;
    private final CdrIngestOptions ingest;
    private final SummaryOutboxOptions summary;

    public StartEndpoints(TenantConfigSyncOptions sync, DatasourceOptions datasource, CdrIngestOptions ingest,
            SummaryOutboxOptions summary) {
        this.sync = sync;
        this.datasource = datasource;
        this.ingest = ingest;
        this.summary = summary;
    }

    public TenantConfigSyncOptions Sync() { return sync; }
    public DatasourceOptions Datasource() { return datasource; }
    public CdrIngestOptions Ingest() { return ingest; }
    public SummaryOutboxOptions Summary() { return summary; }

    /** Is this a lab start ({@code billing.lab.local-only=true}, from a {@code -D}, the environment or a file)? */
    public static boolean IsALabStart(Config config) {
        return config.getOptionalValue(LocalOnlyKey, Boolean.class).orElse(false);
    }

    /** What this process will dial. A block that is switched off dials nothing and is not listed. */
    public List<Endpoint> All() {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("the tenant tree", "billing.config-manager.base-url", sync.ConfigManager.BaseUrl));
        if (sync.ConfigEvents.Enabled)
            endpoints.add(new Endpoint("the doorbell's brokers", "billing.config-events.bootstrap-servers",
                    sync.ConfigEvents.BootstrapServers));
        if (datasource.IsConfigured())
            endpoints.add(new Endpoint("the datasource", "billing.datasource.host", datasource.Kind + "://"
                    + datasource.Host + ":" + datasource.Port + (datasource.IsPostgres() ? "/" + datasource.Database : "")));
        if (ingest.Enabled)
            endpoints.add(new Endpoint("the cdr topic's brokers", "billing.cdr-ingest.bootstrap-servers", ingest.BootstrapServers));
        if (summary.Enabled && summary.BootstrapServers != null && !summary.BootstrapServers.isBlank())
            endpoints.add(new Endpoint("the summary ping's brokers", "billing.summary.bootstrap-servers", summary.BootstrapServers));
        return endpoints;
    }

    /** The first lines of a start: where the profile was read from, then one line for each endpoint. */
    public List<String> Lines(String profileSource) {
        List<String> lines = new ArrayList<>();
        lines.add("endpoints of this start (nothing has been dialed yet) — " + profileSource);
        for (Endpoint e : All()) lines.add("endpoint: " + e.What() + " = " + e.Address() + "  (" + e.Key() + ")");
        return lines;
    }

    /** The one way the application gets its endpoints: SAID, then — on a lab start — JUDGED, in that order (a
     * refused start has its endpoints in the log too). Dials nothing. */
    public static StartEndpoints SaidAndJudged(StartEndpoints resolved, String profileSource, boolean aLabStart,
            Consumer<String> say) {
        resolved.Lines(profileSource).forEach(say);
        if (!aLabStart) return resolved;
        resolved.RefuseWhatIsNotThisBox();
        say.accept(LocalOnlyKey + "=true: every endpoint is on this box — the start goes on");
        return resolved;
    }

    /** A lab start's check: throws, naming every endpoint that is not on this box. Dials nothing, looks up no name. */
    public void RefuseWhatIsNotThisBox() {
        RefuseWhatIsNotThisBox(StartEndpoints::IsThisBox);
    }

    void RefuseWhatIsNotThisBox(Predicate<String> isThisBox) {
        List<String> elsewhere = new ArrayList<>();
        for (Endpoint e : All())
            for (String host : HostsOf(e.Address()))
                if (!isThisBox.test(host)) elsewhere.add(e.What() + " = " + e.Address() + " (" + e.Key() + ": host " + host + ")");
        if (!elsewhere.isEmpty())
            throw new IllegalStateException("REFUSING TO START (" + LocalOnlyKey + "=true): a lab start dials only this box"
                    + " — localhost, a loopback address, or an address one of its own interfaces holds; a host name is"
                    + " never looked up — and these endpoints are elsewhere: " + elsewhere
                    + ". Was the lab's own profile read? The line 'endpoints of this start' says which one was.");
    }

    /** The host(s) of an address: out of a URL ({@code http://h:p}, {@code kind://h:p/db}), out of a broker list
     * {@code h1:p,h2:p}, or the bare host. An IPv6 address comes without its brackets. */
    static List<String> HostsOf(String address) {
        List<String> hosts = new ArrayList<>();
        if (address == null || address.isBlank()) return hosts;
        for (String part : address.split(",")) {
            String one = part.trim();
            if (one.isEmpty()) continue;
            hosts.add(WithoutBrackets(one.contains("://") ? HostOfAUrl(one) : HostOfHostAndPort(one)));
        }
        return hosts;
    }

    private static String HostOfAUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException notAUrl) {
            return url;                                   // judged as it stands: it will not be this box
        }
    }

    private static String HostOfHostAndPort(String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        boolean aBareIpv6 = hostAndPort.indexOf(':') != colon && !hostAndPort.startsWith("[");
        return colon > 0 && !aBareIpv6 ? hostAndPort.substring(0, colon) : hostAndPort;
    }

    private static String WithoutBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** Is the host this box? {@code localhost}, a loopback address, or an address one of this box's interfaces holds. */
    static boolean IsThisBox(String host) {
        return IsThisBox(host, address -> {
            try {
                return NetworkInterface.getByInetAddress(address) != null;
            } catch (Exception unreadable) {
                return false;
            }
        });
    }

    static boolean IsThisBox(String host, Predicate<InetAddress> heldByAnInterfaceOfThisBox) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        if (!IsAnAddress(host)) return false;             // a name: never looked up, never this box
        try {
            InetAddress address = InetAddress.getByName(host);   // an address literal is parsed, not looked up
            return address.isLoopbackAddress() || heldByAnInterfaceOfThisBox.test(address);
        } catch (Exception unparsable) {
            return false;
        }
    }

    /** An IPv4 or IPv6 address written out — the only thing {@link InetAddress#getByName} answers without a lookup. */
    static boolean IsAnAddress(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (h.contains(":") && h.matches("[0-9a-f:.]+"));
    }
}
