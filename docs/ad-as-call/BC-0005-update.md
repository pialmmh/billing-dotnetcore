from: BC  to: ARCH  kind: update  number: 0005  date: 2026-10-04 11:05 +06:00  branch: postgres-ad-call  head: (the commit this note is in)

# BC-0005 — DRAFT — the three follow-ups of the rehearsal (R-0001) and of BC-0004

**DRAFT.** It is pushed with the first commit and filled in as each item lands. Your message of 2026-10-04 (after R-0001) asks three
things; this page says where each stands.

| # | item | state |
|---|---|---|
| 1 | the ping must not hold a batch (R-0001 §1.2) | **built, in this commit** — the break table, the gate and the lab run follow |
| 2 | F6: `LegacyDedupProdRehearsal` reads its password from the environment, by the name of a variable | **built** — the break table and the gate follow |
| 3 | F1, prepared on a branch of its own (`no-bundled-tenant`), not merged | not started |

---

## 1 · The ping never holds a batch

**What was wrong.** `SummaryChangeNotificationPublisher.Publish` called `producer.send()` on the ingest's thread. A Kafka producer
waits for the topic's metadata inside `send()` — `max.block.ms`, 60 s by default — and when the topic is not there it does not throw:
it fails the returned future, which nobody read. So: 60 s per tier per batch, no line of billing-core's own, health UP. The class's own
comment said "NEVER blocks". It did.

**As built.**

| | |
|---|---|
| the hand-over | `Publish` puts the ping on a bounded queue (256) and returns. It never calls the brokers |
| the ping's own thread (`summary-ping`) | the only one that calls the brokers, and the only one that waits for them (10 s at most per call) |
| a topic that is not on the brokers | is never handed a ping. The brokers are asked for their topics (a second small client that lists them) — NOT the producer: a producer asked for a topic the brokers do not have keeps asking in the background, and that is where the Kafka client's 219 WARN lines came from. So billing-core creates no topic, and the ping does not make a broker create one either (`auto.create.topics.enable`) |
| while the topic does not take pings | the brokers are asked again once a minute — between two pings, and without a batch too; the pings in between are dropped untried (a ping is a nudge, not data: they do not pile up, and nothing waits behind them) |
| at start | the brokers are asked once, so the health road knows before the first batch |
| what is said | ONE WARN a minute: `summary ping NOT published to topic '<topic>' on <brokers> — the topic is not on the brokers (N ping(s) not sent since the last line). Nothing is lost: … billing-core creates no topic: make it, and the pings start by themselves within a minute.` Brokers that do not answer, and a ping the producer refuses in the middle of a run, are said the same way with their cause. `summary ping: topic … takes pings again` when it is over |
| the health road | `cdr-ingest` stays **UP** and carries the detail `summary-ping` (the same words, with the topic and the brokers) until the topic takes pings again. An ingest that is DOWN keeps its `reason`; the two do not hide each other |
| the topic's name | `cdr_summary_ping_<root>` on a deployment, a profile value on both sides: said in `docs/postgres-tenant-profile.md` §2, with a table of what each of the four topics does when it is missing. The built-in default stays `cdr_summary_ping` (`ccl78` names it so) |

**The test you asked for** — `PingDoesNotHoldABatchLabTests` (PostgreSQL lab): the ping's topic is missing and every call to the brokers
waits; the sample view's two tiers are written; the rows are in both tiers' `cdr`; ONE WARN; `/q/health` UP with the detail. Its fake
behaves as Kafka does for a missing topic, and its wait is bounded, so the rule broken is a red test, not a hung one.

Fourteen more in `SummaryChangeNotificationPublisherTests` (no lab): the caller goes on at once; the brokers are only ever called on the
ping's own thread; one WARN a minute with the count; dropped untried inside the minute and the producer never asked for the topic;
asked again after the minute — by a ping, under pings that never stop, and without a batch; brokers that do not answer; the queue is
bounded; a refusal in the middle of a run is said too; health UP with the detail, DOWN keeps its reason; the detail goes when the
topic is back; a ping that is switched off starts nothing.

*(the break table, the suite's numbers and the run on a real broker: filled in below when they are done)*

## 2 · F6 — the rehearsal's password

`LegacyDedupProdRehearsal` (a by-name rehearsal against production; not in the suite, and it stays out) took its password as
`-Drehearsal.pw=…`.

| | |
|---|---|
| now | `-Drehearsal.pw-env=<NAME>` names an environment variable; the password is read from the environment, by that name (`testsupport/RehearsalSecret`). The class's header says how: `read -rs REHEARSAL_PW && export REHEARSAL_PW`, then the name on the line |
| the old property | refused when someone still passes it — `-Drehearsal.pw is REFUSED: a password is never on a command line … What it holds was not used and is not printed … If the password was typed on this line, change it.` Refused beside the new property too, and when it is empty |
| no name, a name whose variable is not set or empty | refused in words; the variable is named; there is no fallback |
| what is given where the name belongs but is not a name (the password itself, by mistake) | refused, and NOT printed |
| tests | `RehearsalSecretTests` (6, in the suite). The rehearsal class itself was run by name four times against `127.0.0.1:1` — a closed port of this box, never a real one: no url → skipped, as before; the old property → refused in those words; the variable named and not set → refused by its name; named and set → it goes on to the connection (nothing listens: `Communications link failure`) |

**One more of the same kind, in my own B10 code** (say if you do not want it): a refusal of `DatasourceSecret` printed what the profile
holds in `password-ref` when that is not `env:<NAME>`, and when `password` is there too. A secret typed into the wrong key would have
reached a log. Both refusals no longer print it (`DatasourceSecretTests`, one more test).

*(the break table and the suite's numbers: filled in below when they are done)*

## 3 · F1 — the jar's own registry, on its own branch

*(not started)*
