# Mediation port — progress & resume note

> **Read this first after a compaction.** Then rejoin the shared dev log:
> `bash "$HOME/.claude/bin/devlog.sh" startdev routesphere dotnet` (role = **dotnet**; architect is routesphere side).
> Resume at **NEXT STEP #1** (per-tenant RateCache wiring) below.

## Goal
Port the legacy C# CDR mediation into **telcobright-billing-core (.NET 8)**, starting with **service groups 10 & 11**, computing the post-call charge + writing CDR + summary **per tier** (admin + each reseller). Reuse the proven business logic; replace only the legacy tech.

## Chosen approach = **Option B (lean reuse)**
Port the proven `Sg*` detection + the A2Z charge **math** against **our** config-manager-fed context + a single-connection data layer. Do **NOT** port the full `CdrProcessor`/`MediationContext`/`MEF`/`CdrJobContext` scaffolding (it's batch/file/EF orchestration routesphere replaces; `RatingRule` is even an empty stub).
- **No Spring.NET / WinSCP** — SpEL was only used for file-name filtering (decode) + FlexValidation; neither is in the SG 10/11 path. Rules become **code (beans)**, not parsed strings.

## Locked contract (with architect)
- gRPC **`FinalizeAndSummarize(callFacts + map<dbName, TierReserved{packageAccountId, uom, reservedAmount}>) → map<dbName, TierSettlement{charged, packageAmount, inPartnerCost, …}>`**.
- **Per-call atomic** = ONE MySQL transaction across the call's tier schemas (all on the same server).
- **Idempotent on `uniqueId`** (routesphere may retry on timeout; mem-ledger settle is a SEPARATE commit).
- **Modes:** admin tier = FULL (customer+supplier, families); reseller tier = CUSTOMER-ONLY A2Z.
- routesphere sends **lean facts + `TierReserved`** (no full cdr); **dotnet builds the per-tier cdr**. routesphere sends `partnerId`. Tenant hierarchy is NOT passed (dotnet derives via `ITenantRegistry.AncestorChain`; but per-call grouping means each call already carries its tier map).
- **CDR batch shape:** the unit is the CALL; one call carries one cdr per tier bundled `map<dbName, cdr>`; a batch is `List<that map>`.

## Binding rules / decisions
- **Single MySqlConnection** shared by EF Core + raw SQL → same transaction (atomicity). Legacy did `DbCmd = CreateCommandFromDbContext(context)`. Port: `conn.BeginTransaction()`; `ctx.Database.UseTransaction(tx)`; `new MySqlCommand(sql, conn, tx)`.
- Ported legacy code keeps **legacy namespaces + type names** (`MediationModel.*`, `TelcobrightMediation.*`) so `Sg*`/`Sf*` port near-verbatim; `#nullable disable` + `NoWarn CS8981` (lower-cased type names).
- Trims (replace-the-tech): EF nav props stripped (partner/ne); `AbstractCdrSummary` SQL-writer methods + `ICacheble` stripped; `CdrExt` `PartialCdrContainer` (decode) + `AccWiseTransactionContainer` (→ mem-ledger) stripped.
- Legacy repo `telcobright-billing-dotnet` is a **read-only reference** (.NET Framework, won't build on Linux).

## The two service groups
| | SG 10 | SG 11 |
|---|---|---|
| class | `SgDomOffnetOut` "Domestic Outgoing [Iptsp/pbx]" | `SgDomOffnetIn` "Domestic Incoming [iptsp/pbx]" |
| claims when | `InPartner.PartnerType == 3` (retail) | `InPartner.PartnerType == 2` (icx) |
| normalizes | terminating/called number | originating/calling number |
| family (customer dir) | SF 10 = `SfA2ZWithVatTax` | SF 11 = `SfDomOffNetInAns` |
| summary tables | `sum_voice_day_03` / `hr_03` | `sum_voice_day_02` / `hr_02` |
| chargeable key | `(sg=10, sf=10, dir=1)` | `(sg=11, sf=11, dir=1)` |

## A2Z charge math (ported in `A2ZCharger`)
- **Duration** (`GetA2ZDuration`): `MinDurationSec` is a **ms threshold** (<0 = actual, >0 = ceil if frac ≥ threshold else floor, =0 = always ceil), then **ceil up to `Resolution` (pulse) multiple**.
- **Amount** (`GetA2ZAmountWithOutSurCharge`): first `SurchargeTime` secs cost a flat `SurchargeAmount`; remaining `× (rateAmount / billingSpanSec)`; round to precision. `billingSpanSec` = 60 for per-minute (from rate plan; param for now, default 60).

## DONE (commits on `pialmmh/billing-dotnetcore` master)
- `@7c87bce` — config-sync: per-tenant `DynamicContext{MediationContext}` from **live** config-manager; **ccl78** loads end-to-end; `GetMaxRatePerMinute` (admission); streaming client + 180s timeout; broker `103.95.96.78:9092`; Kafka backoff; `Config→Adapters`; datasource block; fail-fast.
- `@55ca8f3` — entity/model layer ported → `src/Billing.Mediation/Engine/Models/` (`cdr`, `acc_chargeable`, `partner`, `ne`, `CdrSummaryType`, `ISummary`, `AbstractCdrSummary`, `sum_voice.cs` [day_02/03 + hr_02/03], `CdrMediationResult`) + `Engine/Cdr/CdrExt.cs`.
- `@41aecb1` — `Rate` entity fixed (RateAmount/IdRatePlan/CountryCode/Category/Resolution/MinDurationSec/SurchargeTime/SurchargeAmount); **`A2ZCharger`** (`src/Billing.Mediation/Rating/A2ZCharger.cs`) + 4 golden tests.
- `@a818085` — **Step 1: per-tenant RateCache wiring**. `MediationContext.RateCache` built from `RatePlanWiseTodaysRates` in `ConfigManagerMapper.ToMediation`, today-stamped (built even when the MediationContextDto block is absent). +4 mapper-wiring tests.
- `@746f544` — **Step 2: SG 10/11 detection beans** (`src/Billing.Mediation/ServiceGroups/`): `IServiceGroupDetector`+`ServiceGroupMatch`, `SgDomOffnetOut`(10, retail→terminating), `SgDomOffnetIn`(11, icx→originating), `BdNumberNormalizer` (strip +/0/00880/880880/880; fixed the legacy SG10 +-branch length bug), `ServiceGroupDetection` coordinator (first-to-claim wins) + `Default()` + DI ext. Reads `DynamicContext.Partners`. AnsPrefixFinder deferred (summary-dim). +11 tests.
- `@5af7d12` — **Step 3 (.NET side): rate-plan resolution + basic-charge slice**. `RatePlanAssignmentTuple`+`AssignmentDirection` (Model), `RatePlanResolver` (route-key>partner-key, lowest priority; folded into `MediationContext.RatePlanResolver`, built in mapper), `BasicCharge` orchestrator (detect→resolve→`RateCache.FindRate`→`A2ZCharger`). `MediationContextDto += RatePlanAssignmentTuples`. +9 tests.
- **Build 0/0, 39 tests green.**

## NEXT STEPS (resume here)
- ✅ **Step 1** (RateCache wiring) — done `@a818085`.
- ✅ **Step 2** (SG10/11 detection) — done `@746f544`.
- 🟡 **Step 3** — **.NET side done** `@5af7d12` (resolver + basic charge). **PENDING: config-manager (Java) must serve `MediationContext.ratePlanAssignmentTuples`** matching the .NET contract `RatePlanAssignmentTuple{ id, idService, assignDirection(1=Cust,2=Supp), idPartner?, route?, priority, idRatePlan }`. Handed to architect `@5af7d12` for ratification (3 Qs): **Q1** idRatePlan-per-tuple (legacy reaches plan via `rateassigns[].idrateplan` — propose config-manager resolves one per tuple); **Q2** idRatePlan == a key of `ratePlanWiseTodaysRates` (= `rateplanassign.id`) so `RateCache.FindRate` hits; **Q3** does it supersede the already-served `RateAssignsCustomer/Supplier` (which lack idService) for SG-aware resolution. Pull config-manager `main @af98faf` (architect-authored base; DO NOT recreate). mvn needs JDK 21. **Until ratified, do not unilaterally change the tuple shape.**
- ⬜ **Step 4a — acc_chargeable + summary**: from `BasicChargeResult` build `acc_chargeable` (key `(sg, sf, dir=1)`: SG10→sf10, SG11→sf11) and `SetServiceGroupWiseSummaryParams` → `sum_voice_day_03/hr_03` (SG10) / `_02` (SG11). Port from legacy `SgDomOffnet*.SetServiceGroupWiseSummaryParams` (read in this session) + `AccChargeableExt`. Needs AnsPrefixFinder for the summary's `tup_countryorareacode`/`tup_destinationId` (or stub those first).
- ⬜ **Step 4b — `FinalizeAndSummarize` gRPC handler**: idempotent on `uniqueId`; per-call atomic = ONE `MySqlConnection` (EF Core + raw SQL share it + one tx) across the call's tier schemas; two-mode loop (admin = FULL customer+supplier+families; reseller = CUSTOMER-ONLY A2Z); `map<dbName,TierReserved>` in → `map<dbName,TierSettlement>` out; dotnet builds the per-tier cdr.

## Key legacy reference files (`/home/mustafa/telcobright-projects/telcobright-billing-dotnet`)
- per-CDR loop: `Mediation/Cdr/CdrProcessor.cs` `Mediate()` (L70) + `ExecuteServiceGroups()` (L287); job runner `CdrJob.Execute()` (`Mediation/Cdr/CdrJob.cs`).
- SG: `_ServiceGroups/SgDomOffnetOut.cs` (10), `SgDomOffnetIn.cs` (11). SF: `_ServiceFamilies/SfA2Z.cs`, `SfA2ZWithVatTax.cs` (10), `SfDomOffNetInAns.cs` (11).
- rating math: `Mediation/Rating/A2ZRater.cs` `ExecuteA2ZRating` (L46) → `Mediation/Rating/PrefixMatcher.cs` `GetA2ZDuration` (L194) + `GetA2ZAmountWithOutSurCharge` (L261).
- config: `Mediation/Config/CdrSetting.cs`, `ServiceGroupConfiguration.cs`; the SG↔rateplan config lives in EXISTING DB tables `rateplanassignmenttuple`/`rateassign`/`billingruleassignment` (CRUD-configured), NOT a new table.
- the `List<cdr>` → mediation seam = `CdrExtFactory.CreateCdrExtWithNonPartialOrFinalInstance(cdr)` → `CdrCollectionResult` → `CdrProcessor`. (decode/uniqueId upstream, ignored.)

## Environment / coordination
- config-manager **live**: `http://103.95.96.78:7072` (HTTP) + `103.95.96.78:9092` (Kafka; topic `config_event_loader_ccl78`, 3 partitions). MySQL `103.95.96.77:3306`. Tenant **ccl78** (root dbName `telcobright`, children `res_233`/`res_233_2`/`res_225`).
- dotnet 8.0.128; config-manager mvn needs JDK 21 (`/home/mustafa/.sdkman/candidates/java/21.0.9-amzn`); config-manager git remote `pialmmh/config-manager.git`, branch `main`.
- Shared dev log: `/tmp/shared-instruction/routesphere-activity.ndjson`. My role = **dotnet**. Card-update each task: `devlog.sh card "<summary>" "" ""`. Handoffs: `devlog.sh handoff architect "<text>"`.
- Architect lives on routesphere side; mem-ledger (balance/reserve) stays in routesphere; dotnet does charge calc + cdr/summary.
