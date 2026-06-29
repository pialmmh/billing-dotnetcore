# telcobright-billing-core — Java / Quarkus port

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
data/           live MySQL adapters (JDBC) + the one-transaction batch runner
tenantconfigsync/  per-tenant config load from config-manager (HTTP) + Kafka config-event source
BillingConfig.java     CDI composition root (the Program.cs equivalent)
BillingBootstrap.java  startup: fail-fast tenant load + config-event listener
```

## Build / test / run
```bash
mvn -f java/pom.xml clean package        # compile + 103 tests + runnable jar
mvn -f java/pom.xml test                 # tests only (MySQL integration tests skip if 127.0.0.1:3306 down)
mvn -f java/pom.xml quarkus:dev          # dev mode (boots vs live config-manager; gRPC on :9000)
java -jar java/target/quarkus-app/quarkus-run.jar
```
- gRPC server: `:9000` (h2c, separate server). Config tree read from `./config` (`billing.config.dir`).
- Local MySQL for integration tests: `127.0.0.1:3306` (lxc), `root`/`123456`.

## Faithful-port notes (where C# semantics needed a Java shape)
- Non-nullable C# value types default to a value, not null: `cdr` `DateTime` fields → `LocalDateTime.of(1,1,1,0,0)`
  (C# `DateTime.MinValue`); summary `decimal` aggregation fields → `BigDecimal.ZERO`.
- The 20-field `CdrSummaryTuple` (a C# `ValueTuple`) → `java.util.List<Object>`. C# `decimal` tuple-key equality
  is scale-insensitive, so the rate elements are normalised with `stripTrailingZeros()` in `GetTupleKey()`.
- C# named/optional args have no Java analogue → overloads (engine) or a fluent builder (`TestData`).
- C# extension methods → plain static helpers (`CdrExt`, `MySqlFieldExtensions`).
