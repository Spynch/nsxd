#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

wait_for_toxiproxy() {
  local timeout="${1:-30}"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if curl -fsS "http://${TOXIPROXY_HOST}/version" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "toxiproxy not available at ${TOXIPROXY_HOST} after ${timeout}s" >&2
  return 1
}

wait_for_toxiproxy
toxiproxy_setup
toxiproxy_reset
echo "Toxiproxy proxies created and reset."
