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
> **4 · A start says its endpoints first — and THE JAR, STARTED WITHOUT A CONFIGURATION OF ITS OWN, IS `ccl78` DEV.**
> The `application.properties` inside the jar enables tenant `ccl78`, profile `dev`, and that profile names boxes that run today
> (its config-manager, its Kafka, its MySQL) with the cdr ingest ON. `java -jar …/quarkus-run.jar` or `mvn quarkus:dev` from a
> directory with no `config/application.properties` of its own is that deployment: it fetches its tree, joins its consumer group,
> writes to its database. A lab start goes through `tools/lab/start-local-only.sh` only — see "A start in a lab" below.
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
mvn -f java/pom.xml quarkus:dev          # dev mode — AS ccl78 DEV: it dials that deployment's boxes (top of this page, 4)
java -jar java/target/quarkus-app/quarkus-run.jar   # the same, unless the directory has its own config/application.properties
java/tools/lab/start-local-only.sh java/target/quarkus-app <run dir> [-e NAME]...   # a LAB start: this box only
```

### A start in a lab

Every start says, as its first log lines, where the active profile was read from and each address it is about to dial —
before the first of them is dialed (`StartEndpoints`; the four option blocks that hold an address reach the application only
through it):

```
endpoints of this start (nothing has been dialed yet) — tenant btcl, profile lab: the file /…/config/tenants/btcl/lab/profile-lab.yml
endpoint: the tenant tree = http://10.10.252.1:7754  (billing.config-manager.base-url)
endpoint: the doorbell's brokers = 127.0.0.1:7792  (billing.config-events.bootstrap-servers)
endpoint: the datasource = postgresql://127.0.0.1:7743/routesphere  (billing.datasource.host)
endpoint: the cdr topic's brokers = 127.0.0.1:7792  (billing.cdr-ingest.bootstrap-servers)
endpoint: the summary ping's brokers = 127.0.0.1:7792  (billing.summary.bootstrap-servers)
```

A start that fell back to a profile the jar carries says `THE JAR'S OWN config/tenants/…` in that first line.

A lab start is made with `tools/lab/start-local-only.sh <quarkus-app dir> <run dir> [-e NAME]... [-- <java option>...]`:

1. **before anything is started**, `tools/lab/lab-endpoints.py` says every address the run directory's configuration names
   (over the jar's own build-time defaults) and refuses unless each is on this box. A run directory without its own
   `config/application.properties` is refused.
2. the service is started in the run directory with an emptied environment (PATH, HOME, LANG, JAVA_HOME and the variables
   named with `-e` — a secret is named, its value is on no command line) and with `-Dbilling.lab.local-only=true`.
3. with that key billing-core judges the endpoints IT resolved and refuses the start itself —
   `REFUSING TO START (billing.lab.local-only=true): …` naming each — when one is not this box. Put the key in the lab's
   `config/application.properties` too.

"This box" is the word `localhost`, a loopback address, or an address one of this box's own interfaces holds (a lab bridge).
A host NAME is never looked up — the lookup itself would leave the box — and is refused. The launcher works for any Quarkus
jar (a lab's prime-context is started with it too); step 3 is billing-core's own.
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
