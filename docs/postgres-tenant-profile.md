# The profile of a PostgreSQL tenant

billing-core writes a tenant's rows where the tenant's profile says: MySQL (a tenant is a **database**) or PostgreSQL (a tenant is
a **schema** of one switch database). The pipeline, the one transaction per tenant batch, the summary outbox and the ping are the
same on both. This page lists the keys a PostgreSQL profile sets, and what a deployment must have in place before the first start.

The file: `config/tenants/<root tenant>/<profile>/profile-<profile>.yml` (bundled, or under `billing.config.dir`). The tenant that
`application.properties` enables is the ROOT of a tree (`btcl`); its resellers (`res_44`, `res_44_7`) come from the tree and need no
entry of their own.

## 1 · The keys

```yaml
billing:
  # The tenant tree. On a PostgreSQL deployment this is prime-context, on the same roads config-manager has.
  config-manager:
    base-url: "http://10.10.199.1:7091"
    tenant-root-endpoint: "/get-specific-tenant-root"
    timeout-seconds: 180

  # The doorbell: prime-context rings config_event_loader_<root> AFTER it rebuilt the tree.
  config-events:
    enabled: true
    bootstrap-servers: "10.10.199.20:9092"
    event-topic-base: "config_event_loader"            # the topic is <base>_<root tenant>
    consumer-group-base: "billing-core-config-reload"
    debounce-ms: 3000

  datasource:
    kind: postgresql                                   # mysql (the default) | postgresql
    host: "10.10.199.20"
    port: 5432                                         # default 5432 when kind is postgresql
    database: "routesphere"                            # the switch database; a tenant is a schema of it
    username: "billing_core"
    password-ref: "env:TENANT_BTCL_SWITCH_BILLING_CORE_PASSWORD"   # the NAME of a variable; never the value
    postgres:
      summary-service-role: "summary_service"          # reads cdr / cdrerror / acc_chargeable; deletes from summary_affected only
      reader-roles: ["ad_sphere"]                      # read the four tables
      months-back: 1                                   # the month partitions a new table is made with …
      months-ahead: 3                                  # … and how far ahead a month is added

  cdr-ingest:
    enabled: true
    bootstrap-servers: "10.10.199.20:9092"
    topic: "cdr_btcl"                                  # cdr_<root tenant>
    dead-letter-topic: "cdr_dlq_btcl"                  # cdr_dlq_<root tenant>; it must EXIST
    consumer-group: "billing-core-cdr-ingest"
    auto-offset-reset: "earliest"                      # the default: a new group skips nothing
    dead-letter-unhealthy-after-tries: 3
    unknown-tenant-reload-seconds: 30
    poll-ms: 500

  summary:
    enabled: true                                      # the ping; the outbox row is written regardless
    entity-type: "cdr"
    ping-topic: "cdr_summary_ping_btcl"                # named by the ROOT, as the cdr topic is (see 2)
    bootstrap-servers: "10.10.199.20:9092"

  summary-rollup:
    enabled: false                                     # MUST stay off on PostgreSQL (refused at start otherwise)
```

| key | default | what it does |
|---|---|---|
| `datasource.kind` | `mysql` | the write target. Any other word than `mysql` / `postgresql` is refused at start |
| `datasource.database` | — | PostgreSQL only: the one switch database. A tenant's connection is this database with `currentSchema=<tenant>`: its search path is the tenant's schema and nothing else |
| `datasource.username` | — | the role billing-core connects as (`billing_core`). It needs USAGE and CREATE on every tenant schema — prime-context's provisioning gives both |
| `datasource.password-ref` | — | `env:<NAME>`: the password is read from that environment variable at start. A variable that is not set = the service refuses to start and names the variable. `password:` (the value in the file) still works for the deployments that use it; naming both is refused |
| `datasource.postgres.summary-service-role` | `summary_service` | the role that gets SELECT on `cdr`, `cdrerror`, `acc_chargeable`, SELECT + DELETE on `summary_affected`, and nothing on any partition. Empty = no grant and no revoke for it |
| `datasource.postgres.reader-roles` | `[ad_sphere]` | roles that get SELECT on the four tables. `[]` = none |
| `datasource.postgres.months-back` / `months-ahead` | `1` / `3` | a new partitioned table is made with these months around the current one, plus a DEFAULT partition; a month that comes inside `months-ahead` is added before it is needed |
| `cdr-ingest.dead-letter-topic` | `cdr_dlq` | where a refused record goes. **Required, and the topic must exist**: the ingest consumes nothing until it does, says so in the log, and turns `/q/health` red |
| `cdr-ingest.auto-offset-reset` | `earliest` | where a consumer group with no committed offset starts. `latest` skips what was published before its first start |
| `cdr-ingest.dead-letter-unhealthy-after-tries` | `3` | failed tries to publish one batch's dead letters before `/q/health` goes red. The batch is held and retried regardless |
| `cdr-ingest.unknown-tenant-reload-seconds` | `30` | a record for a tenant the loaded tree does not know makes billing-core fetch the tree again — not more often than this |

## 2 · What must be there before the first start

| what | who makes it |
|---|---|
| the switch database, the roles `billing_core`, `summary_service`, `ad_sphere`, and `GRANT billing_core, summary_service TO prime_context` | the deployment's window (prime-context `docs/ad-as-call/postgres-tenancy.md` §3) |
| each tenant's schema, with USAGE + CREATE for `billing_core` | prime-context's provisioning |
| the Kafka topics `cdr_<root>`, `cdr_dlq_<root>`, `config_event_loader_<root>`, `cdr_summary_ping_<root>` | the deployment's window. billing-core creates no topic |
| the variable named by `password-ref`, in the unit's environment | secreteer (`/etc/secreteer/<tenant>/<app>.env`, `EnvironmentFile=`) |

billing-core's own tables — `cdr`, `cdrerror`, `acc_chargeable`, `summary_affected` — are **not** on this list: it makes them itself
the first time it serves a schema (`java/src/main/resources/sql/postgres/billing-tables.sql`). Nobody applies that file by hand.

**The four topics are not alike.**

| topic | when it is missing |
|---|---|
| `cdr_dlq_<root>` (`billing.cdr-ingest.dead-letter-topic`) | the ingest consumes NOTHING and `/q/health` is DOWN, until the topic is there. A refused record must never be lost behind a committed offset |
| `cdr_summary_ping_<root>` (`billing.summary.ping-topic`) | **nothing waits.** The rows are in `cdr` at once; billing-core says ONE WARN a minute (`summary ping NOT published to topic '…' on … — the topic is not on the brokers`) and `/q/health` stays UP, with the detail `summary-ping`. The summaries then come at the summary service's next poll instead of at once. When the topic is made the pings start by themselves within a minute: no restart |

**The ping's topic is named by the root**, `cdr_summary_ping_<root>`, as the cdr topic is — a profile value on BOTH sides: here
`billing.summary.ping-topic`, and the same name in the summary service's profile. Two roots on one set of brokers must not ring each
other's summary service. The built-in default, `cdr_summary_ping`, is what a profile gets that names none (`ccl78` names it so).

billing-core creates no topic, and the ping does not make a broker create one either: the brokers are asked for their topics, and a
topic they do not list is never handed a ping — also where the brokers would create a topic on first use (`auto.create.topics.enable`).

## 3 · What to look at

| road | says |
|---|---|
| `GET /q/health` (the service's HTTP port) | `cdr-ingest` is DOWN, with the reason, while the ingest is refused at start (no dead-letter topic) or holds a batch whose dead letters cannot be published |
| the log, `cdr ingest REFUSED — …` | the same refusal, in words, with the topic and the brokers |
| `GET /q/health`, `cdr-ingest` UP with the detail `summary-ping` | the summary ping cannot be published (its topic is not on the brokers, or they do not answer). Nothing waits: the rows are written, the summary service polls |
| the log, `summary ping NOT published to topic '…' on … — …` (WARN, once a minute) | the same, with the topic, the brokers, the cause and how many pings were not sent. `summary ping: topic '…' on … takes pings again` when it is over |
| the log, `schema <s>: billing-core's tables made — …` | the first batch of a schema made its tables |
| the log, `schema <s>: partition <p> could NOT be added (…)` | a month could not be added; its rows go to the DEFAULT partition; tried again the next day |
