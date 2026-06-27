# Local debug — billing-core against CCL (config-manager + Kafka real, DB local until VPN)

Run and debug `billing-core` on a fresh device while it talks to the **real CCL config-manager and Kafka**,
writing the DB slice to **local MySQL** (the real CCL DB needs the VPN — see the blocker at the end). This is
the exact setup used in development; follow it top to bottom.

## Reachability (verify first — this is what decides the setup)
```bash
probe() { timeout 6 bash -c "echo > /dev/tcp/$1/$2" 2>/dev/null && echo "$1:$2 OPEN" || echo "$1:$2 UNREACHABLE"; }
probe 103.95.96.78 7072   # config-manager  → expect OPEN
probe 103.95.96.78 9092   # CCL Kafka        → expect OPEN
probe 103.95.96.77 3306   # CCL DB           → UNREACHABLE without the VPN (use local mysql)
probe 127.0.0.1   3306    # local mysql      → expect OPEN
```
- config-manager + Kafka are on a **public** host (`…78`) → reachable from anywhere.
- The CCL DB (`…77`) is **not public** → only reachable over the VPN / CCL subnet route.

## Prerequisites (new device)
- **.NET 8 SDK** — `dotnet --version` → 8.x.
- **MySQL at `127.0.0.1:3306`** (house convention: lxc, `root` / `123456`). Connect with
  `mysql -h 127.0.0.1 -P 3306 -u root -p123456`.
- **Network egress to `103.95.96.78`** (config-manager + Kafka).
- **git** access to `git@github.com:pialmmh/billing-dotnetcore.git`.
- Optional: `grpcurl` or Postman (gRPC) to drive the service.

## 1. Clone + build + smoke-test
```bash
git clone git@github.com:pialmmh/billing-dotnetcore.git
cd billing-dotnetcore
dotnet build                 # expect: Build succeeded, 0 errors
dotnet test                  # expect: all green; the integration tests auto-create their own local DBs,
                             # which also PROVES local mysql + build are good on this device.
```

## 2. What points where (no file edits needed)
`src/Billing.Service/config/tenants/ccl78/dev/profile-dev.yml` already points at CCL:
- `config-manager.base-url: http://103.95.96.78:7072`  ← real CCL, reachable
- `config-events.bootstrap-servers: 103.95.96.78:9092` ← real CCL Kafka, reachable
- `datasource.host: 103.95.96.77`                      ← real CCL DB, NOT reachable → override to local (below)

The DB target is overridden at **runtime via env** (no YAML edit):
```
Billing__Db__Host=127.0.0.1
Billing__Db__Port=3306
Billing__Db__User=root
Billing__Db__Password=123456
```

## 3. Create the local tenant schemas + tables
billing-core writes into each tenant's **own schema** (the admin schema + `res_NNN` resellers). The dev chain
seen from config-manager is `res_233_2 → res_233 → telcobright` (+ `res_225`). Create the ones you'll exercise,
e.g. `res_233`:
```sql
CREATE DATABASE IF NOT EXISTS res_233 CHARACTER SET utf8mb4;
```
Then the tables. Authoritative DDL lives in the repo — don't hand-rewrite:
- **`summary_affected`** (outbox): apply `src/Billing.Data/Sql/summary_outbox.sql` into the schema.
- **`cdr` / `cdrerror` / `acc_chargeable`**: for quick debug, permissive all-TEXT tables are enough — the column
  lists are `cdr.ExtInsertColumns` and `acc_chargeable.ExtInsertColumns`; see how the tests build them in
  `tests/Billing.Tests/CdrBatchAtomicityTests.cs` (`CreatePermissive`).
- **`sum_voice_day_02/03`, `sum_voice_hr_02/03`** (only if running INLINE summary mode): exact DDL is in that
  same test file (`CreateSummaryTables`).

> Tip: the fastest way to get a known-good local schema is to copy what `CdrBatchAtomicityTests` creates — it
> builds every table this pipeline writes.

## 4. Run the service
```bash
cd src/Billing.Service
Billing__Db__Host=127.0.0.1 Billing__Db__Port=3306 Billing__Db__User=root Billing__Db__Password=123456 \
  dotnet run
```
On startup it: loads each enabled tenant's `MediationContext` from **CCL config-manager** (`…78`), subscribes to
**CCL Kafka** config-event topics (`…78`), and routes all writes to **local mysql**. Note the Kestrel URL in the
log (dev default `http://localhost:5293`).
- A `Kafka consume error … topic doesn't exist; retrying` warning is **normal** — the
  `config_event_loader_<tenant>` topic only exists once config-manager publishes a change. The consumer backs
  off and retries; it is not a failure.

## 5. Drive + debug the gRPC API
Service proto: `src/Billing.Service/Protos/billing.proto`. Main entry points:
- `ProcessCdrBatch { tenant, repeated cdrs_json }` — the Kafka-fed batch path (cdr → rate → validate → write).
- `GetMaxRatePerMinute`, `FinalizeAndSummarize`.

grpcurl example (plaintext h2c):
```bash
grpcurl -plaintext -d '{"tenant":"res_233","cdrs_json":["{ ...one cdr json... }"]}' \
  localhost:5293 telcobright.billing.v1.RatingService/ProcessCdrBatch
```
Postman: New → gRPC → `localhost:5293`, import `billing.proto`, pick `ProcessCdrBatch`. A full payload walkthrough
is in `docs/postman-e2e-testing.md`.

**Attach a debugger:**
- Rider / Visual Studio: open the solution, set `Billing.Service` as startup, add the `Billing__Db__*` env vars
  to the run configuration, breakpoint in `BillingServiceImpl` / `CdrProcessor` / `SummaryOutboxWriter`.
- VS Code: C# Dev Kit; put the env vars in `launch.json`.
- More logs: `Logging__LogLevel__Default=Debug`.

## 6. Debug the OUTBOX / summary hand-off (the decoupled path)
```bash
# in res_233 (or whichever tenant): apply src/Billing.Data/Sql/summary_outbox.sql first
cd src/Billing.Service
Billing__Db__Host=127.0.0.1 Billing__Db__Port=3306 Billing__Db__User=root Billing__Db__Password=123456 \
Billing__Summary__Enabled=true \
Billing__Summary__BootstrapServers=103.95.96.78:9092 \   # real CCL Kafka (or 127.0.0.1:9092 for local)
Billing__Summary__PingTopic=cdr_summary_ping \
  dotnet run
```
Then call `ProcessCdrBatch` and observe:
- a row in `summary_affected` (one per batch; `data` = base64(gzip(JSON)) of the rated cdrs + customer chargeable);
- a message on `cdr_summary_ping`.
Decode/inspect the blob: `SELECT data FROM summary_affected\G` → base64-decode → gunzip → JSON.
(Off by default: with `Billing__Summary__Enabled` unset, summaries are written inline the legacy way.)

## When the VPN is up → switch to the real CCL DB
1. Bring up the tunnel; verify `nc -zv 103.95.96.77 3306` (or the probe above) is OPEN.
2. Fill the real CCL DB creds in `profile-dev.yml` → `datasource.username` / `datasource.password` (currently
   empty TODO).
3. **Drop the `Billing__Db__*` env overrides** → the service uses `profile-dev.yml`'s datasource (`…77`).
4. Now cdr / chargeable / summary / outbox writes land in the real CCL schemas.

## KNOWN BLOCKER (as of this writing)
- **CCL DB `103.95.96.77:3306` is not reachable** from a plain internet connection — no WireGuard/OpenVPN tunnel
  was up, and the route to `…77` goes out the normal gateway. `config-manager` and `Kafka` (`…78`) ARE public and
  reachable. → debug against real CCL config-manager + Kafka, write to **local mysql**, until the VPN / route to
  the CCL subnet is in place. Also fill the empty CCL DB `username`/`password` in `profile-dev.yml`.
