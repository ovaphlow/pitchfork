import { Pool } from "pg";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 库存基础单位与包装辅助 — 浏览器验收（015 计划已被 016 替代，本套件按当前 016 实现验收：
 * `materials.base_unit/quantity_scale/package_unit/package_size`，库存账本默认基础单位，
 * 包装仅作展示/盘点辅助；对应计划 §6.3 1/2/5 与 015 §9.3 继承口径）。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API、共享 Nexus 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL / PLAYWRIGHT_API_BASE_URL / PLAYWRIGHT_DB_* / PLAYWRIGHT_USERNAME/PASSWORD
 *
 * 主线：新建片剂物资（片、0 位小数、盒=24）→ UI 录入 2 盒入库 → 结存/可用/成本 48 片 +
 * 整包展示；0.5 片（scale=0 超精度）与服务端错误保持库存不变；非包装物资按基础数量入库。
 */

const FIXTURE_PREFIX = "pw-iv-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;
const NEXUS_API_BASE =
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL ?? "http://127.0.0.1:8423/crate-api/shared/v1";

interface Warehouse { id: string; code: string; payload: { name: string } }
interface InventoryMaterial { id: string; code: string; name: string; base_unit: string; quantity_scale: number; package_unit: string | null; package_size: string | null; status: string }
interface InventoryLot { id: string }
interface ApiResult<T> { status: number; body: T }

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the inventory base unit tests`);
  return value;
}

function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

/** 在浏览器页面上调用 Aceso API；登录后自动携带同站 token。错误(>=400)直接抛错。 */
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
      try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = { raw: text }; }
      return { status: response.status, body: parsed };
    },
    { baseUrl, path, method: options.method ?? "GET", body: options.body, headers: options.headers ?? {} },
  ) as ApiResult<T>;
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}: ${JSON.stringify(result.body)}`);
  return result.body;
}

/** 原样返回状态码与响应体（不抛错）。 */
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

async function nexusListWarehouses(page: Page): Promise<Warehouse[]> {
  return page.evaluate(async ({ nexusBase }) => {
    const response = await fetch(`${nexusBase}/settings?category=warehouse&page=1&page_size=100`, { credentials: "include" });
    if (!response.ok) throw new Error(`warehouse settings failed: ${response.status}`);
    return (await response.json()) as Warehouse[];
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
        body: JSON.stringify({ category: "warehouse", code: "WH-IV-E2E", parent_code: "", root_code: "", payload: { name: "E2E库存库", description: "016 e2e fixture" } }),
      });
      if (!response.ok) throw new Error(`create warehouse failed: ${response.status}`);
      return (await response.json()) as Warehouse;
    },
    { nexusBase: NEXUS_API_BASE },
  );
  return created.code;
}

/** 创建片剂物资（片、0 位小数，可选 盒=24 包装），批控可选。 */
async function createMaterialFixture(
  page: Page,
  suffix: string,
  index: number,
  input: { packaged: boolean; batchControl?: boolean },
): Promise<InventoryMaterial> {
  const marker = `${FIXTURE_PREFIX}${suffix}-${index}`;
  const material = await api<InventoryMaterial>(page, "/crate-api/inventories/v1/materials", {
    method: "POST",
    body: {
      code: `${marker}-MAT`,
      name: `${FIXTURE_PREFIX}${suffix} 药品${index}`,
      category: "药品",
      base_unit: "片",
      quantity_scale: 0,
      ...(input.packaged ? { package_unit: "盒", package_size: "24" } : {}),
      enable_batch_control: input.batchControl ?? false,
      cost_method: "FIFO",
      status: "ACTIVE",
    },
  });
  return material;
}

async function createLotFixture(page: Page, materialId: string, suffix: string): Promise<InventoryLot> {
  return api<InventoryLot>(page, `/crate-api/inventories/v1/lots`, {
    method: "POST",
    body: {
      material_id: materialId,
      batch_no: `${FIXTURE_PREFIX}${suffix}-BATCH`,
      production_date: "2026-01-01",
      expiry_date: "2028-12-31",
      manufacturer: "测试药厂",
    },
  });
}

async function selectOptionIfPresent(page: Page, selectLocator: Locator, value: string) {
  await expect(selectLocator.locator(`option[value="${value}"]`)).toHaveCount(1, { timeout: 10_000 });
  await selectLocator.selectOption(value);
}

/** DB：读取指定仓库/物资/批次的结存。 */
async function readStock(
  warehouse: string,
  materialId: string,
  lotId: string | null,
): Promise<{ quantity: number; lockedQuantity: number; availableQuantity: number; totalCost: number } | null> {
  const result = await databasePool.query<{ quantity: string; locked_quantity: string; total_cost: string }>(
    `SELECT quantity::text AS quantity, locked_quantity::text AS locked_quantity, total_cost::text AS total_cost
     FROM public.stocks
     WHERE warehouse = $1 AND material_id = $2 AND ($3::text IS NULL OR lot_id = $3::text)`,
    [warehouse, materialId, lotId],
  );
  const row = result.rows[0];
  if (!row) return null;
  return {
    quantity: Number(row.quantity),
    lockedQuantity: Number(row.locked_quantity),
    availableQuantity: Number(row.quantity) - Number(row.locked_quantity),
    totalCost: Number(row.total_cost),
  };
}

/** 清理由本文件创建的物资/批次/库存/操作 fixture。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    const materials = await client.query<{ id: string }>(
      `SELECT id FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1`,
      [fixturePattern],
    );
    const materialIds = materials.rows.map((row) => row.id);
    const operations = materialIds.length
      ? await client.query<{ id: string }>(
          `SELECT DISTINCT detail.operation_id AS id
           FROM public.stock_operation_details detail JOIN public.materials material ON material.id = detail.material_id
           WHERE material.id = ANY($1)`,
          [materialIds],
        )
      : { rows: [] as Array<{ id: string }> };
    const operationIds = operations.rows.map((row) => row.id);

    await client.query(
      `DELETE FROM public.stock_operation_details WHERE id LIKE $1 OR material_id = ANY($2) OR operation_id = ANY($3)`,
      [fixturePattern, materialIds, operationIds],
    );
    await client.query(
      `DELETE FROM public.stock_operations WHERE id LIKE $1 OR metadata::text LIKE $1 OR id = ANY($2)`,
      [fixturePattern, operationIds],
    );
    await client.query(`DELETE FROM public.stocks WHERE id LIKE $1 OR material_id = ANY($2)`, [fixturePattern, materialIds]);
    await client.query(`DELETE FROM public.lots WHERE id LIKE $1 OR batch_no LIKE $1 OR material_id = ANY($2)`, [fixturePattern, materialIds]);
    await client.query(`DELETE FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1 OR id = ANY($2)`, [fixturePattern, materialIds]);

    const residual = await client.query<{ n: string }>(
      `SELECT (SELECT count(*) FROM public.materials WHERE id LIKE $1 OR code LIKE $1 OR name LIKE $1)::text AS n`,
      [fixturePattern],
    );
    if (residual.rows[0]?.n !== "0") throw new Error("fixture cleanup left residual materials");
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** UI：手工入库一行（包装物资走整包/余数，非包装物资走基础数量）。 */
async function inboundViaUi(
  page: Page,
  warehouse: string,
  input: {
    materialId: string;
    packaged: boolean;
    lotId?: string;
    pkgQty?: string;
    remQty?: string;
    baseQuantity?: string;
    unitCost: string;
  },
): Promise<void> {
  await page.goto("/dashboard/inventory", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "手工入库" }).click();
  const modal = modalByTitle(page, "手工入库");
  await expect(modal).toBeVisible();

  await modal.locator("#inbound-warehouse").selectOption(warehouse);
  await selectOptionIfPresent(page, modal.getByLabel("物资").first(), input.materialId);
  if (input.packaged) {
    await modal.getByLabel("整包数（盒）").fill(input.pkgQty ?? "0");
    await modal.getByLabel("余数（片）").fill(input.remQty ?? "0");
    await modal.getByLabel("单位成本（每片）").fill(input.unitCost);
  } else {
    await modal.getByLabel("基础数量（片）").fill(input.baseQuantity ?? "");
    await modal.getByLabel("单位成本（每片）").fill(input.unitCost);
  }
  if (input.lotId) {
    await selectOptionIfPresent(page, modal.getByLabel("批次（批控物资必选）").first(), input.lotId);
  }
  await modal.getByRole("button", { name: "确认入库" }).click();
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

test("016/015 主线：片剂(片,0位,盒=24) UI 录 2 盒入库 → 结存/可用/成本 48 片 + 整包展示", async ({ page }) => {
  const suffix = "MAIN";
  const warehouse = await ensureWarehouse(page);
  const material = await createMaterialFixture(page, suffix, 1, { packaged: true, batchControl: true });
  const lot = await createLotFixture(page, material.id, suffix);

  await inboundViaUi(page, warehouse, {
    materialId: material.id,
    packaged: true,
    lotId: lot.id,
    pkgQty: "2",
    remQty: "0",
    unitCost: "2.5",
  });

  // 弹窗关闭后刷新，行出现
  const modal = modalByTitle(page, "手工入库");
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  const row = page.getByRole("row").filter({ hasText: material.code });
  await expect(row).toBeVisible({ timeout: 10_000 });

  // 库存账本为基础单位：48 片，整包展示 2 盒；锁定量 0，可用 48
  await expect(row).toContainText(/48\.000000 \/ 0\.000000 \/ 48\.000000/);
  await expect(row).toContainText("片");
  await expect(row).toContainText("整包：2 盒");

  // DB 断言：数量/可用/成本均为 48 片口径
  const stock = await readStock(warehouse, material.id, lot.id);
  expect(stock).toMatchObject({ quantity: 48, lockedQuantity: 0, availableQuantity: 48, totalCost: 120 });
});

test("016/015 超精度拒绝：UI 录 0.5 片 → 服务端 400、库存不变；伪造旧字段 400", async ({ page }) => {
  const suffix = "PREC";
  const warehouse = await ensureWarehouse(page);
  const material = await createMaterialFixture(page, suffix, 1, { packaged: true, batchControl: true });
  const lot = await createLotFixture(page, material.id, suffix);

  // UI：整包 0 + 余数 0.5 → 合计 48.5（scale=0 应被拒绝）→ 弹窗保留并显示错误
  await inboundViaUi(page, warehouse, {
    materialId: material.id,
    packaged: true,
    lotId: lot.id,
    pkgQty: "0",
    remQty: "0.5",
    unitCost: "2.5",
  });
  const modal = modalByTitle(page, "手工入库");
  await expect(modal).toBeVisible({ timeout: 10_000 });
  await expect(modal).toContainText("precision"); // 服务端精度错误原样展示
  // 库存未被写入
  expect(await readStock(warehouse, material.id, lot.id)).toBeNull();
  // 关闭弹窗，行不存在
  await modal.getByRole("button", { name: "取消" }).click();
  await expect(modal).not.toBeVisible();

  // API 直接提交超精度 → 400
  const overPrecision = await rawApi<{ error: string }>(page, "/crate-api/inventories/v1/operations/inbound", {
    method: "POST",
    body: {
      warehouse,
      items: [{ material_id: material.id, lot_id: lot.id, quantity: "48.5", unit_cost: "2.5" }],
      note: "over-precision",
    },
  });
  expect(overPrecision.status).toBe(400);
  expect((overPrecision.body as { error: string }).error).toContain("precision");

  // API 提交旧拆零/伪造换算字段 → 400（未知字段白名单拒绝）
  const forged = await rawApi<{ error: string }>(page, "/crate-api/inventories/v1/operations/inbound", {
    method: "POST",
    body: {
      warehouse,
      items: [{ material_id: material.id, lot_id: lot.id, quantity: "48", unit_cost: "2.5", conversion_ratio: "24" }],
      note: "forged",
    },
  });
  expect(forged.status).toBe(400);
  expect((forged.body as { error: string }).error).toContain("unsupported field conversion_ratio");
  // 仍然没有任何库存写入
  expect(await readStock(warehouse, material.id, lot.id)).toBeNull();
});

test("016 §6.3-1：UI 新建片剂物资（片、0 位、盒=24）→ 库存页按包装入库 1 盒，默认单位仍为片", async ({ page }) => {
  const suffix = "UIMAT";
  const warehouse = await ensureWarehouse(page);
  const code = `${FIXTURE_PREFIX}${suffix}-1`;
  const name = `${FIXTURE_PREFIX}${suffix} 片剂`;

  // 物资管理页 UI 新建：片、0 位小数、盒=24
  await page.goto("/dashboard/materials", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "新建物资" }).click();
  const modal = modalByTitle(page, "新建物资");
  await expect(modal).toBeVisible();
  await modal.getByLabel("物资编码").fill(code);
  await modal.getByLabel("物资名称").fill(name);
  await modal.locator("#material-category").selectOption({ index: 1 });
  await modal.getByLabel("基础单位", { exact: true }).fill("片");
  await modal.getByLabel("数量精度（小数位 0–6）").fill("0");
  await modal.getByLabel("包装单位（可选）").fill("盒");
  await modal.getByLabel("每包含基础单位数（可选）").fill("24");
  await modal.getByRole("button", { name: "创建" }).click();
  await expect(modal).not.toBeVisible({ timeout: 10_000 });

  // 表格行：基础单位列=片、精度列=0
  const row = page.getByRole("row").filter({ hasText: code });
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row.locator("td").nth(5)).toHaveText("片");
  await expect(row.locator("td").nth(6)).toHaveText("0");

  // API 回读：物资口径正确（基础单位权威，包装为辅助）
  const created = (await api<{ records: InventoryMaterial[] }>(page, `/crate-api/inventories/v1/materials?limit=200`)).records.find((m) => m.code === code);
  expect(created).toBeTruthy();
  expect(created!.base_unit).toBe("片");
  expect(created!.quantity_scale).toBe(0);
  expect(created!.package_unit).toBe("盒");
  expect(Number(created!.package_size)).toBe(24);
  expect(created!.status).toBe("ACTIVE");

  // 库存页按包装录入 1 盒 + 0 片 → 基础数量 24 片
  await inboundViaUi(page, warehouse, { materialId: created!.id, packaged: true, pkgQty: "1", remQty: "0", unitCost: "3" });
  const inboundModal = modalByTitle(page, "手工入库");
  await expect(inboundModal).not.toBeVisible({ timeout: 10_000 });
  const stockRow = page.getByRole("row").filter({ hasText: code });
  await expect(stockRow).toBeVisible({ timeout: 10_000 });
  await expect(stockRow).toContainText(/24\.000000 \/ 0\.000000 \/ 24\.000000/);
  await expect(stockRow).toContainText("片");
  await expect(stockRow).toContainText("整包：1 盒");
  expect(await readStock(warehouse, created!.id, null)).toMatchObject({ quantity: 24, lockedQuantity: 0, availableQuantity: 24, totalCost: 72 });
});

test("016/015 非包装物资：UI 按基础数量入库 6 片 → 结存显示 6 片", async ({ page }) => {
  const suffix = "PLAIN";
  const warehouse = await ensureWarehouse(page);
  const material = await createMaterialFixture(page, suffix, 1, { packaged: false, batchControl: false });

  await inboundViaUi(page, warehouse, {
    materialId: material.id,
    packaged: false,
    baseQuantity: "6",
    unitCost: "1.5",
  });

  const modal = modalByTitle(page, "手工入库");
  await expect(modal).not.toBeVisible({ timeout: 10_000 });
  const row = page.getByRole("row").filter({ hasText: material.code });
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText(/6\.000000 \/ 0\.000000 \/ 6\.000000/);
  await expect(row).toContainText("片");

  const stock = await readStock(warehouse, material.id, null);
  expect(stock).toMatchObject({ quantity: 6, lockedQuantity: 0, availableQuantity: 6, totalCost: 9 });

  // 窄屏由 mobile-chrome 项目以 393px 运行本套全部用例覆盖（013 已记录：Aceso 固定 260px 侧边栏
  // 会在 393px 设备上使布局视口 ~507px，属产品既有布局，不做文档级无溢出断言；表格自身
  // overflow-x-auto 保证按需横向滚动）。
});