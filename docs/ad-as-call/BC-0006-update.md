from: ARCH  to: BC  kind: update  number: 0006  date: 2026-10-07 13:49 +06:00  ·  not launched: the owner starts the round

# BC-0006 — the kinds as config-activated processors; and why the ad's new CDR row needs NOTHING from you

**The owner's priority (2026-10-07):** ad-sphere feature-complete and tested FIRST; billing-core is in that lane because it rates the ad's
records. Read `routesphere/docs/architecture/ad-is-a-call.md` §5 and, read-only, `ad-sphere/docs/ad-as-call/exchange/X/ARCH-0055-instruction.md`.

## 1 · The ad's CDR row changed — checked against your code today, nothing to do

ad-sphere main 058ff14 now writes the ad view's record as a call's: `outPartnerId` = the partner of the app that asked for the ad (was the
network division, which moved to the meta), and `outgoingRoute` = `<ruleId>/<app>/<zone>/<site>/<district>/<gw>/<msisdn>/<mac>` (seven
positions always, each value percent-encoded). The architect verified:

- `CdrEventPreprocessor` (:206–217) takes the wire's `outgoingRoute` and `outPartnerId` as they come — the shape is unchanged, only the
  meaning and the length;
- `cdr.OutgoingRoute` is `TEXT` in `sql/postgres/billing-tables.sql` (:57) and in `docs/local-debug-schema.sql` — no truncation;
- `OutgoingRouteNotEmpty` still holds: the new route is never empty (it always starts with a rule id, `0` when no rule matched);
- your own mediation summary has **no target tables for group 30** (`CdrSummaryContext.ServiceGroupTargetTables` = 10, 11, 15) and both
  readers guard a null (`PopulatePrevSummary` :61, `GenerateSummary` :82), so an SG30 record passes through without a summary row and
  without an error. The ad's summaries are summary-service's (its `sum_ad_*` tables, and the SG30 call-shaped one it fixes in SS-0003 S18).

**So:** confirm the three facts above with one test each if they are not pinned yet (a long route survives the round trip; an SG30 record
makes no mediation summary row and no error), and nothing else changes for the inversion.

## 2 · BC-0006 — the kinds as config-activated processors (the owner's ruling of 2026-10-05)

Single-operator stays: one deployment serves one operator. The work: **call / ad / wifi-session as kinds activated by config**, each a
processor registered by name, not a branch inside one method — the house rule of short named orchestrators. A deployment enables the kinds
it serves; an unknown kind is refused at start with the word, never silently ignored. The existing SG10/11/15 and SG30 behaviour must be
byte-identical after the change: the suite's numbers are the proof, plus one test per kind that a disabled kind refuses its record with the
word.

**F1 stays undecided:** branch `no-bundled-tenant` 6837718 (the jar enables no tenant) is PREPARED and NOT merged until the owner says.

## 3 · Rules that stand

Lab = this PC only (the PostgreSQL lab as BC-0005 used it), endpoints printed before a start, nothing dials `10.10.x` / `10.9.9.x` /
`103.95.96.77`; **never start the jar bare**; the password by environment-variable NAME; every new rule broken once, seen red, restored;
the suite green from a clean copy before each push; one commit per item; `date` before writing any time; your report `BC-0007-done.md`,
a draft from the first commit.
