#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_kv_replication"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset
leader="$(wait_for_single_leader 30)"

kv_put "replication-key" "replication-value" >/dev/null

for node in "${NODES[@]}"; do
  status="$(curl_status "$node")"
  applied="$(printf '%s' "$status" | json_get "lastApplied")"
  assert_true "[[ ${applied} -ge 1 ]]" "node ${node} did not apply entry"
done

echo "Replication verified on all nodes."
