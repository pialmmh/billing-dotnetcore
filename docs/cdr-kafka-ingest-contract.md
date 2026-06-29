# CDR Kafka ingest + multi-tenant fan-out — contract & design

How rated CDRs flow from **routesphere (producer)** into **billing-core (consumer)** and get written, per
reseller layer, into each layer's own schema — in one atomic transaction.

> Status: **PROPOSED** — billing-core (consumer) side authored here. The routesphere (producer) side must
> conform; the architect ratifies the wire contract (§3). Open items in §9.

---

## 1. The shape of the flow

routesphere mediates a call live and **already fans it out per reseller layer and rates each layer's
customer leg**. It emits, per call, an **array of per-tier records**, each self-describing:

- `tenant` — the schema that record belongs to (`telcobright`, `res_233`, …),
- `resellerHierarchy` — `admin > … > self` (last node == `tenant`; parent's is a prefix of child's),
- that tier's partner / rate / package / amounts.

billing-core does **not** derive the chain or re-resolve rates. It maps each record to the engine's `cdr`,
groups by `tenant`, and runs the existing single-tenant pipeline once per tier — all tiers of a poll-batch
committing together across schemas.

```
routesphere ──▶ Kafka topic `cdr_rated` (key=channelCallUuid, value=CdrEvent[] for one call)
                        │
                        ▼
        ┌─────────────────────────────┐
        │ CdrKafkaConsumer            │  poll batch · offset mgmt · dead-letter
        └──────────────┬──────────────┘
                        ▼
        ┌─────────────────────────────┐
        │ CdrEventPreprocessor        │  decode → validate → map CdrEvent→cdr →
        │  (pure, unit-testable)      │  group by tenant → attach each tenant's
        └──────────────┬──────────────┘  MediationContext+Partners → MultiTenantCdrBatch
                        ▼
        ┌─────────────────────────────┐
        │ MultiTenantCdrProcessor     │  ONE connection · ONE tx across schemas
        │                             │  for each tenant: CdrPipeline.Process →
        │                             │    insert <tenant>.cdr/.acc_chargeable/.sum_voice_*
        └──────────────┬──────────────┘  COMMIT (or ROLLBACK all) → then commit Kafka offsets
                        ▼
        per-layer rows land in each layer's own schema; reseller reads its own DB
```

Three components, clean SRP: **Consumer** (IO), **Preprocessor** (pure transform — the piece this task adds),
**Processor** (cross-schema write). The ported `CdrPipeline`/`BasicCharge`/`CdrSummaryContext` are reused as-is.

---

## 2. Contract A — Kafka wire (producer ⇄ consumer)

| Field | Value |
|---|---|
| **Topic** | `cdr_rated` (v1) — single stream (a call spans tenants, so NOT per-tenant) |
| **Key** | `channelCallUuid` (UUID string) — one partition per call → per-call ordering + dedup |
| **Value** | UTF-8 JSON **array** = all per-tier records of ONE call (`CdrEvent[]`) |
| **Delivery** | at-least-once; consumer commits offsets **after** the DB tx commits |
| **Ordering** | per-call (guaranteed by key); cross-call order not required |

### CdrEvent (one tier record) — value schema
| field | type | req | notes / maps to `cdr` |
|---|---|---|---|
| `tenant` | string | ✓ | target schema (routing). Must == last node of `resellerHierarchy` |
| `resellerHierarchy` | string | ✓ | `admin > … > self`, ` > ` separator → `cdr.ResellerHierarchy` (NEW col) |
| `sequenceNo` | long | ✓ | `cdr.SequenceNumber` · **idempotency key within a schema** |
| `callId` | string | ✓ | `cdr.UniqueBillId` (call correlation across schemas) |
| `channelCallUuid` | string | ✓ | `cdr.ChannelCallUuid` (NEW col) + Kafka key |
| `startTime` `answerTime` `endTime` | datetime `yyyy-MM-dd HH:mm:ss` | ✓ | `StartTime` `AnswerTime` `EndTime` |
| `durationSec` | decimal | ✓ | `DurationSec` |
| `originatingCallingNumber`/`terminatingCallingNumber` | string | ✓ | `OriginatingCallingNumber`/`TerminatingCallingNumber` |
| `originatingCalledNumber`/`terminatingCalledNumber` | string | ✓ | `OriginatingCalledNumber`/`TerminatingCalledNumber` |
| `callerIp` / `receiverIp` | string | | `OriginatingIP` / `TerminatingIP` |
| `hangupCause` | string | | `cdr.HangupCause` (NEW col) |
| `channelReadCodecName` | string | | `Codec` |
| `pdd` | float | | `PDD` |
| `inPartnerId` / `outPartnerId` | int | ✓ | `InPartnerId` / `OutPartnerId` |
| `isPrepaid` | int | | `PrePaid` (1=prepaid, 2=postpaid — **confirm**) |
| `matchPrefixCustomer` | string | | `MatchedPrefixCustomer` |
| `supplierPrefix` | string | | `MatchedPrefixSupplier` |
| `ansIdTerm`/`ansPrefixTerm`/`ansIdOrig`/`ansPrefixOrig` | int/str | | `AnsIdTerm`/`AnsPrefixTerm`/`AnsIdOrig`/`AnsPrefixOrig` |
| `callRatePerMinBDT` | decimal | ✓ | `CustomerRate` — the per-min rate this tier charges (already resolved) |
| `inPartnerUom` | string | ✓ | `cdr.InPartnerUom` (NEW col) — `BDT`=cash, `TF_min`=package, `OTH_ea`=per-event |
| `idPackageAccount` | long | | `cdr.IdPackageAccount` (NEW col) |
| `inPartnerCost` | decimal | ✓ | `InPartnerCost` — computed **cash** customer charge (0 when package) |
| `packageAmount` | decimal | ✓ | `cdr.PackageAmount` (NEW col) — billed **package** units (0 when cash) |
| `supplierCost` | decimal | | `OutPartnerCost` (**confirm** target) |
| `costIcxIn` / `costAnsIn` | decimal | | `CostIcxIn` / `CostAnsIn` (carried verbatim) |
| `revenueAnsOut` / `revenueIgwOut` | decimal | | `RevenueAnsOut` / `RevenueIgwOut` (carried verbatim) |

**New `cdr` columns** (extend, don't replace the engine model): `ResellerHierarchy`, `ChannelCallUuid`,
`HangupCause`, `InPartnerUom`, `IdPackageAccount`, `PackageAmount`. Everything else maps to existing fields.

---

## 3. Contract B — Preprocessor → Processor (in-process)

The preprocessor emits one object per poll-batch:

```
MultiTenantCdrBatch {
    List<PerTenantCdrs> tenants;        // grouped by tenant (one entry per distinct schema in the batch)
}
PerTenantCdrs {
    String   tenant;                    // target schema
    Tenant   context;                   // registry.FindByDbName(tenant): MediationContext + Partners
    List<cdr> cdrs;                     // shaped engine cdrs for this tenant (each tagged ResellerHierarchy)
}
```

Preprocessor responsibilities (pure, no IO — fully unit-testable):
1. **decode** value → `List<CdrEvent>` (Jackson, case-insensitive, JSR-310 dates).
2. **validate**: required fields present; `resellerHierarchy` last node == `tenant`; `tenant` exists in the
   registry. Optional: chain completeness (the leaf record's hierarchy lists exactly the tiers present).
3. **map** each `CdrEvent` → `cdr` per Contract A.
4. **group** by `tenant`; **attach** each tenant's `context` from the registry.
5. Bad/unmappable records → **dead-letter** (don't poison the batch).

The processor consumes `MultiTenantCdrBatch` (§4).

---

## 4. Contract C — write model (the atomic fan-out)

- **ONE connection** to the MySQL server (cross-schema), **ONE transaction per poll-batch**. Per-call
  atomicity is a guaranteed subset (the whole batch is all-or-nothing). Honours the architect's
  "one batch = one commit" rule, extended across schemas.
- For each `PerTenantCdrs`: run `CdrPipeline.Process(CdrBatch{context.MediationContext, context.Partners,
  cdrs, store, schema=tenant})`. The writers emit **schema-qualified** SQL (`res_233.cdr`,
  `res_233.acc_chargeable`, `res_233.sum_voice_*`).
- `COMMIT` → only then commit Kafka offsets. On any error: `ROLLBACK` all, do **not** advance offsets
  (message is re-delivered; idempotency makes the replay safe).
- **Idempotency**: `cdr` unique key on `SequenceNumber` (unique per tier-record); writes use
  INSERT-ignore / upsert so a redelivery does not double-insert or double-count summaries.

Write-layer change needed: thread a `schema` (table prefix) through `CdrBatch` so `CdrWriter` /
`ChargeableWriter` / `CdrSummaryContext` qualify table names; open one cross-schema connection (the DB user
needs INSERT on every tenant schema).

---

## 5. Per-tier rating — billing does the FINAL rating (this is the whole point)

Every call is rated **twice**:

| When | Who / how | Duration | Result |
|---|---|---|---|
| **Admission** (live) | routesphere → us via `GetMaxRatePerMinute`; we resolve the per-minute rate from the tier's rate plan | 1 minute (block) | routesphere **reserves** balance per minute |
| **Final** (this flow) | end of call → Kafka → us; we re-run the customer A2Z rule per tier | **actual** duration (fractional sec) | the **authoritative** per-tier cost |

So the pipeline **RE-RATES**: detect service group → run the single customer-A2Z rule → resolve the rate from
each tier's rate plan → apply it to the **actual** duration. Customer leg only, first SG rule only. The amounts
carried in the event (`callRatePerMinBDT`, `inPartnerCost`) are routesphere's **reservation estimates** — kept
for reference/reconciliation, **not** used as the charge.

Needs each tier's rate plan loaded (the same resolution admission uses) — the live rate-form blocker
(config-manager tuples) must be cleared for pricing to actually produce a number.

---

## 6. Delivery semantics summary
- **At-least-once** + **idempotent writes** (dedup on `SequenceNumber`) = effectively-once billing.
- **Offsets after commit**; rollback ⇒ replay.
- **Dead-letter** topic `cdr_rated_dlq` for records that fail validation/mapping (poison-message safety).
- **Ordering** per call via `channelCallUuid` key.

---

## 7. Component placement (code)
| Component | Package | Role |
|---|---|---|
| `CdrKafkaConsumer` | `beans` (or `ingest`) | the real "cdr ingest loop" (replaces the stub in `CdrProcessor.StartAsync`) |
| `CdrEvent` (+ nested) | `ingest/dto` | the wire DTO (Contract A) |
| `CdrEventPreprocessor` | `ingest` | pure transform → `MultiTenantCdrBatch` (Contract B) |
| `MultiTenantCdrProcessor` | `beans` | cross-schema tx fan-out (Contract C) |
| `CdrPipeline` etc. | `mediation/*` | unchanged single-tenant engine, run per tier |

gRPC `ProcessCdrBatch` stays as a **single-tenant test entry** (or is realigned later) — Kafka is the real path.

---

## 8. Open items (please confirm)
1. **Topic name** — `cdr_rated`? (and DLQ `cdr_rated_dlq`)
2. **Final-cost feedback** (§10) — topic name `cdr_final_cost`? one message per call carrying all tiers?
3. **Mapping targets** to confirm: `supplierCost → OutPartnerCost`?; `packageAmount` as a NEW column (vs reuse `XAmount`/`YAmount`/`ZAmount`)?; `isPrepaid` 1=prepaid/2=postpaid?
4. **Message framing** — value is an array = ONE call's tiers (confirmed by your examples)? Or could a message carry multiple calls?
5. **Idempotency key** — `SequenceNumber` unique per record (good), or prefer `channelCallUuid`+`tenant`?

## 9. Routesphere ratification
The architect must ratify §2 (inbound `cdr_rated`) **and** §10 (outbound `cdr_final_cost`) so the routesphere
producer/consumer and billing-core agree. Until ratified this is the billing-core-proposed contract.

## 10. Outbound — final cost back to routesphere (the reimbursement loop)

routesphere reserves **per 1-minute block** at admission; the real call is fractional seconds. After final
rating, billing **publishes the final per-tier cost back to routesphere**, which finalizes the balance and
**refunds the over-reservation**.

```
admission:  reserve = rate × 60s         (per minute, per tier)
final:      cost    = rate × actualSec    (billing computes, this flow)
routesphere refund  = reserve − cost      (per tier)
```

| | |
|---|---|
| **Topic** | `cdr_final_cost` (billing → routesphere) |
| **Key** | `channelCallUuid` |
| **Value** | one call, all tiers' final costs (sample C below) |

billing therefore has **two** outbound publishes after a batch commits: (1) the existing **summary trigger**
(`cdr_summary_ping` + the outbox) → summary-service; (2) this **final cost** (`cdr_final_cost`) → routesphere.
routesphere matches each tier by `channelCallUuid` + `partnerId` to its reservation and reimburses.

## 11. Sample payloads — Call 1 (outgoing, 2 tiers)

### A — routesphere → Kafka `cdr_rated`  (key = channelCallUuid; value = the call's tier records)
```json
[
  { "tenant":"res_233", "resellerHierarchy":"telcobright > res_233",
    "sequenceNo":1881104, "callId":"2-169791@103.95.96.78",
    "channelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
    "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
    "durationSec":2.055,
    "originatingCallingNumber":"09646999999","originatingCalledNumber":"8801789896378",
    "callerIp":"103.95.96.78","receiverIp":"103.95.96.98",
    "inPartnerId":1,"outPartnerId":234,"isPrepaid":1,
    "matchPrefixCustomer":"880","callRatePerMinBDT":0.350,"inPartnerUom":"BDT","idPackageAccount":1,
    "inPartnerCost":0.012,"packageAmount":0.0,
    "ansIdTerm":23,"ansPrefixTerm":"88017","channelReadCodecName":"PCMU","pdd":2.447 },

  { "tenant":"telcobright", "resellerHierarchy":"telcobright",
    "sequenceNo":1881105, "callId":"2-169791@103.95.96.78",
    "channelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
    "durationSec":2.055,
    "inPartnerId":233,"outPartnerId":234,"isPrepaid":2,
    "matchPrefixCustomer":"880","callRatePerMinBDT":0.400,"inPartnerUom":"BDT","idPackageAccount":236,
    "inPartnerCost":0.013,"packageAmount":0.0 }
]
```
(other call facts as per the full CdrEvent schema in §2; `callRatePerMinBDT`/`inPartnerCost` are admission
reservation values, not the final charge.)

### B — preprocessor → per-tier pipeline  (shaped engine `cdr` for the `res_233` tier)
```jsonc
{
  "SequenceNumber":1881104, "UniqueBillId":"2-169791@103.95.96.78",
  "ChannelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
  "ResellerHierarchy":"telcobright > res_233",
  "StartTime":"2026-06-17T13:34:43","AnswerTime":"2026-06-17T13:34:54","EndTime":"2026-06-17T13:34:56",
  "DurationSec":2.055,
  "OriginatingCallingNumber":"09646999999","OriginatingCalledNumber":"8801789896378",
  "OriginatingIP":"103.95.96.78","TerminatingIP":"103.95.96.98",
  "InPartnerId":1,"OutPartnerId":234,"PrePaid":1,
  "MatchedPrefixCustomer":"880","AnsIdTerm":23,"AnsPrefixTerm":"88017","Codec":"PCMU","PDD":2.447,
  "InPartnerUom":"BDT","IdPackageAccount":1,
  "CustomerRate":0.350          // reference (admission); the pipeline RE-RATES on DurationSec
  // ServiceGroup → detected by the pipeline.
  // final CustomerRate + InPartnerCost + acc_chargeable → produced by the customer-A2Z rule on
  // DurationSec=2.055 — NOT copied from the event.
}
```
The processor runs this with `res_233`'s MediationContext + Partners and writes `res_233.cdr`,
`res_233.acc_chargeable`, `res_233.sum_voice_*` (schema-qualified, inside the one cross-schema tx).

### C — billing → Kafka `cdr_final_cost`  (the missing piece — final cost per tier)
```json
{
  "channelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
  "callId":"2-169791@103.95.96.78",
  "tiers":[
    {"tenant":"res_233",    "partnerId":1,  "uom":"BDT","finalCost":0.012, "packageAmount":0.0,"billedDurationSec":2.055},
    {"tenant":"telcobright","partnerId":233,"uom":"BDT","finalCost":0.0137,"packageAmount":0.0,"billedDurationSec":2.055}
  ]
}
```
routesphere: `refund(tier) = reserved_per_minute − finalCost` — e.g. `res_233`: `0.350 − 0.012 = 0.338`.
