#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_pause_leader"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
echo "Pausing leader node${leader}"
${COMPOSE} pause "node${leader}"

new_leader="$(wait_for_single_leader 30)"
assert_true "[[ ${new_leader} != ${leader} ]]" "new leader should differ"

echo "Unpausing leader node${leader}"
${COMPOSE} unpause "node${leader}"
sleep 3

status="$(curl_status "$leader")"
role="$(printf '%s' "$status" | json_get "role")"
assert_true "[[ ${role} != LEADER ]]" "old leader should step down"

echo "Pause leader scenario passed."
