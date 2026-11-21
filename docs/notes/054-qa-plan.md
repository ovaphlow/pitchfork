# 054 儿保模式角色 3–8 · 测试入口核查与验收计划（草案）

> ⚠️ **已被取代（2026-11-21）**：本文件是 2026-10-06 的 054（儿保）QA 计划历史存档，
> 其中**所有可执行命令与入口约定均已失效**，请勿照抄：
> - 儿保（052/053/054）实现已随 056 全量下线，相关测试类与接口不复存在；
> - 测试入口不再「自重置 `aceso_test`」——脚本与测试**都不 DROP/CREATE 数据库**，
>   库名由 `-Dintegration.db.database` 显式给出（无默认值），`--skip-reset` 已删除，
>   新增 `--database` / `--no-migrate`；「healthcare 先、aceso 后」的顺序硬约束已取消。
> - 现行口径唯一真源：[`../aceso-test-database.md`](../aceso-test-database.md)。

| 项 | 值 |
|---|---|
| 共享任务 | `task-4`（测试/集成验收角色 `qa`） |
| 阶段 | 第一阶段：只读核查 + 验收计划草案 |
| 范围 | 测试基础设施摸底；**不启动** podman / 数据库 / 开发服务；**不运行** gradle 与 pnpm（构建车道归开发） |
| 产出 | 本文件（唯一写入范围） |
| 日期 | 2026-10-06 |
| 依赖 | `docs/plans/054.*`（尚未发布，pm/architect 冻结后本计划第 5 节须按契约回填） |

> 本文件所有结论来自逐行阅读仓库脚本、AGENTS.md、build 配置与测试源码；所有命令均标注「已实测 / 未实测（本阶段只读）」。凡「未实测」命令请在获授权后按第 7 节的授权清单执行。

---

## 0. 结论摘要（TL;DR）

1. **测试入口可自起自收**：`service-vertx-kotlin/scripts/aceso-test.sh` 是唯一推荐真库入口，自取密码 → 自起 `pitchfork-aceso-test-db`（55432）→ 自重置 `aceso_test` → 跑 healthcare 再跑 aceso → 退出时 `down` 自己起的容器并打印收摊证据。测试通过但收摊失败时以 **exit 3** 结束，不打印假的 OK。
2. **Flyway 不由脚本执行**：脚本只 `DROP/CREATE aceso_test`；迁移由测试 JVM 内的 `DatabaseConfig.migrate` 按该模块 test runtime classpath 扫 `classpath:db/migration` 应用。这直接决定了「healthcare 先、aceso 后」是硬约束（见 §1.3、§3.4）。
3. **认证是进程内假认证，不涉及 token**：集成测试用 `Handler` 把 `userId` 放进 `RoutingContext`；浏览器验收才走真实 JWT（由用户管理的服务签发）。本文件与后续证据**一律不记录 token 值**。
4. **现有基线可跑**：不访库单测/路由测试 5 个模块合计 81 个类（dining 3 / nursing 21 / pharmacy 9 / healthcare 41 / aceso 5 个具体类 + 2 个支撑类），真库集成类 28 个。`libs/trainings` **没有 `src/test`**，`:libs:trainings:test` 为 NO-SOURCE。
5. **前端**：`ui-astro` 根 `package.json` 有 `test:unit:aceso` = `node --test apps/aceso/tests/*.test.ts`（8 个纯函数用例文件，不访库）；`pnpm --filter @pitchfork/aceso build` = `astro check && astro build`。`apps/aceso` 自身 `package.json` **没有 test script**。
6. **⚠️ 阻塞（本会话环境）**：`podman ps -a` 在当前沙箱直接失败 —— `Failed to obtain podman configuration: set sticky bit on: chmod /run/user/1000/libpod: read-only file system`。宿主机无 `docker`、无 `psql`、无 `pg_isready`，`55432` 无监听。**因此当前会话无法自起测试库，真库验收被环境阻塞**；需要 lead 提供可写 runtime 的 podman 环境，或由用户预先起好 55432 测试库（详见 §7）。

---

## 1. 隔离测试库入口

### 1.1 两个脚本的关系

| | `scripts/aceso-test.sh`（**推荐，唯一入口**） | `scripts/aceso-integration-tests.sh`（旧，保留兼容） |
|---|---|---|
| 定位 | 全生命周期：取密码 → 起/复用库 → 重置 → 跑测试 → 收摊 | 窄口径：重置库 + 只跑 2 个 Healthcare 类 |
| 密码 | 自动（`$PITCHFORK_DB_PASSWORD` → `$PITCHFORK_TEST_DB_PASSWORD` → `apps/aceso/.env`） | 必须手动 `export PITCHFORK_DB_PASSWORD`，否则 exit 2 |
| 端口 | 自动探测 55432 / 自动起容器；`--port` 可覆盖 | 固定默认 55432（`PITCHFORK_TEST_DB_PORT` 可覆盖），容器没起就 connection refused |
| 重置库 | 容器内 `psql`（宿主机无需 psql） | 容器优先，回退**宿主机 psql** |
| 测试范围 | `:libs:healthcare:test` → `:apps:aceso:test` 全部类 | 仅 `*MedicalOrderIntegrationTest*`、`*ElderlyDeathIntegrationTest*` |
| 容器生命周期 | 自动 `down` 自己起的隔离项目容器 + 真实收摊证据 | **不管理容器生命周期**（起收自理） |
| 结论 | 054 真库验收只用它 | 仅历史命令留存，不用于 054 |

### 1.2 关键常量（逐行核对 `aceso-test.sh`）

| 项 | 值 | 行号 |
|---|---|---|
| 容器名 | `pitchfork-aceso-test-db` | L62 |
| 测试端口 | `55432`（compose 映射 `127.0.0.1:${PITCHFORK_TEST_DB_PORT:-55432}:5432`） | L61 |
| 库名 | `aceso_test`（`ACESO_TEST_DB` 可覆盖，**改成别的名字会被拒绝并 exit 2**） | L57, L212–216 |
| 用户 | `ovaphlow`（`PITCHFORK_TEST_DB_USER`） | L58 |
| Host | `127.0.0.1` | L59 |
| 隔离项目名 | `pitchfork-aceso-test`（三层：compose 顶层 `name:` + 每次调用 `-p` + `COMPOSE_PROJECT_NAME`） | L70–71 |
| compose 文件 | `apps/aceso/compose.test.yaml`（`name: pitchfork-aceso-test`，`tmpfs: /var/lib/postgresql/data`，无持久卷） | L52 |
| 开发库（**禁止触碰**） | 容器 `pitchfork-aceso-db`，端口 `5432` | L63–64 |
| 就绪等待 | 容器内 `pg_isready`，默认 60s（`ACESO_READY_TIMEOUT`） | L73 |
| gradle 基础参数 | `--no-daemon -Pkotlin.compiler.execution.strategy=in-process` | L75 |

### 1.3 Flyway 迁移如何应用（重要）

- **脚本不跑 Flyway**：它只通过容器内 `psql` 执行
  `DROP DATABASE IF EXISTS aceso_test WITH (FORCE);` + `CREATE DATABASE aceso_test;`（L701–732）。
- **迁移在测试 JVM 内应用**：测试类调用 `DatabaseConfig.migrate(dbConfig)`，扫 `classpath:db/migration`。
  - `apps/aceso`：`AcesoDbIntegrationTestBase.setup` → `AcesoIntegrationTestSupport.migrate`（`AcesoIntegrationTestSupport.kt:142–144`）。
  - `libs/dining`：`DiningRoutesIntegrationTest.setup` 直接调用（`DiningRoutesIntegrationTest.kt:100`），且在 migrate 前先 `CREATE TABLE IF NOT EXISTS healthcare.patients/encounters` 造最小前置（L62–83）。
- **后果（顺序硬约束）**：`:apps:aceso:test` 的 testRuntimeClasspath 是全集（含 dining V600），`:libs:healthcare:test` 的子集不含 dining。若 apps 先跑，healthcare 的 Flyway validate 会报
  `Detected applied migration not resolved locally: 600`。所以脚本固定 **healthcare 先 → aceso 后**（L860–897）。
- **迁移号段**（根 AGENTS.md）：inventories V200+、pharmacy V300+、nursing V400+、healthcare V500+、dining V600–V699。
- 迁移只能由持有该 lib 的 app/模块 classpath 带入；**新增 054 迁移必须落在归属 lib**，并由真库运行的模块覆盖到。

### 1.4 fixture 与认证如何准备（不含任何 token 值）

- **认证**：
  - 集成测试 = 进程内假认证，不签发/不校验 JWT：`AcesoIntegrationTestSupport.fakeAuth()` 把 `userId = "integration-tester"` 写入上下文（`AcesoIntegrationTestSupport.kt:61–64`）；`DiningRoutesIntegrationTest` 的 stub 写 `userId = "tester"`（L116–120）。
  - 还有负向装配用于证明 fail-closed：`/pharmacy-noauth/v1/*`（缺身份 → 401）、`/pharmacy-noport/v1/*`（缺端口 → 503）。
  - 浏览器验收走真实 JWT，由**用户管理**的 Aceso API 签发；验收证据只记录「登录成功/角色名/HTTP 状态码」，**不记录 token、密码、Cookie 值**。
- **fixture**：
  - 每个集成类声明 `fixturePrefix`（如 `AcesoDbIntegrationTestBase.fixturePrefix`、`DiningRoutesIntegrationTest.FIXTURE_PREFIX = "sr-"`）。
  - `AcesoDbIntegrationTestBase` 在每个测试前后执行 `cleanupFixtures()` + `assertNoResidual()`，`@AfterAll` 再收一次，保证残差为零（`AcesoDbIntegrationTestBase.kt:95–127`）。
  - 通用前缀清理器 `cleanupAll(prefix)` 按 `id LIKE '$p%'` / 业务外键级联删除，顺序为「子表 → 父表」，最后删 `healthcare.encounters/patients`（L152–211）。
  - **054 相关缺口（需开发/计划关注）**：`cleanupAll` **完全没有 `dining.*` 语句**（已逐行确认 L152–211）。054 若新增 dining 真库测试，必须自带 dining 清理（现有 `DiningRoutesIntegrationTest` 自行删除 `dining_meal_executions → dining_roster_items → dining_diet_profiles`，可作为模板）。
  - 真库 fixture 一律用**固定前缀 + 唯一后缀**，禁止用历史数据做断言（052 口径）。

### 1.5 退出码

| 码 | 含义 |
|---|---|
| `0` | 两个模块都通过，且收摊成功（`down` 成功 + 容器消失 + 隔离项目名下无残留 + 端口不再监听） |
| `1` | 任一模块测试失败；或容器启动失败 / 就绪超时 / 重置库失败 / 找不到运行时 / `gradlew` 缺失或 chmod 失败 / 内部不变量被破坏 |
| `2` | 用法或前置错误：未知参数、`--port` 非 55432/5432、库名不是 `aceso_test`、缺密码 |
| `3` | **测试本身通过但收摊未成功**（`down` 失败 / 容器仍在 / 仍有残留 / 端口仍监听）——宁可非 0 也不给假 OK |
| `126/127`（gradle 子进程） | wrapper 不可执行或 java 不在 PATH，脚本会额外 warn「gradle 根本没跑起来」 |

### 1.6 日志位置

| 内容 | 位置 |
|---|---|
| 脚本自身输出 | **只到 stdout/stderr，不写日志文件**；需留档自行 `tee`（密码本就不回显，可安全 tee） |
| 逐类结果 XML | `service-vertx-kotlin/<module>/build/test-results/test/TEST-*.xml`，如 `libs/healthcare/build/test-results/test/`、`libs/dining/build/test-results/test/`、`apps/aceso/build/test-results/test/` |
| HTML 报告 | `service-vertx-kotlin/<module>/build/reports/tests/test/index.html` |
| 容器日志 | `podman logs pitchfork-aceso-test-db` |
| 应用运行日志 | `service-vertx-kotlin/logs/app.jsonl`、`logs/app.<YYYY-MM-DD>.jsonl`；`apps/aceso/logs/` |

### 1.7 是否自起自收

- **自起**：55432 未监听且检测到 podman/docker 时，脚本用 `-p pitchfork-aceso-test -f apps/aceso/compose.test.yaml up -d` 起容器（L531–554）。
- **自收**：`trap cleanup EXIT`（L489）覆盖正常退出、失败、Ctrl+C；只 `down` **自己起的**那个容器。容器不是它起的 → 保持原状并提示收尾命令（L418–423）。`--keep` 显式警告不静默遗留（L410–416）。
- **护栏**：任何 `down` 之前逐条断言项目名 == `pitchfork-aceso-test`（argv 的 `-p`、文件 `name:`、容器 compose 标签、compose 解析值），不成立就**拒绝 down**；**从不使用 `-f/--force`，不使用 `remove-orphans`**（L308–361）。
- **安全护栏**：只允许操作 `aceso_test`；密码只从环境变量/`.env` 读且不回显；密码经环境变量传给 Gradle，不进命令行（L38–41）。

---

## 2. 收摊证据命令与预期输出

### 2.1 四条证据命令（照抄）

```bash
# 1) 隔离项目名下不得有任何容器（含已退出）
podman ps -a --format '{{.Names}}\t{{.Status}}' | grep -F pitchfork-aceso-test || echo "无遗留容器"
# 2) 隔离 pod 不得存在
podman pod ps --format '{{.Name}}\t{{.Status}}' | grep -F pod_pitchfork-aceso-test || echo "无遗留 pod"
# 3) 隔离网络不得存在
podman network ls --format '{{.Name}}' | grep -F pitchfork-aceso-test || echo "无遗留网络"
# 4) 测试端口不得监听
ss -lntp | grep -F 55432 || echo "55432 无监听"
```

**收摊成功时的预期输出**（四条都应为「空/无」）：

```
无遗留容器
无遗留 pod
无遗留网络
55432 无监听
```

**运行期间（未收摊前）的应有形态**（用于对照，不要求出现）：

```
pitchfork-aceso-test-db   Up ...            # podman ps -a
pod_pitchfork-aceso-test  Running           # podman pod ps
pitchfork-aceso-test_default                # podman network ls
LISTEN ... 127.0.0.1:55432 ...              # ss -lntp
```

**另外必须同时确认未误伤用户开发库**（只读检查，不操作）：

```bash
podman ps -a --format '{{.Names}}\t{{.Status}}' | grep -F pitchfork-aceso-db || echo "开发库容器不在 podman 管理下或已停（本流程不得改动它）"
```

> 预期：`pitchfork-aceso-db` 的状态与流程开始前**完全一致**（开发库由用户管理，不得被 down/restart/删除）。

### 2.2 本会话实测基线（2026-10-06，只读）

已执行的四条命令在本会话的真实结果：

```
$ podman ps -a --format '{{.Names}}\t{{.Status}}'
Failed to obtain podman configuration: set sticky bit on: chmod /run/user/1000/libpod: read-only file system
$ podman pod ps      → 同上（同一错误）
$ podman network ls  → 同上（同一错误）
$ ss -lntp | grep -F 55432
(55432 无监听)
```

工具与端口基线（只读实测）：

| 项 | 实测 |
|---|---|
| `podman` / `podman-compose` | 二进制存在（`/usr/bin/podman`、`/usr/bin/podman-compose`），但配置目录 `/run/user/1000/libpod` 只读 → 所有 podman 子命令失败 |
| `docker` / `psql` / `pg_isready` | **不存在** |
| `java` | OpenJDK 25.0.4.1（符合 toolchain 要求） |
| `node` / `pnpm` | v24.18.0 / 11.7.0 |
| 监听端口 | `8421`(Nexus) `4324`(Aceso UI) `8422`(Aceso API) `8420`(Identity) `5432`(开发库) **均在监听，全部由用户管理，AI 不得启停**；`55432` 无监听 |

### 2.3 中途失败时的清理步骤

```bash
# 步骤 1：确认要 down 的项目名确实是隔离项目（不要盲敲）
grep -n '^name:' service-vertx-kotlin/apps/aceso/compose.test.yaml   # 预期：name: pitchfork-aceso-test

# 步骤 2：只 down 隔离项目，绝不加 -f/--force/remove-orphans
cd service-vertx-kotlin/apps/aceso
podman compose -p pitchfork-aceso-test -f compose.test.yaml down

# 步骤 3：若 down 失败，先看它的原始输出（常见原因：容器被别的进程占用）
podman logs pitchfork-aceso-test-db            # 看容器自身报错
ss -lntp | grep -F 55432                       # 端口是否仍有孤儿进程占用

# 步骤 4：复跑第 2.1 节四条证据命令，直到全部为「空/无」

# 步骤 5：若有非 podman 管理的孤儿测试进程占用 55432（只有确认是本次测试进程才杀）
ss -lntp | grep -F 55432                       # 记下 pid
ps -o pid,ppid,etime,cmd -p <pid>              # 确认命令行属于测试，不是用户服务
# 确认后再 kill <pid>；任何情况下不得杀 8420/8421/8422/4324/5432 的进程
```

**红线**：无论何种失败，都**不得**对 `pitchfork-aceso-db`（开发库）或任何用户服务执行 down/stop/kill/restart；脚本本身也不会（护栏见 §1.7）。

---

## 3. 现有测试盘点

### 3.1 模块 / 目录 / gradle 任务

| 模块 | gradle 任务 | 测试目录 | 测试类总数 | 不访库 | 真库集成 |
|---|---|---|---:|---:|---:|
| `libs/dining` | `:libs:dining:test` | `libs/dining/src/test/kotlin/com/ovaphlow/crate/dining/` | 5 | 3 | 2 |
| `libs/nursing` | `:libs:nursing:test` | `libs/nursing/src/test/kotlin/com/ovaphlow/crate/nursing/` | 30 | 21 | 9 |
| `libs/pharmacy` | `:libs:pharmacy:test` | `libs/pharmacy/src/test/kotlin/com/ovaphlow/crate/pharmacy/` | 9 | 9 | 0 |
| `libs/healthcare` | `:libs:healthcare:test` | `libs/healthcare/src/test/kotlin/com/ovaphlow/crate/healthcare/` | 47 | 41 | 6 |
| `libs/trainings` | `:libs:trainings:test` | **无 `src/test`** | 0 | 0 | 0（NO-SOURCE） |
| `apps/aceso` | `:apps:aceso:test` | `apps/aceso/src/test/kotlin/com/ovaphlow/crate/aceso/` | 17 | 6 | 11 |

> `apps/aceso` 的 17 个文件中含 2 个支撑类（抽象基类 `AcesoDbIntegrationTestBase`、对象 `AcesoIntegrationTestSupport`），具体测试类 15 个（真库 10、不访库 5）。
> 判据：类上有 `@EnabledIfSystemProperty(named = "integration.db.host", ...)` 记为「真库集成」；其余为「不访库」（含 mockk 桩 Pool 的嵌入式路由测试）。

### 3.2 分类清单

**真库集成类（28 个，缺 `-Dintegration.db.host` 时自动 skip）**

- `libs/dining`（2）：`DiningRoutesIntegrationTest`、`DiningServiceIntegrationTest`
- `libs/nursing`（9）：`ElderlyCareLifecycleIntegrationTest`、`NursingSupplyConsumptionIntegrationTest`、`ServicePeriodRoutesElderlyAdmissionTest`、`TaskExecutionConvergeIntegrationTest`、`TaskExecutionRehabilitationIntegrationTest`、`TaskExecutionRoutesOverdueTest`、`TaskExecutionRoutesStatisticsTest`、`TaskExecutionServiceOverdueIntegrationTest`、`TaskExecutionStatisticsIntegrationTest`
- `libs/healthcare`（6）：`CheckupCreateIntegrationTest`、`ElderlyDeathIntegrationTest`、`ElderlyDischargeHandoverIntegrationTest`、`HealthcareRoutesElderlyCareDischargeTest`、`MedicalOrderIntegrationTest`、`NursingIncidentHandoverIntegrationTest`
- `apps/aceso`（11，含抽象基类）：`BaseUnitCutoverIntegrationTest`、`BillReversalIntegrationTest`、`ChildHealthProfileIntegrationTest`、`DispenseIntegrationTest`、`DoseQuantityIntegrationTest`、`MedicationAdministrationIntegrationTest`、`PurchaseOrderIntegrationTest`、`RequisitionIntegrationTest`、`ReturnIntegrationTest`、`TextOrderRebindAdministrationIntegrationTest`（+ 抽象基类）
- `libs/pharmacy`（0）：**无真库类**，药房真库链路由 `apps/aceso` 的 `*IntegrationTest` 覆盖。

**不访库单测/路由测试（81 个类，默认验证永远可跑）**

- `libs/dining`（3）：`DiningUnitTest`、`DiningRoutesConflictTest`（mockk 桩 Pool 的 409/500 护栏）、`RosterRegenerateGenerationTest`
- `libs/nursing`（21）：`AssessmentServiceTest`、`CarePlanRevisionServiceTest`、`ElderlyDischargeHandoverSnapshotServiceTest`、`FrequencyCalculatorTest`、`MedicationAdministrationServiceTest`、`NursingIncidentServiceTest`、`OperatorDerivationTest`、`PlanRoutesAssessmentGateTest`、`ServicePeriodBusinessDateTest`、`ServicePeriodServiceElderlyCareValidationTest`、`TaskExecutionConvergeTest`、`TaskExecutionQuerySqlTest`、`TaskExecutionServiceConsumptionTest`、`TaskExecutionServiceMedicalOrderTest`、`TaskExecutionServiceOverdueTest`、`TaskExecutionServiceRehabilitationTest`、`TaskExecutionServiceStaleTest`、`TaskExecutionStatisticsSqlTest`、`TaskExecutionStatisticsTest`、`TaskExecutionStatusPermissionWiringTest`、`TaskServiceMedicalOrderTest`
- `libs/pharmacy`（9）：`DispenseServiceTest`、`OperatorDerivationTest`、`PurchaseOrderServiceTest`、`PurchaseOrdersRoutesTest`、`QuantitiesTest`、`RequisitionRoutesTest`、`RequisitionServiceTest`、`ReturnableQuantitiesTest`、`ReturnServiceTest`
- `libs/healthcare`（41）：`AdmissionConflictTest`、`BedOccupancyTest`、`BedRoutesTest`、`BedServiceTest`、`BillingEngineTest`、`BillingPrecheckTest`、`BillingSettlementTest`、`BillingWriteOffTest`、`BillReversalServiceTest`、`BillServiceTest`、`CarePlanRevisionTest`、`CheckupServiceTest`、`ChildCheckupDiagnosisRoutesTest`、`ChildGuidanceTemplateTest`、`ChildHealthAgeTest`、`ChildHealthRoutesTest`、`ChildHealthValidationTest`、`ChildReferralRoutesTest`、`ChildScreeningConclusionRoutesTest`、`ChildScreeningRoutesTest`、`ChildVaccinationScheduleTest`、`ChildVitalThresholdsTest`、`ChronicDiseaseServiceTest`、`DepositOffsetServiceTest`、`DepositServiceTest`、`DevelopmentalScoringTest`、`DischargeBillingWindowTest`、`DoctorClinicalTest`、`FeeItemServiceTest`、`FollowupServiceTest`、`HealthcareBusinessDateTest`、`HealthcareDischargeHandoverTest`、`HealthcareIncidentHandoverRoutesTest`、`HealthcareMedicalOrderTest`、`MedicalOrderAdministrationSummaryTest`、`MedicalOrderPrescribedQuantityTest`、`NursingRecordValidationTest`、`OrderMaterialBatchBindingTest`、`PaymentServiceTest`、`VitalSignReviewTest`、`VitalSignServiceTest`
- `apps/aceso`（5 具体 + 1 支撑）：`ApiBodyHandlingTest`、`DeclaredPermissionsTest`、`DefaultAuthenticationTest`、`IdpSessionAuthHandlerTest`、`SubjectPermissionGateTest`（+ 支撑 `AcesoIntegrationTestSupport`）

### 3.3 两条可直接照抄的命令

**A. 不访库单测/路由测试（不需要密码、不需要数据库，任何真库类自动 skip）**

```bash
cd service-vertx-kotlin
./gradlew :libs:dining:test :libs:nursing:test :libs:pharmacy:test :libs:healthcare:test :apps:aceso:test
```

单模块等价：`./gradlew :libs:dining:test`（或 `:libs:nursing:test` / `:libs:pharmacy:test` / `:libs:healthcare:test` / `:apps:aceso:test`）。
> 未实测（本阶段只读，构建车道归开发）。文档依据：`docs/aceso-test-database.md` L35–43 明确「不给库参数时集成测试自动 skip，命令是绿的」。

**B. 真库集成（推荐唯一入口，自起自收 55432 / `aceso_test`）**

```bash
cd service-vertx-kotlin
./scripts/aceso-test.sh
```

> 未实测（本阶段只读 + 本会话 podman 不可用）。脚本会打印「实际执行的完整 gradle 命令」（密码 `<hidden>`）供复制排错。
> 可选：`--port 5432`（借用开发库端口容器承载 `aceso_test`，仍只 DROP/CREATE `aceso_test`）、`--keep`（本轮内保留容器，离开前必须自己 down）、`--skip-reset`、`--offline`、`--container <名>`。

**定向真库复验**（只复验受影响项时用；**前提是 55432 上的 `aceso_test` 已由 `aceso-test.sh` 或用户准备好，且此命令不自管容器生命周期**）：

```bash
cd service-vertx-kotlin
PITCHFORK_DB_PASSWORD=<测试库密码> ./gradlew :libs:dining:test --rerun \
  --tests "*DiningRoutesIntegrationTest" \
  -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
  -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

> ⚠️ 不要用 `./scripts/aceso-test.sh -- --tests "*Xxx"` 做定向：passthrough 参数会同时传给 healthcare 与 aceso 两段，healthcare 段会因找不到匹配类而失败。定向复验请用上面的直连 gradlew 形态，并在交接里写明「容器由谁收」。

### 3.4 顺序硬约束（复述，必须遵守）

1. 真库全量：**`:libs:healthcare:test` 先 → `:apps:aceso:test` 后**（`aceso-test.sh` 已内置）。
2. 手工直连 gradle 时同样要维持此顺序，反了会触发 `FlywayValidateException: Detected applied migration not resolved locally: 600`。
3. `public.stocks` 预检只是 `--skip-reset` 下的兜底体检；默认流程刚重置库时不做预检。

---

## 4. 前端测试入口

### 4.1 现状（读 `ui-astro/package.json`、`ui-astro/apps/aceso/package.json`、`ui-astro/AGENTS.md`）

| 入口 | 定义 | 说明 |
|---|---|---|
| `pnpm test:unit:aceso` | `node --test apps/aceso/tests/*.test.ts`（在 `ui-astro/` 下执行） | 8 个纯函数用例：`age`、`api-error-messages`、`datetime`、`domain-labels`、`execution-statistics`、`growth`、`guidance`、`vaccination`。**不访库、不访网、不启服务**；Node 24 原生 TS type-stripping |
| `pnpm --filter @pitchfork/aceso build` | `astro check && astro build`（app 的 `build` script） | 类型检查 + 构建；需在 `ui-astro/` 下执行。**本阶段不实跑** |
| `pnpm test:e2e` / `test:e2e:aceso` | `playwright test` | 浏览器验收；`playwright.config.ts` 强制 `PLAYWRIGHT_BASE_URL` 必填、**禁止配置 `webServer`**（不得启停用户服务） |
| `@pitchfork/aceso` 的 `package.json` | **没有 test script** | 单测入口只在 `ui-astro` 根 |

### 4.2 前端验收前置（浏览器链）

- `PLAYWRIGHT_BASE_URL` 必须指向**用户管理**的 Aceso UI（默认 `http://127.0.0.1:4324`）。
- 若要验真库数据，Aceso API 必须由**用户**配置到同一个 `aceso_test`（参考 `apps/aceso/aceso-e2e-test-config.json`：host 127.0.0.1 / port 55432 / database aceso_test；该文件里 nexus/identity 端口为 8423/8430，与当前实际监听 8421/8420 不同，使用前须与用户确认）。
- Playwright 缓存缺浏览器时用 `PLAYWRIGHT_EXECUTABLE_PATH=/usr/bin/chromium-browser`；直接清库时设 `PLAYWRIGHT_DB_PORT=55432`。
- **AI 不得启停 4324/8422**；浏览器验收必须等用户把 API 指向测试库后由用户保持运行。

---

## 5. 054 验收计划草案

> ⚠️ `docs/plans/054.*` 尚未发布。本节给出**可复用模板与当前基线路径**；冻结后须把「最小复现命令 / 预期」按 054 契约（路由、字段、阈值、权限码、迁移号）逐条回填，并标注变更点。

### 5.1 通用验收流程（每条链都按此五段填写）

```
前置数据 → 最小复现命令 → 预期 → 实际（待填）→ 脱敏证据
```

- **真库判定口径**：
  - 「必须真库」= 需要 Flyway 建表、跨表聚合、唯一约束/事务/库存扣减、或权限落库才能成立。
  - 「可无库验证」= 纯函数、DTO 映射、路由装配与权限声明、mockk 桩 Pool 的 409/500 分支、前端纯函数。
- **每条链先跑无库基线，再跑真库**：无库红 → 不进入真库验收。
- **脱敏证据固定四件套**（每次验收必附）：
  1. 实际执行的完整命令（密码以 `<hidden>` 代替）；
  2. 模块 `build/test-results/test/TEST-*.xml` 的 `tests/failures/errors/skipped` 汇总，或 API 的 HTTP 状态码 + 脱敏响应体（无 token、无姓名/身份证/手机号）；
  3. 残差对账 SQL 结果为 `0`；
  4. 收摊四证据（§2.1）全部为「空/无」。
- **回收**：测试期新建数据必须在本轮内删除；容器 `down` 后 `tmpfs` 数据即销毁，但 `--keep`/`--port 5432` 场景必须显式对账残差。

### 5.2 六条链模板

#### D1 营养师 · 摄入记录与营养分析

- **真库要求**：**必须真库**（写入就餐执行 + 跨表聚合）。
- **可无库前置**：`./gradlew :libs:dining:test --tests "*DiningUnitTest" --tests "*RosterRegenerateGenerationTest"`（逻辑基线）。
- **前置数据**：在院长者 `healthcare.patients` + 在院 `healthcare.encounters`；启用饮食档案 `dining_diet_profiles`（`meal_type`/`allergies`/`portion_preference`）；当周启用周菜谱 + 配餐名单 `dining_rosters`/`dining_roster_items`；一条待登记的就餐记录。
- **最小复现命令**（当前基线形态，054 路由以契约为准）：
  ```bash
  cd service-vertx-kotlin && ./scripts/aceso-test.sh          # 全量真库
  # 定向复验（55432 已就绪时）：
  PITCHFORK_DB_PASSWORD=<hidden> ./gradlew :libs:dining:test --rerun \
    --tests "*DiningRoutesIntegrationTest" \
    -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
    -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow
  ```
- **预期**：摄入登记返回 201 且幂等更新同一「长者×餐次」；营养分析按区间返回 `{records:[...], meta:{total:N}}`（空为 `[]`/`0`），聚合值与手工对账一致（能量/蛋白等阈值以 054 契约为准）。
- **实际**：待填。
- **脱敏证据**：HTTP 状态码 + 脱敏响应片段；`SELECT count(*)` 残差 = 0；`libs/dining/build/test-results/test/TEST-*.xml` 汇总；收摊四证据。

#### D2 营养师 · 过敏原与特殊膳食申请

- **真库要求**：**必须真库**（唯一约束、申请状态机、过敏原命中拦截）。
- **可无库前置**：`./gradlew :libs:dining:test --tests "*DiningRoutesConflictTest"`（已覆盖唯一约束 409 的 4 种失败形态）。
- **前置数据**：长者过敏原 `dining_diet_profiles.allergies`（JSONB 字符串数组）；菜品 `dining_dishes.diet_tags`；重复启用档案（验证 `uq_dining_diet_profiles_active_patient`）。
- **最小复现命令**：同 D1 的定向命令（`--tests "*Dining*"`）+ 契约新增的过敏原/申请路由。
- **预期**：过敏原命中 → 明确 4xx（不得静默通过）；重复启用档案 → 409；特殊膳食申请创建 201、状态流转合法、非法流转 409/422（以契约为准）。
- **实际**：待填。
- **脱敏证据**：状态码 + 错误 `{error:"..."}` 文案；唯一索引冲突证据（不贴含个人信息的行）；残差 = 0。

#### D3 营养师 · 营养周报

- **真库要求**：**必须真库**（跨 ≥7 天聚合、请假/外出排除口径）。
- **可无库前置**：`./gradlew :libs:nursing:test --tests "*TaskExecutionStatisticsTest"`（区间/口径逻辑参照）+ 前端 `pnpm test:unit:aceso`（`execution-statistics` 用例）。
- **前置数据**：跨周就餐执行记录（含 `adjust_type = 外出/请假` 与正常就餐）；周菜谱快照；边界日（周首/周末）。
- **最小复现命令**（当前基线统计路由）：
  ```bash
  # 在已就绪的 55432 上，以用户管理的 Aceso API 或集成测试发起：
  GET /crate-api/dining/v1/statistics/meals?date_from=<周一>&date_to=<周日>
  ```
- **预期**：区间含首尾；请假/外出不计入配餐口径；`meta.total` 与 SQL 对账一致；空区间返回 `records:[]`/`total:0`。
- **实际**：待填。
- **脱敏证据**：SQL 对账查询与结果（仅计数）；脱敏响应；残差 = 0。

#### 康复师 · 康复任务执行

- **真库要求**：**必须真库**（`nursing.nursing_task_executions` + 服务期 + 康复域）。
- **可无库前置**：`./gradlew :libs:nursing:test --tests "*TaskExecutionServiceRehabilitationTest"`。
- **前置数据**：在院长者 + 启用服务期 `nursing.nursing_service_periods`；康复类护理任务；执行记录（含超时/幂等边界）。
- **最小复现命令**：
  ```bash
  cd service-vertx-kotlin && ./scripts/aceso-test.sh
  # 定向：
  PITCHFORK_DB_PASSWORD=<hidden> ./gradlew :libs:nursing:test --rerun \
    --tests "*TaskExecutionRehabilitationIntegrationTest" \
    -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
    -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow
  ```
- **预期**：康复执行登记成功；重复登记幂等；统计口径与无库基线一致；越权 403、缺身份 401。
- **实际**：待填。
- **脱敏证据**：测试 XML 汇总；残差 = 0；收摊四证据。

#### 保育员 · 配餐名单生成与就餐执行

- **真库要求**：**必须真库**（按「日期+餐次」唯一生成、名单条目唯一约束、幂等执行）。
- **可无库前置**：`./gradlew :libs:dining:test --tests "*RosterRegenerateGenerationTest"`。
- **前置数据**：多份启用饮食档案（含过敏/外出/请假）；当周启用周菜谱；`dining_rosters` 已有同日同餐次（验证 `uq_dining_rosters_meal`）。
- **最小复现命令**：集成测试装配里名单生成走无鉴权路由 `/dining-open/v1/rosters/generate`（`DiningRoutesIntegrationTest.kt:214`）；生产路径以 054 契约为准：
  ```bash
  POST /crate-api/dining/v1/rosters/generate   # 当前基线
  ```
- **预期**：同一「日期+餐次」只生成一份名单；同一「长者×餐次」一条（`uq_dining_roster_items_patient`）；外出/请假排除、临时加餐纳入；重复生成幂等不重复插入。
- **实际**：待填。
- **脱敏证据**：名单条数 SQL 对账；重复生成前后计数不变；残差 = 0。

#### 药师 · 发药 / 领药 / 退药

- **真库要求**：**必须真库**（库存扣减/回补、批次、幂等）。
- **可无库前置**：`./gradlew :libs:pharmacy:test`（9 个类全绿，无需库）。
- **前置数据**：药品 `public.materials` + 批次 `public.lots` + 库存 `public.stocks`；已护士核对的医嘱（`healthcare.medical_orders`）；药房出库/退库/领药单据。
- **最小复现命令**：
  ```bash
  cd service-vertx-kotlin && ./scripts/aceso-test.sh
  # 定向（apps/aceso 覆盖药房真库链路）：
  PITCHFORK_DB_PASSWORD=<hidden> ./gradlew :apps:aceso:test --rerun \
    --tests "*DispenseIntegrationTest" --tests "*RequisitionIntegrationTest" --tests "*ReturnIntegrationTest" \
    -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
    -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow
  ```
- **预期**：发药扣减库存并写明细；退药回补且成本口径一致；领药预留→转移→释放状态正确；幂等键重复提交不重复扣减；库存不足 → 409。
- **实际**：待填。
- **脱敏证据**：库存前后计数对账；`public.stocks/lots/materials` 残差 = 0；测试 XML；收摊四证据。

#### 收银 · 结算 / 支付 / 押金冲抵

- **真库要求**：**必须真库**（账单/支付/押金落库与金额口径）。
- **可无库前置**：`./gradlew :libs:healthcare:test --tests "*Billing*" --tests "*Payment*" --tests "*Deposit*"`。
- **前置数据**：在院长者 + 账单 `healthcare.bills` + 费用明细 `bill_items`；押金 `deposit_records`；欠费/退费边界。
- **最小复现命令**：
  ```bash
  cd service-vertx-kotlin && ./scripts/aceso-test.sh
  # 定向：
  PITCHFORK_DB_PASSWORD=<hidden> ./gradlew :apps:aceso:test --rerun \
    --tests "*BillReversalIntegrationTest" \
    -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
    -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow
  ```
- **预期**：结算金额与费用项合计一致；支付后欠费口径更新；押金冲抵正确；冲正/退费不重复计账；跨库访问被拒绝。
- **实际**：待填。
- **脱敏证据**：金额对账 SQL（仅汇总数字）；测试 XML；残差 = 0；收摊四证据。

#### 管理员 · 权限 / 角色 / 部门

- **真库要求**：**以可无库验证为主**；角色/部门落库与绑定链路需真库。
- **可无库验证（优先，无需库参数）**：
  ```bash
  cd service-vertx-kotlin
  ./gradlew :apps:aceso:test --tests "*DeclaredPermissionsTest" --tests "*SubjectPermissionGateTest" --tests "*DefaultAuthenticationTest"
  ```
  预期：所有新增写路由都登记进 `DECLARED_PERMISSIONS`（`/dining/v1` 写路由已在列，`DeclaredPermissionsTest.kt:293–294`）；dining 管理权限码为 **`dining:manage`**（`DeclaredPermissions.kt:289`，目录项 `DeclaredPermissions.kt:357`，现有 16 条 `/dining/v1` 写路由 L207–222 全部使用该码）；缺权限 403、缺身份 401、缺端口 503。
- **真库部分**：角色分配/部门绑定（046/039 口径）在 `aceso_test` 上验证落库与门控生效。
- **最小复现命令（真库）**：`./scripts/aceso-test.sh` 全量 + 契约新增的管理员链路定向类。
- **预期**：权限变更后同一请求由 403 → 200；不得出现「无权限静默通过」；`DECLARED_PERMISSIONS` 与实际注册路由集合一致（负向用例）。
- **实际**：待填。
- **脱敏证据**：声明路由与权限码清单（脱敏）；状态码变化证据；残差 = 0。

### 5.3 验收矩阵（真库必要性一览）

| 链 | 无库可验证部分 | 必须真库部分 |
|---|---|---|
| D1 营养师·摄入/营养分析 | dining 纯函数、路由装配 | 摄入登记、跨表营养聚合 |
| D2 营养师·过敏原/特殊膳食 | `DiningRoutesConflictTest`（409 分支） | 过敏原拦截、申请状态机、唯一约束 |
| D3 营养师·营养周报 | 统计区间逻辑、前端 `execution-statistics` | 跨日聚合、请假/外出排除口径 |
| 康复师 | `TaskExecutionServiceRehabilitationTest` | 任务执行落库、服务期关联 |
| 保育员 | `RosterRegenerateGenerationTest` | 名单唯一约束、幂等生成、执行登记 |
| 药师 | `:libs:pharmacy:test` 全部 9 类 | 库存扣减/回补、批次、幂等 |
| 收银 | healthcare Billing/Payment/Deposit 单测 | 账单/支付/押金落库与金额口径 |
| 管理员 | `DeclaredPermissionsTest`、`SubjectPermissionGateTest` | 角色/部门落库与绑定生效 |

---

## 6. 禁止事项（硬约束）

1. **不得连接 5432 开发库或任何业务库**：真库只允许 `aceso_test`（脚本已内置拒绝非 `aceso_test` 的护栏）。`--port 5432` 只是借用该端口的 PostgreSQL 服务承载 `aceso_test`，仍只 DROP/CREATE `aceso_test`。
2. **不得启停用户管理的服务**：`8420`(Identity)、`8421`(Nexus)、`8422`(Aceso API)、`4324`(Aceso UI)、`5432`(开发库) 及容器 `pitchfork-aceso-db` —— 不启动、不停止、不杀进程、不重绑端口、不起替代实例。
3. **不得 `git reset/checkout/clean/stash`**：保留用户未提交改动（046–053）。测试角色的写入范围仅 `docs/notes/054-qa-plan.md`（后续真库阶段如需新增测试，须由计划明确分配，不得自行扩大）。
4. **测试数据必须回收**：本轮内新建数据全部删除并给残差证据；测试容器 `down` 干净并给收摊四证据；不得让测试容器/进程跨会话存活。
5. **证据脱敏**：不记录 token、密码、Cookie、身份证/手机号/姓名等个人信息；密码一律 `<hidden>`。
6. **不得把「构建成功」当验收**：真库验收必须给出实际执行的命令、断言结果与残差对账。

---

## 7. 阻塞、环境与授权清单（需 lead 处理）

| # | 项 | 现状 | 需要的动作 |
|---|---|---|---|
| B1 | **podman 在当前沙箱不可用** | `podman ps -a` → `chmod /run/user/1000/libpod: read-only file system`（沙箱只允许 workspace 写入；本会话无法提权，`sandbox_permissions` 被禁用） | lead 在可写 runtime 的会话执行 `./scripts/aceso-test.sh`；或由用户预先起好 55432 测试库后，qa 用定向 gradlew 复验 |
| B2 | 无 `psql` / `pg_isready` / `docker` | 宿主机缺这些工具 | 不影响推荐入口（脚本用容器内 psql）；但手工兜底路径不可用 |
| B3 | **054 计划未发布** | `docs/plans/054.*` 不存在 | pm/architect 冻结契约（路由、字段、阈值、权限码、迁移号）后，qa 回填 §5 的「最小复现命令 / 预期」 |
| B4 | 浏览器验收需用户保持 API 指向 `aceso_test` | 当前 8422 指向开发库（用户管理） | 用户自行把 API 切到 `aceso_test` 并保持运行；AI 不得启停 |
| B5 | `aceso-e2e-test-config.json` 的 nexus/identity 端口（8423/8430）与实际（8421/8420）不一致 | 文件历史值 | 浏览器验收前与用户确认端口 |
| B6 | `AcesoDbIntegrationTestBase.cleanupAll` 无 `dining.*` 清理语句 | 已逐行确认 | 054 dining 真库测试必须自带 dining 清理；建议开发在新增测试中复用前缀清理并在 `assertNoResidual` 断言 0 |

### 首批真库验收需要的最小授权

1. 一个可用的 podman（runtime 目录可写），或一个用户已起好的 `127.0.0.1:55432` 上的 `aceso_test`；
2. `apps/aceso/.env` 或环境变量里的**测试库**密码（不写入任何证据文件）；
3. 明确「本次测试容器由谁起、由谁收」，并在交接里附收摊四证据；
4. 054 冻结契约与首批候选版本（开发交付后）。

---

## 8. 交接与缺陷回传格式

**验收交付包**：改动/新增文件清单 + 实际执行命令 + 结果 + 残差对账 + 收摊证据 + `git status --short` + 未决问题 + 结论（通过/不通过/阻塞）。

**失败回传格式**（测试必须提供）：

```
前置数据：<最小可复现的数据与步骤>
最小复现命令：<完整命令，密码 <hidden>>
预期：<契约要求的可观察结果>
实际：<真实观察结果>
脱敏证据：<状态码/日志片段/测试 XML 汇总/残差计数；无 token、无个人信息>
```

开发修复后重新交付候选版本，测试只复验受影响项，评审再作最终判断；最终状态由 lead 更新。

---

## 9. 真库验收记录与运维提示（2026-10-06 更新）

### 9.1 A 链真库验收结论：**通过**

| 项 | 结果 |
|---|---|
| 定向 `:libs:dining:test --tests '*ChildDietitianWorkflowIntegrationTest'` | **7/7**（failures=0 errors=0 skipped=0），残差 0 断言通过 |
| 模块全量 `:libs:dining:test`（含 `DiningRoutesIntegrationTest`、`DiningServiceIntegrationTest`） | **137/137**（failures=0 errors=0 skipped=0） |
| 覆盖 | V601 DDL+唯一索引、幂等 UPSERT、A3 终版口径（总量=落库值、明细之和=总量、无快照不回落常量）、A4 状态机与覆盖式过敏原（无过敏主数据表）、A5 跨周聚合与同周覆盖、空数据周可判定 |

验收中修复的**测试缺陷**（生产无问题）：查询串中文枚举未百分号编码（`meal_time=午餐`、`status=已终止`）导致服务端白名单解析失败 400。修法：统一 `enc()` 助手 `URLEncoder.encode(v, UTF_8)`，**所有**查询参数（含日期/ID）一并编码。前端共享客户端用 `URLSearchParams` 构造查询，生产链路无此问题。

### 9.2 ⚠️ 运维提示：复用既有 55432 时脚本**不会**重置库

`scripts/aceso-test.sh` 探测到 `127.0.0.1:55432` 已可连时走「直接复用」分支，**跳过 `DROP/CREATE aceso_test`**。若上一轮测试（如 dining 集成类自建的**最小 `healthcare.patients` 表**）在库中留下非 Flyway 管理的表，则后续模块的 `DatabaseConfig.migrate` 会在 V500 报
`relation "patients" already exists`。

**正确做法（不改脚本，只改操作）**：
```bash
# 复用前先显式重置（容器内 psql；宿主机无 psql 也可用）
podman exec pitchfork-aceso-test-db psql -U ovaphlow -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS aceso_test WITH (FORCE);" -c "CREATE DATABASE aceso_test;"
```
或**不复用**：先 `down` 掉容器再跑 `./scripts/aceso-test.sh`（脚本自起自收并重置）。
`--keep` 只适合「同一轮内连续跑同一模块」，跨模块/跨轮复用必须先重置。

**密码来源必须与容器创建时一致（2026-10-06 实测踩坑）**：容器 `POSTGRES_PASSWORD` 取
`compose.test.yaml` 的 `${PITCHFORK_TEST_DB_PASSWORD:-pitchfork-test-only}`。
- 经 `scripts/aceso-test.sh` 起的容器：密码 = 脚本取到的 `PITCHFORK_DB_PASSWORD`（即 `apps/aceso/.env` 的值）。
- **手工 `podman compose ... up -d` 且未导出 `PITCHFORK_TEST_DB_PASSWORD`**：密码回落为默认值 `pitchfork-test-only`，
  此时用 `.env` 里的密码跑定向 gradle 会得到 `FATAL: password authentication failed for user "ovaphlow"`。
- 定向复跑前先确认容器是「谁起的、用哪个密码」；不确定时用默认值试一次（脚本自身也有「校验密码可登录」这一步会提前报错）。

### 9.2.1 定向真库复跑的标准动作（复用已有 55432）

```bash
cd service-vertx-kotlin
set -a && . apps/aceso/.env && set +a
# 若容器是手工起的、未导出 PITCHFORK_TEST_DB_PASSWORD，则改用容器实际密码（常见默认值）：
# export PITCHFORK_DB_PASSWORD=pitchfork-test-only
./gradlew :libs:<module>:test --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
  -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
  --tests '*<IntegrationTest>'
```
集成测试类自身会在 `@BeforeAll` 执行 `DROP DATABASE aceso_test WITH (FORCE)` + `CREATE DATABASE`（JDBC，不需要 podman），
因此即使无法 `podman exec` 也能获得干净库；显式重置只是双保险。

### 9.3 真库 fixture 必须包含 `healthcare.patients.person_type`（054 A4b 起）

054 A4b 让 `DietProfileService` 按 `person_type` 分流（`SELECT id, person_type FROM healthcare.patients`）。
dining 集成测试自建的**最小 `healthcare.patients` 表**原先没有该列 → `POST /diet-profiles` 直接 500。
fixture 口径（与 V525 一致）：
```sql
ALTER TABLE healthcare.patients ADD COLUMN IF NOT EXISTS person_type VARCHAR NOT NULL DEFAULT '居民';
```
`ChildDietitianWorkflowIntegrationTest` 已按此补齐；`DiningRoutesIntegrationTest` / `DiningServiceIntegrationTest`
的最小 healthcare schema 若不同步补列，A4b 落地后会继续 500（**属开发侧的既有测试 fixture 缺口**）。

### 9.4 非 054 的既有基线缺陷（两簇合并表，不并入 054 验收结论）

**结论口径（两簇一致）**：均为 **054 未改动的既有类**、**单独运行可复现**（排除「多类共库顺序干扰」与「全量套件相互影响」）、
**不计入 054 验收**；建议**单开基线修复任务**，不在 054 范围内修（保持范围纪律）。

#### 簇 1 —— healthcare / apps:aceso 药房·医嘱链路（7 failures / 6 类）

qa 于 2026-10-06 独立复现（先 JDBC 重置 `aceso_test`，再按模块单独定向运行）：

| 类 | 模块 | tests | failures | 一行根因 | 复现证据 |
|---|---|---:|---:|---|---|
| `MedicalOrderIntegrationTest` | `:libs:healthcare` | 20 | 1 | 期望 400「医生必填」，实际 **401**（认证/权限口径在 042/046 后收紧，测试未同步） | 干净库 + healthcare 单独定向运行即复现 |
| `DispenseIntegrationTest` | `:apps:aceso` | 10 | 2 | 发药闭环 / 存量补绑期望 200，实际 **400** | 单独定向运行即复现 |
| `DoseQuantityIntegrationTest` | `:apps:aceso` | 1 | 1 | `{"error":"operator cannot be delegated"}`（042 fail-closed） | 单独定向运行即复现 |
| `MedicationAdministrationIntegrationTest` | `:apps:aceso` | 2 | 1 | 期望 200，实际 **400** | 单独定向运行即复现 |
| `ReturnIntegrationTest` | `:apps:aceso` | 1 | 1 | `operator cannot be delegated`（创建退药单） | 单独定向运行即复现 |
| `TextOrderRebindAdministrationIntegrationTest` | `:apps:aceso` | 1 | 1 | 期望 200，实际 **400**（补绑后给药来源链路） | 单独定向运行即复现 |
| **小计** | | **35** | **7** | | |

#### 簇 2 —— nursing 逾期/供给/周期/康复链路（25 failures / 5 类）

qa 于 2026-10-06 C 链窗口独立复现（全量 482 tests / 25 failures；随后逐类单独运行，同样复现）：

| 类 | tests | failures | 一行根因 | 复现证据 |
|---|---:|---:|---|---|
| `TaskExecutionServiceOverdueIntegrationTest` | 9 | 9 | overdue 筛选与 SQL 派生断言全线失败 | 单独运行同样复现 |
| `TaskExecutionRoutesOverdueTest` | 12 | 8 | `/overdue` 返回 `{"error":"internal error"}`（500）、尾部斜杠 500 | 单独运行同样复现 |
| `TaskExecutionRehabilitationIntegrationTest` | 7 | 3 | 康复活动按 `task_type` 过滤 / 取消后不再排期断言失败 | 单独运行同样复现 |
| `NursingSupplyConsumptionIntegrationTest` | 10 | 3 | 库存合并、非法批次整体回滚、缺库存 404 映射失败 | 单独运行同样复现 |
| `ServicePeriodRoutesElderlyAdmissionTest` | 8 | 2 | 补建周期期望 200 实际 **404**、`encounter_id` 精确过滤失败 | 单独运行同样复现 |
| **小计** | **46** | **25** | | |

#### 附带发现：跨模块 Flyway 顺序约束是**硬约束**（同批记录，供基线任务参考）

在上述复现过程中确认：当 `aceso_test` 已被**超集模块**（`apps:aceso`，含 dining `V600`）推进后，
再单独运行 `:libs:healthcare:test` 会直接失败：

```
FlywayValidateException: Validate failed: Migrations have failed validation
Detected applied migration not resolved locally: 600.
```

即 `DatabaseConfig.migrate` 里的 `.ignoreMigrationPatterns("*:missing", "*:ignored")` **并未真正抑制**该错误
（与代码注释给人的印象不符）。因此 `scripts/aceso-test.sh` 的「**healthcare 先、aceso 后**」是**载荷约束**，
任何手工/定向运行都必须遵守；跨模块复用同一库前应先重置（见 §9.2）。

**054 侧对照**：A 链、A4b、B 链（B1–B4）、D 链、C 链在定向运行下**全部通过**
（A 7/7、A4b 6/6、B1 9/9、B2 7/7、B3 6/6、B4 7/7、D 8/8、C 13/13，且各自模块全量含 dev DB-free 类均全绿）。

### 9.5 收摊证据（lead 执行）

```
无遗留容器 / 无遗留 pod / 无遗留网络 / 55432 无监听
```
开发库 `pitchfork-aceso-db`（5432）状态未变，全程未触碰；用户服务 8420/8421/8422/4324 照旧。

### 9.6 真库断言规范（B/C/D/E 链适用，2026-10-06 实战沉淀）

以下每条都是**真库跑出来的教训**，新写集成测试一律照此办理：

1. **`TIMESTAMPTZ` 回读必须比较 `toInstant()`，`OffsetDateTime.parse` 后比较仍不够**。
   pgclient 回读 `TIMESTAMPTZ` 会归一到会话时区（通常 UTC），`2026-08-03T00:00+08:00` 会变成
   `2026-08-02T16:00Z`：**同一瞬时、不同 offset**，`OffsetDateTime.equals` 判不相等。
   正确写法：`assertEquals(expected.toInstant(), OffsetDateTime.parse(actual).toInstant())`。
   （另注：`OffsetDateTime.toString()` 在秒为 0 时会省略秒，故断言**不要比字符串**。）
2. **查询串里的中文枚举必须百分号编码**：`meal_time=午餐`、`status=已终止` 裸 UTF-8 进 URL 会 400。
   统一走 `enc(v) = URLEncoder.encode(v, UTF_8)`，**日期/ID 也一并编码**（前端 `URLSearchParams` 同口径）。
3. **路由路径逐字核对**，不要凭前缀猜：例如里程碑状态是
   `/nursing/v1/child-rehab-milestone-status/:patient_id`，**不是** `/child-rehab-milestones/status/:id`
   （后者是 Vert.x 路由 404，响应体为空，与业务 404 的 `{error}` 形状不同——空体 404 即路由未命中）。
4. **Vert.x 异步链用 `compose` 串联，不要叠 `onComplete`**：`onComplete` 返回原 `Future<T>` 且不接管
   handler 里的异步结果，叠两个 `onComplete` 会让第二段拿到第一段的结果（典型 NPE：`meta` 为 null）。
5. **每个模块的测试 JVM 参数转发不同**：dining/nursing 的 `tasks.withType<Test>` 会转发
   `integration.db.*` 与 `PITCHFORK_DB_PASSWORD`；**pharmacy 目前不转发系统属性**
   （`libs/pharmacy/build.gradle.kts` 只有 `useJUnitPlatform()`）。因此 pharmacy 真库用例以
   **环境变量**口径取配置（`PITCHFORK_TEST_DB_HOST/PORT/DATABASE/USER`），门控用
   `@EnabledIf("integrationDbAvailable")`（系统属性或环境变量任一存在才启用，**绝不回落到 5432**）。
   建议开发侧给 pharmacy 补齐与 dining/nursing 相同的转发，使统一命令生效。
6. **fixture 自带清理 + 残差 0 断言**：固定前缀 + 每次运行唯一后缀；`@BeforeEach/@AfterEach/@AfterAll`
   按前缀核对所有相关表计数为 0；`@BeforeAll` 用 JDBC `DROP DATABASE aceso_test WITH (FORCE)` 自重置
   （不依赖 podman/psql）。
7. **跨 schema 弱关联的对端表要自带**：pharmacy 测试 classpath 不含 inventories 迁移，故
   `public.materials` 由测试自建最小表（`id`/`name`），用于验证「无 FK、服务层校验存在性」。

---

## 附：本文件依据（逐项可复核）

- `service-vertx-kotlin/scripts/aceso-test.sh`（922 行，逐行阅读）
- `service-vertx-kotlin/scripts/aceso-integration-tests.sh`（80 行，逐行阅读）
- `service-vertx-kotlin/apps/aceso/compose.test.yaml`（39 行）
- `docs/aceso-test-database.md`（213 行）
- `service-vertx-kotlin/AGENTS.md`、根 `AGENTS.md`、`ui-astro/AGENTS.md`
- `service-vertx-kotlin/apps/aceso/src/test/kotlin/com/ovaphlow/crate/aceso/{AcesoDbIntegrationTestBase,AcesoIntegrationTestSupport}.kt`
- `service-vertx-kotlin/libs/dining/src/test/kotlin/com/ovaphlow/crate/dining/{DiningRoutesIntegrationTest,DiningRoutesConflictTest}.kt`
- `service-vertx-kotlin/libs/dining/src/main/resources/db/migration/V600__create_dining_tables.sql`
- `service-vertx-kotlin/{settings.gradle.kts,apps/aceso/build.gradle.kts,libs/*/build.gradle.kts}`
- `ui-astro/{package.json,playwright.config.ts,AGENTS.md}`、`ui-astro/apps/aceso/package.json`
- 只读实测：`podman ps -a` / `podman pod ps` / `podman network ls` / `ss -lntp` / `git status --short` / 工具版本
