#!/usr/bin/env bash
set -euo pipefail

SCENARIO="scenario_smoke"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

trap 'collect_artifacts "${SCENARIO}"' EXIT

toxiproxy_setup
toxiproxy_reset

leader="$(wait_for_single_leader 30)"
echo "Leader elected: ${leader}"
