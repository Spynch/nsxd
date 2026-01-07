#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_partition_2_1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

toxiproxy_partition 1 3
toxiproxy_partition 3 1
toxiproxy_partition 2 3
toxiproxy_partition 3 2

leader=""
deadline=$((SECONDS + 30))
while (( SECONDS < deadline )); do
  for node in 1 2; do
    status="$(curl_status "$node")"
    role="$(printf '%s' "$status" | json_get "role")"
    if [[ "$role" == "LEADER" ]]; then
      leader="$node"
      break
    fi
  done
  [[ -n "$leader" ]] && break
  sleep 1
done
assert_true "[[ -n ${leader} ]]" "no leader in majority partition"
kv_put "partition-key" "majority" >/dev/null

status3="$(curl_status 3)"
applied3="$(printf '%s' "$status3" | json_get "lastApplied")"
assert_true "[[ ${applied3} -lt 1 ]]" "minority node should not apply entries"

toxiproxy_unpartition 1 3
toxiproxy_unpartition 3 1
toxiproxy_unpartition 2 3
toxiproxy_unpartition 3 2

sleep 4
status3="$(curl_status 3)"
applied3="$(printf '%s' "$status3" | json_get "lastApplied")"
assert_true "[[ ${applied3} -ge 1 ]]" "minority node should catch up"

echo "Partition 2-1 scenario passed."
