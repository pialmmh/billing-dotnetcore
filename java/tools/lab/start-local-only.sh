#!/usr/bin/env bash
# start-local-only.sh — the one way a service is started in a lab of this repo.
#
#   start-local-only.sh <quarkus-app dir> <run dir> [-e NAME]... [-- <java option>...]
#
# 1. BEFORE anything is started, lab-endpoints.py says every address the run directory's configuration names (over
#    the jar's own build-time defaults) and refuses unless each one is on this box. A run directory without its own
#    config/application.properties is refused: a start from there would run on what the jar carries, and this
#    repo's jar carries the registry and the profiles of a real deployment.
# 2. The service is started IN the run directory with an emptied environment — PATH, HOME, LANG, JAVA_HOME and the
#    variables named with -e stay (a secret is NAMED here, its value comes from the caller's environment and is on
#    no command line) — so that no variable of the caller's shell moves an address.
# 3. It is started with -Dbilling.lab.local-only=true. billing-core then says the endpoints IT resolved before it
#    dials one, and refuses the start itself when one is not this box (StartEndpoints) — the check that still holds
#    when step 1 judged a file the service did not read. Another service ignores the key.
#
# Output: <run dir>/service.log. Pid: <run dir>/pid. Exit 0 = started; 1 = refused or died (the reason is printed).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="${1:?usage: start-local-only.sh <quarkus-app dir> <run dir> [-e NAME]... [-- <java option>...]}"
run="${2:?the run directory}"
shift 2
keep=(PATH HOME LANG JAVA_HOME)
java_options=()
while [ $# -gt 0 ]; do
    case "$1" in
        -e) keep+=("${2:?-e needs a variable name}"); shift 2 ;;
        --) shift; java_options=("$@"); break ;;
        *)  echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

say_and_judge_the_endpoints() {
    local set_on_the_command_line=()
    for option in "${java_options[@]}"; do
        case "$option" in -D*=*) set_on_the_command_line+=("${option#-D}") ;; esac
    done
    python3 "$here/lab-endpoints.py" "$app" "$run" "${set_on_the_command_line[@]}"
}

refuse_a_named_variable_that_is_not_set() {
    for name in "${keep[@]:4}"; do
        [ -n "${!name:-}" ] || { echo "NOT STARTED: the variable $name is named with -e and is not set in this shell." >&2; exit 1; }
    done
}

start_with_an_emptied_environment() {
    (
        for name in $(compgen -e); do
            case " ${keep[*]} " in *" $name "*) ;; *) unset "$name" 2>/dev/null || true ;; esac
        done
        cd "$run"
        exec java "${java_options[@]}" -Dbilling.lab.local-only=true -jar "$app/quarkus-run.jar"
    ) > "$run/service.log" 2>&1 &
    echo $! > "$run/pid"
}

wait_until_it_is_up_or_gone() {
    local pid; pid="$(cat "$run/pid")"
    for _ in $(seq 1 240); do
        if grep -q "REFUSING TO START" "$run/service.log" 2>/dev/null || ! kill -0 "$pid" 2>/dev/null; then
            grep -hE "endpoint|REFUSING TO START" "$run/service.log" | cut -c1-600 || true
            echo "NOT RUNNING — see $run/service.log (last lines):" >&2
            tail -n 5 "$run/service.log" | cut -c1-400 >&2
            return 1
        fi
        if grep -qE "started in [0-9.]+s" "$run/service.log" 2>/dev/null; then
            grep -hE "endpoint|started in [0-9.]+s" "$run/service.log" | cut -c1-600 || true
            echo "started: pid $pid, log $run/service.log"
            return 0
        fi
        sleep 0.5
    done
    echo "NOT UP after 120 s — pid $pid, see $run/service.log" >&2
    return 1
}

say_and_judge_the_endpoints || { echo "NOT STARTED." >&2; exit 1; }
refuse_a_named_variable_that_is_not_set
start_with_an_emptied_environment
wait_until_it_is_up_or_gone
