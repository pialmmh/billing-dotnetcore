# gRPC testing setup — Postman / grpcurl on a fresh PC

How to stand up a gRPC client (Postman **or** grpcurl) on **any machine** and exercise the three
`telcobright-billing-core` RPCs against the **live config-manager**, with copy‑paste payloads and a
troubleshooting section for every error we actually hit.

Service: `telcobright.billing.v1.RatingService` (proto: `src/Billing.Service/Protos/billing.proto`).
RPCs: `GetMaxRatePerMinute` (admission), `FinalizeAndSummarize` (post‑call compute), `ProcessCdrBatch`
(batch CDR write — the Kafka‑fed pipeline, driven here over gRPC).

---

## 1. Prerequisites

| Need | How |
|---|---|
| **.NET 8 SDK** | Windows: `winget install Microsoft.DotNet.SDK.8`, or the no‑admin script (below). macOS/Linux: `dotnet-install.sh`. Verify `dotnet --version` → `8.0.x`. |
| **Git + the repo** | `git clone <pialmmh/billing-dotnetcore>`; branch `master`. |
| **A gRPC client** | **Postman** (desktop), and/or **grpcurl** (single binary — see §5). |
| **Network to config-manager** | The service is **fail‑fast**: it won't start unless it can reach config-manager. Dev = `http://103.95.96.78:7072`. Preflight it (§3). |

No‑admin .NET 8 install (Windows PowerShell), if you can't/don't want a machine‑wide install:
```powershell
& ([scriptblock]::Create((irm 'https://dot.net/v1/dotnet-install.ps1'))) -Channel 8.0 -InstallDir "$env:USERPROFILE\.dotnet"
# then for each shell, or persist via setx:
$env:PATH = "$env:USERPROFILE\.dotnet;$env:PATH"; $env:DOTNET_ROOT = "$env:USERPROFILE\.dotnet"
```

---

## 2. Build

```bash
cd billing-dotnetcore
dotnet build telcobright-billing-core.sln -c Debug    # expect 0 warnings / 0 errors
```

---

## 3. Preflight the network (the service won't start otherwise)

config-manager must be reachable, or startup aborts (fail‑fast).
```powershell
# Windows PowerShell
Test-NetConnection 103.95.96.78 -Port 7072        # config-manager (HTTP) — must succeed
```
```bash
# bash
nc -vz 103.95.96.78 7072
```
Notes:
- The Kafka broker (`103.95.96.78:9092`) is only the **config‑reload trigger**, not CDRs. If you see repeating
  `rdkafka ... GroupCoordinator ... 10.x.x.x:9092 ... failed` lines, that's harmless — the broker advertises a
  private address; config still loads over HTTP and the service runs fine. To silence it, set
  `config-events.enabled: false` in the profile (below).
- To run against **prod** config-manager (`10.9.9.2:7072`), set `profile: prod` in `config/tenants.yml`.

---

## 4. Run the service

```bash
dotnet run --project src/Billing.Service --launch-profile http
```
- Listens on **`http://localhost:5293`** — HTTP/2 **plaintext (h2c)** → clients connect with **TLS OFF**.
- Wait for the log lines: `N tenant(s) loaded` and `Now listening on: http://localhost:5293`.
- First load pulls a ~100 MB payload from config-manager (~10 s).

> The build output DLLs are locked while the service runs. If you also run it from an IDE (Rider/VS), stop that
> instance before `dotnet run`, or you'll get `MSB3021: file is being used by another process`.

---

## 5. Client setup

### Postman (gRPC)
1. **New → gRPC Request**.
2. Server URL `localhost:5293`, **TLS OFF**.
3. **Import a .proto file** → `src/Billing.Service/Protos/billing.proto` (server reflection is **not** enabled, so you must import).
4. Pick service `telcobright.billing.v1.RatingService` → method.
5. Put the JSON in the **Message** tab (it must be **valid JSON** — Postman flags it if not), then **Invoke**.

### grpcurl
Download the standalone binary (no install): grab `grpcurl_<ver>_<os>_<arch>.zip` from
`github.com/fullstorydev/grpcurl/releases` and unzip. Then:
```bash
grpcurl -plaintext -import-path src/Billing.Service/Protos -proto billing.proto \
  -d @ localhost:5293 telcobright.billing.v1.RatingService/GetMaxRatePerMinute < payload.json
```
**Windows/PowerShell quoting trap:** PowerShell prepends a UTF‑8 BOM when piping, which grpcurl rejects
(`invalid character 'ï'`). Write the payload **without a BOM** and feed it via `cmd`:
```powershell
[System.IO.File]::WriteAllText("$PWD\payload.json", $json, (New-Object System.Text.UTF8Encoding($false)))
cmd /c "type payload.json | grpcurl -plaintext -import-path src\Billing.Service\Protos -proto billing.proto -d @ localhost:5293 telcobright.billing.v1.RatingService/GetMaxRatePerMinute"
```

---

## 6. The live tenant chain (ccl78) — needed to build payloads

```
telcobright   (admin / root)   depth 0   retail partner e.g. 336
 ├─ res_233   (reseller)       depth 1   retail partner e.g. 1
 │   └─ res_233_2 (sub-reseller) depth 2 retail partner e.g. 236
 └─ res_225   (reseller)
```
- `levels` carry the per‑tier partner **by depth**: `0 = telcobright (root)` … leaf = deepest reseller.
- **Service‑group detection is by partner *type***, looked up in that tenant's config:
  `PartnerType == 3` (retail) → **SG10** (outgoing); `PartnerType == 2` (icx) → **SG11** (incoming).
  A partner id that isn't present, or isn't retail/icx, in that tenant → `"service group not detected"`.
  ⚠️ Partner ids in your *CDR data* may not match the live ccl78 config — use ids that are actually
  retail/icx in ccl78 (above) when you want detection to succeed.

---

## 7. Payloads

> `service_type` is the enum **by name** (`VOICE`/`SMS`). All `*_epoch_millis` are int64 **as strings**.
> Example call below: outgoing `09646999999 → 8801789896378`, 2026‑06‑17 13:34:43/54/56 (UTC),
> 2 levels (`res_233 → telcobright`).

### 7a. `GetMaxRatePerMinute` (admission — no DB)
```json
{
  "tenant": "res_233",
  "partner_id": 1,
  "calling_number": "09646999999",
  "called_number": "8801789896378",
  "source_ip": "103.95.96.78",
  "service_type": "VOICE",
  "start_epoch_millis": "1781703283000",
  "levels": [
    { "depth": 0, "partner_id": 336 },
    { "depth": 1, "partner_id": 1 }
  ]
}
```
Today's expected reply: each tier detects SG10 and returns **package candidates**, but **no cash rate** →
`ok:false, reject_reason:"no rate or package for the call"` (see the rate blocker, §9).

### 7b. `FinalizeAndSummarize` (post‑call — compute only, no DB write)
```json
{
  "facts": {
    "tenant": "res_233", "service_type": "VOICE",
    "caller_number": "09646999999", "called_number": "8801789896378",
    "source_ip": "103.95.96.78", "incoming_route": "in", "outgoing_route": "out",
    "switch_id": 1, "session_id": "7def7167-dad1-4215-8680-a3e0d24d1b6a",
    "sip_call_id": "2-169791@103.95.96.78", "start_epoch_millis": "1781703283000"
  },
  "levels": [ { "depth": 0, "partner_id": 336 }, { "depth": 1, "partner_id": 1 } ],
  "answer_epoch_millis": "1781703294000", "end_epoch_millis": "1781703296000",
  "billsec": 2, "ring_seconds": 11, "answered": true,
  "hangup_cause": "NORMAL_CLEARING", "receiver_ip": "103.95.96.98",
  "read_codec": "PCMU", "reserved_amount": 0.013
}
```
Today's expected reply: `error:"unrated: ..."` (same rate blocker).

### 7c. `ProcessCdrBatch` (batch CDR write — **writes to MySQL**)
`CdrBatchRequest { tenant, repeated string cdrs_json }`. Each `cdrs_json` element is a **string** holding a
JSON `cdr` (the `MediationModel.cdr` POCO). Field names match the POCO and are **case‑insensitive**; keys
that don't match a POCO property are silently ignored. **Timestamps must be ISO‑8601 with `T`**
(`2026-06-17T13:34:54`) — a space (`2026-06-17 13:34:54`) fails to parse.

One cdr (readable):
```jsonc
{
  "SwitchId":1,"SequenceNumber":1881104,"ServiceGroup":10,
  "OriginatingCallingNumber":"09646999999","TerminatingCallingNumber":"09646999999",
  "OriginatingCalledNumber":"8801789896378","TerminatingCalledNumber":"8801789896378",
  "StartTime":"2026-06-17T13:34:43","AnswerTime":"2026-06-17T13:34:54",
  "ConnectTime":"2026-06-17T13:34:54","EndTime":"2026-06-17T13:34:56","SignalingStartTime":"2026-06-17T13:34:43",
  "DurationSec":2.055,"ChargingStatus":1,"InPartnerId":1,"OutPartnerId":234,
  "AnsIdTerm":23,"AnsPrefixTerm":"88017","AnsIdOrig":0,"PDD":2.447,
  "Category":1,"SubCategory":1,"UniqueBillId":"2-169791@103.95.96.78"
}
```
Full request (each cdr is **one unbroken line** inside `cdrs_json` — line breaks make the JSON invalid):
```json
{
  "tenant": "res_233",
  "cdrs_json": [
    "{\"SwitchId\":1,\"SequenceNumber\":1881104,\"ServiceGroup\":10,\"OriginatingCallingNumber\":\"09646999999\",\"TerminatingCallingNumber\":\"09646999999\",\"OriginatingCalledNumber\":\"8801789896378\",\"TerminatingCalledNumber\":\"8801789896378\",\"StartTime\":\"2026-06-17T13:34:43\",\"AnswerTime\":\"2026-06-17T13:34:54\",\"ConnectTime\":\"2026-06-17T13:34:54\",\"EndTime\":\"2026-06-17T13:34:56\",\"SignalingStartTime\":\"2026-06-17T13:34:43\",\"DurationSec\":2.055,\"ChargingStatus\":1,\"InPartnerId\":1,\"OutPartnerId\":234,\"AnsIdTerm\":23,\"AnsPrefixTerm\":\"88017\",\"AnsIdOrig\":0,\"PDD\":2.447,\"Category\":1,\"SubCategory\":1,\"UniqueBillId\":\"2-169791@103.95.96.78\"}"
  ]
}
```

`ProcessCdrBatch` **writes to MySQL**, so it needs DB credentials. With none set it refuses cleanly
(`datasource credentials not configured`). Supply them via configuration/env (the profile's OpenBao
secret-ref isn't wired yet) **before** running the service:
```bash
# host/port default to the profile's datasource (103.95.96.77:3306); override as needed
Billing__Db__Host=127.0.0.1 Billing__Db__User=<user> Billing__Db__Password=<pass> \
  dotnet run --project src/Billing.Service --launch-profile http
```
- **Dead host on purpose** (`127.0.0.1` with nothing listening) → it parses the CDRs then fails at connect:
  the connect error **proves the CDRs were received + parsed** without writing anywhere. Good for a receive check.
- **Real DB** (e.g. `103.95.96.77`) with valid creds + the tenant's schema (`res_233`) → it writes in one
  transaction. With the rate blocker open the cdrs are **unrated → written to `cdrerror`** (so
  `committed:true, rated:0, errored:N, cdr_errors_written:N`). Writing to a remote client DB is gated —
  get explicit go‑ahead first.

---

## 8. Verify / debug

Run the service from an IDE (Rider/VS) in **Debug**, set breakpoints, then Invoke from Postman/grpcurl:

| Where | File |
|---|---|
| batch entry — inspect `request.Tenant` + `request.CdrsJson` | `BillingServiceImpl.ProcessCdrBatch` (`:56`, `:67`) |
| admission chain + per‑tier rating | `GetMaxRatePerMinute` / `BuildChain` → `MaxRateTierRater.RateTier` → `BasicCharge.MatchCustomerRate` |
| SG detection (partner type) | `ServiceGroupDetection.Detect` / `SgDomOffnetOut.Detect` / `SgDomOffnetIn.Detect` |
| rate match (empty today) | `BasicCharge.MatchRate` (`tuples.Count == 0` → the blocker) |
| per‑level settle | `FinalizeEngine.Finalize` |

A grpcurl `DeadlineExceeded` against a debugger‑attached service usually means **your request is paused at a
breakpoint** — not a failure.

---

## 9. Troubleshooting (every error we actually hit)

| Symptom | Cause → fix |
|---|---|
| `unknown tenant ''` (empty quotes) | The request's `tenant` arrived **blank**. In Postman the Message wasn't applied (wrong tab / not re‑Invoked) **or** a `cdrs_json` string had a **line break** → invalid JSON → empty message sent. Put each cdr string on **one line**; confirm valid JSON; re‑Invoke. Test with the minimal body `{"tenant":"res_233","cdrs_json":[]}`. |
| `unknown tenant 'res_233'` (name shown) | Tenant tree not loaded / wrong dbName. Check startup logged `N tenant(s) loaded` and use a real dbName (`res_233`, `res_233_2`, `telcobright`). |
| `datasource credentials not configured` | `ProcessCdrBatch` needs `Billing__Db__User`/`Password` (and host). Set them and restart (§7c). |
| `service group not detected` | The tier's `partner_id` isn't a **retail(3)/icx(2)** partner in that tenant's config. Use ids that are (ccl78: 236/1/336). See §6. |
| `no rate or package for the call` / `unrated: ...` | The **rate blocker** (§ below) — config-manager isn't serving `mediationContext.ratePlanAssignmentTuples`, so there's no cash rate yet. Packages still come back. |
| `cdr json parse error: ...` | A `cdrs_json` entry is malformed — usually a space‑separated timestamp (use `T`) or a stray line break. |
| `invalid character 'ï'` (grpcurl) | UTF‑8 BOM in the payload from PowerShell piping — write BOM‑free + feed via `cmd type` (§5). |
| repeating `rdkafka ... GroupCoordinator ... failed` | Harmless — config‑event Kafka coordinator advertises a private IP; CDRs don't come via Kafka. Set `config-events.enabled: false` to silence. |
| service won't start | config-manager unreachable (fail‑fast). Preflight `103.95.96.78:7072` (§3). |

**The rate blocker (why nothing prices yet):** config-manager serves rates as `context.ratePlanWiseTodaysRates`
/ `rateAssignsCustomer` / `rateAssignsSupplier`, but its `mediationContext` has no `ratePlanAssignmentTuples`.
The rater builds its `RateCache` from those tuples → empty → no cash rate. Until that's closed (config-manager
emits the tuples, or billing-core maps the served rate‑assigns in `ConfigManagerMapper`), `GetMaxRatePerMinute`
returns packages‑only/reject and `FinalizeAndSummarize` returns `unrated`.
