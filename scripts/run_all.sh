#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

scenarios=(
  "scenario_smoke.sh"
  "scenario_kv_replication.sh"
  "scenario_cas.sh"
  "scenario_kill_leader.sh"
  "scenario_pause_follower.sh"
  "scenario_pause_leader.sh"
  "scenario_crash_recovery.sh"
  "scenario_no_quorum.sh"
  "scenario_partition_2_1.sh"
  "scenario_partition_isolate_leader.sh"
  "scenario_latency_instability.sh"
  "scenario_catchup_after_degradation.sh"
)

passed=()
failed=()

for scenario in "${scenarios[@]}"; do
  echo "=== Running ${scenario} ==="
  if "${SCRIPT_DIR}/${scenario}"; then
    passed+=("${scenario}")
  else
    failed+=("${scenario}")
  fi
done

echo "=== сценарии: summary ==="
echo "PASSED: ${passed[*]:-none}"
echo "FAILED: ${failed[*]:-none}"

if [[ "${#failed[@]}" -gt 0 ]]; then
  exit 1
fi
