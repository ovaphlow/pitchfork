import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 013 Aceso 药房申领与护理站补货调拨闭环 — 浏览器验收。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API、Nexus 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL        Aceso UI 地址
 *   PLAYWRIGHT_API_BASE_URL    Aceso API 地址（/crate-api 根）
 *   PLAYWRIGHT_DB_*            隔离 PostgreSQL 连接
 *   PLAYWRIGHT_USERNAME/PASSWORD 测试账号
 *   PLAYWRIGHT_NEXUS_API_BASE_URL 可选；缺省按仓库配置所在 Nexus 地址
 *
 * 主线（对照 docs/plans/013.aceso-pharmacy-requisition-and-ward-stock-transfer.md）：
 *   护理站建单（DRAFT）→ 药房审批并预留批次库存（APPROVED）→
 *   药房确认调拨（DISPENSED）→ 药房源仓库 OUTBOUND + 护理站目标仓库 INBOUND
 *   → 每个明细回写双向库存操作明细 ID；取消已审批单释放预留；重复确认幂等。
 */

const FIXTURE_PREFIX = "pw-rq-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;
const NEXUS_API_BASE =
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL ?? "http://192.168.0.109:8421/crate-api/shared/v1";

interface Warehouse {
  id: string;
  category: string;
  code: string;
  root_code: string;
  parent_code: string;
  payload: { name: string; description?: string };
  created_at: string;
  updated_at: string;
}

interface InventoryMaterial {
  id: string;
  name: string;
}

interface InventoryLot {
  id: string;
}

interface InventoryFixtureItem {
  materialId: string;
  lotId: string;
  materialName: string;
}

interface PharmacyRequisitionItem {
  id: string;
  material_id: string;
  requested_quantity: string | null;
  approved_quantity: string | null;
  dispensed_quantity: string | null;
  lot_id: string | null;
  outbound_stock_operation_detail_id: string | null;
  inbound_stock_operation_detail_id: string | null;
}

interface PharmacyRequisition {
  id: string;
  requisition_no: string;
  warehouse: string;
  destination_warehouse: string | null;
  department: string | null;
  status: string;
  items?: PharmacyRequisitionItem[];
  created_at: string | null;
  approved_at: string | null;
  dispensed_at: string | null;
  cancelled_at: string | null;
}

interface PharmacyRequisitionList {
  records: PharmacyRequisition[];
  meta: { total: number };
}

interface ApiResult<T> {
  status: number;
  body: T;
}

interface StockSnapshot {
  quantity: number;
  lockedQuantity: number;
  totalCost: number;
}

const createdRequisitionIds: string[] = [];
let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso pharmacy requisition tests`);
  return value;
}

function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

/** 清理由本文件创建的申领单和库存 fixture。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    const requisitionIds = [...createdRequisitionIds];

    // 申领：先删明细，再删主表（兼容调用端 UI/API 创建的 idempotency_key/department 前缀）
    await client.query(
      `DELETE FROM pharmacy.pharmacy_requisition_items
       WHERE id LIKE $1
          OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)
          OR requisition_id = ANY($2)
          OR requisition_id IN (
            SELECT id FROM pharmacy.pharmacy_requisitions
            WHERE id LIKE $1 OR idempotency_key LIKE $1 OR department LIKE $1 OR id = ANY($2)
          )`,
      [fixturePattern, requisitionIds],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_requisitions
       WHERE id LIKE $1 OR idempotency_key LIKE $1 OR department LIKE $1 OR id = ANY($2)`,
      [fixturePattern, requisitionIds],
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
        (SELECT count(*) FROM pharmacy.pharmacy_requisitions WHERE id LIKE $1 OR idempotency_key LIKE $1 OR department LIKE $1 OR id = ANY($2)) +
        (SELECT count(*) FROM pharmacy.pharmacy_requisition_items WHERE id LIKE $1 OR requisition_id = ANY($2)) +
        (SELECT count(*) FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM public.lots WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stocks WHERE id LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stock_operations WHERE id LIKE $1 OR metadata::text LIKE $1) +
        (SELECT count(*) FROM public.stock_operation_details WHERE id LIKE $1)
      )::text AS residual`,
      [fixturePattern, requisitionIds],
    );
    if (result.rows[0]?.residual !== "0") throw new Error("fixture cleanup left residual data");
    await client.query("COMMIT");
    createdRequisitionIds.length = 0;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 在浏览器页面上调用 Aceso API；登录后会自动携带同站 token。错误(>=400)直接抛错。 */
async function api<T>(page: Page, path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const baseUrl = requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL);
  const result = await page.evaluate(
    async ({ baseUrl: requestBaseUrl, path: requestPath, method, body, headers }) => {
      const token = localStorage.getItem("token");
      const response = await fetch(`${requestBaseUrl}${requestPath}`, {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...headers,
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
    { baseUrl, path, method: options.method ?? "GET", body: options.body, headers: options.headers ?? {} },
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

/** Nexus 仓库列表：测试前置数据必须使用真实仓库编码。 */
async function nexusListWarehouses(page: Page): Promise<Warehouse[]> {
  return page.evaluate(async ({ nexusBase }) => {
    const response = await fetch(`${nexusBase}/settings?category=warehouse&page=1&page_size=100`, {
      credentials: "include",
    });
    if (!response.ok) throw new Error(`warehouse settings failed: ${response.status}`);
    return (await response.json()) as Warehouse[];
  }, { nexusBase: NEXUS_API_BASE });
}

/** Nexus 创建仓库（仅在 e2e Nexus 缺少两个仓库时补种）。 */
async function nexusCreateWarehouse(page: Page, input: { code: string; name: string; description?: string }): Promise<Warehouse> {
  return page.evaluate(
    async ({ nexusBase, code, name, description }) => {
      const response = await fetch(`${nexusBase}/settings`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          category: "warehouse",
          code,
          parent_code: "",
          root_code: "",
          payload: { name, ...(description ? { description } : {}) },
        }),
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(`create warehouse failed: ${response.status} ${text}`);
      }
      return (await response.json()) as Warehouse;
    },
    { nexusBase: NEXUS_API_BASE, ...input },
  );
}

/** 确保至少存在两个不同仓库编码，供源/目标仓库下拉选择。 */
async function ensureTwoWarehouses(page: Page): Promise<[string, string]> {
  const warehouses = await nexusListWarehouses(page);
  if (warehouses.length >= 2) {
    return [warehouses[0].code, warehouses[1].code];
  }
  const source = warehouses[0]?.code;
  if (!source) {
    const created = await nexusCreateWarehouse(page, {
      code: "WH-E2E",
      name: "E2E总仓库",
      description: "013 e2e fixture 药房仓库",
    });
    return [created.code, await ensureTwoWarehouses(page).then(([, dest]) => dest)];
  }
  const createdDest = await nexusCreateWarehouse(page, {
    code: "WH-E2E-WARD",
    name: "E2E护理站仓",
    description: "013 e2e fixture 护理站目标仓库",
  });
  return [source, createdDest.code];
}

/** 创建启用了批次管控的物资、批次和源仓库可用库存（含一次手工入库）。 */
async function createInventoryFixtureItem(
  page: Page,
  suffix: string,
  index: number,
  warehouse: string,
): Promise<InventoryFixtureItem> {
  const marker = `${FIXTURE_PREFIX}${suffix}-${index}`;
  const material = await api<InventoryMaterial>(page, "/crate-api/inventories/v1/materials", {
    method: "POST",
    body: {
      code: `${marker}-MAT`,
      name: `${FIXTURE_PREFIX}${suffix} 药品${index}`,
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
  const lot = await api<InventoryLot>(page, "/crate-api/inventories/v1/lots", {
    method: "POST",
    body: {
      material_id: material.id,
      batch_no: `${marker}-BATCH`,
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
      note: "013 browser e2e fixture",
    },
  });
  return { materialId: material.id, lotId: lot.id, materialName: material.name };
}

/** 选择下拉框中第一个非空选项（通用辅助）。 */
async function selectFirstOption(page: Page, selectLocator: Locator) {
  await expect(selectLocator.locator("option")).not.toHaveCount(1, { timeout: 10_000 });
  const value = await selectLocator.locator("option").nth(1).getAttribute("value");
  if (!value) throw new Error("expected a non-empty select option");
  await selectLocator.selectOption(value);
}

async function selectOptionIfPresent(page: Page, selectLocator: Locator, value: string) {
  await expect(selectLocator.locator(`option[value="${value}"]`)).toHaveCount(1, { timeout: 10_000 });
  await selectLocator.selectOption(value);
}

/** UI：护理站申领 Tab 中创建申领单（DRAFT）。 */
async function createRequisitionViaUi(
  page: Page,
  department: string,
  sourceWarehouse: string,
  destinationWarehouse: string,
  items: Array<{ materialId: string; quantity: string }>,
): Promise<void> {
  await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "护理站申领" }).click();
  await page.getByRole("button", { name: "新建申领" }).click();
  const modal = modalByTitle(page, "新建护理站申领");
  await expect(modal).toBeVisible();
  await modal.locator("#req-warehouse").selectOption(sourceWarehouse);
  await modal.locator("#req-destination").selectOption(destinationWarehouse);
  await modal.getByLabel("申领科室").fill(department);

  for (let index = 0; index < items.length; index += 1) {
    if (index > 0) {
      await modal.getByRole("button", { name: "+ 添加物资" }).click();
    }
    const materialSelect = modal.locator("select").nth(2 + index);
    await selectOptionIfPresent(page, materialSelect, items[index].materialId);
    await modal.locator('input[type="number"]').nth(index).fill(items[index].quantity);
  }

  await modal.getByRole("button", { name: "提交申领" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  const row = page.getByRole("row").filter({ hasText: department });
  await expect(row).toBeVisible({ timeout: 10_000 });
}

/** UI：审批弹窗中逐项填写批准数量并选择批次。 */
async function approveRequisitionViaUi(
  page: Page,
  department: string,
  approvals: Array<{ approvedQuantity: string; lotId: string }>,
): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: department });
  await expect(row).toContainText("待审核");
  await row.getByRole("button", { name: "审批" }).click();
  const modal = modalByTitle(page, "审批申领（预留库存）");
  await expect(modal).toBeVisible();
  const tableRows = modal.locator("tbody tr");
  await expect(tableRows).toHaveCount(approvals.length);
  for (let index = 0; index < approvals.length; index += 1) {
    const itemRow = tableRows.nth(index);
    await itemRow.locator('input[type="number"]').fill(approvals[index].approvedQuantity);
    await selectOptionIfPresent(page, itemRow.locator("select"), approvals[index].lotId);
  }
  await modal.getByRole("button", { name: "确认审批并预留" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("待调拨");
}

/** UI：确认调拨（APPROVED → DISPENSED）。 */
async function dispenseRequisitionViaUi(page: Page, department: string): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: department });
  await expect(row).toContainText("待调拨");
  await row.getByRole("button", { name: "确认调拨" }).click();
  const modal = modalByTitle(page, "确认调拨");
  await expect(modal).toBeVisible();
  await modal.getByRole("button", { name: "确认调拨" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已完成");
}

/** UI：取消申领（草稿或已审批均可，按状态断言前置）。 */
async function cancelRequisitionViaUi(page: Page, department: string, expectedStatus: string, reason: string): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: department });
  await expect(row).toContainText(expectedStatus);
  await row.getByRole("button", { name: "取消" }).click();
  const modal = modalByTitle(page, "取消申领");
  await expect(modal).toBeVisible();
  await modal.getByLabel("取消原因").fill(reason);
  await modal.getByRole("button", { name: "确认取消" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已取消");
}

/** API 列表按科室取回申领单，并记录到清理集合。 */
async function captureRequisition(page: Page, department: string): Promise<PharmacyRequisition> {
  const list = await api<PharmacyRequisitionList>(
    page,
    `/crate-api/pharmacy/v1/requisitions?department=${encodeURIComponent(department)}&limit=10`,
  );
  const listRecord = list.records.find((record) => record.department === department);
  if (!listRecord) throw new Error(`requisition not found for department ${department}`);
  if (!createdRequisitionIds.includes(listRecord.id)) createdRequisitionIds.push(listRecord.id);
  const detail = await api<PharmacyRequisition>(page, `/crate-api/pharmacy/v1/requisitions/${listRecord.id}`);
  return detail;
}

/** DB：读取指定仓库/物资/批次结存。 */
async function readStock(warehouse: string, materialId: string, lotId: string): Promise<StockSnapshot | null> {
  const result = await databasePool.query<{ quantity: string; locked_quantity: string; total_cost: string }>(
    `SELECT quantity::text AS quantity, locked_quantity::text AS locked_quantity, total_cost::text AS total_cost
     FROM public.stocks
     WHERE warehouse = $1 AND material_id = $2 AND lot_id = $3`,
    [warehouse, materialId, lotId],
  );
  const row = result.rows[0];
  return row ? { quantity: Number(row.quantity), lockedQuantity: Number(row.locked_quantity), totalCost: Number(row.total_cost) } : null;
}

/** DB：013 调拨来源的库存操作数量（应为 2：源 OUTBOUND + 目标 INBOUND）。 */
async function countRequisitionTransferOperations(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(DISTINCT operation.id)::text AS count
     FROM public.stock_operations operation
     JOIN public.stock_operation_details detail ON detail.operation_id = operation.id
     WHERE detail.material_id = $1
       AND operation.metadata->>'source' = 'PHARMACY_REQUISITION_TRANSFER'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

/** DB：013 调拨来源的库存操作明细数量（每物资一条出库 + 一条入库）。 */
async function countRequisitionTransferDetails(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1
       AND operation.metadata->>'source' = 'PHARMACY_REQUISITION_TRANSFER'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

/** DB：读取申领明细的双向库存操作明细 ID。 */
async function readRequisitionDetailIds(requisitionId: string): Promise<Array<{ outbound: string | null; inbound: string | null }>> {
  const result = await databasePool.query<{ outbound_stock_operation_detail_id: string | null; inbound_stock_operation_detail_id: string | null }>(
    `SELECT outbound_stock_operation_detail_id, inbound_stock_operation_detail_id
     FROM pharmacy.pharmacy_requisition_items
     WHERE requisition_id = $1
     ORDER BY id`,
    [requisitionId],
  );
  return result.rows.map((row) => ({ outbound: row.outbound_stock_operation_detail_id, inbound: row.inbound_stock_operation_detail_id }));
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

test("013 主线：UI 新建双物资申领 → 审批预留 → 确认调拨后双仓库存与双向明细关联", async ({ page }) => {
  const suffix = "MAIN";
  const department = `${FIXTURE_PREFIX}${suffix}-科`;
  const [sourceWarehouse, destinationWarehouse] = await ensureTwoWarehouses(page);
  const item1 = await createInventoryFixtureItem(page, suffix, 1, sourceWarehouse);
  const item2 = await createInventoryFixtureItem(page, suffix, 2, sourceWarehouse);

  await createRequisitionViaUi(page, department, sourceWarehouse, destinationWarehouse, [
    { materialId: item1.materialId, quantity: "2" },
    { materialId: item2.materialId, quantity: "1" },
  ]);

  const draft = await captureRequisition(page, department);
  expect(draft.status).toBe("DRAFT");
  expect(draft.warehouse).toBe(sourceWarehouse);
  expect(draft.destination_warehouse).toBe(destinationWarehouse);
  expect(draft.department).toBe(department);
  expect(draft.items ?? []).toHaveLength(2);
  expect(Number(draft.items?.[0]?.requested_quantity ?? "0")).toBe(2);
  expect(Number(draft.items?.[1]?.requested_quantity ?? "0")).toBe(1);

  // 审批预留：源库存 quantity 不变，locked_quantity 增加，目标仓库尚无库存
  const beforeApprove1 = await readStock(sourceWarehouse, item1.materialId, item1.lotId);
  expect(beforeApprove1).toMatchObject({ quantity: 10, lockedQuantity: 0, totalCost: 25 });

  await approveRequisitionViaUi(page, department, [
    { approvedQuantity: "2", lotId: item1.lotId },
    { approvedQuantity: "1", lotId: item2.lotId },
  ]);

  const approved = await captureRequisition(page, department);
  expect(approved.status).toBe("APPROVED");
  const source1Approved = await readStock(sourceWarehouse, item1.materialId, item1.lotId);
  const source2Approved = await readStock(sourceWarehouse, item2.materialId, item2.lotId);
  expect(source1Approved).toMatchObject({ quantity: 10, lockedQuantity: 2, totalCost: 25 });
  expect(source2Approved).toMatchObject({ quantity: 10, lockedQuantity: 1, totalCost: 25 });

  // UI 确认调拨
  await dispenseRequisitionViaUi(page, department);
  const dispensed = await captureRequisition(page, department);
  expect(dispensed.status).toBe("DISPENSED");
  expect(dispensed.approved_at).toBeTruthy();
  expect(dispensed.dispensed_at).toBeTruthy();

  // 源库存扣减且预留清零；目标库存建立并入账
  const source1 = await readStock(sourceWarehouse, item1.materialId, item1.lotId);
  const source2 = await readStock(sourceWarehouse, item2.materialId, item2.lotId);
  const dest1 = await readStock(destinationWarehouse, item1.materialId, item1.lotId);
  const dest2 = await readStock(destinationWarehouse, item2.materialId, item2.lotId);
  expect(source1).toMatchObject({ quantity: 8, lockedQuantity: 0, totalCost: 20 });
  expect(source2).toMatchObject({ quantity: 9, lockedQuantity: 0, totalCost: 22.5 });
  expect(dest1).toMatchObject({ quantity: 2, lockedQuantity: 0, totalCost: 5 });
  expect(dest2).toMatchObject({ quantity: 1, lockedQuantity: 0, totalCost: 2.5 });

  // 每个物资在 013 来源下应有 2 条操作明细（源/目标各 1 条），且合计两笔库存操作
  expect(await countRequisitionTransferOperations(item1.materialId)).toBe(2);
  expect(await countRequisitionTransferOperations(item2.materialId)).toBe(2);
  expect(await countRequisitionTransferDetails(item1.materialId)).toBe(2);
  expect(await countRequisitionTransferDetails(item2.materialId)).toBe(2);

  // 申领明细回写双向 detail ID（可追溯闭环）
  const detailIds = await readRequisitionDetailIds(dispensed.id);
  expect(detailIds).toHaveLength(2);
  expect(detailIds.every((detail) => detail.outbound && detail.inbound)).toBe(true);

  // 详情弹窗展示状态和出入明细 ID 摘要
  const row = page.getByRole("row").filter({ hasText: department });
  await row.getByRole("button", { name: "详情" }).click();
  const detailModal = modalByTitle(page, "申领单详情");
  await expect(detailModal).toBeVisible();
  await expect(detailModal).toContainText("已完成");
  await expect(detailModal.locator("tbody tr").first()).toContainText("出");
  await expect(detailModal.locator("tbody tr").first()).toContainText("入");
  await detailModal.getByRole("button", { name: "关闭" }).click();
  await expect(detailModal).not.toBeVisible();
});

test("013 取消已审批申领：UI 取消释放预留且不生成库存操作", async ({ page }) => {
  const suffix = "CANCEL";
  const department = `${FIXTURE_PREFIX}${suffix}-科`;
  const [sourceWarehouse, destinationWarehouse] = await ensureTwoWarehouses(page);
  const item = await createInventoryFixtureItem(page, suffix, 1, sourceWarehouse);

  await createRequisitionViaUi(page, department, sourceWarehouse, destinationWarehouse, [
    { materialId: item.materialId, quantity: "3" },
  ]);
  const draft = await captureRequisition(page, department);
  expect(draft.status).toBe("DRAFT");

  // UI 审批 3 件，源库存被锁定 3
  await approveRequisitionViaUi(page, department, [{ approvedQuantity: "3", lotId: item.lotId }]);
  const approved = await captureRequisition(page, department);
  expect(approved.status).toBe("APPROVED");
  const lockedBeforeCancel = await readStock(sourceWarehouse, item.materialId, item.lotId);
  expect(lockedBeforeCancel).toMatchObject({ quantity: 10, lockedQuantity: 3, totalCost: 25 });

  // UI 取消已审批单：预留释放、库存不减少、无 013 调拨操作
  await cancelRequisitionViaUi(page, department, "待调拨", "护理站取消本次补货");
  const cancelled = await captureRequisition(page, department);
  expect(cancelled.status).toBe("CANCELLED");
  expect(cancelled.cancelled_at).toBeTruthy();

  const afterCancel = await readStock(sourceWarehouse, item.materialId, item.lotId);
  expect(afterCancel).toMatchObject({ quantity: 10, lockedQuantity: 0, totalCost: 25 });
  expect(await readStock(destinationWarehouse, item.materialId, item.lotId)).toBeNull();
  expect(await countRequisitionTransferOperations(item.materialId)).toBe(0);
  expect(await countRequisitionTransferDetails(item.materialId)).toBe(0);
});

test("013 确认调拨幂等：重复确认返回原结果且不重复生成库存操作", async ({ page }) => {
  const suffix = "IDEM";
  const department = `${FIXTURE_PREFIX}${suffix}-科`;
  const [sourceWarehouse, destinationWarehouse] = await ensureTwoWarehouses(page);
  const item = await createInventoryFixtureItem(page, suffix, 1, sourceWarehouse);

  // 用 API 快速走完 DRAFT → APPROVED → DISPENSED，再验证 DISPENSED 重试幂等
  const created = await api<PharmacyRequisition>(page, "/crate-api/pharmacy/v1/requisitions", {
    method: "POST",
    body: {
      warehouse: sourceWarehouse,
      destination_warehouse: destinationWarehouse,
      department,
      items: [{ material_id: item.materialId, requested_quantity: "4" }],
    },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-IDEM-KEY` },
  });
  expect(created.status).toBe("DRAFT");
  if (!createdRequisitionIds.includes(created.id)) createdRequisitionIds.push(created.id);

  const itemId = created.items?.[0]?.id;
  expect(itemId).toBeTruthy();
  const approved = await api<PharmacyRequisition>(page, `/crate-api/pharmacy/v1/requisitions/${created.id}/approve`, {
    method: "PUT",
    body: { items: [{ id: itemId, approved_quantity: "4", lot_id: item.lotId }] },
  });
  expect(approved.status).toBe("APPROVED");

  const firstDispense = await api<PharmacyRequisition>(page, `/crate-api/pharmacy/v1/requisitions/${created.id}/dispense`, {
    method: "PUT",
    body: {},
  });
  expect(firstDispense.status).toBe("DISPENSED");
  const firstOutbound = firstDispense.items?.[0]?.outbound_stock_operation_detail_id;
  const firstInbound = firstDispense.items?.[0]?.inbound_stock_operation_detail_id;
  expect(firstOutbound).toBeTruthy();
  expect(firstInbound).toBeTruthy();

  const retried = await api<PharmacyRequisition>(page, `/crate-api/pharmacy/v1/requisitions/${created.id}/dispense`, {
    method: "PUT",
    body: {},
  });
  expect(retried.status).toBe("DISPENSED");
  expect(retried.items?.[0]?.outbound_stock_operation_detail_id).toBe(firstOutbound);
  expect(retried.items?.[0]?.inbound_stock_operation_detail_id).toBe(firstInbound);

  // 库存与操作只产生一次
  const source = await readStock(sourceWarehouse, item.materialId, item.lotId);
  const dest = await readStock(destinationWarehouse, item.materialId, item.lotId);
  expect(source).toMatchObject({ quantity: 6, lockedQuantity: 0, totalCost: 15 });
  expect(dest).toMatchObject({ quantity: 4, lockedQuantity: 0, totalCost: 10 });
  expect(await countRequisitionTransferOperations(item.materialId)).toBe(2);
  expect(await countRequisitionTransferDetails(item.materialId)).toBe(2);
});