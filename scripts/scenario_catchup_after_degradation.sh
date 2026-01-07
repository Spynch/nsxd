#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_catchup_after_degradation"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

wait_for_single_leader 30

toxiproxy_add_latency 1 3 500 200
toxiproxy_add_latency 3 1 500 200
toxiproxy_add_loss 1 3
toxiproxy_add_loss 3 1
toxiproxy_add_latency 2 3 500 200
toxiproxy_add_latency 3 2 500 200
toxiproxy_add_loss 2 3
toxiproxy_add_loss 3 2

for i in {1..5}; do
  kv_put "degrade-key-${i}" "v${i}" >/dev/null
done

toxiproxy_reset
sleep 5

status3="$(curl_status 3)"
applied3="$(printf '%s' "$status3" | json_get "lastApplied")"
assert_true "[[ ${applied3} -ge 5 ]]" "node 3 should catch up after degradation"

echo "Catchup after degradation scenario passed."
