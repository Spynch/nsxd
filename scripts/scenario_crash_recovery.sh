#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_crash_recovery"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
follower=""
for node in "${NODES[@]}"; do
  if [[ "$node" != "$leader" ]]; then
    follower="$node"
    break
  fi
done

echo "Stopping follower node${follower}"
${COMPOSE} stop "node${follower}"

kv_put "crash-key" "durable" >/dev/null

echo "Starting follower node${follower}"
${COMPOSE} start "node${follower}"
sleep 4

value="$(kv_get "crash-key")"
assert_equal "durable" "${value}"

echo "Crash recovery scenario passed."
