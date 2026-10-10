# ARCH-0077-C — billing-core: service group 101 "Domestic WiFi", a STATED pre-rated group like the ad's 30

**To:** the billing-core agent (worktree `telcobright-billing-core/worktrees/wifi-101`, branch `wifi-101` off `postgres-ad-call` dc5a70b) · **From:** the architect.
**Why:** the owner (2026-10-10): "use a different service group e.g. 101 (domestic wifi). make sure that we generate the cdr and summary input data exactly like call,
same entities like cdr, then in summary service sum_voice_day_xx." and "disable rateplan looking for now." The WiFi switch (on the session base) settles each tier
itself from its package buckets and states the group on the record; billing-core takes the charge from the row, as it does for the ad's 30 — never a rate plan.

## 1 · The items (one commit each; every rule broken once and seen red; push after each)

| # | item | the rule |
|---|---|---|
| 1 | **`servicegroups/SgWifiDomestic`** — `Id = 101`, `RuleName "Domestic WiFi"`, `IsStatedBy(Integer)` / `Is(int)` as `SgAdView` has them; it is a STATED group: no detector runs for it | — |
| 2 | **The preprocessor admits a stated 101**: `CdrEventPreprocessor.StatesAnUnknownServiceGroup` admits {30, 101}; the words of the refusal name both ("a record may state 30 or 101; absent or 0 = billing detects the group"); the mapped `c.ServiceGroup` keeps 101; `ChargingStatusOf` for 101 = the VOICE rule (`durationSec > 0` → 1), not the ad's "shown" | tests: a stated 101 passes, a stated 100 is refused in words, a 101 row with 0 s has ChargingStatus 0 |
| 3 | **`ServiceGroupConfiguration.Defaults` gains 101**: `RatingRule(SfPreRated, customer direction)` + the voice's checklists (charged: InPartnerIdGt0, DurationSecGtEq0, EndTimeIsGtEqStartTime; unanswered: InPartnerIdGt0, EndTimeIsGtEqStartTime); `WithTheBuiltInAdView` becomes "with the built-in STATED groups" — 30 and 101 are always present, a served map may override either | a tenant map without 101 still charges a 101 row; a served 101 wins |
| 4 | **`BasicCharge.Rate`** takes the pre-rated path for 101 exactly as for 30 (`ChargeAsSettled`): ONE customer chargeable from the row — `SfPreRated`: `BilledAmount` = `PackageAmount` when the tier paid in units (> 0) else `InPartnerCost`; `idBilledUom` = `InPartnerUom` (TF_min for a bucket); `RatePlanResolver` / `PrefixMatcher` NEVER run for 101 (a test with no rate plan assignment at all charges the row) | red first: without the change a 101 row goes to the detectors / is refused |
| 5 | **The cdr row** is the voice's `cdr` entity on the tier's schema with `ServiceGroup = 101` — the same writer, the same transaction (cdr + acc_chargeable + summary_affected), the same ping; the idempotency key as today (ChannelCallUuid + tenant) | an end-to-end test through `MultiTenantCdrProcessor` on the PostgreSQL lab: a 101 record in = 1 cdr row, 1 acc_chargeable (servicegroup 101, idBilledUom TF_min, BilledAmount = the minutes), 1 outbox row |
| 6 | **The Kafka contract page** (`docs/cdr-kafka-ingest-contract.md`) gains 101: the fields a WiFi record carries (tenant, resellerHierarchy, callId, channelCallUuid, serviceGroup 101 STATED, originatingCalledNumber = terminatingCalledNumber = `W<segment>`, originatingCallingNumber = the msisdn, startTime/answerTime/endTime, durationSec, hangupCause, isPrepaid 1, inPartnerId = the subscriber's tree partner, inPartnerUom, idPackageAccount, packageAmount, inPartnerCost, callRatePerMinBDT as reference, incomingRoute = the site/gateway; null and harmless: outPartnerId, supplier fields, codec, pdd, ans ids) | — |
| 7 | **Report `BC-0007-done.md`**: the reds, the clean-copy suite (`git archive`, fresh private repository) on both engines as BC-0005 ran them, the commits | — |

**Not this round:** a WiFi kinds module (void — 101 is the pre-rated shape under the voice row); any rate-plan path for 101; the bed.

## 2 · Rules that stand

Java 21; YOUR private Maven repository chained read-only (`-Dmaven.repo.local=$HOME/.m2/wifi-101-repo -Dmaven.repo.local.tail=$HOME/.m2/repository`); `date` before any
time; the trailer `Co-Authored-By: <the model you run as> <noreply@anthropic.com>`; push after every item (`origin wifi-101`); the lab's PostgreSQL and MySQL are LOCAL
(127.0.0.1, credentials by NAME from the environment, never in a file or a command line); never start the jar bare (a bundled default tenant reaches LIVE — the rule since
2026-10-02); every lab binds 127.0.0.1 and prints its endpoints first; WireGuard is UP — never reach 172.17.191.1, 10.10.191.x, 10.10.188.x (the radius-2 BED), 10.10.175.x,
10.9.9.x or 103.95.96.77; a test that fails for an unreachable service: stop and report; nothing left running; the merge is the architect's; never `cd` into another
agent's worktree; the house style.
