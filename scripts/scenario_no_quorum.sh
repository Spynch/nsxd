#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_no_quorum"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
to_stop=()
for node in "${NODES[@]}"; do
  if [[ "$node" != "$leader" ]]; then
    to_stop+=("$node")
  fi
done

echo "Stopping nodes ${to_stop[*]}"
for node in "${to_stop[@]}"; do
  ${COMPOSE} stop "node${node}"
done

sleep 2
kv_put "no-quorum-key" "value" >/dev/null || true
assert_true "[[ ${KV_HTTP_STATUS} != 200 ]]" "write should fail without quorum"

for node in "${to_stop[@]}"; do
  ${COMPOSE} start "node${node}"
done

echo "No quorum scenario passed."
