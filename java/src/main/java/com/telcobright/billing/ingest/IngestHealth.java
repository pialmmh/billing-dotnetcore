package com.telcobright.billing.ingest;

/**
 * What the cdr ingest says about itself, for the health road ({@code /q/health}; see
 * {@link com.telcobright.billing.beans.CdrIngestHealthCheck}). The ingest loop writes it; the health check reads it.
 *
 * <p>It goes RED in two cases, both of which mean "records are waiting and nothing moves":
 * <ul>
 *   <li>the ingest is refused at start — the profile names no dead-letter topic, or the topic does not exist;</li>
 *   <li>a batch's dead letters could not be published for a setting's worth of tries
 *       ({@code billing.cdr-ingest.dead-letter-unhealthy-after-tries}) — its offsets are held.</li>
 * </ul>
 * An ingest that is switched off is not red: that is a configuration, not a fault.
 *
 * <p>One thing is said WITHOUT going red: the summary ping that cannot be published ({@link #PingNotPublished}). The
 * batch's rows and its outbox row are written and the summary service polls — nothing waits, only the nudge is
 * missing. The health road stays UP and carries the words as a detail.
 */
public final class IngestHealth {

    /** {@code Up} or not, and why not. */
    public record State(boolean Up, String Reason) {
    }

    private volatile State state = new State(true, "");
    private volatile String pingDetail = "";

    public void Up() {
        state = new State(true, "");
    }

    public void Down(String reason) {
        state = new State(false, reason == null ? "" : reason);
    }

    public State Current() {
        return state;
    }

    /** The summary ping could not be published. Not red: a detail on the health road (the words name the topic and
     * the brokers). */
    public void PingNotPublished(String detail) {
        pingDetail = detail == null ? "" : detail;
    }

    public void PingPublished() {
        pingDetail = "";
    }

    /** What there is to say about the summary ping; empty = nothing. */
    public String PingDetail() {
        return pingDetail;
    }
}
