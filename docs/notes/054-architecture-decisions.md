# 054 架构决策备忘 — Aceso 儿保模式 角色 3–8

> ⚠️ **部分条款已被取代（2026-11-21）**：本文件是 2026-10-06 的 054 架构决策存档，作为历史记录保留。
> 已不再成立的内容：
> - 「不使用全局 `outOfOrder`」——`DatabaseConfig.migrate` 现已配置
>   `baselineOnMigrate` + `ignoreMigrationPatterns(*:missing,*:ignored)` + `outOfOrder(true)`，
>   因为各模块号段共享一个 schema history 且模块 classpath 只见自己那段；
> - 儿保（052/053/054）相关决策对象已全部下线（见 056）；`apps/trainova` 与
>   `libs/{knowledge,skills,trainings,exams,onsite,analytics}` 已于 2026-11-21 删除，
>   文中的「Trainova legacy 禁止依赖」条款不再有对象。
> - 现行口径见 [`../../AGENTS.md`](../../AGENTS.md)、[`../architecture.md`](../architecture.md)。

- 作者：架构师（architect）
- 创建时间：2026-10-06
- 状态：**架构约束已冻结（§1–§7）**；**§8 计划评审已完成**（6 项阻塞 BL2–BL7 已按 L7–L17 闭合）；**§9 实现评审（A/F1/B/D）已完成**（M1/M2/R-1/C-1/C-2 闭环，见 §9.9）；**§10 增量评审（C/E/F）已完成**（M3–M6 提出）；**§11 最终评审结论已出具** —— **054 六链（A/B/C/D/E/F）达到计划 §8 验收口径（含已声明受控例外），M1–M6 与 R-1/C-1/C-2 全部闭环，无阻塞交付的残余项**；唯一交付放行条件 = 调度者 §8.8 全量默认验证结果并入 §13（见 §11.3 / §11.6）
- 基线：`main @ f0efe32` + 未提交工作区（046–053）
- 上游：根 `AGENTS.md`、`docs/architecture.md`、`service-vertx-kotlin/AGENTS.md`、`ui-astro/AGENTS.md`、046/047/048/049/050/052/053 计划
- 写入范围：仅本文件。**本备忘不改任何生产代码、不改任何计划文件。**

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 模块归属 | 六角色功能**全部落 Aceso 侧既有 lib**（`dining`/`nursing`/`pharmacy`/`healthcare`）+ `apps/aceso` + `ui-astro`；**不新建 lib**；**禁止**用 `libs/trainings`（Trainova legacy）承载任何功能（计划 §3 Da 已采纳） |
| 康复师归属 | **`libs/nursing`**（Lead 裁定 L3 已冻结，与本备忘 §1.2 一致）；复用 `nursing_service_periods`/`nursing_plans`/`care_plan_revisions`，**不动 `nursing_tasks`** |
| 迁移号 | dining **V601–V602**、nursing **V409–V414**、pharmacy **V304**、healthcare **V536–V540**；**V534/V535 保留 053**（Lead 裁定 L1，✅ 与本备忘 §2.3 建议一致） |
| 权限码 | **新增 0 个**（Lead 裁定 L4）；六链全部复用既有码。⚠️ C 链复用 `nursing:document` 使 `trainer01` 权限粒度偏粗 → 已接受残余风险（§8.3 N16） |
| 契约 | `/crate-api/<module>/v1/<resource>`、`{records,meta:{total}}`、ULID、`OffsetDateTime`、`metadata` JSONB、中文枚举（§4）；计划枚举白名单未冻结（§8.3 N6）、`ChildRehabPlan.patient_id` 需 join 派生（§8.1 BL6） |
| 持久化 | 新代码一律原生参数化 SQL（`$1..$n` + `Tuple.of`），不新增 `org.jooq` 引用、不重跑 codegen（§6） |
| 热点串行 | `aceso.ts` 追加式单作者；`Sidebar.tsx` 单作者；`DeclaredPermissions.kt` 单作者；`Main.kt` 尽量不改；各域 `*Routes.kt` 同文件单作者（§5、计划 L5） |
| 车道 | A（dining）/ D（pharmacy）可与 B、C（nursing）并行；B 与 C **必须串行**（共用 `NursingRoutes.kt`）；E/F2/F3（healthcare）共用 `HealthcareRoutes.kt` 亦须串行（§7） |
| 评审 | **有条件通过**：骨架/模块归属/号段/权限/契约/持久化/真库安全达标；**6 项阻塞**（BL2 门禁测试、BL3 平台管理员、BL4 放量协议、BL5 A5 迁移、BL6 patient_id、BL7 B2 执行链）见 §8.1、§8bis |

---

## 1. 模块归属矩阵

### 1.0 硬边界（先读，来自仓库既有规则，不可协商）

1. **产品只挂载自己的领域库**。`apps:aceso` 的依赖是 `healthcare / inventories / pharmacy / nursing / dining / logging / database / common`（`service-vertx-kotlin/apps/aceso/build.gradle.kts:6-15`）。**不得为复用表或端点新增跨产品 lib 依赖**。
2. **`libs/trainings` 属 Trainova 侧 legacy jOOQ**。其包名是 `com.ovaphlow.crate.training`（**单数**，见 `libs/trainings/src/main/kotlin/com/ovaphlow/crate/training/TrainingRoutes.kt:1`），`service-vertx-kotlin/AGENTS.md` 明确把它与 `knowledge/skills/exams/onsite/analytics`、`apps/trainova` 同列为「不新增 jOOQ 用法」的 legacy 区，且 Aceso 未依赖它。→ **Aceso 的康复/训练功能一律不得落 `libs/trainings`**。
3. **`libs/healthcare` 的 incubating 标签已过时**。`docs/architecture.md:21` 仍写「Incubating / 不能被 app 挂载」，但 `apps:aceso` 早已 `implementation(project(":libs:healthcare"))` 并在 `Main.kt:250` 挂载 `/healthcare/v1/*`（052/053 的 `Child*Service`、V526–V533 均在其中）。054 **不得**以 incubating 为由回避 healthcare；同时应把该文档口径不一致作为独立后续项记录（不在 054 范围）。
4. **新增 lib 需按「最大号段 +100」**并说明理由。054 各角色的功能量均不足以支撑新 lib（见各角色「为何不新建 lib」）。

### 1.1 营养师 `dietitian01`（优先级：中高）

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 膳食摄入记录与营养分析 | `libs/dining` | **V601** | dining 已拥有 `dining_dishes`（菜品/标签）、`dining_meal_executions`（就餐执行）、`dining_rosters`（配餐名单）；摄入量是膳食域的自然延伸。`dining_meal_executions` 的语义是**是否就餐的状态标记**（`V600:120`），不能塞入「吃了多少」，故新建表而非改旧表 | 新增 `V601__add_intake_records.sql`（`dining_intake_records` + 明细）、`IntakeRecordService.kt`、`NutritionAnalysis.kt`（纯函数，无 DB）、`DiningRoutes.kt` 追加段、`aceso.ts` 追加段、新页面 `NutritionIntakePage.tsx` + `pages/dashboard/child-nutrition.astro` |
| 过敏原标记与特殊膳食申请 | `libs/dining` | **V602** | `dining_diet_profiles.allergies JSONB`（`V600:21`）**已存在**——过敏原标记**复用该列**，不新建；「特殊膳食申请」是新增审批流程（申请→审批→生效），新建表 | `V602__add_special_diet_requests.sql`（`dining_special_diet_requests`）、`SpecialDietRequestService.kt`、`DiningRoutes.kt` 追加段、`aceso.ts` 追加段、申请页/审批区 |
| 营养周报 | `libs/dining` | **无迁移**（只读聚合） | 周报是既有表的聚合投影，冻结快照无产品诉求；落表会引入无谓的一致性负担 | `GET /dining/v1/nutrition-reports/weekly` 只读端点 + `DiningStatisticsService` 追加方法（或新 `NutritionReportService.kt`）+ 报表页 |

**为何不新建 lib**：三项功能全部读写 `dining.*` 表，且 `dining:manage` 已在 046 接线；新建 lib 只会增加一个空壳与跨 schema 连接成本。

### 1.2 康复师 `rehab01`（优先级：中）——**关键纠偏**

> **结论：康复师全部功能落 `libs/nursing`，不得落 `libs/trainings`。**
>
> 理由（三条独立成立）：
> 1. `libs/trainings` 是 **Trainova 侧 legacy jOOQ**（包名 `com.ovaphlow.crate.training` 单数），Aceso 未依赖，且产品边界禁止跨产品 lib 依赖；
> 2. `libs/nursing` **已有完整的康复承载面**：`rehab:execute` 权限码（`DeclaredPermissions.kt:12`）、`nursing_tasks.task_type='REHABILITATION'`（`V400:96` + `V405` CHECK）、`TaskExecutionService`/`TaskExecutionRoutes` 的打卡链路（`EXECUTION_PERMISSION_CODES_BY_TASK_TYPE`）、`nursing_plans`/`nursing_plan_items`（`V400:57,73`）、`nursing_care_plan_revisions`（`V406`）、`nursing_incidents`/`nursing_shift_handovers`（`V408`）；
> 3. 康复「训练计划周期」与护理「服务周期/计划」在数据形状上同构（period → plan → plan_item → task → execution），另起炉灶会产生两套排期引擎。
>
> ✅ **评审后修订（2026-10-06，见 §8.5）**：Lead 裁定 L3 已采纳「落 `libs/nursing`」，与本条结论一致。实测补充两条修正：① `nursing_tasks` **无 `patient_id`**（`V400:90-112`），**不能**复用其承载儿童个体化训练计划；② `nursing_service_periods.patient_id` **可以**承载儿童康复周期，且新增 `service_type` + `encounter_id IS NULL` **满足** `V404` 的 `chk_elderly_care_encounter_link` 第二分支（实测）。`nursing_plans.period_id` NOT NULL（`V400:60`）可复用为训练计划，但**无 `patient_id`** → 响应需 join 派生（§8.1 BL6）。**不得落 `libs/trainings`** —— 该结论不变且已实测（无迁移目录、仅 `apps/trainova` 挂载）。

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 训练计划周期管理与进度记录 | `libs/nursing` | **V409** | 周期 = `nursing_service_periods`；计划 = `nursing_plans`/`nursing_plan_items`（已含 `start_date`/`end_date`/`frequency_code`）；进度 = `nursing_task_executions`（REHABILITATION）。V409 只做**最小增量**：为 `nursing_plans` 加康复分类列（或约定走 `metadata.plan_category`），不改既有列语义 | `V409__add_rehab_plan_category.sql`、`RehabPlanService.kt`、独立 `RehabRoutes.kt`（**新增子路由文件**，把对热点 `NursingRoutes.kt` 的改动压到一行挂载）、`aceso.ts` 追加段、康复页 |
| 家庭训练指导手册分发 | `libs/nursing` | **V410** | 分发是照护域事实（谁、何时、向哪个家庭、发了哪版手册、是否签收）；**手册正文文件走 Nexus files**（`/crate-api/shared/v1/*`），不在 nursing 存二进制，也不新建 files 能力 | `V410__add_rehab_handout_distributions.sql`、`RehabHandoutService.kt`、`RehabRoutes.kt` 追加段、`aceso.ts` 追加段 |
| 里程碑提醒 | `libs/nursing` | **V411** | 提醒**复用既有排期引擎**：里程碑落表 → 生成 `nursing_tasks`（task_type=REHABILITATION）→ 由既有 `executions/generate` 出执行记录。**不新建提醒引擎、不新建消息通道**（站内提醒走 Nexus messages，与 052 P4 同口径） | `V411__add_rehab_milestones.sql`、`RehabMilestoneService.kt`、`RehabRoutes.kt` 追加段、`aceso.ts` 追加段、里程碑页 |

**权限**：计划/里程碑定义 → `nursing:document`；康复活动打卡 → `rehab:execute`（既有 INJECTED 门禁已承载，**不改** `TaskExecutionRoutes` 的注入式选码逻辑）。

### 1.3 保育员/班主任 `trainer01`（优先级：中）

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 日常活动日志与观测 | `libs/nursing` | **V412** | `LIVING_CARE` 已是 `nursing_tasks.task_type` 合法值（`V405`）；活动日志是照护事实，与护理/康复同域，便于按儿童聚合 | `V412__add_daily_activity_logs.sql`、`DailyActivityService.kt`、独立 `CareActivityRoutes.kt`、`aceso.ts` 追加段、活动日志页 |
| 班级日程与活动安排 | `libs/nursing` | **V413** | 语义是**班级/小组日程**，不是个人护理计划——`nursing_plans.period_id` 为 `NOT NULL`（`V400:58`），塞进去会扭曲语义（班级不属于某个服务周期）。新建独立表 + 明细 | `V413__add_class_schedules.sql`、`ClassScheduleService.kt`、`CareActivityRoutes.kt` 追加段、`aceso.ts` 追加段、班级日程页 |
| 环境安全巡查 | `libs/nursing` | **V414** | 与 `nursing_incidents`（`V408`）同为安全事实，同域便于「巡查发现问题 → 不良事件」联动 | `V414__add_safety_inspections.sql`、`SafetyInspectionService.kt`、`CareActivityRoutes.kt` 追加段、`aceso.ts` 追加段、巡查页 |

**为何不新建 lib**：三项都读写 `nursing.*`，且复用 `nursing:document`；新建 lib 的收益为零，却要多一条 Flyway 号段与一个 composition 依赖。

> ✅ **评审后修订（2026-10-06）**：054 计划（552 行版）已按 Lead 裁定 **落 `libs/nursing`（V413–V414）+ 复用 `nursing:document`**，**不新建 lib、不新增权限码** —— 与本条「不新建 lib」的架构偏好一致。保留的残余风险：`trainer01` 复用 `nursing:document` 会获得护理评估/计划/不良事件/交接班写权（权限粒度偏粗），已按 L4 接受并登记为 §8.3 N16；回退方案为未来单列 `childcare:manage`。

### 1.4 药师 `pharmacist01`（优先级：低·儿保场景）

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 儿科药物库与用量计算 | `libs/pharmacy`（剂量元数据） + `libs/inventories`（药品目录本体，**只读不改**） | **V304** | 药品目录本体是 `inventories.materials`（`category='药品'`），pharmacy 已通过 `DrugCatalogPort` 读取；儿科剂量元数据（mg/kg 上下限、年龄下限、频次上限）是**药房作业属性**，落 pharmacy 侧表并以跨 schema **弱关联** `material_id`（不加 FK，与 `nursing_care_plan_revisions.encounter_id` 同风格，`V406:6`），避免污染通用物资表 | `V304__add_pediatric_drug_refs.sql`、`PediatricDrugService.kt`、`PediatricDoseCalculator.kt`（纯函数，无 DB）、pharmacy 侧 `PediatricRoutes.kt`、`aceso.ts` 追加段、药师页 |
| 药物过敏交叉检查 | **复用 053 已规划的引擎**，pharmacy 侧只加调用点 | **V305（仅当需药师侧台账）**，否则无迁移 | ⚠️ **与 053 D1/D2/D3 重叠**：053 已规划 `PatientAllergyService`（V535 过敏史规范化）+ 过敏/禁忌检查引擎（纯逻辑）+ 开嘱前检查接线。054 **不得重复实现**同一引擎（会造成两套判定口径，安全边界反而更弱）。过敏史读 `healthcare.patients.allergies JSONB`（`V500`），跨 schema 只读 SQL | 复用 053 的引擎与数据形状；pharmacy 侧只在「发药前」增加一次调用 + 中文提醒；不新增 `libs:pharmacy → libs:healthcare` 编译期依赖（跨 schema 只读查询） |

**权限**：儿科药物库维护 → `pharmacy:dispense`；用量计算（纯函数、无写库）→ 无门禁（按读路由处理，仍需登录）；发药前过敏提醒 → `pharmacy:dispense`。

### 1.5 财务/收银 `cashier01`（优先级：低）

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 儿保费用结算与发票 | `libs/healthcare` | **V536**（见 §2.3） | 计费链**整体已在 healthcare**：`deposit_records`/`fee_items`/`bills`/`bill_items`/`payments`（`V513–V518`）、红冲（`V523`）、结算（`V517`）。发票是计费链的收尾状态，落同域 | 优先**加列**（`bills.invoice_no`/`invoice_status`）；仅当确需发票明细才建 `healthcare_invoices`。`HealthcareRoutes.kt` 是 1523 行热点 → 优先新增独立 `BillingInvoiceRoutes.kt` 并在 `HealthcareRoutes` 追加一行挂载 |
| 套餐付费状态可视化 | `libs/healthcare` | **V537** | 套餐是「账单分组 + 状态投影」；若套餐可由 `bills` 分组表达则**纯查询、不落表**（推荐），确需套餐主体时才建 `child_care_packages` | `V537__add_child_care_packages.sql`（可选）、`ChildPackageService.kt`、`BillingInvoiceRoutes.kt` 追加段、`aceso.ts` 追加段、收银页 |

**口径红线（020 §0 前提，仍然有效）**：费用功能只做「**标记数据状态与业务流程收束**」，不做真实收付款/资金对账/支付通道。发票同理——只做**票号与状态**，不接税控、不做金额核算。

### 1.6 管理员 `admin`（基础设施）

| 优先功能 | 归属 | 迁移 | 理由 | 最小改动边界 |
|---|---|---|---|---|
| 角色权限精细化 | `apps/aceso`（`DeclaredPermissions.kt` + `RolesPage.tsx`）+ Nexus 角色目录 | **无** | 权限权威在 Nexus 角色目录，Aceso 侧只声明「码接到了哪些路由」（`DeclaredPermissions.kt:37-44`）。054 的「精细化」= 目录/声明与角色页体验，不改数据模型 | `DeclaredPermissions.kt`（单作者追加）、`RolesPage.tsx`、可能的新只读端点。**若要做部门级/数据范围级授权，属 Nexus 侧改动，超出 054 Aceso 范围，须单独立项** |
| 基础数据维护 | `apps/aceso` + Nexus | **无** | `base_data:manage` 已接线 Nexus 的 departments 写路由（`DeclaredPermissions.kt:328-330`）；物资/床位/费用项分别走 `inventory:write`/`bed:manage`/`fee_item:manage` | 现有页面（`MaterialsPage`/`BedsPage`/`FeeItemsPage`/`DepartmentsPage`）体验与文案；不改表 |
| 系统日志与操作审计 | `apps/aceso`（**只读跨域聚合**） | **无**（推荐） | 各域表已自带审计列（`created_by`/`operator`/`nurse_checked_by`+`nurse_checked_at`（`V507`）、`bill_reversal` 审计约束（`V523`）、`encounter_terminal_audit`（`V524`））。`libs/logging` 只有日志门面 `Log.kt`，**无表** | `GET /crate-api/access/v1/audit/events` 只读聚合 + 审计页。**不新建统一审计表**（见 §8 待裁定项） |

---

## 2. 迁移号段分配与冲突检查

### 2.1 号段隔离（根 `AGENTS.md` 硬约束）

`V200+ inventories`、`V300+ pharmacy`、`V400+ nursing`、`V500+ healthcare`、`V600–V699 Aceso dining`；`apps/trainova` 的 `V5–V7` 是**执行过的历史**，不可移动/复制/新增。**不使用全局 `outOfOrder`。**

Flyway 位置是 `classpath:db/migration`（`libs/database/.../DatabaseConfig.kt:58`），**每个运行时 lib 各自贡献一个 `db/migration`**，全部落到同一个 `aceso` 库——因此号段隔离是硬要求。

### 2.2 现有最大版本（实测）

| lib | 现有最大版本 | 实测证据 | 054 独占号 | 备注 |
|---|---|---|---|---|
| dining | **V600** | 仅 `V600__create_dining_tables.sql`（单迁移，7 张表） | **V601, V602**（V603 可选） | 营养师 A 链 |
| nursing | **V408** | `V400`–`V408` 连续 | **V409–V414** | 康复师 B1–B3（V409–V411）、保育员 C1–C3（V412–V414） |
| pharmacy | **V303** | `V300`–`V303` 连续 | **V304**（V305 可选） | 药师 D 链 |
| healthcare | **V533**（已落盘）；**V534/V535 由 053 计划预留** | `V500`–`V533` 连续；V534/V535 **文件尚不存在** | **V536+** | 收银 E 链；管理员审计若落表另计 |
| inventories | **V204** | `V200/V201/V203/V204`（**V202 空缺**） | **无**（054 不改 inventories） | **不得复用 V202**（空缺是历史，不是可分配号） |
| apps/trainova | V5–V7 | 基线 | **不可动** | — |

### 2.3 冲突检查

| 检查项 | 结论 |
|---|---|
| 052（V526–V530） | 已落盘，054 无交集 ✅ |
| 053（V531–V535） | V531/V532/V533 **已落盘**；**V534（B3 疾病防控指导）、V535（D1 过敏史规范化）仍被 053 §1.1 任务表预留但未落盘** ⚠️ |
| dining / nursing / pharmacy / inventories | 054 号段与既有最大号无重叠 ✅ |
| 全局 `outOfOrder` | 不使用 ✅ |
| 同号多任务 | 053 曾出现「V532 由 A3 与 B1 共用」——实际落盘为**单一作者合并**（`V532__add_child_checkup_diagnosis.sql` 同时含体检诊断列与 `developmental_screenings.checkup_id`）。054 沿用该先例：**一号一任务为默认，确需共用必须先合并迁移作者** |

> ### ⚠️ 阻塞项 1（必须先裁定）：healthcare 起始号
>
> 调度者指示「临床相关新表可继续 healthcare，**新号从 V534 起**」，但 **053 §1.1 明确预留 V534/V535**（B3、D1）。两者冲突。
>
> **建议裁定**：054 healthcare 从 **V536** 起，把 V534/V535 留给 053；
> **备选**：若确认 053 的 B3/D1 不再执行（裁剪或改号），由调度者一次性裁定并**同时**更新 053 与 054 两份计划，054 方可回收 V534/V535。
> 在裁定前，**任何 054 任务不得创建 V534/V535 文件**。
>
> ✅ **已解除（2026-10-06）**：054 计划 §0/§11.1 L1 已采纳本建议 —— healthcare 编排为 **V536–V540**（E V536–V538 / F V539–V540），V534/V535 保留 053（见 §8.0 迁移号判定：**通过**）。

### 2.4 顺延规则（可执行）

1. **号随派工单调递增**：同一 lib 内按派工顺序连续占用。
2. **取消不复用**：任务取消/跳过，其号**永久作废**，下一任务顺延，避免「已应用迁移被改写」。
3. **跨 lib 互不影响**：dining / nursing / pharmacy / healthcare 四条号段各自独立推进。
4. **一号一任务**：任务表必须写明独占号；共用号须先合并迁移作者（053 V532 先例）。
5. **写号即声明**：任务的迁移号在计划 §4 任务表与 §7 文件矩阵中双重登记，评审据此核对。

---

## 3. 权限码策略

### 3.1 逐项判定

| 角色·功能 | 建议 | 码 | 判定理由 |
|---|---|---|---|
| 营养师·摄入记录/营养分析/过敏原标记/特殊膳食申请/营养周报 | **复用** | `dining:manage` | 与 046 已接线的 16 条 dining 写路由同一作业主体；仅资源名不同不构成新增理由 |
| 康复师·训练计划周期/里程碑定义/手册分发台账 | **复用** | `nursing:document` | 047 已把「评估/计划/任务/服务周期/访视日程/护理记录/交接班/不良事件」统一归 `nursing:document`；康复计划属同一文书集合 |
| 康复师·康复活动打卡 | **复用** | `rehab:execute` | 045 已接线（INJECTED），`EXECUTION_PERMISSION_CODES_BY_TASK_TYPE` 已按 `REHABILITATION` 选码，**不改** |
| 保育员·活动日志/班级日程/安全巡查 | **复用** | `nursing:document` | 照护文书类事实，与康复计划同集合；**不新增 `care:manage`** |
| 药师·儿科药物库维护 | **复用** | `pharmacy:dispense` | 儿科剂量元数据在 pharmacy 侧表，维护动作属药房作业链（050 已把领用/调拨并入同码，先例一致） |
| 药师·用量计算（纯函数、无写库） | **不挂门禁** | — | 无副作用计算路由按读路由处理，仍需登录（与 046「读路由不上门禁」一致） |
| 药师·发药前过敏交叉检查 | **复用** | `pharmacy:dispense` | 挂在发药链上；引擎本体属 053（`clinical:write` 侧） |
| 收银·结算/发票/套餐状态 | **复用** | `billing:write` | 046 已接线 7 条 `billing:write` 路由，发票/套餐是同一计费链的延伸 |
| 管理员·角色权限精细化 | **复用** | `access:manage` | Nexus 侧已接线（`DeclaredPermissions.kt:323-327`） |
| 管理员·基础数据维护 | **复用** | `base_data:manage` | Nexus 侧已接线（`:328-330`） |
| 管理员·系统日志与操作审计 | **不挂门禁** | — | 只读聚合，按读路由处理 |

**预期结论：054 零新增权限码。**

### 3.2 若确需新增（判据与门禁要求）

**判据（同时满足才新增）**：① 目标角色集合与既有码**不同**，且 ② 复用会导致**越权**。仅「资源名不同」不构成理由——046 的教训是目录里堆积「没有任何接口在用」的死配置。

**新增时的四项强制动作**：
1. 在 `DeclaredPermissions.kt` 增加常量 + `PERMISSION_CATALOG` 条目（`code/description/enforcedBy="aceso"`）；
2. 所有受保护写路由加入 `DECLARED_PERMISSIONS`（`PermissionWiring.CENTRAL`；需读库选码才用 `INJECTED`）；
3. **门禁测试覆盖**：`DeclaredPermissionsTest` 的「真实 Router 枚举 = 声明」护栏（`:283`）必须把新模块的 Router 纳入 scope 枚举，否则新路由会「裸奔」而测试仍绿；
4. 在计划中写明与既有码的语义边界与不重叠证明。

**集中声明位置**：`service-vertx-kotlin/apps/aceso/src/main/kotlin/com/ovaphlow/crate/aceso/DeclaredPermissions.kt`（唯一权威，禁止在 lib 内散落权限码字面量）。

---

## 4. 契约形状

### 4.1 通用规则（冻结）

| 项 | 规则 |
|---|---|
| 路径 | `/crate-api/<module>/v1/<resource>`；`<module>` = lib 名（`dining`/`nursing`/`pharmacy`/`healthcare`/`inventories`）；集合用复数，单项用 `:id`，动作型用 `/:id/<verb>`（`status`/`approve`/`distribute`…） |
| 列表 | `{ "records": [...], "meta": { "total": N } }`；空列表必须返回 `records: []` + `total: 0` |
| 单条 | 直接返回对象；创建 `201`；删除 `204`；错误 `{ "error": "<中文消息>" }`（051 中文化口径） |
| ID | 26 位 Crockford Base32 **ULID**，服务端生成 |
| 时间 | `created_at`/`updated_at` 为 `OffsetDateTime`（ISO-8601 带偏移） |
| 扩展字段 | 一律命名 `metadata`，类型 JSONB |
| 枚举 | **中文值**（`启用/停用`、`待审批/已批准/已驳回`、`已完成/已取消`…）；不引入英文 code，不改写既有枚举 |
| 分页 | `limit`（默认 50，上限 100）/ `offset`（≥0） |
| 操作人 | 服务端从认证主体派生（042）；客户端提交 `operator`/`created_by`/`reviewed_by` → `400` |
| 认证 | JWT Bearer；前端只经 `@pitchfork/shared/aceso` 请求 |

### 4.2 建议新资源路径与关键字段

**dining（营养师）**
| 方法 | 路径 | 请求要点 | 响应要点 |
|---|---|---|---|
| POST | `/dining/v1/intake-records` | `patient_id`、`encounter_id`、`record_date`、`meal_time`、`items[]`（`dish_id`/`dish_name`/`portion`/`unit`）、`metadata` | 201 + 记录对象 |
| GET | `/dining/v1/intake-records` | `patient_id`/`date_from`/`date_to`/`limit`/`offset` | `{records,meta:{total}}` |
| GET | `/dining/v1/intake-records/:id` | — | 记录对象 |
| GET | `/dining/v1/nutrition-analysis` | `patient_id`、`date_from`、`date_to` | 聚合对象（能量/蛋白/脂肪/碳水 + 达标率） |
| POST | `/dining/v1/special-diet-requests` | `patient_id`、`request_type`（中文枚举）、`reason`、`allergens[]`、`effective_from` | 201 + 申请单（`status="待审批"`） |
| PATCH | `/dining/v1/special-diet-requests/:id/status` | `status`（`已批准`/`已驳回`）、`remark` | 更新后对象 |
| GET | `/dining/v1/nutrition-reports/weekly` | `patient_id` 或 `encounter_id`、`week_start` | 周报聚合对象 |

**nursing（康复师）**
| 方法 | 路径 | 请求要点 | 响应要点 |
|---|---|---|---|
| POST/GET | `/nursing/v1/rehab-plans` | `period_id`、`encounter_id`、`plan_name`、`goals`、`start_date`、`end_date` | 201 / `{records,meta}` |
| PATCH | `/nursing/v1/rehab-plans/:id/status` | `status`（`ACTIVE`/`COMPLETED`/`DISCONTINUED`，沿用既有英文状态枚举，**不改**） | 更新后对象 |
| POST | `/nursing/v1/rehab-plans/:id/milestones` | `title`、`target_date`、`criteria` | 201 + 里程碑 |
| PATCH | `/nursing/v1/rehab-milestones/:id` | `status`（`待达成`/`已达成`/`已跳过`）、`achieved_at` | 更新后对象 |
| POST | `/nursing/v1/rehab-handouts` | `title`、`version`、`file_ref`（Nexus files 引用） | 201 + 手册 |
| POST | `/nursing/v1/rehab-handouts/:id/distribute` | `patient_id`、`guardian_name`、`channel`（中文枚举）、`distributed_at` | 201 + 分发台账 |
| GET | `/nursing/v1/rehab-progress` | `patient_id` 或 `period_id` | 进度聚合（计划/里程碑/执行完成率） |

**nursing（保育员）**
| 方法 | 路径 | 请求要点 |
|---|---|---|
| POST/GET | `/nursing/v1/activity-logs` | `patient_id`、`log_date`、`activity_type`（中文）、`observation`、`metadata` |
| POST/GET | `/nursing/v1/class-schedules` | `class_name`、`week_start`、`items[]`（`weekday`/`start_time`/`end_time`/`activity`/`location`） |
| POST | `/nursing/v1/class-schedules/:id/items` | 明细替换/追加 |
| POST/GET | `/nursing/v1/safety-inspections` | `area`、`inspected_at`、`items[]`（`check_point`/`result`（`正常`/`异常`）/`remark`） |
| PATCH | `/nursing/v1/safety-inspections/:id/status` | `status`（`待整改`/`已整改`/`已关闭`） |

**pharmacy（药师）**
| 方法 | 路径 | 请求要点 |
|---|---|---|
| POST/GET | `/pharmacy/v1/pediatric-drug-refs` | `material_id`、`age_min_months`、`age_max_months`、`dose_per_kg_min`、`dose_per_kg_max`、`max_daily_dose`、`frequency_max`、`contraindications[]` |
| PUT | `/pharmacy/v1/pediatric-drug-refs/:id` | 同上（覆盖式） |
| POST | `/pharmacy/v1/pediatric-dose/calculate` | `material_id`、`age_months`、`weight_kg` → **纯计算、不落库、不挂门禁** |
| POST | `/pharmacy/v1/dispenses/:id/allergy-check` | 发药前过敏/禁忌检查（复用 053 引擎），返回 `{passed, warnings[]}` |

**healthcare（收银）**
| 方法 | 路径 | 请求要点 |
|---|---|---|
| POST | `/healthcare/v1/encounters/:id/invoices` | 由账单/结算派生；`invoice_no` 服务端生成 |
| GET | `/healthcare/v1/invoices` | `patient_id`/`status`/`date_from`/`date_to`/分页 |
| PATCH | `/healthcare/v1/invoices/:id/status` | `status`（`已开具`/`已作废`） |
| POST/GET | `/healthcare/v1/child-care-packages` | 套餐定义（名称/包含项/周期/价格标记载荷） |
| GET | `/healthcare/v1/child-care-packages/:id/payment-status` | 只读状态投影 |

**access（管理员）**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/access/v1/audit/events` | 跨域只读聚合（`entity`/`entity_id`/`action`/`operator`/`occurred_at`），分页同上；**不落表、不挂门禁** |

---

## 5. 共享热点文件串行化方案

### 5.1 热点清单与写权规则

| 热点文件 | 现状 | 规则 |
|---|---|---|
| `ui-astro/packages/shared/src/aceso.ts` | **5891 行**，按 `// =====` 分节 | **追加式 + 单作者**：每个任务只在自己域小节**末尾追加**，禁止重排、重格式化、改动他人小节。同一时间只允许一个任务持有写权（调度者发「写令牌」，完成即释放）。两任务必须同轮时，**由先完成者代写后者的契约段**（一次追加），后者的契约由评审核对 |
| `ui-astro/apps/aceso/src/components/Sidebar.tsx` | 168 行，`menuItems` 结构体（`:40-115`） | **单作者**：所有 054 菜单改动由**一名** UI 菜单作者一次性完成；其它任务只提交「菜单项片段（label/path/icon/domains/order）」。新项追加在对应 group 的 `children` **末尾**；新 group 追加在 `menuItems` 末尾 |
| `apps/aceso/.../DeclaredPermissions.kt` | 430 行，`DECLARED_PERMISSIONS`（`:77-254`）+ `PERMISSION_CATALOG`（`:340-371`） | **单作者追加**：新写路由在 `DECLARED_PERMISSIONS` 末尾追加；新增码同时补 `PERMISSION_CATALOG`。与 `Main.kt` **同批同作者**。禁止改动既有条目顺序与语义 |
| `apps/aceso/.../Main.kt` | 797 行，`buildApiRouter` | **尽量不改**：集中门禁由 `DECLARED_PERMISSIONS` 驱动（`:225-231`），新增受保护路由**不需要**改 Main.kt。仅当新增模块挂载点或新注入端口时才改；若必须改，与 `DeclaredPermissions.kt` 同批。**绝不调整既有注册顺序**（`BodyHandler` `:207` → 认证总闸 `:213` → 集中门禁 `:225` → 模块子路由 `:233+`），这是 P0「写操作全部挂起」的根因护栏 |
| `libs/dining/.../DiningRoutes.kt` | 328 行 | **同文件单作者**；营养师三任务同属一域 → 由**一个** dining 车道作者串行追加，或按 §5.2 拆独立子路由 |
| `libs/healthcare/.../HealthcareRoutes.kt` | **1523 行** | **同文件单作者**；收银链优先新增独立 `BillingInvoiceRoutes.kt`，在 `HealthcareRoutes` 只追加**一行挂载** |
| `libs/nursing/.../NursingRoutes.kt`、`TaskExecutionRoutes.kt` | 护理热点 | **同文件单作者**；康复师与保育员**各自新增独立子路由文件**（`RehabRoutes.kt`、`CareActivityRoutes.kt`），在 `NursingRoutes.create` 各追加一行挂载 → 把热点改动压缩为两次单行追加 |
| pharmacy `*Routes.kt` | 分文件（`DispenseRoutes`/`RequisitionRoutes`/`PurchaseOrderRoutes`/`ReturnRoutes`） | 药师链**新增** `PediatricRoutes.kt`，不改既有文件 |

### 5.2 热点改造的通用手法（把冲突降到「单行追加」）

1. **新域 = 新路由对象**：每个新功能链新建独立 `XxxRoutes.kt`，返回自己的 `Router`。
2. **热点文件只加挂载行**：在 owning lib 的 `*Routes.kt` 中追加一行 `router.route("/<prefix>/*").subRouter(XxxRoutes.create(vertx, pool, auth))`。
3. **契约只追加**：`aceso.ts` 按域小节追加。
4. **菜单单作者**：`Sidebar.tsx` 由一人批量落。
5. **权限单作者**：`DeclaredPermissions.kt` 由一人批量落，且与 `Main.kt` 同批。

### 5.3 谁先谁后（可执行顺序）

```
① 冻结号段（调度者，一次性）→ 解除 §2.3 阻塞项 1
② 批次 1（并行，文件不重叠）：A 链（dining V601–V602） ∥ D 链（pharmacy V304）
③ 批次 2（nursing 车道内串行）：B 链（康复 V409–V411） → C 链（保育 V412–V414）
   └─ 若先拆出 RehabRoutes.kt / CareActivityRoutes.kt，则 B 与 C 的 service/迁移可并行，
      仅「NursingRoutes.kt 单行挂载」串行
④ 批次 3（等 053 收尾）：E 链（healthcare V536+）
⑤ 每批次末：热点文件单作者统一落 `aceso.ts` / `Sidebar.tsx` / `DeclaredPermissions.kt`（+`Main.kt` 如需）
⑥ F 链（管理员）：无迁移，可与 ②③④ 并行，但 `DeclaredPermissions.kt` 写权必须串行
```

---

## 6. 持久化规则

### 6.1 强制规则（`service-vertx-kotlin/AGENTS.md`）

1. **新增/变更一律原生参数化 SQL**：`pool.preparedQuery(sql).execute(Tuple.of(...))`，PostgreSQL `$1..$n` 按位置绑定。
2. **JSONB** 绑 `io.vertx.core.json.JsonObject` / `JsonArray`，读回用 `Row.getJsonObject` / `Row.getJsonArray`。
3. **不新增 `org.jooq` 引用**、**不重跑 codegen**、不新增 `jooq-config.xml`、不提交 `build/tmp` 渲染配置。
   - 实测各 lib 仍有 legacy jOOQ：dining 6、nursing 12、pharmacy 5、healthcare 18、inventories 4 个文件含 `org.jooq`。规则是**新文件不得 import**；**编辑既有文件时不得扩散** jOOQ 用法。
4. **跨 schema 显式限定**：`dining.`、`nursing.`、`healthcare.`、`public.`（`inventories.materials`/`lots`/`stocks` 在 `public`）。
5. **表名/列名只能来自 owning lib 的迁移历史**，不得臆造；新表由本任务的迁移创建。
6. 禁止把用户输入或动态值拼进 SQL 文本；动态谓词与其绑定值成对追加。

### 6.2 054 表清单（新建 / 改动）

| lib | 迁移 | 新建表 | 改动表 |
|---|---|---|---|
| dining | V601 | `dining_intake_records`、`dining_intake_record_items`（或 `items` JSONB，二选一由开发按查询需要定） | — |
| dining | V602 | `dining_special_diet_requests` | — （过敏原**复用** `dining_diet_profiles.allergies`，不改列） |
| nursing | V409 | — | `nursing_plans` 加康复分类列（或约定 `metadata.plan_category`） |
| nursing | V410 | `nursing_rehab_handout_distributions`（+ `nursing_rehab_handouts` 手册主表） | — |
| nursing | V411 | `nursing_rehab_milestones` | — |
| nursing | V412 | `nursing_daily_activity_logs` | — |
| nursing | V413 | `nursing_class_schedules`、`nursing_class_schedule_items` | — |
| nursing | V414 | `nursing_safety_inspections`、`nursing_safety_inspection_items` | — |
| pharmacy | V304 | `pharmacy_pediatric_drug_refs` | — |
| pharmacy | V305（可选） | 药师侧过敏检查台账（仅当需要留痕） | — |
| healthcare | V536+ | `healthcare_invoices`（+items，**仅当确需明细**；否则只加 `bills` 列） | `bills`（`invoice_no`/`invoice_status`） |
| healthcare | V537（可选） | `child_care_packages`（+ 关联） | — |
| inventories | — | **不改** | **不改** |

> 表名以最终计划为准；**评审时逐条核对**「新建表是否落在 owning lib 的号段内」「是否误改他域表」「跨 schema 是否显式限定」。

---

## 7. 并行车道与依赖图

### 7.1 依赖图

```
F 链（管理员·基础设施，无迁移）
  └─ 可与任意链并行；唯一串行点是 DeclaredPermissions.kt 写权

A 链（营养师，dining V601–V602）── 独立车道（DiningRoutes.kt 单作者）
D 链（药师，pharmacy V304）────── 独立车道（新增 PediatricRoutes.kt）
  └─ D 链「发药前过敏交叉检查」依赖 053 D1/D2 落地（否则须并入 053 或先做 D1/D2）

B 链（康复师，nursing V409–V412）─┐
C 链（保育员，nursing V413–V414）─┴─ 同 lib、同 NursingRoutes.kt → 必须串行（B 合入后再开 C）

E 链（收银，healthcare V536+）──── 依赖 053 收尾（V534/V535 归属裁定）+ HealthcareRoutes.kt 单作者
```

### 7.2 文件重叠矩阵（可并行判定）

| 组合 | 是否重叠 | 判定 |
|---|---|---|
| A ∥ D | 否（dining vs pharmacy） | ✅ 可并行 |
| A ∥ B/C | 否（dining vs nursing） | ✅ 可并行 |
| B ∥ C | **是**（`NursingRoutes.kt`、nursing 号段） | ❌ 串行；先拆 `RehabRoutes.kt`/`CareActivityRoutes.kt` 后可并行 service/迁移，仅挂载行串行 |
| E ∥ 任意 | **是**（`HealthcareRoutes.kt` 1523 行热点） | ❌ 串行（且必须等 053） |
| 任意 ∥ `aceso.ts` | **是** | ❌ 串行（追加式单作者） |
| 任意 ∥ `Sidebar.tsx` | **是** | ❌ 串行（单作者批量落） |
| 任意 ∥ `DeclaredPermissions.kt` / `Main.kt` | **是** | ❌ 串行（同批同作者） |
| 前端页面 `*Page.tsx` / `pages/dashboard/*.astro` | 否（按角色新建） | ✅ 可并行 |

### 7.3 调度者必须做的三件事

1. **冻结号段**并解除 §2.3 阻塞项 1（healthcare 起始号）；
2. 为 `aceso.ts` / `Sidebar.tsx` / `DeclaredPermissions.kt` 发放**单一写令牌**，按批次末统一落；
3. 明确 B/C 的 nursing 车道顺序（或先批准拆子路由文件）。

---

## 8. 独立评审结论（对 `docs/plans/054.aceso-child-health-role-workflows.md`）

- 评审对象：`docs/plans/054.aceso-child-health-role-workflows.md`（**552 行 / 13 章 / 25 任务 / 14 迁移**，2026-10-06 13:35 版；含 Lead 冻结裁定 §11.1 L1–L5）
- 评审时间：2026-10-06；评审方式：**只读**计划 + 只读代码实测复核（不改计划文件、不改生产代码）
- 总体判定：**有条件通过（Conditional Pass）** — 骨架、模块归属、号段、权限策略、契约、持久化、真库安全口径达标；**6 项阻塞**须先补齐方可派工 A5、B、C、D2、E、F1、F2 链。
- 版本说明：本结论针对 552 行版重写；对 522 行版的评审已被取代（旧版的新 lib `childcare`、healthcare 承载康复、`childcare:manage` 新码三项**均已按 Lead 裁定 L1–L5 修正**）。
- ✅ **状态更新（2026-10-06 晚）**：计划已扩为 **651 行 / 26 任务 / 15 迁移**（§11.1 增至 **L1–L17**），本节 6 项阻塞**全部闭合**：BL2→L11、BL3→L12、BL4→L13、BL5→L14、BL6→L8、BL7→L7；另新增 **L17 = A4b**（儿童饮食档案免入住）。§8.2 的 N4 事实错误（`listSubjectPermissions`）与 §8.3 的 N6（枚举白名单）亦已分别修正与以 §5.8 冻结。本节保留为**初评记录**；实现层结论见 **§9**。

### 8.0 逐维度判定

| 维度 | 判定 | 说明 |
|---|---|---|
| 范围（六角色 15 项优先功能） | **通过** | 营养师 A1–A6、康复师 B1–B5、保育员 C1–C3、药师 D1–D4、收银 E1–E4、管理员 F1–F3 全覆盖；不改 Trainova/Nexus/IDP/`packages/ui`（§7 无越界文件）。F2 对 052/053 已交付 `ChildVaccinationService`/`DevelopmentalScale` 的读库优先改造已在计划 §0 显式标注为**唯一例外**，并有计划 §8.6 的回归断言（旧版 N11 已缓解） |
| 模块归属 | **通过** | 六链全落 Aceso 侧**既有**模块（dining/nursing/pharmacy/healthcare），**不新建 lib**；B/C 落 `libs/nursing`（L3）；`libs/trainings` 明令禁止（§3 Da 末）✅ 与架构备忘 §1.0 硬边界一致 |
| 迁移号（L1） | **通过** | healthcare **V536–V540**（E V536–V538 / F V539–V540），**V534/V535 保留 053** ✅ 与本备忘 §2.3 建议一致；dining V601–V602、nursing V409–V414、pharmacy V304、inventories 不改、Trainova V5–V7 不动；一号一任务；不用 `outOfOrder`；算术正确（迁移 2+6+1+5=14；任务 6+5+3+4+4+3=25） |
| 权限码（L4） | **通过（附风险）** | **新增 0 个**，六链全部复用：A=`dining:manage`、B=`rehab:execute`、C=`nursing:document`、D=`pharmacy:dispense`、E=`billing:write`、F2=`base_data:manage`、F3 读=`access:manage`。✅ 与 L4 一致。⚠️ C 链复用 `nursing:document` 使 `trainer01` 获得护理文书写权（评估/计划/不良事件/交接班），属**越权面扩大**——Lead 已裁定零新增，故登记为已接受残余风险（N16），不再作为阻塞 |
| 契约 | **需改** | 路径/列表形状/ULID/`OffsetDateTime`/`metadata`/操作人服务端派生符合冻结规则；**但** `ChildRehabPlan.patient_id` 在 `nursing_plans` 不存在（BL6）、枚举白名单未冻结（N6）、`POST /dispenses` 增补未标注（N8） |
| 文件范围矩阵 | **需改** | 每任务列可改文件 + 热点单作者规则可执行；**但** `DeclaredPermissionsTest.kt` 无写权（BL2）、A5 无 `.sql`（BL5）、集成测试类未进矩阵（N12） |
| 验收口径（§8 十条） | **需改** | 十条基本可判定、每任务有单条命令（§9）、R10 诚实标注 podman 阻塞；**但** §8.7 对门禁测试的覆盖描述**与代码事实不符**（BL2）、平台管理员被 F1 误伤（BL3）、「有码 2xx」缺放量前置（BL4） |
| 派工顺序（§6） | **通过** | 依赖关系正确：A2 与 A1 并行、A3 依赖 A1+A2、A5 依赖 A4 表、D3 blocked 于 053、F3 最后、F1 第 0 批先落机制；B 合入后再开 C 正确。措辞瑕疵见 N14 |
| 角色、职责与交接（§10） | **需改** | 四角色 + 调度者职责/范围/禁止事项齐备；**但** §10 开发行要求「新增写路由与 `DeclaredPermissions.kt` 声明同一提交」而 §7 未给开发该文件（N13，内部矛盾） |
| 持久化 | **通过** | 原生参数化 SQL、不新增 jOOQ、不重跑 codegen、跨 schema 显式限定；§8.9 有 `rg "org.jooq"` 零命中验收 |
| 真库与安全 | **通过** | `./scripts/aceso-test.sh`（55432 / `aceso_test`）+ 收摊证据 + 残差 0 + 禁连 5432 + 禁启停服务 |
| 现状事实准确性 | **基本通过** | 抽样复核 12 项属实（8.2）；1 处**实质性错误**（N4）、1 处行号漂移 |

### 8.1 阻塞项（须先解决；每条注明阻塞任务与最小改法）

| # | 阻塞项 | 阻塞任务 | 证据 | 最小改法 |
|---|---|---|---|---|
| **BL2** | **`DeclaredPermissionsTest` 会把「正确声明」判成失败，同时放过 healthcare 漏登记** | **B1–B4、C1–C2、D2**（并使 E1–E3/F2 的护栏失效） | 实测该测试的**全等**断言只枚举**具名子路由**：`nursingReal` = `ServicePeriodRoutes/AssessmentRoutes/PlanRoutes/TaskRoutes/VisitScheduleRoutes/TaskExecutionRoutes` 六个子路由（`:348-364`），**不含 `NursingRoutes` 聚合**；`pharmacyReal` = `DispenseRoutes/ReturnRoutes/PurchaseOrderRoutes/RequisitionRoutes`（`:311-344`），**不含 `PharmacyRoutes` 聚合**。而 §7 让 B1–B4/C1–C2 改 `NursingRoutes.kt`（新增段）、D2 改 `PharmacyRoutes.kt`（新增段）→ 新路由进 `declared*` 却不在 `*Real` → `assertEquals` **必然失败**。反之 `/healthcare/v1` 只做单向 `declared ⊆ real`（`:368-373`）→ E/F2 漏登记**不会失败**。§3 Di/§5.7/§8.7 声称「同一提交即不漂移」「逐段全量比对」**与代码不符** | ① §7 新增一行「集成（调度者）」或独立任务，**授予 `apps/aceso/src/test/kotlin/.../DeclaredPermissionsTest.kt` 写权限**；② 把 `NursingRoutes.create(...)`、`PharmacyRoutes.create(...)`（或其新增子路由）纳入枚举；③ 为 `/healthcare/v1/{child-service-*,child-package-*,invoices,base-vaccination-types,base-developmental-scales}` 增加 scope marker **全等**断言（照抄 `inHealthScope`(`:388`)/`inMasterDataScope` 写法）；④ §8.7 改为「全等断言通过」，删去「逐段全量比对」的笼统表述 |
| **BL3** | **F1 会让平台管理员失去全部菜单 → 破坏 046 双轨引导者** | **F1** | 后端已短路平台管理员：`SubjectPermissionGateTest.kt:105`「a platform admin bypasses the permission lookup」断言「平台管理员必须放行（双轨引导者）」且不查 Nexus；而平台管理员**没有任何权限码**。计划 §8.6 若按「无权限码账号看不到对应菜单入口」实现，前端将与后端语义相反 | F1 明确：`platformAdmin === true`（`lib/currentUser.ts:26` 已有该字段）时**跳过权限过滤、菜单全量可见**；计划 §8.6 增加断言「平台管理员登录 → 全部入口可见且能进角色页」，与 `SubjectPermissionGateTest:105` 对齐 |
| **BL4** | **缺「放量协议/上线前置」→ §8.7「有码 2xx」无法执行** | **全部六链验收** | 六角色都需在 Nexus 建角色并分配；046 §5 有该章节，054 全文无。`rehab.staff` 在 047 仍标「如未建」（`047:142`）；C 链的 `trainer01` 也需一个持有 `nursing:document` 的角色 | 新增小节（建议 §9.5）：`role_code → permission_code → subject` —— `dining.staff→dining:manage→dietitian01`、`rehab.staff→rehab:execute→rehab01`、`childcare.staff→nursing:document→trainer01`、`pharmacy.staff→pharmacy:dispense→pharmacist01`、`billing.staff→billing:write→cashier01`；写明「未分配 = 403 是预期，不是回归」+ 权限缓存 TTL ≤ 60s |
| **BL5** | **A5（营养周报）没有迁移作者，建不了表** | **A5** | §3 Dc 明列新增表 `dining_nutrition_weekly_reports`；但 §4 A5 迁移列为「—」，§7 A5 允许文件**不含任何 `.sql`**；§11 Q1 又说「V601–V602（摄入/菜品营养 + 特殊膳食/**周报快照**）」→ 周报表应由 V602 建，但 §4 A4 主题与 §7 A4 均未提该表 | 二选一：① 在 §4 A4 主题与 §7 A4 显式写明「`V602__*.sql` 同时创建 `dining_special_diet_requests` 与 `dining_nutrition_weekly_reports`」（A5 只消费）；② 给 A5 分配 **V603** 并同步 §11 Q1。推荐 ①（保持 Q1 的 V601–V602 口径） |
| **BL6** | **`ChildRehabPlan.patient_id` 在存储层不存在** | **B1**（连带 B2–B4 的 `patient_id` 查询） | §5.7 定义 `ChildRehabPlan { period_id; patient_id; … }`，但 `nursing_plans` 列仅 `id/period_id/encounter_id/plan_name/goals/status/created_by/start_date/end_date/metadata/created_at/updated_at`（`V400:58-70`）——**无 `patient_id`**；§5.2 的 `GET /child-rehab-plans?patient_id=` 亦需跨表 | 明确 `patient_id` 为**响应派生字段**（`JOIN nursing_service_periods ON period_id` 取 `patient_id`），并在 §5.7 加注释、§5.2 注明该筛选走 join；**不要**给 `nursing_plans` 加列（会动 052/053 之外共享表） |
| **BL7** | **B2 存在两条并行执行链，进度口径二义** | **B2**（连带 B1/B5 的进度展示） | §4 B2 写「打卡**复用 `nursing_task_executions` 口径**」，同时 §5.2 新增 `POST /child-rehab-sessions`、§7 B2 新建 `V410__*.sql`（会话表），§3 Db 又说「执行与打卡复用 `nursing_tasks`/`nursing_task_executions`」——同一「是否完成训练」事实有两个潜在来源 | 二选一并写入 §3 Db + §4 B2 + §8.2：① **复用派**：儿童计划生成 `nursing_tasks(REHABILITATION)`，进度由 `nursing_task_executions` 派生，`child-rehab-sessions` 退化为只读投影（则 V410 可能不需要）；② **新表派**：`nursing_child_rehab_sessions` 为唯一事实源，明确声明「不复用打卡链」，并在 §5.2 删去「复用 `nursing_task_executions` 口径」表述。推荐 ②（B1 已决定不动 `nursing_tasks`，①会让两条链在 `nursing_tasks` 上再次交叉） |

### 8.2 现状事实抽样复核（12 项，均属实）

| 计划引用 | 复核结果 |
|---|---|
| `V400:14-18` `service_type` CHECK 基础值 | ✅ 属实（`V404` 又扩为 `+ELDERLY_CARE`） |
| `V400:60` `nursing_plans.period_id NOT NULL` | ✅ 属实（第 60 行正是 `period_id`） |
| `V404:24-28` `chk_elderly_care_encounter_link` | ✅ 属实；新增 `service_type` 且 `encounter_id IS NULL` **满足第二分支**，B 链设计可行 |
| `TaskExecutionService.kt:186-199` 全院性康复参与条件 | ✅ 属实 |
| `V515:20-22` `bills.encounter_id NOT NULL` | ✅ 属实 |
| `V400:96-102` `REHABILITATION`/`LIVING_CARE` 合法 task_type | ✅ 属实 |
| `service-nexus-shared/src/lib.rs:71` `/settings` 无 `route_layer` | ✅ 属实 |
| `apps/trainova/Main.kt:9,78` `libs/trainings` 仅 trainova 挂载 | ✅ 属实 |
| `domain-labels.test.ts:59-66` `NON_ELDERLY_VISIBLE_COMPONENTS` 把膳食/康复页排除在外（R9） | ✅ 属实（注释明写「养老专属页面（膳食营养、康复活动…）不在本清单内」）→ A6/B5 必须同步该清单 |
| `DiningConstants.kt:26-27` 就餐状态 正常/部分/未就餐/拒食 | ✅ 属实 |
| `ChildVaccinationService.kt:56` / `DevelopmentalScale.kt:26,38` / `ChildGuidanceService.kt:35,62` 硬编码常量 | ✅ 属实 |
| `Sidebar.tsx:89,90,95-96` 膳食/康复/收费菜单仅养老域 | ✅ 属实 |
| `DeclaredPermissionsTest.kt:289-296` `/dining/v1` 全量比对范式 | ✅ 属实（但仅 dining/inventories 为真全等，见 BL2） |

**需更正**：`§2.5` 引用 `Main.kt:308-334` 为药品目录端口 → 实际在 `:315`（healthcare）与 `:336`（pharmacy）；`§2.7` 见 N4。

### 8.3 需改项（非阻塞，但派工前应落到计划）

| # | 问题 | 位置 | 最小修改建议 |
|---|---|---|---|
| **N4** | **§2.7 事实错误（552 行版仍存在）**：`listSubjectPermissions` 这个导出**不存在**；实际是 `getSubjectPermissions(subjectId)`（`aceso.ts:876`），且**已被 `UsersPage.tsx:270` 调用**，并非「零调用」 | §1.1 管理员行、§2.7 | 改为「**菜单/页面**未按权限显隐；`getSubjectPermissions` 已在用户详情使用」。规定 F1 **复用** `getSubjectPermissions` + `useCurrentUser()`（`lib/currentUser.ts`），**不得**新增重复客户端函数。补充：Nexus 端点 `subject_id` **必填**（`subject_roles/mod.rs:40,294`），查自己免 `access:manage`（`:296-307`）→ F1 可行 |
| **N6** | 契约枚举值未冻结：§5 多处 `status: string` / `pay_state: string` / `level: string` 未给取值；Di 说「新增枚举一律中文」，但 B 链复用 `nursing_plans.status` 的英文 CHECK（`ACTIVE/COMPLETED/DISCONTINUED`，`V400:64`） | §5.1–5.6 | 逐资源冻结白名单，例：`SpecialDietRequest.status ∈ {待审批,已批准,已驳回,已终止}`；`Invoice.status ∈ {已开具,已作废}`；`ChildcareSafetyInspection.status ∈ {待整改,已整改,已关闭}`；`ChildPackageSubscription.pay_state ∈ {未付,部分,已付,已用完}`；`ChildRehabPlan.status` 明确「沿用既有英文三值」或改为中文并说明迁移影响 |
| **N13** | §10 开发行要求「新增写路由与 `DeclaredPermissions.kt` 声明**同一提交**」，但 §7 把该文件只给「集成（调度者）」行，§10 开发的可改范围是「该任务 §7 行的文件集合」→ **开发无法履行** | §10 开发行、§7 | 改为「开发提交**声明片段**（method/path/code）随任务交付，由调度者与该任务路由**同批合入** `DeclaredPermissions.kt`」；保留单作者规则 |
| **N14** | §6 措辞自相矛盾：称「A/B-C/D/E-F2 四组文件集合互不相交」，但 B/C 共用 `NursingRoutes.kt`、E/F2/F3 共用 `HealthcareRoutes.kt`，与同段末「必须串行的热点文件」表述冲突 | §6 并行与互斥段 | 改为「四组**跨 lib** 文件集合互不相交；组内热点文件（`NursingRoutes.kt`、`HealthcareRoutes.kt`）仍须串行」 |
| **N15** | B 链新增 `service_type` 的语言与回归：§8.2 断言 `service_type=儿保康复`（中文），而既有 CHECK 全为英文枚举（`V400`/`V404`）；且 V409 需 `DROP CONSTRAINT` + `ADD CONSTRAINT` 重建 | §3 Db、§4 B1、§8.2 | 二选一：① 用英文 code（如 `CHILD_REHAB`）与既有列一致，§8.2 同步；② 坚持中文，则显式标注「本列新增值为中文，为 Di 的唯一例外」并说明理由。**无论哪种**，V409 必须保留 `ELDERLY_CARE` 分支，并加「既有养老周期读写不回归」断言 |
| **N16** | C 链复用 `nursing:document` 的越权面（已按 L4 接受，登记为残余风险）：`trainer01` 因此获得护理评估/计划/不良事件/交接班写权 | §3 Dd、§11 Q3 | 在 §12 增一条残余风险：「保育员复用 `nursing:document` 导致权限粒度偏粗；若后续出现越权事件，回退方案为新增 `childcare:manage` 并单列迁移（Q3 备选）」。本轮不改 |
| **N17** | F3 审计中间件注册位置不精确：§3 Dh 只说「认证总闸之后、模块子路由之前」，该区间还含 `DECLARED_PERMISSIONS` 集中门禁（`Main.kt:225-231`） | §3 Dh、§7 F3 | §7 F3 明确「在 `Main.kt:225` 集中门禁循环**之前**注册」（可记录 403 尝试）；并加护栏：**不得插入任何 `BodyHandler`**、不得改动既有注册顺序（P0 根因） |
| **N18** | `POST /pharmacy/v1/dispenses` 是 046 **冻结**路由，§5.4 追加 `allergy_warnings`/`acknowledged_warnings`，而 §5 开篇自称「冻结，车道不得改」 | §5 开篇、§5.4 | 标注「对 046 冻结路由的**向后兼容增补**」；D3 增加回归断言「既有消费方不传新字段仍 2xx」 |
| **N19** | A1 幂等口径未定：§8.1 只说「重复提交幂等」 | §4 A1、§8.1 | 明确「同儿童 + 同日期 + 同餐次唯一索引 → 重复提交 409」（与 V600 的 `uq_dining_diet_profiles_active_patient` 风格一致） |
| **N12** | 集成测试类未进文件矩阵：§8.10 提到 `ChildRoleWorkflowIntegrationTest`，§7 无对应行 | §7、§8.10 | §7 增「测试（集成）」行，或在 §9 标注由测试角色按 §10 范围创建 |

### 8.4 通过项明细（可作为开发与测试的执行依据）

1. **模块归属与号段编排正确**：六链全落 Aceso 既有模块、不新建 lib；B/C 落 `nursing`；healthcare V536–V540 且 V534/V535 留 053 —— **完全采纳本备忘 §2.3 与 Lead L1**。`libs/trainings` 禁令在 §3 Da 末显式写出。
2. **权限码零新增成立**：六链逐项映射到既有码，与 L4 一致；无新增码即无需改 `PERMISSION_CATALOG`，门禁工作面收敛（但**仍需**扩 `DeclaredPermissionsTest`，见 BL2）。
3. **药师不重复建设**（§3 De、Q5、R6）：`pharmacy_pediatric_dose_rules`（V304）+ 纯函数 `PediatricDoseCalculator`；D3 依赖 053 且保持 `DrugCatalogPort` fail-closed 语义不变。
4. **过敏原不自建主数据表**（L2、§3 Dc、Q7）：复用 `dining_diet_profiles.allergies`，与 053 `patient_allergies` 口径对齐留独立任务，避免两张主数据表与脏数据扩散（R4）。
5. **收银不改 `bills`**（§3 Df、Q4）：`bills.encounter_id NOT NULL` 已验证；独立新表 + 仅即时结算，避免养老账期/押金/结算/红冲全链回归。
6. **康复复用既有链路**（§3 Db）：复用 `nursing_service_periods`（`patient_id` 承载儿童、`encounter_id` NULL 满足 `V404` CHECK）、`nursing_plans/plan_items`、`care_plan_revisions`；**不动** `nursing_tasks` 表结构与 `TaskExecutionService`（1800 行）——符合最小范围。
7. **契约骨架合规**：路径前缀与 lib 名一致；列表/错误/分页/ID/时间/`metadata`/操作人派生符合冻结规则；`aceso.ts` 只增不改既有签名（追加点 `:4662` 之后）。
8. **持久化合规**：原生参数化 SQL、不新增 jOOQ、不重跑 codegen；§8.9 有零命中验收。
9. **热点文件单作者规则可执行**（L5、§7 首段 + 互斥规则 ①–④）：`aceso.ts`/`Sidebar.tsx`/`DeclaredPermissions.kt`/`Main.kt`/各 `*Routes.kt` 串行；F1 第 0 批先落权限机制，避免 `Sidebar.tsx` 反复改结构。
10. **真库与安全口径完整**：§9 隔离库自起自收、收摊证据、禁连 5432、禁启停服务、fixture 残差 0；R10 诚实标注 podman 在本沙箱被阻塞并要求标注「真库未执行」，不以构建通过替代。
11. **派工顺序依赖正确**（§6）：A2∥A1、A3←A1+A2、A5←A4 表、D3 blocked 于 053、F3 最后、B 合入后再开 C。
12. **风险登记质量高**：R1（迁移撞号）、R4（过敏形状）、R9（`domain-labels.test.ts` 门禁，已实测）、R10（真库沙箱）、R11（任务规模）均具体且可验证。

### 8.5 架构备忘的自我更正

§1.2 原判「康复师落 `libs/nursing` 且可复用护理周期/计划」需分层更正：

- **成立**：模块落 `libs/nursing`（Lead L3 已裁定，与本备忘一致）；
- **不成立**：**不能**复用 `nursing_tasks` 承载儿童个体化康复计划 —— `nursing_tasks` 无 `patient_id`（`V400:90-112` 实测）；
- **成立**：`nursing_service_periods.patient_id` **可以**承载儿童康复周期（`V400:12`），且新 `service_type` + `encounter_id IS NULL` 满足 `V404` 的 `chk_elderly_care_encounter_link` 第二分支（实测）；
- **成立**：`nursing_plans` 可复用为训练计划（`period_id` NOT NULL，`V400:60`），但**无 `patient_id`** → 响应需 join 派生（BL6）；
- **不可落** `libs/trainings`（Trainova legacy，无迁移目录、仅 `apps/trainova` 挂载，已实测）。

### 8.6 架构师对计划 §12 R3 的裁定请求回复

计划 §12 R3 问：「`nursing_service_periods` 无『同儿童唯一启用周期』DB 约束，如需 DB 兜底加部分唯一索引（V409 内），需架构师确认」。

**架构裁定：应当加，且必须用部分唯一索引。** 理由：服务端校验在并发下不成立（两个请求同时通过校验）；膳食域已有同构先例 `uq_dining_diet_profiles_active_patient`（`V600:26-27`，`WHERE status='启用'`）。建议写法：

```sql
-- V409 内，与 service_type CHECK 扩展同批
CREATE UNIQUE INDEX uq_nursing_service_periods_active_child_rehab
    ON nursing.nursing_service_periods (patient_id)
    WHERE service_type = '<儿保康复类型>' AND status = 'ACTIVE';
```

约束：① 必须带 `service_type` 谓词，**不得**影响 `ELDERLY_CARE`/`HOME_CARE`/`COMMUNITY_CARE`/`HOSPICE` 周期；② 索引名与既有 `uq_` 前缀风格一致；③ V409 同时负责 `DROP`/`ADD` `nursing_service_periods_service_type_check`，两处改动须在同一迁移内且可回滚验证；④ §8.2 的「同儿童重复启用周期 409」断言改为直接依赖该唯一索引（而非仅服务端校验）。

---

## 8bis. 评审裁定请求（提交调度者，一句话版）

1. **BL2**：批准 §7 新增「集成（调度者）」对 `apps/aceso/src/test/kotlin/.../DeclaredPermissionsTest.kt` 的写权限 —— 需把 `NursingRoutes`/`PharmacyRoutes` 聚合纳入枚举（否则 B/C/D2 的**正确声明也会让测试红**），并为 healthcare 新前缀加**全等** scope 断言。**这是当前最紧的阻塞：不解决则 B1 一起步就红。**
2. **BL3**：批准 F1 的 `platform_admin` 全量可见豁免（后端 `SubjectPermissionGateTest.kt:105` 已短路放行，前端必须镜像）。
3. **BL4**：批准新增「放量协议」小节（六角色 `role_code → permission_code → subject`）。
4. **BL5**：A5 周报表的迁移归属二选一（推荐由 A4 的 V602 一并创建）。
5. **BL6**：`ChildRehabPlan.patient_id` 确认为 join 派生字段（不加列）。
6. **BL7**：B2 执行链二选一（推荐「新表为唯一事实源」，删去「复用 `nursing_task_executions` 口径」表述）。
7. **N15**：B 链新 `service_type` 用英文 code 还是中文（Di 的中文例外）。
8. **§12 R3**：已裁定 —— **加部分唯一索引**，写法与约束见 §8.6。

---

## 9. 实现评审（rev2）— A / F1 / B / D 链已交付 diff

- 评审对象：当前工作区中 054 批次改动 —— **A 链**（A1–A5 + A3 口径修订 + A4b + A6）、**F1**、**B 链**（B1–B4 + B5）、**D 链**（D1/D2 + D4）
- 基准：计划 `docs/plans/054.…md`（651 行，§7 文件范围矩阵 + §11.1 L1–L17）
- 方法：**只读**代码 + `git status/diff` + 逐条断言核对（不改生产代码、不改计划、不启停任何服务/数据库）
- 证据限制（须先声明）：① 本工作区同时携带 **046–053 的未提交改动**，`git diff` 无法把 054 与 046–053 分离，故对**已跟踪文件**的判断以「结构与签名是否被改动」为准，对**新增文件**的判断为确定性结论；② 我**未**独立复跑真库（禁止启停 55432/5432 与开发服务），真库数字采信 Lead 回报，其充分性见 §9.6。

### 9.0 总判定：**有条件通过**（1 项需修 + 1 项需授权/口径确认）

批次质量高：迁移号、模块归属、零新增权限码、原生 SQL、契约形状、热点追加、并发唯一冲突收口、类型绑定纪律**全部达标**；两个已知真库级缺陷（`org.jooq.JSONB` 绑 pgclient、INNER JOIN 丢 `encounter_id IS NULL`）**均已修复且带护栏**，横向扫描未发现第三个同型缺陷。

### 9.1 逐项判定

| # | 维度 | 判定 | 关键证据 |
|---|---|---|---|
| 1 | **范围合规** | **通过（附归属说明）** | 所有越界项均有批准记录；两个「疑似越界」文件实为**其它计划基线**（见 §9.7） |
| 2 | **迁移号合规** | **通过** | dining `V601–V603`、nursing `V409–V414`、pharmacy `V304`、healthcare ≤`V536`（V534/V535 归 053 已落盘）；全库迁移号**无重复**；Trainova `V5–V7` **未动**（`git status` 无 trainova 变更） |
| 3 | **持久化规则** | **通过** | 批次新增 main 文件 `org.jooq` 命中数 **0**；全部 `preparedQuery` + `$1..$n` + `Tuple`；跨 schema 显式限定（`healthcare.patients`、`nursing.*`、`public.materials`） |
| 4 | **权限与门禁** | **通过** | 权限码常量**零新增**；批次新增写路由**逐条**在 `DECLARED_PERMISSIONS`（见 §9.5）；计数与源码一致：CENTRAL **160**、总数 **163**、`pharmacy:dispense` **17**、`nursing:document` **29**、`health:manage` **25**、`admission:write` **8**、`bed:manage` **4**、`fee_item:manage` **4** |
| 5 | **契约合规** | **通过** | 列表一律 `{records, meta:{total}}`（含空列表 `[]`/`0`）；`Ulid.generate()`；`OffsetDateTime`；`metadata` JSONB；中文枚举（`待审批/已批准/已驳回/已终止`、`启用/停用`、`未达成/已达成`）；046 冻结路由向后兼容（D3 空体仍 400 非 404/500） |
| 6 | **系统性问题** | **1 需修 + 1 可接受** | 见 §9.2 / §9.3：**M1** pharmacy 真库门控缺口（需修）；`RosterService` INNER JOIN（**可接受**，已被真库断言锁定为有意口径） |
| 7 | **热点串行** | **通过** | `DiningRoutes.kt` **+124/−0**、`PharmacyRoutes.kt` **+67/−0**、`NursingRoutes.kt` **+381/−1**（唯一删除行是 045/046 的 `TaskExecutionRoutes.create(...)` 形参扩展，非 054）、`Sidebar.tsx` 纯追加、`aceso.ts` **+1790/−7**（7 行删除属 046/051 的 `problemMessage`/`physician`/`doctor`）、`Main.kt` 注册顺序未变（BodyHandler→认证→集中门禁→子路由）；`DeclaredPermissions.kt` 054 条目为**块内插入**，既有条目逐字未改 |
| 8 | **验收证据充分性** | **基本充分，有 false-green 风险** | 见 §9.6 |

### 9.2 需修项

| # | 问题 | 影响任务 | 证据 | 最小改法 |
|---|---|---|---|---|
| **M1** | **`libs/pharmacy/build.gradle.kts` 不转发 `integration.db.*` 系统属性** → D2 真库类在计划 §9 的 `-Dintegration.db.*` 口径下**静默 skip**（`Assumptions.assumeTrue` 失败记为 skipped，构建仍 SUCCESSFUL） | **D2**（连带任何 pharmacy 真库验收） | `libs/pharmacy/build.gradle.kts:23` 仅 `tasks.withType<Test> {`，**无** `integration.db.` 转发；对照 `libs/dining/build.gradle.kts:23-28`、`libs/nursing/build.gradle.kts:24-29`、`libs/healthcare/build.gradle.kts:24-28` 均有；`PediatricDoseRuleIntegrationTest.kt:60-61,85-129` 自述「pharmacy 暂不转发 → 用环境变量口径」 | 三选一：① 在 `libs/pharmacy/build.gradle.kts` 的 `tasks.withType<Test>` 内补 3 行转发（照抄 dining）；② 全仓库统一改环境变量口径并在计划 §9 更新命令；③ 若坚持现状，**验收证据必须附 executed/skipped 计数**证明该集成类真的跑了。**需 Lead 授权**：`libs/pharmacy/build.gradle.kts` 不在 §7 D2 允许清单内 |
| **M2** | **L11 只完成一半**：`NursingRoutes`/`PharmacyRoutes` **聚合**已纳入全等枚举（`:373` / `:319`），但 `/healthcare/v1` **仍只有单向 `declared ⊆ real`**（`:395`），未加新前缀的全等 scope 断言 | **E1–E3、F2、F3**（**不影响本批**） | `DeclaredPermissionsTest.kt:395` 仍是单向断言；scope 函数只有既有的 `inNursingScope(:402)`/`inHealthScope(:413)`/`inMasterDataScope(:429)`，**无** `child-service-*`/`invoices`/`base-vaccination-types`/`base-developmental-scales`/`audit-logs` | 照抄 `inHealthScope` 写法新增 `inChildServiceScope`（`child-service-packages`/`child-package-subscriptions`/`child-service-settlements`/`invoices`）与 `inBaseDataScope`（`base-vaccination-types`/`base-developmental-scales`/`audit-logs`）**全等**断言。归调度者（§7 集成行） |

### 9.3 系统性风险横向扫描（本轮重点）

> 方法：对批次 9 个新服务 + 4 组新 native SQL 常量集 + 全 lib 的 JOIN/类型绑定/`ON CONFLICT`/`RETURNING` 逐类扫描。

| # | 风险型 | 扫描结果 | 判定 |
|---|---|---|---|
| **S1** | **原生 SQL 绑 `org.jooq.JSONB`**（pgclient 无编解码器） | 批次全部 JSONB 绑定走 Vert.x `JsonArray`/`JsonObject`：`MealIntakeService.kt:151,160`、`NutritionWeeklyReportService.kt:289-293`、`SpecialDietRequestService.kt:144,150`、`DietProfileService.kt:246-252`、`ChildRehab*` 各服务。`org.jooq.JSONB` 仅存在于 **legacy jOOQ 文件**（`DishService`、`DietProfileService.insertProfile`、Trainova 侧）——jOOQ 路径由 jOOQ 自行编解码，**不经过 pgclient**，故不是同型缺陷 | ✅ **已修**；护栏：`ChildDietAllergenByPatientIntegrationTest.kt:44` 逐字写明根因「绑 `org.jooq.JSONB` 时 pgclient 无编解码器 → 真库失败」；`DishNutritionRoutesTest.kt:88` 断言绑定为 JSONB。**建议**：`DishService` 新增的 jOOQ JSONB 绑定不可被复制进 native SQL（该文件属 legacy jOOQ 路径，A1 扩展符合文件既有约定，可接受） |
| **S2** | **INNER `join()` 丢弃 `encounter_id IS NULL` 行** | 横向扫 **8 处 native JOIN** + **141 处 jOOQ join**：`healthcare.patients`（`patient_id` NOT NULL FK）、`nursing_plans.period_id`（NOT NULL FK）、`nursing_service_periods`（NOT NULL FK）→ 安全；`vital_sign_records.encounter_id` **可空** → `VitalSignService.kt:422,836` 已用 `leftJoin` ✅；`health_checkup_members` → `CheckupService.kt:1075,1106,1165` 用 `leftJoin` ✅；`followup_plans`/`chronic_disease_registrations`/`medical_orders` 的 `encounter_id` 均 **NOT NULL** → INNER 安全 ✅。**唯一残留**：`RosterService.kt:239`（`loadExpectedProfiles`）与 `:494`（`buildManualItem`）对 `dining_diet_profiles → healthcare.encounters` 用 **INNER** `join(e)`；V603 后该列可空 | ✅ **DietProfileService 已修**（`DietProfileService.kt:288`、`:339` 改 `leftJoin(e)` 并附根因注释）；⚠️ **`RosterService` 判为「可接受」**——`ChildDietAllergenByPatientIntegrationTest.kt:416,446` 用真库断言「儿童档案（`encounter_id IS NULL`）**不得**进养老配餐名单」，即**有意排除**，非回归。**建议**：把隐式 INNER 排除改为**显式口径**（加 `person_type`/`service_type` 谓词或注释），并在计划中记录「儿童不进入养老配餐名单；儿保配餐不在本轮范围」，避免后人误判为缺陷 |
| **S3** | **`ON CONFLICT` 推断谓词与唯一索引不匹配** | 4 处逐字匹配：`MealIntakeService.kt:63` `(patient_id, intake_date, meal_time)` ↔ V601 `uq_dining_meal_intake_records_meal`；`NutritionWeeklyReportService.kt:51` `(patient_id, week_start)` ↔ V602 `uq_dining_nutrition_weekly_reports_week`；`DietProfileService.kt:72` `(patient_id) WHERE status = '启用'` ↔ V600 `uq_dining_diet_profiles_active_patient`（部分索引，谓词逐字一致）；`ChildRehabMilestoneService.kt:46` `(patient_id, milestone_code)` ↔ V411 `uq_child_rehab_milestones_patient_code` | ✅ **通过** |
| **S4** | **`RETURNING` 用法** | 4 处 `RETURNING id` + `rows.iterator().firstOrNull()?.getString("id")`，`DietProfileService` 另有 `?: id` 兜底；`ON CONFLICT DO UPDATE` 命中时 RETURNING 返回既有行 id（语义正确） | ✅ **通过** |
| **S5** | **类型绑定**（NUMERIC/DATE/TIMESTAMPTZ/JSONB 编解码） | 全部使用定型 API：`addBigDecimal`（NUMERIC，无浮点尾差）、`addLocalDate`（DATE）、`addOffsetDateTime`（TIMESTAMPTZ）、`addValue(JsonArray/JsonObject)`（JSONB）；**零**处未定型 `addValue(null)`；仅 2 处**定型** null（`null as JsonObject`，避免 pgclient「无法确定数据类型」） | ✅ **通过**；护栏：B2/B3/B4 真库类各有「NUMERIC5_4 编解码」「INT 与 DATE 编解码」「TIMESTAMPTZ 编解码」断言 |
| **S6** | **并发唯一冲突退化为 500** | 054 全部改用共享 `isUniqueViolation`（遍历 `cause` 链 + 约束名兜底，`libs/database/UniqueViolation.kt:20`）；B1 有「并发创建_唯一索引兜底恰好一个成功」，B4 有「并发签收_条件更新兜底恰好一个成功」，A4b 依赖 `isUniqueViolation(error, setOf("uq_dining_diet_profiles_active_patient"))` | ✅ **通过** |
| **S7** | **部分唯一索引被无关服务类型污染** | `V409` 索引带 `service_type = 'CHILD_REHAB' AND status = 'ACTIVE'` 谓词（§8.6 架构裁定逐字落实）；`V412` 待签收部分索引带 `acknowledged_at IS NULL` | ✅ **通过** |

**建议的护栏策略（写入后续任务模板）**
1. 真库集成类必须：① 类头写明「为什么必须真库（mock 测不到的点）」；② 覆盖 **codec 断言**（JSONB/NUMERIC/TIMESTAMPTZ/DATE）；③ 覆盖**并发唯一冲突**兜底；④ 覆盖**空列表形状**（`records: []` + `total: 0`）。
2. 验收命令必须报告 **executed / skipped** 计数：`@EnabledIfSystemProperty` 门控的类在缺参时是 **skipped 而非 failed**，**禁止**只看 `BUILD SUCCESSFUL` 判定真库通过。
3. 统一各 lib 的 `integration.db.*` 转发（M1），或全仓库统一环境变量口径。
4. native SQL 中**禁止**出现 `org.jooq.JSONB`；legacy jOOQ 文件内允许，但需注释说明「仅限 jOOQ 路径」。

### 9.4 通过项明细（可作为后续批次模板）

1. **迁移设计**：`V409` CHECK 重建与部分唯一索引**同批**、`IF NOT EXISTS`、`SET search_path`；`V410` 明确「append-only 台账、不设唯一约束」；`V411` 明确「四态读时派生、不落库」；`V412` 明确「签收由 `acknowledged_at` 派生、单向」；`V603` 明确「跨表 CHECK 不可行 → 服务层校验」并保留既有部分唯一索引。
2. **事实源唯一（L7）**：`nursing_child_rehab_sessions` 为「是否完成训练」唯一事实源，**不**复用 `nursing_task_executions`；有专门真库断言「不写打卡链」。
3. **派生字段（L8）**：`ChildRehabPlan.patient_id` 由 `join nursing_service_periods` 派生，`nursing_plans` **未加列**；有真库断言「patient_id 由 join 派生」。
4. **操作人派生（042）**：`NursingRoutes.withOperator`（`:479-507`）复用共享 `resolveOperator`——非管理员代操作 → `400 operator cannot be delegated`（fail-closed，不静默改写）；`PediatricDoseRuleService` 白名单拒绝客户端 `created_by`（`:377`）；`DiningRoutes` 新路由均取 `ctx["userId"]`。
5. **服务端派生结论**：`GET /nutrition-analysis` 为只读、结论由 `NutritionAnalysis.conclusionOf` 派生（`NutritionAnalysisService.kt:114`），不接受结论入参；空数据返回零值 + `无摄入记录`，**不 500**。
6. **状态机**：`SpecialDietRequestService.kt:53-64` 显式 `TRANSITIONS` 表 + `canTransition`，创建恒为 `待审批`，客户端提交 `status` 被忽略。
7. **F1 实现（L12）**：`permissions.ts:61` `platformAdmin === true` 直接放行；权限未知（`codes === null`）→ 受限入口不可见（fail-closed，与后端一致）；复用 `getCurrentSession` + `getSubjectPermissions`，**未新增**共享客户端函数；`Sidebar.tsx` 既有菜单项零改动、新项只加可选 `requiredPermissions`。
8. **R9 域文案**：`domain-labels.test.ts` 的 `NON_ELDERLY_VISIBLE_COMPONENTS` 已补 `DietitianWorkbenchPage.tsx`/`ChildRehabPage.tsx`/`PharmacistWorkbenchPage.tsx`。
9. **D2 跨 schema**：`V304` 对 `public.materials` **不加 FK**、落 `material_name` 快照、查询显式 `public.materials`（`PediatricDoseRuleService.kt:66`）；真库断言「无外键弱关联」「`material_id` 不存在 → 404 且零落库」。
10. **046 冻结路由向后兼容（L16）**：`046兼容_POST_dispenses空体仍400非404_500` 真库断言存在。

### 9.5 新增写路由 ↔ 门禁声明逐条核对

| 链 | 新增写路由 | 声明 | 结果 |
|---|---|---|---|
| A1 | `POST /dining/v1/meal-intake-records`；`PUT /dining/v1/dishes/:id`（既有，扩展 nutrition/allergens） | `DeclaredPermissions.kt:260`；既有条目 | ✅ |
| A4 | `PUT /dining/v1/diet-profiles/:id/allergens`；`POST /dining/v1/special-diet-requests`；`PATCH /dining/v1/special-diet-requests/:id/status` | `:262,:263,:264` | ✅ |
| A4b | `PUT /dining/v1/diet-profiles/by-patient/:patient_id/allergens` | `:268` | ✅ |
| A5 | `POST /dining/v1/nutrition-weekly-reports/generate` | `:266` | ✅ |
| B1 | `POST /nursing/v1/child-rehab-periods`；`PATCH …/:id/status`；`POST /nursing/v1/child-rehab-plans` | `:84,:85,:86` | ✅ |
| B2 | `POST /nursing/v1/child-rehab-sessions` | `:88` | ✅ |
| B3 | `POST /nursing/v1/child-rehab-milestones` | `:90` | ✅ |
| B4 | `POST /nursing/v1/child-rehab-handouts`；`PATCH …/:id/acknowledge` | `:92,:93` | ✅ |
| D2 | `POST /pharmacy/v1/pediatric-dose-rules`；`PUT …/:id` | `:301,:302` | ✅ |
| 读路由 | `GET /nutrition-analysis`、`GET /meal-intake-records`、`GET /pediatric-dose-calculations`、`GET /child-rehab-*` 等 | 按 046 D7 **不上门禁**（需登录） | ✅ |

**权限码**：常量表仍为既有 13 个 + `nursing:execute`/`rehab:execute`，**零新增**（L4 成立）；`PERMISSION_CATALOG` 未增条目。

### 9.6 验收证据充分性

**已覆盖（与任务声明逐条对应）**
- A1/A3/A4/A5：`ChildDietitianWorkflowIntegrationTest`（V601 建表与菜品营养列、摄入幂等覆盖、分析总量=明细和、特殊膳食状态机、过敏原覆盖写且无主数据表、周报跨周聚合与同周覆盖、空数据周不 500）+ `MealIntakeRoutesTest`/`NutritionRoutesTest`/`NutritionWeeklyReportTest`/`SpecialDietRequestServiceTest`/`DishNutritionRoutesTest`。
- A2：`NutritionAnalysisTest`（纯逻辑）。
- A4b：`ChildDietAllergenByPatientIntegrationTest`（V603 DDL、儿童无入住建档/过敏原、非儿童 400 文案、长者回归、**儿童不进养老配餐**）+ `DietProfileChildAllergenRoutesTest`。
- B1：`ChildRehabPeriodIntegrationTest`（V409 CHECK+部分唯一索引、`encounter_id` NULL、重复启用 409、暂停再启用 409、**并发兜底**、状态机终态、plan 复用 `nursing_plans` 且 `patient_id` join 派生、空列表形状）。
- B2：`ChildRehabSessionIntegrationTest`（唯一事实源且不写打卡链、同日多行、`NUMERIC(5,4)` 编解码、进度汇总口径、区间过滤不影响 progress、非法 id 404/400）。
- B3：`ChildRehabMilestoneIntegrationTest`（唯一索引 + `ON CONFLICT` 幂等、INT/DATE 编解码、读时四态含临近→逾期、已达成优先、空列表）。
- B4：`ChildRehabHandoutIntegrationTest`（待签收部分索引 `EXPLAIN` 命中、签收单向 409、**并发签收兜底**、TIMESTAMPTZ 编解码、正文快照语义）。
- D1：`PediatricDoseCalculatorTest`（31 项：折算/封顶/摊回/区间缺失回落/非法输入）。
- D2：`PediatricDoseRuleIntegrationTest`（V304 无 FK 弱关联、建查改、`material_id` 404 零落库、匹配优先级、046 兼容）+ `PediatricDoseRuleRoutesTest`。
- F1：`permissions.test.ts`（纯函数：平台管理员短路、未知权限 fail-closed、空/缺省 `requiredPermissions`）。
- 无 `@Disabled`；18 个真库类由 `@EnabledIfSystemProperty(named = "integration.db.host")` 门控。

**未覆盖 / 未独立验证项**
1. **我无法独立复跑真库**（硬约束：不得启停 DB/服务）→ A 链 137/137、A4b 6/6、B1 9/9、B2 27/27、B3+B4 61/61 均**采信 Lead 回报**。建议 Lead 在 §13 验收记录附：命令、**executed/skipped 计数**、收摊证据（`podman ps -a`、`ss -lntp | grep 55432`）。
2. **D2 真库证据在 `-Dintegration.db.*` 口径下不成立**（M1）：除非用环境变量或补转发，`PediatricDoseRuleIntegrationTest` 会 skip。
3. **F1 仅 node 单测**，无浏览器级/真实 Nexus 联调验证（计划未要求，登记为残余）。
4. **「儿童不进养老配餐名单」已被断言锁定**，但计划未显式裁定该口径 → 若产品期望儿保儿童进入配餐，则该断言锁死了错误行为（见 §9.3 S2 建议）。
5. **`/healthcare/v1` 新前缀无全等门禁断言**（M2）→ E/F2/F3 交付时需先补。

### 9.7 范围合规明细

**批准越界项（判定为合规）**
| 项 | 依据 |
|---|---|
| A1 改 `DishService.kt` | 计划 §7 A1 已列（rev3，Lead 批准归属 A1） |
| A6/B5/D4 的 `pages/dashboard/*.astro` 页面壳 | 计划 §7 A6/B5/D4 已列（rev4 补录） |
| A4b 的既有真库 fixture 补列（`DiningRoutesIntegrationTest`/`DiningServiceIntegrationTest`） | Lead 已批准 |
| `DeclaredPermissionsTest.kt` **计数行**最小改动 | 计划 §7 集成行 rev3 明确「计数行随每个新增声明同批 +1 属开发最小改动」 |

**不在 §7 但属其它计划基线（非本批越界，登记以免误判）**
| 文件 | 归属 | 证据 |
|---|---|---|
| `libs/database/src/main/kotlin/com/ovaphlow/crate/database/UniqueViolation.kt` | **051**（`051` §7 L-DINING 明列；文件头注释「051 §3 D2」） | `docs/plans/051….md:194`；054 的 `DietProfileService`/`ChildRehabPeriodService`/`ChildcareClassScheduleService` 只是**复用**该助手 |
| `apps/aceso/src/main/kotlin/…/NexusMessageClient.kt` | **052**（作为 `childHealthMessagePort` 注入 healthcare；052 范围含 `apps/aceso/`） | `Main.kt:266`；`ChildHealthSupport.kt:99` 注释「由 App 编排层注入 `NexusMessageClient`」 |
| `libs/healthcare/…/Child*Service.kt`（含 6 个含 `org.jooq` 的新文件） | **052/053** | 053 计划 §0 已声明「052 的代码沿用 `DSL.table/DSL.field` 按名引用，属独立后续项」；**不属 054 批次**，但建议登记为跨批次技术债 |

> ⚠️ **跨批次技术债提示**：`ChildAlertService`(4)/`ChildGrowthService`(5)/`ChildGuidanceService`(5)/`ChildHealthCheckupService`(5)/`ChildHealthSupport`(3)/`ChildVaccinationService`(5) 为**新增文件**却含 `org.jooq` 引用，与 `service-vertx-kotlin/AGENTS.md`「新增/变更持久化默认原生 SQL」相悖。053 计划已把它列为 R1 独立后续项 —— 建议在 054 §13 或独立任务中明确「052/053 存量 jOOQ 收敛」的归属与批次，避免长期悬空。

### 9.8 裁定请求（提交调度者）

1. **M1（需授权）**：批准在 `libs/pharmacy/build.gradle.kts` 的 `tasks.withType<Test>` 内补 `integration.db.*` 转发（3 行，照抄 dining），否则 D2 真库证据在计划 §9 命令下不成立。
2. **M2（归调度者）**：按 L11 补齐 `/healthcare/v1` 新前缀的**全等** scope 断言（`child-service-*`/`invoices`/`base-vaccination-types`/`base-developmental-scales`/`audit-logs`）——E/F2/F3 开工前完成。
3. **S2 口径确认**：请在计划中显式记录「儿保儿童**不进入**养老配餐名单；儿保配餐不在本轮范围」，并把 `RosterService` 的隐式 INNER 排除改为显式谓词/注释。
4. **证据格式**：§13 验收记录请统一附 `executed/skipped` 计数 + 收摊证据，禁止以 `BUILD SUCCESSFUL` 代替真库结论。
5. **跨批次技术债**：确认「052/053 存量 jOOQ 收敛」的归属批次。

### 9.9 处置闭环复核（2026-10-06 晚，只读追加）

调度者已逐条处置 §9.8，本节记录**独立复核结论**。

| 项 | 处置 | 复核结论 |
|---|---|---|
| **M1** | 调度者授权并派工（task-35），dev2 完成 | ✅ **闭环**。`libs/pharmacy/build.gradle.kts:23-29` 与 `libs/dining/build.gradle.kts:23-29` **逐字一致**（`integration.db.*` → `systemProperty`）。负向验证（`-Dintegration.db.port=1` → 属性进测试 JVM → `Connection refused`、**无 5432 回落**；无参 → `skipped=8`、模块 162/162）与该实现吻合 → **D2 真库证据成立的前提已具备** |
| **M2** | 调度者亲自实现（L11 归调度者） | ✅ **闭环**。`inChildServiceScope`(`DeclaredPermissionsTest.kt:450`) 与 `inBaseDataScope`(`:464`) 均为**双向 `assertEquals`**（整组全等，非单向），前缀与建议逐字一致 |
| **S2** | 转 PM 在计划显式记录配餐口径 + `RosterService` 显式谓词列为后续小项 | ✅ 无异议（不影响本批验收） |
| **证据格式** | §13 附每个真库类 executed/skipped 计数 + 收摊四证据 | ✅ 无异议 |
| **技术债** | 053 的 6 个 `Child*Service.kt` 的 jOOQ 已在 053 R1 登记，**不并入 054**，总交付单列 | ✅ 无异议 |
| **R-1** | 调度者按建议 1+2+3 **全部实施**（`DeclaredPermissionsTest.kt`，调度者文件） | ✅ **已闭合（Lead 实施，F3 读路由断言随 F3 落地）** —— 复核见下 |

#### ✅ R-1 复核：受门禁的**读路由**会被三处断言误杀（**已闭合**）

**原根因**：`declaredUnder` 只按 path 前缀过滤、不按方法过滤；而 `healthcareReal` 来自 `writeRoutes`（仅 POST/PUT/PATCH/DELETE）。两侧方法域不对称 → F3 声明 `GET /healthcare/v1/audit-logs` 时会在**声明完全正确**的情况下打挂三处断言（与 BL2 同型）。

**实施复核（只读，2026-10-06 晚）**

| 建议 | 落地 | 复核 |
|---|---|---|
| ① 方法域对齐 | `writeMethodsForGate = setOf("POST","PUT","PATCH","DELETE")`(`:294`)；`declaredUnder`(`:296-300`) 与 `declaredNursing`(`:390-393`) 均加 `it.method in writeMethodsForGate`；根因注释在 `:291-293` | ✅ **正确**。全部与 `writeRoutes` 比对的断言（dining/inventories/pharmacy/healthcare 通用+三个 scope/nursing）现在方法域一致；声明全量表**未动** |
| ② `audit-logs` 移出写 scope | `baseDataScopePrefixes`(`:471-474`) 仅剩 `base-vaccination-types` / `base-developmental-scales`；注释在 `:468` | ✅ **正确** |
| ③ 读路由单列断言 | 调度者记入收尾清单，**随 F3 落地**（`method=="GET" && path=="/healthcare/v1/audit-logs" && code==access:manage` + 请求级 403） | ⏳ **待 F3**；增量评审时复核「是否到位」 |

**携带项（评审追加，均已由调度者实施并复核）**

| 携带项 | 实施 | 复核 |
|---|---|---|
| **C-1 断言文案口径** | `:483` 改为「healthcare 侧**基础数据写路由**整组必须与声明一致（F2；**审计读路由不在本 scope，由 F3 单列断言覆盖**；漏登记或新裸奔写路由都会失败）」，标题注释同步为「基础数据写路由全等」 | ✅ **已闭合**（`:470-475` + `:483`） |
| **C-2 注释不夸大能力** | `:289-296` 明写「过滤后**读路由不在任何全等断言覆盖内**——自省端点测试只校验 `routes.size()` 计数与 `permission_codes` 集合，**不校验 method/path**」，并写明 F3 落地**必须**做两件事：① 补 `GET /healthcare/v1/audit-logs` + `access:manage` 单列断言与请求级 403 断言；② `routes.size()` **163 → 164** | ✅ **已闭合**（`:293-296`，且 F3 清单已**内嵌在代码现场**，不再只存在于调度者备忘） |

> C-2 的价值：把「③ 读路由单列断言不可省」与「`routes.size()` 计数 +1」直接写在断言旁边，任何后续改这段代码的人都会看到，不依赖口口相传。增量评审时据此逐项核对。

**触发点与影响面（存档）**：F3 的 `GET /healthcare/v1/audit-logs` 是 046 D7 读路由门禁的**唯一例外**，也是全系统第一条被声明的 GET；修复后不再误杀。影响面仅 F3 及将来任何受门禁的读路由；E2/E3/F2 只声明写路由则不受影响。

**最小改法存档（原始建议，已实施 ①②③）**
1. 与 `writeRoutes` 比较时限定写方法（**声明全量仍保留 GET**，否则自省端点会漏报唯一受门禁的读路由）。
2. 为唯一受门禁的读路由**单列断言** + 请求级「无 `access:manage` → 403 中文」断言（`writeRoutes` 永远覆盖不到它）。
3. 把 `audit-logs` 从写 scope 移出。

---

## 10. 增量评审（C/E/F）— C 链 + E 链 + F2 + F3 已交付 diff

- 评审对象：**C 链**（C1/C2/C3）、**E 链**（E1/E2/E3/E4）、**F2**（V539 基础数据）、**F3**（V540 审计 + 首条受门禁读路由）
- 基准：计划 651 行版（§5.5/§5.6/§5.8、§11.1 L1–L17）+ §9.9 的 6 项就绪清单
- 方法：**只读**代码 + `git status` + 逐条断言核对（不改生产代码、不改计划、不启停服务/数据库；真库数字仍采信调度者回报）
- 总判定：**有条件通过** — 2 项需改（M3 并发唯一冲突弱判定复发、M4 F3 请求级 403 断言缺失 + 注释失实）+ 1 项证据补齐（M5 F2/F3 缺真库）+ 1 项需裁定（M6 `enforced_by` 单值已不准确）

### 10.0 六项就绪清单逐项判定

| # | 项 | 判定 | 证据 |
|---|---|---|---|
| 1 | F3 读路由断言 | **部分通过（M4）** | ✅ 计数与单列断言全对；❌ **请求级 403 断言缺失** |
| 2 | E/F/C 链 S1–S7 复扫 | **S1/S2/S3/S4/S5/S7 通过；S6 同型复发（M3）** | 见 §10.1 |
| 3 | C 链 `nursing:document` 越权面 | **通过** | 恰 7 条 childcare 写路由；`nursing:document` 仍 **29** 条（13 护理文书 + 2 C1 + 5 C2 + 9 healthcare 侧），**未额外扩大** |
| 4 | 迁移号连续性 | **通过** | healthcare `V536–V540`（E1/E2/E3/F2/F3）、nursing `V409–V414`；全库**无重复号**；与 053 的 `V534/V535` **无重叠**；Trainova `V5–V7` **未动** |
| 5 | 验收证据充分性 | **需改（M5）** | E 链真库证据强；**F2/F3 无真库测试** |
| 6 | 计划一致性 | **通过** | 两处已知偏差均已在 §5.5 rev7 记录；实际路由与 §5.5/§5.6 **逐条一致** |

### 10.1 第 1 项：F3 读路由断言（复核）

**✅ 通过部分（计数与声明全部核对无误）**

| 断言 | 位置 | 实测 |
|---|---|---|
| `routes.size() == 172` | `DeclaredPermissionsTest.kt:257` | ✅ 实际 `DeclaredPermission(` 计数 = **172** |
| `count { method == "GET" } == 1` | `:179-183` | ✅ 实际 GET = **1**（`DeclaredPermissions.kt:316`） |
| 单列断言 `GET + /healthcare/v1/audit-logs + access:manage` | `:170-178` | ✅ 三者齐备 |
| `count { code == ACCESS_MANAGE_PERMISSION } == 1` | `:164-168` | ✅ 实际 = **1** |
| `count { code == BASE_DATA_MANAGE_PERMISSION } == 4` | `:157-161` | ✅ 实际 = **4** |
| CENTRAL = 169 | `:122-126` | ✅ 实际 CENTRAL = **169**（169+3 INJECTED = 172） |
| `writeMethodsForGate` 仍过滤 | `:327,331,424` | ✅ 保留 |

方法分布实测：POST 104 / PUT 27 / PATCH 31 / DELETE 9 / **GET 1** = 172；分项计数 `nursing:document` 29、`health:manage` 25、`admission:write` 8、`bed:manage` 4、`fee_item:manage` 4、`pharmacy:dispense` 17 **全部与源码一致**。

**❌ 缺口 M4：请求级 403 断言仍未落地，且注释失实**

- `AuditLogRoutesTest.kt:35-36` 写：「门禁（全系统第一条受门禁的读路由：无 `access:manage` → 403 中文）**由调度者在 `apps/aceso` 的 `DeclaredPermissionsTest` 统一覆盖**」。
- **但该覆盖不存在**：`DeclaredPermissionsTest` 只有**声明存在性**断言（`:170-178`）与 GET 唯一性断言（`:179-183`）；全仓库检索 `audit-logs` + `403/access` 仅命中这两处注释与声明断言，**没有任何请求级断言**。
- 影响：① C-2 与计划 §8.6 ⑤ 承诺的「无码 → 403 中文」**未被任何测试锁定**；② `AuditLogRoutesTest` 的注释会让后续评审误以为已覆盖（**失实注释比缺失本身更危险**）。
- 风险定级：**中**（非安全漏洞——声明已在 `DECLARED_PERMISSIONS`、`Main.kt:233` 的 CENTRAL 循环按 `method+path` 注册门禁，路径 `/healthcare/v1/audit-logs` 与 `HealthcareRoutes` 的 `router.get("/audit-logs")` 在 `/healthcare/v1/*` 挂载下逐字匹配，接线正确；缺的是**端到端证据**）。
- **最小改法**（二选一）：
  1. 在 `DeclaredPermissionsTest` 或新增小测试中，用**生产装配** `buildApiRouter(...)` + 桩权限客户端（返回空 `permission_codes`）发 `GET /crate-api/healthcare/v1/audit-logs` → 断言 `403` + 中文「权限不足」文案；再给 `access:manage` → 断言非 403。`ApiBodyHandlingTest` 已用生产装配，基建现成。
  2. 或修正 `AuditLogRoutesTest.kt:35-36` 注释为「**尚未覆盖，待补**」，并在 §13 记为未覆盖项——**不可保留失实注释**。

### 10.2 第 2 项：S1–S7 横向复扫（C/E/F）

| # | 风险型 | 扫描结果 | 判定 |
|---|---|---|---|
| **S1** | 原生 SQL 绑 `org.jooq.JSONB` | **C/E/F 全部新增 main 文件 `org.jooq` 命中 = 0**（`AuditMiddleware`/`AuditLogService`/`BaseDataService`/`ChildServicePackageService`/`ChildServiceSettlementService`/`InvoiceService`/4 个 `Childcare*Service`）。JSONB 绑定与读回均为 Vert.x 类型：`BaseDataService.kt:482-496`（`recommendedMonths: JsonArray`、`definition: JsonObject`、`metadata: JsonObject?`）、`:113,129,131`（`getJsonArray`/`getJsonObject`）；`AuditLogService.kt:28,90`（`metadata: JsonObject?` + `addValue`）；C 链 `ChildcareSafetyInspectionService.kt:129`（`items`）、`:132`（`metadata`） | ✅ **通过** |
| **S2** | INNER JOIN 丢弃可空列行 | **C/E/F 新服务 JOIN 数 = 0**（`grep JOIN` 全空）——无同型风险。患者校验用**单表存在性查询**（`SELECT id, person_type FROM healthcare.patients WHERE id = $1`：`ChildServicePackageService.kt:137`、`ChildServiceSettlementService.kt:129`、`InvoiceService.kt:83`），不经 JOIN 丢行 | ✅ **通过**（且**不存在** §9.3 S2 的残留面） |
| **S3** | `ON CONFLICT` 谓词不匹配 | **C/E/F 新服务 `ON CONFLICT` 数 = 0**——幂等改为「唯一约束 + 显式冲突映射」（见 S6），无谓词推断风险 | ✅ **通过**（风险型不适用） |
| **S4** | `RETURNING` 用法 | **C/E/F 未使用 `RETURNING`**（插入后统一 `compose { get(id) }` 回读） | ✅ **通过** |
| **S5** | 类型绑定（NUMERIC/DATE/TIMESTAMPTZ） | 金额全程 **`BigDecimal`**（`ChildServicePackageService.kt:42` 注释「禁止经 Double」、`:326` `addValue(getBigDecimal("price"))`、`:327-328` `BigDecimal.ZERO`）；入参 ≤2 位小数、超出 400 不静默进位 | ✅ **通过** |
| **S6** | **并发唯一冲突退化为 500** | ❌ **同型复发（M3）**：`ChildServicePackageService.kt:592`、`InvoiceService.kt:377`、`BaseDataService.kt:731` 各自定义**私有** `mapUniqueViolation`，实现为**单层 `error.message` 子串匹配**——**不遍历 `cause` 链、不校验 `sqlState == "23505"`**。而共享助手 `libs/database/UniqueViolation.kt:20` 已由 051 建立、A/B/C 链已在用（`WeeklyMenuService`/`DietProfileService`/`ChildRehabPeriodService`/`ChildcareClassScheduleService`） | ❌ **需改** |
| **S7** | 部分唯一索引谓词污染 | C/E/F 未新增部分唯一索引（E/F 的唯一约束均为普通 UNIQUE：`uq_child_service_packages_code`、`uq_child_service_settlements_no`、`uq_child_service_invoices_no`、`uq_base_vaccination_types_code`、`uq_base_developmental_scales_code_version`） | ✅ **通过**（风险型不适用） |

**M3 细节与最小改法**

- **现状可用但脆弱**：`mapUniqueViolation` 直接挂在 `pool.preparedQuery(...).execute(...)` 的 Future 上，pgclient 抛出的 `PgException.message` 确实含约束名，故 E1/E3/F2 当前能正确映射 409。
- **风险**：① 一旦该调用被包进 `compose`/事务包装层，异常被包装后 `message` 可能不再含约束名 → **409 退化为 500**（这正是 051 D2 修掉 dining 同类问题的原因，`UniqueViolation.kt:8-16` 注释有完整根因）；② 无 `sqlState` 校验，子串匹配偏松。
- **最小改法**（每文件 1 行，保留现有扩展名与调用点不变）：
  ```kotlin
  private fun <T> Future<T>.mapUniqueViolation(constraint: String, message: String): Future<T> =
      recover { error ->
          if (isUniqueViolation(error, setOf(constraint))) Future.failedFuture(ConflictException(message))
          else Future.failedFuture(error)
      }
  ```
  即把三处私有实现改为**委托**共享 `isUniqueViolation`（自动获得 cause 链 + `23505` 兜底）。也可进一步抽到 `libs/healthcare` 的公共 helper 供后续复用。
- **需同步**：`service-vertx-kotlin/AGENTS.md` 未规定唯一冲突判定方式，建议在 §9.3 的护栏策略中固化「唯一冲突一律走 `isUniqueViolation`，禁止新写 message 子串匹配」。

### 10.3 第 5 项：验收证据充分性（M5）

| 链 | 真库集成测试 | 覆盖 | 判定 |
|---|---|---|---|
| **E1/E2/E3** | ✅ `ChildServiceBillingIntegrationTest`（`@EnabledIfSystemProperty` 门控，8 用例） | `V536_V538迁移_六表约束索引生效且bills未被动`、`E1付费状态四态派生与E2联动`、`E1免费套餐视为已付_订阅状态机非法流转400`、`E2结算单_明细取整与合计`、`E2超额收款400_不写流水不更新头`、`E3发票_票号唯一409与状态单向`、`E3发票_关联结算单金额服务端派生`、`E链不动养老账期链_bills行数不变`、`空列表形状与非法参数` | ✅ **充分**（含 §8.5 要求的「不改 `bills`」回归断言） |
| **C1/C2** | ✅ `ChildcareActivityLogIntegrationTest`、`ChildcareScheduleAndSafetyIntegrationTest` | 活动日志/观测/日程冲突/巡查闭环 | ✅ **充分** |
| **F2（`base_*`）** | ❌ **无** — 仅 `BaseDataRoutesTest` + `BaseDataFallbackTest`（均 **DB-free**，mockk 桩） | 回落逻辑三态覆盖良好（库空/读失败/读库优先），但 **V539 DDL、唯一约束、JSONB 往返**未真库验证 | ❌ **需改** |
| **F3（`audit_logs`）** | ❌ **无** — 仅 `AuditLogRoutesTest`（DB-free stub）+ `AuditMiddlewareTest`（DB-free，落库用替身） | 中间件行为覆盖好（写方法入账/GET 不入账/失败不阻断/403 尝试入账/路径不重复前缀），但 **V540 DDL 与真实落库/查询**未真库验证 | ❌ **需改** |

- 计划 §4 对 F2/F3 的验收点均明写「**+ 真库**」，故这**不是**可选项。
- **风险不对称**：F2 的缺失点恰好是 **S1 JSONB codec 面**——`BaseDataFallbackTest` 用 mockk 桩绕过了真实 JSONB 编解码，而 `recommended_months`（JSONB array）与 `definition`（JSONB object）正是最易踩 codec 的字段。**唯一能抓这类缺陷的真库测试恰好缺席。**
- **最小改法**：各补一个门控集成类（照 `ChildServiceBillingIntegrationTest` 模板，自建 fixture + 自清 + 残差 0）：
  1. `BaseDataIntegrationTest`：V539 建表/唯一约束；`base_vaccination_types.recommended_months` 与 `base_developmental_scales.definition` 的 **JSONB 写入→读回**；`refreshAll()` 在**真库空表**下等价常量基线、有启用记录时覆盖（把 `BaseDataFallbackTest` 的三态在真库重放一次）。
  2. `AuditLogIntegrationTest`：V540 建表；`AuditLogService.record` 真库落一条（`metadata` JSONB 往返）；`GET /audit-logs` 过滤参数真库查询；`status_code`/`resource_id` 映射。
- 另：E/F 的 `*RoutesTest` 均为 DB-free，符合计划分层；本条只针对**真库层缺席**。

### 10.4 第 6 项：计划一致性（通过）

| 计划条款 | 实际实现 | 判定 |
|---|---|---|
| §5.5 `PUT /child-service-packages/:id`（rev7 标注为增补） | `HealthcareRoutes.kt:1459` 存在，且 `DeclaredPermissions.kt:238` 已声明 | ✅ 一致（增补已按 N18 体例记录） |
| §5.5 发票路径 `/invoices` | `HealthcareRoutes.kt:1520,1526,1535` 为 `/invoices`、`/invoices/:id/status`；表名 `healthcare.child_service_invoices`（V538） | ✅ 一致（计划已同步为 `/invoices`；表名与路由前缀不同名属既有惯例，如 `child_service_settlements` → `/child-service-settlements`） |
| §5.6 F 链 3 组路径 | `:1547-1585` 疫苗类型/发育量表各 POST/PUT/GET；`:1596` `GET /audit-logs` | ✅ 逐条一致 |
| §5.3 C 链 7 条写路由 | `NursingRoutes.kt:260,290,324,354,364,394,404` | ✅ 逐条一致 |
| §5.8 枚举白名单 | C/E/F 迁移注释与实现均按白名单（`启用/停用`、`待收款/部分收款/已结清/已作废`、`已开具/已作废`、`草稿/已发布/已取消`、`待整改/已整改/已关闭`） | ✅ 一致 |
| §5.4 D3 增补（N18/L16） | 未在本次范围（前批已核） | — |

### 10.5 第 3 项：C 链越权面（通过）

- C 链写路由 **恰 7 条**（`childcare-activity-logs` POST、`childcare-behavior-observations` POST、`childcare-class-schedules` POST + `/:id/status` PATCH、`childcare-safety-inspections` POST + `/:id/items` POST + `/:id/close` PATCH），与计划 §5.3 逐条一致。
- `nursing:document` 声明总数仍 **29**（护理文书 13 + C1 保育文书 2 + C2 保育日程与巡查 5 + healthcare 侧护理类 9），**未新增**任何越权声明。
- L6 已接受的残余风险（`trainer01` 复用 `nursing:document` 因而获得护理评估/计划/不良事件/交接班写权）**未被 C 链扩大**；C 链只消费既有的 7 条自身路由。

### 10.6 需改与裁定清单

| # | 项 | 影响 | 最小改法 |
|---|---|---|---|
| **M3** | E1/E3/F2 私有 `mapUniqueViolation` 弱判定（单层 message 子串，无 cause 链 / 无 `sqlState`） | E1/E3/F2（并发冲突在异常被包装时 409→500） | 三处私有实现改为委托共享 `isUniqueViolation(error, setOf(constraint))`（每文件 1 行）；并把「唯一冲突一律走共享助手」写入护栏 |
| **M4** | F3 **请求级 403 断言缺失**，且 `AuditLogRoutesTest.kt:35-36` 注释**失实**声称已由 `DeclaredPermissionsTest` 覆盖 | F3（唯一受门禁读路由的端到端证据缺失） | 补请求级断言（生产装配 + 桩权限客户端 → 无码 403 中文 / 有码非 403）；或至少修正注释并在 §13 记为未覆盖项 |
| **M5** | F2/F3 **无真库测试**（计划 §4 明写「+ 真库」） | F2/F3 验收 | 补 `BaseDataIntegrationTest`、`AuditLogIntegrationTest`（JSONB 往返 + DDL + 真实落库/查询 + 残差 0） |
| **M6** | `enforced_by` 单值已不准确（见 §10.7） | 自省端点/角色页展示、046 D3/049 D1 一致性 | 见 §10.7 |

### 10.7 调度者提问的裁定：`base_data:manage` 由「仅 Nexus」变「Nexus + Aceso 双承载」

**结论：裁定方向成立且必要，但「同步」这一步未按 049 D1 规定的程序完成 —— 需补计划同步 + 自省契约裁定。**

**（1）功能必要性：成立 ✅**
Aceso 侧 `/healthcare/v1/base-vaccination-types`、`/base-developmental-scales` 的 4 条写路由**只能**由 `DECLARED_PERMISSIONS` 门禁；Nexus 门禁覆盖不到 Aceso 路由。若不声明即 **fail-open（4 条裸奔写路由）**。故把 049 D1 的「Aceso 不得声明」断言反转为「恰 4 条」在**机制上正确且必要**（`DeclaredPermissions.kt:311-314` + `Main.kt:233` 循环）。

**（2）但 049 D1 是**冻结裁定**，且它**预先规定了改判程序** ❌**
- `docs/plans/049….md:45-53`：D1 标题即「三个新码，**不**扩展 `base_data:manage` 到 Aceso」；**理由 1 = 「`enforced_by` 单值不变式（046 D8）」**——「`base_data:manage` 已由 Nexus 接线；若同时接 Aceso 路由，`enforced_by` 必须变成多值，需改动 043 已冻结的自省契约形状与角色页展示」；理由 3 = 域内聚（beds/fee_items 各立新码）。
- `049….md:216`（§11.1）：**「若用户改判为并入，需同步修改 046 D3 与本计划 D1，并升级自省端点为多值 `enforced_by`。」**
- 现状：只改了测试断言 + 注释，**未**同步 046 D3、**未**改 049 D1、**未**升级 `enforced_by`。

**（3）实际后果：`enforced_by` 现在是不准确的**
- `PERMISSION_CATALOG`（`DeclaredPermissions.kt:429-433`）仍为 `ACCESS_MANAGE_PERMISSION → "nexus"`、`BASE_DATA_MANAGE_PERMISSION → "nexus"`，而两码现由 **Nexus + Aceso 双承载**（`access:manage` 新增 `GET /healthcare/v1/audit-logs`；`base_data:manage` 新增 4 条写路由）。
- 自省端点 `GET /access/v1/permissions` 原样透出 `enforced_by`（`AccessRoutes` 用 `entry.enforcedBy ?: ""`），**角色页据此展示** → 用户看到「仅 Nexus 承载」，与事实不符。
- 现有测试**发现不了**：`:221-225` 只断言单向「标 `nexus` ⟹ 真在 Nexus 接线」，不校验反向（Aceso 已接线的码是否如实标注）。

**（4）建议的最小改法（三选一，需调度者裁定）**
1. **按 049 规定的程序走（推荐）**：把 `enforced_by` 升级为**多值**（如 `"nexus+aceso"` 或数组），同步更新 046 D3、049 D1（标注被 054 F2/F3 取代）、054 §5.7/§7、`PERMISSION_CATALOG`、`AccessRoutes`、角色页展示，并新增断言「标 `nexus` 的码必须在 Nexus 接线」+「Aceso 已接线的码必须在 `enforced_by` 中出现 `aceso`」。**代价**：触及 043 冻结的自省契约形状（但 049 已预授权此路径）。
2. **保持单值、语义收窄为「主承载层」**：`enforced_by` 继续表示「最初/权威承载层」，另**新增**一个附加字段（如 `also_enforced_by: ["aceso"]`）——对既有消费方**向后兼容**，不动 043 形状。**代价**：契约字段增加。
3. **明确记录接受该展示不准确**：在计划与 §13 写明「`enforced_by` 单值不再反映双承载；角色页展示以 `wired` 为准」，并修正目录描述。**代价**：留下已知不准确项。

**（5）另需注意的语义张力（建议一并处理）**
- `base_data:manage` 目录描述是「基础数据管理（**部门等组织主数据**）」，而 F2 用它承载**临床内容主数据**（疫苗类型、发育量表）。049 D1 的「域内聚」原则恰恰是**为 beds/fee_items 各立新码**而不并入 `base_data:manage`。二者口径不一致 → 至少应**更新目录描述**（如「基础数据管理（部门等组织主数据、疫苗类型、发育量表）」），或说明为何临床内容主数据例外。
- 该描述变更会进入自省端点 `catalog[].description`，属**兼容性安全**的文案变更。

**（6）是否需要同步计划：需要**
- 054 计划：§5.7/§7 F2 行、§11.1 增列一条裁定（`base_data:manage` 双承载 + `enforced_by` 处置）；
- 046 D3、049 D1：按 049 §11.1 的预授权标注取代关系；
- 若选方案 1，另需更新 043 的自省契约章节与角色页说明。

### 10.8 通过项明细（C/E/F 批次）

1. **零新增权限码（L4 保持）**：权限码常量表未增；C/E/F 全部复用既有码（`nursing:document` / `billing:write` / `base_data:manage` / `access:manage`）。
2. **迁移设计规范**：V536–V540 均 `SET search_path`、显式 schema 前缀、中文枚举走应用层白名单 + 注释（不加 CHECK）、金额/数量 CHECK 兜底、JSONB 类型 CHECK（`jsonb_typeof(items)='array'`、`definition='object'`）、业务唯一约束（票号/结算单号/套餐码/量表码+版本）。
3. **E 链不改 `bills`**（§3 Df / L? ）：`V536_V538迁移…bills未被动` + `E链不动养老账期链_bills行数不变` 双真库断言。
4. **F3 中间件护栏（L15）落实**：`Main.kt:209 BodyHandler → :215 认证总闸 → :221 审计中间件 → :233 集中门禁`，**位置精确符合 L15**（在门禁前 → 403 尝试也入账），未插入 `BodyHandler`、注册顺序未变。
5. **F3 旁路语义**：只审写方法（`GET` 不入账）、`endHandler` 恰好一次、**绝不读请求体**（PHI）、落库失败只 `log.warn` 不影响主请求与状态码 —— 均有对应 DB-free 单测。
6. **`audit_logs` 设计**：`metadata` 显式注释「禁止写请求体」；三个查询索引（`occurred_at` / `actor+occurred_at` / `method+path+occurred_at`）；`actor` 允许空（异常路径）。
7. **金额精度纪律**：E 链全程 `BigDecimal`、禁 Double、≤2 位小数、超额收款 400 不写流水不更新头（真库断言）。
8. **C 链权限面未扩大**：7 条自身路由，`nursing:document` 仍 29。
9. **计划与实现逐条一致**（§10.4）：含两处已知偏差均已按 N18 体例记录。
10. **C2 唯一冲突走共享助手**：`ChildcareClassScheduleService.kt:133` 用 `isUniqueViolation(error, setOf(SLOT_UNIQUE_INDEX))` ✅（与 E/F 形成对照，见 M3）。

---

## 11. 最终评审结论（054 全链 A/B/C/D/E/F）

- 出具人：架构师（独立评审角色）；出具时间：2026-10-07
- 评审方式：**只读**代码 + 计划 + 测试文件逐条核对；**不改生产代码、不改计划、不启停服务/数据库、不跑构建车道**
- 覆盖范围：§8 计划评审（BL2–BL7）→ §9 实现评审（A/F1/B/D）→ §9.9 处置闭环（M1/M2/R-1/C-1/C-2）→ §10 增量评审（C/E/F，M3–M6）→ 本节最终结论
- **结论：达到计划 §8 验收口径（含已声明的受控例外），M1–M6 与 R-1/C-1/C-2 全部闭环，无阻塞交付的残余项。** 唯一待并入项是调度者正在跑的全量默认验证（§8.8），其结果为通过性前提而非评审缺口（见 §11.3）。

### 11.1 ① 六链是否达到计划 §8 验收口径

| §8 条款 | 判定 | 依据 |
|---|---|---|
| §8.1 A 链（营养师） | **通过** | §9：A1 摄入幂等（唯一索引 + `ON CONFLICT`）、A2 纯逻辑、A3 结论服务端派生 + 空数据不 500、A4 过敏原复用既有列（L2，无第二张主数据表）+ 特殊膳食状态机、A4b 儿童免入住建档 + 非儿童 4xx + 长者回归、A5 周报覆盖生成；**配餐口径按 §8.1 rev7 判定为「儿保儿童不进入养老配餐」有意口径**（`ChildDietAllergenByPatientIntegrationTest:416,446` 真库断言），A4b **不得被验收为「儿童配餐已生效」** |
| §8.2 B 链（康复师） | **通过** | §9：V409 部分唯一索引（`service_type='CHILD_REHAB'`）兜底并发、`patient_id` join 派生（L8，未加列）、`nursing_child_rehab_sessions` 为唯一事实源（L7，不写打卡链）、里程碑读时四态、手册签收单向 + 并发兜底；既有四类养老周期不回归 |
| §8.3 C 链（保育员） | **通过** | §10：7 条自身写路由、日程冲突 409（走共享 `isUniqueViolation`）、巡查闭环、`nursing:document` 恰 29 条未扩大；真库类 `ChildcareActivityLogIntegrationTest` + `ChildcareScheduleAndSafetyIntegrationTest` |
| §8.4 D 链（药师） | **通过** | §9：D1 引擎 31 单测、D2 弱关联 `public.materials`（无 FK + 名称快照）、D3 复用 053 引擎不重建（L16 既有消费方不传新字段仍 2xx）、D4 复用既有 `checkDrugAllergy` 不新增重复客户端函数 |
| §8.5 E 链（收银） | **通过** | §10：`ChildServiceBillingIntegrationTest`（真库 10 用例）覆盖 V536–V538 约束、E1 四态派生、E2 取整/超额 400 不写流水、E3 票号唯一 409 + 单向、**`bills` 与养老账期链未被动**（双断言）、空列表形状；金额全程 `BigDecimal` 禁 Double |
| §8.6 F 链（管理员） | **通过** | F1 平台管理员豁免（L12）+ fail-closed（§9）；F2 读库优先/常量回落 + 库空等价（`BaseDataFallbackTest` DB-free + `BaseDataContentIntegrationTest` 真库 6 用例）；F3 中间件位置精确（`Main.kt:209→215→221→233`，L15）、只记元数据不落请求体、403 尝试入账、**`GET /audit-logs` 无码 → 403 且不触库 / 有码 200**（`AuditLogAccessTest` 4 用例 + `AuditLogIntegrationTest` 8 用例） |
| §8.7 权限与门禁 | **通过** | §9/§10 实测：**新增权限码 0 个**；总数 **172**、CENTRAL **169**、GET **1**，分项计数（`nursing:document` 29 / `health:manage` 25 / `admission:write` 8 / `bed:manage` 4 / `fee_item:manage` 4 / `pharmacy:dispense` 17 / `base_data:manage` 4 / `access:manage` 1）**逐项与源码一致**；`/dining/v1`、`/nursing/v1`、`/pharmacy/v1`、`child-service-*`、`base-*` **全等**，其余 healthcare 单向（L11 rev7 口径）；L18 双承载（Nexus + Aceso）已声明且缺一即裸奔 |
| §8.8 契约与回归 | **条件通过（待调度者全量验证结果并入 §13）** | 契约形状（`{records,meta:{total}}`、ULID、`OffsetDateTime`、`metadata`、中文枚举）在 §9/§10 逐链核对通过；`org.jooq` 新增文件零命中（§8.9 同源）；**受控例外 R17**（两簇既有基线失败：healthcare/aceso 7 项 + nursing 25 项，**与 054 无关**）须在 §13 逐项列出实际计数与豁免依据；**我未独立复跑**（构建车道归开发/调度者），故本条以调度者结果为准 |
| §8.9 原生 SQL | **通过** | 054 全部新增 main 文件（dining/nursing/pharmacy/healthcare/apps-aceso 共 30+ 个）`org.jooq` 命中 **0**；JSONB 一律 Vert.x `JsonArray`/`JsonObject`；跨 schema 显式限定（`healthcare.patients`、`nursing.*`、`public.materials`） |
| §8.10 真库 | **通过（口径需澄清）** | 计划点名的单一 `ChildRoleWorkflowIntegrationTest` **不存在**；但六链由 **12 个按链拆分的真库类（≈96 个 `@Test`，全部 `@EnabledIfSystemProperty` 门控）**覆盖：A 2 个、B 4 个、C 2 个、D 1 个、E 1 个、F2 1 个、F3 1 个 —— **覆盖意图已达成**。建议 §13 将 §8.10 的验收对象改述为「上述按链真库类清单」（见 §11.4 R-B） |

**受控例外清单（评审确认其成立）**
1. **R17 两簇既有基线失败**（healthcare/aceso 7 项 + nursing 25 项）—— 均落在 054 未改动类、单独运行可复现、报错为 042 `operator cannot be delegated` 与 401「医生必填」，属 **046–053 基线**，**不计入 054 验收**；且计划明写「**不得**因该豁免放宽 054 自身断言」，与我的评审口径一致。
2. **L6 保育员复用 `nursing:document` 的越权面** —— Lead 裁定接受（零新增码），§10.5 已确认 **C 链未扩大**该面。
3. **R19 `enforced_by` 对双承载码不完整** —— 接受为已知偏差（字段语义「由 Nexus 门禁强制」仍为真，只是不完整）；目录描述已修正。
4. **R20 门禁 403 响应体为英文 `forbidden` + `required_permission`** —— **046 既有口径、非 054 引入、本轮不改**（我独立复核 `SubjectPermissionGate.kt:183-188` 确为 `{"error":"forbidden","required_permission":"<code>"}`，与计划 rev11 描述一致）。
5. **F3 盲区 7「落库失败不阻断主请求」** —— 无法在复用同一 Pool 下稳定注入，由 DB-free `AuditMiddlewareTest` 替身 recorder 覆盖（类 KDoc 已写明），**接受**。
6. **R14 A4b「非儿童必须有入住」仅服务层校验** —— 跨 schema CHECK 不可行，已由 DB-free + 真库测试锁定；直接改库绕过服务的场景由运维约束。

### 11.2 ② M1–M6 / R-1 / C-1 / C-2 闭环状态

| 项 | 内容 | 闭环证据（本次独立复核） |
|---|---|---|
| **M1** | `libs/pharmacy` 未转发 `integration.db.*` → 真库静默 skip | ✅ `libs/pharmacy/build.gradle.kts:23-29` 与 dining 逐字一致；含「无 5432 回落」负向验证 |
| **M2** | `/healthcare/v1` 新前缀缺全等 scope 断言 | ✅ `inChildServiceScope`(`:450`) / `inBaseDataScope`(`:464`) 双向 `assertEquals` |
| **R-1** | 受门禁**读路由**会被写路由全等断言误杀 | ✅ `writeMethodsForGate`(`:327`) 过滤 + `audit-logs` 移出写 scope + 单列断言 + GET 唯一性断言；`routes.size()` 已 172 |
| **C-1** | `inBaseDataScope` 断言文案口径 | ✅ `:483` 文案与 scope 一致 |
| **C-2** | 注释夸大能力 | ✅ `:293-296` 改为事实描述，并把 F3 两件必做事内嵌代码现场 |
| **M3** | E1/E3/F2 私有 `mapUniqueViolation` 弱判定（S6 同型复发） | ✅ 三处已**委托**共享助手：`ChildServicePackageService.kt:593-600`、`InvoiceService.kt:378-385`、`BaseDataService.kt:732-739` 均为 `isUniqueViolation(error, setOf(constraint))`；约束名不变、调用点不变 |
| **M4** | F3 请求级 403 断言缺失 + `AuditLogRoutesTest` 注释失实 | ✅ 新增 `apps/aceso/.../AuditLogAccessTest.kt`（4 例：无码 403 且不触库 / 有码 200 空页 / 未认证 401 / **403 尝试入账**，用生产同款 `buildApiRouter` + 桩 IdP/Nexus）；`AuditLogRoutesTest.kt:35-39` 注释已改为事实描述并指向该文件 |
| **M5** | F2/F3 缺真库测试 | ✅ `BaseDataContentIntegrationTest`（6 用例：V539 约束索引、**库空等价常量基线逐字段相等**、写入覆盖→清空回基线、CRUD + 白名单 400 + 空列表）；`AuditLogIntegrationTest`（8 用例：V540 三索引列类型、声明表 GET 恰一条、写方法各落一条 + **GET 零落库**、**403 尝试入账**、读路由门禁无码 403 / 有码 2xx + GET 零入账、**PHI 不落请求体**、过滤前缀/闭区间/空结果）。唯一未覆盖 = 盲区 7（见 §11.1 例外 5） |
| **M6** | `enforced_by` 单值已不准确 | ✅ 按调度者裁定：`enforced_by` **保持单值 `"nexus"`**（语义仍真、不完整）；`base_data:manage` 目录描述已修正为「基础数据管理（组织主数据：Nexus 部门；临床内容主数据：疫苗类型、发育量表）」（`DeclaredPermissions.kt:437-439`）；计划 **R19** 登记为已知并接受的偏差 + 后续单列任务（多值升级或向后兼容附加字段）。**049 D1 预先规定的「升级多值」程序未被违反——因为本轮明确不做升级，而是显式接受偏差并登记后续项**，该处置自洽 |

**结论：M1–M6 与 R-1/C-1/C-2 全部闭环，无遗留待办。**

### 11.3 ③ 是否还有阻塞交付的残余项

**无阻塞交付的残余项。** 交付前提仅剩一条**通过性条件**（非评审缺口）：

- **§8.8 全量默认验证结果**（5 模块 DB-free + 前端构建）正由调度者串行执行并将并入 §13。要求：054 **自身**新增/变更的测试全绿；R17 两簇既有基线失败以「同基线复现 + 落在 054 未改动类」为豁免并逐项列出计数。**我未独立复跑**（构建车道归开发/调度者，且硬约束禁止我占用构建车道）。

两条**非阻塞口径项**（不影响交付，建议在 §13 一并记录）：
- **R-B**：§8.10 点名的 `ChildRoleWorkflowIntegrationTest` 不存在，实际为 12 个按链真库类（覆盖意图已达成）→ §13 改述验收对象即可。
- **R-C**：本备忘 §9/§10/§11 的真库数字（137/137、6/6、9/9、27/27、61/61、E/F/C 各链、M5 的 5/5 与 7/7）**均采信调度者回报**；我受硬约束未启停数据库/服务，未独立复跑。§13 附 executed/skipped 计数 + 收摊四证据后，该证据链即完整。

### 11.4 ④ 残余风险清单（供写入计划 §13）

| # | 风险 | 来源 | 建议处置 | 严重度 |
|---|---|---|---|---|
| **R-A** | **F3 盲区 7「审计落库失败不阻断主请求」仅 DB-free 覆盖**（复用同一 Pool 无法稳定注入失败） | §10.3 / M5 | 保留现状 + 类 KDoc 说明；若后续需要，可在集成测试中用**独立 Pool 指向只读连接**或注入故障 recorder 覆盖 | 低 |
| **R-B** | **§8.10 点名的 `ChildRoleWorkflowIntegrationTest` 不存在**（实际为 12 个按链真库类） | §11.1 | §13 把 §8.10 的验收对象改述为按链真库类清单；或补建聚合类（非必需） | 低（口径） |
| **R-C** | **真库数字采信调度者回报**，评审未独立复跑 | §11.3 | §13 附每个真库类 executed/skipped 计数 + 收摊四证据（`podman ps -a`、`podman pod ls`、`podman network ls`、`ss -lntp \| grep 55432`） | 低（流程） |
| **R-D** | **三处 `mapUniqueViolation` 仍为各服务私有扩展**（虽已委托共享助手） | §10.2 M3 | 后续把该扩展抽到 `libs/healthcare`（或 `libs/database`）公共 helper，避免第四份复制；并在 `service-vertx-kotlin/AGENTS.md` 固化「唯一冲突一律走 `isUniqueViolation`，禁止 message 子串匹配」 | 低 |
| **R-E** | **C 链复用 `nursing:document` 的越权面**（`trainer01` 因此可写护理评估/计划/不良事件/交接班） | L6 | 已接受（零新增码）；回退方案 = 新增 `childcare:manage` 并单列迁移（Q3 备选） | 中（已接受） |
| **R-F** | **A4b「非儿童必须有 ACTIVE `ELDERLY_CARE` 入住」仅服务层校验**（跨 schema CHECK 不可行） | R14 | 服务层 + 测试锁定；直接改库绕过服务的场景由运维约束；如需 DB 兜底另立任务 | 中（已接受） |
| **R-G** | **配餐口径依赖隐式 INNER JOIN 排除**（`RosterService.kt:239,494`），未来若放宽儿童入住口径可能误纳入 | R16 | 本轮标注为有意口径（§8.1 rev7）；后续改为显式 `person_type`/`service_type` 谓词或补注释 | 中 |
| **R-H** | **`enforced_by` 对双承载码不完整**（`base_data:manage` / `access:manage`） | R19 | 本轮接受；后续按 049 预授权程序升级多值（同步 046 D3/049 D1/角色页）或采用向后兼容附加字段，**需单列任务** | 中（已接受） |
| **R-I** | **门禁 403 响应体为英文 `forbidden` + `required_permission`**（非中文） | R20 | **046 既有、非 054 引入、本轮不改**；若产品要求中文错误体需单列任务（触及 046 冻结契约 + `problem-message.ts` + 全部 403 断言消费方） | 中（已接受） |
| **R-J** | **两簇既有基线失败**（healthcare/aceso 7 项 + nursing 25 项，与 054 无关） | R17 | §13 逐项列出计数与豁免依据；**不得**据此放宽 054 自身断言；建议单列修复任务 | 中（既有） |
| **R-K** | **跨库 schema 隐藏依赖**：pharmacy `confirm` 经 `ReturnableQuantities.kt:72` 读 `nursing.medication_administrations`，仅 pharmacy 迁移的库会 500 | R18 | 既有技术债、非 054 引入；建议单列任务评估显式声明 `:libs:nursing` 依赖 / 端口注入给药量 / 文档标注运行时前提 | 中（既有） |
| **R-L** | **053 的 6 个新增 `Child*Service.kt` 含 `org.jooq`**，与 AGENTS「新增持久化默认原生 SQL」相悖 | §9.7 / 053 R1 | 已在 053 R1 登记，**不并入 054**；建议总交付单列并排期收敛 | 中（跨批次） |
| **R-M** | **`docs/architecture.md` 的 healthcare「Incubating」标签与 `apps:aceso` 实际依赖不一致** | §1.0 硬边界 3 | 独立后续项修正文档 | 低 |

### 11.5 评审独立性与证据边界（声明）

1. **只读**：全程未改生产代码、未改计划、未启停任何服务/数据库、未占用构建车道（`git status` 中本备忘为唯一由我写入的文件）。
2. **证据来源**：源码/迁移/测试文件的逐条静态核对（我直接读取）；`git status`/`git diff` 结构核对；计划 §5/§7/§8/§11 与实现的逐条比对。
3. **未独立验证**：真库执行结果与构建结果均采信调度者/开发回报（受硬约束所限）；已在 §11.3 R-C 登记，并要求 §13 附 executed/skipped 计数与收摊证据。
4. **口径一致性**：本备忘的「受控例外」与计划 §8.8/§12 的 R14/R16/R17/R18/R19/R20 一一对应，未新增或放宽任何例外。
5. **未发现**任何越权声明、fail-open 写路由、`org.jooq` 泄漏进原生 SQL、`ON CONFLICT` 谓词错配、或迁移号冲突。

### 11.6 最终结论

> **054（A/B/C/D/E/F 六链）达到计划 §8 验收口径（含已声明的受控例外），M1–M6 与 R-1/C-1/C-2 全部闭环，无阻塞交付的残余项。**
>
> 交付放行条件：调度者 §8.8 全量默认验证结果为「054 自身测试全绿 + R17 既有基线失败按豁免逐项列出」。
>
> 残余风险 13 项（R-A…R-M）已列于 §11.4，其中 R-E/R-F/R-H/R-I 为**已接受**、R-J/R-K/R-L 为**既有技术债（非 054 引入）**、R-A/R-B/R-C/R-D/R-M 为**低危后续项**。
>
> —— 架构师（独立评审），2026-10-07

---

## 附录 A：事实证据清单（本次只读核查）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `libs/trainings` 包名为单数 `com.ovaphlow.crate.training`，无迁移目录 | `libs/trainings/src/main/kotlin/com/ovaphlow/crate/training/TrainingRoutes.kt:1`；`find libs/trainings -path "*db/migration*"` → 空 |
| 2 | trainings 属 Trainova legacy jOOQ，不新增 jOOQ 用法 | `service-vertx-kotlin/AGENTS.md`「Product Boundaries」「Module Changes」 |
| 3 | `apps:aceso` 依赖 healthcare/inventories/pharmacy/nursing/dining | `service-vertx-kotlin/apps/aceso/build.gradle.kts:6-15` |
| 4 | healthcare 被挂载（incubating 标签过时） | `apps/aceso/.../Main.kt:250`；`docs/architecture.md:21` |
| 5 | 集中式门禁由 `DECLARED_PERMISSIONS` 驱动 | `Main.kt:225-231`；`DeclaredPermissions.kt:77-254` |
| 6 | 注册顺序护栏（BodyHandler→认证→门禁→子路由） | `Main.kt:207,213,225,233+` |
| 7 | 现有权限码与目录 | `DeclaredPermissions.kt:282-313`（常量）、`:340-371`（目录）、`:322-331`（Nexus 侧） |
| 8 | 门禁「声明=接线」护栏测试 | `apps/aceso/src/test/.../DeclaredPermissionsTest.kt:283` |
| 9 | 康复选码映射（`rehab:execute`） | `DeclaredPermissions.kt:12,22-27`；`Main.kt:269-282`（注入式门禁） |
| 10 | `REHABILITATION`/`LIVING_CARE` 是合法 task_type | `libs/nursing/.../V400__create_nursing_tables.sql:96`；`V405__extend_task_type_for_medical_orders.sql` |
| 11 | nursing 计划/修订/事件表 | `V400:57,73`（plans/items）、`V406`（care_plan_revisions）、`V408`（incidents/handovers） |
| 12 | dining 表结构与过敏列 | `libs/dining/.../V600__create_dining_tables.sql:15-35`（`dining_diet_profiles.allergies`）、`:120`（meal_executions） |
| 13 | 计费链在 healthcare | `V513`–`V523`（deposit/fee_items/bills/payments/settlement/reversal） |
| 14 | 药品目录本体在 inventories（`public.materials`） | `libs/inventories/.../V200__create_inventory_tables.sql`；`Main.kt` 的 `DrugCatalogPort` 适配器 |
| 15 | Flyway 位置 `classpath:db/migration`（各 lib 贡献同一库） | `libs/database/.../DatabaseConfig.kt:58` |
| 16 | 现有最大迁移号 | dining V600；nursing V408；pharmacy V303；healthcare V533（V534/V535 未落盘）；inventories V204（V202 空缺） |
| 17 | 053 预留 V534/V535 | `docs/plans/053.aceso-child-health-doctor-workflows.md:48,52`（B3=V534、D1=V535）、`:79` |
| 18 | 053 已落盘 V531–V533（含 V532 单作者合并） | `libs/healthcare/.../V531,V532,V533`；`V532` 同时含体检诊断列与 `developmental_screenings.checkup_id` |
| 19 | legacy jOOQ 仍在各 lib 存在（新文件禁用） | `grep -rl org.jooq libs/{dining,nursing,pharmacy,healthcare,inventories}` → 6/12/5/18/4 个文件 |
| 20 | 费用功能只做状态标记（不接支付） | `docs/plans/020.aceso-elderly-billing-chain-repair.md:23` |
| 21 | 操作人服务端派生 | 042 计划；`DeclaredPermissions.kt` 相关路由注释与 046 §6.6 |
| 22 | `aceso.ts` 5891 行、`Sidebar.tsx` 168 行、`HealthcareRoutes.kt` 1523 行、`DiningRoutes.kt` 328 行 | `wc -l` 实测 |

---

## 附录 B：待裁定项（提交调度者）

> 本表为 §1–§7 起草时的预置项；**评审后的最终裁定请求以 §8.1 与 §8bis 为准**。状态标注见下表。

| # | 事项 | 建议 | 状态 |
|---|---|---|---|
| B1 | healthcare 054 起始号 | **V536**（保留 V534/V535 给 053） | ✅ **已解除** — 计划 §0/§11.1 L1 冻结 healthcare **V536–V540** |
| B2 | 药师过敏交叉检查与 053 的重叠处理 | 054 复用 053 引擎 | ✅ **已解除** — 计划 §3 De/§11.2 Q5 明确「只接线不重建」，D3 依赖 053 D1/D2 |
| B3 | 管理员审计是否落表 | 原建议不落表 | ⚠️ 计划选择落表（healthcare V540 + `AuditMiddleware`），方案自洽、R8 有缓解，**不再作为阻塞**；仅需精确注册位置（§8.3 N17） |
| B4 | B/C 链是否先拆独立子路由文件 | 建议拆 | ⚠️ 计划未拆：B/C 同落 `libs/nursing` 且共用 `NursingRoutes.kt` → §6 已规定「B 合入后再开 C」；仍建议按子路由文件拆分以降串行压力 |
| B5 | `docs/architecture.md` 的 healthcare「Incubating」标签与 `apps:aceso` 实际依赖不一致 | 独立后续项修正文档 | ⏸ 未处理，不阻塞 054 |
| ~~BL1~~ | ~~康复师 B 链归属~~ | — | ✅ **已解除** — Lead 裁定 L3 冻结为 `libs/nursing`（计划 §3 Da/Db 已落） |
| **BL2** | **`DeclaredPermissionsTest` 覆盖缺口**（会把正确声明判红、放过 healthcare 漏登记） | 批准「集成」行写权限并扩测试 | 🔴 **阻塞**（§8.1，最紧） |
| **BL3** | **F1 平台管理员豁免**（046 双轨引导者） | 批准 `platform_admin` 全量可见 | 🔴 **阻塞**（§8.1） |
| **BL4** | **放量协议章节**（六角色 role_code → code → subject） | 新增 §9.5 | 🔴 **阻塞**（§8.1） |
| **BL5** | **A5 周报表无迁移作者** | 由 A4 的 V602 一并创建（推荐）或 A5 用 V603 | 🔴 **阻塞**（§8.1） |
| **BL6** | **`ChildRehabPlan.patient_id` 存储层不存在** | 明确为 join 派生字段 | 🔴 **阻塞**（§8.1） |
| **BL7** | **B2 两条并行执行链，进度口径二义** | 二选一，推荐「新表为唯一事实源」 | 🔴 **阻塞**（§8.1） |
| ~~N9~~ | ~~`libs/childcare` 新 lib 三选一~~ | — | ✅ **已解除** — 计划已改为落 `libs/nursing`，不新建 lib |
