#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

toxiproxy_setup
toxiproxy_reset
echo "Toxiproxy proxies created and reset."
