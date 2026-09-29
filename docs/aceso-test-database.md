# Aceso 集成测试数据库

集成测试使用独立、可销毁的 `aceso_test` PostgreSQL，不得连接业务 Compose 中的 `aceso` 数据库。

## 推荐：一条命令

```bash
cd service-vertx-kotlin && ./scripts/aceso-test.sh
```

`scripts/aceso-test.sh` 是当前推荐的唯一测试入口，它自己完成整条生命周期，不需要你 `export` 密码、不需要另开终端看容器、也不需要宿主机装 `psql`：

| 步骤 | 脚本行为 |
|---|---|
| 1. 取密码 | 依次尝试 `$PITCHFORK_DB_PASSWORD` → `$PITCHFORK_TEST_DB_PASSWORD` → `apps/aceso/.env`（`set -a; source` 安全加载）。三者都没有才报错退出，并告诉你该在哪一行填。**密码不会回显到输出或日志。** |
| 2. 找/起测试库 | 先探测 `127.0.0.1:55432`：已可连就直接复用；否则若 `podman`（或 `docker`）可用，用 `apps/aceso/compose.test.yaml` 以 `-d` 启动 `pitchfork-aceso-test-db`，再轮询容器内 `pg_isready` 直到就绪（默认超时 60s，可用 `ACESO_READY_TIMEOUT` 放宽）。两条路都不通就报错，**不静默降级**。 |
| 3. 重置库 | `DROP DATABASE IF EXISTS aceso_test WITH (FORCE); CREATE DATABASE aceso_test;`，通过 `podman exec <容器> psql -U ovaphlow -d postgres ...` 执行（postgres 镜像内自带 `psql`），因此**宿主机没有 `psql` 也能用**。容器/端口都不可用时才回退宿主机 `psql`。 |
| 4. 跑测试 | **先** `:libs:healthcare:test`，**再** `:apps:aceso:test`（覆盖两个模块的全部测试类；这个顺序是硬约束，原因见「为什么先跑 `:libs:healthcare:test`」），带上 `-Dintegration.db.host/port/database/user`。`public.stocks` 预检只是兜底：默认流程刚重置过库时 `public.stocks` 必然不存在，故仅在 `--skip-reset` 且前置 gradle 成功时才跑；前置 gradle 失败时会打印「前置 gradle 未成功（exit N），schema 预检无意义」并跳过。脚本会打印**实际执行的完整 gradle 命令**（密码以 `<hidden>` 代替）便于复制排错。 |
| 5. 收摊 | 默认 `down` 掉**自己启动的**容器，并打印 `podman ps` 证据确认已消失；容器不是它起的就保持原状，绝不停止用户管理的服务。 |

常用参数（完整说明见 `./scripts/aceso-test.sh --help`）：

```bash
./scripts/aceso-test.sh --port 5432      # 用开发库端口所在容器承载 aceso_test（仍只 DROP/CREATE aceso_test）
./scripts/aceso-test.sh --keep           # 保留容器（必须在本轮结束前自己收掉）
./scripts/aceso-test.sh --skip-reset     # 不重置库，直接跑
./scripts/aceso-test.sh --offline        # 给 gradlew 加 --offline
./scripts/aceso-test.sh --container <名> # 指定执行 psql / pg_isready 的容器
```

**第一次运行时看什么判断正常**：应依次看到「密码来源：…（内容不显示）」→「127.0.0.1:55432 已可连/正在启动容器」→「容器已就绪」→「密码校验通过」→「重置完成」→ 两条带 `PITCHFORK_DB_PASSWORD=<hidden>` 的完整 gradle 命令（healthcare 在前、aceso 在后）→「结果汇总」→「收摊证据」。任何一步失败都会打印中文原因和可选出路，而不是裸 exit code。收摊证据只有在 `down` 成功、容器消失、无残留、端口不再监听时才配「收摊成功」字样，否则打印「**收摊未成功**」+ 残留资源 + 人工清理命令（测试通过但收摊失败时 exit 3）。

护栏：脚本只允许操作名为 `aceso_test` 的库（`ACESO_TEST_DB` 改成别的名字会被拒绝并 exit 2），绝不连接 `aceso` 业务库。

## 门控：不给库参数时集成测试会自动 skip

没有 `-Dintegration.db.host` 时，两个模块的集成类由类上的
`@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")` 跳过；即使有人新增子类忘了加注解，基类 `@BeforeAll` 里的 `Assumptions` 兜底也只会 skip 整个类，不会让模块变红。所以下面这条命令在没有任何库参数时是绿的（只有不访问数据库的单元/嵌入式路由测试真正执行）：

```bash
cd service-vertx-kotlin
./gradlew :libs:healthcare:test :apps:aceso:test
```

## 备选：手动启动容器 + 手敲 gradle

只有在需要手工干预（例如排查容器本身、或想自己控制 `/tmp` 配置）时才走这条路。

### 手动启动测试库

```bash
cd service-vertx-kotlin/apps/aceso
export PITCHFORK_TEST_DB_PASSWORD=pitchfork-test-only
podman compose -p pitchfork-aceso-test -f compose.test.yaml up -d
```

测试库容器属于**测试资源**，允许用 `-d` 后台启动（便于同一轮里把测试跑完）；但本轮结束必须 `down`（见下文「收摊」），**不得让它跨会话存活**。想前台观察日志时去掉 `-d`，Ctrl+C 即停。确认端口占用可执行 `podman compose -p pitchfork-aceso-test -f compose.test.yaml ps`。**必须带 `-p pitchfork-aceso-test`**：少了它会退回目录名 `aceso`，与开发库同项目（见下文「项目隔离」）。

### 手动跑测试

测试数据库监听 `127.0.0.1:55432`。容器健康后：

```bash
cd service-vertx-kotlin
export PITCHFORK_DB_PASSWORD=pitchfork-test-only
./gradlew :libs:healthcare:test :apps:aceso:test \
  -Dintegration.db.host=localhost \
  -Dintegration.db.port=55432 \
  -Dintegration.db.database=aceso_test \
  -Dintegration.db.user=ovaphlow \
  --rerun
```

手动跑时也必须保持 **healthcare 先、aceso 后**（原因见「为什么先跑 `:libs:healthcare:test`」）；反了会让 healthcare 的 Flyway validate 报 `Detected applied migration not resolved locally: 600`。

注意：手敲时如果宿主机没有 `psql`，重置 `aceso_test` 这一步要自己用容器内的 psql 完成：

```bash
podman exec pitchfork-aceso-test-db psql -U ovaphlow -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS aceso_test WITH (FORCE);"
podman exec pitchfork-aceso-test-db psql -U ovaphlow -d postgres -v ON_ERROR_STOP=1 \
  -c "CREATE DATABASE aceso_test;"
```

测试会运行 Flyway 迁移并使用固定前缀 fixture。浏览器验收如需直接清理同一数据库，还需把 `PLAYWRIGHT_DB_PORT` 设为 `55432`，并按计划提供用户管理的 Aceso 地址和测试账户。若 Playwright 缓存中没有匹配的浏览器，可将 `PLAYWRIGHT_EXECUTABLE_PATH=/usr/bin/chromium-browser` 指向系统 Chromium；该配置不会启动或接管 Aceso 服务。

## 收摊

默认由 `aceso-test.sh` 自动完成；手动流程请自己执行：

```bash
cd service-vertx-kotlin/apps/aceso
podman compose -p pitchfork-aceso-test -f compose.test.yaml down   # 不要加 -f/--force/remove-orphans
podman ps -a --format '{{.Names}}\t{{.Status}}' | grep -F pitchfork-aceso-test || echo "无遗留容器"
podman pod ps | grep -F pod_pitchfork-aceso-test || echo "无遗留 pod"
podman network ls | grep -F pitchfork-aceso-test || echo "无遗留网络"
```

该 Compose 使用 PostgreSQL `tmpfs`，没有持久化卷；`down` 删除容器后测试数据库内容即被销毁。若启动失败，先检查 `55432` 是否已被占用。

### 生命周期策略（2026-09-29 起，见根 `AGENTS.md`「服务与资源生命周期」）

- **开发服务只由用户启停**：Aceso API `8422`、Aceso UI `4324`、Identity `8420`、Nexus `8421`、开发库 `5432` 等，agent 不得启动、停止、杀进程、重绑端口或另起替代实例。
- agent 允许 build、compile、运行单元/嵌入式路由测试，以及**运行仓库内的测试脚本**（如 `scripts/aceso-test.sh`）。
- **测试数据库与测试进程由 agent 自行管理，但用完必须收干净**：`down` 掉自己启动的容器、终止遗留测试进程、确认没有孤儿容器或进程占用端口，并在交接中给出证据（`podman ps` 与进程列表）。
- 因此：以为「前台跑所以不会遗留」已经不成立；正确做法是**起（可后台）→ 跑完 → 必 `down` → 给证据**，任何情况下都不得把测试容器留在后台跨会话存活。
- `--keep` 只是同一轮内的省时手段，脚本会显式提示容器仍在运行并给出收尾命令；请勿把它当成长期驻留。

## 为什么先跑 `:libs:healthcare:test`

脚本固定先 `:libs:healthcare:test`、后 `:apps:aceso:test`，**这是硬约束**（2026-09-29 在干净库上单写入者实测）：

- 把 `aceso_test` 重置为空库后按「apps 先 → healthcare 后」跑：`:apps:aceso:test` **通过**，因为它的 `testRuntimeClasspath` 是全集（`database` / `common` / `inventories` / `nursing` / `pharmacy` / `healthcare` / `dining`），会把 dining 段迁移 `V600` 铺进库；随后 `:libs:healthcare:test` **挂在**
  `org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation — Detected applied migration not resolved locally: 600`。
  `V600` 是 Aceso dining 段，而 `:libs:healthcare` 的 classpath **不含 dining**，于是 Flyway 校验看到「库里已应用、本地解析不到」→ 直接失败。也就是说 **`:apps:aceso` 的 classpath 是全集（含 dining/pharmacy），先跑它会污染 healthcare 的校验**。
- 反过来（healthcare 先、apps 后）不会出问题：healthcare 只铺它自己那套子集迁移；apps 随后解析的是**超集**，不存在「已应用但解析不到」。
- healthcare 自身的 classpath 经 `healthcare → nursing → inventories`（`nursing` 用 `api` 暴露 `inventories`）已经覆盖创建 `public.materials/lots/stocks` 的 `V200/V201`，所以它先跑能自己把表建起来。
- healthcare 里的 `ElderlyDeathIntegrationTest` / `NursingIncidentHandoverIntegrationTest` 会 `DROP/CREATE aceso_test`；apps 在其后运行，正好由它自己的 `migrate` 重新铺满。
- `public.stocks` 预检**只是兜底，不能替代顺序**：默认流程刚 `DROP/CREATE` 过库，`public.stocks` 必然不存在，所以预检只在「前置 gradle 成功」且使用了 `--skip-reset`（沿用上一轮库内容）时才执行；前置 gradle 失败时脚本会打印「前置 gradle 未成功（exit N），schema 预检无意义」并跳过，**不会**把失败归因成 classpath 问题。

## 项目隔离：为什么测试 compose 必须有独立项目名（2026-09-29 险情）

`apps/aceso/compose.test.yaml`（测试库 `pitchfork-aceso-test-db`，`127.0.0.1:55432`）与 `apps/aceso/compose.yaml`（开发库 `pitchfork-aceso-db`，`0.0.0.0:5432`）**在同一个目录** `apps/aceso`。podman-compose 的项目名解析优先级是：

```
-p/--project-name  >  $COMPOSE_PROJECT_NAME  >  compose 文件顶层 name:  >  文件所在目录名
```

（podman-compose 1.6.0 `PodmanCompose._parse_compose_file`；默认 pod 名是 `pod_<项目名>`，默认网络名是 `<项目名>_default`。）

`compose.test.yaml` 顶层原先**没有** `name:`，于是项目名退回目录名 **`aceso`**，与开发库 `compose.yaml` 的项目**同名** —— 测试 compose 的 `up`/`down` 操作的是同一个 pod（`pod_aceso`）与同一个网络（`aceso_default`）。实测到的收摊报错：

```
Error: not all containers could be removed from pod 16d55bcbe94e...: removing pod containers
Error: error removing container 520c458a07de... as it is running - running or paused containers cannot be removed without force
Error: "aceso_default" has associated containers with it. Use -f to forcibly delete containers and pods
```

其中 `16d55bcbe94e` = `pod_aceso`，正是用户开发库所在的 pod。**当时只是因为开发容器正在运行（不加 `-f` 删不掉）才没有造成损坏**；若开发库当时没在跑，或有人加了 `-f`，开发库容器与网络就会被删掉。同一个根因还有第二个表现：更早用同一命令起的测试容器，会在用户操作 aceso 项目时被一并移除（因为属于同一个项目）。

现在的隔离是**三层**，任何一层被误改都不会退回 `aceso`：

1. `compose.test.yaml` 顶层 `name: pitchfork-aceso-test`；
2. `scripts/aceso-test.sh` 的**每一次** compose 调用都显式带 `-p pitchfork-aceso-test`（唯一的调用形态由 `COMPOSE_CMD` 构造）；
3. 脚本导出 `COMPOSE_PROJECT_NAME=pitchfork-aceso-test`。

此外脚本有 **`down` 硬护栏**：执行任何 `down` 之前逐条断言「argv 带隔离项目名 / 文件顶层 `name:` 等于隔离名 / 目标容器的 compose 项目标签等于隔离名 / compose 解析出的项目名等于隔离名」，任何一项不成立就**拒绝执行 `down`** 并以非 0 退出。脚本**从不使用** `-f`/`--force`，也不使用 `remove-orphans`。

手工操作时也必须带项目名（否则会撞回 `aceso`）：

```bash
cd service-vertx-kotlin/apps/aceso
podman compose -p pitchfork-aceso-test -f compose.test.yaml up -d    # 起
podman compose -p pitchfork-aceso-test -f compose.test.yaml down     # 收（不加 -f）
podman ps -a --format '{{.Names}}\t{{.Status}}' | grep -F pitchfork-aceso-test
podman pod ps | grep -F pod_pitchfork-aceso-test
podman network ls | grep -F pitchfork-aceso-test
```

## 收摊与 gradlew 权限

- **收摊必须真实**：脚本捕获 `down` 的退出码，并且只有在「`down` 成功 + 目标容器已消失 + 隔离项目名下无残留容器/pod/网络 + 测试端口不再监听」四件事同时成立时才打印「收摊成功」。任一条件不成立时打印的是「**收摊未成功**」+ 失败原因 + 残留资源（按隔离项目名过滤的 `podman ps -a` / `podman pod ps` / `podman network ls`）+ 人工清理命令；此时若测试本身通过，脚本以 **exit 3** 结束（避免给出假的 OK）。`--keep` 不会打印任何「已收摊」字样，只提示容器仍在运行与收尾命令。
- **gradlew 可执行位**：`service-vertx-kotlin/gradlew` 在 git index 里曾登记为 `100644`，且本仓库 `core.fileMode=false`，所以主检出里的 `+x` 是手工 `chmod` 的，新克隆/worktree 会得到 `env: ./gradlew: 权限不够`（gradle 根本没跑）。脚本在调用 gradle 前检测 wrapper：不可执行就 `chmod +x` 并打印提示，`chmod` 失败则明确报错（不静默、不掩盖）。仓库侧修复：`git update-index --chmod=+x service-vertx-kotlin/gradlew`。

## 与 `scripts/aceso-integration-tests.sh` 的关系

| | `scripts/aceso-test.sh`（推荐） | `scripts/aceso-integration-tests.sh`（旧，保留兼容） |
|---|---|---|
| 密码 | 自动从环境变量或 `apps/aceso/.env` 读 | 必须手动 `export PITCHFORK_DB_PASSWORD`，否则 exit 2 |
| 端口 | 自动探测 55432 / 自动起容器；可用 `--port` 覆盖 | 固定默认 55432，容器没起就 connection refused |
| 重置库 | 容器内 `psql`（宿主机无需 psql） | 容器优先，回退**宿主机 `psql`**（本机可能没有） |
| 测试范围 | `:libs:healthcare:test` → `:apps:aceso:test` 全部测试类（healthcare 优先，顺序为硬约束） | 只跑 `MedicalOrderIntegrationTest`、`ElderlyDeathIntegrationTest` |
| 收摊 | 自动 `down` 自己起的隔离项目容器 + 真实证据（失败时明确报「收摊未成功」） | 不管理容器生命周期 |

两者不冲突：新的 `aceso-test.sh` 是超集，`aceso-integration-tests.sh` 仅为历史命令留存。新写文档或自动化请只用 `aceso-test.sh`。

## Aceso API 隔离

`compose.test.yaml` 只提供测试 PostgreSQL，不会自动把已有的 Aceso API 从业务库切换到测试库。浏览器验收必须连接一个**由用户管理**、且配置到同一个 `aceso_test` 的 Aceso API；不能直接使用默认配置中的 `5432/aceso` API。

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

在**用户管理的终端**启动 API，让启动时的 Flyway 初始化 `aceso_test`：

```bash
cd service-vertx-kotlin
PITCHFORK_CONFIG=/tmp/aceso-test-config.json \
PITCHFORK_DB_PASSWORD=pitchfork-test-only \
./gradlew :apps:aceso:run
```

API 健康后，再按计划运行 Playwright；测试结束先关闭 API，再执行本页的 Compose `down`。若不想占用 `8422`，API、Aceso UI 的 `PUBLIC_API_URL` 和 Playwright 的 `PLAYWRIGHT_API_BASE_URL` 必须一起改为同一个测试端口。

> 说明：`aceso-test.sh --port 5432` 借用的是**开发库端口所在容器的 PostgreSQL 服务**，但只 `DROP/CREATE aceso_test`，不会读写 `aceso` 业务库；这与上面「不要把 Aceso API 指向 `5432/aceso`」并不矛盾。默认仍建议用 55432 的测试专用容器。
