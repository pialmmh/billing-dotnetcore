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
 */
public final class IngestHealth {

    /** {@code Up} or not, and why not. */
    public record State(boolean Up, String Reason) {
    }

    private volatile State state = new State(true, "");

    public void Up() {
        state = new State(true, "");
    }

    public void Down(String reason) {
        state = new State(false, reason == null ? "" : reason);
    }

    public State Current() {
        return state;
    }
}
