import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

/**
 * 017 Aceso 院内护理异常事件与班次交接闭环 — 浏览器验收（计划 §7.2 项 4）。
 *
 * 依赖用户已启动的 Aceso UI、API、Identity 与隔离测试数据库：
 *   PLAYWRIGHT_BASE_URL / PLAYWRIGHT_API_BASE_URL / PLAYWRIGHT_DB_* / PLAYWRIGHT_USERNAME/PASSWORD
 *
 * 主线：护理员为活动养老入住 UI 上报「跌倒/坠床」→ 追加处置/通知（处理中）→
 * 关闭（已关闭）+ 重复关闭 409 + 未来发生时间/伪造字段 400；交班人按照护单元创建交班单
 * （快照冻结未完成执行、未关闭事件与手工事项）→ 接班人 UI 确认接班（重复接班 409）→
 * 已接班后可补充事项；跨入住事件读写 404、伪造 encounter 404、缺 encounter 400、同单元同班次
 * 重复创建 409。时间线无副作用读取展示。
 */

const FIXTURE_PREFIX = "pw-ih-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;

interface ApiResult<T> { status: number; body: T }
interface NursingIncident {
  id: string;
  encounter_id: string;
  period_id: string;
  incident_type: string;
  severity: string;
  status: string;
  occurred_at: string;
  description: string;
  reporter: string | null;
}
interface NursingIncidentAction { id: string; action_type: string; body: string; actor: string | null }
interface NursingIncidentDetail extends NursingIncident { actions: NursingIncidentAction[] }
interface ShiftHandoverItem {
  id: string;
  item_kind: string;
  encounter_id: string | null;
  period_id: string | null;
  source_id: string | null;
  summary: string;
  created_by: string | null;
}
interface ShiftHandoverDetail {
  id: string;
  care_unit: string;
  business_date: string;
  shift: string;
  handover_by: string | null;
  received_by: string | null;
  received_at: string | null;
  status: string;
  items: ShiftHandoverItem[];
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the nursing incident handover tests`);
  return value;
}

function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

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

async function rawApi<T>(page: Page, path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<{ status: number; body: T | { error: string } }> {
  const baseUrl = requiredEnvironment("PLAYWRIGHT_API_BASE_URL", API_BASE_URL);
  const result = await page.evaluate(
    async ({ baseUrl: requestBaseUrl, path: requestPath, method, body, headers }) => {
      const token = localStorage.getItem("token");
      const response = await fetch(`${requestBaseUrl}${requestPath}`, {
        method,
        headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
        credentials: "include",
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let parsed: unknown = {};
      try { parsed = text ? JSON.parse(text) : {}; } catch { parsed = { raw: text }; }
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

/** 建患者 + 活动养老入住（含照护单元 department），返回 encounter id。 */
async function createElderlyAdmission(page: Page, suffix: string, department: string): Promise<string> {
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}-老人` },
  });
  const admission = await api<{ encounter: { id: string } }>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patient.id,
      encounter_no: `${FIXTURE_PREFIX}${suffix}-enc`,
      admit_date: `${new Date(Date.now() - 24 * 3_600_000).toISOString().slice(0, 10)}T00:00:00+08:00`,
      department,
    },
  });
  return admission.encounter.id;
}

/** 读取入院 API 自动创建的 ELDERLY_CARE 周期。 */
async function loadPeriodForEncounter(encounterId: string): Promise<string> {
  const result = await databasePool.query<{ id: string }>(
    `SELECT id FROM nursing.nursing_service_periods WHERE encounter_id = $1 AND status = 'ACTIVE' ORDER BY created_at LIMIT 1`,
    [encounterId],
  );
  const periodId = result.rows[0]?.id;
  if (!periodId) throw new Error(`no ACTIVE period for encounter ${encounterId}`);
  return periodId;
}

/** 种一条 PENDING 今日护理执行（交班快照「未完成执行」来源）。 */
async function seedPendingExecution(periodId: string, suffix: string) {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    const taskId = `${FIXTURE_PREFIX}${suffix}-task`;
    const execId = `${FIXTURE_PREFIX}${suffix}-exec`;
    await client.query(
      `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
       VALUES ($1, $2, 'NURSING', $3, 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [taskId, periodId, `${FIXTURE_PREFIX}${suffix} 交班待执行任务`],
    );
    const planned = new Date(Date.now() + 3 * 3_600_000).toISOString();
    await client.query(
      `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
       VALUES ($1, $2, $3::timestamptz, 'PENDING') ON CONFLICT (id) DO NOTHING`,
      [execId, taskId, planned],
    );
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** DB 读取事件状态与动作数。 */
async function readIncident(incidentId: string): Promise<{ status: string; actionCount: number; encounterId: string; periodId: string } | null> {
  const result = await databasePool.query<{ status: string; encounter_id: string; period_id: string; action_count: string }>(
    `SELECT i.status::text AS status, i.encounter_id, i.period_id,
            (SELECT count(*) FROM nursing.nursing_incident_actions a WHERE a.incident_id = i.id)::text AS action_count
     FROM nursing.nursing_incidents i WHERE i.id = $1`,
    [incidentId],
  );
  const row = result.rows[0];
  return row
    ? { status: row.status, actionCount: Number(row.action_count), encounterId: row.encounter_id, periodId: row.period_id }
    : null;
}

/** DB 读取交接单及事项。 */
async function readHandover(handoverId: string): Promise<{ status: string; items: Array<{ kind: string }> } | null> {
  const result = await databasePool.query<{ status: string }>(
    `SELECT status::text AS status FROM nursing.nursing_shift_handovers WHERE id = $1`,
    [handoverId],
  );
  const row = result.rows[0];
  if (!row) return null;
  const items = await databasePool.query<{ kind: string }>(
    `SELECT item_kind::text AS kind FROM nursing.nursing_shift_handover_items WHERE handover_id = $1 ORDER BY created_at, id`,
    [handoverId],
  );
  return { status: row.status, items: items.rows.map((r) => ({ kind: r.kind })) };
}

/** 清理 017 fixture（含入院/周期/任务/执行/护理记录等关联清除）。 */
async function cleanupDatabase() {
  const client = await databasePool.connect();
  const pattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    const encounters = await client.query<{ id: string }>(
      `SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1`,
      [pattern],
    );
    const encounterIds = encounters.rows.map((row) => row.id);

    await client.query(
      `DELETE FROM nursing.nursing_incident_actions WHERE incident_id IN
         (SELECT id FROM nursing.nursing_incidents WHERE encounter_id = ANY($2::text[]) OR id LIKE $1)`,
      [pattern, encounterIds],
    );
    await client.query(
      `DELETE FROM nursing.nursing_incidents WHERE encounter_id = ANY($2::text[]) OR id LIKE $1`,
      [pattern, encounterIds],
    );
    await client.query(
      `DELETE FROM nursing.nursing_shift_handover_items WHERE handover_id IN
         (SELECT id FROM nursing.nursing_shift_handovers WHERE care_unit LIKE $1)`,
      [pattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_shift_handovers WHERE id LIKE $1 OR care_unit LIKE $1`,
      [pattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_executions WHERE id LIKE $1 OR task_id IN
         (SELECT id FROM nursing.nursing_tasks WHERE period_id IN
           (SELECT id FROM nursing.nursing_service_periods WHERE encounter_id = ANY($2::text[])))`,
      [pattern, encounterIds],
    );
    await client.query(
      `DELETE FROM nursing.nursing_tasks WHERE id LIKE $1 OR period_id IN
         (SELECT id FROM nursing.nursing_service_periods WHERE encounter_id = ANY($2::text[]))`,
      [pattern, encounterIds],
    );
    await client.query(
      `DELETE FROM nursing.nursing_service_periods WHERE encounter_id = ANY($2::text[]) OR id LIKE $1`,
      [pattern, encounterIds],
    );
    await client.query(`DELETE FROM healthcare.encounters WHERE encounter_no LIKE $1`, [pattern]);
    await client.query(`DELETE FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1`, [pattern]);

    const residual = await client.query<{ n: string }>(
      `SELECT ((SELECT count(*) FROM nursing.nursing_incidents WHERE encounter_id = ANY($2::text[])) +
              (SELECT count(*) FROM nursing.nursing_shift_handovers WHERE id LIKE $1 OR care_unit LIKE $1) +
              (SELECT count(*) FROM healthcare.encounters WHERE encounter_no LIKE $1))::text AS n`,
      [pattern, encounterIds],
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

async function gotoResidentView(page: Page) {
  await page.goto("/dashboard/inpatient", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "长者照护档案" }).click();
  // 等待自动选中的入住档案与标签页出现
  await expect(page.getByRole("button", { name: "异常事件" })).toBeVisible({ timeout: 10_000 });
}

test("017 异常事件主线：UI 上报跌倒→追加处置/通知(处理中)→关闭(已关闭)，重复关闭/未来时间/伪造字段被拒", async ({ page }) => {
  const suffix = "INC";
  const department = `${FIXTURE_PREFIX}${suffix}-养老区`;
  const encounterId = await createElderlyAdmission(page, suffix, department);
  const periodId = await loadPeriodForEncounter(encounterId);
  const description = `${FIXTURE_PREFIX}${suffix} 洗浴后跌倒`;

  await gotoResidentView(page);
  await page.getByRole("button", { name: "异常事件" }).click();

  // 上报
  await page.getByRole("button", { name: "上报事件" }).click();
  const createModal = modalByTitle(page, "上报护理异常事件");
  await expect(createModal).toBeVisible();
  await createModal.locator("#incident-description").fill(description);
  await createModal.getByRole("button", { name: "上报事件" }).click();
  await expect(createModal).not.toBeVisible({ timeout: 10_000 });

  const row = page.getByRole("row").filter({ hasText: description });
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("跌倒/坠床");
  await expect(row).toContainText("已上报");

  // 捕获事件 ID 并校验归属与上报人
  const incidentList = await api<{ records: NursingIncident[] }>(
    page,
    `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents?limit=10`,
  );
  const incident = incidentList.records.find((record) => record.description === description);
  expect(incident).toBeTruthy();
  const incidentId = incident!.id;
  expect(incident!.encounter_id).toBe(encounterId);
  expect(incident!.period_id).toBe(periodId);
  expect(incident!.status).toBe("已上报");
  expect(incident!.reporter).toBeTruthy();
  expect(await readIncident(incidentId)).toMatchObject({ status: "已上报", actionCount: 1 });

  // 追加处置/通知 → 处理中
  await row.getByRole("button", { name: "处置" }).click();
  const actionModal = modalByTitle(page, "追加处置/通知/观察");
  await expect(actionModal).toBeVisible();
  await actionModal.locator("#action-type").selectOption("通知");
  await actionModal.locator("#action-body").fill("已联系家属并告知情况");
  await actionModal.locator("#action-party").fill("家属");
  await actionModal.locator("#action-result").fill("已接通");
  await actionModal.getByRole("button", { name: "追加记录" }).click();
  await expect(actionModal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("处理中");
  expect(await readIncident(incidentId)).toMatchObject({ status: "处理中", actionCount: 2 });

  // 详情：原始上报与通知事实可读
  await row.getByRole("button", { name: "详情" }).click();
  const detailModal = modalByTitle(page, "异常事件详情");
  await expect(detailModal).toBeVisible();
  await expect(detailModal).toContainText("上报");
  await expect(detailModal).toContainText("通知/联系");
  await expect(detailModal).toContainText("已联系家属并告知情况");
  await expect(detailModal).toContainText("上报人：");
  // 关闭
  await detailModal.getByRole("button", { name: "关闭事件" }).click();
  const closeModal = modalByTitle(page, "关闭异常事件");
  await expect(closeModal).toBeVisible();
  await closeModal.locator("#close-note").fill("家属知晓，后续加强看护");
  await closeModal.getByRole("button", { name: "确认关闭" }).click();
  await expect(closeModal).not.toBeVisible({ timeout: 10_000 });
  await expect(detailModal).toContainText("已关闭");
  await expect(detailModal).toContainText("关闭");
  await detailModal.getByRole("button", { name: "关闭" }).click();
  await expect(detailModal).not.toBeVisible();

  await expect(row).toContainText("已关闭");
  await expect(row.getByRole("button", { name: "处置" })).toHaveCount(0);
  expect(await readIncident(incidentId)).toMatchObject({ status: "已关闭", actionCount: 3 });

  // 重复关闭 → 409，历史不变
  const repeatClose = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents/${incidentId}/close`, {
    method: "POST",
    body: { close_note: "重复关闭" },
  });
  expect(repeatClose.status).toBe(409);
  expect(await readIncident(incidentId)).toMatchObject({ status: "已关闭", actionCount: 3 });

  // 未来发生时间 → 400；未知字段 → 400；伪造上报人 → 400
  const future = new Date(Date.now() + 24 * 3_600_000).toISOString();
  const futureCreate = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents`, {
    method: "POST",
    body: { incident_type: "走失", severity: "一般", occurred_at: future, description: "未来事件" },
  });
  expect(futureCreate.status).toBe(400);
  const unknownField = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents`, {
    method: "POST",
    body: { incident_type: "走失", severity: "一般", occurred_at: new Date().toISOString(), description: "未知字段", forged_status: "已关闭" },
  });
  expect(unknownField.status).toBe(400);
  const forgedActor = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents`, {
    method: "POST",
    body: { incident_type: "走失", severity: "一般", occurred_at: new Date().toISOString(), description: "伪造上报人", reporter: "someone" },
  });
  expect(forgedActor.status).toBe(400);

  // 时间线 API 读取展示异常事件（NURSING_INCIDENT），且读取不产生新事实
  const beforeTimeline = (await readIncident(incidentId))!;
  const timeline = await api<{ records: Array<{ event_type: string; title: string | null; summary: string | null }> }>(
    page,
    `/crate-api/nursing/v1/timeline?period_id=${encodeURIComponent(periodId)}&encounter_id=${encodeURIComponent(encounterId)}&limit=100`,
  );
  const timelineIncident = timeline.records.find((event) => event.event_type === "NURSING_INCIDENT");
  expect(timelineIncident).toBeTruthy();
  expect(timelineIncident!.title ?? "").toContain("异常事件");
  expect(timelineIncident!.title ?? "").toContain("跌倒/坠床");
  expect(await readIncident(incidentId)).toMatchObject({ status: beforeTimeline.status, actionCount: beforeTimeline.actionCount });
});

test("017 班次交接主线：UI 创建交接单(冻结未完成执行/未关闭事件/手工)→接班(重复 409)→已接班后补充事项", async ({ page }) => {
  const suffix = "HAND";
  const department = `${FIXTURE_PREFIX}${suffix}-康复区`;
  const encounterId = await createElderlyAdmission(page, suffix, department);
  const periodId = await loadPeriodForEncounter(encounterId);
  await seedPendingExecution(periodId, suffix);
  // 未关闭事件（快照「未关闭事件」来源）
  const incident = await api<NursingIncident>(page, `/crate-api/healthcare/v1/encounters/${encounterId}/nursing-incidents`, {
    method: "POST",
    body: {
      incident_type: "压疮",
      severity: "较重",
      occurred_at: new Date(Date.now() - 30 * 60_000).toISOString(),
      description: `${FIXTURE_PREFIX}${suffix} 卧床压疮观察`,
    },
  });
  expect(incident.status).toBe("已上报");

  await gotoResidentView(page);
  await page.getByRole("button", { name: "班次交接" }).click();
  await page.getByRole("button", { name: "创建交接单" }).click();
  const createModal = modalByTitle(page, "创建班次交接单");
  await expect(createModal).toBeVisible();
  await expect(createModal).toContainText(department);
  await createModal.locator("#handover-manual").fill("重点关注饮水情况");
  await createModal.getByRole("button", { name: "创建交接单" }).click();
  await expect(createModal).not.toBeVisible({ timeout: 10_000 });

  // 交接单表格行（科室只在标题，不在表行；按班次定位）
  const row = page
    .locator("table")
    .filter({ hasText: "业务日期" })
    .locator("tbody tr")
    .filter({ hasText: "早班" });
  await expect(row).toHaveCount(1, { timeout: 10_000 });
  await expect(row).toContainText("待接班");
  await expect(row).toContainText("4"); // 执行 + 事件 + 入住 + 手工

  // API 取交接单：快照包含执行/事件/手工，且来源记录未被改写
  const handoverList = await api<{ records: Array<{ id: string; care_unit: string }> }>(
    page,
    `/crate-api/healthcare/v1/nursing-shift-handovers?care_unit=${encodeURIComponent(department)}&limit=10`,
  );
  const handover = handoverList.records.find((record) => record.care_unit === department);
  expect(handover).toBeTruthy();
  const detail = await api<ShiftHandoverDetail>(page, `/crate-api/healthcare/v1/nursing-shift-handovers/${handover!.id}`);
  expect(detail.status).toBe("待接班");
  expect(detail.handover_by).toBeTruthy();
  expect(detail.received_by).toBeNull();
  const kinds = detail.items.map((item) => item.item_kind);
  expect(kinds).toContain("执行");
  expect(kinds).toContain("事件");
  expect(kinds).toContain("入住");
  expect(kinds).toContain("手工");
  const eventItem = detail.items.find((item) => item.item_kind === "事件");
  expect(eventItem?.source_id).toBe(incident.id);
  expect(eventItem?.period_id).toBe(periodId);
  // 来源未被改写
  expect(await readIncident(incident.id)).toMatchObject({ status: "已上报", actionCount: 1 });
  const handoverDb = await readHandover(handover!.id);
  expect(handoverDb).toMatchObject({ status: "待接班" });
  expect(handoverDb!.items.map((item) => item.kind).sort()).toEqual(["事件", "入住", "手工", "执行"]);

  // UI 接班 → 已接班
  await row.getByRole("button", { name: "详情" }).click();
  const detailModal = modalByTitle(page, "班次交接单详情");
  await expect(detailModal).toBeVisible();
  await expect(detailModal).toContainText("事件");
  await expect(detailModal).toContainText("手工补充");
  await detailModal.getByRole("button", { name: "确认接班" }).click();
  await expect(detailModal).not.toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText("已接班");

  const received = await api<ShiftHandoverDetail>(page, `/crate-api/healthcare/v1/nursing-shift-handovers/${handover!.id}`);
  expect(received.status).toBe("已接班");
  expect(received.received_by).toBeTruthy();
  expect(received.received_at).toBeTruthy();

  // 重复接班 → 409，接班人不覆盖
  const repeatReceive = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/nursing-shift-handovers/${handover!.id}/receive`, {
    method: "POST",
    body: {},
  });
  expect(repeatReceive.status).toBe(409);
  expect((await readHandover(handover!.id))!.status).toBe("已接班");

  // 已接班后补充事项（追加，保留补充人和时间）
  await row.getByRole("button", { name: "详情" }).click();
  const detail2 = modalByTitle(page, "班次交接单详情");
  await expect(detail2).toBeVisible();
  await detail2.getByRole("button", { name: "补充事项" }).click();
  const appendModal = modalByTitle(page, "补充交接事项");
  await expect(appendModal).toBeVisible();
  await appendModal.locator("#append-content").fill("接班后巡视完成");
  await appendModal.getByRole("button", { name: "追加事项" }).click();
  await expect(appendModal).not.toBeVisible({ timeout: 10_000 });
  await expect(detail2).toContainText("接班后巡视完成");
  await detail2.getByRole("button", { name: "关闭" }).click();

  const appended = await api<ShiftHandoverDetail>(page, `/crate-api/healthcare/v1/nursing-shift-handovers/${handover!.id}`);
  expect(appended.status).toBe("已接班");
  const appendedItems = appended.items.filter((item) => item.item_kind === "手工");
  expect(appendedItems).toHaveLength(2);
  expect(appendedItems[1].created_by).toBeTruthy();

  // 同照护单元/业务日期/班次重复创建 → 409（不同键）
  const duplicate = await rawApi<{ error: string }>(page, "/crate-api/healthcare/v1/nursing-shift-handovers", {
    method: "POST",
    body: { encounter_id: encounterId, business_date: detail.business_date, shift: detail.shift, manual_items: ["再来"] },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffix}-dup-key` },
  });
  expect(duplicate.status).toBe(409);
});

test("017 隔离与伪造拒绝（API）：跨入住事件读写 404、伪造 encounter 404、缺失 encounter 400", async ({ page }) => {
  const suffixA = "ISO-A";
  const suffixB = "ISO-B";
  const encounterA = await createElderlyAdmission(page, suffixA, `${FIXTURE_PREFIX}${suffixA}-特护区`);
  const encounterB = await createElderlyAdmission(page, suffixB, `${FIXTURE_PREFIX}${suffixB}-普通区`);
  const periodA = await loadPeriodForEncounter(encounterA);

  const incidentA = await api<NursingIncident>(page, `/crate-api/healthcare/v1/encounters/${encounterA}/nursing-incidents`, {
    method: "POST",
    body: {
      incident_type: "其他",
      severity: "一般",
      occurred_at: new Date(Date.now() - 10 * 60_000).toISOString(),
      description: `${FIXTURE_PREFIX}${suffixA} 隔离事件`,
    },
  });
  expect(incidentA.period_id).toBe(periodA);

  // 跨入住：A 的事件在 B 的作用域下不可读/不可处置/不可关闭 → 404
  const crossDetail = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterB}/nursing-incidents/${incidentA.id}`);
  expect(crossDetail.status).toBe(404);
  const crossAction = await rawApi<{ error: string }>(page, `/crate-api/healthcare/v1/encounters/${encounterB}/nursing-incidents/${incidentA.id}/actions`, {
    method: "POST",
    body: { action_type: "观察", body: "跨入住处置" },
  });
  expect(crossAction.status).toBe(404);
  // B 的列表不应包含 A 的事件
  const listB = await api<{ records: NursingIncident[] }>(page, `/crate-api/healthcare/v1/encounters/${encounterB}/nursing-incidents?limit=10`);
  expect(listB.records).toHaveLength(0);

  // 伪造（不存在）encounter 创建事件 → 404
  const bogusEncounter = await rawApi<{ error: string }>(page, "/crate-api/healthcare/v1/encounters/01M0AAAAAAAAAAAAAAAAAAAAAA/nursing-incidents", {
    method: "POST",
    body: { incident_type: "其他", severity: "一般", occurred_at: new Date().toISOString(), description: "伪造入住" },
  });
  expect(bogusEncounter.status).toBe(404);

  // 交班创建：缺失 encounter_id → 400；伪造 encounter_id → 404
  const todayDate = new Date().toISOString().slice(0, 10);
  const missingEncounter = await rawApi<{ error: string }>(page, "/crate-api/healthcare/v1/nursing-shift-handovers", {
    method: "POST",
    body: { business_date: todayDate, shift: "早班" },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffixA}-missing-enc` },
  });
  expect(missingEncounter.status).toBe(400);
  const bogusHandover = await rawApi<{ error: string }>(page, "/crate-api/healthcare/v1/nursing-shift-handovers", {
    method: "POST",
    body: { encounter_id: "01M0AAAAAAAAAAAAAAAAAAAAAA", business_date: todayDate, shift: "早班" },
    headers: { "Idempotency-Key": `${FIXTURE_PREFIX}${suffixA}-bogus-enc` },
  });
  expect(bogusHandover.status).toBe(404);
});