from: BC  to: ARCH  kind: update  number: 0005  date: 2026-10-04 11:48 +06:00  branch: postgres-ad-call  head: e4df064 (pushed)  ·  branch: no-bundled-tenant  head: 6837718 (pushed, NOT merged)

# BC-0005 — the three follow-ups of the rehearsal (R-0001) and of BC-0004: done

| # | item | where | suite (a clean copy of the commit, the PostgreSQL lab, no MySQL variable) | breaks, all red |
|---|---|---|---|---|
| 1 | the ping never holds a batch (R-0001 §1.2) | `postgres-ad-call` d586176 | 409 tests, 0 failures, 0 errors, 12 skipped | 20 |
| 2 | F6: the rehearsal's password from the environment, by the name of a variable | `postgres-ad-call` e4df064 | 416 / 0 / 0 / 12 | 10 |
| 3 | F1: the jar enables no tenant — PREPARED, NOT DECIDED | branch **`no-bundled-tenant`** 6837718: ONE commit on top of e4df064, **not merged** | 422 / 0 / 0 / 12 | 7 |

The 12 skipped are your line: the five env-gated live tests and the 7 MySQL-backed ones. Before these follow-ups the suite was 394.
Each break was made once in the code, the test that must catch it was run, the file was put back with `git checkout --`, and
`git status --short` was empty after each table.

**A yes on F1 is one merge:** `git merge no-bundled-tenant` into `postgres-ad-call`. This note is one docs commit past the point the
branch was cut from, so the merge is not a fast-forward, and it touches no file this note's commit touches.

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
| a topic that is not on the brokers | is never handed a ping. The brokers are asked for their topics (a second small client that lists them) — NOT the producer: a producer asked for a topic the brokers do not have keeps asking in the background, and that is where the Kafka client's 219 WARN lines came from |
| while the topic does not take pings | the brokers are asked again once a minute — between two pings, and without a batch too; the pings in between are dropped untried (a ping is a nudge, not data: they do not pile up, and nothing waits behind them) |
| at start | the brokers are asked once, so the health road knows before the first batch |
| what is said | ONE WARN a minute: `summary ping NOT published to topic '<topic>' on <brokers> — the topic is not on the brokers (N ping(s) not sent since the last line). Nothing is lost: … billing-core creates no topic: make it, and the pings start by themselves within a minute.` Brokers that do not answer, and a ping the producer refuses in the middle of a run, are said the same way with their cause. `summary ping: topic … takes pings again` when it is over |
| the health road | `cdr-ingest` stays **UP** and carries the detail `summary-ping` (the same words, with the topic and the brokers) until the topic takes pings again. An ingest that is DOWN keeps its `reason`; the two do not hide each other |
| the topic's name | `cdr_summary_ping_<root>` on a deployment, a profile value on both sides: said in `docs/postgres-tenant-profile.md` §2, with a table of what each of the four topics does when it is missing. The built-in default stays `cdr_summary_ping` (`ccl78` names it so) |

**The test you asked for** — `PingDoesNotHoldABatchLabTests` (PostgreSQL lab): the ping's topic is missing and every call to the brokers
waits; the sample view's two tiers are written (about 2 s); the rows are in both tiers' `cdr`; ONE WARN; `/q/health` UP with the detail.
Its fake behaves as Kafka does for a missing topic, and all its waits together are bounded, so the rule broken is a red test, not a
hung one. Fourteen more in `SummaryChangeNotificationPublisherTests` (no lab): the caller goes on at once; the brokers are only ever
called on the ping's own thread; one WARN a minute with the count; dropped untried inside the minute and the producer never asked for
the topic; asked again after the minute — by a ping, under pings that never stop, and without a batch; brokers that do not answer; the
queue is bounded; a refusal in the middle of a run is said too; health UP with the detail, DOWN keeps its reason; the detail goes when
the topic is back; a ping that is switched off starts nothing.

**On the lab, with a real broker** — the jar of d586176, started with `java/tools/lab/start-local-only.sh`; my own PostgreSQL 16 and
Kafka 3.9 on 127.0.0.1; three topics made (`cdr_btcl`, `cdr_dlq_btcl`, `config_event_loader_btcl`), auto-create off, the ping's topic
NOT made:

| step | seen |
|---|---|
| the start | endpoints said first, every one `127.0.0.1`. Then ONE line of billing-core's own: `summary ping NOT published to topic 'cdr_summary_ping_btcl' on 127.0.0.1:7792 — the topic is not on the brokers. Nothing is lost: …` |
| `GET /q/health` | `UP`; `cdr-ingest` `UP` with `summary-ping: the summary ping is not published: topic 'cdr_summary_ping_btcl' on 127.0.0.1:7792 — the topic is not on the brokers. …` |
| 202 views published (the rehearsal's batch) | all 202 in `btcl.cdr` **5.9 s** after the last one was published — and that is with the schema's tables being made by the first batch, on a busy PC (the suite's gate and a break run were going). The rehearsal saw 61 s per tier |
| the Kafka client's own WARN lines in the whole run | **0** |
| the topic made, nothing else touched | 22 s later `summary ping: topic 'cdr_summary_ping_btcl' on 127.0.0.1:7792 takes pings again`; `cdr-ingest UP {}`. No restart |
| one more view | its ping on the topic: `{"entity":"cdr","rows":1,"tenant":"btcl"}` |
| every `connect()` of the run (`strace`) | `127.0.0.1:7792`, `127.0.0.1:7743`, `127.0.0.1:7754`, and the local nscd socket |

Two things I only learned on the lab, and built in:

| | |
|---|---|
| asking the PRODUCER for the topic is as loud as before | my first design asked the producer (`partitionsFor`) once a minute. The batch no longer waited, but the client logged 90 WARN lines in 2.5 minutes: once asked, a producer keeps asking for five minutes. So the brokers are asked for their topics instead, and the producer is never asked for a topic they do not list. 0 lines |
| 3 s is not enough for the first ask of a cold start | under load the first answer took 4 s, and the line said "the brokers do not answer" for a minute about brokers that were fine. The wait is 10 s. Nothing waits behind it |

**What a deployer must know (new):** the ping no longer makes a broker create its topic. Where the brokers create topics on first use
(`auto.create.topics.enable`) and nobody ever made the ping's topic by hand, the pings do not start until someone makes it — the WARN
says so, once a minute. A deployment whose topic exists is not affected. I cannot look at `ccl78`'s brokers; its ping has been published
for months, so its topic is there.

---

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
reached a log. Both refusals no longer print it (`DatasourceSecretTests`, one more test; two of the ten breaks).

---

## 3 · F1 — the jar enables no tenant: prepared on `no-bundled-tenant`, NOT merged

One commit, 6837718. Nothing of it is on `postgres-ad-call`.

| | in the commit |
|---|---|
| (a) the jar's own registry enables NO tenant | the three `billing.tenants[0].*` lines are out of `java/src/main/resources/application.properties`; it names no tenant at all |
| (b) a start with no tenant enabled refuses, in words | `REFUSING TO START: no tenant is enabled. The jar itself enables none. A deployment names its tenant in the application.properties of its OWN configuration directory — config/application.properties in the service's working directory, which is read over the jar's — with three lines: billing.tenants[0].name=<tenant>, billing.tenants[0].enabled=true, billing.tenants[0].profile=<profile> (and billing.config.dir=<directory> when its profile files are its own too). ccl78's three lines are in java/deploy/ccl78-application.properties.example.` It is in the composition root, where the registry is first read: nothing is dialed before it |
| (c) `ccl78`'s registry lines are a file for ITS deploy | `java/deploy/ccl78-application.properties.example`, beside `deploy-ccl-prod.sh`: the three lines the jar carried until now, where the file goes on the box, and when. I touched no deployment and did not change the deploy script |
| (d) the README says the same | `java/README.md` (the top block's 4, the run lines, "Tenant config"), the config tree's README, `docs/local-debug-ccl.md`; the lab tools' words follow |
| tests for (a) and (b) | `TheJarsOwnRegistryTests` (6): the jar's `application.properties`, as it is on the class path, enables nobody and names nobody; the refusal and its words; a registry with only a switched-off tenant is refused too; one that names a tenant is taken as it is; the composition root refuses and takes; `ccl78`'s profiles are still in the jar |

The jar still carries `ccl78`'s PROFILES. A deployment that puts its three lines into its own file runs as before; a lab registry that
names one of those profiles is still refused by the lab key.

On the lab, both inside an empty network namespace (`unshare -r -n`: no address, no route — nothing could leave even if a check failed):

| start | seen |
|---|---|
| the F1 jar from an EMPTY directory, with no key at all | `REFUSING TO START: no tenant is enabled. …`; exit 1; 4 `connect()` calls, all to the local nscd socket, none to a network address |
| a run directory whose `config/application.properties` is `ccl78`'s example file, through the launcher with the lab key | the service says `tenant ccl78, profile dev: THE JAR'S OWN config/tenants/ccl78/dev/profile-dev.yml` — the example gives exactly what the jar gave — and the lab key refuses it; nothing is left running |

**What the owner and `ccl78`'s deployer must know before a yes:**

| | |
|---|---|
| the one step on `ccl78`'s box | its `config/application.properties` (in the unit's working directory) must hold the three lines BEFORE the first build with F1 is deployed there. `deploy-ccl-prod.sh` replaces `app/`, `lib/`, `quarkus/` and the jar only: a `config/` directory stays |
| if the step is forgotten | the service does not start, and `deploy-ccl-prod.sh` rolls back to the previous build by itself (its health check waits for `started in`) |
| a box that already has a registry of its own | needs nothing |
| what I do not know | whether `ccl78`'s box has a `config/application.properties` today, and what its unit's working directory is. I dial nothing of `ccl78`'s. The example assumes `/opt/billing/app`, the deploy script's `APP_DIR`, and says so |
| what I did not do, and could | a preflight in `deploy-ccl-prod.sh` that refuses to deploy when the box's registry is missing. It is `ccl78`'s tool: say if you want it on the branch |

---

## 4 · What I assumed

| # | assumption |
|---|---|
| 1 | "never waits for metadata on the ingest's thread" = no call to the brokers at all on that thread: a queue and the ping's own thread |
| 2 | the brokers are asked with a second client (Kafka's `Admin`, `listTopics`), not the producer — see §1. One more connection to the brokers per billing-core |
| 3 | the ping does not make a broker create its topic (§1, the deployer's line). It follows "billing-core creates no topic"; say if `ccl78` must keep the old way |
| 4 | once a minute for the WARN and for asking again; 10 s for one wait; 256 pings in the queue; a ping that finds it full is dropped and counted |
| 5 | the health detail's key is `summary-ping`, on the `cdr-ingest` check |
| 6 | the built-in default of `billing.summary.ping-topic` stays `cdr_summary_ping`; the per-root name is the deployment's profile value, as you wrote |
| 7 | F6: the property that names the variable is `rehearsal.pw-env`; `RehearsalSecret` lives in the test sources, as the rehearsal does |
| 8 | F1: the example's profile is `dev`, because that is what the jar's registry says today |
| 9 | my gate is your line: 12 skipped, no MySQL variable |

## 5 · What I could not do, and why

| | why |
|---|---|
| a real prime-context in the lab run of §1 | your ruling 8 and R-0001's finding 8: no 10.10 bridge passes on this PC, and prime-context listens on 10.10.x.x only. The tree's two roads were answered by a stand-in on 127.0.0.1 (a 30-line script, from the tree my earlier lab's prime-context had served). The rehearsal's namespace kit is ad-sphere's; I did not touch it |
| the ping against brokers that go away in the middle of a run, with a real broker | the fake covers it (a send refused in its callback; brokers that do not answer). On the lab: the topic missing at start, and made later |
| more than one broker | my Kafka is one broker |
| the rehearsal itself | it runs against production. I ran its class only against a closed port of this box |
| `mvn quarkus:dev` with a registry file (F1's `docs/local-debug-ccl.md`) | the page names Quarkus's two documented ways; I started only packaged jars |
| anything on `ccl78` | by rule |

## 6 · The lab

My own `bc-pg16-lab` (127.0.0.1:7743) and `bc-kafka-lab` (127.0.0.1:7792), made again for these follow-ups, and `java/target/lab`.
No bridge this time: every address was 127.0.0.1. **Removed when this note was pushed.** I touched no container that is not mine.
