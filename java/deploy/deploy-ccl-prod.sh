#!/usr/bin/env bash
###############################################################################
# deploy-ccl-prod.sh — production deploy for telcobright billing-core (CCL / ccl98)
#
# Runs ON the CCL host (103.95.96.103). Builds the Quarkus fast-jar from the build
# tree, backs up the running deploy, swaps in the new jar, GRACEFULLY restarts the
# service (drains the in-flight CDR batch + commits Kafka offsets first), verifies a
# healthy boot, and AUTO-ROLLS-BACK if the service does not come up clean.
#
# Usage:
#   ./deploy-ccl-prod.sh                # build + deploy + verify (prompts to proceed)
#   ./deploy-ccl-prod.sh --yes          # no prompt (for automation)
#   ./deploy-ccl-prod.sh --skip-build   # deploy the already-built target/ (no mvn)
#   ./deploy-ccl-prod.sh --no-rollback  # do not auto-rollback on health failure
#
# Safe to re-run. Does NOT touch the DB, the flag, or /opt/routesphere (production
# routesphere). Keeps the last 5 backups.
###############################################################################
set -euo pipefail

# ---- config (CCL / ccl98) ----
BUILD_DIR="${BILLING_BUILD_DIR:-$HOME/billing-build/java}"
APP_DIR="${BILLING_APP_DIR:-/opt/billing/app}"          # telcobright-owned (755) — no sudo to copy
SERVICE="${BILLING_SERVICE:-billing.service}"           # sudo systemctl restart is passwordless
LOG="${BILLING_LOG:-/var/log/billing/billing.log}"
KAFKA_GROUP="${BILLING_KAFKA_GROUP:-billing-core-shadow}"
BACKUP_ROOT="${BILLING_BACKUP_ROOT:-$HOME}"
KEEP_BACKUPS=5
HEALTH_TIMEOUT=90                                        # seconds to wait for a healthy boot

YES=0; SKIP_BUILD=0; ROLLBACK=1
for a in "$@"; do case "$a" in
  --yes|-y) YES=1;; --skip-build) SKIP_BUILD=1;; --no-rollback) ROLLBACK=0;;
  *) echo "unknown arg: $a" >&2; exit 2;; esac; done

TS="$(date +%Y%m%d-%H%M%S)"
BACKUP="$BACKUP_ROOT/opt-billing-app.bak.$TS"
say(){ echo "[$(date +%T)] $*"; }
die(){ echo "[$(date +%T)] ERROR: $*" >&2; exit 1; }

# ---- 0. preflight ----
[ -d "$BUILD_DIR" ] || die "build dir not found: $BUILD_DIR"
[ -d "$APP_DIR" ]   || die "app dir not found: $APP_DIR"
command -v mvn >/dev/null || [ "$SKIP_BUILD" = 1 ] || die "mvn not on PATH (or pass --skip-build)"
COMMIT="$(git -C "$BUILD_DIR" rev-parse --short HEAD 2>/dev/null || echo 'n/a')"
say "CCL production deploy — service=$SERVICE app=$APP_DIR build=$BUILD_DIR commit=$COMMIT"
if [ "$YES" != 1 ]; then
  read -r -p "Proceed with PRODUCTION deploy? [y/N] " ans
  [ "$ans" = y ] || [ "$ans" = Y ] || { say "aborted."; exit 0; }
fi

# ---- 1. build the fast-jar ----
if [ "$SKIP_BUILD" = 1 ]; then
  say "skip-build: using existing $BUILD_DIR/target/quarkus-app"
else
  say "building (mvn -B -DskipTests package) …"
  ( cd "$BUILD_DIR" && mvn -B -DskipTests package >/tmp/deploy-ccl-build.log 2>&1 ) \
    || { tail -30 /tmp/deploy-ccl-build.log; die "build FAILED (see /tmp/deploy-ccl-build.log)"; }
  grep -q "BUILD SUCCESS" /tmp/deploy-ccl-build.log || die "no BUILD SUCCESS"
fi
FASTJAR_DIR="$BUILD_DIR/target/quarkus-app"
[ -f "$FASTJAR_DIR/quarkus-run.jar" ] || die "fast-jar not produced: $FASTJAR_DIR/quarkus-run.jar"
say "fast-jar OK: $(ls -la "$FASTJAR_DIR"/app/*.jar | awk '{print $5" bytes",$NF}')"

# ---- 2. backup the running deploy (for rollback) ----
cp -a "$APP_DIR" "$BACKUP" || die "backup failed"
say "backup: $BACKUP"
OLD_PID="$(systemctl show "$SERVICE" -p MainPID --value 2>/dev/null || echo '?')"

# ---- 3. swap in the new jar (APP_DIR is telcobright-owned; no sudo needed) ----
say "deploying new fast-jar into $APP_DIR …"
rm -rf "$APP_DIR"/app "$APP_DIR"/lib "$APP_DIR"/quarkus "$APP_DIR"/quarkus-run.jar "$APP_DIR"/quarkus-app-dependencies.txt
cp -a "$FASTJAR_DIR"/. "$APP_DIR"/ || die "copy into $APP_DIR failed"

# ---- 4. graceful restart (drains in-flight batch + commits offsets first) ----
MARK="$(date '+%Y-%m-%d %H:%M:%S')"
say "graceful restart: sudo systemctl restart $SERVICE"
sudo systemctl restart "$SERVICE" || die "systemctl restart failed"

# ---- 5. verify healthy boot ----
rollback(){
  [ "$ROLLBACK" = 1 ] || { say "auto-rollback disabled; leaving as-is. Backup: $BACKUP"; exit 1; }
  say "ROLLING BACK to $BACKUP …"
  rm -rf "$APP_DIR"/app "$APP_DIR"/lib "$APP_DIR"/quarkus "$APP_DIR"/quarkus-run.jar "$APP_DIR"/quarkus-app-dependencies.txt
  cp -a "$BACKUP"/. "$APP_DIR"/
  sudo systemctl restart "$SERVICE"
  sleep 8
  say "rollback restart done; service=$(systemctl is-active "$SERVICE") pid=$(systemctl show "$SERVICE" -p MainPID --value)"
  die "deploy failed health check — ROLLED BACK to previous build ($BACKUP)."
}
say "waiting for healthy boot (up to ${HEALTH_TIMEOUT}s) …"
ok=0
for _ in $(seq 1 $((HEALTH_TIMEOUT/3))); do
  if [ "$(systemctl is-active "$SERVICE")" = active ] \
     && awk -v a="$MARK" '$0>=a' "$LOG" 2>/dev/null | grep -q "started in"; then ok=1; break; fi
  sleep 3
done
[ "$ok" = 1 ] || rollback
NEW_PID="$(systemctl show "$SERVICE" -p MainPID --value)"
[ "$NEW_PID" != "$OLD_PID" ] || rollback

# boot-window checks: consuming Kafka, no fatal errors (ignore benign old-pid Kafka shutdown noise + config-test)
awk -v a="$MARK" '$0>=a' "$LOG" | grep -q "cdr ingest listening" \
  && say "cdr ingest listening (group=$KAFKA_GROUP)" || say "WARN: no 'cdr ingest listening' seen (cdr-ingest disabled?)"
FATAL="$(awk -v a="$MARK" '$0>=a' "$LOG" | grep -E ' (ERROR|FATAL) ' \
        | grep -viE 'config-manager down \(test\)|Failed to close coordinator|Error Occurred After Shutdown' | head -5 || true)"
[ -z "$FATAL" ] || { say "fatal log lines after boot:"; echo "$FATAL"; rollback; }

# graceful-drain confirmation from the OLD pid (informational)
awk -v a="$MARK" '$0>=a' "$LOG" | grep -q "drained cleanly" \
  && say "previous instance drained cleanly (no lost in-flight batch)" \
  || say "note: no 'drained cleanly' line (prior stop may not have been graceful)"

# ---- 6. prune old backups (keep last N) ----
ls -1dt "$BACKUP_ROOT"/opt-billing-app.bak.* 2>/dev/null | tail -n +$((KEEP_BACKUPS+1)) | xargs -r rm -rf

say "DEPLOY OK — service=active pid=$NEW_PID commit=$COMMIT. Backup kept: $BACKUP"
say "rollback if needed: restore $BACKUP into $APP_DIR and 'sudo systemctl restart $SERVICE'"
