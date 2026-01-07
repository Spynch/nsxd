#!/usr/bin/env bash
set -euo pipefail

# Summary CSV helpers
#
# summary_init "<run_dir>"
# summary_append "<run_dir>" "<scenario>" "<result>" "<duration_sec>" "<leader_before>" "<leader_after>" "<notes>"
# summary_finalize "<run_dir>"

summary_init() {
  local run_dir="$1"
  mkdir -p "$run_dir"
  local csv="$run_dir/summary.csv"
  echo "timestamp,scenario,result,duration_sec,leader_before,leader_after,notes" > "$csv"
}

summary_append() {
  local run_dir="$1"
  local scenario="$2"
  local result="$3"
  local duration="$4"
  local leader_before="$5"
  local leader_after="$6"
  local notes="$7"

  # Escape quotes for CSV
  notes="${notes//\"/\"\"}"

  local ts
  ts="$(date -Iseconds)"

  echo "\"$ts\",\"$scenario\",\"$result\",\"$duration\",\"$leader_before\",\"$leader_after\",\"$notes\"" >> "$run_dir/summary.csv"
}

summary_finalize() {
  local run_dir="$1"
  mkdir -p artifacts
  cp -f "$run_dir/summary.csv" "artifacts/summary.csv"
}
