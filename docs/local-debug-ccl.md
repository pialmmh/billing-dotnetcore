# Debug billing-core (Java/Quarkus): the CDR-processing → summary-outbox write path

Run the **Java/Quarkus** billing-core locally and attach a debugger to watch **CDR processing write to the
summary outbox** — `ProcessCdrBatch` → rate → validate → write `cdr` + `cdrerror` + `acc_chargeable` +
one `summary_affected` row, all in one transaction.

> ⚠️ **UNCOMMITTED WORK (2026-07-05).** The current code — the audit fixes (SQL escaping, `max(id)` id-seeding,
> per-cdr error isolation, RateCache race), **outbox v2** (the `op` add/subtract column, ALL chargeable legs in
> the blob, the per-tenant `GET_LOCK` commit-ordering), and the `mediation/summary` → `mediation/sql` cut — is
> **in the working tree, NOT committed/pushed**. A fresh `git clone` gets the older `7feef36` and will NOT match
> this doc. **Before debugging on another PC: commit + push first** (or copy the working tree / debug on the box
> that has it). `mvn -f java/pom.xml test` should report **89 passing** on the current tree.

Two DB targets:

| Target | When | DB reachability |
|---|---|---|
| **Local MySQL** (§A) | fast iteration on the write logic — recommended for "does CDR processing write the outbox?" | `127.0.0.1:3306` (no VPN) |
| **Real CCL backend** (§B) | faithful end-to-end against real schema + real ping consumer | CCL DB `103.95.96.77` needs the **CCL VPN** |

Rating is **DB-free** either way — rates come from **CCL config-manager (`103.95.96.78:7072`, open internet)**.
So the local-MySQL path still exercises real rating; only the writes land locally.

---

## §A — Local MySQL fast path (no VPN) ← use this to debug the write path

1. **Create the schema** (permissive throwaway DB — proves the writes, types not enforced):
   ```bash
   mysql -h 127.0.0.1 -P 3306 -u root -p123456 -e 'create database if not exists ccl_debug'
   mysql -h 127.0.0.1 -P 3306 -u root -p123456 ccl_debug < docs/local-debug-schema.sql
   ```
   (`docs/local-debug-schema.sql` creates `cdr`, `cdrerror`, `acc_chargeable`, `summary_affected` — the last
   with the real `id AUTO_INCREMENT` + `op` column.)
2. **Point the profile at it** — in `java/src/main/resources/config/tenants/ccl78/dev/profile-dev.yml`,
   `billing.datasource`: `host: 127.0.0.1`, `database: ccl_debug`, `username: root`, `password: 123456`.
   (`config-manager.base-url` and the `summary` block stay on CCL — leave them.)
3. **Run + drive** (§3–§5 below), then inspect: `SELECT id, entity_type, op FROM ccl_debug.summary_affected;`
   → base64-decode → gunzip → JSON to see `[{Cdr, Chargeables:[...]}]`.

The rest of this doc (§B) is the real-CCL-backend path.

---

## §B — Against the real CCL backend (config-manager + Kafka + DB)

Wired to the **real CCL backend**. There is **no local-database fallback in this mode**: if a CCL endpoint is
unreachable, bring up connectivity (the CCL VPN / route to the CCL subnet) — do not substitute a local database
here (use §A for that).

> This is the canonical version. The Java port lives under **`java/`** in the repo; the legacy .NET version
> (`src/Billing/`) is being retired. All paths below are the Java ones.

## Endpoints — all CCL, all must be reachable
| Service | Address | Reachable from open internet? |
|---|---|---|
| config-manager | `http://103.95.96.78:7072` | yes |
| Kafka | `103.95.96.78:9092` | yes |
| DB (MySQL) | `103.95.96.77:3306` | **NO — needs the CCL VPN / subnet route** |

The summary-service is wired to these **same** endpoints.

## 0. Confirm CCL reachability first
```bash
probe() { timeout 6 bash -c "echo > /dev/tcp/$1/$2" 2>/dev/null && echo "$1:$2 OPEN" || echo "$1:$2 UNREACHABLE"; }
probe 103.95.96.78 7072   # config-manager → OPEN  (service fail-fasts on startup if this is down)
probe 103.95.96.78 9092   # CCL Kafka       → OPEN
probe 103.95.96.77 3306   # CCL DB          → must be OPEN to run the write path
```
If the DB is UNREACHABLE, bring up the **CCL VPN / route to `103.95.96.77`**. Do not point at a local database.

## Prerequisites (new device)
- **JDK 21** (`java -version` → 21.x) and **Maven** (`mvn -version`). The repo uses Java 21 / Quarkus 3.24.
- **CCL connectivity**: egress to `103.95.96.78` (config-manager + Kafka) AND the VPN/route to `103.95.96.77` (DB).
- **git** access to `git@github.com:pialmmh/billing-dotnetcore.git`.
- `grpcurl` or Postman (gRPC) to drive the service.
- An IDE that can attach a remote JVM debugger: **IntelliJ IDEA** (has a Quarkus run/debug; or plain Remote JVM
  attach) or **VS Code** (Java + "Debugger for Java").

## 1. Clone + build
```bash
git clone git@github.com:pialmmh/billing-dotnetcore.git
cd billing-dotnetcore
mvn -f java/pom.xml clean package            # expect: BUILD SUCCESS, 89 tests pass
# faster inner loop once it's green: add -DskipTests
```

## 2. Config — routesphere style; already points at CCL, just fill the DB creds
Two parts (see `java/src/main/resources/config/README.md`):

1. **Tenant registry** — `java/src/main/resources/application.properties` (which tenants load + active profile):
   ```properties
   billing.tenants[0].name=ccl78
   billing.tenants[0].enabled=true
   billing.tenants[0].profile=dev
   ```
2. **Active profile detail** — `java/src/main/resources/config/tenants/ccl78/dev/profile-dev.yml`, already CCL:
   - `config-manager.base-url: http://103.95.96.78:7072`
   - `config-events.bootstrap-servers: 103.95.96.78:9092`
   - `datasource.host: 103.95.96.77`

Fill `datasource.username` / `datasource.password` (currently empty TODO) with the CCL DB credentials. The profile
is the single source of truth — no environment overrides. (To point at an external, already-edited config dir
without rebuilding, set `billing.config.dir=/path/to/config`; otherwise the bundled classpath tree is used.)

## 3. Run in dev mode (live reload + debugger ready)
```bash
mvn -f java/pom.xml quarkus:dev
```
Quarkus dev mode:
- **Opens a JDWP debug port on `localhost:5005` automatically** (attach your IDE — see §4).
- Hot-reloads code on save (no restart for most edits).
- Starts the **gRPC server on `:9000`** (plaintext h2c, a dedicated server — set in `application.properties`).
- **Fail-fast**: on boot it loads every enabled tenant from **CCL config-manager**; if config-manager is
  unreachable the boot fails (by design — bring up connectivity). It then subscribes to **CCL Kafka**
  config-event topics and writes to the **CCL DB**.
- A `Kafka consume error … topic doesn't exist; retrying` warning is **normal** — the
  `config_event_loader_<tenant>` topic exists only once config-manager publishes a change.

## 4. Attach the debugger + set breakpoints  ← the point of this doc
Quarkus `quarkus:dev` already runs the app with JDWP listening on **`localhost:5005`**. Attach to it:

- **IntelliJ IDEA**: open the `java/` folder as a Maven project → Run → **Edit Configurations → + → Remote JVM
  Debug** → host `localhost`, port `5005` → Debug. (Or use IntelliJ's built-in Quarkus run config and hit Debug.)
- **VS Code**: open `java/`, install "Extension Pack for Java", add a launch config of type `java` /
  request `attach` → `hostName: localhost`, `port: 5005` → F5.

To make the app **pause until** the debugger attaches (useful for catching startup):
```bash
mvn -f java/pom.xml quarkus:dev -Dsuspend=true        # waits on :5005 before booting
```
Change/disable the port with `-Ddebug=<port>` / `-Ddebug=false`.

Good breakpoint spots:
| Concern | Class |
|---|---|
| gRPC entry | `api/BillingServiceImpl`, `api/internal/ProcessCdrBatchHandler` |
| batch + tenant resolve | `beans/CdrProcessor` |
| rating | `mediation/rating/BasicCharge`, `mediation/cdr/CdrPipeline` |
| outbox encode | `mediation/cdr/SummaryOutboxWriter` |
| config load | `tenantconfigsync/internal/TenantHierarchyLoader`, `BillingBootstrap` |

More logs: add to `application.properties` (or pass `-D…` on the command line):
```properties
quarkus.log.category."com.telcobright.billing".level=DEBUG
```

**Debug the packaged jar instead of dev mode** (e.g. to mimic prod):
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar java/target/quarkus-app/quarkus-run.jar
```

## 5. Drive the gRPC API
Proto: `java/src/main/proto/billing.proto`. Entry points:
- `ProcessCdrBatch { tenant, repeated cdrs_json }` — cdr → rate → validate → write.
- `GetMaxRatePerMinute`, `FinalizeAndSummarize`.
```bash
grpcurl -plaintext -d '{"tenant":"res_233","cdrs_json":["{ ...one cdr json... }"]}' \
  localhost:9000 telcobright.billing.v1.RatingService/ProcessCdrBatch
```
Postman: New → gRPC → `localhost:9000`, import `billing.proto`, pick `ProcessCdrBatch`. The full payload
walkthrough in `docs/postman-e2e-testing.md` still applies field-for-field (same proto, same wire format) — just
target **`localhost:9000`** instead of the old .NET `:5293`.

## 6. Outbox / summary debug (the decoupled path)
Summary is **outbox-ONLY** — there is no inline summary engine anymore. The batch ALWAYS writes one compressed
`summary_affected` row atomically with the cdr/chargeable write; `billing.summary.enabled` only gates the
best-effort Kafka *ping* (the durable hand-off is the row itself). Everything is config — no env switches.
1. Apply the outbox table into the target schema: `java/src/main/resources/sql/summary_outbox.sql` (creates
   `summary_affected` with `id AUTO_INCREMENT`, `entity_type`, **`op ENUM('add','subtract')`**, `data`).
   (§A's `local-debug-schema.sql` already includes it.)
2. `billing.summary.enabled: true` is already set in `profile-dev.yml`; the `summary` block carries
   `ping-topic: cdr_summary_ping` + CCL `bootstrap-servers`.
3. `mvn -f java/pom.xml quarkus:dev`.

Call `ProcessCdrBatch` → observe a row in `summary_affected` and (CCL mode) a message on `cdr_summary_ping`;
the summary-service consumes it. Inspect the blob:
```sql
SELECT id, entity_type, op, data FROM summary_affected\G
```
`op` = `add` for normal batches (`subtract` is reserved for the future correction producer). Decode `data`:
base64 → gunzip → JSON = an array of **`{Cdr, Chargeables:[...ALL legs...]}`** (v2; the old shape was
`{Cdr, Customer}` — a v2-aware consumer still reads both). The summary-service side (its beans, the ledger-free
chargeable summary, table self-provisioning) is a SEPARATE service — see
`/tmp/shared-instruction/summary-service-work-order.md`; this doc stops at the billing write.

## If a CCL endpoint is unreachable
This is expected without connectivity. Bring up the **VPN / route to the CCL subnet** (especially `103.95.96.77`)
and fill the DB creds in `profile-dev.yml`. **Do NOT substitute a local database** — the point is to debug against
the real CCL backend.
