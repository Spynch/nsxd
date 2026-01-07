#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_pause_follower"
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

echo "Pausing follower node${follower}"
${COMPOSE} pause "node${follower}"

kv_put "pause-follower-key" "value1" >/dev/null

echo "Unpausing follower node${follower}"
${COMPOSE} unpause "node${follower}"

sleep 3
status="$(curl_status "$follower")"
applied="$(printf '%s' "$status" | json_get "lastApplied")"
assert_true "[[ ${applied} -ge 1 ]]" "follower did not catch up"

echo "Pause follower scenario passed."
