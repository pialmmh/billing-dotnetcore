# End-to-end testing from Postman (gRPC) + debugging

How to run `telcobright-billing-core` against the **live config-manager**, send a demo **multi-level** call
from **Postman (gRPC)**, and debug it. **Everything below was verified live on 2026-06-25** (real responses
are inlined).

Two RPCs (`src/Billing.Service/Protos/billing.proto`, service `telcobright.billing.v1.RatingService`):
- **`GetMaxRatePerMinute`** — pre-call admission; a candidate **per tier** of the tenant chain.
- **`FinalizeAndSummarize`** — post-call; the **per-level settlement** (compute-only today — see §7).

---

## 1. Config is already wired (no change needed)
The config-manager URL + tenant live in the profile files (NOT `appsettings.json`):
- `config/tenants.yml` → `name: ccl78, enabled: true, profile: dev`
- `config/tenants/ccl78/dev/profile-dev.yml` → `config-manager.base-url: http://103.95.96.78:7072`.

On startup the service **fail-fast** loads the tenant tree (+ `MediationContext`) from config-manager;
if it's unreachable the service refuses to start. **Verified:** from this machine the load is `200 OK`
and yields the chain below. (To use prod config-manager `10.9.9.2:7072`, set `profile: prod`.)

## 2. Run the service
```bash
cd telcobright-billing-core
dotnet run --project src/Billing.Service --launch-profile http
```
Listens on **`http://localhost:5293`** — HTTP/2 **plaintext (h2c)**; Postman gRPC connects with **TLS OFF**.
Look for `Now listening on: http://localhost:5293` and `N tenant(s) loaded`.

## 3. The real tenant chain + partner ids (ccl78, verified)
```
telcobright   (admin / root)   partners: 336, 338, 114, 232, 233, 234, …
 ├─ res_233   (reseller)       partners: 1, 2
 │   └─ res_233_2 (sub-reseller) partners: 236, 337
 └─ res_225   (reseller)       partners: (none)
```
So a 3-level demo = leaf **`res_233_2`** → `res_233` → `telcobright`. `levels` carry the partner per tier by
**depth**: `0 = telcobright (root)`, `1 = res_233`, `2 = res_233_2 (leaf)`.

## 4. Set up Postman (gRPC)
1. **New → gRPC Request**. 2. Server URL `localhost:5293`, **TLS OFF**. 3. **Import a .proto file** →
`src/Billing.Service/Protos/billing.proto` (server reflection isn't enabled). 4. Service
`telcobright.billing.v1.RatingService` → method. 5. Paste a body below, **Invoke**.
(`service_type` = enum by name `VOICE`/`SMS`; `start_epoch_millis` = int64 **as a string**.)

---

## 5. `GetMaxRatePerMinute` (multi-tier admission) — request + REAL response
```json
{
  "tenant": "res_233_2",
  "partner_id": 236,
  "calling_number": "8801711000000",
  "called_number": "8801712345678",
  "source_ip": "10.0.0.1",
  "service_type": "VOICE",
  "start_epoch_millis": "1750000000000",
  "levels": [
    { "depth": 0, "partner_id": 336 },
    { "depth": 1, "partner_id": 1 },
    { "depth": 2, "partner_id": 236 }
  ]
}
```
Actual reply (verified): the 3-tier chain resolves, **SG10 detected at every tier**, **package candidates
come back** (from config-manager's `partnerIdWisePackageAccounts`), but **no cash rate** → the `telcobright`
tier rejects, so the call rejects:
```json
{
  "rejectReason": "no rate or package for the call",
  "tiers": {
    "res_233_2":   { "serviceGroup": 10, "candidates": [
                      { "packageAccountId": "2", "uom": "TF_min", "maxAmountFirstMinute": 1 },
                      { "packageAccountId": "1", "uom": "BDT",    "maxAmountFirstMinute": 1 } ] },
    "res_233":     { "serviceGroup": 10, "candidates": [
                      { "packageAccountId": "1", "uom": "BDT", "maxAmountFirstMinute": 1 } ] },
    "telcobright": { "serviceGroup": 10, "rejectReason": "no rate or package for the call" }
  }
}
```
Omit `levels` to rate only the leaf (ancestors fall back to partner 0). A `ratePerMinute` value appears on a
cash candidate **once the rate tuples are served** — see §8.

## 6. `FinalizeAndSummarize` (multi-level CDR, post-call) — request + REAL response
```json
{
  "facts": { "tenant": "res_233_2", "service_type": "VOICE", "caller_number": "8801711000000",
             "called_number": "8801712345678", "incoming_route": "in", "outgoing_route": "out",
             "switch_id": 1, "session_id": "sess-demo-1", "sip_call_id": "callid-demo-1",
             "start_epoch_millis": "1750000000000" },
  "levels": [ {"depth":0,"partner_id":336}, {"depth":1,"partner_id":1}, {"depth":2,"partner_id":236} ],
  "answer_epoch_millis": "1750000005000", "end_epoch_millis": "1750000065000",
  "billsec": 60, "answered": true, "reserved_amount": 5.0
}
```
Actual reply (verified) — settlement per level; unrated for the same reason (no rate tuple):
```json
{ "error": "unrated: no service group, rate plan, or matching rate",
  "settlements": [ {"depth":2,"partnerId":236}, {"depth":1,"partnerId":1}, {"partnerId":336} ] }
```
With rates served, each settlement carries `uom / chargedAmount / serviceGroupId / serviceFamilyId /
matchedPrefix` and `totalCharged` is set.

## 7. Debugging
Run under a debugger (VS Code C# Dev Kit / Rider / VS — run profile `Billing.Service` http; or attach to the
`dotnet` process). Breakpoints, top-down:

| Where | File |
| --- | --- |
| RPC entry + chain build | `BillingServiceImpl.GetMaxRatePerMinute` / `BuildChain` / `FinalizeAndSummarize` |
| per-tier rating (admission) | `MaxRateTierRater.RateTier` → `BasicCharge.MatchCustomerRate` |
| SG detection | `ServiceGroupDetection.Detect` |
| rate lookup (RateCache + PrefixMatcher) | `BasicCharge.MatchRate` |
| per-level settle (finalize) | `FinalizeEngine.Finalize` → `BasicCharge.Compute` / `Rate` |

Inspect: the resolved `AncestorChain`, each tier's `MediationContext.RatePlanResolver` / `RateCache`
(empty today), `ServiceGroupConfigurations` (in-code Defaults), and `Partners`.

## 8. The integration gap (THIS is why there's no price yet)
**Not** a config-manager URL problem — the service connects fine and the chain + SG detection + **packages**
all work live. The gap is the **rate form**:

- config-manager serves rates under `context.*` (the DynamicContext form): `ratePlanWiseTodaysRates` (8),
  `rateAssignsCustomer` (15), `rateAssignsSupplier` (2), and packages `partnerIdWisePackageAccounts` (2).
- Its `mediationContext` carries only `categories` (9) + `serviceGroupRules` (0) — **no
  `ratePlanAssignmentTuples`**.
- Our faithful RateCache rater builds `RatePlanResolver` + `RateCache` **from
  `mediationContext.ratePlanAssignmentTuples` + nested rateassigns** → which are absent → empty → **no cash
  rate** → `"no rate or package for the call"`.

**To close it, pick one:**
1. config-manager also emits `mediationContext.ratePlanAssignmentTuples` (each with nested `rateassigns`) —
   the legacy-shaped tuple form our port consumes; **or**
2. billing-core maps the already-served `rateAssignsCustomer/Supplier` (+ `ratePlanWiseTodaysRates`) into the
   tuple/rateassign shape the `TupleRateLoader`/`RateCache` expect.

`serviceGroupConfigurations` + validation checklists fall back to in-code `ServiceGroupConfiguration.Defaults`
(SG10/11), so the rule set is present regardless. **`FinalizeAndSummarize` is compute-only** today
(`cdr_written`/`summary_written` = false); the atomic cdr/summary WRITE is the batch `CdrProcessor` /
`MySqlCdrBatchRunner`, not yet hosted on a gRPC method.
