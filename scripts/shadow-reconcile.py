#!/usr/bin/env python3
"""
Shadow-run reconciliation: compare the new billing-core engine's output (shadow DB) against the
production billing figures for the SAME calls, keyed by UniqueBillId (= routesphere callId).

READ-ONLY. Shells out to the `mysql` client (no driver needed). Never writes any database.

Two independent production references are used, both already captured with each call:
  1. cdr.CustomerRate on the SHADOW row itself — this is the rate routesphere put on the live Kafka
     envelope (mapped by CdrEventPreprocessor), i.e. the production rate for that exact call. Comparing it
     to the shadow chargeable's unitPriceOrCharge is a self-contained, single-DB rate reconciliation.
  2. (optional, --with-prod-db) the production DB's own cdr row for the same UniqueBillId — an independent
     cross-check of duration and the incumbent's persisted rate.

Env (shadow DB = the test schema billing-core writes):
  SHADOW_HOST SHADOW_PORT SHADOW_USER SHADOW_PASS SHADOW_DB (default telcobright)
Optional production DB cross-check (read-only):
  PROD_HOST PROD_PORT PROD_USER PROD_PASS PROD_DB (default telcobright)   [enable with --with-prod-db]
Window:
  WINDOW_MINUTES (default 60)   RATE_TOLERANCE (default 0.0001)   AMOUNT_TOLERANCE (default 0.01)

Usage:
  SHADOW_HOST=<shadow-db-host> SHADOW_USER=tbuser SHADOW_PASS=*** WINDOW_MINUTES=120 \
    python3 shadow-reconcile.py
  ... PROD_HOST=<prod-db-host> PROD_USER=tbuser PROD_PASS=*** python3 shadow-reconcile.py --with-prod-db
"""
import os, sys, subprocess, collections

def env(k, d=None):
    v = os.environ.get(k)
    return v if v not in (None, "") else d

def q(host, port, user, pw, sql):
    """Run one read-only query, return list of tab-split rows (no header)."""
    cmd = ["mysql", "-h", host, "-P", str(port), "-u", user, f"-p{pw}", "-N", "-B", "-e", sql]
    out = subprocess.run(cmd, capture_output=True, text=True)
    lines = [l for l in out.stdout.splitlines() if l.strip() and "Using a password" not in l
             and "World-writable" not in l]
    return [l.split("\t") for l in lines]

def num(x):
    try: return float(x)
    except (TypeError, ValueError): return None

def main():
    with_prod = "--with-prod-db" in sys.argv
    W   = int(env("WINDOW_MINUTES", "60"))
    RT  = float(env("RATE_TOLERANCE", "0.0001"))
    AT  = float(env("AMOUNT_TOLERANCE", "0.01"))
    sh  = dict(host=env("SHADOW_HOST", "127.0.0.1"), port=env("SHADOW_PORT", "3306"),
               user=env("SHADOW_USER", "tbuser"), pw=env("SHADOW_PASS", ""), db=env("SHADOW_DB", "telcobright"))
    pr  = dict(host=env("PROD_HOST", "127.0.0.1"), port=env("PROD_PORT", "3306"),
               user=env("PROD_USER", "tbuser"), pw=env("PROD_PASS", ""), db=env("PROD_DB", "telcobright"))

    print(f"== shadow reconciliation ==  window={W}m  shadow={sh['host']}/{sh['db']}"
          + (f"  prod={pr['host']}/{pr['db']}" if with_prod else "  (single-DB rate mode)"))

    # ---- shadow: rated calls (kafka-ingested) with the production rate carried on the cdr ----
    rated = q(sh["host"], sh["port"], sh["user"], sh["pw"], f"""
        SELECT c.UniqueBillId, c.InPartnerId, c.ServiceGroup, c.DurationSec+0,
               c.CustomerRate+0, ch.unitPriceOrCharge+0, ch.BilledAmount+0
        FROM {sh['db']}.cdr c
        JOIN {sh['db']}.acc_chargeable ch
          ON ch.uniqueBillId=c.UniqueBillId AND ch.assignedDirection=1
        WHERE c.FileName='kafka:cdr' AND c.StartTime >= NOW() - INTERVAL {W} MINUTE""")
    errored = q(sh["host"], sh["port"], sh["user"], sh["pw"], f"""
        SELECT UniqueBillId, LEFT(ErrorCode,80)
        FROM {sh['db']}.cdrerror
        WHERE FileName='kafka:cdr' AND StartTime >= NOW() - INTERVAL {W} MINUTE""")

    print(f"\nshadow calls in window: rated={len(rated)}  errored(cdrerror)={len(errored)}")
    if not rated:
        print("  (no rated shadow calls yet — let the shadow consume more live traffic)")
    # ---- rate reconciliation (single-DB: production rate on the cdr vs shadow chargeable rate) ----
    rate_ok = rate_bad = 0
    by_sg = collections.Counter()
    mismatches = []
    for uid, inp, sg, dur, prate, srate, samt in rated:
        by_sg[sg] += 1
        p, s = num(prate), num(srate)
        if p is None or s is None:
            rate_bad += 1; mismatches.append((uid, inp, sg, prate, srate, "null rate")); continue
        if abs(p - s) <= RT: rate_ok += 1
        else: rate_bad += 1; mismatches.append((uid, inp, sg, prate, srate, f"Δ={p-s:+.5f}"))
    if rated:
        pct = 100.0 * rate_ok / len(rated)
        print(f"\nRATE parity (production cdr.CustomerRate vs shadow unitPriceOrCharge):")
        print(f"  match={rate_ok}  mismatch={rate_bad}  = {pct:.2f}% match")
        print(f"  by service group: " + ", ".join(f"SG{sg}:{n}" for sg, n in sorted(by_sg.items())))
        for uid, inp, sg, pr_, sr_, why in mismatches[:20]:
            print(f"    MISMATCH uid={uid} partner={inp} SG={sg} prod={pr_} shadow={sr_} {why}")
        if len(mismatches) > 20:
            print(f"    ... and {len(mismatches)-20} more")

    # ---- optional cross-DB check against the production database ----
    if with_prod:
        uids = [r[0] for r in rated]
        prod = {}
        for i in range(0, len(uids), 500):
            chunk = uids[i:i+500]
            inlist = ",".join("'" + u.replace("'", "") + "'" for u in chunk)
            for row in q(pr["host"], pr["port"], pr["user"], pr["pw"],
                         f"SELECT UniqueBillId, DurationSec+0, CustomerRate+0 FROM {pr['db']}.cdr "
                         f"WHERE UniqueBillId IN ({inlist})"):
                prod[row[0]] = row
        found = sum(1 for u in uids if u in prod)
        dur_ok = dur_bad = prate_ok = prate_bad = 0
        for uid, inp, sg, dur, prate, srate, samt in rated:
            pr_row = prod.get(uid)
            if not pr_row: continue
            if num(pr_row[1]) is not None and num(dur) is not None and abs(num(pr_row[1]) - num(dur)) <= 0.5:
                dur_ok += 1
            else: dur_bad += 1
            if num(pr_row[2]) is not None and num(srate) is not None and abs(num(pr_row[2]) - num(srate)) <= RT:
                prate_ok += 1
            else: prate_bad += 1
        print(f"\ncross-DB check vs production {pr['host']}/{pr['db']}:")
        print(f"  shadow calls found in prod by UniqueBillId: {found}/{len(uids)}")
        if found == 0:
            print("  NOTE: the incumbent CSV path stores UniqueBillId as a NUMERIC id, not routesphere's callId")
            print("        UUID, and the UUID is not stored in the prod cdr — so there is NO shared per-call key.")
            print("        Rely on the single-DB RATE reconciliation above (production rate is captured on the")
            print("        same Kafka message the engine rated). For per-call charge parity across systems,")
            print("        either persist the envelope chargedAmount on the shadow cdr, or add the callId to the")
            print("        incumbent CSV; for a macro check, compare per-partner/per-hour totals between the DBs.")
        print(f"  duration match: {dur_ok} ok / {dur_bad} off (>0.5s)")
        print(f"  rate match (prod cdr.CustomerRate vs shadow rate): {prate_ok} ok / {prate_bad} off")

    if errored:
        print(f"\nshadow cdrerror reasons (calls the engine could not rate — investigate for parity):")
        reasons = collections.Counter(r[1] for r in errored)
        for reason, n in reasons.most_common(10):
            print(f"  {n:5d}  {reason}")

    print("\nverdict: RATE parity is the primary signal. Investigate any mismatch/cdrerror before cutover.")

if __name__ == "__main__":
    main()
