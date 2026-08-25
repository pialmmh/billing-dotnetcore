# billing-core (Java) — takeover brief for the next agent

**Repo:** `git@github.com:pialmmh/billing-dotnetcore.git`, branch **`master`**, live code under **`java/`**
(Java 21 / Quarkus 3.24, package `com.telcobright.billing`). HEAD `5a99458`. Build/test:
`mvn -f java/pom.xml clean package` → **89 tests green**. Legacy .NET (`telcobright-billing-dotnet`, .NET
Framework) is the **read-only behavior reference** — port faithfully, don't invent abstractions.

**Golden rules the user enforces (violations get reverted):** faithful verbatim port (keep legacy
signatures/lowercase type names `cdr`/`rateassign`); reuse tested legacy helpers; **ONE batch = ONE
top-level commit/rollback** (inner code only EMITS SQL — see `MySqlCdrBatchRunner`); if code can't reach a
DB/REST/external service, **STOP and ask** (don't paper over). DB creds are **inline in the profile YAML**
for THIS project (OpenBao dropped); real creds live in an EXTERNAL config dir (`billing.config.dir`), the
in-repo profile is a blank template.

---

## 1. What this service does

A gRPC rating / CDR-mediation service. routesphere (Java) calls it for rate admission + post-call; it also
ingests rated CDRs, writes them per tenant schema, and hands summaries to a standalone **summary-service**
via an outbox. It is a faithful port of the tested C# mediation engine. Service groups **SG10** (domestic
outgoing) and **SG11** (domestic incoming) are implemented; others are future scope.

The per-batch pipeline (single tenant, all in ONE tx):
```
cdr ─► detect SG ─► rate (RateCache/A2ZRater) ─► service family ─► acc_chargeable (customer + supplier legs)
    ─► validate (checklists) ─► write: cdr + cdrerror + acc_chargeable + ONE summary_affected outbox row
    ─► (post-commit) best-effort Kafka ping to the summary-service
```
Rating is **DB-free** — all rate data comes from config-manager (`103.95.96.78:7072`, HTTP + a Kafka
config-events consumer keeps it fresh). The DB is only WRITTEN (cdr/chargeable/outbox).

---

## 2. APIs that already work (gRPC, `:9000`, proto `java/src/main/proto/billing.proto`)

Impl `api/BillingServiceImpl.java` → thin handlers in `api/internal/`:

| RPC | Handler | Purpose | State |
|---|---|---|---|
| `GetMaxRatePerMinute` | `MaxRateHandler.java` | rate admission — max per-minute rate over the tier chain | ✅ works (verified live) |
| `FinalizeAndSummarize` | `FinalizeHandler.java` | post-call per-tier settlement — **compute-only** (no DB; routesphere mem-ledger applies it) | ✅ |
| `ProcessCdrBatch` | `ProcessCdrBatchHandler.java` | batch CDR processing — **the debug entry** (drives the pipeline until the Kafka consumer exists) | ✅ |

Drive them with grpcurl/Postman — see `docs/local-debug-ccl.md` §5. Rating is offloaded off the Mutiny
event loop (`BillingServiceImpl` wraps each RPC in `runSubscriptionOn(workerPool)`).

---

## 3. Important code files (by concern)

**Entry / transaction boundary**
- `beans/CdrProcessor.java` — startup bean; `ProcessBatch(tenant, cdrs)`; `onStart` is the **seam where the
  Kafka cdr loop goes** (currently a log line). Resolves tenant config → `MySqlCdrBatchRunner`.
- `data/MySqlCdrBatchRunner.java` — the ONE tx: `setAutoCommit(false)` → `GET_LOCK('billing_batch_<schema>')`
  held across commit (makes outbox ids commit-ordered + id-seeding race-free) → run pipeline → commit/rollback.
- `data/MySqlConnectionFactory.java` (host/creds from profile; `IsConfigured()`), `data/MySqlExecutor.java`
  (the tx-bound `ISqlExecutor`; raw `Statement`), `data/MaxIdSeededAutoIncrementManager.java` (seeds ids from
  `max(id)` per table in the batch tx).

**Pipeline** — `mediation/cdr/`
- `CdrPipeline.java` — phase 0 (IdCall + intra-batch dup asserts), phase 1 (mediate+qualify), phase 2 (write).
- `CdrWriter.java`, `ChargeableWriter.java` (both legs), `SummaryOutboxWriter.java` (v2: `op` + `{Cdr,
  Chargeables[]}` blob, base64(gzip(json))), `Entry.java`/`RatedCdr.java`/`CdrBatch.java`/`CdrBatchResult.java`.
- `mediation/validation/` — `MediationValidator.java` + one class per `IValidationRule` + `ValidationRuleRegistry`.

**Rating** — `mediation/rating/`
- `BasicCharge.java` (detect→resolve→rate→family→chargeable, loops the SG's configured rules for both legs),
  `A2ZRater.java` (+`A2ZRateResult`), `RatePlanResolver.java` (route→partner→null), `FinalizeEngine.java`
  (settlement compute), `MaxRateEngine.java` (admission).
- `ratecaching/`: `RateCache.java` (per-day dict, now `ConcurrentHashMap`), `PrefixMatcher.java`
  (=legacy MatchPrefixParallel), `TupleRateLoader.java` (the config-fed JOIN; DB-free), `IRateLoader.java`.
- `servicefamilies/`: `IServiceFamily.java` (RATING-MATH-ONLY scope note — accounting/posting deliberately
  dropped), `ChargeableBuilder.java`, `SfA2Z.java` (SG10 supplier), `SfA2ZWithVatTax.java` (SG10 customer),
  `SfDomOffNetInAns.java` (SG11), `FamilyStamp.java`.
- `servicegroups/`: `ServiceGroupDetection.java`, `SgDomOffnetOut.java` (10), `SgDomOffnetIn.java` (11),
  `BdNumberNormalizer.java`.

**Config sync (config-manager → in-memory)** — `tenantconfigsync/`
- `internal/ConfigManagerMapper.java` (served JSON → engine models — the mapping surface), `HttpConfigManagerClient.java`,
  `TenantHierarchyLoader.java`/`TenantTreeBuilder.java`/`TenantRegistryState.java`, `DayBoundaryRefresher.java`.
- **`internal/ConfigEventConsumerLoop.java` + `KafkaConfigEventSource.java` — the EXISTING Kafka consumer;
  MIRROR THIS PATTERN for the cdr consumer** (offsets, poll loop, debounce). `api/ITenantRegistry.java`,
  `dependencies/*Options.java` (profile-bound config incl. `SummaryOutboxOptions`, `ConfigEventsOptions`).

**Summary hand-off (outbound — DONE)**
- `beans/SummaryChangeNotificationPublisher.java` — post-commit ping to `cdr_summary_ping` (never throws/blocks).
- `src/main/resources/sql/summary_outbox.sql` — the `summary_affected` DDL (`id`, `entity_type`, `op`, `data`).

**Shared SQL infra** — `mediation/sql/`: `ISqlExecutor`, `BatchSqlWriter` (segmented multi-row insert, default
1000), `CollectionSegmenter`, `ICacheble`, `IAutoIncrementManager`, `MySqlFieldExtensions` (SQL literal
escaping lives here), `CountingAutoIncrementManager`.

**Models** — `mediation/engine/models/`: `cdr.java` (104-col `ExtInsertColumns`), `acc_chargeable.java`
(33-col), `rate`/`rateplan`/`rateassign`/`Rateext`/`rateplanassignmenttuple`/`enumbillingspan`/`partner`/`ne`.

**Config / run** — `src/main/resources/application.properties` (tenant list + active profile + gRPC :9000),
`config/tenants/ccl78/dev/profile-dev.yml` (config-manager URL, Kafka, datasource, summary block).

---

## 4. Docs to read first

- `docs/local-debug-ccl.md` — **how to build/run/debug** (§A local-MySQL fast path, §B CCL backend) +
  `docs/local-debug-schema.sql` (ready-to-run permissive schema for cdr/cdrerror/acc_chargeable/summary_affected).
- `docs/cdr-kafka-ingest-contract.md` — **the design spec for the #1 remaining task** (the Kafka consumer).
- `docs/AGENT_GUIDE.md` — principles (has a .NET-era banner; §2 principles still hold).
- Project memory `project_billing_mediation_port.md` — full current-state narrative.

---

## 5. Remaining work (priority order)

### T1 — Inbound Kafka CDR consumer  ← the big one, design already specced
Build per `docs/cdr-kafka-ingest-contract.md`. Three SRP pieces, plugging into the EXISTING pipeline/outbox/ping:
1. `CdrKafkaConsumer` — poll `cdr_rated` (key=callUuid, value=`CdrEvent[]` per call), offset mgmt, dead-letter.
   Mirror `ConfigEventConsumerLoop`/`KafkaConfigEventSource`.
2. `CdrEventPreprocessor` — pure/testable: decode → validate → map `CdrEvent`→`cdr` → group by tenant →
   attach each tenant's `MediationContext`+Partners.
3. `MultiTenantCdrProcessor` — ONE connection, ONE tx **across tier schemas**; per tenant run the existing
   `CdrPipeline.Process`; commit all tiers together, THEN commit Kafka offsets. (Current pipeline is
   single-tenant — this is the fan-out wrapper.)
   Wire the loop launch in `CdrProcessor.onStart`. The summary ping already fires post-commit — reuse it.
   **Dependency:** the `cdr_rated` wire format is produced by routesphere; it's PROPOSED (contract §9 open
   items) and the ARCHITECT ratifies it. Build against the specced shape; flag the open items, don't guess.

### T2 — Correction producer (subtract flow)
The outbox `op` column + the summary-service consumer already support subtract. Billing must EMIT corrections:
port the legacy `CdrEraser`/reprocess flow to outbox — load old cdr+chargeables, write a `subtract` blob
(OLD values) + an `add` blob (NEW values) in ONE tx (`SummaryOutboxWriter.Write(..., op)` already takes op).

### T3 — Cross-batch duplicate detection
Kafka at-least-once can redeliver → double-bill. Legacy used a `zz_uniqueevent` filter. Decision needed:
port the filter vs a unique index on `UniqueBillId`. (Intra-batch dup asserts already exist in `CdrPipeline`.)

### T4 — More service groups (beyond SG10/SG11), and a live DB run
New SGs = new `Sg*` detector + `Sf*` family + config. Live run needs a reachable MySQL with the 4 tables +
creds (external config dir + CCL VPN).

---

## 6. The summary-service side (SEPARATE repo/agent — not this codebase)

`/home/mustafa/telcobright-projects/summary-service` (its own agent, role `architect`). It consumes the
outbox and rolls up summaries. Its outstanding work order + the answered Q&A are in:
- `/tmp/shared-instruction/summary-service-work-order.md` (blob v2 + op plumbing, SG10 faithfulness fixes,
  suffix `03`/`02`, the NEW chargeable day/hr summary, table self-provisioning, robustness).
- `/tmp/shared-instruction/summary-service-questions-for-dotnet.md` (Q1–Q6 answered by billing).
Do NOT edit summary-service from billing; coordinate via the dev log (`devlog.sh handoff architect "..."`,
project `summary-service`, log `/tmp/shared-instruction/summary-service-activity.ndjson`).

---

## 7. Gotchas / do-not-break

- Summary is **outbox-ONLY** — there is no inline summary engine; the billing repo has no `summary` package
  (generic infra was cut to `mediation/sql`). Don't re-add summary logic here.
- Faithful-port quirks are documented in javadoc (e.g. SfA2Z supplier leg leaves `chargeable.TaxAmount1`
  null; `connectedcallsCC` not scaled on the summary side). Don't "tidy" them without an explicit decision.
- The DB sink is a raw `Statement` — string values MUST go through `MySqlFieldExtensions.ToMySqlField`
  (it escapes `'`/`\`). Never concatenate a raw string into SQL.
- One commit/rollback, top-level only. Never commit inside the pipeline/writers.
