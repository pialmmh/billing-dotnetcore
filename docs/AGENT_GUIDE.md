# Agent guide — working on telcobright-billing-core

> ⚠️ **THIS GUIDE IS .NET-ERA AND PARTLY SUPERSEDED (as of 2026-07).** The service was **ported to
> Java/Quarkus** — the live code is under **`java/`** (Java 21, package `com.telcobright.billing`); the
> `src/Billing` .NET tree is retired. Corrections that override §3–§4 below:
> - **Build/test/run:** `mvn -f java/pom.xml clean package` (**89 tests**), then `mvn -f java/pom.xml
>   quarkus:dev` (gRPC on **:9000**, JDWP on :5005). Not `dotnet …`, not `:5293`.
> - **Summary is OUTBOX-ONLY** — the inline `CdrSummaryContext` step (§4 phase 3) is GONE. The batch writes
>   one `summary_affected` row (v2: `op` column + ALL chargeable legs); a standalone **summary-service**
>   consumes it. Pipeline is now 2 phases (mediate+qualify → write).
> - **Secrets:** DB creds are **inline in the profile YAML** for this project (OpenBao dropped) — §2 rule 5 is void here.
> - **The runnable, current instructions are `docs/local-debug-ccl.md`** (+ `docs/local-debug-schema.sql` for a
>   local-MySQL write-path debug). The authoritative current-state narrative is the project memory
>   `project_billing_mediation_port.md`. The principles in §2 (faithful port, one-commit-per-batch, reuse
>   tested code, stop-and-ask on unreachable infra) STILL HOLD. Read the rest of this file for history/context only.

Read this first if you're an AI agent picking up this repo. It's the **how to work on it** map:
principles, build/run, architecture, current state, what's next, and the one live blocker.

> Companion docs: **`docs/postman-e2e-testing.md`** (run + Postman/grpcurl, verified live),
> **`docs/mediation-port-progress.md`** (resume note), and the Trilium onboarding note
> `dotnet-billing` (`http://10.9.9.6:7081`, note `AONi8vSwypyD`).

---

## 1. What this is
A **.NET 8 gRPC rating / CDR / summary service** (`telcobright-billing-core`, repo
`pialmmh/billing-dotnetcore`, branch `master`). It is a **faithful port** of the tested C# mediation engine
in the read-only reference `telcobright-billing-dotnet` (.NET Framework, won't build on Linux). routesphere
(Java/Quarkus) calls it for admission + post-call; config-manager feeds per-tenant config over HTTP.

## 2. Working principles (the user ENFORCES these — violations get reverted)
1. **Faithful verbatim port.** Keep legacy signatures/namespaces (`MediationModel.*`,
   `TelcobrightMediation.*`, `#nullable disable`, lowercase types `cdr`/`rateassign`). Don't invent
   abstractions or architect dependencies.
2. **Reuse the tested legacy code** — port the real helper (e.g. `CollectionSegmenter`), don't shortcut. The
   user reviews with "did you reuse our existing writers?".
3. **Keep the code AND the number of classes close to the legacy.** Fix imports only as needed to compile.
4. **One batch = ONE top-level commit/rollback**, at the high-level entry only. **No commit/rollback in any
   inner class/method** — inner code only EMITS SQL. (See `MySqlCdrBatchRunner`.)
5. **Secrets only from OpenBao**, never YAML/env (code-master rule). DB host/port are non-secret (in the
   profile); creds come from the store.
6. **Never write the remote client DB `103.95.96.77:3306` without explicit human go-ahead.** (Go-ahead was
   given for the current dev env.)
7. **If code fails to reach a DB / REST endpoint / external service, STOP and ask the human** — don't paper
   over it.
8. The user spot-checks faithfulness with pointed "did we port X faithfully / does it behave like legacy?"
   questions, then expects the fix. Answer honestly first.

## 3. Build / test / run
```bash
dotnet build                       # expect 0 warnings / 0 errors (xUnit analyzer is on — keep it clean)
dotnet test --no-build             # 95 tests, 0 skipped (@a11f5da)
dotnet run --project src/Billing --launch-profile http   # boots vs LIVE config-manager, :5293 (h2c)
```
- **Local MySQL** (integration tests): lxc container, `127.0.0.1:3306` (NOT localhost), `root`/`123456`,
  MySQL 5.7, driver MySqlConnector. Tests create their own DBs and **skip** if mysql is down. DB is behind
  seams (`IRateLoader`, `ISummaryStore`, `ISqlExecutor`) so everything is unit-testable without a DB.
- **config-manager** (dev): `http://103.95.96.78:7072`, tenant `ccl78` (from `config/tenants.yml` +
  `config/tenants/ccl78/dev/profile-dev.yml`). The service **fail-fast** loads it on startup — won't start
  if it's unreachable.

## 4. Architecture map
ONE project / ONE binary: `src/Billing` (`Billing.csproj`, Web SDK). Folders-as-packages, same namespaces:
`Mediation/` (the engine), `Data/` (live MySql adapters), `TenantConfigSync/` (tenant config sync from
config-manager), `Api/` (gRPC host) + `Beans/` (the `CdrProcessor` startup bean + summary ping; the
config-event consumer is config-sync infra in `TenantConfigSync/Internal/`, not a bean).

The per-tenant **batch pipeline** — `Mediation/Cdr/CdrPipeline.cs`, `Process(CdrBatch)` (run by the
`Beans/CdrProcessor` startup bean, which resolves tenant config from config-manager first):
```
1 Mediate   BasicCharge.Rate: detect SG → run the SG's CONFIGURED rating rules over the per-day RateCache → chargeables
2 Qualify   MediationValidator: common + per-SG answered/unanswered checklists  (reject → cdrerror)
3 Summaries CdrSummaryContext: pre-load the batch's dates/hours-involved, merge-add each call
4 Write     cdr + acc_chargeable + sum_voice_* (qualified) and cdrerror (rejected),
            all through ONE segmented writer (BatchSqlWriter), inside ONE transaction owned by MySqlCdrBatchRunner
```
Key files by concern (under `src/Billing/`; engine concerns live in `Mediation/`):
- **Rating**: `Rating/{BasicCharge, A2ZRater, RatePlanResolver}`, `Rating/RateCaching/{RateCache, PrefixMatcher,
  TupleRateLoader}`, `ServiceFamilies/Sf*`, `ServiceGroups/{ServiceGroupDetection, Sg*}`.
- **Rules & config**: `Context/{MediationContext, RatingConfig}` (`Rule`/`RatingRule`/`ServiceGroupConfiguration`),
  `Validation/{IValidationRule+MediationValidator, CdrValidationRules+ValidationRuleRegistry}`.
- **Summary**: `Summary/{CdrSummaryContext, Cache/AbstractCache, Cache/SummaryCache}`.
- **SQL writing**: `Sql/{BatchSqlWriter, CollectionSegmenter, ISqlExecutor}`; writers `Cdr/{CdrWriter,
  ChargeableWriter}`; atomic runner `Data/MySqlCdrBatchRunner`.
- **gRPC**: `Api/BillingServiceImpl.cs`, proto `Protos/billing.proto`.
- **Config sync**: `TenantConfigSync/Internal/{ConfigManagerMapper, HttpConfigManagerClient,
  TenantHierarchyLoader}`.

## 5. Current state (DONE, SG10 & SG11, build 0/0, 95 tests)
- Full per-tenant pipeline (mediate → qualify → summarize → atomic write) — proven vs local mysql.
- Configured rating rules on a reusable `Rule` base; family-by-id registry.
- Post-mediation validation checklists + **cdrerror**; data-driven validation config (name-keyed registry +
  config-manager `{rule, data}` references — replaces legacy MEF/Spring.NET/TypeNameHandling).
- Segmented batch SQL writing; one top-level commit/rollback (`MySqlCdrBatchRunner`).
- **gRPC**: `GetMaxRatePerMinute` (real rater; whole chain in one round trip via per-tier `levels`),
  `FinalizeAndSummarize` (compute-only per-level settlement), and **`ProcessCdrBatch`** (the Kafka-fed batch
  path: `repeated string cdrs_json` → full `cdr` POCO → `CdrProcessor.Process`, atomic write to the tenant's
  schema).

## 6. THE LIVE BLOCKER — rate-form mismatch (fix this to make a demo price)
config-manager serves rates as the **DynamicContext** form — `context.ratePlanWiseTodaysRates`,
`rateAssignsCustomer`, `rateAssignsSupplier`, packages `partnerIdWisePackageAccounts` — but its
`mediationContext` carries only `categories` + `serviceGroupRules`, **NOT `ratePlanAssignmentTuples`**. The
faithful RateCache rater builds `RatePlanResolver` + `RateCache` from `mediationContext.ratePlanAssignmentTuples`
(+ nested `rateassigns`) → absent → empty → **no cash rate**. Verified live: GetMaxRatePerMinute over
`res_233_2 → res_233 → telcobright` detects SG10 + returns package candidates but rejects with
`"no rate or package for the call"`; ProcessCdrBatch would route cdrs to **cdrerror** (unrated).

**Fix (pick one, raise with the architect):**
1. config-manager also emits `mediationContext.ratePlanAssignmentTuples` (legacy tuple shape, nested
   `rateassigns`) — billing-core already deserializes it (`MediationContextDto.RatePlanAssignmentTuples`).
2. billing-core maps the already-served `rateAssignsCustomer/Supplier` (+ `ratePlanWiseTodaysRates`) into the
   tuple/rateassign shape `TupleRateLoader`/`RateCache` expect, inside `ConfigManagerMapper`.

## 7. What's next (pending, roughly prioritized)
1. **Close the rate-form gap (§6)** — without it nothing prices.
2. **DB creds for `ProcessCdrBatch`**: OpenBao isn't wired + no OpenBao address in the profile. Creds come
   from configuration as a stopgap (`Billing:Db:User`/`Password`, host overridable). Wire OpenBao secret-ref
   resolution properly, or have the human supply dev creds.
3. **Job-fetch layer** (legacy `_Process/CdrJobProcessor.cs`) — fetch/decode jobs per tenant+NE → produce the
   `List<cdr>` → `ProcessCdrBatch`/`CdrProcessor`. Kept decoupled from processing.
4. config-manager to emit the full `mediationContext` (SG configs, checklists, tuples); GL/accounting
   (`acc_transaction`), cdrinconsistent, NER/PDD/post-rating; partner rules; service groups beyond 10/11.

## 8. Testing
See **`docs/postman-e2e-testing.md`** — verified live: run steps, Postman gRPC setup, the real ccl78 chain +
partner ids, the actual request/response payloads for all three RPCs, and debugging breakpoints. grpcurl is
installed; the proto must be imported (no server reflection).

## 9. Coordination
Shared dev log `/tmp/shared-instruction/routesphere-activity.ndjson`, role **dotnet**
(`/home/mustafa/.claude/bin/devlog.sh startdev routesphere dotnet`; `handoff <to-role> <task>` to notify the
architect — the architect ratifies contracts). Cross-agent specs already posted under
`/tmp/shared-instruction/`: `billing-maxrate-levels-contract-change.md`,
`billing-e2e-testing-handoff.md`. Commit/push only when the human asks; end commit messages with the
`Co-Authored-By` trailer.
