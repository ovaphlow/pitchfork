# Aceso 集成测试数据库

集成测试使用独立、可销毁的 `aceso_test` PostgreSQL，不得连接业务 Compose 中的 `aceso` 数据库。

## 启动

```bash
cd service-vertx-kotlin/apps/aceso
export PITCHFORK_TEST_DB_PASSWORD=pitchfork-test-only
podman compose -f compose.test.yaml up -d
podman compose -f compose.test.yaml ps
```

测试数据库监听 `127.0.0.1:55432`。容器健康后，测试命令使用映射端口：

```bash
cd service-vertx-kotlin
export PITCHFORK_DB_PASSWORD=pitchfork-test-only
./gradlew :libs:nursing:test :libs:healthcare:test \
  -Dintegration.db.host=localhost \
  -Dintegration.db.port=55432 \
  -Dintegration.db.database=aceso_test \
  -Dintegration.db.user=ovaphlow \
  --tests "*ElderlyCare*" \
  --rerun-tasks
```

测试会运行 Flyway 迁移并使用固定前缀 fixture。浏览器验收如需直接清理同一数据库，还需把 `PLAYWRIGHT_DB_PORT` 设为 `55432`，并按计划提供用户管理的 Aceso 地址和测试账户。若 Playwright 缓存中没有匹配的浏览器，可将 `PLAYWRIGHT_EXECUTABLE_PATH=/usr/bin/chromium-browser` 指向系统 Chromium；该配置不会启动或接管 Aceso 服务。

## Aceso API 隔离

`compose.test.yaml` 只提供测试 PostgreSQL，不会自动把已有的 Aceso API 从业务库切换到测试库。浏览器验收必须连接一个由用户管理、且配置到同一个 `aceso_test` 的 Aceso API；不能直接使用默认配置中的 `5432/aceso` API。

停止用户管理的旧 Aceso API 后，可临时创建 `/tmp/aceso-test-config.json`：

```json
{
  "database": {
    "host": "127.0.0.1",
    "port": 55432,
    "database": "aceso_test",
    "user": "ovaphlow",
    "pool-size": 10
  },
  "server": {
    "port": 8422,
    "cors-origins": ["http://127.0.0.1:4324"]
  },
  "nexus": { "base-url": "http://127.0.0.1:8421" },
  "identity": { "base-url": "http://127.0.0.1:8420" },
  "console-level": "INFO"
}
```

在用户管理的终端启动 API，让启动时的 Flyway 初始化 `aceso_test`：

```bash
cd service-vertx-kotlin
PITCHFORK_CONFIG=/tmp/aceso-test-config.json \
PITCHFORK_DB_PASSWORD=pitchfork-test-only \
./gradlew :apps:aceso:run
```

API 健康后，再按计划运行 Playwright；测试结束先关闭 API，再执行本页的 Compose `down`。若不想占用 `8422`，API、Aceso UI 的 `PUBLIC_API_URL` 和 Playwright 的 `PLAYWRIGHT_API_BASE_URL` 必须一起改为同一个测试端口。

## 关闭

```bash
cd service-vertx-kotlin/apps/aceso
podman compose -f compose.test.yaml down
```

该 Compose 使用 PostgreSQL `tmpfs`，没有持久化卷；`down` 删除容器后测试数据库内容即被销毁。若启动失败，先检查 `55432` 是否已被占用；不要改用业务端口 `5432`。
