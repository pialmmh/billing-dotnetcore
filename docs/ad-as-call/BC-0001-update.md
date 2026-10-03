from: BC  to: ARCH  kind: update  number: 0001  date: 2026-10-04 03:54 +06:00  branch: postgres-ad-call  head: ec9e0ab (origin/master; nothing built yet)

# BC-0001 — the brief read against the code, before I build

I read the instruction page, the brief (B1–B10), the design (§3, §4, §4.1, §5), the wire's JSON schema and its contract test,
my own contract page, prime-context's `postgres-tenancy.md`, ad-sphere's `JdbcCdrReader` and `PcBillingStandIn`, seed-callflow's
`CdrEvent` + `CdrAssembler`, and the Java port (`java/`, ~19k lines).

**Baseline** (origin/master ec9e0ab, `mvn -f java/pom.xml test`): 227 tests, 0 failures, 0 errors, 5 skipped. The 5 skipped are
the env-gated live tests (`SummaryCdrDump`, `SummaryPartitionDiag`, `SummaryRollupFixAndFold`, `SummaryRollupLive`,
`ConfigManagerLiveRate`). The MySQL integration tests RAN (6 tests in 3 classes) — see W16.

Nothing below is built yet. Each item says what the code does, what the brief asks, and what I will do. If a "what I will do"
is wrong, say so and I change it — I do not wait for an answer on any of them, except the two marked **ASK**.

---

## 1 · What is wrong, or missing, against the brief

### The wire (B1)

| # | the code today | the brief / the design | what I will do |
|---|---|---|---|
| W1 | `CdrEventPreprocessor.Validate` REQUIRES `inPartnerId`, `outPartnerId`, `callRatePerMinBDT`, `inPartnerUom`. A record without one is dead-lettered | the ratified schema makes these four OPTIONAL. seed-callflow's `CdrAssembler.unadmittedRecord` (a view nobody was admitted for) sends NO rate, NO uom, NO prefix, NO account — and no `outPartnerId` when the tenant has no out-partner setting | **This is a defect, not a detail: today EVERY refused view would be dead-lettered**, so B3's "a failed view gives a cdr row" could never hold. I make the four optional. A record with no partner then reaches mediation and goes to `cdrerror` by the checklist (B4), as the brief asks |
| W2 | a dead letter is only LOGGED (`CdrKafkaConsumer`, a TODO), and its offset is committed. The record is gone | the ratified wire names `cdr_dlq_<root tenant>` | I publish each dead letter to the profile's `dead-letter-topic` before the offsets are committed. Best effort: a failed publish is one ERROR line and the batch still commits — the same loss as today, never a stalled ingest. Say if you want the other choice (hold the batch until the dead letter is published) |
| W3 | `auto.offset.reset = latest`, hard-coded | "a new consumer group starts at `earliest` (a profile value)" | new key `billing.cdr-ingest.auto-offset-reset`, default `earliest`. The two `ccl78` profiles get `latest` written in, so their behaviour does not change |
| W4 | `hangupCause → AreaCodeOrLata`; `variableSipCallId → AdditionalMetaData`; `IncomingRoute = receiverIp`, `OutgoingRoute = callerIp` (the live call feed's mappings) | §4.1: `hangupCause → HangupCause`, `additionalMetaData → AdditionalMetaData`, `incomingRoute / outgoingRoute → IncomingRoute / OutgoingRoute` | the new field wins when it is sent; the old mapping stays as the fallback, so the live call feed is unchanged. `hangupCause` is written to BOTH `AreaCodeOrLata` (as today) and the new `HangupCause` column |
| W5 | `serviceGroup` is not on the DTO; `ServiceGroupDetection.Detect` sets `ServiceGroup = 0` first, always | absent or 0 = detect; 30 = taken as given | 30 is taken as given and never reaches a detector. Any other value is treated as absent (detected), exactly as today — the contract rules only 0 and 30. Say if another value should be refused |

### Group 30 (B2–B4)

| # | the code today | what I will do |
|---|---|---|
| W6 | `ServiceGroupConfiguration.Defaults` has 10 and 11 only. A served `serviceGroupConfigurations` map REPLACES the defaults wholesale (`ConfigManagerMapper.ToMediation`) | group 30 joins the defaults (one pre-rated customer rule + the two checklists of B4). A served map without a 30 entry gets the default 30; a served 30 overrides it. 10 and 11 are untouched (a served map without 10 still has no 10 — unchanged). prime-context serves no service-group configuration at all, so on PostgreSQL the defaults are what runs |
| W7 | `ChargingStatus = durationSec > 0 ? 1 : 0` (a proxy: the live call feed sends no answer time on failed legs). It picks the answered / unanswered checklist and is the summary's `successfulcalls` | for group 30 only: `ChargingStatus = answerTime != null` ("answered = shown", design §2). Calls keep the proxy |
| W8 | the service families all take a matched `Rateext` and stamp the cdr from it (`FamilyStamp`): rate, prefix, cost, durations | a new family for group 30 that takes NO rate and stamps nothing: the cdr row keeps the wire's amounts. It builds one customer `acc_chargeable`. My mapping, where the brief names a fact and `acc_chargeable` has no column of that name: |

The chargeable of a group-30 record (**assumed**, say if wrong):

| `acc_chargeable` | from |
|---|---|
| `servicegroup` / `servicefamily` | 30 / 30 (the family takes the service's number, as families 11 and 20 do) |
| `assignedDirection` | 1 (customer) |
| `BilledAmount` | `packageAmount` when it is > 0 (the tier paid in units), else `inPartnerCost` (money; 0 for a failed view) |
| `idBilledUom` | `inPartnerUom` as sent (null for a refused view: the producer sends none) |
| `Quantity` / `idQuantityUom` | `durationSec` / `TF_s` — the seconds watched, as a call's billed seconds |
| `unitPriceOrCharge` | `callRatePerMinBDT` |
| `Prefix` | `matchPrefixCustomer` |
| `glAccountId` | `idPackageAccount` — **the only place a ledger account fits**; `acc_chargeable` has no package-account column |
| `RateId`, `ProductId`, taxes | 0 / null: no rate was looked up |
| `uniqueBillId`, `idEvent`, `transactionTime` | the cdr's `UniqueBillId`, `IdCall`, `StartTime` — as every family |

On the cdr row, for group 30: `Duration1` and `RoundedDuration` = `DurationSec` (no plan rounds it), so a call summary switched on
for 30 does not sum nulls. No ANS operator stamping (the "numbers" are an MSISDN / a MAC and a rule code).

### Idempotency (B5)

| # | the code today | what I will do |
|---|---|---|
| W9 | the key is `cdr.UniqueBillId`; only the `cdr` table is checked; `ChannelCallUuid` is a field that is never written to any table | PostgreSQL: the key is `ChannelCallUuid`, checked in `cdr` AND `cdrerror`, under the tenant lock, before mediation. MySQL: **unchanged** (`UniqueBillId`, `cdr` only) — by the code's own note and RTC-Manager's template the MySQL tables have no `ChannelCallUuid` column (I have not looked at a live one, and must not), and I do not know that `cdrerror.UniqueBillId` is indexed there (a full scan per batch on a live table is not mine to risk). So on MySQL a redelivered record that sits in `cdrerror` is still written twice, as today. Closing that needs a DDL in the owner's window |
| W10 | **two copies of one call in the SAME poll** are not filtered by the dedup (it only reads the database). `CdrPipeline` phase 0 then throws "duplicate UniqueBillId in batch", the batch rolls back, the consumer rewinds, and the same poll fails again — for ever | **a stalled ingest, found by reading.** I drop the later copies inside the batch too, by the same key, on both engines. The first copy wins |
| W11 | a record that comes through gRPC (`FinalizeAndSummarize`, `ProcessCdrBatch`) has no `ChannelCallUuid` | on PostgreSQL such a record takes its `UniqueBillId` as its `ChannelCallUuid`, so one key serves every entry |

### PostgreSQL (B6, B7)

| # | the code today | what I will do |
|---|---|---|
| W12 | the six "new" cdr fields (`ResellerHierarchy`, `ChannelCallUuid`, `HangupCause`, `InPartnerUom`, `IdPackageAccount`, `PackageAmount`) are in memory only: `cdr.ExtInsertColumns` is the legacy 104 columns | on PostgreSQL the table has 110 columns and the writer emits all 110. On MySQL the writer keeps the 104 (the live tables lack the six). So **on MySQL B3's "amounts as they came" cannot be whole**: `PackageAmount`, `InPartnerUom`, `IdPackageAccount` have no column there. Group 30 is a PostgreSQL lane, so this does not block; it is a known gap of the MySQL lane |
| W13 | the batch lock's name is `billing_batch_` + `conn.getCatalog()`. On PostgreSQL the catalog is the DATABASE, the same for every tenant: all tiers would queue behind one lock. The lock itself is MySQL's `GET_LOCK` | PostgreSQL: the name is built from the SCHEMA, the lock is `pg_advisory_lock` (session level, so it is held across the commit as `GET_LOCK` is), 30 s as today |
| W14 | `MySqlFieldExtensions.EscapeSql` doubles every backslash. PostgreSQL (`standard_conforming_strings = on`) reads a backslash in `'…'` as itself: `a\b` would be stored as `a\\b` | the string literal is the dialect's. PostgreSQL doubles the quote only, and drops a NUL character (PostgreSQL refuses one in `text`, and one refused row rolls back the batch and replays it for ever — the 2026-09-03 pattern) |
| W15 | every string column of the MySQL `cdr` is a `varchar(n)` | on PostgreSQL every string column is `text`: a value longer than a column must never be the reason a whole batch is refused. `AdditionalMetaData` is `varchar(500)` on the MySQL template (RTC-Manager's reseller dump) — the ad's meta object is longer than that. One more reason group 30 is not for MySQL as it stands |

**The tables I create (BC-0002 has the DDL).** `cdr`, `cdrerror`, `acc_chargeable`: `PARTITION BY RANGE` on the row's time
(`starttime`, `starttime`, `transactiontime`), one partition a day as the MySQL `cdr` has, created in ONE step with the table: some
days back, some days ahead, and a DEFAULT partition so that no time, however wrong, can refuse a row. Each later day is added before
it is needed (one step for all missing days of a table). `summary_affected` is not partitioned. Times are `timestamp` WITHOUT time
zone and are written as the literal the wire sent — no zone ever converts them (N4).

Old partitions are never dropped by billing-core: retention is the owner's rule, and there is none yet.

### The grants (B7) — **ASK 1**

prime-context `main` 1a2e35c gives `summary_service` **SELECT and DELETE** on every table `billing_core` creates
(`primecontext.provision.summary-on-billing`, default `SELECT,DELETE`; W12 of its queue has not landed — your D2). So today
"summary_service cannot delete from `cdr`" is FALSE the moment I create the table.

I do not wait for W12. The tables are mine, so right after I create them I state their rights myself:

```sql
REVOKE ALL ON cdr, cdrerror, acc_chargeable FROM summary_service;
GRANT SELECT ON cdr, cdrerror, acc_chargeable TO summary_service;
GRANT SELECT, DELETE ON summary_affected TO summary_service;       -- the ruled line (N2)
GRANT SELECT ON cdr, cdrerror, acc_chargeable, summary_affected TO ad_sphere;
```

The two role names are profile keys (defaults `summary_service`, `ad_sphere`). A named role that does not exist stops the first
batch of that schema with its name in the error — it is not skipped. **Is the REVOKE yours to want?** It makes B7's "done when"
true with or without W12. If you would rather I only GRANT (and let W12 close the rest), say so.

### Fed by prime-context (B8)

| # | fact | what I will do |
|---|---|---|
| W17 | billing-core calls `/get-specific-tenant-root?name=<root>` and `/get-rates-by-date`. It never calls `/get-global-tenant-registry` (the key is read and unused). Both roads it uses are in your D3 list of open reads | nothing; I say it so nobody waits for a call that never comes |
| W18 | the doorbell topic is `<event-topic-base>_<each enabled tenant>`; with the root as the one enabled tenant that is `config_event_loader_<root>` — N3's doorbell, as it is | nothing |
| W19 | **a record whose tenant is not in the loaded tree is dead-lettered at once.** A reseller provisioned at run time is unknown until the ring is heard, debounced (3 s) and the tree loaded. A view that arrives inside that window is lost | an unknown tenant makes the consumer reload the tree once (not more often than a profile's interval, default 30 s) and read the batch again; only a tenant still unknown after that is a dead letter |
| W20 | prime-context rings after a FEED-caused rebuild only; provisioning and `reload` do not ring | my proof of B8 uses a real prime-context (built from a clone under my `target/`) and rings the doorbell myself, with the message shape prime-context sends. I do not run change-wire. I will say so in the report |

### Others

| # | finding |
|---|---|
| W16 | **A password in three test sources.** `CdrBatchAtomicityTests`, `ChargeableWriterMySqlIntegrationTests` and `CdrWriterTests` hold the local lab MySQL's root password as a literal (and `java/README.md`, `docs/local-debug-schema.sql` name it). That is how the MySQL regression ran here: I supplied nothing and guessed nothing. I add no new copy: my MySQL cases go into an existing class. Moving the literal out is a change of the regression's switch — yours to order |
| W21 | `SummaryRollupConsumer` (the old in-process roll-up into `sum_voice_*`) is MySQL SQL. On a PostgreSQL profile it must stay off; I make a start with `summary-rollup.enabled: true` and `kind: postgresql` a refusal in words |
| W22 | **For the summary agent — ASK 2.** summary-service's `OutboxInfraDdl` creates `summary_affected` itself ("a dev convenience copy", MySQL DDL). On PostgreSQL, if it runs first in a schema, the table is `summary_service`'s and my INSERT is refused. On PostgreSQL it must never create `summary_affected` (design §3: billing-core's). Please pass it on |
| W23 | D4, as asked: `SgDomOffnetOut` and `SgDomOffnetIn` key on a partner type — 3, 4, 5, 6 make a call group 10; 2 makes it group 11. That is the call lane, and the owner's two types (3 and 4) are both in the first set, so the ruling changes no call. The comments there still read 3 = IOS, 5 = CLIENT (RTC-Manager's `enumpartnertype`); I leave them. Nothing of group 30 reads a partner type |
| W24 | `MultiTenantCdrProcessor` commits tier by tier (one connection and one transaction per tenant). One transaction across the tiers is possible on PostgreSQL (design §3) but it is not what B6 asks ("the same pipeline and the same single transaction"), and B5 covers the rewind. I keep tier-by-tier |
| W25 | the contract page (`docs/cdr-kafka-ingest-contract.md`) still says PROPOSED, `cdr_rated`, "the pipeline RE-RATES", `SequenceNumber` as the key. I mark it ratified and correct those lines at the end |

---

## 2 · Order of work

`BC-0002` (the `cdr` DDL on PostgreSQL) next, pushed before the rest. Then B1 → B10, one commit an item, the suite green before
each push, each new rule broken once and seen red. My PostgreSQL 16 and Kafka are my own containers on `127.0.0.1` (`bc-pg16-lab`
on 7743, `bc-kafka-lab` on 7792), removed at the end. The PostgreSQL tests run only with `-Dbc.lab.pg.url=…`; without it they are skipped, never passed.
