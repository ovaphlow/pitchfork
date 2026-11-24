# Aceso 集成测试数据库

集成测试**不再要求专用测试库、不再 DROP/CREATE 数据库、不再硬编码库名**。
目标库由 `-Dintegration.db.database` 显式给出，测试只增量迁移 + 前缀隔离，不管理库的生命周期。

## 一条命令

```bash
cd service-vertx-kotlin && ./scripts/aceso-test.sh
```

`scripts/aceso-test.sh` 是唯一入口（旧的 `aceso-integration-tests.sh` 已于 2026-11-21 删除，
它依赖「每次重置库」的模型）。脚本行为：

| 步骤 | 行为 |
|---|---|
| 1. 取密码 | 依次尝试 `$PITCHFORK_DB_PASSWORD` → `$PITCHFORK_TEST_DB_PASSWORD` → `apps/aceso/.env`。三者都没有才报错退出。**密码不会回显** |
| 2. 找/起实例 | 先探测 `127.0.0.1:55432`：已可连就复用；否则用 `apps/aceso/compose.test.yaml` 启动一次性容器 `pitchfork-aceso-test-db`（tmpfs，`down` 即消失）并等 `pg_isready`。两条路都不通就报错，**不静默降级** |
| 3. 解析库名 | 未给 `--database` 时：一次性容器用它自带的 `aceso_test`；指向既有实例（`--port 5432` / `--container`）则**直接报错**，脚本不猜你的库 |
| 4. 建库（仅在不存在时） | 查 `pg_database`，不存在才 `CREATE DATABASE`；**绝不 `DROP DATABASE`**，已有内容一律保留 |
| 5. 跑测试 | **一次** gradle 调用：`:libs:healthcare:test :apps:aceso:test`，带 `-Dintegration.db.*`；打印实际命令（密码替换为 `<hidden>`） |
| 6. 收摊 | 默认 `down` 掉**自己启动的**容器，并打印 `podman ps` 证据；不是它起的不动 |

常用参数：

```bash
./scripts/aceso-test.sh                                   # 一次性容器 + 自带库 aceso_test
./scripts/aceso-test.sh --port 5432 --database aceso --no-migrate   # 对已有实例跑，且不推进迁移
./scripts/aceso-test.sh --database mydb                   # 指定库名（脚本不存在时才建）
./scripts/aceso-test.sh --keep                            # 保留容器（必须本轮自己收掉）
./scripts/aceso-test.sh --offline                         # gradlew 加 --offline
```

## 手动跑

```bash
cd service-vertx-kotlin
export PITCHFORK_DB_PASSWORD=<密码>
./gradlew :libs:healthcare:test :apps:aceso:test --rerun \
  -Dintegration.db.host=localhost \
  -Dintegration.db.port=5432 \
  -Dintegration.db.database=aceso \
  -Dintegration.db.migrate=false
```

参数含义：

| 系统属性 | 缺省 | 含义 |
|---|---|---|
| `-Dintegration.db.host` | 无 | 缺则**所有集成类 skip** |
| `-Dintegration.db.database` | 无 | 缺则**所有集成类 skip**（不硬编码、不猜） |
| `-Dintegration.db.port` | `5432` | 端口 |
| `-Dintegration.db.user` | `ovaphlow` | 用户 |
| `-Dintegration.db.migrate` | `true` | 是否执行 Flyway；`false` = 只复用既有 schema |
| `PITCHFORK_DB_PASSWORD` | 无 | 只从环境读取，缺则 skip |

以上参数统一由 `libs/database` 的测试 fixture `IntegrationDb`
（`com.ovaphlow.crate.database.testing.IntegrationDb`）解析，测试类不再各自读属性、
不再各自持有 `aceso_test` 常量。

## 隔离是靠什么保证的（不再靠「一次性库」）

| 层 | 机制 |
|---|---|
| 数据 | 每个测试类用固定 fixture 前缀（如 `qa054f3-`、`mo-`、`ed-`），`@BeforeEach`/`@AfterEach` 按前缀清理，`assertNoResidual()` 断言残差为 0 |
| 断言 | 一律按 fixture id / 前缀收敛，不依赖「库是空的」 |
| 全局性用例 | 极少数天然全库的用例（如 `closeExpiredOrders` 的全量收束）自带 `Assumptions.assumeTrue(foreignCandidates == 0)`：在非空库上**诚实 skip**，不改别人的数据 |
| 门控 | `integration.db.host` + `integration.db.database` 两条 `@EnabledIfSystemProperty`：没参数就整类 skip，不会误连 |

这套机制的原意就是「可以跑在任何已迁移的库上」。历史上要求专用库，是因为测试自己
`DROP DATABASE` 重建基线 —— 那是**自找的约束**，不是数据隔离的必要条件。

## Flyway：为什么不再有模块顺序冲突

`DatabaseConfig.migrate()` 的现行配置：

```kotlin
.baselineOnMigrate(true)
.ignoreMigrationPatterns("*:missing", "*:ignored", "*:future")
.outOfOrder(true)   // 各模块号段（V2xx/V3xx/V4xx/V5xx/V6xx）共享一个 schema history
```

因此：

- `:libs:healthcare` 的 classpath 只带自己那套子集迁移，`db/migration` 里看不到 dining 的
  `V600` —— `V600` 高于该模块本地最高版本 `V540`，已应用但本地解析不到属于 `*:future`，被忽略；
- 已应用迁移低于本地最高版本但本地缺失时属于 `*:missing`，同样被忽略；
- `:apps:aceso` 的 classpath 是全集，随后要应用的低版本号迁移由 `outOfOrder` 放行；
- 于是**「先 healthcare 后 aceso」不再是硬约束**，脚本改为一次 gradle 调用。

> 早期文档记录的 `Detected applied migration not resolved locally: 600` 是
> `outOfOrder` + `*:missing` 之前的现象，现已不适用。

仍会真正失败的两种情况（不要触发）：修改已应用迁移的 checksum；库中存在比本地更新的版本
（`*:future`，当前 ignore 列表未包含，属有意保留的报错）。

## 指向既有库时要注意的唯一风险

`migrate` 默认开启：如果目标库有**未应用**的迁移（例如别人刚加了 `V541`），跑一次测试就会
把它推到那个库 —— 对开发库（`aceso`）而言这是不可逆的 schema 变化。

所以对既有库请加 `--no-migrate`（脚本）或 `-Dintegration.db.migrate=false`（手动）：

```bash
./scripts/aceso-test.sh --port 5432 --database aceso --no-migrate
```

这样测试只读 schema、只写自己前缀的 fixture。

## 生命周期策略（2026-09-29 起，见根 `AGENTS.md`「服务与资源生命周期」）

- **开发服务只由用户启停**：Aceso API `8422`、Aceso UI `4324`、Identity `8420`、开发库 `5432` 等，
  agent 不得启动、停止、杀进程、重绑端口或另起替代实例。
- agent 允许 build、compile、运行单元/嵌入式路由测试，以及**运行仓库内的测试脚本**。
- **测试资源由 agent 自行管理，但用完必须收干净**：`down` 掉自己启动的容器、终止遗留测试进程、
  确认没有孤儿容器或进程占用端口，并在交接中给出证据（`podman ps` 与进程列表）。
- `--keep` 只是同一轮内的省时手段，不得让容器跨会话存活。

## 项目隔离：为什么测试 compose 必须有独立项目名（2026-09-29 险情）

`apps/aceso/compose.test.yaml`（`pitchfork-aceso-test-db`，`127.0.0.1:55432`）与
`apps/aceso/compose.yaml`（开发库 `pitchfork-aceso-db`，`0.0.0.0:5432`）**在同一个目录**
`apps/aceso`。podman-compose 的项目名解析优先级是：

```
-p/--project-name  >  $COMPOSE_PROJECT_NAME  >  compose 文件顶层 name:  >  文件所在目录名
```

`compose.test.yaml` 原先**没有** `name:`，项目名退回目录名 **`aceso`**，与开发库同名 ——
测试 compose 的 `down` 操作的是同一个 pod（`pod_aceso`）与同一个网络（`aceso_default`）。
当时只因为开发容器正在运行（不加 `-f` 删不掉）才没有造成损坏。

现在的隔离是三层，任何一层被误改都不会退回 `aceso`：

1. `compose.test.yaml` 顶层 `name: pitchfork-aceso-test`；
2. `scripts/aceso-test.sh` 的**每一次** compose 调用都显式带 `-p pitchfork-aceso-test`；
3. 脚本导出 `COMPOSE_PROJECT_NAME=pitchfork-aceso-test`。

外加 `down` 硬护栏：执行任何 `down` 前逐条断言项目名，任何一项不成立就拒绝执行并非 0 退出；
脚本**从不使用** `-f`/`--force`，也不使用 `remove-orphans`。
