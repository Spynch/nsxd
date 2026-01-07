#!/usr/bin/env bash
set -euo pipefail

# Runs only toxiproxy-based scenarios (no docker stop/kill/pause)
# Produces artifacts/<run_ts>/summary.csv and copies to artifacts/summary.csv

RUN_TS="$(date +%Y%m%d_%H%M%S)"
RUN_DIR="artifacts/${RUN_TS}"
mkdir -p "$RUN_DIR"

export ARTIFACTS_RUN_DIR="$RUN_DIR"

source "./scripts/summary.sh"

summary_init "$RUN_DIR"

run_scenario() {
  local scenario="$1"
  local script="$2"

  local leader_before leader_after start end dur

  leader_before="$(./scripts/find_leader.sh 2>/dev/null || echo "unknown")"
  start="$(date +%s)"

  if bash "$script"; then
    end="$(date +%s)"
    dur="$((end-start))"
    leader_after="$(./scripts/find_leader.sh 2>/dev/null || echo "unknown")"
    summary_append "$RUN_DIR" "$scenario" "PASS" "$dur" "$leader_before" "$leader_after" ""
  else
    end="$(date +%s)"
    dur="$((end-start))"
    leader_after="$(./scripts/find_leader.sh 2>/dev/null || echo "unknown")"
    summary_append "$RUN_DIR" "$scenario" "FAIL" "$dur" "$leader_before" "$leader_after" "see artifacts for status/log placeholders"
    return 1
  fi
}

# Give cluster a moment
sleep 2

# Optional: toxiproxy init/reset if you have it
# bash ./scripts/toxiproxy/setup.sh || true

run_scenario "scenario_partition_2_1" "./scripts/scenario_partition_2_1.sh"
run_scenario "scenario_partition_isolate_leader" "./scripts/scenario_partition_isolate_leader.sh"
run_scenario "scenario_latency_instability" "./scripts/scenario_latency_instability.sh"
run_scenario "scenario_catchup_after_degradation" "./scripts/scenario_catchup_after_degradation.sh"

summary_finalize "$RUN_DIR"

echo "Toxiproxy suite finished."
echo "Summary written to: $RUN_DIR/summary.csv"
echo "Latest summary copied to: artifacts/summary.csv"
