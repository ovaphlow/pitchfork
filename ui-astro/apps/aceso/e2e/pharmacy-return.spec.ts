import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 012 Aceso 药房退药与可追溯退库闭环 — 浏览器验收。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL        Aceso UI 地址
 *   PLAYWRIGHT_API_BASE_URL    Aceso API 地址（/crate-api 根）
 *   PLAYWRIGHT_DB_*            隔离 PostgreSQL 连接
 *   PLAYWRIGHT_USERNAME/PASSWORD 测试账号
 *   PLAYWRIGHT_NEXUS_API_BASE_URL 可选；缺省按仓库配置所在 Nexus 地址
 *
 * 主线（对照 docs/plans/012.aceso-pharmacy-medication-return.md）：
 *   已发药(DISPENSED)明细 → 详情弹窗「创建退药」→ 待确认(PENDING)退药单 →
 *   「确认入库」同事务回库（INBOUND + source=PHARMACY_RETURN，成本锁定）→
 *   「取消」待确认退药单（不产生库存操作）；重复确认幂等；已确认单不可取消。
 */

const FIXTURE_PREFIX = "pw-pr-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;
const NEXUS_API_BASE =
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL ?? "http://192.168.0.109:8421/crate-api/shared/v1";

interface Encounter {
  id: string;
  encounter_no: string;
  patient_id: string;
  status: string;
}

interface AdmissionResponse {
  encounter: Encounter;
  nursing_period: { id: string; encounter_id: string; service_type: string; status: string };
}

interface MedicalOrder {
  id: string;
  order_type: string;
  order_content: string;
  doctor: string;
  status: string;
}

interface PharmacyDispenseItem {
  id: string;
  material_id: string | null;
  lot_id: string | null;
  dispensed_quantity: string | null;
  stock_operation_detail_id: string | null;
  unit_cost: string | null;
}

interface PharmacyDispense {
  id: string;
  dispense_no: string;
  patient_id: string;
  encounter_id: string | null;
  status: string;
  warehouse: string | null;
  items: PharmacyDispenseItem[];
}

interface PharmacyReturnItem {
  id: string;
  return_id: string;
  dispense_item_id: string;
  quantity: string | null;
  stock_operation_detail_id: string | null;
  unit_cost: string | null;
  total_cost: string | null;
}

interface PharmacyReturn {
  id: string;
  return_no: string;
  original_dispense_id: string;
  patient_id: string;
  return_reason: string | null;
  status: string;
  operator: string | null;
  created_at: string;
  confirmed_at: string | null;
  total_quantity?: string | null;
  items: PharmacyReturnItem[];
}

interface PharmacyReturnList {
  records: PharmacyReturn[];
  meta: { total: number };
}

interface InlineFixture {
  patientId: string;
  admission: AdmissionResponse;
  order: MedicalOrder;
  warehouse: string;
  materialId: string;
  lotId: string;
  materialName: string;
}

interface ApiResult<T> {
  status: number;
  body: T;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso pharmacy return tests`);
  return value;
}

function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

/** 清理由本文件创建的整套 fixture，包含药房发药/退药单、护理任务和库存操作。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");

    // 药房：先删退药明细/主表，再删发药明细，最后删发药主表
    await client.query(
      `DELETE FROM pharmacy.pharmacy_return_items item
       WHERE item.dispense_item_id IN (
         SELECT id FROM pharmacy.pharmacy_dispense_items item2
         WHERE item2.dispense_id IN (
           SELECT id FROM pharmacy.pharmacy_dispenses d
           WHERE d.patient_id LIKE $1 OR d.encounter_id LIKE $1
             OR d.metadata::text LIKE $1
         )
       )`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_returns r
       WHERE r.original_dispense_id IN (
         SELECT id FROM pharmacy.pharmacy_dispenses d
         WHERE d.patient_id LIKE $1 OR d.encounter_id LIKE $1
           OR d.metadata::text LIKE $1
       )`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_dispense_items item
       WHERE item.dispense_id IN (
         SELECT id FROM pharmacy.pharmacy_dispenses d
         WHERE d.patient_id LIKE $1 OR d.encounter_id LIKE $1
           OR d.metadata::text LIKE $1
       )`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_dispenses
       WHERE id LIKE $1 OR patient_id LIKE $1 OR encounter_id LIKE $1
          OR metadata::text LIKE $1`,
      [fixturePattern],
    );

    // 护理执行/任务/周期
    await client.query(
      `DELETE FROM nursing.nursing_visit_schedules schedule
       USING nursing.nursing_service_periods period
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE schedule.period_id = period.id
         AND (schedule.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_execution_consumptions consumption
       USING nursing.nursing_task_executions execution
       WHERE consumption.task_execution_id = execution.id
         AND (consumption.id LIKE $1 OR execution.id LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_executions execution
       USING nursing.nursing_tasks task
       LEFT JOIN nursing.nursing_service_periods period ON period.id = task.period_id
       LEFT JOIN healthcare.encounters encounter ON encounter.id = task.encounter_id OR encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id OR patient.id = encounter.patient_id
       WHERE execution.task_id = task.id
         AND (task.id LIKE $1 OR task.encounter_id LIKE $1 OR period.id LIKE $1
              OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_tasks task
       WHERE task.id LIKE $1 OR task.encounter_id LIKE $1
          OR task.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
          OR task.period_id IN (
            SELECT period.id FROM nursing.nursing_service_periods period
            WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
               OR period.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
               OR period.patient_id IN (SELECT id FROM healthcare.patients WHERE name LIKE $1)
          )`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.medical_orders order_row
       WHERE order_row.id LIKE $1 OR order_row.encounter_id LIKE $1
          OR order_row.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)`,
      [fixturePattern],
    );

    // 护理计划/评估等
    await client.query(
      `DELETE FROM nursing.nursing_plan_items item
       USING nursing.nursing_plans plan
       JOIN nursing.nursing_service_periods period ON period.id = plan.period_id
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE item.plan_id = plan.id
         AND (plan.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_plans plan
       USING nursing.nursing_service_periods period
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE plan.period_id = period.id
         AND (plan.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_assessments assessment
       WHERE assessment.id LIKE $1 OR assessment.encounter_id LIKE $1
          OR assessment.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
          OR assessment.period_id IN (
            SELECT period.id FROM nursing.nursing_service_periods period
            WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
               OR period.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
               OR period.patient_id IN (SELECT id FROM healthcare.patients WHERE name LIKE $1)
          )`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_service_periods period
       WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
          OR period.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
          OR period.patient_id IN (SELECT id FROM healthcare.patients WHERE name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.encounters encounter
       USING healthcare.patients patient
       WHERE encounter.patient_id = patient.id
         AND (encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.patients
       WHERE id LIKE $1 OR name LIKE $1`,
      [fixturePattern],
    );

    // 库存：先收集 API 生成的真实 ULID，再按物资/批次/操作关联删除
    const inventoryMaterials = await client.query<{ id: string }>(
      `SELECT id FROM public.materials
       WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1`,
      [fixturePattern],
    );
    const inventoryMaterialIds = inventoryMaterials.rows.map((row) => row.id);
    const inventoryOperations = inventoryMaterialIds.length
      ? await client.query<{ id: string }>(
          `SELECT DISTINCT detail.operation_id AS id
           FROM public.stock_operation_details detail
           JOIN public.materials material ON material.id = detail.material_id
           WHERE material.id = ANY($1)`,
          [inventoryMaterialIds],
        )
      : { rows: [] as Array<{ id: string }> };
    const inventoryOperationIds = inventoryOperations.rows.map((row) => row.id);

    await client.query(
      `DELETE FROM public.stock_operation_details
       WHERE id LIKE $1
          OR material_id = ANY($2)
          OR operation_id = ANY($3)`,
      [fixturePattern, inventoryMaterialIds, inventoryOperationIds],
    );
    await client.query(
      `DELETE FROM public.stock_operations
       WHERE id LIKE $1 OR metadata::text LIKE $1 OR id = ANY($2)`,
      [fixturePattern, inventoryOperationIds],
    );
    await client.query(
      `DELETE FROM public.stocks
       WHERE id LIKE $1 OR material_id = ANY($2)`,
      [fixturePattern, inventoryMaterialIds],
    );
    await client.query(
      `DELETE FROM public.lots
       WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id = ANY($2)`,
      [fixturePattern, inventoryMaterialIds],
    );
    await client.query(
      `DELETE FROM public.materials
       WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1 OR id = ANY($2)`,
      [fixturePattern, inventoryMaterialIds],
    );

    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM pharmacy.pharmacy_dispense_items item
          WHERE item.id LIKE $1 OR item.dispense_id IN (SELECT id FROM pharmacy.pharmacy_dispenses WHERE id LIKE $1 OR patient_id LIKE $1 OR encounter_id LIKE $1 OR metadata::text LIKE $1)) +
        (SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE id LIKE $1 OR patient_id LIKE $1 OR encounter_id LIKE $1 OR metadata::text LIKE $1) +
        (SELECT count(*) FROM pharmacy.pharmacy_return_items item
          WHERE item.dispense_item_id IN (SELECT id FROM pharmacy.pharmacy_dispense_items WHERE dispense_id IN (SELECT id FROM pharmacy.pharmacy_dispenses WHERE id LIKE $1 OR patient_id LIKE $1 OR encounter_id LIKE $1 OR metadata::text LIKE $1))) +
        (SELECT count(*) FROM pharmacy.pharmacy_returns r
          WHERE r.original_dispense_id IN (SELECT id FROM pharmacy.pharmacy_dispenses WHERE id LIKE $1 OR patient_id LIKE $1 OR encounter_id LIKE $1 OR metadata::text LIKE $1)) +
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM healthcare.medical_orders order_row WHERE order_row.id LIKE $1 OR order_row.encounter_id IN (SELECT encounter.id FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.encounter_no LIKE $1 OR patient.name LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_service_periods period LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id WHERE period.id LIKE $1 OR period.encounter_id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks task WHERE task.id LIKE $1 OR task.encounter_id LIKE $1 OR task.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_task_executions execution WHERE execution.id LIKE $1 OR execution.task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)) +
        (SELECT count(*) FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM public.lots WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stocks WHERE id LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stock_operations WHERE id LIKE $1 OR metadata::text LIKE $1 OR EXISTS (SELECT 1 FROM public.stock_operation_details detail JOIN public.materials material ON material.id = detail.material_id WHERE detail.operation_id = public.stock_operations.id AND (material.id LIKE $1 OR material.code LIKE $1 OR material.name LIKE $1))) +
        (SELECT count(*) FROM public.stock_operation_details WHERE id LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1))
      )::text AS residual`,
      [fixturePattern],
    );
    if (result.rows[0]?.residual !== "0") throw new Error("fixture cleanup left residual data");
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 在浏览器页面上调用 Aceso API；登录后会自动携带同站会话。错误(>=400)直接抛错。 */
async function api<T>(page: Page, path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const baseUrl = requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL);
  const result = await page.evaluate(
    async ({ baseUrl: requestBaseUrl, path: requestPath, method, body }) => {
      const token = localStorage.getItem("token");
      const response = await fetch(`${requestBaseUrl}${requestPath}`, {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        credentials: "include",
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let parsed: unknown = {};
      try {
        parsed = text ? JSON.parse(text) : {};
      } catch {
        parsed = { raw: text };
      }
      return { status: response.status, body: parsed };
    },
    { baseUrl, path, method: options.method ?? "GET", body: options.body },
  ) as ApiResult<T>;
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}: ${JSON.stringify(result.body)}`);
  return result.body;
}

/** 原样返回状态码与响应体（不抛错），用于断言 400/404/409 等错误口径。 */
async function rawApi<T>(page: Page, path: string, options: { method?: string; body?: unknown } = {}): Promise<{ status: number; body: T | { error: string } }> {
  const baseUrl = requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL);
  const result = await page.evaluate(
    async ({ baseUrl: requestBaseUrl, path: requestPath, method, body }) => {
      const token = localStorage.getItem("token");
      const response = await fetch(`${requestBaseUrl}${requestPath}`, {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        credentials: "include",
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let parsed: unknown = {};
      try {
        parsed = text ? JSON.parse(text) : {};
      } catch {
        parsed = { raw: text };
      }
      return { status: response.status, body: parsed };
    },
    { baseUrl, path, method: options.method ?? "GET", body: options.body },
  ) as ApiResult<unknown>;
  return result as { status: number; body: T | { error: string } };
}

async function ensureAuthenticated(page: Page) {
  // 强制从登录页建立 IDP 会话：已登录会自动跳到 /dashboard，未登录则提交表单。
  // 不依赖 /dashboard/pharmacy 的自动跳转——无会话时该页不会重定向到 /login。
  await page.goto("/login", { waitUntil: "networkidle" });
  if (!page.url().includes("/login")) return; // 已登录
  const identifier = requiredEnvironment("PLAYWRIGHT_USERNAME", LOGIN_IDENTIFIER);
  const password = requiredEnvironment("PLAYWRIGHT_PASSWORD", LOGIN_PASSWORD);
  await page.getByLabel("账号").fill(identifier);
  await page.getByLabel("密码").fill(password);
  await Promise.all([
    page.waitForURL(/\/dashboard/),
    page.getByRole("button", { name: "登录" }).click(),
  ]);
}

async function createPatient(page: Page, suffix: string): Promise<string> {
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}-patient` },
  });
  return patient.id;
}

async function createActiveAdmission(page: Page, suffix: string): Promise<AdmissionResponse> {
  const patientId = await createPatient(page, suffix);
  return api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: "2026-08-01T00:00:00+08:00",
    },
  });
}

/** 读取 Nexus 仓库下拉配置；测试前置数据必须使用真实仓库编码。 */
async function getWarehouseCode(page: Page): Promise<string> {
  const warehouses = await page.evaluate(async ({ nexusBase }) => {
    const response = await fetch(`${nexusBase}/settings?category=warehouse&page=1&page_size=100`, {
      credentials: "include",
    });
    if (!response.ok) throw new Error(`warehouse settings failed: ${response.status}`);
    const body = (await response.json()) as Array<{ code: string }>;
    return body;
  }, { nexusBase: NEXUS_API_BASE });
  const first = warehouses[0];
  if (!first?.code) throw new Error(`no warehouse configured at ${NEXUS_API_BASE}`);
  return first.code;
}

/** 创建启用批次管控的物资、批次和可用库存（含一次手工入库）。 */
async function createInventoryFixture(
  page: Page,
  suffix: string,
  warehouse: string,
): Promise<{ materialId: string; lotId: string; materialName: string }> {
  const materialCode = `${FIXTURE_PREFIX}${suffix}-MAT`;
  const material = await api<{ id: string; name: string }>(page, "/crate-api/inventories/v1/materials", {
    method: "POST",
    body: {
      code: materialCode,
      name: `${FIXTURE_PREFIX}${suffix} 降压药`,
      category: "药品",
      base_unit: "盒",
      quantity_scale: 0,
      package_unit: "盒",
      package_size: "1",
      enable_batch_control: true,
      cost_method: "FIFO",
      status: "ACTIVE",
    },
  });
  const lot = await api<{ id: string }>(page, "/crate-api/inventories/v1/lots", {
    method: "POST",
    body: {
      material_id: material.id,
      batch_no: `${FIXTURE_PREFIX}${suffix}-BATCH`,
      production_date: "2026-01-01",
      expiry_date: "2027-12-31",
      manufacturer: "测试药厂",
    },
  });
  await api<unknown>(page, "/crate-api/inventories/v1/operations/inbound", {
    method: "POST",
    body: {
      warehouse,
      items: [
        {
          material_id: material.id,
          lot_id: lot.id,
          quantity: "10",
          unit_cost: "2.50",
        },
      ],
      note: "012 browser e2e fixture",
    },
  });
  return { materialId: material.id, lotId: lot.id, materialName: material.name };
}

async function createMedicationOrder(
  page: Page,
  admission: AdmissionResponse,
  suffix: string,
): Promise<MedicalOrder> {
  const order = await api<MedicalOrder>(
    page,
    `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`,
    {
      method: "POST",
      body: {
        order_type: "MEDICATION",
        order_class: "LONG_TERM",
        order_content: `${FIXTURE_PREFIX}${suffix} 降压药每日一次`,
        doctor: "测试医生",
        start_time: "2026-08-06T08:00:00+08:00",
        order_details: {
          drug_name: "氨氯地平片",
          dose: "1",
          unit: "片",
          route: "口服",
          frequency_code: "QD",
          frequency_name: "每日一次",
        },
      },
    },
  );
  await api<MedicalOrder>(page, `/crate-api/healthcare/v1/orders/${order.id}/nurse-check`, {
    method: "PATCH",
    body: {},
  });
  return order;
}

/** 构建完整测试前置：入住 + 护士核对医嘱 + 真实仓库 + 批次库存。 */
async function createInlineFixture(page: Page, suffix: string): Promise<InlineFixture> {
  const admission = await createActiveAdmission(page, suffix);
  const order = await createMedicationOrder(page, admission, suffix);
  const warehouse = await getWarehouseCode(page);
  const inventory = await createInventoryFixture(page, suffix, warehouse);
  return {
    patientId: admission.encounter.patient_id,
    admission,
    order,
    warehouse,
    materialId: inventory.materialId,
    lotId: inventory.lotId,
    materialName: inventory.materialName,
  };
}

/** 选择下拉框中第一个非空选项（操作人或库存物资）。 */
async function selectFirstOption(page: Page, selectLocator: Locator) {
  await expect(selectLocator.locator("option")).not.toHaveCount(1, { timeout: 10_000 });
  const value = await selectLocator.locator("option").nth(1).getAttribute("value");
  if (!value) throw new Error("expected a non-empty select option");
  await selectLocator.selectOption(value);
}

/** UI：从待接方用药医嘱创建发药单（接方）。 */
async function createDispenseViaUi(
  page: Page,
  order: MedicalOrder,
  warehouse: string,
): Promise<void> {
  await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  const orderRow = page.getByRole("row").filter({ hasText: order.order_content });
  await expect(orderRow).toBeVisible();
  await orderRow.getByRole("button", { name: "创建发药单" }).click();

  const modal = modalByTitle(page, "创建发药单");
  await expect(modal).toBeVisible();
  await modal.locator("#dispense-warehouse").selectOption(warehouse);
  await selectFirstOption(page, modal.locator("#dispense-material"));
  await modal.locator("#dispense-operator").waitFor({ state: "visible" });
  await selectFirstOption(page, modal.locator("#dispense-operator"));
  await modal.getByRole("button", { name: "创建发药单" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
}

/** UI：发药单 Tab 中执行 审方 → 开始调配 → 发药确认，直至已发药。 */
async function confirmDispenseViaUi(page: Page, patientName: string): Promise<void> {
  await page.getByRole("button", { name: "发药单" }).click();
  const dispenseRow = page.getByRole("row").filter({ hasText: patientName });
  await expect(dispenseRow).toContainText("待审方");

  async function runAction(buttonText: string, modalTitle: string) {
    await dispenseRow.getByRole("button", { name: buttonText }).click();
    const actionModal = modalByTitle(page, modalTitle);
    await expect(actionModal).toBeVisible();
    await selectFirstOption(page, actionModal.locator("#action-operator"));
    await actionModal.getByRole("button", { name: modalTitle }).click();
    await expect(actionModal).not.toBeVisible({ timeout: 10_000 });
  }

  await runAction("审方", "审方");
  await expect(dispenseRow).toContainText("已审方");
  await runAction("开始调配", "开始调配");
  await expect(dispenseRow).toContainText("调配中");
  await runAction("发药确认", "发药确认");
  await expect(dispenseRow).toContainText("已发药");
}

/** UI：发药单详情弹窗 →「创建退药」→ 提交退药单（自动切到退药单 Tab）。 */
async function createReturnViaUi(page: Page, patientName: string): Promise<void> {
  await page.getByRole("button", { name: "发药单" }).click();
  const dispenseRow = page.getByRole("row").filter({ hasText: patientName });
  await expect(dispenseRow).toContainText("已发药");
  await dispenseRow.getByRole("button", { name: "详情" }).click();

  const detail = modalByTitle(page, "发药单详情");
  await expect(detail).toBeVisible();
  await detail.getByRole("button", { name: "创建退药" }).click();

  const returnModal = modalByTitle(page, "创建退药单");
  await expect(returnModal).toBeVisible();
  await selectFirstOption(page, returnModal.locator("#return-item"));
  await selectFirstOption(page, returnModal.locator("#return-operator"));
  await returnModal.getByRole("button", { name: "创建退药单" }).click();
  await expect(returnModal).not.toBeVisible({ timeout: 10_000 });

  // 创建成功后页面切到退药单 Tab，发药单详情弹窗仍开着，需关闭
  await detail.getByRole("button", { name: "关闭" }).click();
  await expect(detail).not.toBeVisible();
}

/** UI：退药单 Tab 中确认入库（PENDING → CONFIRMED）。 */
async function confirmReturnViaUi(page: Page, patientName: string): Promise<void> {
  const returnRow = page.getByRole("row").filter({ hasText: patientName });
  await expect(returnRow).toContainText("待确认");
  await returnRow.getByRole("button", { name: "确认入库" }).click();
  const modal = modalByTitle(page, "确认退药入库");
  await expect(modal).toBeVisible();
  await selectFirstOption(page, modal.locator("#return-action-operator"));
  await modal.getByRole("button", { name: "确认入库" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(returnRow).toContainText("已入库");
}

/** UI：退药单 Tab 中取消待确认退药单（PENDING → CANCELLED）。 */
async function cancelReturnViaUi(page: Page, patientName: string): Promise<void> {
  const returnRow = page.getByRole("row").filter({ hasText: patientName });
  await expect(returnRow).toContainText("待确认");
  await returnRow.getByRole("button", { name: "取消" }).click();
  const modal = modalByTitle(page, "取消退药单");
  await expect(modal).toBeVisible();
  await modal.getByRole("button", { name: "取消退药单" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(returnRow).toContainText("已取消");
}

/** API：快速把发药单推到 DISPENSED（边界/幂等用例用，避免重复 UI 大流程）。 */
async function dispenseToDispensedViaApi(page: Page, fixture: InlineFixture): Promise<PharmacyDispense> {
  const created = await api<PharmacyDispense>(
    page,
    "/crate-api/pharmacy/v1/dispenses/from-medical-order",
    {
      method: "POST",
      body: {
        medical_order_id: fixture.order.id,
        warehouse: fixture.warehouse,
        material_id: fixture.materialId,
        lot_id: fixture.lotId,
        dispensed_quantity: "1",
      },
    },
  );
  const itemId = created.items[0]?.id;
  if (!itemId) throw new Error("dispense created without items");
  await api<unknown>(page, `/crate-api/pharmacy/v1/dispenses/${created.id}/review`, {
    method: "POST",
    body: { operator: "审方药师" },
  });
  await api<unknown>(page, `/crate-api/pharmacy/v1/dispenses/${created.id}/start`, {
    method: "POST",
    body: { operator: "调配药师" },
  });
  const confirmed = await api<PharmacyDispense>(page, `/crate-api/pharmacy/v1/dispenses/${created.id}/confirm`, {
    method: "POST",
    body: { operator: "发药药师" },
  });
  expect(confirmed.status).toBe("DISPENSED");
  return confirmed;
}

/** API：创建待确认退药单（返回单号与明细）。 */
async function createReturnViaApi(page: Page, dispense: PharmacyDispense, quantity = "1"): Promise<PharmacyReturn> {
  const itemId = dispense.items[0]?.id;
  if (!itemId) throw new Error("dispense has no item to return");
  return api<PharmacyReturn>(page, "/crate-api/pharmacy/v1/returns/from-dispense", {
    method: "POST",
    body: {
      dispense_id: dispense.id,
      dispense_item_id: itemId,
      quantity,
      return_reason: "老人未使用",
      operator: "护士甲",
      restockable: true,
      remark: "包装完整",
    },
  });
}

/** DB：某物资 PHARMACY_RETURN 来源的 INBOUND 操作明细数量。 */
async function countReturnInboundDetails(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1
       AND operation.operation_type = 'INBOUND'
       AND operation.metadata->>'source' = 'PHARMACY_RETURN'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

/** DB：某物资 OUTBOUND 操作明细数量（发药出库）。 */
async function countOutboundDetails(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1 AND operation.operation_type = 'OUTBOUND'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

/** DB：某物资/批次当前结存。 */
async function readStock(materialId: string, lotId: string): Promise<{ quantity: number; totalCost: number } | null> {
  const result = await databasePool.query<{ quantity: string; total_cost: string }>(
    `SELECT quantity::text AS quantity, total_cost::text AS total_cost
     FROM public.stocks
     WHERE material_id = $1 AND lot_id = $2`,
    [materialId, lotId],
  );
  const row = result.rows[0];
  return row ? { quantity: Number(row.quantity), totalCost: Number(row.total_cost) } : null;
}

/** DB：退药明细回写的库存操作明细与成本。 */
async function readReturnItem(returnId: string): Promise<{ stockOperationDetailId: string | null; unitCost: number }> {
  const result = await databasePool.query<{ stock_operation_detail_id: string | null; unit_cost: string }>(
    `SELECT stock_operation_detail_id, unit_cost::text AS unit_cost
     FROM pharmacy.pharmacy_return_items
     WHERE return_id = $1`,
    [returnId],
  );
  const row = result.rows[0];
  if (!row) throw new Error(`return item not found: ${returnId}`);
  return { stockOperationDetailId: row.stock_operation_detail_id, unitCost: Number(row.unit_cost) };
}

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  databasePool = new Pool({
    host: process.env.PLAYWRIGHT_DB_HOST ?? "localhost",
    port: Number(process.env.PLAYWRIGHT_DB_PORT ?? "5432"),
    database: process.env.PLAYWRIGHT_DB_DATABASE ?? "aceso_test",
    user: process.env.PLAYWRIGHT_DB_USER ?? "ovaphlow",
    password: requiredEnvironment("PLAYWRIGHT_DB_PASSWORD", process.env.PLAYWRIGHT_DB_PASSWORD),
  });
  await cleanupDatabase();
});

test.afterAll(async () => {
  await cleanupDatabase();
  await databasePool.end();
});

test.beforeEach(async ({ page }) => {
  await ensureAuthenticated(page);
  await cleanupDatabase();
});

test.afterEach(async () => {
  await cleanupDatabase();
});

test("退药主线：从已发药明细创建待确认退药单，UI 确认入库后库存回库且成本锁定", async ({ page }) => {
  const suffix = "MAIN";
  const fixture = await createInlineFixture(page, suffix);
  const patientName = `${FIXTURE_PREFIX}${suffix}-patient`;

  // 011 前置：接方 → 审方 → 调配 → 发药确认
  await createDispenseViaUi(page, fixture.order, fixture.warehouse);
  await confirmDispenseViaUi(page, patientName);

  // 发药确认后库存为 9，尚无任何退药入库流水
  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 9, totalCost: 22.5 });
  expect(await countReturnInboundDetails(fixture.materialId)).toBe(0);

  // UI：详情 → 创建退药 → 待确认退药单（默认数量 1、原因预填「老人未使用」）
  await createReturnViaUi(page, patientName);

  const returnsList = await api<PharmacyReturnList>(
    page,
    `/crate-api/pharmacy/v1/returns?patient_id=${fixture.patientId}&limit=10`,
  );
  const pending = returnsList.records.find((record) => record.status === "PENDING");
  expect(pending).toBeTruthy();
  expect(pending?.patient_id).toBe(fixture.patientId);
  expect(pending?.original_dispense_id).toBeTruthy();
  expect(Number(pending?.total_quantity ?? "0")).toBe(1);

  // 待确认阶段：不发生库存操作，结存仍为 9
  expect(await countReturnInboundDetails(fixture.materialId)).toBe(0);
  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 9, totalCost: 22.5 });

  // UI：确认入库 → 已入库（PENDING → CONFIRMED）
  await page.getByRole("button", { name: "退药单" }).click();
  await confirmReturnViaUi(page, patientName);

  const confirmed = await api<PharmacyReturn>(page, `/crate-api/pharmacy/v1/returns/${pending!.id}`);
  expect(confirmed.status).toBe("CONFIRMED");
  expect(confirmed.operator).toBeTruthy();
  expect(confirmed.confirmed_at).toBeTruthy();
  const returnItem = confirmed.items[0];
  expect(returnItem?.stock_operation_detail_id).toBeTruthy();
  expect(Number(returnItem?.unit_cost ?? "0")).toBe(2.5); // 成本由原发药明细锁定
  expect(Number(returnItem?.total_cost ?? "0")).toBe(2.5);

  // 回库生效：库存 9 → 10，存在一条 PHARMACY_RETURN 来源 INBOUND 流水
  expect(await countReturnInboundDetails(fixture.materialId)).toBe(1);
  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 10, totalCost: 25 });

  // 退药明细已回写库存入库操作明细 ID（可追溯闭环）
  const item = await readReturnItem(pending!.id);
  expect(item.stockOperationDetailId).toBe(returnItem?.stock_operation_detail_id);
  expect(item.unitCost).toBe(2.5);
});

test("重复确认幂等：已确认退药单重试不重复回库，库存与流水只增加一次", async ({ page }) => {
  const suffix = "IDEM";
  const fixture = await createInlineFixture(page, suffix);
  const dispense = await dispenseToDispensedViaApi(page, fixture);

  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 9, totalCost: 22.5 });

  const created = await createReturnViaApi(page, dispense);
  expect(created.status).toBe("PENDING");

  const first = await api<PharmacyReturn>(page, `/crate-api/pharmacy/v1/returns/${created.id}/confirm`, {
    method: "PUT",
    body: { operator: "药房药师" },
  });
  expect(first.status).toBe("CONFIRMED");
  const firstDetailId = first.items[0]?.stock_operation_detail_id;
  expect(firstDetailId).toBeTruthy();

  // 幂等重试：返回已确认结果，且不产生第二条回库流水
  const retried = await api<PharmacyReturn>(page, `/crate-api/pharmacy/v1/returns/${created.id}/confirm`, {
    method: "PUT",
    body: { operator: "药房药师", remark: "幂等重试" },
  });
  expect(retried.status).toBe("CONFIRMED");
  expect(retried.items[0]?.stock_operation_detail_id).toBe(firstDetailId);

  expect(await countReturnInboundDetails(fixture.materialId)).toBe(1);
  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 10, totalCost: 25 });
  expect(await countOutboundDetails(fixture.materialId)).toBe(1); // 仅发药出库 1 条
});

test("取消待确认退药单：UI 取消后不产生库存操作，可重新退药；已确认单不可取消", async ({ page }) => {
  const suffix = "CANCEL";
  const fixture = await createInlineFixture(page, suffix);
  const patientName = `${FIXTURE_PREFIX}${suffix}-patient`;
  const dispense = await dispenseToDispensedViaApi(page, fixture);

  // API 造一张待确认退药单，再用 UI 取消（药房退药 Tab 的取消入口）
  const created = await createReturnViaApi(page, dispense);
  expect(created.status).toBe("PENDING");
  await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "退药单" }).click();
  await cancelReturnViaUi(page, patientName);

  const cancelled = await api<PharmacyReturn>(page, `/crate-api/pharmacy/v1/returns/${created.id}`);
  expect(cancelled.status).toBe("CANCELLED");

  // 取消不产生回库流水，结存仍为 9
  expect(await countReturnInboundDetails(fixture.materialId)).toBe(0);
  expect(await readStock(fixture.materialId, fixture.lotId)).toEqual({ quantity: 9, totalCost: 22.5 });

  // 取消释放了可退数量：同一明细可再次创建退药单
  const recreated = await createReturnViaApi(page, dispense);
  expect(recreated.status).toBe("PENDING");

  // 确认后不可取消：PUT cancel 应返回 409
  await api<PharmacyReturn>(page, `/crate-api/pharmacy/v1/returns/${recreated.id}/confirm`, {
    method: "PUT",
    body: { operator: "药房药师" },
  });
  const cancelConfirmed = await rawApi<{ error: string }>(
    page,
    `/crate-api/pharmacy/v1/returns/${recreated.id}/cancel`,
    { method: "PUT" },
  );
  expect(cancelConfirmed.status).toBe(409);
  expect(cancelConfirmed.body).toMatchObject({ error: expect.stringContaining("cannot cancel return in status CONFIRMED") });
});

test("退药创建边界：restockable=false 拒绝、旧通用入口拒绝、超量与非 DISPENSED 拒绝", async ({ page }) => {
  const suffix = "EDGE";
  const fixture = await createInlineFixture(page, suffix);

  // 未完成发药的单（PENDING）不可创建退药 → 409
  const inProgress = await api<PharmacyDispense>(
    page,
    "/crate-api/pharmacy/v1/dispenses/from-medical-order",
    {
      method: "POST",
      body: {
        medical_order_id: fixture.order.id,
        warehouse: fixture.warehouse,
        material_id: fixture.materialId,
        lot_id: fixture.lotId,
        dispensed_quantity: "1",
      },
    },
  );
  const inProgressItemId = inProgress.items[0]?.id;
  expect(inProgressItemId).toBeTruthy();
  const notDispensed = await rawApi<{ error: string }>(
    page,
    "/crate-api/pharmacy/v1/returns/from-dispense",
    {
      method: "POST",
      body: {
        dispense_id: inProgress.id,
        dispense_item_id: inProgressItemId,
        quantity: "1",
        return_reason: "老人未使用",
        operator: "护士甲",
        restockable: true,
      },
    },
  );
  expect(notDispensed.status).toBe(409);
  expect(notDispensed.body).toMatchObject({ error: expect.stringContaining("only DISPENSED dispense can be returned") });

  // 释放 PENDING 发药单：同医嘱同一时刻只允许一张非取消发药单
  await api<PharmacyDispense>(page, `/crate-api/pharmacy/v1/dispenses/${inProgress.id}/cancel`, {
    method: "POST",
    body: { remark: "边界用例释放占位发药单" },
  });

  // 已发药单：restockable=false 必须被拒 → 400
  const dispense = await dispenseToDispensedViaApi(page, fixture);
  const noRestockable = await rawApi<{ error: string }>(
    page,
    "/crate-api/pharmacy/v1/returns/from-dispense",
    {
      method: "POST",
      body: {
        dispense_id: dispense.id,
        dispense_item_id: dispense.items[0]!.id,
        quantity: "1",
        return_reason: "老人未使用",
        operator: "护士甲",
        restockable: false,
      },
    },
  );
  expect(noRestockable.status).toBe(400);
  expect(noRestockable.body).toMatchObject({ error: expect.stringContaining("restockable") });

  // 旧的通用 POST /returns 入口必须被拒绝 → 400
  const oldEntry = await rawApi<{ error: string }>(
    page,
    "/crate-api/pharmacy/v1/returns",
    { method: "POST", body: { patient_id: fixture.patientId } },
  );
  expect(oldEntry.status).toBe(400);

  // 退药数量超过原发药数量 1 → 409
  const overQuantity = await rawApi<{ error: string }>(
    page,
    "/crate-api/pharmacy/v1/returns/from-dispense",
    {
      method: "POST",
      body: {
        dispense_id: dispense.id,
        dispense_item_id: dispense.items[0]!.id,
        quantity: "2",
        return_reason: "老人未使用",
        operator: "护士甲",
        restockable: true,
      },
    },
  );
  expect(overQuantity.status).toBe(409);
  expect(overQuantity.body).toMatchObject({ error: expect.stringContaining("return quantity exceeds") });

  // 合法创建不受上述失败影响
  const created = await createReturnViaApi(page, dispense);
  expect(created.status).toBe("PENDING");
  expect(Number(created.items[0]?.quantity ?? "0")).toBe(1);
  // 上述失败的请求均未产生回库流水
  expect(await countReturnInboundDetails(fixture.materialId)).toBe(0);
});