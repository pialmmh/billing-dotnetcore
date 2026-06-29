package com.telcobright.billing.tenantconfigsync.spi;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * What this package REQUIRES to know config changed: a source of change signals. routesphere drives
 * this off Kafka (topic {@code config_event_loader_<tenant>}); the host provides that adapter and
 * injects it. The package stays free of any broker dependency — it only consumes the SPI. Absence of
 * a source is information: with none registered, config loads once on start and never reloads.
 *
 * <p>Faithful-port note: this SPI is the boundary to the Kafka glue (KafkaConfigEventSource +
 * ConfigEventConsumerLoop), which is implemented separately. The async shape is preserved:
 * C# {@code Task} → {@link CompletableFuture}{@code <Void>}, the {@code Func<ConfigChangeSignal, Task>}
 * callback → {@link Function}{@code <ConfigChangeSignal, CompletableFuture<Void>>}.
 * The C# {@code CancellationToken} has no Java equivalent; it is modelled here as a
 * {@code CompletableFuture<Void>} placeholder (completes = cancel) — the glue owner should finalize
 * the cancellation shape to match KafkaConfigEventSource.
 */
public interface IConfigEventSource {
    /** Begin delivering signals to {@code onSignal}. Returns when subscribed. */
    CompletableFuture<Void> StartAsync(
        Function<ConfigChangeSignal, CompletableFuture<Void>> onSignal,
        CompletableFuture<Void> ct);

    /** Stop delivering signals. */
    CompletableFuture<Void> StopAsync(CompletableFuture<Void> ct);
}
