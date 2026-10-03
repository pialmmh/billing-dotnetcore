# telcobright-billing-core — Java / Quarkus port

> ## Read this before a build of this branch goes on a box that runs today
>
> **1 · The cdr ingest consumes NOTHING until its dead-letter topic exists on the brokers** — on the MySQL deployments too.
>
> The topic is the profile's `billing.cdr-ingest.dead-letter-topic` (default `cdr_dlq`; `cdr_dlq_<root tenant>` on the ratified wire).
> billing-core creates no topic. While the topic is missing (or the profile names none) the ingest says so — one ERROR line with the
> topic and the brokers, repeated about once a minute — and `/q/health` is DOWN. It looks again every few seconds and starts by
> itself when the topic is there: no restart. **Create the topic first, then start the build.**
>
> Why: an offset is never committed past a record that is neither written nor dead-lettered. Rows are committed, then the batch's
> dead letters are published (`acks=all`), then the offsets. A publish that fails HOLDS the batch — nothing else is consumed — and is
> tried again every 5 s; after `dead-letter-unhealthy-after-tries` (3) tries `/q/health` goes DOWN.
>
> **2 · A health road is new:** `GET /q/health` on the service's HTTP port (`quarkus-smallrye-health`). Its `cdr-ingest` check is
> DOWN, with the reason, while the ingest is refused or holds a batch. A window's gate and a monitor read it.
>
> **3 · A new consumer group starts at `earliest`** (`billing.cdr-ingest.auto-offset-reset`). A group that already has offsets is not
> affected; `ccl78` dev keeps `latest` in its profile.
>
> The profile of a PostgreSQL tenant: `../docs/postgres-tenant-profile.md`. The wire: `../docs/cdr-kafka-ingest-contract.md`.

A **faithful 1:1 clone** of the .NET 8 service in `../src/Billing`, ported to **Java 21 / Quarkus 3.24**.
Same folder structure, same class + method names (kept verbatim, including the legacy lower-case model
names `cdr` / `ne` / `partner` / `rateassign`). ONE binary. The `.proto` is shared with the .NET service —
identical gRPC wire format; only the generated-code layout differs.

## Tech mapping (.NET → Java)
| .NET 8 | Java / Quarkus |
|---|---|
| Grpc.AspNetCore | quarkus-grpc (Mutiny `RatingService`) |
| Confluent.Kafka | quarkus-kafka-client (plain producer/consumer) |
| YamlDotNet | jackson-dataformat-yaml |
| MySqlConnector | mysql-connector-j (JDBC, manual tx on `java.sql.Connection`) |
| Microsoft.Extensions.DI | CDI / Quarkus Arc (`BillingConfig` producers) |
| `IHostedService` | `@Observes StartupEvent` (`CdrProcessor`, `BillingBootstrap`) |
| xUnit | JUnit 5 |
| C# `record` / `decimal` / `DateTime` | Java `record` / `BigDecimal` / `LocalDateTime` |

## Layout (folders = packages under `com.telcobright.billing`)
```
api/            gRPC surface (BillingServiceImpl) + api/internal handlers
beans/          CdrProcessor (main startup bean) + SummaryChangeNotificationPublisher
mediation/      the engine: cdr, context, rating(+ratecaching), servicegroups, servicefamilies,
                summary(+cache), sql, validation, engine/models (the verbatim POCOs)
data/           the write target's adapters (JDBC): MySQL, and PostgreSQL (a tenant = a schema) + the one-transaction
                batch runner; what differs between the two is one interface, DatasourceEdge
tenantconfigsync/  per-tenant config load from config-manager (HTTP) + Kafka config-event source
BillingConfig.java     CDI composition root (the Program.cs equivalent)
BillingBootstrap.java  startup: fail-fast tenant load + config-event listener
```

## Build / test / run
```bash
mvn -f java/pom.xml clean package        # compile + 103 tests + runnable jar
mvn -f java/pom.xml test                 # tests only (MySQL integration tests skip if 127.0.0.1:3306 down)
mvn -f java/pom.xml test -Dbc.lab.pg.url=jdbc:postgresql://127.0.0.1:7743/routesphere
                                         # + the PostgreSQL lab tests (a throwaway PostgreSQL with the roles of
                                         #   prime-context's postgres-tenancy.md §4); without the key they are SKIPPED
mvn -f java/pom.xml quarkus:dev          # dev mode (boots vs live config-manager; gRPC on :9000)
java -jar java/target/quarkus-app/quarkus-run.jar
```
- gRPC server: `:9000` (h2c, separate server).
- Tenant config (routesphere convention): registry (enable/disable + active profile) in
  `application.properties` (`billing.tenants[i].*`); per-profile YAML in
  `src/main/resources/config/tenants/<t>/<p>/profile-<p>.yml` (classpath), overridable by an external
  dir via `billing.config.dir`.
- Local MySQL for integration tests: `127.0.0.1:3306` (lxc), `root`/`123456`.

## Faithful-port notes (where C# semantics needed a Java shape)
- Non-nullable C# value types default to a value, not null: `cdr` `DateTime` fields → `LocalDateTime.of(1,1,1,0,0)`
  (C# `DateTime.MinValue`); summary `decimal` aggregation fields → `BigDecimal.ZERO`.
- The 20-field `CdrSummaryTuple` (a C# `ValueTuple`) → `java.util.List<Object>`. C# `decimal` tuple-key equality
  is scale-insensitive, so the rate elements are normalised with `stripTrailingZeros()` in `GetTupleKey()`.
- C# named/optional args have no Java analogue → overloads (engine) or a fluent builder (`TestData`).
- C# extension methods → plain static helpers (`CdrExt`, `MySqlFieldExtensions`).
