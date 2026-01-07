#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_kill_leader"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
echo "Stopping leader node${leader}"
${COMPOSE} stop "node${leader}"

new_leader="$(wait_for_single_leader 30)"
assert_true "[[ ${new_leader} != ${leader} ]]" "new leader should differ"

kv_put "kill-leader-key" "after-failover" >/dev/null

echo "Starting old leader node${leader}"
${COMPOSE} start "node${leader}"
sleep 3

value="$(kv_get "kill-leader-key")"
assert_equal "after-failover" "${value}"

echo "Leader kill/recovery scenario passed."
