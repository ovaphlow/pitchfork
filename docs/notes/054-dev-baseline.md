# 054 开发基线验证与 dining 域侦察

> ⚠️ **历史存档（2026-10-06）**：文中端口、模块与「未触碰 Trainova」等表述描述的是当时状态。
> 2026-11-21 起 Trainova（`apps/trainova` 及 6 个 lib、3 个前端）已从仓库删除，
> 儿保实现亦已下线；现行架构与测试口径见 [`../architecture.md`](../architecture.md) 与
> [`../aceso-test-database.md`](../aceso-test-database.md)。

- **任务**：共享任务板 `task-3`（054 开发基线验证与目标域侦察）
- **角色**：开发（实现角色）
- **日期**：2026-10-06
- **写入范围**：仅本文件（`docs/notes/054-dev-baseline.md`）
- **本阶段约束**：不改任何生产代码 / 测试代码 / 前端代码；不启动 podman、测试数据库或任何开发服务（8422/4324/5432 等）；本任务独占构建车道。
- **环境**：JDK 25.0.4.1（Red Hat），Gradle 9.5.1（wrapper，发行版缓存在 `service-vertx-kotlin/.gradle-cache/`），Node/pnpm（`ui-astro` 工作区）。

---

## 1. 三条基线命令的实际执行与结果

### 1.1 后端编译基线

```bash
cd service-vertx-kotlin && ./gradlew :libs:dining:compileKotlin :apps:aceso:compileKotlin --offline
```

**结果：通过（`--offline` 可用，无需去离线重试）。**

```
BUILD SUCCESSFUL in 10s
13 actionable tasks: 13 up-to-date
EXIT=0
```

要点：
- `:libs:dining:compileKotlin`、`:apps:aceso:compileKotlin` 以及依赖链（`libs:common` / `libs:database` / `libs:logging` / `libs:inventories` / `libs:pharmacy` / `libs:nursing` / `libs:healthcare`）全部 `UP-TO-DATE`，说明当前工作区（含未提交的 046–053 改动）已处于可编译状态。
- `--offline` 未触发任何依赖解析失败 → 本地 Gradle 缓存完整，后续实施任务可继续用离线编译作为快速门禁。
- 仅有 JVM 原生访问与 Gradle 单次守护进程的 WARNING，无 error。

### 1.2 前端构建基线

```bash
cd ui-astro && pnpm --filter @pitchfork/aceso build
```

**结果：通过。** 脚本实际为 `astro check && astro build`。

```
$ astro check && astro build
[check] Getting diagnostics for Astro files in /home/ovaphlow/pitchfork/ui-astro/apps/aceso...
Result (120 files):
- 0 errors
- 0 warnings
- 16 hints
[build] 32 page(s) built in 3.12s
[build] Complete!
EXIT=0
```

要点：
- `astro check`：120 个文件，**0 error / 0 warning / 16 hints**（hints 全为既有 e2e spec 与 `LoginForm.tsx` 的未使用变量、deprecated `FormEvent`，与本域无关）。
- 静态产物落到 `ui-astro/apps/aceso/dist/`，含 `/dashboard/dining/index.html`；`DiningPage` chunk 42.95 kB。
- 前端构建门禁对 054 可直接复用（0 error 是硬门槛）。

### 1.3 现有测试基线

```bash
cd service-vertx-kotlin && ./gradlew :libs:dining:test --offline
```

**结果：通过，但需注意「热工作区下该任务不真正执行测试」——必须强制重跑才能取到计数。**

三次调用的真实差异（如实记录，供后续验收命令设计参考）：

| # | 命令 | Gradle 行为 | 是否真正执行测试 |
|---|---|---|---|
| 1 | `./gradlew :libs:dining:test --offline` | `BUILD SUCCESSFUL in 8s`，`:libs:dining:test UP-TO-DATE` | 否（up-to-date） |
| 2 | `./gradlew :libs:dining:cleanTest :libs:dining:test --offline` | `BUILD SUCCESSFUL in 9s`，`:libs:dining:test FROM-CACHE` | 否（构建缓存命中） |
| 3 | `./gradlew :libs:dining:test --offline --no-build-cache --rerun-tasks` | `BUILD SUCCESSFUL in 29s`，`9 actionable tasks: 9 executed` | **是** |

以第 3 次执行的 JUnit XML（`libs/dining/build/test-results/test/*.xml`）为准：

| 测试类 | tests | skipped | failures | errors | 性质 |
|---|---:|---:|---:|---:|---|
| `DiningRoutesConflictTest` | 6 | 0 | 0 | 0 | DB-free 路由护栏（mockk Pool） |
| `DiningRoutesIntegrationTest` | 6 | 6 | 0 | 0 | 真库路由（默认跳过） |
| `DiningServiceIntegrationTest` | 17 | 17 | 0 | 0 | 真库服务（默认跳过） |
| `DiningUnitTest` | 8 | 0 | 0 | 0 | 纯函数/枚举 |
| `RosterRegenerateGenerationTest` | 4 | 0 | 0 | 0 | DB-free |
| **合计** | **41** | **23** | **0** | **0** | 实际执行 18 条全通过 |

- 跳过原因：`DiningServiceIntegrationTest.kt:65-66` 用 `Assumptions.assumeTrue(password.isNotBlank(), "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行")` 做类级门控；默认验证（无库）永远跳过 23 条。**真库覆盖必须由测试角色经 `scripts/aceso-test.sh`（55432 / `aceso_test`）另行取得，不得把 18 条 DB-free 通过当作域验收。**
- 第 3 次执行出现 `w: Failed to compile with Kotlin daemon / Using fallback strategy: Compile without Kotlin daemon` —— 非致命（Gradle 自动降级为进程内编译），构建仍 SUCCESSFUL；后续若在 CI 复现需留意。
- **未执行的操作**：未启动 podman/容器、未连接 5432 或任何业务库、未启停任何开发服务。

---

## 2. `libs/dining` 现状

### 2.1 文件与规模

```
libs/dining/src/main/kotlin/com/ovaphlow/crate/dining/
  DietProfileService.kt      284 行
  DishService.kt             172 行
  WeeklyMenuService.kt       413 行
  RosterService.kt           536 行
  MealExecutionService.kt    186 行
  DiningStatisticsService.kt 207 行
  DiningRoutes.kt            328 行
  DiningConstants.kt          55 行
  Exceptions.kt                4 行（DiningNotFoundException / DiningConflictException）
libs/dining/src/main/resources/db/migration/V600__create_dining_tables.sql  (130 行)
libs/dining/build.gradle.kts → api(:libs:database, :libs:common, vertx.web, jooq)
```

### 2.2 各服务职责

| 服务 | 职责 | 关键实现事实 |
|---|---|---|
| `DietProfileService` | 长者饮食档案 CRUD + 启停（FR-1）：餐食类型/忌口过敏/份量偏好 | 建档校验活动 `ELDERLY_CARE` 入住（联查 `healthcare.encounters`/`patients`）；`create`/`updateStatus` 已做 23505 → `DiningConflictException`（`DietProfileService.kt:115`、`:243`，051 已收敛为 `libs/database` 的 `isUniqueViolation(error, setOf("uq_dining_diet_profiles_active_patient"))`；`WeeklyMenuService.kt:89`、`:202` 同源） |
| `DishService` | 菜品库 CRUD + 启停（FR-2）：分类/适用餐次/饮食标签 | `meal_times`/`diet_tags` 为 JSONB 字符串数组白名单校验（`DiningConstants`） |
| `WeeklyMenuService` | 周菜谱编排（FR-3）：建档、按周/状态列表、`by-date`、整表替换明细、整周复制 | `dish_name` 为创建时快照（`V600:65-66`）；复制到已有启用菜谱的周返回 409；`validateDishes` 校验菜品存在与启用 |
| `RosterService` | 配餐名单（FR-4）：按「日期 + 餐次」生成、手工增删调整 | 配餐口径 = **启用档案 ∩ `encounter.status='ACTIVE'`**（`RosterService.kt:227-252`）；生成/刷新时把 `dining_diet_profiles.allergies` 快照进 `dining_roster_items.allergies`（`:284-314`、`:397-430`）；`adjust_type` 外出/请假排除应就餐口径 |
| `MealExecutionService` | 就餐执行登记（FR-5）：按名单条目幂等 upsert + 列表 | `roster_item_id` 唯一，重复登记为更新（`MealExecutionService.kt:67-104`）；`recorded_by` 取认证主体；列表联查 `rosters`/`roster_items` 带出日期、餐次、过敏快照 |
| `DiningStatisticsService` | 就餐统计（FR-6）：区间 summary + by_status / by_meal / by_date | 就餐率由 `DiningConstants.diningRate` 计算（两位小数，分母 0 → null） |
| `DiningConstants` | 业务枚举（中文值）与纯函数 | `MEAL_TYPES` / `PORTION_PREFERENCES` / `DISH_CATEGORIES` / `MEAL_TIMES` / `DIET_TAGS` / `MEAL_STATUSES` / `ADJUST_TYPES` / `ENABLE_STATUSES`；`diningRate`、`weekStartOf` |
| `DiningRoutes` | 路由装配 + 统一错误映射 | 服务在 `create()` 内构造（`:29-34`）；`IllegalArgumentException→400`、`DiningNotFoundException→404`、`DiningConflictException→409`、其余 `→500 {"error":"internal error"}` |

### 2.3 路由清单（`DiningRoutes.kt`，挂载前缀 `/crate-api/dining/v1`）

| 方法 | 相对路径 | 行 | 门禁（`DeclaredPermissions.kt`） |
|---|---|---|---|
| GET | `/health` | 38 | 白名单 |
| POST | `/diet-profiles` | 50 | `dining:manage` |
| GET | `/diet-profiles` | 55 | 读开放 |
| GET | `/diet-profiles/:id` | 66 | 读开放 |
| PUT | `/diet-profiles/:id` | 69 | `dining:manage` |
| PATCH | `/diet-profiles/:id/status` | 72 | `dining:manage` |
| DELETE | `/diet-profiles/:id` | 75 | `dining:manage` |
| POST | `/dishes` | 87 | `dining:manage` |
| GET | `/dishes` / `/dishes/:id` | 92 / 103 | 读开放 |
| PUT | `/dishes/:id` | 106 | `dining:manage` |
| PATCH | `/dishes/:id/status` | 109 | `dining:manage` |
| POST | `/weekly-menus` | 121 | `dining:manage` |
| GET | `/weekly-menus`、`/weekly-menus/by-date`、`/weekly-menus/:id` | 126 / 137 / 140 | 读开放 |
| PUT / PATCH / POST | `/weekly-menus/:id`、`/:id/status`、`/:id/items`、`/:id/copy` | 143 / 146 / 149 / 152 | `dining:manage` |
| POST | `/rosters/generate` | 167 | `dining:manage` |
| GET | `/rosters`、`/rosters/:id` | 179 / 189 | 读开放 |
| POST / DELETE | `/rosters/:id/items`、`/rosters/:id/items/:itemId` | 192 / 197 | `dining:manage` |
| POST | `/executions` | 212 | `dining:manage` |
| GET | `/executions` | 224 | 读开放 |
| GET | `/statistics/meals` | 240 | 读开放 |

注意：`/weekly-menus/by-date` 为静态段，必须在 `/weekly-menus/:id` 之前注册（`DiningRoutes.kt:136` 注释）——新增静态子路径时沿用该顺序约束。

### 2.4 V600 表结构（6 张表，schema `dining`）

| 表 | 关键列 | 约束/索引 |
|---|---|---|
| `dining_diet_profiles` | `id`, `patient_id→healthcare.patients`, `encounter_id→healthcare.encounters`, `meal_type`, `allergies JSONB DEFAULT '[]'`（**字符串数组**）, `portion_preference`, `remark`, `status`, `metadata`, `created_at`, `updated_at` | 部分唯一 `uq_dining_diet_profiles_active_patient (patient_id) WHERE status='启用'` |
| `dining_dishes` | `id`, `name`, `category`, `meal_times JSONB`, `diet_tags JSONB`, `status`, `remark`, `metadata`, 时间戳 | `category`/`status` 索引；**无营养列、无过敏原列** |
| `dining_weekly_menus` | `id`, `week_start DATE`, `name`, `status`, `remark`, `metadata`, 时间戳 | 部分唯一 `uq_dining_weekly_menus_active_week (week_start) WHERE status='启用'` |
| `dining_weekly_menu_items` | `id`, `menu_id→weekly_menus ON DELETE CASCADE`, `day_of_week 1-7`, `meal_time`, `dish_id→dishes`, `dish_name`（快照）, `sort_order`, `metadata` | 唯一 `(menu_id, day_of_week, meal_time, dish_id)` |
| `dining_rosters` | `id`, `menu_date DATE`, `meal_time`, `generated_by`, `generated_at`, `remark`, `metadata`, 时间戳 | 唯一 `(menu_date, meal_time)` |
| `dining_roster_items` | `id`, `roster_id→rosters CASCADE`, `patient_id→patients`, `encounter_id`, `patient_name`, `meal_type`, `allergies JSONB`（快照，字符串数组）, `source 自动/手工`, `adjust_type`, `remark`, `sort_order`, `metadata`, 时间戳 | 唯一 `(roster_id, patient_id)` |
| `dining_meal_executions` | `id`, `roster_item_id UNIQUE→roster_items CASCADE`, `status`, `remark`, `recorded_by`, `recorded_at`, `updated_at`, `metadata` | `roster_item_id` 唯一（幂等登记） |

### 2.5 持久化风格（对新增代码的硬约束）

- 现有 dining 服务**全部是 legacy jOOQ 构建器**，但统一经 `client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))` 执行（各服务文件末尾的 `execute` 私有函数，如 `DietProfileService.kt:282`、`MealExecutionService.kt:184`、`WeeklyMenuService.kt:411`、`RosterService.kt:534`）。
- 按 `service-vertx-kotlin/AGENTS.md`：**新增/变更持久化默认原生参数化 SQL（`$1..$n` + `Tuple.of`），不新增 `org.jooq` 引用**。参照已落地的 052/053 写法：`libs/healthcare/.../ChildHealthCheckupService.kt:52-58`（`private const val DIAGNOSE_SQL = "UPDATE ... SET x = \$1 ..."`）。
- 跨 schema 名必须显式限定（`healthcare.patients`、`dining.dining_rosters` 等）；JSONB 以 `JsonObject`/`JsonArray` 绑定，`Row.getJsonObject/getJsonArray` 读回。

---

## 3. 缺口侦察：「摄入记录 / 营养分析 / 过敏原 / 特殊膳食申请 / 周报」是否已存在

结论：**五项目前全部不存在（表、字段、路由、前端均无）**。证据如下。

| 检索 | 命令 | 结果 |
|---|---|---|
| 摄入记录 | `grep -rn "intake\|Intake" libs/dining/src --include=*.kt` | **0 命中**（仅 `DiningConstants.kt:7`、`DiningRoutes.kt:13` 等注释里的「膳食营养」） |
| 营养分析 | `grep -rn "营养\|nutrition" libs/dining/src ui-astro/packages/shared/src/aceso.ts` | 仅注释；**无 nutrition 类型/函数/表/路由** |
| 营养字段 | `grep -rn "calorie\|protein\|热量\|蛋白\|营养素" libs/**/*.sql libs/**/*.kt` | 仅 `V600:40` 的 `diet_tags` 含「高蛋白」标签与 `DiningConstants.DIET_TAGS`；**无任何能量/蛋白/脂肪/碳水列** |
| 过敏原表 | `grep -rn "patient_allergies\|PatientAllergy" service-vertx-kotlin ui-astro` | **0 命中**（053 D1 的 V535 规范化表尚未实现，见 §4） |
| 特殊膳食申请 | `grep -rn "special.?diet\|特殊膳食" ...` | **0 命中** |
| 营养周报 | `grep -rn "周报\|weeklyReport\|weekly-report" service-vertx-kotlin/libs service-vertx-kotlin/apps ui-astro/apps/aceso ui-astro/packages/shared/src` | **0 命中** |
| 契约覆盖面 | `ui-astro/packages/shared/src/aceso.ts:4236-4662` | dining 段只覆盖 5 块：饮食档案 / 菜品库 / 周菜谱 / 配餐名单 / 就餐执行 + 就餐统计 |

即：`libs/dining` 当前只实现「**配餐 → 就餐执行 → 就餐率统计**」这条线，**没有任何摄入量、营养值、过敏原主数据、特殊膳食审批、周报**能力。

---

## 4. 过敏数据现状与消费方（避免与 052/053 重复建设）

### 4.1 两处既有 `allergies`，形状不同

| 位置 | 形状 | 证据 |
|---|---|---|
| `healthcare.patients.allergies` JSONB | 注释声明 `[{"allergen":"青霉素","reaction":"皮疹"}]`（**对象数组**） | `V500__create_healthcare_tables.sql:27`、`:37`（`COMMENT ON COLUMN patients.allergies IS '过敏史 JSON 数组'`） |
| `dining.dining_diet_profiles.allergies` JSONB | 默认 `'[]'`，代码按**字符串数组**读写 | `V600:20`；`DietProfileService.kt` `cAllergies = DSL.field("allergies", JSONB::class.java)`，UI `DiningPage.tsx:216` 用 `split(/[,，]/)` 生成字符串数组 |
| `dining.dining_roster_items.allergies` JSONB | 生成时从饮食档案**快照**的字符串数组 | `V600:106`；`RosterService.kt:294`、`:308`、`:430` |

### 4.2 `patients.allergies` 存在形状不一致（D2 的前置风险）

| 事实 | 证据 |
|---|---|
| 契约声明为字符串数组 | `aceso.ts:182` `allergies: string[] \| null`（`Patient`）；`aceso.ts:208` `PatientInput.allergies?: string[] \| null` |
| 写入不做规范化 | `HealthcareService.kt:1562` / `:1578`：`jsonArray(body, "allergies")` 原样写 JSONB |
| 前端按字符串数组提交 | `EldersPage.tsx:177-180` 逗号切分为 `string[]`；`:192` 提交 |
| 前端按对象数组读取 | `AdmissionsPage.tsx:166`：`patient.allergies.map((item) => (item as { allergen?: string }).allergen ?? "-")` |
| 另一个契约声明为对象数组 | `aceso.ts:1106` `ElderlyDischargeHandoverPatient.allergies: Record<string, unknown>[] \| null` |
| 展示端按字符串数组 join | `EldersPage.tsx:214`：`patient.allergies?.join("，")` → 若库中是对象数组会渲染成 `[object Object]` |

→ **同一个列在不同写入路径下可能存字符串数组或对象数组**，消费方假设不一致。营养师的「过敏原标记」若直接复用该列，必须先裁定形状或走 053 D1 的规范化表。

### 4.3 052/053 的既有/计划边界

- 052（儿保护士链）**明确不改 `libs/dining`**：`docs/plans/052...md:8`。
- 053（儿保医生链）计划 `D1 — 药物过敏史规范化`：新增 `patient_allergies` 规范化表，迁移 **V535**，回填 `patients.allergies` JSONB，权限 `clinical:write`；`D2` 为纯逻辑 `DrugAllergyChecker`；见 `docs/plans/053...md:52`、`:113-115`、`:192-202`、`:227-228`。
- 工作区现状：`libs/healthcare/src/main/resources/db/migration/` 最大为 **V533**（A2/B1/B2 已落地），**V534/V535 未出现**；`grep patient_allergies` 0 命中 → **053 D1/D2/D3 尚未实现**。
- 051（QA 第 8 轮）已修复膳食建档唯一冲突的 409 语义并补了 DB-free 护栏（`DiningRoutesConflictTest`，6 条通过），dining 的 23505 判定已收敛到 `libs/database` 的 `isUniqueViolation`（`UniqueViolation.kt`，未提交）。

→ **054 的过敏原能力不得自建第二张过敏主数据表**：dining 侧只做「饮食档案/配餐口径的过敏原标记与回避」，主数据应消费 053 D1 的 `patient_allergies`（或其回填前的 `patients.allergies`），并以任务卡裁定形状。

---

## 5. UI 现状

### 5.1 膳食页面

| 项 | 证据 |
|---|---|
| 页面壳 | `ui-astro/apps/aceso/src/pages/dashboard/dining.astro`（`DashboardLayout title="膳食营养"` + `<DiningPage client:only="react" />`） |
| 主组件 | `components/DiningPage.tsx`（1599 行），6 个 Tab：`profiles` `:126`、`dishes` `:465`、`menus` `:716`、`rosters` `:1015`、`executions` `:1285`、`statistics` `:1455`；Tab 注册与渲染在 `:88-110` |
| 页面文案写死「长者」 | `DiningPage.tsx:85`「长者餐食管理：饮食档案 → 菜品菜谱 → 配餐 → 就餐执行 → 统计」；`:84` 标题「膳食营养」 |
| 菜单入口 | `Sidebar.tsx:89`：`{ label: "膳食营养", path: "/dashboard/dining", icon: "🍱", domains: ["养老"] }` —— **仅养老域可见，儿保域不可见** |
| 域词表 | `ui-astro/apps/aceso/src/lib/domain.ts:14` `export type Domain = "医疗" \| "养老" \| "儿保"`；`PAGE_TITLE_DOMAIN_LABELS` 目前**没有** `/dashboard/dining` 条目 |
| 域文案门禁测试 | `ui-astro/apps/aceso/tests/domain-labels.test.ts:66` 注释明确「**养老专属页面（膳食营养、康复活动、慢病档案等）不在本清单内**」→ 若把膳食页开放给儿保域，必须把 `DiningPage.tsx` 加进 `NON_ELDERLY_VISIBLE_COMPONENTS`，并把写死的「长者」改走 `DOMAIN_ENTITY`（居民/长者/儿童），否则该测试与 UX 口径都会分叉 |

### 5.2 shared 契约

- `ui-astro/packages/shared/src/aceso.ts` dining 段为 **`:4236-4662`**，之后 `:4664` 起是 Healthcare 押金段。
- 现有类型/函数：`DietProfile(+Input/List)`、`Dish(+Input/List)`、`WeeklyMenuItem/WeeklyMenu(+Input/List)`、`MealExecution(+List)`、`RosterItem/Roster/RosterList/RosterGenerateResult`、`MealStatistics`；函数 `listDietProfiles` `:4425` … `getMealStatistics` `:4652`。
- 约定：列表 `{records, meta:{total}}`；单条直接返回对象；错误 `{error}`；时间 `OffsetDateTime`；扩展 JSONB 命名 `metadata`；枚举中文值。
- 前端测试入口：`ui-astro/package.json:15` `"test:unit:aceso": "node --test apps/aceso/tests/*.test.ts"`。

### 5.3 权限与接线

| 项 | 证据 |
|---|---|
| 权限码常量 | `apps/aceso/.../DeclaredPermissions.kt:289` `internal const val DINING_MANAGE_PERMISSION = "dining:manage"` |
| 目录描述 | `DeclaredPermissions.kt:357` `PermissionCatalogEntry(DINING_MANAGE_PERMISSION, "膳食管理（饮食档案、菜品、周菜单、配餐名单、用餐执行）", "aceso")` |
| 已声明写路由 | `DeclaredPermissions.kt:207-222`（16 条，全部 `dining:manage`，`CENTRAL`） |
| 集中式门禁接线 | `apps/aceso/.../Main.kt:225-231`：按 `DECLARED_PERMISSIONS` 中 `wiring == CENTRAL` 的项在模块子路由**之前**注册 `requireAnyPermission` |
| 模块挂载 | `Main.kt:284-290`：`apiRouter.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, idpSessionAuthHandler(...)))` |
| **门禁测试（关键）** | `apps/aceso/src/test/.../DeclaredPermissionsTest.kt:282-296`：`assertEquals(declaredUnder("/dining/v1"), writeRoutes(DiningRoutes.create(vertx, pool, null), "/dining/v1"))` —— **新增任何一条写路由却忘记登记 `DECLARED_PERMISSIONS`，该测试立即失败**（读路由不上门禁） |
| 角色映射已存在 | `docs/plans/046...md:293`：`dining.staff → dining:manage → dietitian01` —— 营养师角色已有可用权限码，**054 无需新增岗位码** |

---

## 6. 首批实施任务（营养师 A 链）切入点建议

> 本节的表名/路由/迁移号是**开发侧可执行建议**；最终契约以 `docs/plans/054.aceso-child-health-role-workflows.md`（task-1）与 `docs/notes/054-architecture-decisions.md`（task-2）冻结为准。未冻结前不得据此改生产代码。

### 6.1 迁移号

- dining 段当前最大 **V600**（`libs/dining/.../migration/` 仅 `V600__create_dining_tables.sql`），V601–V699 全空 → 首批建议 **V601 / V602 / V603** 各任务独占一号，互不冲突。
- 与 healthcare（当前最大 V533，053 计划占 V534/V535）、nursing（V408）、pharmacy（V303）、inventories（V204）**无号段交叉**。

### 6.2 建议新建的表

**D1 膳食摄入记录与营养分析（V601）**

- `dining_intake_records`：`id`（ULID）/ `roster_item_id`（可空，FK `dining_roster_items`）/ `patient_id`（FK `healthcare.patients`）/ `encounter_id` / `intake_date DATE` / `meal_time`（中文枚举）/ `items JSONB`（`[{dish_id,dish_name,portion,unit,energy_kcal,protein_g,fat_g,carb_g}]`，`portion` 复用「标准/大半份/小半份」）/ `energy_kcal NUMERIC` / `protein_g NUMERIC` / `fat_g NUMERIC` / `carb_g NUMERIC`（服务端按 items 汇总落库，固定口径）/ `appetite VARCHAR`（新增中文白名单，如「正常/欠佳/拒食」）/ `source VARCHAR`（配餐/手工）/ `recorded_by`（认证主体，客户端不得提交）/ `remark` / `metadata` / `created_at` / `updated_at`。
- 同迁移内给 `dining_dishes` **追加 `nutrition JSONB`**（每份能量/三大营养素）与 `allergens JSONB`（过敏原标记），否则营养分析无数据源——当前 `dining_dishes` 无任何营养/过敏原列（§3）。
- 迁移必须 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，不改既有列、不重跑 codegen。

**D2 过敏原标记与特殊膳食申请（V602）**

- `dining_special_diet_requests`：`id` / `patient_id` / `encounter_id` / `request_type`（中文白名单，如「特殊膳食/忌口替代/过敏回避」）/ `allergens JSONB` / `meal_type` / `diet_tags JSONB` / `reason TEXT` / `status`（中文状态机，建议「待审批 → 已批准/已驳回 → 已停用」）/ `effective_from DATE` / `effective_to DATE` / `reviewed_by` / `reviewed_at` / `operator` / `remark` / `metadata` / 时间戳。
- **不新建过敏主数据表**：过敏原来源复用 `dining_diet_profiles.allergies`（dining 口径）与 053 D1 的 `patient_allergies`（V535，尚未实现）；形状不一致问题见 §4.2，须在任务卡裁定。

**D3 营养周报（V603）**

- `dining_nutrition_weekly_reports`：`id` / `week_start DATE` / `scope`（机构/个人）/ `patient_id`（机构级为空）/ `summary JSONB`（就餐率、摄入均值、达标率）/ `items JSONB`（逐日/逐餐明细快照）/ `generated_by` / `generated_at` / `status` / `metadata` / 时间戳。
- 建议**快照落库**（而非纯实时聚合），保证周报可追溯与可复现；生成入口一次写入，查询读表。

### 6.3 建议新增的路由（`/crate-api/dining/v1`）

| 任务 | 路由 | 写门禁 |
|---|---|---|
| D1 | `POST /intake-records`、`GET /intake-records`、`GET /intake-records/:id`、`PATCH /intake-records/:id`、`DELETE /intake-records/:id`（软删或物理删需裁定） | `dining:manage`（写）；GET 读开放 |
| D1 | `GET /nutrition-analysis?patient_id=&date_from=&date_to=`（区间聚合：摄入均值/达标率/按日按餐） | 读开放 |
| D1 | `PUT /dishes/:id`（既有）承载 `nutrition`/`allergens` 字段；若字段较多可加 `PATCH /dishes/:id/nutrition` | `dining:manage` |
| D2 | `POST /special-diet-requests`、`GET /special-diet-requests`、`GET /special-diet-requests/:id`、`PATCH /special-diet-requests/:id/status`（审批/驳回/停用） | `dining:manage`（写）；GET 读开放 |
| D3 | `POST /nutrition-weekly-reports/generate`、`GET /nutrition-weekly-reports?week_start=&patient_id=`、`GET /nutrition-weekly-reports/:id` | `dining:manage`（写）；GET 读开放 |

- 静态段（如 `/nutrition-analysis`、`/nutrition-weekly-reports/generate`）必须注册在参数段之前（同 `by-date` 的既有约束，`DiningRoutes.kt:136`）。
- **每条新增写路由必须同步登记 `DeclaredPermissions.kt`（`CENTRAL` + `dining:manage`）**，否则 `DeclaredPermissionsTest` 的 `/dining/v1` 全量比对失败（§5.3）。

### 6.4 最小改动文件清单（建议，按写入者串行）

| # | 文件 | 动作 | 独占说明 |
|---|---|---|---|
| 1 | `libs/dining/src/main/resources/db/migration/V601__add_dining_intake_and_nutrition.sql` | 新建（`dining_intake_records` + `dining_dishes.nutrition/allergens`） | D1 |
| 2 | `libs/dining/src/main/resources/db/migration/V602__add_dining_special_diet_requests.sql` | 新建 | D2 |
| 3 | `libs/dining/src/main/resources/db/migration/V603__add_dining_nutrition_weekly_reports.sql` | 新建 | D3 |
| 4 | `libs/dining/src/main/kotlin/.../IntakeRecordService.kt` | 新建（原生参数化 SQL） | D1 |
| 5 | `libs/dining/src/main/kotlin/.../NutritionAnalysisService.kt` | 新建（或并入 4） | D1 |
| 6 | `libs/dining/src/main/kotlin/.../SpecialDietRequestService.kt` | 新建 | D2 |
| 7 | `libs/dining/src/main/kotlin/.../NutritionWeeklyReportService.kt` | 新建 | D3 |
| 8 | `libs/dining/src/main/kotlin/.../DiningConstants.kt` | 追加枚举（`APPETITE_STATUSES`、`REQUEST_TYPES`、`REQUEST_STATUSES`、`REPORT_SCOPES` 等），追加点 `:39` 之后 | **共享热点，串行** |
| 9 | `libs/dining/src/main/kotlin/.../DiningRoutes.kt` | 追加三组路由 + `registerAuth`，追加点 `:248` 之后、`return router`（`:250`）之前；服务在 `create()`（`:29-34`）内构造 | **共享热点，串行** |
| 10 | `libs/dining/src/main/kotlin/.../DishService.kt` | 承载 `nutrition`/`allergens` 字段读写（或新增 `DishNutritionService`，二选一） | D1/D2 边界，需指定唯一写入者 |
| 11 | `apps/aceso/src/main/kotlin/.../DeclaredPermissions.kt` | 追加 `CENTRAL` 写路由声明（复用 `dining:manage`，`DINING_MANAGE_PERMISSION` 在 `:289`），追加点 `:222` 之后 | **共享热点，串行** |
| 12 | `ui-astro/packages/shared/src/aceso.ts` | 追加 dining 类型与函数，插入点 **`:4662` 之后 / `:4664` Healthcare 段之前** | **共享热点，串行** |
| 13 | `ui-astro/apps/aceso/src/components/DiningPage.tsx` | 追加 Tab（`TABS` 与 `:88-110` 渲染），或新增 `NutritionIntakePage.tsx` 等独立页 | D1/D2/D3 会争用，需分派唯一写入者或改用独立页 |
| 14 | `ui-astro/apps/aceso/src/components/Sidebar.tsx` | `:89` 膳食营养条目的 `domains` 是否增 `儿保`（以及是否新增营养师专属入口） | **共享热点，串行** |
| 15 | `ui-astro/apps/aceso/src/pages/dashboard/*.astro` | 若走独立页，新建壳页面 | 各任务互不重叠 |
| 16 | `libs/dining/src/test/kotlin/.../*`、`apps/aceso/src/test/kotlin/.../DeclaredPermissionsTest.kt` | DB-free 单测/路由护栏（mockk Pool 范式见 `DiningRoutesConflictTest`） | 测试文件各自新增，避免同文件并写 |

### 6.5 共享热点文件的追加位置（供架构师串行化）

| 热点文件 | 追加位置 | 约束 |
|---|---|---|
| `ui-astro/packages/shared/src/aceso.ts` | `:4662` 后、`:4664` 前（dining 段尾部） | 只增不改既有函数签名（052 §5 同口径） |
| `ui-astro/apps/aceso/src/components/Sidebar.tsx` | `:89` 膳食营养条目；新增入口放「健康服务」组 `:82-91` | 任何条目 `domains` 变更都要同步 `tests/domain-labels.test.ts` |
| `apps/aceso/src/main/kotlin/.../DeclaredPermissions.kt` | 写路由声明 `:222` 之后；常量区 `:289` 附近（若复用则不新增常量）；目录描述 `:357` | 声明 = 接线，门禁测试强校验 |
| `libs/dining/src/main/kotlin/.../DiningRoutes.kt` | 新路由段 `:248` 后、`return router` 前 | 静态段先于 `:id` 段 |
| `libs/dining/src/main/kotlin/.../DiningConstants.kt` | `:39` 之后 | 中文枚举值，不引入英文 code |
| `ui-astro/apps/aceso/src/components/DiningPage.tsx` | `TABS` 常量 + `:105-110` 渲染块 | 若三任务并行，建议各自独立页面规避同文件争用 |

---

## 7. 阻塞、风险与待裁定（开发侧视角）

| # | 项 | 说明 | 影响 |
|---|---|---|---|
| R1 | `patients.allergies` 形状不一致 | 字符串数组（`aceso.ts:182`、`EldersPage.tsx:177`）与对象数组（`V500:27`、`AdmissionsPage.tsx:166`、`aceso.ts:1106`）并存，写入不规范化（`HealthcareService.kt:1562`） | **D2 过敏原标记的契约必须先裁定形状**，否则会放大既有脏数据 |
| R2 | 053 D1（`patient_allergies` V535）未实现 | 该表是过敏规范化主数据的归属；dining 若自建过敏表将重复建设 | D2 需明确「消费 053 表」还是「先复用 `patients.allergies`」；**建议等 053 D1 或明确复用口径** |
| R3 | 新增写路由漏登记即门禁测试红 | `DeclaredPermissionsTest.kt:282-296` 对 `/dining/v1` 做全量比对 | 实施时「路由 + 声明」必须同一提交 |
| R4 | dining 为 legacy jOOQ 模块 | 新服务不得新增 `org.jooq` 引用，须原生 SQL；`DiningRoutes.create()` 只注入 `pool` + `authHandler` | 新服务构造签名应保持 `(pool)`，便于 `DiningRoutes` 装配与门禁测试（`DiningRoutes.create(vertx, pool, null)`）复用 |
| R5 | 膳食页开放儿保域会触发域文案门禁 | `domain-labels.test.ts:66` 现把膳食页列为养老专属；`DiningPage.tsx:85` 写死「长者」 | 若 054 要让营养师在儿保域用，需同步改测试清单 + 文案走 `DOMAIN_ENTITY` |
| R6 | 真库覆盖默认缺失 | 41 条 dining 测试中 23 条默认 skip（`DiningServiceIntegrationTest.kt:65-66`） | 营养链的落库/聚合验收必须由测试角色经 `scripts/aceso-test.sh`（55432 / `aceso_test`）取得，不能以 DB-free 通过代替 |
| R7 | 菜品无营养数据源 | `dining_dishes` 无能量/营养素列 | D1 必须先补菜品营养字段（或允许 items 直接带营养值），否则「营养分析」无输入 |
| R8 | 构建缓存会吞掉测试执行 | 热工作区下 `:libs:dining:test` 可能 `UP-TO-DATE`/`FROM-CACHE` | 验收命令建议固定为 `./gradlew :libs:dining:test --offline --no-build-cache --rerun-tasks`（或 `cleanTest test`） |

---

## 8. 收尾：`git status --short` 摘要

- **总计 107 项**（收尾快照；13:18 首次读取时为 104 项，增量来自同组其它成员的 `docs/notes/054-*.md`，非本任务）：`?? 64`（未跟踪，含 046–053 的计划与新增源码/测试/迁移）、` M 43`（已修改，含 `libs/dining` 3 个服务、`apps/aceso` 权限与 Main、`ui-astro` 多处组件与 `packages/shared/src/aceso.ts`）。
- 与 dining 直接相关的既有未提交改动：` M libs/dining/.../DietProfileService.kt`、` M .../RosterService.kt`、` M .../WeeklyMenuService.kt`、`?? libs/dining/src/test/.../DiningRoutesConflictTest.kt`、`?? .../RosterRegenerateGenerationTest.kt`、`?? libs/database/.../UniqueViolation.kt`（051 收口）。
- 本次任务**未产生任何新增/修改文件**（仅本文件），**未执行** `git reset/checkout/clean/stash`，**未触碰** Trainova、`packages/ui`、Nexus/IDP。
- 未跟踪清单（节选，按类别）：`docs/plans/046–053*.md`、`libs/healthcare` 的 V526–V533 迁移与 `Child*Service.kt`/测试、`libs/nursing` 新增测试、`ui-astro/apps/aceso` 的 `Child*Page.tsx` / `lib/*.ts` / `child-*.astro` 页面与前端测试。
- 本次任务**未启动任何容器/服务**（无 podman、无 5432/55432、无 8422/4324 操作），无残留进程与端口占用。

---

## 9. 给后续实施任务的交接要点（一句话版）

dining 域只有「配餐—执行—就餐率」一条线；营养师的摄入记录/营养分析/过敏原标记/特殊膳食申请/营养周报**五项目前为零**，可从 **V601–V603** 三段独占迁移起步；写路由一律复用 **`dining:manage`** 并在 `DeclaredPermissions.kt` 同步登记；过敏原不要自建主数据表（等 053 D1 V535 或明确复用 `patients.allergies` 并先裁定形状）；新增持久化走原生参数化 SQL，不引入 `org.jooq`；`aceso.ts` 追加点在 `:4662` 后、`Sidebar.tsx` 在 `:89`、`DiningRoutes.kt` 在 `:248` 后、`DiningConstants.kt` 在 `:39` 后。
