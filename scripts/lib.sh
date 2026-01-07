#!/usr/bin/env bash
set -euo pipefail

# --- Base URLs for nodes (works on host and inside docker tester) ---
HTTP_NODE1="${HTTP_NODE1:-http://localhost:8081}"
HTTP_NODE2="${HTTP_NODE2:-http://localhost:8082}"
HTTP_NODE3="${HTTP_NODE3:-http://localhost:8083}"

node_http_base() {
  local node="$1"
  case "$node" in
    1) echo "$HTTP_NODE1" ;;
    2) echo "$HTTP_NODE2" ;;
    3) echo "$HTTP_NODE3" ;;
    *) echo "unknown-node" ;;
  esac
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${COMPOSE:-docker compose}"

# Toxiproxy API host (no scheme). Works on host and inside docker tester.
# - On host: localhost:8474
# - In tester: toxiproxy:8474
if [[ -n "${TOXIPROXY_URL:-}" ]]; then
  # allow TOXIPROXY_URL like http://toxiproxy:8474
  TOXIPROXY_HOST="$(echo "$TOXIPROXY_URL" | sed -E 's#^https?://##')"
else
  if [[ -n "${IN_DOCKER:-}" ]]; then
    TOXIPROXY_HOST="${TOXIPROXY_HOST:-toxiproxy:8474}"
  else
    TOXIPROXY_HOST="${TOXIPROXY_HOST:-localhost:8474}"
  fi
fi


NODES=(1 2 3)
declare -A NODE_HTTP=(
  ["1"]="localhost:8081"
  ["2"]="localhost:8082"
  ["3"]="localhost:8083"
)
declare -A RAFT_PORTS=(
  ["1"]="5001"
  ["2"]="5002"
  ["3"]="5003"
)
declare -A PROXY_PORTS=(
  ["1-2"]="10012"
  ["1-3"]="10013"
  ["2-1"]="10021"
  ["2-3"]="10023"
  ["3-1"]="10031"
  ["3-2"]="10032"
)

json_get() {
  local key="$1"
  python - "$key" <<'PY'
import json,sys
key=sys.argv[1]
data=json.load(sys.stdin)
val=data.get(key,"")
if isinstance(val, (list, dict)):
  print(json.dumps(val))
else:
  print(val)
PY
}

curl_status() {
  local node="$1"
  local base
  base="$(node_http_base "$node")"
  curl -fsS "${base}/status"
}

find_leader() {
  local leader=""
  for node in "${NODES[@]}"; do
    local status
    if ! status="$(curl_status "$node")"; then
      continue
    fi
    local role
    role="$(printf '%s' "$status" | json_get "role")"
    if [[ "$role" == "LEADER" ]]; then
      if [[ -n "$leader" && "$leader" != "$node" ]]; then
        echo "multiple leaders detected: $leader and $node" >&2
        return 1
      fi
      leader="$node"
    fi
  done
  [[ -n "$leader" ]] && echo "$leader"
}

wait_for_single_leader() {
  local timeout="${1:-30}"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    local leader
    leader="$(find_leader || true)"
    if [[ -n "$leader" ]]; then
      echo "$leader"
      return 0
    fi
    sleep 1
  done
  echo "no leader elected within ${timeout}s" >&2
  return 1
}

node_url() {
  local node_id="$1"
  node_http_base "$node_id"
}


kv_request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local content_type="${4:-text/plain}"

  local target
  target="$(find_leader || echo "1")"

  for attempt in 1 2; do
    local url
    url="$(node_url "$target")${path}"
    local tmp
    tmp="$(mktemp)"
    local code
    if [[ -n "$body" ]]; then
      code="$(curl -sS -L -o "$tmp" -w "%{http_code}" -X "$method" \
        -H "Content-Type: ${content_type}" \
        --data-binary "$body" \
        "$url")"
    else
      code="$(curl -sS -L -o "$tmp" -w "%{http_code}" -X "$method" "$url")"
    fi
    KV_HTTP_STATUS="$code"

    if [[ "$code" == "409" || "$code" == "503" ]]; then
      local leader
      leader="$(python - "$tmp" <<'PY'
import json,sys
try:
  data=json.load(open(sys.argv[1]))
  print(data.get("leader",""))
except Exception:
  print("")
PY
)"
      if [[ -n "$leader" && "$leader" != "$target" ]]; then
        target="$leader"
        rm -f "$tmp"
        continue
      fi
    fi

    cat "$tmp"
    rm -f "$tmp"
    return 0
  done

  return 1
}

kv_put() {
  local key="$1"
  local value="$2"
  kv_request "PUT" "/kv/${key}" "$value" "text/plain"
}

kv_get() {
  local key="$1"
  kv_request "GET" "/kv/${key}"
}

kv_del() {
  local key="$1"
  kv_request "DELETE" "/kv/${key}"
}

kv_cas() {
  local key="$1"
  local expected="$2"
  local value="$3"
  local payload
  payload="$(printf '{"key":"%s","expected":"%s","value":"%s"}' "$key" "$expected" "$value")"
  kv_request "POST" "/kv/cas" "$payload" "application/json"
}

assert_equal() {
  local expected="$1"
  local actual="$2"
  local message="${3:-}"
  if [[ "$expected" != "$actual" ]]; then
    echo "assert_equal failed: expected='$expected' actual='$actual' $message" >&2
    return 1
  fi
}

assert_true() {
  local condition="$1"
  local message="${2:-}"
  if ! eval "$condition"; then
    echo "assert_true failed: $condition $message" >&2
    return 1
  fi
}

collect_artifacts() {
  local scenario="$1"

  # If run directory is provided (from run_all.sh), store under it.
  # Otherwise keep legacy behavior: artifacts/<ts>/<scenario>
  local base_dir
  if [[ -n "${ARTIFACTS_RUN_DIR:-}" ]]; then
    base_dir="${ARTIFACTS_RUN_DIR}"
  else
    local ts
    ts="$(date +%Y%m%d_%H%M%S)"
    base_dir="artifacts/${ts}"
  fi

  local dir="${base_dir}/${scenario}"
  mkdir -p "$dir"

  for node in "${NODES[@]}"; do
    curl_status "$node" > "${dir}/status-node${node}.json" || true
  done
  if command -v docker >/dev/null 2>&1; then
    ${COMPOSE} logs --no-color > "${dir}/docker-compose.log" || true
  else
    echo "docker logs not available in this environment" > "${dir}/docker-compose.log"
  fi

}

proxy_name() {
  local src="$1"
  local dst="$2"
  echo "proxy-${src}-${dst}"
}

toxiproxy_api() {
  curl -sS "http://${TOXIPROXY_HOST}$1"
}

toxiproxy_setup() {
  for key in "${!PROXY_PORTS[@]}"; do
    local src="${key%-*}"
    local dst="${key#*-}"
    local port="${PROXY_PORTS[$key]}"
    local name
    name="$(proxy_name "$src" "$dst")"
    curl -sS -X DELETE "http://${TOXIPROXY_HOST}/proxies/${name}" >/dev/null 2>&1 || true
    curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"${name}\",\"listen\":\"0.0.0.0:${port}\",\"upstream\":\"node${dst}:${RAFT_PORTS[$dst]}\"}" >/dev/null
  done
}

toxiproxy_reset() {
  for key in "${!PROXY_PORTS[@]}"; do
    local src="${key%-*}"
    local dst="${key#*-}"
    local name
    name="$(proxy_name "$src" "$dst")"
    curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/reset" >/dev/null || true
  done
}

toxiproxy_add_latency() {
  local src="$1"
  local dst="$2"
  local latency="${3:-200}"
  local jitter="${4:-50}"
  local name
  name="$(proxy_name "$src" "$dst")"
  curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":${latency},\"jitter\":${jitter}}}" >/dev/null
}

toxiproxy_add_loss() {
  local src="$1"
  local dst="$2"
  local name
  name="$(proxy_name "$src" "$dst")"
  curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"loss\",\"type\":\"limit_data\",\"stream\":\"downstream\",\"attributes\":{\"bytes\":1}}" >/dev/null
}

toxiproxy_add_bandwidth() {
  local src="$1"
  local dst="$2"
  local rate_kbps="${3:-50}"
  local name
  name="$(proxy_name "$src" "$dst")"
  curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"bandwidth\",\"type\":\"bandwidth\",\"stream\":\"downstream\",\"attributes\":{\"rate\":${rate_kbps}}}" >/dev/null
}

toxiproxy_partition() {
  local src="$1"
  local dst="$2"
  local name
  name="$(proxy_name "$src" "$dst")"
  curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"partition\",\"type\":\"timeout\",\"stream\":\"downstream\",\"attributes\":{\"timeout\":0}}" >/dev/null
  curl -sS -X POST "http://${TOXIPROXY_HOST}/proxies/${name}/toxics" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"partition-up\",\"type\":\"timeout\",\"stream\":\"upstream\",\"attributes\":{\"timeout\":0}}" >/dev/null
}

toxiproxy_unpartition() {
  local src="$1"
  local dst="$2"
  local name
  name="$(proxy_name "$src" "$dst")"
  curl -sS -X DELETE "http://${TOXIPROXY_HOST}/proxies/${name}/toxics/partition" >/dev/null || true
  curl -sS -X DELETE "http://${TOXIPROXY_HOST}/proxies/${name}/toxics/partition-up" >/dev/null || true
}
