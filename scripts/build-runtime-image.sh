#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-nuwax-backend}"
IMAGE_TAG="${IMAGE_TAG:-local}"
MAVEN_PROFILE="${MAVEN_PROFILE:-prod}"
SKIP_TESTS="${SKIP_TESTS:-true}"
SKIP_MAVEN_BUILD="${SKIP_MAVEN_BUILD:-false}"
PUSH_IMAGE="${PUSH_IMAGE:-false}"
PLATFORM="${PLATFORM:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BOOTSTRAP_MODULE="app-platform-bootstrap/app-platform-web-bootstrap"
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"

cd "$PROJECT_ROOT"

if [[ "$SKIP_MAVEN_BUILD" != "true" ]]; then
  MVN_ARGS=(-pl "$BOOTSTRAP_MODULE" -am package -P"$MAVEN_PROFILE")
  if [[ "$SKIP_TESTS" == "true" ]]; then
    MVN_ARGS+=(-DskipTests)
  fi
  mvn "${MVN_ARGS[@]}"
fi

if ! ls "$BOOTSTRAP_MODULE"/target/app-platform-web-bootstrap-*.jar >/dev/null 2>&1; then
  printf 'Runtime jar not found under %s/target\n' "$BOOTSTRAP_MODULE" >&2
  exit 1
fi

DOCKER_BUILD_ARGS=(-f Dockerfile.runtime -t "$IMAGE")
if [[ -n "$PLATFORM" ]]; then
  DOCKER_BUILD_ARGS+=(--platform "$PLATFORM")
fi
DOCKER_BUILD_ARGS+=(.)

docker build "${DOCKER_BUILD_ARGS[@]}"

if [[ "$PUSH_IMAGE" == "true" ]]; then
  docker push "$IMAGE"
fi

printf 'Built image: %s\n' "$IMAGE"
