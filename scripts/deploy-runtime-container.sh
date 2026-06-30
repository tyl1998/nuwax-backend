#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-nuwax-backend}"
IMAGE_TAG="${IMAGE_TAG:-local}"
CONTAINER_NAME="${CONTAINER_NAME:-nuwax-backend}"
APP_PORT="${APP_PORT:-8080}"
OUTER_PORT="${OUTER_PORT:-6443}"
HOST_APP_PORT="${HOST_APP_PORT:-8080}"
HOST_OUTER_PORT="${HOST_OUTER_PORT:-6443}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-120}"
PULL_IMAGE="${PULL_IMAGE:-false}"
JWT_DIR="${JWT_DIR:-$(pwd)/docker/jwt}"
UPLOAD_DIR="${UPLOAD_DIR:-}"
NETWORK_NAME="${NETWORK_NAME:-}"
NETWORK_ALIAS="${NETWORK_ALIAS:-}"
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"

if [[ "$PULL_IMAGE" == "true" ]]; then
  docker pull "$IMAGE"
fi

mkdir -p "$JWT_DIR"

VOLUME_ARGS=(-v "$JWT_DIR:/app/config/jwt")
if [[ -n "$UPLOAD_DIR" ]]; then
  mkdir -p "$UPLOAD_DIR"
  VOLUME_ARGS+=(-v "$UPLOAD_DIR:/app/upload")
fi

NETWORK_ARGS=()
if [[ -n "$NETWORK_NAME" ]]; then
  NETWORK_ARGS+=(--network "$NETWORK_NAME")
  if [[ -n "$NETWORK_ALIAS" ]]; then
    NETWORK_ARGS+=(--network-alias "$NETWORK_ALIAS")
  fi
fi

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --add-host=host.docker.internal:host-gateway \
  ${NETWORK_ARGS[@]+"${NETWORK_ARGS[@]}"} \
  -p "$HOST_APP_PORT:$APP_PORT" \
  -p "$HOST_OUTER_PORT:$OUTER_PORT" \
  "${VOLUME_ARGS[@]}" \
  -e APP_PROFILE="${APP_PROFILE:-prod}" \
  -e APP_PORT="$APP_PORT" \
  -e MYSQL_HOST="${MYSQL_HOST:-host.docker.internal}" \
  -e MYSQL_PORT="${MYSQL_PORT:-13306}" \
  -e MYSQL_DATABASE="${MYSQL_DATABASE:-agent_platform}" \
  -e MYSQL_USER="${MYSQL_USER:-agent_platform}" \
  -e MYSQL_PASSWORD="${MYSQL_PASSWORD:-admin123}" \
  -e MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}" \
  -e REDIS_HOST="${REDIS_HOST:-host.docker.internal}" \
  -e REDIS_PORT="${REDIS_PORT:-16379}" \
  -e REDIS_PASSWORD="${REDIS_PASSWORD:-123456}" \
  -e REDIS_DB="${REDIS_DB:-0}" \
  -e MILVUS_HOST="${MILVUS_HOST:-host.docker.internal}" \
  -e MILVUS_PORT="${MILVUS_PORT:-19530}" \
  -e MILVUS_URI="${MILVUS_URI:-http://host.docker.internal:19530}" \
  -e MILVUS_USER="${MILVUS_USER:-root}" \
  -e MILVUS_PASSWORD="${MILVUS_PASSWORD:-Milvus}" \
  -e DORIS_HOST="${DORIS_HOST:-${MYSQL_HOST:-host.docker.internal}}" \
  -e DORIS_PORT="${DORIS_PORT:-${MYSQL_PORT:-13306}}" \
  -e DORIS_DB="${DORIS_DB:-agent_custom_table}" \
  -e DORIS_DB_NAME="${DORIS_DB_NAME:-${DORIS_DB:-agent_custom_table}}" \
  -e DORIS_USERNAME="${DORIS_USERNAME:-${MYSQL_USER:-agent_platform}}" \
  -e DORIS_PASSWORD="${DORIS_PASSWORD:-${MYSQL_PASSWORD:-admin123}}" \
  -e ES_URL="${ES_URL:-http://host.docker.internal:9200}" \
  -e ES_USERNAME="${ES_USERNAME:-elastic}" \
  -e ES_PASSWORD="${ES_PASSWORD:-elastic123}" \
  -e LOG_SERVICE_URL="${LOG_SERVICE_URL:-http://host.docker.internal:8097}" \
  -e MCP_PROXY_URL="${MCP_PROXY_URL:-http://host.docker.internal:8020}" \
  -e CODE_EXECUTE_URL="${CODE_EXECUTE_URL:-http://host.docker.internal:8020/api/run_code_with_log}" \
  -e BUILD_SERVER_URL="${BUILD_SERVER_URL:-http://host.docker.internal:60000/api}" \
  -e AI_AGENT_URL="${AI_AGENT_URL:-http://host.docker.internal:8086}" \
  -e DOCKER_PROXY_URL="${DOCKER_PROXY_URL:-http://host.docker.internal:8088}" \
  -e DEV_SERVER_HOST="${DEV_SERVER_HOST:-http://host.docker.internal}" \
  -e PROD_SERVER_HOST="${PROD_SERVER_HOST:-http://host.docker.internal:8099}" \
  -e CORS_ALLOW_ORIGIN="${CORS_ALLOW_ORIGIN:-http://localhost,http://localhost:80,http://localhost:8080}" \
  -e CORS_ALLOW_CREDENTIALS="${CORS_ALLOW_CREDENTIALS:-true}" \
  -e OUTER_PORT="$OUTER_PORT" \
  -e LOG_LEVEL="${LOG_LEVEL:-INFO}" \
  -e WAIT_TIMEOUT_SECONDS="$WAIT_TIMEOUT_SECONDS" \
  "$IMAGE"

HEALTH_URL="${HEALTH_URL:-http://localhost:${HOST_APP_PORT}/ready}"
deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
until curl -fsS "$HEALTH_URL" >/dev/null; do
  if (( SECONDS >= deadline )); then
    docker logs --tail=200 "$CONTAINER_NAME" || true
    printf 'Container %s failed health check: %s\n' "$CONTAINER_NAME" "$HEALTH_URL" >&2
    exit 1
  fi
  sleep 2
done

printf 'Deployed %s as %s\n' "$IMAGE" "$CONTAINER_NAME"
