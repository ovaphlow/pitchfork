import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

/**
 * Aceso 二次确认对话框 — 浏览器验收。
 *
 * 背景：原生 `window.confirm` 由浏览器 chrome 渲染、不在 DOM 中，自动化测试只能用
 * dialog 处理器接受/拒绝，无法断言语义。本次改造把 6 个二次确认入口换成
 * `@pitchfork/ui` 的 `ConfirmDialog`（页面内 Modal），本套件逐个验收：
 *   1 物资删除     /dashboard/materials
 *   2 费用项目删除 /dashboard/fee-items
 *   3 床位删除     /dashboard/beds
 *   4 饮食档案删除 /dashboard/dining（饮食档案页签）
 *   5 配餐名单移除 /dashboard/dining（配餐名单页签）
 *   6 医嘱收束     /dashboard/orders
 *
 * 断言口径：确认框文案沿用原 `window.confirm` 的业务措辞；取消不产生副作用；
 * 确认后实体消失（前 5 项）或医嘱状态变为已完成（第 6 项）。
 *
 * 依赖用户已启动的 Aceso UI、Aceso API 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL / PLAYWRIGHT_API_BASE_URL / PLAYWRIGHT_DB_* /
 *   PITCHFORK_DB_PASSWORD / PLAYWRIGHT_USERNAME / PLAYWRIGHT_PASSWORD
 */

const FIXTURE_PREFIX = "pw-cd-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;

/** 远期日期 + 餐次：避免与真实配餐名单撞 `(menu_date, meal_time)` 唯一键 */
const ROSTER_DATE = "2027-03-15";
const ROSTER_MEAL = "午餐";
const ADMIT_DATE = "2026-09-01";
/** 收束候选 = `status=ACTIVE && end_time < now`，故结束时间必须落在过去 */
const EXPIRED_ORDER_START = "2026-09-01T08:00:00+08:00";
const EXPIRED_ORDER_END = "2026-09-01T09:00:00+08:00";

interface Material { id: string; code: string; name: string }
interface FeeItem { id: string; name: string; category: string }
interface Bed { id: string; department: string; ward: string }
interface Patient { id: string; name: string }
interface AdmissionResponse { encounter: { id: string; encounter_no: string; status: string } }
interface DietProfile { id: string; patient_id: string; encounter_id: string }
interface Roster { id: string; menu_date: string; meal_time: string }
interface ApiResult<T> { status: number; body: T }

interface AdmissionFixture {
  patientId: string;
  patientName: string;
  encounterId: string;
  encounterNo: string;
}

let databasePool: Pool;
/** 需要在 afterEach 收摊的跨表 fixture（前 5 项由 UI 删除自证，这些是前置数据） */
const createdEncounterIds: string[] = [];
const createdRosterIds: string[] = [];

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso confirm dialog tests`);
  return value;
}

/**
 * Pixel 5 设备模拟（projects mobile-chrome）在 headless 下 CSS 视口与命中坐标不一致
 * （见 roles-directory.spec.ts 同款规避）；确认框交互只在桌面 chromium 验收。
 */
function skipBrokenMobileEmulation(testInfo: { project: { name: string } }): void {
  test.skip(
    testInfo.project.name === "mobile-chrome",
    "Pixel 5 设备模拟在 headless 下视口与命中坐标不一致，交互类用例仅在桌面 chromium 执行",
  );
}

/** `@pitchfork/ui` Modal 渲染为带 <h3> 标题的浮层，按标题定位浮层容器 */
function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

/** 在浏览器页面上调用 Aceso API；登录后自动携带同站 token。错误(>=400)直接抛错。 */
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
  if (result.status >= 400) {
    throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}: ${JSON.stringify(result.body)}`);
  }
  return result.body;
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

/** 表格行定位：Aceso 各页共用 @pitchfork/ui 的 Table（<tbody><tr>） */
function rowByText(page: Page, text: string) {
  return page.getByRole("row").filter({ hasText: text });
}

// ——— fixture ———

async function createMaterial(page: Page, suffix: string): Promise<Material> {
  return api<Material>(page, "/crate-api/inventories/v1/materials", {
    method: "POST",
    body: {
      code: `${FIXTURE_PREFIX}${suffix}-MAT`,
      name: `${FIXTURE_PREFIX}${suffix} 物资`,
      category: "耗材",
      base_unit: "个",
      quantity_scale: 0,
      cost_method: "FIFO",
      status: "ACTIVE",
    },
  });
}

async function createFeeItem(page: Page, suffix: string): Promise<FeeItem> {
  return api<FeeItem>(page, "/crate-api/healthcare/v1/fee-items", {
    method: "POST",
    body: {
      category: "其他",
      name: `${FIXTURE_PREFIX}${suffix} 费用项目`,
      unit_price: 12.5,
      remark: "e2e confirm dialog fixture",
    },
  });
}

async function createBed(page: Page, suffix: string): Promise<Bed> {
  return api<Bed>(page, "/crate-api/healthcare/v1/beds", {
    method: "POST",
    body: {
      department: `${FIXTURE_PREFIX}${suffix}`,
      ward: `${FIXTURE_PREFIX}${suffix}-01`,
      label: `${FIXTURE_PREFIX}${suffix} 床位`,
      remark: "e2e confirm dialog fixture",
    },
  });
}

async function createActiveAdmission(page: Page, suffix: string): Promise<AdmissionFixture> {
  const patientName = `${FIXTURE_PREFIX}${suffix}-patient`;
  const patient = await api<Patient>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: patientName },
  });
  const admission = await api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patient.id,
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: `${ADMIT_DATE}T00:00:00+08:00`,
    },
  });
  createdEncounterIds.push(admission.encounter.id);
  return {
    patientId: patient.id,
    patientName,
    encounterId: admission.encounter.id,
    encounterNo: admission.encounter.encounter_no,
  };
}

async function createDietProfile(page: Page, admission: AdmissionFixture): Promise<DietProfile> {
  return api<DietProfile>(page, "/crate-api/dining/v1/diet-profiles", {
    method: "POST",
    body: {
      patient_id: admission.patientId,
      encounter_id: admission.encounterId,
      meal_type: "普食",
      allergies: [],
      remark: "e2e confirm dialog fixture",
    },
  });
}

/** 生成远期配餐名单（返回真实 ULID，收摊时按 id 删除，不按日期误伤真实数据） */
async function generateRoster(page: Page): Promise<Roster> {
  const result = await api<{ roster: Roster }>(page, "/crate-api/dining/v1/rosters/generate", {
    method: "POST",
    body: { date: ROSTER_DATE, meal_time: ROSTER_MEAL },
  });
  createdRosterIds.push(result.roster.id);
  return result.roster;
}

/** 手工条目：只有 `source=手工` 的条目在名单详情里才显示「移除」 */
async function addManualRosterItem(page: Page, rosterId: string, patientId: string): Promise<void> {
  await api(page, `/crate-api/dining/v1/rosters/${rosterId}/items`, {
    method: "POST",
    body: { patient_id: patientId, adjust_type: "临时加餐", remark: "e2e confirm dialog fixture" },
  });
}

/** 已到期医嘱：`order_class=TEMPORARY` 且 `end_time` 落在过去，才进入收束候选 */
async function createExpiredOrder(page: Page, encounterId: string, suffix: string): Promise<string> {
  const orderContent = `${FIXTURE_PREFIX}${suffix} 到期医嘱`;
  await api(page, `/crate-api/healthcare/v1/encounters/${encounterId}/orders`, {
    method: "POST",
    body: {
      order_type: "THERAPY",
      order_class: "TEMPORARY",
      order_content: orderContent,
      doctor: "测试医生",
      start_time: EXPIRED_ORDER_START,
      end_time: EXPIRED_ORDER_END,
      order_details: { treatment_item: "康复理疗" },
    },
  });
  return orderContent;
}

// ——— 收摊 ———

/**
 * 收摊顺序遵循外键依赖：名单条目 → 名单 → 饮食档案 → 护理任务 → 医嘱 → 入住 → 患者；
 * 物资/床位/费用项目按前缀兜底（前 3 项用例已由 UI 删除，这里防用例失败残留）。
 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    if (createdRosterIds.length > 0) {
      await client.query(`DELETE FROM dining.dining_roster_items WHERE roster_id = ANY($1::text[])`, [createdRosterIds]);
      await client.query(`DELETE FROM dining.dining_rosters WHERE id = ANY($1::text[])`, [createdRosterIds]);
    }
    if (createdEncounterIds.length > 0) {
      await client.query(`DELETE FROM dining.dining_diet_profiles WHERE encounter_id = ANY($1::text[])`, [createdEncounterIds]);
      await client.query(
        `DELETE FROM nursing.nursing_task_executions
          WHERE task_id IN (SELECT id FROM nursing.nursing_tasks WHERE encounter_id = ANY($1::text[]))`,
        [createdEncounterIds],
      );
      await client.query(`DELETE FROM nursing.nursing_tasks WHERE encounter_id = ANY($1::text[])`, [createdEncounterIds]);
      await client.query(`DELETE FROM healthcare.medical_orders WHERE encounter_id = ANY($1::text[])`, [createdEncounterIds]);
      await client.query(`DELETE FROM healthcare.encounters WHERE id = ANY($1::text[])`, [createdEncounterIds]);
    }
    await client.query(`DELETE FROM public.materials WHERE code LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM healthcare.beds WHERE department LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM healthcare.fee_items WHERE name LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    if (createdEncounterIds.length > 0) {
      await client.query(`DELETE FROM healthcare.patients WHERE name LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    }
    await client.query("COMMIT");
    createdRosterIds.length = 0;
    createdEncounterIds.length = 0;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
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

// ——— 1 物资删除 ———

test("物资删除：确认框沿用原文案，取消无副作用，确认后行消失", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const material = await createMaterial(page, "MAT");
  await page.goto("/dashboard/materials", { waitUntil: "networkidle" });

  const row = rowByText(page, material.code);
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "删除" }).click();

  const dialog = modalByTitle(page, "确认删除物资");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`将删除物资「${material.name}」（${material.code}）`);
  await expect(dialog).toContainText("删除后不可恢复");
  await expect(dialog.getByRole("button", { name: "取消" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "确认删除" })).toBeVisible();

  // 取消：确认框关闭且物资仍在
  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toBeVisible();

  // 确认：物资从列表消失
  await row.getByRole("button", { name: "删除" }).click();
  await modalByTitle(page, "确认删除物资").getByRole("button", { name: "确认删除" }).click();
  await expect(rowByText(page, material.code)).toHaveCount(0);
});

// ——— 2 费用项目删除 ———

test("费用项目删除：确认框保留计费影响说明，取消无副作用，确认后行消失", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const item = await createFeeItem(page, "FEE");
  await page.goto("/dashboard/fee-items", { waitUntil: "networkidle" });

  const row = rowByText(page, item.name);
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "删除" }).click();

  const dialog = modalByTitle(page, "确认删除费用项目");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`将删除费用项目「${item.name}」（${item.category}）`);
  await expect(dialog).toContainText("删除后新账单无法再按该分类自动计费");

  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toBeVisible();

  await row.getByRole("button", { name: "删除" }).click();
  await modalByTitle(page, "确认删除费用项目").getByRole("button", { name: "确认删除" }).click();
  await expect(rowByText(page, item.name)).toHaveCount(0);
});

// ——— 3 床位删除 ———

test("床位删除：确认框提示在住占用会被拒绝，确认后行消失", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const bed = await createBed(page, "BED");
  await page.goto("/dashboard/beds", { waitUntil: "networkidle" });

  const row = rowByText(page, bed.ward);
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "删除" }).click();

  const dialog = modalByTitle(page, "确认删除床位");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`将删除床位「${bed.department} / ${bed.ward}」`);
  await expect(dialog).toContainText("系统会拒绝删除，可改为停用");

  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toBeVisible();

  await row.getByRole("button", { name: "删除" }).click();
  await modalByTitle(page, "确认删除床位").getByRole("button", { name: "确认删除" }).click();
  await expect(rowByText(page, bed.ward)).toHaveCount(0);
});

// ——— 4 饮食档案删除 ———

test("饮食档案删除：确认框点名长者，取消无副作用，确认后行消失", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const admission = await createActiveAdmission(page, "PROFILE");
  await createDietProfile(page, admission);

  await page.goto("/dashboard/dining", { waitUntil: "networkidle" });
  const row = rowByText(page, admission.patientName);
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "删除" }).click();

  const dialog = modalByTitle(page, "确认删除饮食档案");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`删除「${admission.patientName}」的饮食档案`);

  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toBeVisible();

  await row.getByRole("button", { name: "删除" }).click();
  await modalByTitle(page, "确认删除饮食档案").getByRole("button", { name: "确认删除" }).click();
  await expect(rowByText(page, admission.patientName)).toHaveCount(0);
});

// ——— 5 配餐名单条目移除 ———

test("配餐名单移除：只对手工条目开放，确认后条目从名单消失", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const admission = await createActiveAdmission(page, "ROSTER");
  const roster = await generateRoster(page);
  await addManualRosterItem(page, roster.id, admission.patientId);

  await page.goto("/dashboard/dining", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "配餐名单" }).click();

  // 打开该名单详情（标题含日期、餐次与人数，按「配餐名单（N 人）」定位浮层）
  const rosterRow = rowByText(page, ROSTER_DATE);
  await expect(rosterRow).toBeVisible();
  await rosterRow.getByRole("button", { name: "查看名单" }).click();
  const detail = page.getByRole("heading", { name: /配餐名单（\d+ 人）/ }).locator("xpath=../..");
  await expect(detail).toBeVisible();

  const itemRow = detail.getByRole("row").filter({ hasText: admission.patientName });
  await expect(itemRow).toBeVisible();
  await itemRow.getByRole("button", { name: "移除" }).click();

  const dialog = modalByTitle(page, "确认移除配餐名单条目");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`将「${admission.patientName}」从本餐名单中移除`);
  await expect(dialog).toContainText("已登记就餐的条目不可删除");

  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(itemRow).toBeVisible();

  await itemRow.getByRole("button", { name: "移除" }).click();
  await modalByTitle(page, "确认移除配餐名单条目").getByRole("button", { name: "确认移除" }).click();
  await expect(detail.getByRole("row").filter({ hasText: admission.patientName })).toHaveCount(0);
});

// ——— 6 医嘱收束 ———

test("医嘱收束：确认框说明收束范围，取消不改状态，确认后医嘱变为已完成", async ({ page }, testInfo) => {
  skipBrokenMobileEmulation(testInfo);
  const admission = await createActiveAdmission(page, "ORDER");
  const orderContent = await createExpiredOrder(page, admission.encounterId, "ORDER");

  await page.goto(`/dashboard/orders?encounter_id=${admission.encounterId}`, { waitUntil: "networkidle" });
  const row = rowByText(page, orderContent);
  await expect(row).toBeVisible();
  await expect(row).toContainText("进行中");

  await page.getByRole("button", { name: /收束已到期医嘱/ }).click();
  const dialog = modalByTitle(page, "确认收束已到期医嘱");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(`收束「${admission.patientName}」本入住全部已到期`);
  await expect(dialog).toContainText("未执行的护理记录保持原状态");

  // 取消：医嘱仍为进行中
  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();
  await expect(rowByText(page, orderContent)).toContainText("进行中");

  // 确认：幂等 POST 收束，行内状态变为已完成且有结果提示
  await page.getByRole("button", { name: /收束已到期医嘱/ }).click();
  await modalByTitle(page, "确认收束已到期医嘱").getByRole("button", { name: "确认收束" }).click();
  await expect(page.getByText(/已收束 1 条已到期医嘱/)).toBeVisible();
  await expect(rowByText(page, orderContent)).toContainText("已完成");
});
