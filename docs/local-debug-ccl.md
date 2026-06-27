# Debug billing-core against the real CCL backend (config-manager + Kafka + DB)

Run billing-core locally (your dev box / a new device) wired to the **real CCL backend** — config-manager,
Kafka, and DB. There is **no local-database fallback by design**: if a CCL endpoint is unreachable, bring up
connectivity (the CCL VPN / route to the CCL subnet) — do not substitute a local database.

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
probe 103.95.96.78 7072   # config-manager → OPEN
probe 103.95.96.78 9092   # CCL Kafka       → OPEN
probe 103.95.96.77 3306   # CCL DB          → must be OPEN to run the write path
```
If the DB is UNREACHABLE, bring up the **CCL VPN / route to `103.95.96.77`**. Do not point at a local database.

## Prerequisites (new device)
- **.NET 8 SDK** (`dotnet --version` → 8.x).
- **CCL connectivity**: egress to `103.95.96.78` (config-manager + Kafka) AND the VPN/route to `103.95.96.77` (DB).
- **git** access to `git@github.com:pialmmh/billing-dotnetcore.git`.
- `grpcurl` or Postman (gRPC) to drive the service.

## 1. Clone + build
```bash
git clone git@github.com:pialmmh/billing-dotnetcore.git
cd billing-dotnetcore
dotnet build                 # expect: Build succeeded, 0 errors
```

## 2. Config — already points at CCL; just fill the DB creds
`src/Billing.Service/config/tenants/ccl78/dev/profile-dev.yml` already targets CCL:
- `config-manager.base-url: http://103.95.96.78:7072`
- `config-events.bootstrap-servers: 103.95.96.78:9092`
- `datasource.host: 103.95.96.77`

Fill `datasource.username` / `datasource.password` (currently empty TODO) with the CCL DB credentials. The
profile is the single source of truth — no environment overrides.

## 3. Run
```bash
cd src/Billing.Service
dotnet run
```
It loads each tenant's config from **CCL config-manager**, subscribes to **CCL Kafka** config-event topics, and
writes to the **CCL DB**. Note the Kestrel URL in the log (dev default `http://localhost:5293`).
- A `Kafka consume error … topic doesn't exist; retrying` warning is **normal** — the
  `config_event_loader_<tenant>` topic exists only once config-manager publishes a change.

## 4. Drive + debug the gRPC API
Proto: `src/Billing.Service/Protos/billing.proto`. Entry points:
- `ProcessCdrBatch { tenant, repeated cdrs_json }` — cdr → rate → validate → write.
- `GetMaxRatePerMinute`, `FinalizeAndSummarize`.
```bash
grpcurl -plaintext -d '{"tenant":"res_233","cdrs_json":["{ ...one cdr json... }"]}' \
  localhost:5293 telcobright.billing.v1.RatingService/ProcessCdrBatch
```
Postman: New → gRPC → `localhost:5293`, import `billing.proto`, pick `ProcessCdrBatch`. Full payload walkthrough:
`docs/postman-e2e-testing.md`.

**Breakpoints:** Rider / Visual Studio (set `Billing.Service` as startup) or VS Code (C# Dev Kit); break in
`BillingServiceImpl` / `CdrProcessor` / `SummaryOutboxWriter`. For more logs raise `Logging:LogLevel:Default` in
`appsettings.json`.

## 5. Outbox / summary debug (the decoupled path)
Everything is config — there are no environment switches.
1. Apply the outbox table into the CCL tenant schema(s): `src/Billing.Data/Sql/summary_outbox.sql` (creates `summary_affected`).
2. In `profile-dev.yml` set `billing.summary.enabled: true` (default `false` = legacy inline). The `summary` block
   already carries `ping-topic: cdr_summary_ping` and `bootstrap-servers` = CCL Kafka.
3. `cd src/Billing.Service && dotnet run`.

Call `ProcessCdrBatch` → observe a row in `summary_affected` (CCL DB) and a message on `cdr_summary_ping` (CCL
Kafka); the summary-service consumes it. Inspect the blob: `SELECT data FROM summary_affected\G` → base64-decode
→ gunzip → JSON.

## If a CCL endpoint is unreachable
This is expected without connectivity. Bring up the **VPN / route to the CCL subnet** (especially
`103.95.96.77`) and fill the DB creds in `profile-dev.yml`. **Do NOT substitute a local database** — the point
is to debug against the real CCL backend.
