# Aceso 养老收费链路修复 — 交接说明

日期：2026-09-28（夜）
适用计划：`docs/plans/020` ~ `docs/plans/023`

## 一句话状态

四份计划的**开发全部交付并通过默认验证**；**真实数据库集成/E2E 与用户手测尚未进行**；工作区改动**尚未提交**（约 100 条）。本文件供次日接手者（人或 agent）在**不依赖既有对话上下文**的情况下接着干。

## 交付内容与验证级别

| 计划 | 内容 | 默认验证证据（可复现） | 未验证 |
|---|---|---|---|
| [020](../plans/020.aceso-elderly-billing-chain-repair.md) | 费用项目维护页（`/dashboard/fee-items` + 侧边栏入口）、收费域错误中文化（28 条映射）、结算押金核销（复用 `payments(押金)` + `deposit_records(核销)`）、V518 | 目标 6 类 **111/0**；前端构建 0 errors | 浏览器主线、真实核销写入 |
| [021](../plans/021.aceso-billing-settlement-write-off.md) | 收束未结余额显式标记（V519 两列）、区间最终账单改「先建后判」、`write_off_reason` 门禁（有未结无原因 → 409）、只读预览接口、汇总加 `write_off_amount` | 目标 **128/0**；整模块 483/0；前端构建 0 errors | 409 整笔回滚、并发收束 |
| [022](../plans/022.aceso-billing-precheck-and-arrears-filter.md) | 生成账单前置校验下沉（`precheck` 端点，前端删除复刻的取级规则）、欠费按入住过滤 | 目标 **149/0**；整模块 504/0；前端构建 0 errors | 真实 SQL 过滤与 `meta.total` 同源 |
| [023](../plans/023.aceso-discharge-decoupled-from-settlement.md) | **离院/去世不再自动收束账单**；删除 `BillService.settleEncounter`（静默结转路径消失）；离院成功提示下一步指引 | 目标 **157/0**；整模块 **512/0/57**；前端构建 0 errors | 离院零账单写入的真实残差 |

**当前统一基线（调度者独立复跑，非转述）**：

```
libs:healthcare:test --rerun  →  classes=27  tests=512  failures=0  errors=0  skipped=57
pnpm --filter @pitchfork/aceso build  →  26 page(s), astro check 0 errors / 0 warnings
git diff --check  →  clean
```

## 为什么这次改动重要（一句话版）

原实现里：新库 `fee_items` 恒空且**没有任何维护入口** → 生成账单必然失败并抛出英文原文；离院时**无条件自动收束账单** → 押金核销与减免留痕在正常流程里**永不可达**。这两条是本轮修复的核心，其余是配套。

## 明天开工顺序（建议）

### 第 1 步：跑真实数据库集成测试（唯一被跳过的验证）

57 个 skipped 用例全部是 `@EnabledIfSystemProperty` 门控的 PostgreSQL 集成类，需要一个独立可销毁的 `aceso_test`：

```bash
# 终端 A：起测试库（前台运行，Ctrl+C 停止；我的沙箱起不来 podman，需要你执行）
cd service-vertx-kotlin/apps/aceso
export PITCHFORK_TEST_DB_PASSWORD=pitchfork-test-only
podman compose -f compose.test.yaml up

# 终端 B：跑集成测试（完整参数见 docs/aceso-test-database.md）
cd service-vertx-kotlin
export PITCHFORK_DB_PASSWORD=pitchfork-test-only
./gradlew :libs:healthcare:test \
  -Dintegration.db.host=localhost -Dintegration.db.port=55432 \
  -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
  --tests "*IntegrationTest" --tests "*ElderlyCare*" --rerun-tasks
```

被跳过的 5 个类：`ElderlyDeathIntegrationTest`(10)、`ElderlyDischargeHandoverIntegrationTest`(14)、`HealthcareRoutesElderlyCareDischargeTest`(5)、`MedicalOrderIntegrationTest`(9)、`NursingIncidentHandoverIntegrationTest`(19)。

### 第 2 步：浏览器手测主线

App 与 API 已在运行（UI `4324`、API `8422`，开发库已应用 V518/V519）。主线：

1. 「系统设置 → 费用项目」空态 → 新增并启用：床位费 ×1、护理费 ×1（**名称必须与护理评估结果等级逐字一致**，页面有 4 个预设按钮）、伙食费 ×1
2. 「养老收费」→ 选长者 → 生成账单（应成功，明细含床位费×在院天数、护理费×分段天数、伙食费×折合餐次）
3. 缴费 → 结算核销（押金抵扣）
4. **办理离院** → 注意：现在**离院不再自动收束**，页面会提示「请到养老收费 → 结算收束完成账单收尾」
5. 回到「养老收费」→ 该长者「结算收束」按钮**现在可用** → 预览 → 核销 + 填减免原因 → 收束
6. 「押金管理」应出现 `核销` 记录；欠费列表汇总区出现第四块「收束减免」tile

### 第 3 步：补后置测试文件

四份计划各自在「涉及文件清单」里指定了后置测试文件名（`*IntegrationTest.kt` / `e2e/*.spec.ts`）。这些文件**尚不存在**，需要测试角色在授权环境里创建并执行。

### 第 4 步：提交

见文末「提交建议」。

## 悬空项（已知、未处理）

1. **财务读取端点的认证缺口（需要你确认暴露面）**：`apiRouter`/`mainRouter` 对 `/crate-api/*` **没有全局认证中间件**；`GET /payments/arrears`、`/payments/summary`、`/bills/:id/payments` 挂了 `paymentAuthHandler`，但 `GET /fee-items`、`/fee-items/:id`、`/encounters/:id/bills`、`/bills/:id`、`/encounters/:id/deposits` **没有**。若 8422 可被外部直连，账单/押金/价目表可未授权读取。**先确认有没有反向代理兜底**。
2. **`AdmissionsPage.tsx:918`** 死亡确认文案用「收束该入住全部医嘱、任务与照护周期」——「收束」现在有了「结算收束」的特定含义，可能歧义。属既有用户文案，未擅自改动。
3. **历史已自动收束的账单**：本轮变更前被离院静默结转的欠费，其 `outstanding_amount` 一律为 `0`，**事实不可考**（刻意不回填、不猜测）。
4. **在住期间押金核销**：未做（用户按"状态标记优先"的口径降级为可选）。当前押金只能在结算收束时核销。
5. **`fee_items` 缺少 DB 唯一约束**：「同一分类只能有一条启用项」目前只由应用层在生成账单时校验（前端有同名提示）。建议加部分唯一索引 `(category, name) WHERE status='启用'`，需一次迁移 + 数据清理。
6. **`settleEncounter` 已删除**，若你在别处（分支/未推送的工作）引用过它，需要改用 `HealthcareService.settleEncounterBilling`。

## 关键设计决定（避免重复讨论）

1. **费用功能的定位**：用于**标记数据状态与业务流程收束**，不做真实的付款/收款操作。因此「状态没被标记出来」比「钱没收回来」严重。
2. **离院/去世与结算收束解耦**（023）：离院只标记事实与照护收尾；账单收尾统一由「结算收束」承担，**且必须有门禁**。
3. **收束的唯一入口**是 `POST /encounters/:id/billing-settlement`，顺序固定为「建区间最终账单 → 押金核销 → 未结判定 → 冻结」，同事务整笔回滚。**有未结余额而未提供 `write_off_reason` → 409。**
4. **核销复用既有两张台账**，不新建表：`payments(method=押金)` + `deposit_records(type=核销)`；`payments.method` 的 CHECK 已在 V518 放开，但**客户端提交白名单仍是 5 值**（提交「押金」→ 400），核销只能由收束路径产生。
5. **严格恒等式**：`应缴 − 已缴 = 欠费 + 减免`（减免 = 0 时退化为旧式）。计划初稿写错成不含减免项，已勘误。
6. **服务端语义不在客户端复刻**（022）：能否生成账单由服务端 `precheck` 判定，前端只渲染。
7. **前端收费域错误全部中文可操作**：后端英文文案与 `{"error": ...}` 形状未动，映射表在 `ui-astro/apps/aceso/src/components/billingMessages.ts`。

## 不要做

- **不要** 执行 `git checkout` / `reset` / `clean` / `stash`：工作区里有约 100 条未提交改动，包含四份计划的全部产出，且与你的在途工作（`service-idp-go/**` 等）**在文件内交织**。
- 不要重跑 `generateJooq` 除非 schema 又变了（重生成需要联网，`jooqCodegen` 依赖未缓存，`--offline` 会失败）。
- 不要手工修改 `service-vertx-kotlin/libs/healthcare/src/main/java/.../gen/**`。

## 提交建议

工作区中「本次改动」与「你的在途改动」在 `HealthcareRoutes.kt`、`HealthcareService.kt`、`PaymentService.kt`、`DoctorClinicalTest.kt`、`packages/shared/src/aceso.ts`、`Sidebar.tsx`、`AdmissionsPage.tsx` 等文件里**交织**，无法按文件干净切分。两种做法：

- **A（简单）**：一个 WIP 提交，把当前工作区全部落盘，第二天再拆分整理。
- **B（较干净）**：分两个提交——先提交本次链路的文件（会连带你的改动），再把其余（`service-idp-go/**`、`docs/plans/010`、`DashboardPage`/`OrdersPage`/`OrdersCheckPage`/`PharmacyPage`/`DashboardLayout.astro`/`medical-orders.spec.ts`/`BreadcrumbTitle.tsx`）单独提交。

无论哪种，**先提交再开始第 1 步**，这样集成测试发现的任何问题都还有干净的回退点。
