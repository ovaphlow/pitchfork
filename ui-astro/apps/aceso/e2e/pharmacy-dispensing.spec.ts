import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 011 Aceso 药房用药医嘱接方与发药闭环 — 浏览器验收。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL        Aceso UI 地址
 *   PLAYWRIGHT_API_BASE_URL    Aceso API 地址（/crate-api 根）
 *   PLAYWRIGHT_DB_*            隔离 PostgreSQL 连接
 *   PLAYWRIGHT_USERNAME/PASSWORD 测试账号
 *   PLAYWRIGHT_NEXUS_API_BASE_URL 可选；缺省按仓库配置所在 Nexus 地址
 *
 * 主线：
 *   活动养老入住开 MEDICATION 医嘱 → 护士核对 → 药房待接方 →
 *   选择仓库/库存物资/批次创建发药单 → 审方 → 开始调配 →
 *   发药确认（同事务扣库存并回写库存操作明细 ID）→ 重复确认不重复扣减。
 */

const FIXTURE_PREFIX = "pw-ph-";
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

interface PharmacyDispenseList {
  records: PharmacyDispense[];
  meta: { total: number };
}

interface ApiResult<T> {
  status: number;
  body: T;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso pharmacy dispensing tests`);
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

    // 药房：先删明细，再删主表
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

/** 在浏览器页面上调用 Aceso API；登录后会自动携带同站会话。 */
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

async function ensureAuthenticated(page: Page) {
  await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  if (!page.url().includes("/login")) return;
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
      note: "011 browser e2e fixture",
    },
  });
  return { materialId: material.id, lotId: lot.id, materialName: material.name };
}

async function createMedicationOrder(
  page: Page,
  admission: AdmissionResponse,
  suffix: string,
  material: { materialId: string; materialName: string },
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
          // 025：用药医嘱必须从目录选药，material_id 必填且 drug_name 必须等于目录名
          material_id: material.materialId,
          drug_name: material.materialName,
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

/** 选择下拉框中第一个非空选项（操作人或库存物资）。 */
async function selectFirstOption(page: Page, selectLocator: Locator) {
  await expect(selectLocator.locator("option")).not.toHaveCount(1, { timeout: 10_000 });
  const value = await selectLocator.locator("option").nth(1).getAttribute("value");
  if (!value) throw new Error("expected a non-empty select option");
  await selectLocator.selectOption(value);
}

/**
 * 选择第一个可用库存批次。
 * 025 起发药弹窗把「药品物资」与「库存批次」拆成两个选择器：`#dispense-stock` 必选，
 * 否则前端会以「请选择可用库存批次」拦下提交。
 */
async function selectFirstStock(page: Page, modal: Locator) {
  const stockSelect = modal.locator("#dispense-stock");
  await expect(stockSelect).toBeEnabled({ timeout: 10_000 });
  await selectFirstOption(page, stockSelect);
}

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

  // 025：医嘱已绑定目录药品时 #dispense-material 禁用且只有一个选项（预选医嘱绑定药品），
  // 只有历史自由文本医嘱才需要药房在这里补选目录药品。
  const materialSelect = modal.locator("#dispense-material");
  if (!(await materialSelect.isDisabled())) {
    await selectFirstOption(page, materialSelect);
  } else {
    await expect(materialSelect.locator("option")).toHaveCount(1);
  }

  await selectFirstStock(page, modal);
  await modal.locator("#dispense-operator").waitFor({ state: "visible" });
  await selectFirstOption(page, modal.locator("#dispense-operator"));
  await modal.getByRole("button", { name: "创建发药单" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
}

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  databasePool = new Pool({
    host: process.env.PLAYWRIGHT_DB_HOST ?? "localhost",
    port: Number(process.env.PLAYWRIGHT_DB_PORT ?? "5432"),
    database: process.env.PLAYWRIGHT_DB_DATABASE ?? "aceso_test",
    user: process.env.PLAYWRIGHT_DB_USER ?? "ovaphlow",
    password: requiredEnvironment("PITCHFORK_DB_PASSWORD", process.env.PITCHFORK_DB_PASSWORD),
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

test("药房主线：接方→审方→调配→发药确认，库存出库只发生一次", async ({ page }) => {
  const suffix = "FLOW";
  const admission = await createActiveAdmission(page, suffix);
  const warehouse = await getWarehouseCode(page);
  // 025：药品目录物资必须先建好（含批次与入库），医嘱才能绑定它；
  // 顺序与 011 相反——现在是「先建目录药品，再按目录开医嘱」。
  const inventory = await createInventoryFixture(page, suffix, warehouse);
  const order = await createMedicationOrder(page, admission, suffix, inventory);

  await createDispenseViaUi(page, order, warehouse);

  // 待接方列表标记为已接方，不再显示创建按钮
  const orderRow = page.getByRole("row").filter({ hasText: order.order_content });
  await expect(orderRow).toContainText("已接方");
  await expect(orderRow.getByRole("button", { name: "创建发药单" })).not.toBeVisible();

  // 发药单列表：PENDING → REVIEWED → DISPENSING → DISPENSED
  const patientName = `${FIXTURE_PREFIX}${suffix}-patient`;
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

  // 已发药后不应再有取消入口
  await expect(dispenseRow.getByRole("button", { name: "取消" })).not.toBeVisible();

  // 详情展示库存操作明细 ID
  await dispenseRow.getByRole("button", { name: "详情" }).click();
  const detail = modalByTitle(page, "发药单详情");
  await expect(detail).toContainText("库存操作明细 ID：");
  const detailText = await detail.innerText();
  const stockDetailId = detailText.match(/库存操作明细 ID：(\S+)/)?.[1];
  expect(stockDetailId).toBeTruthy();
  await detail.getByRole("button", { name: "关闭" }).click();

  // 重复确认：返回已发药结果且不新增出库
  const dispenseList = await api<PharmacyDispenseList>(
    page,
    `/crate-api/pharmacy/v1/dispenses?dispense_type=ELDERLY_ROUTINE&limit=100`,
  );
  const dispense = dispenseList.records.find((record) => record.encounter_id === admission.encounter.id);
  expect(dispense).toBeTruthy();
  const dispenseDetail = await api<PharmacyDispense>(
    page,
    `/crate-api/pharmacy/v1/dispenses/${dispense!.id}`,
  );
  expect(dispenseDetail.items[0]?.stock_operation_detail_id).toBe(stockDetailId);

  const retried = await api<PharmacyDispense>(
    page,
    `/crate-api/pharmacy/v1/dispenses/${dispense!.id}/confirm`,
    { method: "POST", body: { operator: "测试药师", remark: "重复确认" } },
  );
  expect(retried.status).toBe("DISPENSED");
  expect(retried.items[0]?.stock_operation_detail_id).toBe(stockDetailId);

  // 数据库层核对：该物资 OUTBOUND 操作明细只有 1 条
  const outboundCount = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1 AND operation.operation_type = 'OUTBOUND'`,
    [inventory.materialId],
  );
  expect(outboundCount.rows[0]?.count ?? "0").toBe("1");

  // 库存可用量减少为 9
  const stockCount = await databasePool.query<{ quantity: string }>(
    `SELECT quantity::text AS quantity
     FROM public.stocks
     WHERE material_id = $1 AND lot_id = $2`,
    [inventory.materialId, inventory.lotId],
  );
  expect(Number(stockCount.rows[0]?.quantity)).toBe(9);
});

test("取消后不产生库存操作，且医嘱可重新接方；重复接方返回 409", async ({ page }) => {
  const suffix = "CANCEL";
  const admission = await createActiveAdmission(page, suffix);
  const warehouse = await getWarehouseCode(page);
  const inventory = await createInventoryFixture(page, suffix, warehouse);
  const order = await createMedicationOrder(page, admission, suffix, inventory);

  await createDispenseViaUi(page, order, warehouse);

  // 取消 PENDING 发药单
  const patientName = `${FIXTURE_PREFIX}${suffix}-patient`;
  await page.getByRole("button", { name: "发药单" }).click();
  const dispenseRow = page.getByRole("row").filter({ hasText: patientName });
  await expect(dispenseRow).toContainText("待审方");
  await dispenseRow.getByRole("button", { name: "取消" }).click();
  const cancelModal = modalByTitle(page, "取消发药单");
  await expect(cancelModal).toBeVisible();
  await selectFirstOption(page, cancelModal.locator("#action-operator"));
  await cancelModal.getByRole("button", { name: "取消发药单" }).click();
  await expect(dispenseRow).toContainText("已取消");

  // 取消单没有库存出库
  const outboundCount = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1 AND operation.operation_type = 'OUTBOUND'`,
    [inventory.materialId],
  );
  expect(outboundCount.rows[0]?.count ?? "0").toBe("0");

  // 医嘱恢复为可接方
  await page.getByRole("button", { name: "待接方用药医嘱" }).click();
  const orderRow = page.getByRole("row").filter({ hasText: order.order_content });
  await expect(orderRow).toContainText("未接方");
  await expect(orderRow.getByRole("button", { name: "创建发药单" })).toBeVisible();

  // 重新接方成功，且再次创建同一未取消单时返回 409
  const created = await api<PharmacyDispense>(
    page,
    "/crate-api/pharmacy/v1/dispenses/from-medical-order",
    {
      method: "POST",
      body: {
        medical_order_id: order.id,
        warehouse,
        material_id: inventory.materialId,
        lot_id: inventory.lotId,
        dispensed_quantity: "1",
      },
    },
  );
  const createdDetail = created.items[0];
  expect(createdDetail?.material_id).toBe(inventory.materialId);
  const duplicateStatus = await page.evaluate(
    async ({ baseUrl, body }) => {
      const response = await fetch(`${baseUrl}/crate-api/pharmacy/v1/dispenses/from-medical-order`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify(body),
      });
      return response.status;
    },
    {
      baseUrl: requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL),
      body: {
        medical_order_id: order.id,
        warehouse,
        material_id: createdDetail!.material_id,
        lot_id: createdDetail!.lot_id,
        dispensed_quantity: "1",
      },
    },
  );
  expect(duplicateStatus).toBe(409);
});

/**
 * 计划 §7.3 手测主线第 7 条的反向用例（选错药 → 被服务端拒绝）。
 *
 * 025 之后 UI 已不可能触发：医嘱绑定目录药品时 `#dispense-material` 被禁用且只有
 * 绑定药品一个选项（见 PharmacyPage.tsx）。因此这里降级为 **API 层断言**，
 * 验证服务端仍然是最终权威——即使绕过 UI 提交不一致的 `material_id` 也必须 409。
 */
test("025反向用例：绕过 UI 提交与医嘱绑定不一致的药品由服务端拒绝且无副作用", async ({ page }) => {
  const suffix = "MISMATCH";
  const admission = await createActiveAdmission(page, suffix);
  const warehouse = await getWarehouseCode(page);
  // 医嘱绑定 inventory；另一个目录药品 other 用于构造「选错药」
  const inventory = await createInventoryFixture(page, suffix, warehouse);
  const other = await createInventoryFixture(page, `${suffix}2`, warehouse);
  const order = await createMedicationOrder(page, admission, suffix, inventory);

  const rejected = await page.evaluate(
    async ({ baseUrl, body }) => {
      const response = await fetch(`${baseUrl}/crate-api/pharmacy/v1/dispenses/from-medical-order`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json().catch(() => ({})) };
    },
    {
      baseUrl: requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL),
      body: {
        medical_order_id: order.id,
        warehouse,
        material_id: other.materialId,
        lot_id: other.lotId,
        dispensed_quantity: "1",
      },
    },
  );
  expect(rejected.status).toBe(409);
  expect(String(rejected.body.error)).toBe("material_id does not match the prescribed drug");

  // 无副作用：该入住没有发药单，两个物资都没有出库明细，库存保持入库时的数量
  const dispenses = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count FROM pharmacy.pharmacy_dispenses WHERE encounter_id = $1`,
    [admission.encounter.id],
  );
  expect(dispenses.rows[0]?.count ?? "0").toBe("0");

  const outbound = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = ANY($1) AND operation.operation_type = 'OUTBOUND'`,
    [[inventory.materialId, other.materialId]],
  );
  expect(outbound.rows[0]?.count ?? "0").toBe("0");

  const stocks = await databasePool.query<{ quantity: string }>(
    `SELECT quantity::text AS quantity FROM public.stocks WHERE material_id = ANY($1) ORDER BY material_id`,
    [[inventory.materialId, other.materialId]],
  );
  expect(stocks.rows.map((row) => Number(row.quantity))).toEqual([10, 10]);
});