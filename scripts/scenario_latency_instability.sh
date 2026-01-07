#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_latency_instability"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

wait_for_single_leader 30

baseline="$(curl -sS "http://${NODE_HTTP[1]}/metrics" | json_get "leaderChangesTotal")"
echo "Baseline leader changes: ${baseline}"

for key in "${!PROXY_PORTS[@]}"; do
  src="${key%-*}"
  dst="${key#*-}"
  toxiproxy_add_latency "$src" "$dst" 200 50
done

sleep 5
stable="$(curl -sS "http://${NODE_HTTP[1]}/metrics" | json_get "leaderChangesTotal")"
echo "Leader changes under latency: ${stable}"

toxiproxy_reset

for key in "${!PROXY_PORTS[@]}"; do
  src="${key%-*}"
  dst="${key#*-}"
  toxiproxy_add_latency "$src" "$dst" 800 200
done

sleep 8
flappy="$(curl -sS "http://${NODE_HTTP[1]}/metrics" | json_get "leaderChangesTotal")"
echo "Leader changes under heavy latency: ${flappy}"

assert_true "[[ ${flappy} -ge ${baseline} ]]" "leader changes counter should not decrease"

echo "Latency instability scenario recorded."
