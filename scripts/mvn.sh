#!/usr/bin/env bash
# Backend Maven komutlarını yerel JDK/Maven gerektirmeden Docker içinde çalıştırır.
# Testcontainers'ın test PostgreSQL konteynerini başlatabilmesi için Docker soketi bağlanır.
#   Kullanım:  scripts/mvn.sh test
#              scripts/mvn.sh -DskipTests package
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${MAVEN_REPO_CACHE:-$HOME/.m2}"
mkdir -p "$M2"
DOCKER_GID="$(stat -c %g /var/run/docker.sock)"

exec docker run --rm \
  --user "$(id -u):$(id -g)" --group-add "$DOCKER_GID" \
  --network host \
  -e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2 \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$M2":/tmp/home/.m2 \
  -v "$ROOT":/workspace -w /workspace/backend \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp -Dmaven.repo.local=/tmp/home/.m2/repository "$@"
