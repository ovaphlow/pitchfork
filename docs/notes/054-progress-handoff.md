# 054 进度交接（暂停点存档）

- 存档时间：2026-10-06 23:0x（Asia/Shanghai）
- 暂停原因：用户要求「保存进度，去睡觉」
- 恢复方式：对会话说「继续」即可（Lead 会重新武装 goal 并按本文件继续派工）
- 计划真源：[`docs/plans/054.aceso-child-health-role-workflows.md`](../plans/054.aceso-child-health-role-workflows.md)（**rev8**，659 行）
- 架构约束与评审：[`docs/notes/054-architecture-decisions.md`](054-architecture-decisions.md)（§1–§9，720 行）
- 测试基建与验收规范：[`docs/notes/054-qa-plan.md`](054-qa-plan.md)（§9.2/§9.4/§9.6）
- 开发基线：[`docs/notes/054-dev-baseline.md`](054-dev-baseline.md)

---

## 0. 恢复后第一件事（重要）

**门禁测试当前为红，属已知中间态**：E3（task-30）被中断时，`/invoices` 两条写路由**已接线且已登记**，但 `DeclaredPermissionsTest.kt` 的计数行**未同步**。

- 实测：`./gradlew :apps:aceso:test --offline --no-build-cache --rerun --tests '*DeclaredPermissions*'` → **6 tests / 2 failed**
  - `the declared list covers every wired permission code`（CENTRAL 计数）
  - `the access endpoint exposes the declared permissions`（`routes.size()`）
- 现值：CENTRAL **162**、`routes.size()` **165**；E3 追加 2 条 → **应为 CENTRAL 164 / routes.size() 167**（`DeclaredPermissions.kt:245-246`）。
- 处置：恢复后由 **dev2**（E3 owner）按实际值 rebase 计数行（只改数字，不改结构），再重跑门禁确认全绿。

---

## 1. 状态总览

| 链 | 任务 | 状态 | 验证 |
|---|---|---|---|
| A 营养师 | A1–A5 + A3 口径修订 | ✅ 交付 | dining 真库 **137/137**（skip 0） |
| A 营养师 | A4b 儿保过敏原可达（V603） | ✅ 交付 | 真库 **6/6**（含 `leftJoin` 修复护栏） |
| A 营养师 | A6 工作台 UI | ✅ 交付 | 前端 156/156 → 后续累计 236/236 |
| F 管理员 | F1 权限感知（含平台管理员豁免） | ✅ 交付 | 前端单测全绿 |
| B 康复师 | B1–B4 后端（V409–V412） | ✅ 交付 | 真库 **9/9 + 27/27 + 61/61** |
| B 康复师 | B5 工作台 UI | ✅ 交付 | 前端 184/184 |
| C 保育员 | C1/C2 后端（V413/V414） | ✅ 交付 | 真库 **81/81**（含并发 409、跨 UTC 日界） |
| C 保育员 | C3 工作台 UI | ✅ 交付 | 前端 236/236 |
| D 药师 | D1 用量引擎 + D2 规则库（V304） | ✅ 交付 | 真库 **162/162**（含 8 条 CHECK、无 FK 弱关联） |
| D 药师 | D4 工作台 UI + D4 增补（过敏交叉检查接入） | ✅ 交付 | 前端 211/211；打包产物含 `drug-allergy-check` |
| E 收银 | E1 套餐与付费状态（V536） | ✅ 交付 | 后端 21/21；真库待跑 |
| E 收银 | E2 结算与收款（V537） | ✅ 交付 | 后端 18/18；真库待跑 |
| E 收银 | **E3 发票台账（V538）** | ⚠️ **在途（被中断）** | 路由+服务+声明已落盘；**计数行未同步 → 门禁红** |
| E 收银 | E4 工作台 UI | ⬜ 未开始（task-39，依赖 E3） | — |
| D 药师 | **D3 发药前过敏接线** | ⚠️ **在途（被中断）** | `DispenseService/DispenseRoutes` 已改、2 个测试类已建；**未回报验证结果** |
| F 管理员 | F2 基础数据维护（V539） | ⬜ 未开始（task-37，依赖 E3） | — |
| F 管理员 | F3 审计日志（V540） | ⬜ 未开始（task-38，依赖 F2） | — |
| F 管理员 | F 链前端（BaseDataPage / AuditLogPage） | ⬜ 未开始 | — |
| 评审 | 架构师 §9 实现评审（A/F1/B/D） | ✅ 完成 | 有条件通过；M1/M2/R-1/C-1/C-2 **全部闭环** |
| 评审 | 架构师增量评审（C/E/F 链） | ⬜ 未开始 | 6 项就绪清单见架构备忘 §9.9 |
| 集成 | 计划 §13 验收记录 | ⬜ 未填写 | 需附 executed/skipped 计数 + 收摊四证据 |

**代码量**：新增文件 174 个、修改文件 53 个（含 046–053 既有未提交改动，**均属用户，禁止 reset/checkout/clean**）。

**已落盘迁移**：dining `V601/V602/V603`、nursing `V409/V410/V411/V412/V413/V414`、pharmacy `V304`、healthcare `V536/V537/V538`（`V539/V540` 待做）。

---

## 2. 在途文件（被打断处，恢复时先读再改）

**E3（dev2）**：
- `libs/healthcare/src/main/resources/db/migration/V538__add_child_service_invoices.sql`（新增）
- `libs/healthcare/src/main/kotlin/.../InvoiceService.kt`（新增）
- `libs/healthcare/src/main/kotlin/.../HealthcareRoutes.kt`（`POST /invoices` :1510、`GET /invoices` :1516、`PATCH /invoices/:id/status` :1525）
- `DeclaredPermissions.kt:245-246`（2 条 `billing:write` 已登记）
- **缺**：`InvoiceRoutesTest.kt`、计数行同步、回报

**D3（dev）**：
- `libs/pharmacy/src/main/kotlin/.../DispenseService.kt`、`DispenseRoutes.kt`（已改）
- `libs/pharmacy/src/test/kotlin/.../DispenseAllergyCheckTest.kt`、`DispenseAllergyRoutesTest.kt`（已建）
- **缺**：验证命令结果、回报（含「DB-free 不可验证点」清单）

---

## 3. 关键裁定（已冻结，实施不得偏离）

- **L1 迁移号**：dining `V601–V603`、nursing `V409–V414`、pharmacy `V304`、healthcare `V536` 起（**V534/V535 归 053**）；inventories 不改；Trainova `V5–V7` 不动。
- **L2 过敏原**：以 `dining_diet_profiles.allergies` 为膳食域操作记录，**不建第二张主数据表**；053 的 `patient_allergies`(V535) 为临床主数据。
- **L3 康复师**：落 `libs/nursing`，**不得落 `libs/trainings`**（Trainova legacy，禁止跨产品依赖）。
- **L4 权限码零新增**：`dining:manage` / `nursing:document` / `rehab:execute` / `pharmacy:dispense` / `billing:write` / `base_data:manage` / `access:manage`。
- **L6** 保育员复用 `nursing:document` 的越权面（trainer01 获护理文书写权）**已接受**，登记为残余风险。
- **L7/L8** B2 会话表为「是否完成训练」唯一事实源；`patient_id` 由 join 派生，**不给 `nursing_plans` 加列**。
- **L11** 门禁写路由比对只取写方法；F3 的 `GET /audit-logs` 是全系统**第一条受门禁读路由**，必须单列断言 + `routes.size()` +1。
- **L15** 审计只记元数据（`actor/method/path/status_code/resource_id/occurred_at`），**不落请求体**；读受 `access:manage`。
- **L17** A4b 由 Lead 立项（触发源：A6 验收发现「儿保过敏原不可达」）。
- **待用户覆写**：计划 §11.2 的 Q1–Q11（默认按推荐执行，不阻塞）。

---

## 4. 既有基线缺陷（R17，**不计入 054 验收**）

均**单独运行可复现**、全部落在 054 未改动的类：

- **簇 1（healthcare/aceso，7 failures / 35 tests）**：`MedicalOrderIntegrationTest`（期望 400 实际 **401**）、`DispenseIntegrationTest`×2、`DoseQuantityIntegrationTest`、`MedicationAdministrationIntegrationTest`、`ReturnIntegrationTest`、`TextOrderRebindAdministrationIntegrationTest`（多为 `operator cannot be delegated`，042 fail-closed）。
- **簇 2（nursing，25 failures / 46 tests）**：`TaskExecutionServiceOverdueIntegrationTest` 9/9、`TaskExecutionRoutesOverdueTest` 8/12、`TaskExecutionRehabilitationIntegrationTest` 3/7、`NursingSupplyConsumptionIntegrationTest` 3/10、`ServicePeriodRoutesElderlyAdmissionTest` 2/8。
- 证据：`docs/notes/054-qa-plan.md` §9.4。**建议单开基线修复任务**；**不得据此放宽 054 自身断言**。

---

## 5. 环境与收摊（本次暂停已完成）

```
无遗留容器 / 无遗留 pod / 无遗留网络 / 55432 无监听
```
- 隔离测试库 `pitchfork-aceso-test-db` 已 `down`（我用 `podman compose -p pitchfork-aceso-test -f apps/aceso/compose.test.yaml down`，未加 `-f/--force`）。
- 用户开发库 `pitchfork-aceso-db`（5432）状态未变（Up，健康），全程未触碰。
- 用户管理的服务（8420/8421/8422/4324/5432）全程未启停。
- 遗留一个 **非本会话启动**的 gradle 客户端/守护进程（PID 162217，已存在 >10 小时）——未处理，避免误伤用户环境。

**恢复真库验收时的口径**（重要）：
- 起容器：`PITCHFORK_TEST_DB_PASSWORD="$PITCHFORK_DB_PASSWORD" podman compose -p pitchfork-aceso-test -f apps/aceso/compose.test.yaml up -d`（**必须带该环境变量**，否则 `POSTGRES_PASSWORD` 回落为 compose 默认值，与 `apps/aceso/.env` 不一致）。
- 复用已有 55432 时，`aceso-test.sh` **不重置库** → 先显式 `DROP/CREATE aceso_test`。
- 脚本顺序硬约束：**healthcare 先、aceso 后**（`ignoreMigrationPatterns` 未真正抑制 missing 校验）。
- 断言规范见 `054-qa-plan.md` §9.6（`toInstant()` 比较 TIMESTAMPTZ、中文查询参数 `enc()`、`compose` 不叠 `onComplete`、残差 0、executed/skipped 计数）。

---

## 6. 恢复后的派工顺序（建议）

1. **dev2**：E3 收尾（补 `InvoiceRoutesTest.kt` + **计数行 rebase 164/167** + 门禁绿 + 回报）→ 然后 **F2（task-37，V539）** → **F3（task-38，V540）**。
2. **dev**：D3 收尾（验证 + 回报，含 DB-free 盲区清单）→ 之后接 **RosterService 显式谓词**小项（可选，R16）。
3. **dev-ui**：E3 完成后 **E4（task-39）** → 然后 **F 链前端**（`BaseDataPage.tsx` + `AuditLogPage.tsx`）。
4. **qa**：E 链真库窗口（E1/E2/E3，含 046 兼容回归）→ D3 真库 → F 链真库。
5. **架构师**：C/E/F 链**增量评审**（按 §9.9 的 6 项清单）。
6. **Lead（我）**：F3 落地时补 `GET /audit-logs` 单列断言 + 请求级 403 + `routes.size()`；收尾跑跨模块门禁与前端构建；填 §13；`down` 容器出四证据；总交付。

---

## 7. 未决 / 需用户决定

1. 计划 §11.2 **Q1–Q11** 待覆写（默认按推荐执行）。
2. **RosterService 隐式 INNER 排除**改为显式谓词（R16，后续项）。
3. **053 技术债**：6 个新增 `Child*Service.kt` 含 `org.jooq`，与 AGENTS「新增持久化默认原生 SQL」相悖（053 R1 已登记，**不并入 054**）。
4. 渲染级/真实账号 **E2E 未做**（本环境禁启停 4324/8422）：各工作台页面与 409/403 文案仅以构建 + 单测覆盖。
5. 两簇既有基线缺陷是否单开修复任务。
