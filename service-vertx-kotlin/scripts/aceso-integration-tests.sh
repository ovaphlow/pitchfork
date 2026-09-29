#!/usr/bin/env bash
# Aceso 008 医嘱/去世 PostgreSQL 集成测试单命令入口。
#
# 目的：为获授权的 aceso_test 维护可重复的“重置 → 迁移 → 目标集成测试”生命周期，
# 避免把会 DROP/CREATE aceso_test 的 Nursing 统计测试与 Healthcare 目标类混在同一次
# Gradle 调用中（历史问题：Nursing 测试重置到 V405 并遗留 healthcare.patients，
# 导致后续 V500 建表冲突）。
#
# 安全性：
#   - 只允许操作名为 aceso_test 的数据库；绝不连接 aceso 业务库/共享开发库/生产库。
#   - 密码只从 $PITCHFORK_DB_PASSWORD 或 $PITCHFORK_TEST_DB_PASSWORD 注入。
#   - 默认通过测试专用容器执行 DROP/CREATE；容器不可用时回退到本地 psql，
#     且调用方仍需保证目标确实是授权、可销毁的 aceso_test。
#
# 用法：
#   PITCHFORK_DB_PASSWORD=... ./scripts/aceso-integration-tests.sh
#   PITCHFORK_DB_PASSWORD=... PITCHFORK_TEST_DB_PORT=55432 ./scripts/aceso-integration-tests.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$ROOT/service-vertx-kotlin"

TEST_DB="${ACESO_TEST_DB:-aceso_test}"
TEST_HOST="${PITCHFORK_TEST_DB_HOST:-127.0.0.1}"
TEST_PORT="${PITCHFORK_TEST_DB_PORT:-55432}"
TEST_USER="${PITCHFORK_TEST_DB_USER:-ovaphlow}"
TEST_PASSWORD="${PITCHFORK_DB_PASSWORD:-${PITCHFORK_TEST_DB_PASSWORD:-}}"
ADMIN_CONTAINER="${PITCHFORK_TEST_DB_CONTAINER:-pitchfork-aceso-test-db}"
SKIP_RESET="${ACESO_SKIP_DB_RESET:-0}"

if [[ "$TEST_DB" != "aceso_test" ]]; then
  echo "Refusing to run: TEST_DB must be aceso_test, got '$TEST_DB'" >&2
  exit 2
fi

if [[ -z "$TEST_PASSWORD" ]]; then
  echo "Missing password: set PITCHFORK_DB_PASSWORD (or PITCHFORK_TEST_DB_PASSWORD)" >&2
  exit 2
fi

reset_db() {
  if [[ "$SKIP_RESET" == "1" ]]; then
    echo "SKIP_RESET=1; leaving existing $TEST_DB as-is"
    return
  fi

  echo "Resetting isolated test database $TEST_DB on $TEST_HOST:$TEST_PORT ..."
  if command -v podman >/dev/null 2>&1 && podman container exists "$ADMIN_CONTAINER" >/dev/null 2>&1; then
    podman exec "$ADMIN_CONTAINER" psql -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE);"
    podman exec "$ADMIN_CONTAINER" psql -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "CREATE DATABASE $TEST_DB;"
  elif command -v docker >/dev/null 2>&1 && docker inspect "$ADMIN_CONTAINER" >/dev/null 2>&1; then
    docker exec "$ADMIN_CONTAINER" psql -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE);"
    docker exec "$ADMIN_CONTAINER" psql -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "CREATE DATABASE $TEST_DB;"
  else
    PGPASSWORD="$TEST_PASSWORD" psql -h "$TEST_HOST" -p "$TEST_PORT" -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE);"
    PGPASSWORD="$TEST_PASSWORD" psql -h "$TEST_HOST" -p "$TEST_PORT" -U "$TEST_USER" -d postgres \
      -v ON_ERROR_STOP=1 -c "CREATE DATABASE $TEST_DB;"
  fi
}

reset_db

echo "Running Healthcare 008 target integration tests..."
cd "$BACKEND_DIR"
PITCHFORK_DB_PASSWORD="$TEST_PASSWORD" ./gradlew :libs:healthcare:test \
  --tests "*MedicalOrderIntegrationTest*" \
  --tests "*ElderlyDeathIntegrationTest*" \
  --rerun-tasks \
  -Dintegration.db.host="$TEST_HOST" \
  -Dintegration.db.port="$TEST_PORT" \
  -Dintegration.db.database="$TEST_DB" \
  -Dintegration.db.user="$TEST_USER" \
  --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
