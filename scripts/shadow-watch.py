#!/usr/bin/env python3
"""
Overnight shadow watcher: for a SETTLED time window, compare the shadow engine (test DB) against the
production database and append one timestamped summary line. READ-ONLY; cron-friendly; no driver (shells to
`mysql`). Intended to run every ~30 min so the morning log is a time-series of match rates.

Signals per window:
  * RATE PARITY (per-call, precise): the production per-minute rate carried on each shadow cdr
    (CustomerRate, from the same Kafka envelope the engine rated) vs the shadow chargeable's
    unitPriceOrCharge. This is the strongest correctness signal; rate parity + equal duration => charge parity.
  * COVERAGE (production-DB-wise): shadow call count & total billable duration vs the production DB's cdr for
    the same StartTime window — did the engine see the same calls, with the same inputs?

Window = [now-LAG-WINDOW, now-LAG], computed on the SERVER clock, lagged so both the near-real-time shadow
and the per-minute production CSV writer have settled.

Env: SHADOW_HOST/PORT/USER/PASS/DB, PROD_HOST/PORT/USER/PASS/DB (all default telcobright),
     WATCH_LAG_MIN (default 15), WATCH_WINDOW_MIN (default 60), RATE_TOLERANCE (default 0.0001).
"""
import os, subprocess

def env(k, d=None):
    v = os.environ.get(k)
    return v if v not in (None, "") else d

def q(h, p, u, pw, sql):
    r = subprocess.run(["mysql", "-h", h, "-P", str(p), "-u", u, f"-p{pw}", "-N", "-B", "-e", sql],
                       capture_output=True, text=True)
    return [l.split("\t") for l in r.stdout.splitlines()
            if l.strip() and "Using a password" not in l and "World-writable" not in l]

def fnum(x):
    try: return float(x)
    except (TypeError, ValueError): return 0.0

def main():
    LAG = int(env("WATCH_LAG_MIN", "15")); WIN = int(env("WATCH_WINDOW_MIN", "60"))
    RT = env("RATE_TOLERANCE", "0.0001")
    sh = dict(h=env("SHADOW_HOST", "127.0.0.1"), p=env("SHADOW_PORT", "3306"),
              u=env("SHADOW_USER", "tbuser"), pw=env("SHADOW_PASS", ""), db=env("SHADOW_DB", "telcobright"))
    pr = dict(h=env("PROD_HOST", "127.0.0.1"), p=env("PROD_PORT", "3306"),
              u=env("PROD_USER", "tbuser"), pw=env("PROD_PASS", ""), db=env("PROD_DB", "telcobright"))
    W0 = f"NOW() - INTERVAL {LAG+WIN} MINUTE"; W1 = f"NOW() - INTERVAL {LAG} MINUTE"

    ts = (q(sh["h"], sh["p"], sh["u"], sh["pw"], "SELECT NOW()") or [["?"]])[0][0]

    # shadow: rate parity + rated aggregate (this test DB is small, the join is cheap)
    sp = q(sh["h"], sh["p"], sh["u"], sh["pw"], f"""
        SELECT SUM(ABS(c.CustomerRate-ch.unitPriceOrCharge)<={RT}), COUNT(*),
               ROUND(COALESCE(SUM(c.DurationSec),0),1), ROUND(COALESCE(SUM(ch.BilledAmount),0),4)
        FROM {sh['db']}.cdr c
        JOIN {sh['db']}.acc_chargeable ch ON ch.uniqueBillId=c.UniqueBillId AND ch.assignedDirection=1
        WHERE c.FileName='kafka:cdr' AND c.StartTime>={W0} AND c.StartTime<{W1}""")
    rok, rtot, sdur, scharge = (sp[0] + ["0","0","0","0"])[:4] if sp else ("0","0","0","0")
    rok = "0" if rok in (None, "NULL", "") else rok
    serr = (q(sh["h"], sh["p"], sh["u"], sh["pw"],
              f"SELECT COUNT(*) FROM {sh['db']}.cdrerror WHERE FileName='kafka:cdr' "
              f"AND StartTime>={W0} AND StartTime<{W1}") or [["0"]])[0][0]

    # production: cdr count + duration for the same window (partition-pruned on starttime -> fast)
    pp = q(pr["h"], pr["p"], pr["u"], pr["pw"],
           f"SELECT COUNT(*), ROUND(COALESCE(SUM(DurationSec),0),1) FROM {pr['db']}.cdr "
           f"WHERE StartTime>={W0} AND StartTime<{W1}")
    pcalls, pdur = (pp[0] + ["0","0"])[:2] if pp else ("0","0")

    pct = (100.0*int(rok)/int(rtot)) if rtot not in (None,"NULL","0","") else float("nan")
    dcalls = (fnum(rtot)+fnum(serr)) - fnum(pcalls)
    ddur = fnum(sdur) - fnum(pdur)
    print(f"[{ts}] win=[-{LAG+WIN}m..-{LAG}m] "
          f"RATE={rok}/{rtot} ({pct:.1f}%) | "
          f"SHADOW calls={rtot}(+{serr}err) dur={sdur}s charge={scharge} | "
          f"PROD calls={pcalls} dur={pdur}s | "
          f"Δcalls={dcalls:+.0f} Δdur={ddur:+.1f}s"
          + (f"  <<< RATE MISMATCH" if (rtot not in ('0','',None) and rok != rtot) else ""))

if __name__ == "__main__":
    main()
