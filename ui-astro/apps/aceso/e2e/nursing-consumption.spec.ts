import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 016 §6.3-4 护理耗材消耗浏览器验收：任务完成后按基础数量消耗库存，
 * 执行耗材详情（数量/单位/成本）与库存流水完全一致；只录入基础数量，
 * 不暴露规格/换算/基础数量等旧字段。
 *
 * 依赖用户已启动的 Aceso UI、API、共享 Nexus 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL / PLAYWRIGHT_API_BASE_URL / PLAYWRIGHT_DB_* / PLAYWRIGHT_USERNAME/PASSWORD
 *
 * 链路：SQL 种 patient/service_period/task/execution(PENDING 今日) +
 * API 种 片剂物资/批次/100 片库存 → UI 今日工作台 开始 → 完成（勾选耗材、
 * 选仓库、添加物资、输入基础数量 5 片）→ 校验执行详情与库存流水一致。
 */

const FIXTURE_PREFIX = "pw-nc-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;
const NEXUS_API_BASE =
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL ?? "http://127.0.0.1:8423/crate-api/shared/v1";

interface ApiResult<T> { status: number; body: T }
interface InventoryMaterial { id: string; code: string; name: string; base_unit: string; quantity_scale: number }
interface InventoryLot { id: string }
interface NursingExecutionConsumptionApi {
  id: string;
  stock_operation_detail_id: string;
  stock_id: string;
  material_id: string;
  lot_id: string | null;
  warehouse: string;
  quantity: string;
  unit: string;
  unit_cost: string;
  total_cost: string;
}
interface NursingExecution {
  id: string;
  status: string;
  task_id: string;
}

interface AdmissionResponse {
  encounter: { id: string };
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the nursing consumption tests`);
  return value;
}

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
      try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = { raw: text }; }
      return { status: response.status, body: parsed };
    },
    { baseUrl, path, method: options.method ?? "GET", body: options.body },
  ) as ApiResult<T>;
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}: ${JSON.stringify(result.body)}`);
  return result.body;
}

async function rawApi<T>(page: Page, path: string, options: { method?: string; body?: unknown } = {}): Promise<{ status: number; body: T | { error: string } }> {
  const baseUrl = requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL);
  const result = await page.evaluate(
    async ({ baseUrl: requestBaseUrl, path: requestPath, method, body }) => {
      const token = localStorage.getItem("token");
      const response = await fetch(`${requestBaseUrl}${requestPath}`, {
        method,
        headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        credentials: "include",
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let parsed: unknown = {};
      try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = { raw: text }; }
      return { status: response.status, body: parsed };
    },
    { baseUrl, path, method: options.method ?? "GET", body: options.body },
  ) as ApiResult<unknown>;
  return result as { status: number; body: T | { error: string } };
}

async function ensureAuthenticated(page: Page) {
  await page.goto("/login", { waitUntil: "networkidle" });
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

async function nexusListWarehouses(page: Page): Promise<Array<{ code: string }>> {
  return page.evaluate(async ({ nexusBase }) => {
    const response = await fetch(`${nexusBase}/settings?category=warehouse&page=1&page_size=100`, { credentials: "include" });
    if (!response.ok) throw new Error(`warehouse settings failed: ${response.status}`);
    return (await response.json()) as Array<{ code: string }>;
  }, { nexusBase: NEXUS_API_BASE });
}

async function ensureWarehouse(page: Page): Promise<string> {
  const warehouses = await nexusListWarehouses(page);
  if (warehouses.length >= 1) return warehouses[0].code;
  const created = await page.evaluate(
    async ({ nexusBase }) => {
      const response = await fetch(`${nexusBase}/settings`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ category: "warehouse", code: "WH-NC-E2E", parent_code: "", root_code: "", payload: { name: "E2E护理耗材库", description: "016 nursing consumption e2e fixture" } }),
      });
      if (!response.ok) throw new Error(`create warehouse failed: ${response.status}`);
      return (await response.json()) as { code: string };
    },
    { nexusBase: NEXUS_API_BASE },
  );
  return created.code;
}

/** 片剂物资（片、0 位、非批控）＋ 100 片库存。 */
async function createStockFixture(page: Page, suffix: string, warehouse: string): Promise<{ materialId: string }> {
  const marker = `${FIXTURE_PREFIX}${suffix}-1`;
  const material = await api<InventoryMaterial>(page, "/crate-api/inventories/v1/materials", {
    method: "POST",
    body: {
      code: `${marker}-MAT`,
      name: `${FIXTURE_PREFIX}${suffix} 纱布卷`,
      category: "耗材",
      base_unit: "片",
      quantity_scale: 0,
      enable_batch_control: false,
      cost_method: "FIFO",
      status: "ACTIVE",
    },
  });
  await api<unknown>(page, "/crate-api/inventories/v1/operations/inbound", {
    method: "POST",
    body: {
      warehouse,
      items: [{ material_id: material.id, quantity: "100", unit_cost: "2.5" }],
      note: "016 nursing consumption e2e fixture",
    },
  });
  return { materialId: material.id };
}

/** 医疗：建患者 + 活动入院（encounter），admit_date 取今天，确保排序在旧数据前。 */
async function createPatientApi(page: Page, suffix: string): Promise<string> {
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}-老人` },
  });
  return patient.id;
}

async function createAdmissionApi(page: Page, patientId: string, suffix: string): Promise<string> {
  const admission = await api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_no: `${FIXTURE_PREFIX}${suffix}-admission`,
      admit_date: `${new Date(Date.now() - 24 * 3_600_000).toISOString().slice(0, 10)}T00:00:00+08:00`,
    },
  });
  return admission.encounter.id;
}

/** SQL：读取入院 API 自动创建的 ELDERLY_CARE 周期（页面按 encounter 过滤周期）。 */
async function loadPeriodForEncounter(encounterId: string): Promise<string> {
  const result = await databasePool.query<{ id: string }>(
    `SELECT id FROM nursing.nursing_service_periods WHERE encounter_id = $1 AND status = 'ACTIVE' ORDER BY created_at LIMIT 1`,
    [encounterId],
  );
  const periodId = result.rows[0]?.id;
  if (!periodId) throw new Error(`no ACTIVE period for encounter ${encounterId}`);
  return periodId;
}

/** SQL：在指定周期下种任务与 PENDING 今日执行。 */
async function seedTasksForPeriod(
  suffix: string,
  periodId: string,
  tasks: Array<{ description: string }>,
): Promise<Array<{ taskId: string; execId: string }>> {
  const client = await databasePool.connect();
  const ids: Array<{ taskId: string; execId: string }> = [];
  try {
    await client.query("BEGIN");
    for (const [index, task] of tasks.entries()) {
      const taskId = `${FIXTURE_PREFIX}${suffix}-task-${index + 1}`;
      const execId = `${FIXTURE_PREFIX}${suffix}-exec-${index + 1}`;
      await client.query(
        `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
         VALUES ($1, $2, 'NURSING', $3, 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
        [taskId, periodId, task.description],
      );
      // 计划时间 = 今天稍早，进入今日待办
      const planned = new Date(Date.now() - 35 * 60_000).toISOString();
      await client.query(
        `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
         VALUES ($1, $2, $3::timestamptz, 'PENDING') ON CONFLICT (id) DO NOTHING`,
        [execId, taskId, planned],
      );
      ids.push({ taskId, execId });
    }
    await client.query("COMMIT");
    return ids;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** DB：读取指定仓库/物资库存。 */
async function readStock(warehouse: string, materialId: string): Promise<{ quantity: number; totalCost: number } | null> {
  const result = await databasePool.query<{ quantity: string; total_cost: string }>(
    `SELECT quantity::text AS quantity, total_cost::text AS total_cost
     FROM public.stocks WHERE warehouse = $1 AND material_id = $2`,
    [warehouse, materialId],
  );
  const row = result.rows[0];
  return row ? { quantity: Number(row.quantity), totalCost: Number(row.total_cost) } : null;
}

/** DB：读取护理执行消耗记录。 */
async function readConsumption(execId: string): Promise<{ quantity: number; unit: string; unitCost: number; totalCost: number; detailId: string } | null> {
  const result = await databasePool.query<{ quantity: string; unit: string; unit_cost: string; total_cost: string; stock_operation_detail_id: string }>(
    `SELECT quantity::text AS quantity, unit::text AS unit, unit_cost::text AS unit_cost, total_cost::text AS total_cost, stock_operation_detail_id::text AS stock_operation_detail_id
     FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id = $1`,
    [execId],
  );
  const row = result.rows[0];
  return row
    ? { quantity: Number(row.quantity), unit: row.unit, unitCost: Number(row.unit_cost), totalCost: Number(row.total_cost), detailId: row.stock_operation_detail_id }
    : null;
}

/** DB：读取该物资的 NURSING_EXECUTION 出库操作明细。 */
async function readConsumptionOperationDetails(materialId: string): Promise<Array<{ quantity: number; unit: string; unitCost: number; totalCost: number; source: string }>> {
  const result = await databasePool.query<{ quantity: string; unit: string; unit_cost: string; total_cost: string; source: string }>(
    `SELECT detail.quantity::text AS quantity, detail.unit::text AS unit, detail.unit_cost::text AS unit_cost,
            detail.total_cost::text AS total_cost, operation.metadata->>'source' AS source
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1 AND operation.operation_type = 'OUTBOUND'
     ORDER BY operation.created_at`,
    [materialId],
  );
  return result.rows.map((row) => ({
    quantity: Number(row.quantity),
    unit: row.unit,
    unitCost: Number(row.unit_cost),
    totalCost: Number(row.total_cost),
    source: row.source ?? "",
  }));
}

/** 清理由本文件创建的全部 fixture。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const pattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    await client.query(
      `DELETE FROM nursing.nursing_task_execution_consumptions
       WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE $1)`,
      [pattern],
    );
    await client.query(`DELETE FROM nursing.nursing_task_executions WHERE id LIKE $1`, [pattern]);
    await client.query(
      `DELETE FROM nursing.nursing_tasks WHERE id LIKE $1
         OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1))`,
      [pattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)`,
      [pattern],
    );
    await client.query(`DELETE FROM healthcare.encounters WHERE encounter_no LIKE $1`, [pattern]);
    await client.query(`DELETE FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1`, [pattern]);

    // 库存 fixture
    const materials = await client.query<{ id: string }>(`SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1`, [pattern]);
    const materialIds = materials.rows.map((row) => row.id);
    const ops = materialIds.length
      ? await client.query<{ id: string }>(
          `SELECT DISTINCT detail.operation_id AS id FROM public.stock_operation_details detail
           JOIN public.materials material ON material.id = detail.material_id WHERE material.id = ANY($1)`,
          [materialIds],
        )
      : { rows: [] as Array<{ id: string }> };
    const opIds = ops.rows.map((row) => row.id);
    await client.query(`DELETE FROM public.stock_operation_details WHERE id LIKE $1 OR material_id = ANY($2) OR operation_id = ANY($3)`, [pattern, materialIds, opIds]);
    await client.query(`DELETE FROM public.stock_operations WHERE id LIKE $1 OR metadata::text LIKE $1 OR id = ANY($2)`, [pattern, opIds]);
    await client.query(`DELETE FROM public.stocks WHERE id LIKE $1 OR material_id = ANY($2)`, [pattern, materialIds]);
    await client.query(`DELETE FROM public.lots WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id = ANY($2)`, [pattern, materialIds]);
    await client.query(`DELETE FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1 OR id = ANY($2)`, [pattern, materialIds]);

    const residual = await client.query<{ n: string }>(
      `SELECT ((SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
              (SELECT count(*) FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE $1)) +
              (SELECT count(*) FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1))::text AS n`,
      [pattern],
    );
    if (residual.rows[0]?.n !== "0") throw new Error("fixture cleanup left residual data");
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 今日工作台行定位（与护理统计 spec 一致）。 */
class TodayTable {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/inpatient", { waitUntil: "networkidle" });
    await expect(this.page.getByRole("button", { name: "刷新" })).toBeVisible();
  }

  row(description: string) {
    return this.page
      .locator("table")
      .filter({ hasText: "计划时间" })
      .locator("tbody tr")
      .filter({ hasText: description });
  }
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

test("016 §6.3-4 UI 完成护理任务并按基础数量消耗耗材：执行耗材详情与库存流水（数量/单位/成本）一致", async ({ page }) => {
  const suffix = "MAIN";
  const warehouse = await ensureWarehouse(page);
  const stock = await createStockFixture(page, suffix, warehouse);
  const patientId = await createPatientApi(page, suffix);
  const encounterId = await createAdmissionApi(page, patientId, suffix);
  const periodId = await loadPeriodForEncounter(encounterId);
  const [{ execId }] = await seedTasksForPeriod(suffix, periodId, [{ description: "护理消耗任务" }]);
  const taskDescription = "护理消耗任务";
  expect(await readStock(warehouse, stock.materialId)).toMatchObject({ quantity: 100, totalCost: 250 });

  const workbench = new TodayTable(page);
  await workbench.goto();
  const row = workbench.row(taskDescription);
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("待执行");

  // 开始 → 执行中
  await row.getByRole("button", { name: "开始" }).click();
  await expect(row).toContainText("执行中");

  // 完成：勾选耗材、选仓库、添加 100 片库存中的 5 片
  await row.getByRole("button", { name: "完成" }).click();
  const modal = page.getByRole("heading", { name: "完成任务" }).locator("xpath=../..");
  await expect(modal).toBeVisible();
  await modal.locator('input[type="checkbox"]').check();
  await modal.locator("#consume-warehouse").selectOption(warehouse);
  // 可用耗材列表出现目标物资 → 添加
  const stockEntry = modal.getByText(`${FIXTURE_PREFIX}${suffix} 纱布卷`, { exact: true }).first();
  await expect(stockEntry).toBeVisible({ timeout: 10_000 });
  await stockEntry.locator("xpath=..").getByRole("button", { name: "添加" }).click();
  // 已选耗材：基础数量输入（唯一的 number 输入）
  const qtyInput = modal.locator('input[type="number"]');
  await expect(qtyInput).toHaveCount(1);
  await qtyInput.fill("5");
  await modal.getByRole("button", { name: "确认完成" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已完成");

  // 执行详情 API：COMPLETED + 耗材明细（数量/单位/成本）
  const execution = await api<NursingExecution>(page, `/crate-api/nursing/v1/executions/${execId}`);
  expect(execution.status).toBe("COMPLETED");
  const consumptions = await api<{ records: NursingExecutionConsumptionApi[] }>(page, `/crate-api/nursing/v1/executions/${execId}/consumptions`);
  expect(consumptions.records).toHaveLength(1);
  const consumption = consumptions.records[0];
  expect(consumption.material_id).toBe(stock.materialId);
  expect(consumption.warehouse).toBe(warehouse);
  expect(Number(consumption.quantity)).toBe(5);
  expect(consumption.unit).toBe("片");
  expect(Number(consumption.unit_cost)).toBe(2.5);
  expect(Number(consumption.total_cost)).toBe(12.5);
  expect(consumption.stock_operation_detail_id).toBeTruthy();

  // 库存流水：OUTBOUND 恰一条 NURSING_EXECUTION 操作明细，与执行耗材完全一致
  const details = await readConsumptionOperationDetails(stock.materialId);
  expect(details).toHaveLength(1);
  expect(details[0]).toMatchObject({ quantity: 5, unit: "片", unitCost: 2.5, totalCost: 12.5, source: "NURSING_EXECUTION" });

  // 库存结存：100 - 5 = 95 片，成本 250 - 12.5 = 237.5，锁定量不变
  expect(await readStock(warehouse, stock.materialId)).toMatchObject({ quantity: 95, totalCost: 237.5 });

  // 护理消耗记录与库存操作明细精确对应
  const dbConsumption = await readConsumption(execId);
  expect(dbConsumption).toMatchObject({ quantity: 5, unit: "片", unitCost: 2.5, totalCost: 12.5 });
  expect(dbConsumption!.detailId).toBe(details[0] && consumption.stock_operation_detail_id);

  // 窄屏由 mobile-chrome 项目以 393px 执行本套用例覆盖
});

test("016 §6.3-4 旧换算字段（unit_spec_id 等）在耗材提交时被 400 拒绝且任务状态不变", async ({ page }) => {
  const suffix = "LEGACY";
  const warehouse = await ensureWarehouse(page);
  const stock = await createStockFixture(page, suffix, warehouse);
  const patientId = await createPatientApi(page, suffix);
  const encounterId = await createAdmissionApi(page, patientId, suffix);
  const periodId = await loadPeriodForEncounter(encounterId);
  const [{ execId }] = await seedTasksForPeriod(suffix, periodId, [{ description: "护理旧字段任务" }]);
  const stockRow = await readStock(warehouse, stock.materialId);
  expect(stockRow).toMatchObject({ quantity: 100 });

  // PATCH 状态带旧字段 → 400（字段白名单拒绝），状态仍 PENDING、库存未变
  const legacy = await rawApi<{ error: string }>(page, `/crate-api/nursing/v1/executions/${execId}/status`, {
    method: "PATCH",
    body: {
      status: "COMPLETED",
      consumptions: [{ stock_id: "any-stock", quantity: "5", unit_spec_id: "legacy-spec" }],
    },
  });
  expect(legacy.status).toBe(400);
  expect((legacy.body as { error: string }).error).toContain("unsupported field");

  const execution = await api<NursingExecution>(page, `/crate-api/nursing/v1/executions/${execId}`);
  expect(execution.status).toBe("PENDING");
  expect(await readStock(warehouse, stock.materialId)).toMatchObject({ quantity: 100 });
  expect(await readConsumptionOperationDetails(stock.materialId)).toHaveLength(0);
});