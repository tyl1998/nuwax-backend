#!/usr/bin/env bash
set -euo pipefail

APP_PROFILE="${APP_PROFILE:-prod}"
SERVER_PORT="${SERVER_PORT:-${APP_PORT:-8080}}"
WAIT_DEPENDENCIES="${WAIT_DEPENDENCIES:-true}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-120}"
DB_HOST="${DB_HOST:-${MYSQL_HOST:-mysql}}"
DB_PORT="${DB_PORT:-${MYSQL_PORT:-3306}}"
DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-agent_platform}}"
DB_USERNAME="${DB_USERNAME:-${MYSQL_USER:-agent_platform}}"
DB_PASSWORD="${DB_PASSWORD:-${MYSQL_PASSWORD:-}}"
REDIS_HOST="${REDIS_HOST:-redis}"
REDIS_PORT="${REDIS_PORT:-6379}"
MILVUS_URI="${MILVUS_URI:-http://${MILVUS_HOST:-milvus}:${MILVUS_PORT:-19530}}"
JAVA_OPTS="${JAVA_OPTS:--server -Xms256m -Xmx2048m -XX:MaxMetaspaceSize=512m -XX:MaxMetaspaceFreeRatio=70 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/java_heap.dump}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

require_env() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    printf 'Missing required env: %s\n' "$name" >&2
    exit 1
  fi
}

manage_jwt_secret() {
  local secret_file="/app/config/jwt/jwt_secret_key.txt"
  if [[ -n "${JWT_SECRET_KEY:-}" ]]; then
    export JWT_SECRET_KEY
    return
  fi
  mkdir -p "$(dirname "$secret_file")"
  if [[ ! -f "$secret_file" ]]; then
    JWT_SECRET_KEY="$(cat /proc/sys/kernel/random/uuid | tr -d '-')$(cat /proc/sys/kernel/random/uuid | tr -d '-')"
    printf '%s' "$JWT_SECRET_KEY" > "$secret_file"
  else
    JWT_SECRET_KEY="$(cat "$secret_file")"
  fi
  export JWT_SECRET_KEY
}

require_env DB_HOST
require_env DB_NAME
require_env DB_USERNAME
require_env DB_PASSWORD
require_env REDIS_HOST
require_env MILVUS_URI
manage_jwt_secret
require_env JWT_SECRET_KEY

if [[ "$DB_PASSWORD" == "your_mysql_password" ]]; then
  printf 'DB_PASSWORD must not use placeholder value\n' >&2
  exit 1
fi

if [[ ${#JWT_SECRET_KEY} -lt 32 || "$JWT_SECRET_KEY" == "your_jwt_secret_key_minimum_32_characters" ]]; then
  printf 'JWT_SECRET_KEY must be at least 32 characters and not a placeholder\n' >&2
  exit 1
fi

parse_uri_host() {
  local uri="$1"
  uri="${uri#*://}"
  uri="${uri%%/*}"
  uri="${uri%%:*}"
  printf '%s' "$uri"
}

parse_uri_port() {
  local uri="$1"
  local fallback="$2"
  uri="${uri#*://}"
  uri="${uri%%/*}"
  if [[ "$uri" == *:* ]]; then
    printf '%s' "${uri##*:}"
  else
    printf '%s' "$fallback"
  fi
}

wait_for_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  printf 'Waiting for %s at %s:%s\n' "$name" "$host" "$port"
  until nc -z "$host" "$port"; do
    if (( SECONDS >= deadline )); then
      printf 'Timed out waiting for %s at %s:%s\n' "$name" "$host" "$port" >&2
      exit 1
    fi
    sleep 2
  done
  printf '%s is ready\n' "$name"
}

if [[ "$WAIT_DEPENDENCIES" == "true" ]]; then
  MILVUS_HOST="$(parse_uri_host "$MILVUS_URI")"
  MILVUS_PORT="$(parse_uri_port "$MILVUS_URI" "19530")"
  wait_for_tcp mysql "$DB_HOST" "$DB_PORT"
  wait_for_tcp redis "$REDIS_HOST" "$REDIS_PORT"
  wait_for_tcp milvus "$MILVUS_HOST" "$MILVUS_PORT"
fi

mkdir -p /app/logs /app/upload /app/config

echo "Starting application on port ${SERVER_PORT} with profile ${APP_PROFILE}"
exec java $JAVA_OPTS \
  -Dfile.encoding=UTF-8 \
  -Dspring.config.location=classpath:/,file:/app/config/ \
  -Dspring.config.name=application,application-external \
  -Dspring.profiles.active="$APP_PROFILE" \
  -Dserver.port="$SERVER_PORT" \
  -Dlogging.file.path=/app/logs \
  -Dlogging.level.root="$LOG_LEVEL" \
  -Dlog.level="$LOG_LEVEL" \
  -jar /app/app.jar
