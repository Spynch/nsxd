#!/usr/bin/env bash
set -euo pipefail

# Convenience script for "make status"
# Uses the same host ports as in docker-compose:
# node1 -> 8081, node2 -> 8082, node3 -> 8083

curl -fsS http://localhost:8081/status | sed 's/^/[node1] /' || echo "[node1] (no response)"
curl -fsS http://localhost:8082/status | sed 's/^/[node2] /' || echo "[node2] (no response)"
curl -fsS http://localhost:8083/status | sed 's/^/[node3] /' || echo "[node3] (no response)"
