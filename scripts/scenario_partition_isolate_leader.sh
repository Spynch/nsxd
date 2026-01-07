#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_partition_isolate_leader"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
for node in "${NODES[@]}"; do
  if [[ "$node" != "$leader" ]]; then
    toxiproxy_partition "$leader" "$node"
    toxiproxy_partition "$node" "$leader"
  fi
done

new_leader=""
deadline=$((SECONDS + 30))
while (( SECONDS < deadline )); do
  for node in "${NODES[@]}"; do
    if [[ "$node" == "$leader" ]]; then
      continue
    fi
    status="$(curl_status "$node")"
    role="$(printf '%s' "$status" | json_get "role")"
    if [[ "$role" == "LEADER" ]]; then
      new_leader="$node"
      break
    fi
  done
  [[ -n "$new_leader" ]] && break
  sleep 1
done
assert_true "[[ -n ${new_leader} ]]" "no new leader in majority"

for node in "${NODES[@]}"; do
  if [[ "$node" != "$leader" ]]; then
    toxiproxy_unpartition "$leader" "$node"
    toxiproxy_unpartition "$node" "$leader"
  fi
done

sleep 4
status="$(curl_status "$leader")"
role="$(printf '%s' "$status" | json_get "role")"
assert_true "[[ ${role} != LEADER ]]" "isolated leader should step down after heal"

echo "Partition isolate leader scenario passed."
