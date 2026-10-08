package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router

/** `nursing_tasks.task_type` 中康复活动的取值（与 `V400/V405` 的 CHECK 约束、`TaskService.VALID_TASK_TYPES` 同源）。 */
internal const val REHABILITATION_TASK_TYPE = "REHABILITATION"

/** 045：康复活动执行（康复活动打卡）权限码。 */
internal const val REHAB_EXECUTE_PERMISSION = "rehab:execute"

/**
 * `nursing_tasks.task_type` → 打卡所需权限码（**满足任一即放行**，顺序决定 403 文案里的枚举顺序）。
 *
 * 只列需要与护理执行码分开的任务类型；未列出的类型走 [DEFAULT_EXECUTION_PERMISSION_CODES]。
 * REHABILITATION 同时接受 `nursing:execute` 是**有意为之**：接线前该码持有者能打卡康复活动，
 * 严格一码会把它们当场降权（静默回退）；康复岗拿到 [REHAB_EXECUTE_PERMISSION] 后
 * 不需要为此获得"记录给药"权限（`POST /executions/:id/administration` 仍只认护理执行码）。
 */
internal val EXECUTION_PERMISSION_CODES_BY_TASK_TYPE: Map<String, List<String>> = mapOf(
    REHABILITATION_TASK_TYPE to listOf(REHAB_EXECUTE_PERMISSION, NURSING_EXECUTE_PERMISSION),
)

/** 045：其它任务类型（以及读不到 task_type、记录不存在）沿用的护理执行码——与接线前语义一致。 */
internal val DEFAULT_EXECUTION_PERMISSION_CODES: List<String> = listOf(NURSING_EXECUTE_PERMISSION)

/**
 * 045：把"这条执行记录是什么任务"翻成"打卡需要哪些权限码"。纯函数，逐条锁定见 `DeclaredPermissionsTest`。
 *
 * `taskType == null`（记录不存在或查询失败）回落到护理执行码：不借 403/404 的差异泄露记录是否存在。
 */
internal fun executionPermissionCodesFor(taskType: String?): List<String> =
    EXECUTION_PERMISSION_CODES_BY_TASK_TYPE[taskType] ?: DEFAULT_EXECUTION_PERMISSION_CODES

/**
 * 一条"路由需要哪个权限码"的声明。
 *
 * 权限码的权威是 Nexus 角色目录；这里声明的是**产品侧把它们接到了哪些路由上**。
 *
 * 045：同一条 `method + path` 可以出现多次，表示"按资源满足其中任一码"（如打卡路由按
 * `task_type` 分别是 `nursing:execute` 与 `rehab:execute`）；只出现一条时就是"必须该码"。
 */
internal enum class PermissionWiring {
    /**
     * 046：由 `Main.kt` 的集中式声明驱动门禁注册——按本声明的 `method + path` 在 `apiRouter`
     * 上直接挂 `requireAnyPermission`，与自省端点共用同一份数据（声明即接线，天然不漂移）。
     */
    CENTRAL,

    /**
     * 046：由模块自己的注入式门禁承载（护理打卡需按执行记录的 `task_type` **读库选码**，
     * 集中式无法表达）。集中式注册必须跳过它，否则 403 的 `required_permission` 文案会退化。
     */
    INJECTED,
}

internal data class DeclaredPermission(
    val method: String,
    val path: String,
    val permissionCode: String,
    val wiring: PermissionWiring = PermissionWiring.CENTRAL,
)

/**
 * Aceso 已真实接线的权限码清单（fail-closed 判定）。
 *
 * 角色管理页用它把"还没有任何接口在用"的权限码标出来，避免继续堆积死配置。
 * **新增受保护路由时必须同时加到这里**：接线与清单共用同一个权限码常量，
 * 漂移由 `DeclaredPermissionsTest` 兜底。
 *
 * 046：清单按 [PermissionWiring] 分流——[PermissionWiring.CENTRAL] 的条目由 `Main.kt`
 * 集中注册门禁（临床 / 膳食 / 物资-采购 / 药房 / 收费 / 用药医嘱护士核对）；护理 3 条为
 * [PermissionWiring.INJECTED]，仍由 `TaskExecutionRoutes` 的注入式门禁承载，**不重复注册**。
 */
internal val DECLARED_PERMISSIONS: List<DeclaredPermission> = listOf(
    // —— 护理（045；INJECTED，由 TaskExecutionRoutes 的注入式门禁承载）——
    DeclaredPermission("POST", "/nursing/v1/executions/:id/administration", NURSING_EXECUTE_PERMISSION, PermissionWiring.INJECTED),
    DeclaredPermission("PATCH", "/nursing/v1/executions/:id/status", NURSING_EXECUTE_PERMISSION, PermissionWiring.INJECTED),
    DeclaredPermission("PATCH", "/nursing/v1/executions/:id/status", REHAB_EXECUTE_PERMISSION, PermissionWiring.INJECTED),

    // —— 054 B1 儿童康复：服务周期与训练计划（直接挂聚合路由，非子路由）——
    DeclaredPermission("POST", "/nursing/v1/child-rehab-periods", REHAB_EXECUTE_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/child-rehab-periods/:id/status", REHAB_EXECUTE_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/child-rehab-plans", REHAB_EXECUTE_PERMISSION),
    // 054 B2：训练执行（会话）——「是否完成训练」的唯一事实源（查询为读路由，不上门禁）
    DeclaredPermission("POST", "/nursing/v1/child-rehab-sessions", REHAB_EXECUTE_PERMISSION),
    // 054 B3：里程碑登记（读时状态派生与查询为读路由，不上门禁）
    DeclaredPermission("POST", "/nursing/v1/child-rehab-milestones", REHAB_EXECUTE_PERMISSION),
    // 054 B4：手册分发与签收（模板查询/台账查询为读路由，不上门禁）
    DeclaredPermission("POST", "/nursing/v1/child-rehab-handouts", REHAB_EXECUTE_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/child-rehab-handouts/:id/acknowledge", REHAB_EXECUTE_PERMISSION),
    // 054 C1：保育员活动日志与行为观测（复用 nursing:document，零新增权限码；查询为读路由）
    DeclaredPermission("POST", "/nursing/v1/childcare-activity-logs", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/childcare-behavior-observations", NURSING_DOCUMENT_PERMISSION),
    // 054 C2：班级日程排课/状态流转 + 环境安全巡查建查/追加整改项/关闭（复用 nursing:document；查询为读路由）
    DeclaredPermission("POST", "/nursing/v1/childcare-class-schedules", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/childcare-class-schedules/:id/status", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/childcare-safety-inspections", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/childcare-safety-inspections/:id/items", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/childcare-safety-inspections/:id/close", NURSING_DOCUMENT_PERMISSION),

    // —— 护理文书：服务周期、评估、计划、任务、访视日程（047 D1/D3）——
    // 路径带尾斜杠是**契约的一部分**：护理子路由以 `/xxx/*` 挂载 + 子路由内 `post("/")`，
    // 实际请求是带尾斜杠的集合路径（`/periods/`），不带尾斜杠是 404（见 047 §6.6 实测护栏）。
    DeclaredPermission("POST", "/nursing/v1/periods/", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PUT", "/nursing/v1/periods/:id", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/periods/:id/status", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/periods/elderly-admission", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/assessments/", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PUT", "/nursing/v1/assessments/:id", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("DELETE", "/nursing/v1/assessments/:id", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/plans/", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/plans/:id/status", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/tasks/", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/tasks/:id/status", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/visit-schedules/", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/visit-schedules/:id/status", NURSING_DOCUMENT_PERMISSION),
    // 护理文书语义但路由挂在 healthcare（复评/修订、护理记录、不良事件、交接班）
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/care-plan-revisions", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/nursing-records", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/nursing-records/:id/corrections", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/nursing-incidents", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/nursing-incidents/:incidentId/actions", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/nursing-incidents/:incidentId/close", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/nursing-shift-handovers", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/nursing-shift-handovers/:id/items", NURSING_DOCUMENT_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/nursing-shift-handovers/:id/receive", NURSING_DOCUMENT_PERMISSION),

    // —— 护理执行：新增的集中式写入（047 D2；045 的记录给药/打卡仍是 INJECTED）——
    // 集合与 `/converge-overdue` 的实际请求带尾斜杠；`converge-overdue` 另有合规的尾斜杠双入口，
    // 两个都声明（047 D3：宁多勿漏，漏一个就是一条裸奔写路由）。
    DeclaredPermission("POST", "/nursing/v1/executions/", NURSING_EXECUTE_PERMISSION),
    DeclaredPermission("PUT", "/nursing/v1/executions/:id", NURSING_EXECUTE_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/executions/generate", NURSING_EXECUTE_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/executions/converge-overdue", NURSING_EXECUTE_PERMISSION),
    DeclaredPermission("POST", "/nursing/v1/executions/converge-overdue/", NURSING_EXECUTE_PERMISSION),

    // —— 健康管理：体征录入/维护、体检、随访、慢病（048 D1/D2）——
    // 录入与流程维护归 `health:manage`；体征「复核/转诊」是临床判断，复用 `clinical:write`（048 D1）。
    DeclaredPermission("POST", "/healthcare/v1/vital-signs", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/vital-signs/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/healthcare/v1/vital-signs/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/health-checkups", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/health-checkups/:id/status", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/health-checkups/:id/members", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/health-checkups/:id/results", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/health-checkup-results/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/health-checkup-results/:id/to-vital-sign", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/health-checkup-results/:id/to-followup", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/followup-plans", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/followup-plans/:id/status", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/followup-records", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/chronic-diseases", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/chronic-diseases/:id/status", HEALTH_MANAGE_PERMISSION),
    // 体征复核/转诊：临床判断，归 046 的 `clinical:write`（与护士的 health:manage 独立）
    DeclaredPermission("POST", "/healthcare/v1/vital-signs/:id/review", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/vital-signs/:id/refer", CLINICAL_WRITE_PERMISSION),

    // —— 儿保日常功能链（052 P1–P5）：体检 / 成长 / 接种 / 异常通报 / 指导 ——
    // 全部复用 `health:manage`（052 D8 / 裁定 6：不新增 child:manage）；读路由不上门禁。
    DeclaredPermission("POST", "/healthcare/v1/child-health-checkups", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/child-health-checkups/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-growth-measurements", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/child-growth-measurements/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/healthcare/v1/child-growth-measurements/:id", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-vaccinations", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-vaccination-status/:patient_id/remind", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-health-notifications", HEALTH_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-guidance-records", HEALTH_MANAGE_PERMISSION),

    // —— 053 A2 发育筛查录入：筛查可护士执行，沿用 052 的 `health:manage`（053 §11 裁定 3）——
    // 判读/转诊（A3）将改用 `clinical:write`；本任务只登记录入写路由。
    DeclaredPermission("POST", "/healthcare/v1/child-developmental-screenings", HEALTH_MANAGE_PERMISSION),

    // —— 053 B2 健康问题转诊：临床判断/指令，复用 046 的 `clinical:write`（053 §11 裁定 3）——
    DeclaredPermission("POST", "/healthcare/v1/child-referrals", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/child-referrals/:id/status", CLINICAL_WRITE_PERMISSION),

    // —— 053 B1 儿保体检诊断：医生回写诊断，复用 046 的 `clinical:write`（053 §11 裁定 2）——
    DeclaredPermission("POST", "/healthcare/v1/child-health-checkups/:id/diagnosis", CLINICAL_WRITE_PERMISSION),

    // —— 053 A3 筛查结论判读：医生临床判断，复用 046 的 `clinical:write`（053 §11 裁定 3）——
    DeclaredPermission("POST", "/healthcare/v1/child-developmental-screenings/:id/conclusion", CLINICAL_WRITE_PERMISSION),

    // —— 053 B3 疾病防控指导：医生指令复用 052 的指导路径；**同路径双码**（health:manage / clinical:write，
    //    任一放行），`kind` 为业务字段而非权限边界（053 §5 + §11 裁定 5）——
    DeclaredPermission("POST", "/healthcare/v1/child-guidance-records", CLINICAL_WRITE_PERMISSION),

    // —— 053 D1 药物过敏史规范化：临床过敏史增改查删，复用 046 的 `clinical:write`（053 §3 Da）——
    // 写路由 POST/PATCH/DELETE 三条；GET 读开放，不登记。
    DeclaredPermission("POST", "/healthcare/v1/patient-allergies", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/patient-allergies/:id", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("DELETE", "/healthcare/v1/patient-allergies/:id", CLINICAL_WRITE_PERMISSION),

    // —— 主数据与登记：患者档案、入住/离院/去世、养老入住（049 D1/D2）——
    DeclaredPermission("POST", "/healthcare/v1/patients", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/patients/:id", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/encounters/:id", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/encounters/:id/discharge", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/encounters/:id/death", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/elderly-admissions", ADMISSION_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/elderly-admissions/:id/discharge-handover", ADMISSION_WRITE_PERMISSION),

    // —— 主数据：床位、费用项（049 D1；不并入 Nexus 的 base_data:manage）——
    DeclaredPermission("POST", "/healthcare/v1/beds", BED_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/beds/:id", BED_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/beds/:id/status", BED_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/healthcare/v1/beds/:id", BED_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/fee-items", FEE_ITEM_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/fee-items/:id", FEE_ITEM_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/fee-items/:id/status", FEE_ITEM_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/healthcare/v1/fee-items/:id", FEE_ITEM_MANAGE_PERMISSION),

    // —— 临床：医嘱、诊断、病程（046 D3/D4）——
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/orders", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/orders/:id/status", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/orders/close-expired", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/orders/bind-material-batch", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/diagnoses", CLINICAL_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/progress-notes", CLINICAL_WRITE_PERMISSION),

    // —— 护理执行：用药医嘱护士核对（046 D3；沿用既有码，走集中式）——
    DeclaredPermission("PATCH", "/healthcare/v1/orders/:id/nurse-check", NURSING_EXECUTE_PERMISSION),

    // —— 收费：账单、缴费、红冲、结算、押金（046 D3；接线既有未接线码 billing:write）——
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/bills", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/bills/:id/items", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/bills/:id/payments", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/bills/:id/reversal", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/billing-settlement", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/deposits", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/encounters/:id/deposits/refunds", BILLING_WRITE_PERMISSION),
    // 054 E1：儿保服务套餐与订阅（独立于 bills；复用 billing:write，不新增码）
    DeclaredPermission("POST", "/healthcare/v1/child-service-packages", BILLING_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/child-service-packages/:id", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-package-subscriptions", BILLING_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/child-package-subscriptions/:id/status", BILLING_WRITE_PERMISSION),
    // 054 E2：儿保服务结算单与收款（独立于 bills；复用 billing:write，不新增码）
    DeclaredPermission("POST", "/healthcare/v1/child-service-settlements", BILLING_WRITE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/child-service-settlements/:id/payments", BILLING_WRITE_PERMISSION),
    // 054 E3：儿保发票台账（独立于 bills；复用 billing:write，不新增码）
    DeclaredPermission("POST", "/healthcare/v1/invoices", BILLING_WRITE_PERMISSION),
    DeclaredPermission("PATCH", "/healthcare/v1/invoices/:id/status", BILLING_WRITE_PERMISSION),

    // —— 膳食：饮食档案、菜品、周菜单、配餐名单、用餐执行（046 D3）——
    DeclaredPermission("POST", "/dining/v1/diet-profiles", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/dining/v1/diet-profiles/:id", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/dining/v1/diet-profiles/:id/status", DINING_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/dining/v1/diet-profiles/:id", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/dishes", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/dining/v1/dishes/:id", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/dining/v1/dishes/:id/status", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/weekly-menus", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/dining/v1/weekly-menus/:id", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/dining/v1/weekly-menus/:id/status", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/weekly-menus/:id/items", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/weekly-menus/:id/copy", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/rosters/generate", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/rosters/:id/items", DINING_MANAGE_PERMISSION),
    DeclaredPermission("DELETE", "/dining/v1/rosters/:id/items/:itemId", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/executions", DINING_MANAGE_PERMISSION),
    // 054 A1：每餐膳食摄入记录（读路由不上门禁，046 D7）
    DeclaredPermission("POST", "/dining/v1/meal-intake-records", DINING_MANAGE_PERMISSION),
    // 054 A4：过敏原标记（覆盖既有 allergies 列）+ 特殊膳食申请状态机
    DeclaredPermission("PUT", "/dining/v1/diet-profiles/:id/allergens", DINING_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/dining/v1/special-diet-requests", DINING_MANAGE_PERMISSION),
    DeclaredPermission("PATCH", "/dining/v1/special-diet-requests/:id/status", DINING_MANAGE_PERMISSION),
    // 054 A5：营养周报显式生成（查询为读路由，不上门禁）
    DeclaredPermission("POST", "/dining/v1/nutrition-weekly-reports/generate", DINING_MANAGE_PERMISSION),
    // 054 A4b：儿保儿童无入住也可按 patient 维度标记过敏原（upsert 最小启用档案）
    DeclaredPermission("PUT", "/dining/v1/diet-profiles/by-patient/:patient_id/allergens", DINING_MANAGE_PERMISSION),

    // —— 物资与采购：物资目录、批次、入库、采购订单与收货（046 D3）——
    DeclaredPermission("POST", "/inventories/v1/materials", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/inventories/v1/materials/:id", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("DELETE", "/inventories/v1/materials/:id", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("POST", "/inventories/v1/lots", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("POST", "/inventories/v1/operations/inbound", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/purchase-orders", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/purchase-orders/:id", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/purchase-orders/:id/approve", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/purchase-orders/:id/cancel", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/purchase-orders/:id/close", INVENTORY_WRITE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/purchase-orders/:id/receipts", INVENTORY_WRITE_PERMISSION),

    // —— 药房：发药单全链与退药（046 D3；接线既有未接线码 pharmacy:dispense）——
    DeclaredPermission("POST", "/pharmacy/v1/dispenses/from-medical-order", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/dispenses", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/dispenses/:id/review", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/dispenses/:id/start", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/dispenses/:id/confirm", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/dispenses/:id/cancel", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/dispenses/:id/status", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/returns", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("POST", "/pharmacy/v1/returns/from-dispense", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/returns/:id/confirm", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/returns/:id/cancel", PHARMACY_DISPENSE_PERMISSION),
    // 050：领用申领与出库调拨同属药房作业链，复用 pharmacy:dispense（不新增码）
    DeclaredPermission("POST", "/pharmacy/v1/requisitions", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/requisitions/:id/approve", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/requisitions/:id/dispense", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/requisitions/:id/cancel", PHARMACY_DISPENSE_PERMISSION),
    // 054 D2：儿科用药规则库（写路由直接挂 PharmacyRoutes 聚合；复用 pharmacy:dispense，不新增码）
    DeclaredPermission("POST", "/pharmacy/v1/pediatric-dose-rules", PHARMACY_DISPENSE_PERMISSION),
    DeclaredPermission("PUT", "/pharmacy/v1/pediatric-dose-rules/:id", PHARMACY_DISPENSE_PERMISSION),
    // 054 F2：基础数据维护（疫苗类型 / 发育量表内容表建查改；写码 base_data:manage，不新增码）
    // 说明：base_data:manage 同时仍由 Nexus 承载 /shared/v1/departments 等写路由（NEXUS_ENFORCED_PERMISSIONS 不变）。
    DeclaredPermission("POST", "/healthcare/v1/base-vaccination-types", BASE_DATA_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/base-vaccination-types/:id", BASE_DATA_MANAGE_PERMISSION),
    DeclaredPermission("POST", "/healthcare/v1/base-developmental-scales", BASE_DATA_MANAGE_PERMISSION),
    DeclaredPermission("PUT", "/healthcare/v1/base-developmental-scales/:id", BASE_DATA_MANAGE_PERMISSION),
    // 054 F3：审计查询（**全系统第一条受门禁的读路由**，受 access:manage；054 §11.2 Q8）
    DeclaredPermission("GET", "/healthcare/v1/audit-logs", ACCESS_MANAGE_PERMISSION),
)

/** 040：产品声明的权限码目录条目。 */
internal data class PermissionCatalogEntry(
    val code: String,
    val description: String,
    /**
     * 043：该码由哪一层真实门禁——`"aceso"`（产品后端路由）/ `"nexus"`（共享服务写路由）/ `null`（尚未接线）。
     *
     * 权限的**权威目录**在 Nexus 角色目录；这里只声明"这套部署里哪些码真的接上了"。
     * 角色管理页用它把未接线的码标出来（040 D2：保留"先建角色再接线"的放量能力）。
     */
    val enforcedBy: String?,
)

/**
 * 043：Nexus 侧门禁声明。
 *
 * 这些路由不在 Aceso 的路由表里（它们是 Nexus 自己的写路由），但角色管理页必须能选到对应权限码，
 * 否则"把部门维护委派给业务角色"这件事无从配置。字段形状与 [DeclaredPermission] 对齐，
 * 只是多了 `code` 在前。
 */
internal data class NexusEnforcedPermission(
    val permissionCode: String,
    val method: String,
    val path: String,
)

internal const val PHARMACY_DISPENSE_PERMISSION = "pharmacy:dispense"
internal const val BILLING_WRITE_PERMISSION = "billing:write"

/** 046：临床写入（医嘱、诊断、病程）。 */
internal const val CLINICAL_WRITE_PERMISSION = "clinical:write"

/** 046：膳食管理（饮食档案、菜品、周菜单、配餐名单、用餐执行）。 */
internal const val DINING_MANAGE_PERMISSION = "dining:manage"

/** 046：物资与采购（物资目录、批次、入库、采购订单与收货）。 */
internal const val INVENTORY_WRITE_PERMISSION = "inventory:write"

/** 047：护理文书写入（评估、计划、任务、服务周期、访视日程、护理记录、交接班、不良事件）。 */
internal const val NURSING_DOCUMENT_PERMISSION = "nursing:document"

/** 048：健康管理写入（体征录入/修改/删除、体检、随访、慢病登记）。 */
internal const val HEALTH_MANAGE_PERMISSION = "health:manage"

/** 049：登记与入住写入（患者建档、办理入住、离院、去世、养老离院交接）。 */
internal const val ADMISSION_WRITE_PERMISSION = "admission:write"

/** 049：床位主数据管理（增删改与状态）。 */
internal const val BED_MANAGE_PERMISSION = "bed:manage"

/** 049：费用项主数据管理（增删改与状态）。 */
internal const val FEE_ITEM_MANAGE_PERMISSION = "fee_item:manage"

/** 043：账号与授权管理（角色、用户↔角色、用户↔部门）。 */
internal const val ACCESS_MANAGE_PERMISSION = "access:manage"

/** 043：基础数据管理（部门目录；后续物资/床位/费用项/仓库并入时沿用此码）。 */
internal const val BASE_DATA_MANAGE_PERMISSION = "base_data:manage"

/**
 * Nexus 侧已接线的写路由（043 §4.1 冻结矩阵）。
 *
 * 与 `service-nexus-shared/src/{roles,departments,subject_roles,subject_departments}/mod.rs`
 * 的 `write_router()` 一一对应；`GET /shared/v1/subject-permissions` 的"仅本人"收紧
 * 也由 `access:manage` 控制（查自己不需要该码，因此不计入本清单）。
 */
internal val NEXUS_ENFORCED_PERMISSIONS: List<NexusEnforcedPermission> = listOf(
    NexusEnforcedPermission(ACCESS_MANAGE_PERMISSION, "POST", "/shared/v1/roles"),
    NexusEnforcedPermission(ACCESS_MANAGE_PERMISSION, "PUT", "/shared/v1/roles/:id"),
    NexusEnforcedPermission(ACCESS_MANAGE_PERMISSION, "DELETE", "/shared/v1/roles/:id"),
    NexusEnforcedPermission(ACCESS_MANAGE_PERMISSION, "PUT", "/shared/v1/subject-roles/subjects/:id"),
    NexusEnforcedPermission(ACCESS_MANAGE_PERMISSION, "PUT", "/shared/v1/subject-departments/subjects/:id"),
    NexusEnforcedPermission(BASE_DATA_MANAGE_PERMISSION, "POST", "/shared/v1/departments"),
    NexusEnforcedPermission(BASE_DATA_MANAGE_PERMISSION, "PUT", "/shared/v1/departments/:id"),
    NexusEnforcedPermission(BASE_DATA_MANAGE_PERMISSION, "DELETE", "/shared/v1/departments/:id"),
)

/**
 * 产品权限目录：Aceso 声明的全部权限码。
 *
 * 放量协议要求"先建角色再接线"，所以目录必须覆盖所有码（含 043 起就保留的 Nexus 码）；
 * 它同时是"只能从清单里选"的唯一来源，从源头杜绝自由文本权限码。
 * 每个已接线的码都必须出现在这里，漂移由 `DeclaredPermissionsTest` 兜住。
 */
internal val PERMISSION_CATALOG: List<PermissionCatalogEntry> = listOf(
    PermissionCatalogEntry(
        NURSING_EXECUTE_PERMISSION,
        "护理执行（记录给药、非康复活动的执行状态更新、用药医嘱护士核对、执行记录增改与生成）",
        "aceso",
    ),
    PermissionCatalogEntry(REHAB_EXECUTE_PERMISSION, "康复活动执行（康复活动打卡）", "aceso"),
    PermissionCatalogEntry(
        NURSING_DOCUMENT_PERMISSION,
        "护理文书写入（评估、计划、任务、服务周期、访视日程、护理记录、交接班、不良事件）",
        "aceso",
    ),
    PermissionCatalogEntry(HEALTH_MANAGE_PERMISSION, "健康管理写入（体征录入、体检、随访、慢病登记）", "aceso"),
    PermissionCatalogEntry(CLINICAL_WRITE_PERMISSION, "临床写入（医嘱、诊断、病程、体征复核与转诊）", "aceso"),
    PermissionCatalogEntry(ADMISSION_WRITE_PERMISSION, "登记与入住写入（患者建档、办理入住、离院、去世、养老离院交接）", "aceso"),
    PermissionCatalogEntry(BED_MANAGE_PERMISSION, "床位主数据管理（增删改与状态）", "aceso"),
    PermissionCatalogEntry(FEE_ITEM_MANAGE_PERMISSION, "费用项主数据管理（增删改与状态）", "aceso"),
    PermissionCatalogEntry(DINING_MANAGE_PERMISSION, "膳食管理（饮食档案、菜品、周菜单、配餐名单、用餐执行）", "aceso"),
    PermissionCatalogEntry(INVENTORY_WRITE_PERMISSION, "物资与采购（物资目录、批次、入库、采购订单与收货）", "aceso"),
    PermissionCatalogEntry(
        PHARMACY_DISPENSE_PERMISSION,
        "发药与调拨（发药单全链、退药、领用申领审批与出库调拨）",
        "aceso",
    ),
    PermissionCatalogEntry(BILLING_WRITE_PERMISSION, "账单与费用写操作（生成、加项、缴费、红冲、结算、押金）", "aceso"),
    PermissionCatalogEntry(
        ACCESS_MANAGE_PERMISSION,
        "账号与授权管理（角色、用户↔角色、用户↔部门）",
        "nexus",
    ),
    // 054 F2（rev10 M6）：该码自 054 起为 **Nexus + Aceso 双承载**——Nexus 侧仍强制组织主数据
    // （`/shared/v1/departments`），Aceso 侧新增强制临床内容主数据（`/healthcare/v1/base-*` 4 条写路由）。
    // `enforced_by` 保持单值 `"nexus"`（语义仍为真、只是不完整）；多值升级见计划 R19（不在本轮）。
    PermissionCatalogEntry(
        BASE_DATA_MANAGE_PERMISSION,
        "基础数据管理（组织主数据：Nexus 部门；临床内容主数据：疫苗类型、发育量表）",
        "nexus",
    ),
)

/**
 * 只读自省端点：`GET /crate-api/access/v1/permissions`。
 *
 * 不属于任何业务域，只回答"这套部署里哪些权限码真的接上了"；受默认拒绝总闸保护（需登录）。
 */
internal object AccessRoutes {

    fun create(vertx: Vertx): Router {
        val router = Router.router(vertx)
        router.get("/permissions").handler { ctx ->
            val routes = JsonArray()
            DECLARED_PERMISSIONS.forEach { declared ->
                routes.add(
                    JsonObject()
                        .put("method", declared.method)
                        .put("path", declared.path)
                        .put("permission_code", declared.permissionCode),
                )
            }
            val wired = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet() +
                NEXUS_ENFORCED_PERMISSIONS.map { it.permissionCode }.toSet()
            val catalog = JsonArray()
            PERMISSION_CATALOG.forEach { entry ->
                val entryRoutes = JsonArray()
                DECLARED_PERMISSIONS
                    .filter { it.permissionCode == entry.code }
                    .forEach { declared ->
                        entryRoutes.add(
                            JsonObject().put("method", declared.method).put("path", declared.path),
                        )
                    }
                // 043：Nexus 侧门禁的路由同样列出来，形状与 Aceso 路由一致
                NEXUS_ENFORCED_PERMISSIONS
                    .filter { it.permissionCode == entry.code }
                    .forEach { declared ->
                        entryRoutes.add(
                            JsonObject().put("method", declared.method).put("path", declared.path),
                        )
                    }
                catalog.add(
                    JsonObject()
                        .put("code", entry.code)
                        .put("description", entry.description)
                        .put("wired", wired.contains(entry.code))
                        .put("enforced_by", entry.enforcedBy ?: "")
                        .put("routes", entryRoutes),
                )
            }
            ctx.json(
                JsonObject()
                    .put("permission_codes", JsonArray(DECLARED_PERMISSIONS.map { it.permissionCode }.distinct()))
                    .put("catalog", catalog)
                    .put("routes", routes),
            )
        }
        return router
    }
}
