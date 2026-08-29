#!/bin/bash
# Usage:
#   stop.sh           — tear everything down unconditionally.
#   stop.sh check     — report-only. If anything is up on the resolved
#                       ports, prints it and exits non-zero without
#                       touching anything. Used by `make e2e` so it
#                       refuses to run while anything else holds those
#                       ports (dev server, another e2e run, docker
#                       compose forwarding via `make box` — they all
#                       look the same here, on purpose).
#
# Resolves the dev PORT and SHADOW_PORT (preferring .envrc; falling back
# to the declared defaults in config.edn / shadow-cljs.edn) and tears
# down anything listening on them, plus the shadow-cljs watch JVM.
# Refuses to act if .dev-server.lock says the server belongs to the
# other environment (see start.sh).

MODE=${1:-stop}
if [ "$MODE" != "stop" ] && [ "$MODE" != "check" ]; then
  echo "Usage: $0 [stop|check]" >&2
  exit 2
fi

# resolve_port <ENVRC_VAR> <fallback-file>
# Returns the first integer found in either .envrc (after `export VAR=`)
# or the fallback EDN. The EDN fallback grabs the first integer that
# follows `:port` — works across aero forms like `#or [#env PORT 3110]`
# and `#shadow/env [... :default 9801]`.
resolve_port() {
  local var="$1" fallback_file="$2" val=""
  if [ -f .envrc ]; then
    val=$(grep -E "^[[:space:]]*export[[:space:]]+$var=" .envrc \
            | head -1 | sed -E "s/.*$var=([0-9]+).*/\1/" \
            | grep -E '^[0-9]+$' || true)
  fi
  if [ -z "$val" ] && [ -f "$fallback_file" ]; then
    val=$(grep -Eo ':port[^0-9]*[0-9]+' "$fallback_file" \
            | head -1 | grep -Eo '[0-9]+$' || true)
  fi
  printf '%s' "$val"
}

PORT=$(resolve_port PORT config.edn)
SHADOW_PORT=$(resolve_port SHADOW_PORT shadow-cljs.edn)

# Refuse to stop a server started in the other environment. Without this,
# a host-side `make stop` while the server runs in docker would kill
# Docker's port-forward proxy on $PORT (two PIDs on macOS — IPv4 + IPv6
# listeners), collapsing the container's networking. The reverse mismatch
# would silently miss the real PID. The lockfile is written by start.sh.
if [ -f /.dockerenv ]; then
  CURRENT=container
else
  CURRENT=host
fi

if [ -f .dev-server.lock ]; then
  LOCKED=$(cat .dev-server.lock)
  if [ "$LOCKED" != "$CURRENT" ]; then
    echo "ERROR: dev server was started in '$LOCKED' env, refusing to stop from '$CURRENT'."
    echo "Run stop from the matching environment, or remove .dev-server.lock to override."
    exit 1
  fi
fi

# Collect what's running before deciding whether to act, so check-mode can
# show the user a definitive list of what's blocking.
declare -a RUNNING_LABELS RUNNING_PORTS RUNNING_PIDS

scan_port() {
  local label="$1" port="$2"
  [ -z "$port" ] && return
  local pids
  pids=$(lsof -ti:"$port" 2>/dev/null | tr '\n' ' ' | sed 's/ $//' || true)
  [ -z "$pids" ] && return
  RUNNING_LABELS+=("$label")
  RUNNING_PORTS+=("$port")
  RUNNING_PIDS+=("$pids")
}

scan_port "dev server"  "$PORT"
scan_port "shadow-cljs" "$SHADOW_PORT"

if [ ${#RUNNING_LABELS[@]} -eq 0 ]; then
  if [ "$MODE" = "stop" ]; then
    # Still run shadow-cljs stop + clean lockfiles for tidiness.
    npx shadow-cljs stop 2>/dev/null || true
    rm -f .shadow-cljs.pid .nrepl-port .dev-server.lock
  fi
  echo "Nothing to stop."
  exit 0
fi

echo "Currently running:"
for i in "${!RUNNING_LABELS[@]}"; do
  echo "  - ${RUNNING_LABELS[$i]} on :${RUNNING_PORTS[$i]} (pid ${RUNNING_PIDS[$i]})"
done

if [ "$MODE" = "check" ]; then
  # Pure-report mode: don't touch anything, just report and fail so the
  # caller (e.g. `make e2e`) aborts. The user resolves it manually.
  exit 1
fi

for i in "${!RUNNING_LABELS[@]}"; do
  # shellcheck disable=SC2086
  kill ${RUNNING_PIDS[$i]} 2>/dev/null || true
done

npx shadow-cljs stop 2>/dev/null || true
rm -f .shadow-cljs.pid .nrepl-port .dev-server.lock

echo "Shut down:"
for i in "${!RUNNING_LABELS[@]}"; do
  echo "  - ${RUNNING_LABELS[$i]} :${RUNNING_PORTS[$i]} (pid ${RUNNING_PIDS[$i]})"
done
