import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 014 Aceso 药房采购与供应商收货入库闭环 — 浏览器验收。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API、共享 Nexus（nexus-shared）与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL        Aceso UI 地址
 *   PLAYWRIGHT_API_BASE_URL    Aceso API 地址（/crate-api 根）
 *   PLAYWRIGHT_DB_*            隔离 PostgreSQL 连接
 *   PLAYWRIGHT_USERNAME/PASSWORD 测试账号
 *   PLAYWRIGHT_NEXUS_API_BASE_URL 共享 Nexus 地址；缺省 http://127.0.0.1:8423/crate-api/shared/v1
 *
 * 主线（对照 docs/plans/014.aceso-pharmacy-procurement-and-receiving.md §9.2）：
 *   药房建采购订单（DRAFT）→ 审核（APPROVED，业务字段冻结）→ 同订单项多批次分批到货
 *   → 每张收货写一张 PHARMACY_PURCHASE_RECEIPT 库存 INBOUND 操作、库存数量/成本增加
 *   → 收齐后 RECEIVED；零收货取消/部分收货关闭余量；幂等/超额/关闭冲突口径。
 */

const FIXTURE_PREFIX = "pw-po-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;
const NEXUS_API_BASE =
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL ?? "http://127.0.0.1:8423/crate-api/shared/v1";

interface Warehouse {
  id: string;
  code: string;
  payload: { name: string; description?: string };
}

interface InventoryMaterial {
  id: string;
  name: string;
  code: string;
}

interface InventoryLot {
  id: string;
}

interface InventoryFixtureItem {
  materialId: string;
  lotId: string;
  materialName: string;
}

interface PurchaseOrderItem {
  id: string;
  material_id: string;
  ordered_quantity: string;
  received_quantity: string;
  remaining_quantity: string;
}

interface PurchaseOrder {
  id: string;
  purchase_order_no: string;
  warehouse: string;
  supplier_name: string;
  status: string;
  requester_id: string;
  approved_by: string | null;
  approved_at: string | null;
  cancelled_at: string | null;
  cancelled_by: string | null;
  cancel_reason: string | null;
  closed_at: string | null;
  closed_by: string | null;
  close_reason: string | null;
  created_at: string;
  items?: PurchaseOrderItem[];
  receipts?: Array<{ id: string; receipt_no: string; stock_operation_id: string }>;
}

interface PurchaseOrderList {
  records: PurchaseOrder[];
  meta: { total: number };
}

interface PurchaseReceiptItem {
  id: string;
  purchase_order_item_id: string;
  material_id: string;
  lot_id: string | null;
  received_quantity: string;
  unit_cost: string;
  total_cost: string;
  stock_operation_detail_id: string;
}

interface PurchaseReceipt {
  id: string;
  receipt_no: string;
  purchase_order_id: string;
  warehouse: string;
  supplier_name: string;
  received_by: string;
  received_at: string;
  stock_operation_id: string;
  items: PurchaseReceiptItem[];
  order?: PurchaseOrder;
}

interface StockTotals {
  quantity: number;
  lockedQuantity: number;
  totalCost: number;
}

interface ApiResult<T> {
  status: number;
  body: T;
}

const createdOrderIds: string[] = [];
let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso purchase order tests`);
  return value;
}

function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
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
async function rawApi<T>(page: Page, path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<{ status: number; body: T | { error: string } }> {
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

// ─── Nexus 仓库 ────────────────────────────────────────────────────────────

async function nexusListWarehouses(page: Page): Promise<Warehouse[]> {
  return page.evaluate(async ({ nexusBase }) => {
    const response = await fetch(`${nexusBase}/settings?category=warehouse&page=1&page_size=100`, {
      credentials: "include",
    });
    if (!response.ok) throw new Error(`warehouse settings failed: ${response.status}`);
    return (await response.json()) as Warehouse[];
  }, { nexusBase: NEXUS_API_BASE });
}

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

/** 确保至少存在一个仓库编码，供药房采购仓库下拉选择。 */
async function ensureWarehouse(page: Page): Promise<string> {
  const warehouses = await nexusListWarehouses(page);
  if (warehouses.length >= 1) return warehouses[0].code;
  const created = await nexusCreateWarehouse(page, {
    code: "WH-PO-E2E",
    name: "E2E药房库",
    description: "014 e2e fixture 药房仓库",
  });
  return created.code;
}

// ─── 库存 fixture（批次管控物资 + 批次 + 仓库库存行） ─────────────────────

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
  // 初始库存行让该物资出现在采购物资下拉中（页面按仓库已有库存聚合选项）
  await api<unknown>(page, "/crate-api/inventories/v1/operations/inbound", {
    method: "POST",
    body: {
      warehouse,
      items: [{ material_id: material.id, lot_id: lot.id, quantity: "2", unit_cost: "2.50" }],
      note: "014 browser e2e fixture",
    },
  });
  return { materialId: material.id, lotId: lot.id, materialName: material.name };
}

async function selectOptionIfPresent(page: Page, selectLocator: Locator, value: string) {
  await expect(selectLocator.locator(`option[value="${value}"]`)).toHaveCount(1, { timeout: 10_000 });
  await selectLocator.selectOption(value);
}

// ─── UI 操作 ───────────────────────────────────────────────────────────────

async function openPurchaseTab(page: Page) {
  await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "采购收货" }).click();
}

async function createOrderViaUi(
  page: Page,
  warehouse: string,
  supplier: string,
  items: Array<{ materialId: string; quantity: string }>,
): Promise<void> {
  await openPurchaseTab(page);
  await page.getByRole("button", { name: "新建采购订单" }).click();
  const modal = modalByTitle(page, "新建采购订单");
  await expect(modal).toBeVisible();
  await modal.locator("#po-warehouse").selectOption(warehouse);
  await modal.getByLabel("供应商名称").fill(supplier);

  for (let index = 0; index < items.length; index += 1) {
    if (index > 0) {
      await modal.getByRole("button", { name: "+ 添加物资" }).click();
    }
    const materialSelect = modal.locator("select").nth(1 + index);
    await selectOptionIfPresent(page, materialSelect, items[index].materialId);
    // 数量输入与同一行物资 select 绑定，避免添加行后全局 nth 序号漂移
    await materialSelect.locator("xpath=..").locator('input[type="number"]').fill(items[index].quantity);
  }

  await modal.getByRole("button", { name: "创建采购订单" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  const row = page.getByRole("row").filter({ hasText: supplier });
  await expect(row).toBeVisible({ timeout: 10_000 });
}

async function approveOrderViaUi(page: Page, supplier: string): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: supplier });
  await expect(row).toContainText("草稿");
  await row.getByRole("button", { name: "审核" }).click();
  const modal = modalByTitle(page, "审核采购订单");
  await expect(modal).toBeVisible();
  await modal.getByRole("button", { name: "确认审核" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已审核");
}

async function cancelOrderViaUi(page: Page, supplier: string, reason: string): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: supplier });
  await expect(row).toContainText("已审核");
  await row.getByRole("button", { name: "取消" }).click();
  const modal = modalByTitle(page, "取消采购订单");
  await expect(modal).toBeVisible();
  await modal.getByLabel("原因").fill(reason);
  await modal.getByRole("button", { name: "确认取消" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已取消");
}

async function closeOrderViaUi(page: Page, supplier: string, reason: string): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: supplier });
  await expect(row).toContainText("部分收货");
  await row.getByRole("button", { name: "关闭余量" }).click();
  const modal = modalByTitle(page, "关闭采购订单");
  await expect(modal).toBeVisible();
  await modal.getByLabel("原因").fill(reason);
  await modal.getByRole("button", { name: "确认关闭" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已关闭");
}

/** 收货弹窗：按物资匹配对应收货块并填写到货行（服务端明细顺序不保证与提交一致）。 */
async function receiveOrderViaUi(
  page: Page,
  supplier: string,
  lines: Array<{ materialId: string; quantity: string; batchNo: string; productionDate: string; expiryDate: string; manufacturer: string; unitCost: string }>,
): Promise<void> {
  const row = page.getByRole("row").filter({ hasText: supplier });
  const receiveButton = row.getByRole("button").first(); // 供应商收货 / 继续收货
  await receiveButton.click();
  const modal = modalByTitle(page, "供应商收货");
  await expect(modal).toBeVisible();

  // 每项收货块：rounded-md border border-border p-3 space-y-2（外层订单头无 space-y-2）。
  // 该弹窗各 Input 未传显式 id，重复 label 派生的 id 相同，getByLabel 会把两个块的
  // 同名单一 id 都解析到第一个块，故按块内 input 固定顺序（收货数量、实际成本、批号、
  // 生产企业、生产日期、有效期）定位；块以物资 ID 文本唯一匹配。
  const itemBlocks = modal.locator("div.rounded-md.border.border-border.p-3.space-y-2");
  await expect(itemBlocks).toHaveCount(lines.length);
  for (const line of lines) {
    const block = itemBlocks.filter({ hasText: line.materialId }).first();
    await expect(block).toHaveCount(1);
    const inputs = block.locator("input");
    await expect(inputs).toHaveCount(6);
    await inputs.nth(0).fill(line.quantity);
    await inputs.nth(1).fill(line.unitCost);
    await inputs.nth(2).fill(line.batchNo);
    await inputs.nth(3).fill(line.manufacturer);
    await inputs.nth(4).fill(line.productionDate);
    await inputs.nth(5).fill(line.expiryDate);
  }

  await modal.getByRole("button", { name: "确认收货入库" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
}

// ─── API 捕获与 DB 断言 ────────────────────────────────────────────────────

/** 按物资 ID 取回订单项（响应明细顺序由 ULID 排序决定，不保证与提交一致）。 */
function itemOf(order: PurchaseOrder, materialId: string): PurchaseOrderItem {
  const item = order.items?.find((candidate) => candidate.material_id === materialId);
  if (!item) throw new Error(`purchase order item missing for material ${materialId}`);
  return item;
}

async function captureOrder(page: Page, supplier: string): Promise<PurchaseOrder> {
  const list = await api<PurchaseOrderList>(
    page,
    `/crate-api/pharmacy/v1/purchase-orders?supplier_name=${encodeURIComponent(supplier)}&limit=10`,
  );
  const record = list.records.find((order) => order.supplier_name === supplier);
  if (!record) throw new Error(`purchase order not found for supplier ${supplier}`);
  if (!createdOrderIds.includes(record.id)) createdOrderIds.push(record.id);
  return api<PurchaseOrder>(page, `/crate-api/pharmacy/v1/purchase-orders/${record.id}`);
}

async function countPurchaseReceiptOperations(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(DISTINCT operation.id)::text AS count
     FROM public.stock_operations operation
     JOIN public.stock_operation_details detail ON detail.operation_id = operation.id
     WHERE detail.material_id = $1
       AND operation.metadata->>'source' = 'PHARMACY_PURCHASE_RECEIPT'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

async function countPurchaseReceiptDetails(materialId: string): Promise<number> {
  const result = await databasePool.query<{ count: string }>(
    `SELECT count(*)::text AS count
     FROM public.stock_operation_details detail
     JOIN public.stock_operations operation ON operation.id = detail.operation_id
     WHERE detail.material_id = $1
       AND operation.metadata->>'source' = 'PHARMACY_PURCHASE_RECEIPT'`,
    [materialId],
  );
  return Number(result.rows[0]?.count ?? "0");
}

/** DB：读取指定仓库/物资所有批次的库存数量/锁定量/总成本汇总。 */
async function readStockTotals(warehouse: string, materialId: string): Promise<StockTotals | null> {
  const result = await databasePool.query<{ quantity: string; locked_quantity: string; total_cost: string }>(
    `SELECT COALESCE(sum(quantity), 0)::text AS quantity,
            COALESCE(sum(locked_quantity), 0)::text AS locked_quantity,
            COALESCE(sum(total_cost), 0)::text AS total_cost
     FROM public.stocks
     WHERE warehouse = $1 AND material_id = $2`,
    [warehouse, materialId],
  );
  const row = result.rows[0];
  return row
    ? { quantity: Number(row.quantity), lockedQuantity: Number(row.locked_quantity), totalCost: Number(row.total_cost) }
    : null;
}

/** 清理由本文件创建的采购/收货记录与库存 fixture。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  const orderIds = [...createdOrderIds];
  try {
    await client.query("BEGIN");

    // 收货/订单：先删明细再删主表（兼容 UI/API 创建：按前缀/幂等键/单号/供应商/已跟踪 id 匹配）
    await client.query(
      `DELETE FROM pharmacy.pharmacy_purchase_receipt_items
       WHERE receipt_id IN (
         SELECT id FROM pharmacy.pharmacy_purchase_receipts
         WHERE purchase_order_id IN (
           SELECT id FROM pharmacy.pharmacy_purchase_orders
           WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)
         )
       )`,
      [fixturePattern, orderIds],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_purchase_receipts
       WHERE id LIKE $1
          OR purchase_order_id IN (
            SELECT id FROM pharmacy.pharmacy_purchase_orders
            WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)
          )`,
      [fixturePattern, orderIds],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_purchase_order_items
       WHERE id LIKE $1
          OR purchase_order_id IN (
            SELECT id FROM pharmacy.pharmacy_purchase_orders
            WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)
          )`,
      [fixturePattern, orderIds],
    );
    await client.query(
      `DELETE FROM pharmacy.pharmacy_purchase_orders
       WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)`,
      [fixturePattern, orderIds],
    );

    // 库存 fixture：先收集 API 生成的真实 ULID，再按物资/批次/操作关联删除
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
       WHERE id LIKE $1 OR material_id = ANY($2) OR operation_id = ANY($3)`,
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
        (SELECT count(*) FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)) +
        (SELECT count(*) FROM pharmacy.pharmacy_purchase_order_items WHERE id LIKE $1 OR purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2))) +
        (SELECT count(*) FROM pharmacy.pharmacy_purchase_receipts WHERE id LIKE $1 OR purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2))) +
        (SELECT count(*) FROM pharmacy.pharmacy_purchase_receipt_items WHERE id LIKE $1 OR receipt_id IN (SELECT id FROM pharmacy.pharmacy_purchase_receipts WHERE purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE $1 OR idempotency_key LIKE $1 OR purchase_order_no LIKE $1 OR supplier_name LIKE $1 OR id = ANY($2)))) +
        (SELECT count(*) FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM public.lots WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stocks WHERE id LIKE $1 OR material_id IN (SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)) +
        (SELECT count(*) FROM public.stock_operations WHERE id LIKE $1 OR metadata::text LIKE $1) +
        (SELECT count(*) FROM public.stock_operation_details WHERE id LIKE $1)
      )::text AS residual`,
      [fixturePattern, orderIds],
    );
    if (result.rows[0]?.residual !== "0") throw new Error("fixture cleanup left residual data");
    await client.query("COMMIT");
    createdOrderIds.length = 0;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 窄屏（viewport < 600）时校验表格容器横向可滚动且不超出视口宽度。 */
async function assertNarrowScrollable(tableWrapper: Locator) {
  const page = tableWrapper.page();
  const width = page.viewportSize()?.width ?? 1280;
  if (width >= 600) return;
  const box = await tableWrapper.boundingBox();
  expect(box).not.toBeNull();
  const scrollable = await tableWrapper.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(scrollable).toBe(true);
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
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

// ─── 主线 ──────────────────────────────────────────────────────────────────

test("014 主线：UI 建单→审核→同订单项双批次分批收货→收讫，库存/成本/入库操作可追溯", async ({ page }) => {
  const suffix = "MAIN";
  const supplier = `${FIXTURE_PREFIX}${suffix} 华康医药配送`;
  const warehouse = await ensureWarehouse(page);
  const item1 = await createInventoryFixtureItem(page, suffix, 1, warehouse);
  const item2 = await createInventoryFixtureItem(page, suffix, 2, warehouse);
  // 初始 fixture 库存每物资 2 件 @2.50
  await expect(await readStockTotals(warehouse, item1.materialId)).toMatchObject({ quantity: 2, lockedQuantity: 0, totalCost: 5 });

  // UI 创建采购订单：item1 订购 100，item2 订购 40
  await createOrderViaUi(page, warehouse, supplier, [
    { materialId: item1.materialId, quantity: "100" },
    { materialId: item2.materialId, quantity: "40" },
  ]);
  const draft = await captureOrder(page, supplier);
  expect(draft.status).toBe("DRAFT");
  expect(draft.warehouse).toBe(warehouse);
  expect(draft.supplier_name).toBe(supplier);
  expect(draft.items ?? []).toHaveLength(2);
  expect(Number(itemOf(draft, item1.materialId).ordered_quantity)).toBe(100);
  expect(Number(itemOf(draft, item2.materialId).ordered_quantity)).toBe(40);
  expect(draft.requester_id).toBeTruthy();
  expect(draft.receipts ?? []).toHaveLength(0);

  // 审核：业务字段冻结
  await approveOrderViaUi(page, supplier);
  const approved = await captureOrder(page, supplier);
  expect(approved.status).toBe("APPROVED");
  expect(approved.approved_by).toBeTruthy();
  expect(approved.approved_at).toBeTruthy();

  // 审核后尝试编辑（PUT）→ 409，订单业务字段未被修改
  const editAttempt = await rawApi<{ error: string }>(page, `/crate-api/pharmacy/v1/purchase-orders/${approved.id}`, {
    method: "PUT",
    body: { warehouse, supplier_name: "篡改供应商", items: [{ material_id: item1.materialId, ordered_quantity: "1" }] },
  });
  expect(editAttempt.status).toBe(409);
  expect((editAttempt.body as { error: string }).error).toBeTruthy();

  // 第一批到货：item1 批次 A 60 件 + item2 批次 C 20 件 → 部分收货
  await receiveOrderViaUi(page, supplier, [
    { materialId: item1.materialId, quantity: "60", batchNo: `${FIXTURE_PREFIX}${suffix}-A`, productionDate: "2026-04-01", expiryDate: "2028-03-31", manufacturer: "某制药一厂", unitCost: "12.5" },
    { materialId: item2.materialId, quantity: "20", batchNo: `${FIXTURE_PREFIX}${suffix}-C`, productionDate: "2026-05-01", expiryDate: "2028-04-30", manufacturer: "某制药二厂", unitCost: "12.5" },
  ]);
  const partial = await captureOrder(page, supplier);
  expect(partial.status).toBe("PARTIALLY_RECEIVED");
  expect(Number(itemOf(partial, item1.materialId).received_quantity)).toBe(60);
  expect(Number(itemOf(partial, item1.materialId).remaining_quantity)).toBe(40);
  expect(Number(itemOf(partial, item2.materialId).received_quantity)).toBe(20);
  expect(Number(itemOf(partial, item2.materialId).remaining_quantity)).toBe(20);
  expect(partial.receipts ?? []).toHaveLength(1);
  const firstReceiptId = partial.receipts?.[0]?.id;
  expect(firstReceiptId).toBeTruthy();

  // 收货凭证详情：供应商/仓库/收货人/时间、双批次、实际成本、唯一库存操作、每项操作明细
  const receipt1 = await api<PurchaseReceipt>(page, `/crate-api/pharmacy/v1/purchase-receipts/${firstReceiptId}`);
  expect(receipt1.warehouse).toBe(warehouse);
  expect(receipt1.supplier_name).toBe(supplier);
  expect(receipt1.received_by).toBeTruthy();
  expect(receipt1.received_at).toBeTruthy();
  expect(receipt1.stock_operation_id).toBeTruthy();
  expect(receipt1.items).toHaveLength(2);
  for (const receiptItem of receipt1.items) {
    expect(receiptItem.lot_id).toBeTruthy();
    expect(Number(receiptItem.unit_cost)).toBe(12.5);
    expect(receiptItem.stock_operation_detail_id).toBeTruthy();
    expect(Number(receiptItem.total_cost)).toBe(Number(receiptItem.received_quantity) * 12.5);
  }

  // 第二批到货：item1 批次 B 40 件 + item2 批次 D 20 件 → 收讫（同订单项两批次分批到货）
  await receiveOrderViaUi(page, supplier, [
    { materialId: item1.materialId, quantity: "40", batchNo: `${FIXTURE_PREFIX}${suffix}-B`, productionDate: "2026-06-01", expiryDate: "2028-05-31", manufacturer: "某制药一厂", unitCost: "12.5" },
    { materialId: item2.materialId, quantity: "20", batchNo: `${FIXTURE_PREFIX}${suffix}-D`, productionDate: "2026-06-15", expiryDate: "2028-06-14", manufacturer: "某制药二厂", unitCost: "12.5" },
  ]);
  const received = await captureOrder(page, supplier);
  expect(received.status).toBe("RECEIVED");
  expect(received.receipts ?? []).toHaveLength(2);
  // 每项积压已收量等于订购量，剩余为 0
  expect(Number(itemOf(received, item1.materialId).received_quantity)).toBe(100);
  expect(Number(itemOf(received, item1.materialId).remaining_quantity)).toBe(0);
  expect(Number(itemOf(received, item2.materialId).received_quantity)).toBe(40);
  expect(Number(itemOf(received, item2.materialId).remaining_quantity)).toBe(0);

  // 库存与成本：初始 2 件 + 两次收货，锁定量恒为 0
  const stock1 = await readStockTotals(warehouse, item1.materialId);
  const stock2 = await readStockTotals(warehouse, item2.materialId);
  expect(stock1).toMatchObject({ quantity: 102, lockedQuantity: 0, totalCost: 5 + 60 * 12.5 + 40 * 12.5 });
  expect(stock2).toMatchObject({ quantity: 42, lockedQuantity: 0, totalCost: 5 + 20 * 12.5 + 20 * 12.5 });

  // 每个物资恰有 2 张 PHARMACY_PURCHASE_RECEIPT 库存操作（每次收货一张）、每条到货一条明细
  expect(await countPurchaseReceiptOperations(item1.materialId)).toBe(2);
  expect(await countPurchaseReceiptOperations(item2.materialId)).toBe(2);
  expect(await countPurchaseReceiptDetails(item1.materialId)).toBe(2);
  expect(await countPurchaseReceiptDetails(item2.materialId)).toBe(2);

  // 订单详情弹窗：展示状态、订购/已收/剩余，收货凭证可追踪到库存操作
  const row = page.getByRole("row").filter({ hasText: supplier });
  await row.getByRole("button", { name: "详情" }).click();
  const detailModal = modalByTitle(page, "采购订单详情");
  await expect(detailModal).toBeVisible();
  await expect(detailModal).toContainText("已收讫");
  await expect(detailModal.locator("tbody tr")).toHaveCount(2);
  await expect(detailModal).toContainText("剩余");
  await expect(detailModal.getByText("库存操作", { exact: false }).first()).toBeVisible();
  await detailModal.getByRole("button", { name: "关闭" }).click();
  await expect(detailModal).not.toBeVisible();

  // 窄屏：列表表格容器横向可滚动且不超出视口
  await assertNarrowScrollable(page.locator(".overflow-x-auto").first());
});

// ─── 取消（零收货 APPROVED） ──────────────────────────────────────────────

test("014 取消零收货订单：UI 审核后取消，无收货凭证且无库存操作", async ({ page }) => {
  const suffix = "CANCEL";
  const supplier = `${FIXTURE_PREFIX}${suffix} 康民医药`;
  const warehouse = await ensureWarehouse(page);
  const item = await createInventoryFixtureItem(page, suffix, 1, warehouse);

  await createOrderViaUi(page, warehouse, supplier, [{ materialId: item.materialId, quantity: "10" }]);
  const draft = await captureOrder(page, supplier);
  expect(draft.status).toBe("DRAFT");

  await approveOrderViaUi(page, supplier);
  const approved = await captureOrder(page, supplier);
  expect(approved.status).toBe("APPROVED");
  expect(approved.receipts ?? []).toHaveLength(0);

  // 零收货取消：DRAFT 已流转到 APPROVED，仍允许取消（零收货）
  await cancelOrderViaUi(page, supplier, "供应商无法供货，取消本次采购");
  const cancelled = await captureOrder(page, supplier);
  expect(cancelled.status).toBe("CANCELLED");
  expect(cancelled.cancelled_at).toBeTruthy();
  expect(cancelled.cancel_reason).toBe("供应商无法供货，取消本次采购");
  expect(cancelled.receipts ?? []).toHaveLength(0);

  // 无任何收货凭证与 PHARMACY_PURCHASE_RECEIPT 库存操作
  expect(await countPurchaseReceiptOperations(item.materialId)).toBe(0);
  expect(await countPurchaseReceiptDetails(item.materialId)).toBe(0);
  // 初始 fixture 库存不受影响
  expect(await readStockTotals(warehouse, item.materialId)).toMatchObject({ quantity: 2, lockedQuantity: 0, totalCost: 5 });
});

// ─── 关闭余量（部分收货） ─────────────────────────────────────────────────

test("014 部分收货后关闭余量：CLOSED 且不新增库存操作，不再显示可收货", async ({ page }) => {
  const suffix = "CLOSE";
  const supplier = `${FIXTURE_PREFIX}${suffix} 恒康医药`;
  const warehouse = await ensureWarehouse(page);
  const item = await createInventoryFixtureItem(page, suffix, 1, warehouse);

  await createOrderViaUi(page, warehouse, supplier, [{ materialId: item.materialId, quantity: "10" }]);
  const draft = await captureOrder(page, supplier);
  expect(draft.status).toBe("DRAFT");
  const itemId = draft.items?.[0]?.id;
  expect(itemId).toBeTruthy();

  await approveOrderViaUi(page, supplier);

  // UI 部分收货 4 件 → PARTIALLY_RECEIVED
  await receiveOrderViaUi(page, supplier, [
    { materialId: item.materialId, quantity: "4", batchNo: `${FIXTURE_PREFIX}${suffix}-E`, productionDate: "2026-02-01", expiryDate: "2028-01-31", manufacturer: "某制药厂", unitCost: "3.00" },
  ]);
  const partial = await captureOrder(page, supplier);
  expect(partial.status).toBe("PARTIALLY_RECEIVED");
  const stockBeforeClose = await readStockTotals(warehouse, item.materialId);
  expect(stockBeforeClose).toMatchObject({ quantity: 6, lockedQuantity: 0, totalCost: 5 + 4 * 3 });

  // 关闭余量：只终止剩余收货权利，不改变已入库库存/凭证
  await closeOrderViaUi(page, supplier, "后续批次不再到货");
  const closed = await captureOrder(page, supplier);
  expect(closed.status).toBe("CLOSED");
  expect(closed.closed_at).toBeTruthy();
  expect(closed.close_reason).toBe("后续批次不再到货");

  const stockAfterClose = await readStockTotals(warehouse, item.materialId);
  expect(stockAfterClose).toMatchObject({ quantity: 6, lockedQuantity: 0, totalCost: 5 + 4 * 3 });
  // 只发生一次收货（1 张操作、1 条明细），关闭不新增
  expect(await countPurchaseReceiptOperations(item.materialId)).toBe(1);
  expect(await countPurchaseReceiptDetails(item.materialId)).toBe(1);

  // 已关闭订单不再显示可收货/关闭按钮，仅剩详情
  const row = page.getByRole("row").filter({ hasText: supplier });
  await expect(row).toContainText("已关闭");
  await expect(row.getByRole("button", { name: "供应商收货" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "继续收货" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "关闭余量" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "详情" })).toHaveCount(1);

  // 已关闭订单再次收货 → 409
  const receiveOnClosed = await rawApi<{ error: string }>(page, `/crate-api/pharmacy/v1/purchase-orders/${closed.id}/receipts`, {
    method: "POST",
    body: { items: [{ purchase_order_item_id: itemId, received_quantity: "6", batch_no: `${FIXTURE_PREFIX}${suffix}-F`, expiry_date: "2028-02-28", unit_cost: "3.00" }] },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-CLOSED-RX` },
  });
  expect(receiveOnClosed.status).toBe(409);
});

// ─── 幂等与冲突口径（API） ────────────────────────────────────────────────

test("014 幂等重放与冲突：同键同内容返回原结果、同键异内容 409、超额收货 409、审核后编辑 409", async ({ page }) => {
  const suffix = "IDEM";
  const supplier = `${FIXTURE_PREFIX}${suffix} 济世医药`;
  const warehouse = await ensureWarehouse(page);
  const item = await createInventoryFixtureItem(page, suffix, 1, warehouse);

  // API 创建订单（携带幂等键 K）
  const created = await api<PurchaseOrder>(page, "/crate-api/pharmacy/v1/purchase-orders", {
    method: "POST",
    body: {
      warehouse,
      supplier_name: supplier,
      items: [{ material_id: item.materialId, ordered_quantity: "10" }],
    },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-CREATE-KEY` },
  });
  expect(created.status).toBe("DRAFT");
  if (!createdOrderIds.includes(created.id)) createdOrderIds.push(created.id);
  const itemId = created.items?.[0]?.id;
  expect(itemId).toBeTruthy();

  // 同键同内容重试 → 200，返回同一订单，不重复建单
  const replayed = await api<PurchaseOrder>(page, "/crate-api/pharmacy/v1/purchase-orders", {
    method: "POST",
    body: {
      warehouse,
      supplier_name: supplier,
      items: [{ material_id: item.materialId, ordered_quantity: "10" }],
    },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-CREATE-KEY` },
  });
  expect(replayed.id).toBe(created.id);

  const approve = await api<PurchaseOrder>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}/approve`, {
    method: "PUT",
    body: {},
  });
  expect(approve.status).toBe("APPROVED");

  // 审核后编辑 → 409
  const editAfterApprove = await rawApi<{ error: string }>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}`, {
    method: "PUT",
    body: { warehouse, supplier_name: "改供应商", items: [{ material_id: item.materialId, ordered_quantity: "5" }] },
  });
  expect(editAfterApprove.status).toBe(409);

  const batchA = `${FIXTURE_PREFIX}${suffix}-A`;
  const receiptBody = {
    items: [
      { purchase_order_item_id: itemId, received_quantity: "6", batch_no: batchA, production_date: "2026-03-01", expiry_date: "2028-02-29", manufacturer: "某制药厂", unit_cost: "4.00" },
    ],
  };

  // 收货（携带幂等键 R）→ 201
  const firstReceive = await api<PurchaseReceipt>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}/receipts`, {
    method: "POST",
    body: receiptBody,
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-RX-KEY` },
  });
  expect(firstReceive.order?.status).toBe("PARTIALLY_RECEIVED");

  // 同键同内容重放 → 200 返回同一收货凭证，不重复入库
  const replayedReceipt = await rawApi<PurchaseReceipt>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}/receipts`, {
    method: "POST",
    body: receiptBody,
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-RX-KEY` },
  });
  expect(replayedReceipt.status).toBe(200);
  expect((replayedReceipt.body as PurchaseReceipt).id).toBe(firstReceive.id);
  expect(await countPurchaseReceiptOperations(item.materialId)).toBe(1);
  expect(await countPurchaseReceiptDetails(item.materialId)).toBe(1);
  expect(await readStockTotals(warehouse, item.materialId)).toMatchObject({ quantity: 8, lockedQuantity: 0, totalCost: 5 + 6 * 4 });

  // 同键不同内容（改了效期）→ 409
  const changedReceipt = await rawApi<{ error: string }>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}/receipts`, {
    method: "POST",
    body: {
      items: [{ purchase_order_item_id: itemId, received_quantity: "6", batch_no: batchA, production_date: "2026-03-01", expiry_date: "2029-01-01", manufacturer: "某制药厂", unit_cost: "4.00" }],
    },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-RX-KEY` },
  });
  expect(changedReceipt.status).toBe(409);

  // 超额收货（6 + 6 > 10）→ 409，库存/凭证不变
  const overReceipt = await rawApi<{ error: string }>(page, `/crate-api/pharmacy/v1/purchase-orders/${created.id}/receipts`, {
    method: "POST",
    body: {
      items: [{ purchase_order_item_id: itemId, received_quantity: "6", batch_no: batchA, production_date: "2026-03-01", expiry_date: "2028-02-29", manufacturer: "某制药厂", unit_cost: "4.00" }],
    },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-RX-KEY-OVER` },
  });
  expect(overReceipt.status).toBe(409);
  expect(await countPurchaseReceiptOperations(item.materialId)).toBe(1);
  expect(await readStockTotals(warehouse, item.materialId)).toMatchObject({ quantity: 8, lockedQuantity: 0, totalCost: 5 + 6 * 4 });
});
