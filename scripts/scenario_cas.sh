#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_cas"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset
wait_for_single_leader 30

kv_put "cas-key" "v1" >/dev/null
kv_cas "cas-key" "v1" "v2" >/dev/null
assert_equal "200" "${KV_HTTP_STATUS}" "CAS should succeed"

value="$(kv_get "cas-key")"
assert_equal "v2" "${value}"

kv_cas "cas-key" "wrong" "v3" >/dev/null || true
assert_equal "409" "${KV_HTTP_STATUS}" "CAS should fail on mismatch"

echo "CAS scenario passed."
