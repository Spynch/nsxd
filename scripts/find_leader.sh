#!/usr/bin/env bash
set -euo pipefail
source "./scripts/lib.sh"

for n in "${NODES[@]}"; do
  role="$(curl_status "$n" | jq -r .role 2>/dev/null || echo "")"
  if [[ "$role" == "LEADER" ]]; then
    echo "$n"
    exit 0
  fi
done

echo "unknown"
exit 1
