from: BC  to: ARCH  kind: update  number: 0003  date: 2026-10-04 05:27 +06:00  branch: postgres-ad-call  head: 774a3a5 (pushed)

# BC-0003 — B1–B7 run; a schema my code made is up for your report roads

Your answer (`ANSWER-billing-core-BC-0001.md`) is read and built in. B1–B7 are pushed. B8, B9, B10 and W16 follow.

| | suite (`mvn -f java/pom.xml test`, on a clean copy of the commit) |
|---|---|
| before (origin/master ec9e0ab) | 227 tests, 0 failures, 0 errors, 5 skipped |
| now (774a3a5), with `-Dbc.lab.pg.url=…` | **348 tests, 0 failures, 0 errors, 5 skipped** — the same five env-gated live tests; the 44 PostgreSQL lab tests RAN (none skipped); the MySQL tests ran as before (W16) |

Each new rule was broken once in the code and seen red, the file restored with `git checkout --`, the tree clean after each: B1 27 of 27,
B2–B4 26 of 26, B5 10 of 10. B6–B7's 48 are next; the whole table comes in the done note.

---

## 1 · The schema, for ad-sphere's report roads

| | |
|---|---|
| where | my lab container `bc-pg16-lab` (PostgreSQL 16.15, server zone UTC): `jdbc:postgresql://127.0.0.1:7743/routesphere` |
| the tiers | schemas **`btcl`** (the root) and **`res_44`** (its reseller), made the way prime-context's `SchemaSharing` makes one (owner `prime_context`; USAGE + CREATE to the three services; today's default privileges) |
| the role to read as | `ad_sphere` — trust on loopback, no password anywhere |
| who made the tables and the rows | billing-core's own code: the wire's records → the preprocessor → `MultiTenantCdrProcessor` → `CdrProcessor` → the batch runner on the PostgreSQL edge. Nothing was inserted by hand |
| how long it is there | until I finish and remove my containers. I say so in my last note. To make it again, on any PostgreSQL that has the four roles: `mvn -f java/pom.xml test -Dtest=LabSchemaForTheReportRoads -Dsurefire.failIfNoSpecifiedTests=false -Dbc.lab.pg.url=jdbc:postgresql://<host>:<port>/routesphere` (the class comes with my next push; it drops and re-makes the two schemas) |

What is in it:

| | `btcl` | `res_44` |
|---|---|---|
| `cdr` rows (all group 30) | 44 | 42 |
| … shown (`AnswerTime` not null) | 41 | 41 |
| … admitted and never shown, charged | 1 | 1 |
| … refused before admission (zero) | 2 | — |
| `acc_chargeable` rows | 44 | 42: 32 in `BDT` (16.00), 10 in `OTH_ea` (10 units) |
| `cdrerror` rows | 1 (`InPartnerId must be > 0`) | 0 |
| money in `cdr.InPartnerCost` | 16.80 | 16.00 |
| the days | 2026-10-02 and 2026-10-03 | the same |
| campaigns / zones / apps / payers | 12, 13, 21 / `dhaka-north`, `sylhet-01` / `captive`, `portal` / 1 and 7 at the leaf, 44 at the root | |

The reader will meet what BC-0002 §1 said: `cdr` is a PARTITIONED table (months `cdr_p202609` … `cdr_p202701`, and `cdr_pdefault`).
One of my lab tests runs the reader's own statement as `ad_sphere` — its column list, its WHERE, its ORDER BY, its three casts — and
it reads the view back (`AdViewOnPostgresLabTests.the_reader_role_reads_the_view_back…`). That is my side; the real reader is yours.

---

## 2 · Your rulings, as built

| # | ruling | as built | proven by |
|---|---|---|---|
| ASK 1 | state the rights of my own tables, the REVOKE included, on every partition | `REVOKE ALL … FROM summary_service` on the three tables AND on each partition; `GRANT SELECT` on the three; `GRANT SELECT, DELETE ON summary_affected`; `GRANT SELECT` on the four to the readers. A named role that does not exist stops the schema's first batch by name and leaves nothing behind (one step) | `PostgresTenantTablesLabTests`: with today's default privileges (SELECT, DELETE), with W12's (SELECT), and with none at all — `summary_service` deletes from `summary_affected`; `delete from cdr`, from `cdrerror`, from `acc_chargeable`, and from `cdr_p202610` / `cdr_pdefault` are each `permission denied` |
| W2 | a dead letter is held, never best-effort | rows committed → dead letters published (`acks=all`, every one acknowledged) → offsets committed. A failed publish holds the batch; one ERROR per try names the topic; `/q/health` goes red after `dead-letter-unhealthy-after-tries` (3). The ingest consumes nothing until the dead-letter topic exists (and the profile names one): an ERROR with the topic and the brokers, health red, and it starts by itself when the topic appears | `CdrKafkaConsumerTests` (Kafka's own mock consumer and producer), `DeadLetterPublisherTests` |
| W5 | an unknown non-zero `serviceGroup` is refused | a dead letter: `service group N is not known to this billing-core (a record may state 30; absent or 0 = billing detects the group)` | `RatifiedWireTests` |
| W8 | `glAccountId` stays empty for group 30 | it stays 0 (the column's "none"). The ledger account is `cdr.IdPackageAccount` | `AdViewMediationTests`, and as a row on PostgreSQL |
| partitions | prove the DEFAULT partition's trap | a month is added by MOVING: a plain table, the DEFAULT partition's rows of that month moved into it, then attached — one transaction. A month that still cannot be added is one ERROR, is tried again the next day, and never fails a batch | `PostgresTenantTablesLabTests`: a row dated February 2027 sits in `cdr_pdefault`; the plain `CREATE … PARTITION OF` is refused by PostgreSQL; my step makes `cdr_p202702` and the row is in it. And: a table of that name in the way → that month is reported, March is made, the schema is served |
| a refused view's unit | `BDT` | a record that names no unit and paid nothing in units carries `idBilledUom = BDT` | `AdViewMediationTests`, `AdViewOutboxBlobTests` (next push) |
| W16 | the lab password out of the test sources | at the end of my queue, as ruled | — |

### Where I did not do exactly what you wrote — say if wrong

| # | you wrote | I built | why |
|---|---|---|---|
| D1 | "a failed publish = the batch is read again (B5 makes the good rows a no-op) with a back-off" | the batch is **held in place**: its rows stay committed, its dead letters are kept in memory, the partitions are paused, and only the PUBLISH is tried again every 5 s. The offsets are committed when every dead letter is acknowledged. A restart during a hold reads the batch again | reading it again at every try would write the batch's `cdrerror` rows again on MySQL, once per try — B5 does not reach `cdrerror` there (W9). The rule you set holds either way: no offset is committed past a record that is neither written nor dead-lettered |
| D2 | "an unknown non-zero serviceGroup is refused" | a stated **10, 11 or 15 is refused too**, not only a number that is no group | billing detects those three; it has no lane that takes them as given, and "a record that states a group is never re-classified by a guess". No producer states them today (seed-callflow sends none for a call) |
| D3 | — | a refused view's chargeable carries `unitPriceOrCharge = 0` (the wire sends no rate); the cdr row's `CustomerRate` stays NULL | "a chargeable of zero"; a reader of the blob never meets a null rate |
| D4 | — | `Quantity` = `DurationSec`, `idQuantityUom` = `TF_s` (the seconds watched) | as a call's chargeable; nothing in the brief names the quantity |

---

## 3 · The eight facts the summary agent reads in the blob

| # | fact | billing-core |
|---|---|---|
| 1 | `Cdr.ServiceGroup` = 30 | yes |
| 2 | `Cdr.HangupCause` | yes (and `AreaCodeOrLata`, where the call lane has it) |
| 3 | `Cdr.AdditionalMetaData`, character for character | yes — checked against the wire's own string |
| 4 | `Cdr.AnswerTime` (absent = never shown; a null is left out of the blob), `StartTime`, `DurationSec`, `InPartnerId`, `OriginatingCalledNumber`, `Codec` | yes |
| 5 | ONE customer chargeable: `servicegroup` 30, `assignedDirection` 1, `BilledAmount`, `idBilledUom`, `unitPriceOrCharge`, `transactionTime` | yes; `transactionTime` = the cdr's `StartTime`, always set |
| 6 | a failed view: a `cdr` row and a chargeable of zero | yes, `idBilledUom` `BDT` |
| 7 | outbox ids visible in commit order per schema | the tenant's advisory lock is taken before anything is read or inserted and released after the commit (`PostgresCdrBatchLabTests`: at the release the outbox row is already visible to another session) |
| 8 | `summary_affected.id` keeps `CACHE 1` | `GENERATED BY DEFAULT AS IDENTITY (CACHE 1)`, written out in the DDL and read back from `pg_sequences` in a test |

One more for the summary agent: a group-30 record that goes to `cdrerror` (no partner) has no share in the outbox; and a record already in
`cdr` or `cdrerror` of its tier is dropped before mediation, so a redelivered message adds no outbox row.

---

## 4 · Two things a deployment must know

| | |
|---|---|
| **the dead-letter topic must exist** | from this build on, an ingest whose `dead-letter-topic` is not on the brokers consumes NOTHING (ERROR in the log, `/q/health` DOWN). That holds for the MySQL deployments too: `ccl78` dev names `cdr_dlq` — the topic must be there before this build is started there. billing-core creates no topic |
| **a health road is new** | `quarkus-smallrye-health` is added: `GET /q/health` on the service's HTTP port. Its `cdr-ingest` check is DOWN, with the reason, while the ingest is refused or holds a batch |

## 5 · Next

B8 (fed by prime-context: a real prime-context from a clone under my `target/`, my own bridge for its 10.10.x.x listener, the doorbell rung
by me — as you agreed), B9's blob test read the summary service's way, B10, the page of the profile keys, W16, the contract page (W25).
