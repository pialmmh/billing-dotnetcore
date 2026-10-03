from: BC  to: ARCH  kind: update  number: 0002  date: 2026-10-04 04:01 +06:00  branch: postgres-ad-call  head: (this commit)

# BC-0002 — the tables billing-core makes in a tenant schema on PostgreSQL

**The DDL:** `java/src/main/resources/sql/postgres/billing-tables.sql` — one file, the one source of the column definitions.
billing-core runs it itself the first time it serves a schema; nobody applies it by hand.

**Status:** the file is written and I applied it by hand in my lab (PostgreSQL 16.15): 4 tables, their partitions and indexes, no
error. The code that runs it, and the writer, land with B6 / B7. The column NAMES and TYPES below are what the reader can build on
now; if one must change I send a new note first. The VALUES of §2 and the rights of §3 are what the code will write — each is
proven in its own item's note, not here.

---

## 1 · Two things ad-sphere's reader must change — read this first

| # | what | why |
|---|---|---|
| R1 | **`JdbcCdrReader.present()` will answer "no table" for my `cdr`.** It asks the driver for types `TABLE` / `BASE TABLE`. A partitioned table is `PARTITIONED TABLE` in PgJDBC's metadata (probed here, driver 42.7.7, PostgreSQL 16.15: `cdr found with types {TABLE, BASE TABLE}: false`, `TABLE_TYPE = PARTITIONED TABLE`). The reader would then log "there is no … cdr table yet" and show an empty report, for ever | add `"PARTITIONED TABLE"` to the type list (or ask `to_regclass('<tier>.cdr')`). The same holds for the summary tables if the summary agent partitions them |
| R2 | **`IdCall` is not an identity and not the primary key.** billing-core assigns it (highest of `cdr` and `cdrerror` + 1, under the tenant's batch lock). The stand-in's `GENERATED … AS IDENTITY PRIMARY KEY` is not the truth | nothing to change in the reader: `rs.getLong("IdCall")` and `ORDER BY StartTime DESC, IdCall DESC` work. Just do not rely on a key |

Everything else the reader does works on these tables as it is: every column of its `CDR_COLUMNS` exists under the same name, and
the three casts it makes are right (`(Integer) InPartnerId`, `(Integer) OutPartnerId`, `(Long) IdPackageAccount`).

---

## 2 · `cdr` — the columns an ad view uses

110 columns: the legacy cdr's 104, in its insert order, then six for the ratified wire. All are nullable except `SwitchId`,
`IdCall`, `SequenceNumber`, `ServiceGroup`, `StartTime`. Identifiers are unquoted, so PostgreSQL stores them in lower case.

| column | type | from the wire | group 30 |
|---|---|---|---|
| `IdCall` | `bigint` not null | — | billing-core's number of the row |
| `SequenceNumber` | `bigint` not null | `sequenceNo` | the producer's running number |
| `FileName` | `text` | — | `kafka:cdr` |
| `ServiceGroup` | `integer` not null | `serviceGroup` | 30 |
| `UniqueBillId` | `text` | `callId` | the ad session id |
| `ChannelCallUuid` | `text` | `channelCallUuid` | the ad session id. The idempotency key of the schema |
| `ResellerHierarchy` | `text` | `resellerHierarchy` | root > … > this tier |
| `IncomingRoute` / `OutgoingRoute` | `text` | `incomingRoute` / `outgoingRoute` | the campaign's route / the zone |
| `OriginatingIP` / `TerminatingIP` | `text` | `callerIp` / `receiverIp` | the gateway / the media server |
| `OriginatingCalledNumber` / `TerminatingCalledNumber` | `text` | the same | the rule's code |
| `OriginatingCallingNumber` / `TerminatingCallingNumber` | `text` | the same | the subscriber |
| `StartTime` | `timestamp` not null | `startTime` | admitted. The partition key |
| `AnswerTime`, `ConnectTime` | `timestamp` | `answerTime` | shown; NULL = never shown |
| `EndTime` | `timestamp` | `endTime` | ended |
| `DurationSec` | `numeric(20,8)` | `durationSec` | the seconds watched |
| `Duration1`, `RoundedDuration` | `numeric(20,8)` | — | = `DurationSec` (no plan rounds a view) |
| `ChargingStatus` | `integer` | — | 1 = shown, 0 = not shown |
| `HangupCause` | `text` | `hangupCause` | the cause. Also in `AreaCodeOrLata`, where the call lane has it |
| `Codec` | `text` | `channelReadCodecName` | the media kind |
| `PDD` | `real` | `pdd` | shown − admitted |
| `InPartnerId` / `OutPartnerId` | `integer` | the same | the payer / the network division |
| `PrePaid` | `integer` | `isPrepaid` | 1 / 2 / 0 |
| `MatchedPrefixCustomer` | `text` | `matchPrefixCustomer` | the matched rate prefix |
| `CustomerRate` | `numeric(20,8)` | `callRatePerMinBDT` | the rate |
| `InPartnerCost` | `numeric(20,8)` | `inPartnerCost` | what was charged in money, as sent |
| `PackageAmount` | `numeric(20,8)` | `packageAmount` | what was charged in units, as sent |
| `InPartnerUom` | `text` | `inPartnerUom` | the unit |
| `IdPackageAccount` | `bigint` | `idPackageAccount` | the ledger account |
| `OutPartnerCost` | `numeric(20,8)` | `supplierCost` | — |
| `AdditionalMetaData` | `text` | `additionalMetaData` | the JSON object, stored character for character as sent |
| `ErrorCode` | `text` | — | set in `cdrerror` only |

What a reader should know about the values:

| | |
|---|---|
| times | `timestamp` WITHOUT time zone: the tenant's wall clock, the literal the wire sent. No zone converts it, whatever zone the JVM or the server runs in (my lab's server is in UTC) |
| amounts | `numeric(20,8)`: `0.50` reads back as `0.50000000` |
| an empty string | is written as NULL (the legacy writer's rule, kept so both engines hold the same rows). So is the text `null` |
| `AdditionalMetaData` | `text`, not `jsonb`: the reader's `LIKE '%"campaignId":12,%'` needs the producer's own spelling, and `jsonb` would re-space it |
| a refused view | one row: `AnswerTime` NULL, `DurationSec` 0, amounts 0, `CustomerRate` / `InPartnerUom` / `MatchedPrefixCustomer` / `IdPackageAccount` NULL (the producer sends none) |

The other columns are the legacy cdr's (`SwitchId`, `CountryCode`, `AnsIdOrig`, `Tax1`, …): the same names as on MySQL, with
`int → integer`, `bigint → bigint`, `decimal(20,8) → numeric(20,8)`, `datetime → timestamp`, `float → real`, every `varchar → text`.

---

## 3 · Partitions, indexes, rights

**Partitions.** `cdr`, `cdrerror` (on `StartTime`) and `acc_chargeable` (on `transactionTime`) are `PARTITION BY RANGE`, **one
partition a month**, made in the same step as the table: the month before, this month, three months ahead, and a DEFAULT partition.

```sql
CREATE TABLE cdr ( … ) PARTITION BY RANGE (StartTime);
CREATE TABLE cdr_p202609 PARTITION OF cdr FOR VALUES FROM ('2026-09-01 00:00:00') TO ('2026-10-01 00:00:00');
CREATE TABLE cdr_p202610 PARTITION OF cdr FOR VALUES FROM ('2026-10-01 00:00:00') TO ('2026-11-01 00:00:00');
…
CREATE TABLE cdr_pdefault PARTITION OF cdr DEFAULT;
```

| | |
|---|---|
| why a month, not a day as the MySQL `cdr` | the idempotency lookup is by `ChannelCallUuid`, which has no time in it. It probes the index of EVERY partition, on every batch. Day partitions add 365 probes a year to each batch of each tier — and 3 tables a day to each tenant schema. Months keep both small. **Say if the owner wants day retention**: then the lookup must be bounded by time, which weakens the key |
| the DEFAULT partition | a row whose time is outside every month (a clock gone wrong) is still stored. A refused row would roll the batch back and replay it for ever |
| later months | added before they are needed, all missing months of a table in one step, at the first batch of a day |
| old months | never dropped by billing-core. Retention is the owner's rule; there is none yet |
| the two window sizes | profile keys (`months-back` 1, `months-ahead` 3) |

**Indexes.**

| table | index | for |
|---|---|---|
| `cdr` | `ux_cdr_call` UNIQUE (`ChannelCallUuid`, `StartTime`) | the hard backstop of the idempotency. A unique index of a partitioned table must hold the partition key; a redelivered record has the same `StartTime` |
| `cdr` | `ix_cdr_servicegroup_starttime` (`ServiceGroup`, `StartTime`) | the reports: `WHERE ServiceGroup = 30 AND StartTime …  ORDER BY StartTime DESC` |
| `cdr` | `ix_cdr_idcall` (`IdCall`), `ix_cdr_uniquebillid` (`UniqueBillId`) | the id seed; a call's rows by its bill id |
| `cdrerror` | the same three without the report index | |
| `acc_chargeable` | PRIMARY KEY (`id`, `transactionTime`); (`uniqueBillId`); (`idEvent`) | |
| `summary_affected` | PRIMARY KEY (`id`); (`entity_type`, `id`) | the summary service's cursor |

The reader filters by `InPartnerId` inside a time range. If a report is slow, ask for `(ServiceGroup, InPartnerId, StartTime)`:
each index is a write on every row, so I add one when it is needed, not before.

**Rights** (BC-0001, the grants; the two role names are profile keys):

| role | `cdr`, `cdrerror`, `acc_chargeable` | `summary_affected` |
|---|---|---|
| `ad_sphere` | SELECT | SELECT |
| `summary_service` | SELECT — and **no right at all on a partition** (prime-context's default privileges would give it DELETE on each one; a partition is a table) | SELECT, DELETE |

---

## 4 · The other three tables

| table | shape |
|---|---|
| `cdrerror` | `(LIKE cdr)`: the same 110 columns. A rejected record keeps every field; `ErrorCode` says why |
| `acc_chargeable` | the legacy 33 columns in their insert order. For group 30: one row a record, `servicegroup` 30, `servicefamily` 30, `assignedDirection` 1, `BilledAmount` the money or the units, `idBilledUom` the unit, `Quantity` the seconds watched (`TF_s`), `unitPriceOrCharge` the rate, `Prefix` the prefix, `glAccountId` the ledger account, `uniqueBillId` / `idEvent` the cdr's `UniqueBillId` / `IdCall` |
| `summary_affected` | `id bigint GENERATED BY DEFAULT AS IDENTITY`, `entity_type varchar(32)`, `op varchar(8)` (`add` / `subtract`, a CHECK where MySQL has an ENUM), `data text`. The same four columns the summary service reads on MySQL |
