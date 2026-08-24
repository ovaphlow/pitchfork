import { Pool, type PoolClient } from "pg";
import { expect, test, type Page } from "@playwright/test";

/**
 * Aceso 护理员工作量与计划完成率统计（docs/plans/004.aceso-nursing-workload-and-completion-statistics.md）
 * 浏览器端到端验收。
 *
 * 覆盖计划第 8.4 节场景：
 *  1. 默认本周范围显示全局统计和至少一个执行人分组
 *  2. 切换范围后卡片/表格更新；空范围显示空态与「暂无应完成任务」
 *  3. 未来任务计入计划任务但不计入应完成/完成率分母
 *  4. 开始、完成、跳过、取消成功后今日列表和统计区都刷新
 *  5. 统计请求失败时今日执行列表仍可操作
 *  6. 未分配任务显示「未分配」，窄屏下筛选/统计卡/表格可操作
 *
 * 运行前提（已有服务，仅校验不启动）：
 *  - Aceso API :8422 连接隔离测试库 aceso_test（127.0.0.1:55432）
 *  - Aceso UI :4324、身份服务 :8420、Nexus :8421
 *  - PLAYWRIGHT_BASE_URL 使用与 API/IDP 同站点地址，保证 identityd_session cookie 正常
 *
 * fixture 使用固定前缀 st4-，通过数据库直接插入 PRN 任务执行记录；
 * PRN 频次不会触发 todayExecutions 懒生成，保证统计与今日列表数据可控。
 */

const FIXTURE_PREFIX = "st4-";

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso statistics browser tests`);
  return value;
}

function todayLocalDate(): string {
  const d = new Date();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

/** 保证本地日期与 UTC 日期不会因凌晨窗口错位，避免今日执行/统计日期范围落空。 */
function canSeedStatisticsFixtures(): boolean {
  return new Date().getHours() >= 8;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `DELETE FROM nursing.nursing_task_execution_consumptions
       WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions
         WHERE id LIKE $1 OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_executions
       WHERE id LIKE $1 OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(`DELETE FROM nursing.nursing_tasks WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM nursing.nursing_service_periods WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM healthcare.patients WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE $1) +
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1)
      )::text AS residual`,
      [`${FIXTURE_PREFIX}%`],
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

async function ensureAuthenticated(page: Page) {
  await page.goto("/dashboard/admission", { waitUntil: "networkidle" });
  if (!page.url().includes("/login")) return;
  const identifier = requiredEnvironment("PLAYWRIGHT_USERNAME", process.env.PLAYWRIGHT_USERNAME);
  const password = requiredEnvironment("PLAYWRIGHT_PASSWORD", process.env.PLAYWRIGHT_PASSWORD);
  await page.getByLabel("账号").fill(identifier);
  await page.getByLabel("密码").fill(password);
  await Promise.all([
    page.waitForURL(/\/dashboard/),
    page.getByRole("button", { name: "登录" }).click(),
  ]);
}

async function seedBaseClient(
  client: PoolClient,
  includeFuture: boolean,
  now: Date,
  patientId: string,
  periodId: string,
  taskA: string,
  taskB: string,
  taskADescription = "统计任务A",
  taskBDescription = "统计任务B",
) {
  const iso = (offsetMs: number) => new Date(now.getTime() + offsetMs).toISOString();

  await client.query(
    `INSERT INTO healthcare.patients (id, name, status) VALUES ($1, $2, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
    [patientId, "统计测试患者"],
  );
  await client.query(
    `INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
     VALUES ($1, $2, 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
    [periodId, patientId],
  );
  await client.query(
    `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
     VALUES ($1, $2, 'NURSING', $3, 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
    [taskA, periodId, taskADescription],
  );
  await client.query(
    `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
     VALUES ($1, $2, 'NURSING', $3, 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
    [taskB, periodId, taskBDescription],
  );

  const executions: Array<[string, string, string, string, string | null, string | null]> = [
    [`${FIXTURE_PREFIX}exec-1-completed`, taskA, "COMPLETED", iso(-3 * 3_600_000), "st4-executor-1", iso(0)],
    [`${FIXTURE_PREFIX}exec-1-pending`, taskA, "PENDING", iso(-35 * 60_000), "st4-executor-1", null],
    [`${FIXTURE_PREFIX}exec-1-in-progress`, taskA, "IN_PROGRESS", iso(-2 * 3_600_000), "st4-executor-1", null],
    [`${FIXTURE_PREFIX}exec-1-skipped`, taskA, "SKIPPED", iso(-60 * 60_000), "st4-executor-1", null],
    [`${FIXTURE_PREFIX}exec-2-completed`, taskB, "COMPLETED", iso(-3 * 3_600_000), "st4-executor-2", iso(0)],
    [`${FIXTURE_PREFIX}exec-2-cancelled`, taskB, "CANCELLED", iso(-2 * 3_600_000), "st4-executor-2", null],
    [`${FIXTURE_PREFIX}exec-null-pending`, taskA, "PENDING", iso(-20 * 60_000), null, null],
    [`${FIXTURE_PREFIX}exec-null-completed`, taskB, "COMPLETED", iso(-4 * 3_600_000), null, iso(0)],
    [`${FIXTURE_PREFIX}exec-outside-date`, taskA, "COMPLETED", iso(-8 * 24 * 3_600_000), "st4-executor-1", iso(-8 * 24 * 3_600_000)],
  ];
  if (includeFuture) {
    executions.push(
      [`${FIXTURE_PREFIX}exec-1-future`, taskA, "PENDING", iso(15 * 60_000), "st4-executor-1", null],
      [`${FIXTURE_PREFIX}exec-2-future`, taskB, "PENDING", iso(30 * 60_000), "st4-executor-2", null],
    );
  }

  for (const [id, taskId, status, planned, executor, actual] of executions) {
    await client.query(
      `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time)
       VALUES ($1, $2, $3::timestamptz, $4, $5, $6::timestamptz) ON CONFLICT (id) DO NOTHING`,
      [id, taskId, planned, status, executor, actual],
    );
  }
}

/** 基础统计 fixture：两个执行人 + 未分配 + 未来（可选）+ 范围外日期。 */
async function seedStatisticsFixtures(includeFuture = true) {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await seedBaseClient(
      client,
      includeFuture,
      new Date(),
      `${FIXTURE_PREFIX}patient`,
      `${FIXTURE_PREFIX}period`,
      `${FIXTURE_PREFIX}task-a`,
      `${FIXTURE_PREFIX}task-b`,
    );
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 向已存在的统计 fixture 追加未来任务，用于验证未来任务不进入完成率分母。 */
async function insertFutureStatisticsFixtures() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    const now = Date.now();
    const iso = (offsetMs: number) => new Date(now + offsetMs).toISOString();
    const futureRows: Array<[string, string, string]> = [
      [`${FIXTURE_PREFIX}exec-1-future`, `${FIXTURE_PREFIX}task-a`, iso(15 * 60_000)],
      [`${FIXTURE_PREFIX}exec-2-future`, `${FIXTURE_PREFIX}task-b`, iso(30 * 60_000)],
    ];
    for (const [id, taskId, planned] of futureRows) {
      await client.query(
        `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
         VALUES ($1, $2, $3::timestamptz, 'PENDING', $4) ON CONFLICT (id) DO NOTHING`,
        [id, taskId, planned, id.includes("1-future") ? "st4-executor-1" : "st4-executor-2"],
      );
    }
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

/** 状态操作 fixture：同一执行人下 4 条过去计划时间记录，分别用于开始/完成/跳过/取消。 */
async function seedOperationFixtures() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `INSERT INTO healthcare.patients (id, name, status) VALUES ($1, $2, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}patient`, "统计测试患者"],
    );
    await client.query(
      `INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
       VALUES ($1, $2, 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}period`, `${FIXTURE_PREFIX}patient`],
    );
    for (const [id, description] of [
      [`${FIXTURE_PREFIX}task-start`, "统计开始操作"],
      [`${FIXTURE_PREFIX}task-complete`, "统计完成操作"],
    ] as const) {
      await client.query(
        `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
         VALUES ($1, $2, 'NURSING', $3, 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
        [id, `${FIXTURE_PREFIX}period`, description],
      );
    }
    const now = Date.now();
    const iso = (offsetMs: number) => new Date(now + offsetMs).toISOString();
    await client.query(
      `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
       VALUES ($1, $2, 'NURSING', '统计跳过操作', 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}task-skip`, `${FIXTURE_PREFIX}period`],
    );
    await client.query(
      `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
       VALUES ($1, $2, 'NURSING', '统计取消操作', 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}task-cancel`, `${FIXTURE_PREFIX}period`],
    );
    const ops: Array<[string, string, string, string]> = [
      [`${FIXTURE_PREFIX}exec-start`, `${FIXTURE_PREFIX}task-start`, "PENDING", iso(-60 * 60_000)],
      [`${FIXTURE_PREFIX}exec-complete`, `${FIXTURE_PREFIX}task-complete`, "IN_PROGRESS", iso(-2 * 3_600_000)],
      [`${FIXTURE_PREFIX}exec-skip`, `${FIXTURE_PREFIX}task-skip`, "PENDING", iso(-3 * 3_600_000)],
      [`${FIXTURE_PREFIX}exec-cancel`, `${FIXTURE_PREFIX}task-cancel`, "PENDING", iso(-4 * 3_600_000)],
    ];
    for (const [id, taskId, status, planned] of ops) {
      await client.query(
        `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
         VALUES ($1, $2, $3::timestamptz, $4, 'st4-ops') ON CONFLICT (id) DO NOTHING`,
        [id, taskId, planned, status],
      );
    }
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

class TodayWorkbench {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/inpatient", { waitUntil: "networkidle" });
    await expect(this.page.getByRole("button", { name: "刷新" })).toBeVisible();
  }

  rows() {
    return this.page
      .locator("table")
      .filter({ hasText: "计划时间" })
      .locator("tbody tr");
  }

  tableScrollBox() {
    return this.page
      .locator("table")
      .filter({ hasText: "计划时间" })
      .locator("xpath=ancestor::div[contains(@class,'overflow-x-auto')]");
  }

  async revealActions() {
    await this.tableScrollBox().evaluate((el) => {
      el.scrollLeft = el.scrollWidth;
    });
  }

  row(description: string) {
    return this.rows().filter({ hasText: description });
  }
}

class StatisticsPanel {
  constructor(private readonly page: Page) {}

  panel() {
    return this.page
      .getByRole("heading", { name: "工作量统计", exact: true })
      .locator('xpath=ancestor::div[contains(@class,"rounded-lg")][1]');
  }

  rows() {
    return this.panel().locator("table tbody tr");
  }

  value(label: string) {
    return this.panel()
      .getByText(label, { exact: true })
      .locator("xpath=following-sibling::p");
  }

  async query() {
    await this.panel().getByRole("button", { name: "查询", exact: true }).click();
  }

  async summaryText() {
    const row = this.rows().first();
    const cells = row.locator("td");
    return (await cells.nth(6).textContent())?.trim() ?? "";
  }

  async expectSummaryContains(...parts: string[]) {
    for (const part of parts) {
      await expect(this.rows().first()).toContainText(part, { useInnerText: true });
    }
  }
}

test.describe.configure({ mode: "serial" });

test.beforeAll(async () => {
  databasePool = new Pool({
    host: process.env.PLAYWRIGHT_DB_HOST ?? "127.0.0.1",
    port: Number(process.env.PLAYWRIGHT_DB_PORT ?? "55432"),
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

test("默认本周范围显示全局统计和至少一个执行人分组（含未分配）", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await seedStatisticsFixtures(true);
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  await expect(stats.rows()).toHaveCount(3);
  await expect(stats.value("计划任务")).toHaveText("10");
  await expect(stats.value("应完成")).toHaveText("8");
  await expect(stats.value("已完成")).toHaveText("3");
  await expect(stats.value("逾期")).toHaveText("3");
  await expect(stats.value("计划完成率")).toHaveText("37.50%");
  await expect(stats.panel().getByRole("cell", { name: "未分配", exact: true })).toBeVisible();
  await expect(stats.rows().first()).toContainText("st4-executor-1");
});

test("切换范围后卡片和表格更新；空范围显示空态和暂无应完成任务", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await seedStatisticsFixtures(true);
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  await expect(stats.rows()).toHaveCount(3);
  await expect(stats.value("计划任务")).toHaveText("10");

  const future = todayLocalDate().split("-").map(Number);
  const futureDate = new Date(Date.UTC(future[0]!, future[1]! - 1, future[2]! + 30));
  const futureValue = futureDate.toISOString().slice(0, 10);
  await page.getByLabel("开始日期").fill(futureValue);
  await page.getByLabel("结束日期").fill(futureValue);
  await stats.query();

  await expect(stats.panel().getByText("所选范围内暂无执行记录", { exact: true })).toBeVisible();
  await expect(stats.value("计划任务")).toHaveText("0");
  await expect(stats.value("应完成")).toHaveText("0");
  await expect(stats.value("计划完成率")).toHaveText("暂无应完成任务");
  await expect(stats.rows()).toHaveCount(0);
});

test("未来任务计入计划任务但不计入应完成和完成率分母", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await seedStatisticsFixtures(false);
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  await expect(stats.rows()).toHaveCount(3);
  await expect(stats.value("计划任务")).toHaveText("8");
  await expect(stats.value("应完成")).toHaveText("8");
  await expect(stats.value("已完成")).toHaveText("3");
  await expect(stats.value("计划完成率")).toHaveText("37.50%");

  await insertFutureStatisticsFixtures();
  await stats.query();
  await expect(stats.value("计划任务")).toHaveText("10");
  await expect(stats.value("应完成")).toHaveText("8");
  await expect(stats.value("已完成")).toHaveText("3");
  await expect(stats.value("计划完成率")).toHaveText("37.50%");
});

test("开始、完成、跳过、取消成功后今日列表和统计区均刷新", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await seedOperationFixtures();
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  await expect(workbench.rows()).toHaveCount(4);
  await expect(stats.value("计划任务")).toHaveText("4");
  await expect(stats.value("应完成")).toHaveText("4");
  await expect(stats.value("已完成")).toHaveText("0");
  await expect(stats.value("逾期")).toHaveText("4");
  await expect(stats.value("计划完成率")).toHaveText("0.00%");

  // 开始：PENDING -> IN_PROGRESS
  await workbench.revealActions();
  await workbench.row("统计开始操作").getByRole("button", { name: "开始" }).evaluate((el) => el.click());
  await expect(workbench.row("统计开始操作").getByText("执行中")).toBeVisible();
  await expect(stats.value("计划任务")).toHaveText("4");
  await expect(stats.value("应完成")).toHaveText("4");
  await expect(stats.value("已完成")).toHaveText("0");
  await expect(stats.value("逾期")).toHaveText("4");

  // 完成：IN_PROGRESS -> COMPLETED
  await workbench.revealActions();
  await workbench.row("统计完成操作").getByRole("button", { name: "完成" }).evaluate((el) => el.click());
  await expect(page.getByText("完成任务", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "确认完成" }).click({ force: true });
  await expect(workbench.row("统计完成操作").getByText("已完成")).toBeVisible();
  await expect(stats.value("已完成")).toHaveText("1");
  await expect(stats.value("逾期")).toHaveText("3");
  await expect(stats.value("计划完成率")).toHaveText("25.00%");

  // 跳过：PENDING -> SKIPPED，必须填写原因
  await workbench.revealActions();
  await workbench.row("统计跳过操作").getByRole("button", { name: "跳过" }).evaluate((el) => el.click());
  await expect(page.getByText("跳过任务", { exact: true })).toBeVisible();
  await page.getByLabel("跳过原因").fill("自动化验收跳过");
  await page.getByRole("button", { name: "确认跳过" }).click({ force: true });
  await expect(workbench.row("统计跳过操作").getByText("已跳过")).toBeVisible();
  await expect(stats.value("已完成")).toHaveText("1");
  await expect(stats.value("逾期")).toHaveText("2");
  await expect(stats.value("计划完成率")).toHaveText("25.00%");

  // 取消：PENDING -> CANCELLED，必须填写原因
  await workbench.revealActions();
  await workbench.row("统计取消操作").getByRole("button", { name: "取消" }).evaluate((el) => el.click());
  await expect(page.getByText("取消任务", { exact: true })).toBeVisible();
  await page.getByLabel("取消原因").fill("自动化验收取消");
  await page.getByRole("button", { name: "确认取消" }).click({ force: true });
  await expect(workbench.row("统计取消操作").getByText("已取消")).toBeVisible();
  await expect(stats.value("计划任务")).toHaveText("4");
  await expect(stats.value("应完成")).toHaveText("4");
  await expect(stats.value("已完成")).toHaveText("1");
  await expect(stats.value("逾期")).toHaveText("1");
  await expect(stats.value("计划完成率")).toHaveText("25.00%");
});

test("统计请求失败时今日列表仍可操作", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await seedOperationFixtures();
  await page.route("**/executions/statistics**", (route) => route.abort());
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  // 统计区显示独立错误，不阻塞今日列表加载
  await expect(page.getByText(/无法加载统计数据|无法连接到服务，请检查网络或服务状态|Failed to fetch|NetworkError|net::ERR_FAILED/i)).toBeVisible();

  await workbench.revealActions();
  await workbench.row("统计开始操作").getByRole("button", { name: "开始" }).evaluate((el) => el.click());
  await expect(workbench.row("统计开始操作").getByText("执行中")).toBeVisible();
  await expect(stats.rows()).toHaveCount(0);
});

test("未分配显示未分配；窄屏下筛选、统计卡和表格可操作", async ({ page }) => {
  test.skip(!canSeedStatisticsFixtures(), "本地时间需 ≥ 08:00，保证统计 fixture 落在今日日期范围");
  await page.setViewportSize({ width: 375, height: 812 });
  await seedStatisticsFixtures(true);
  const workbench = new TodayWorkbench(page);
  const stats = new StatisticsPanel(page);

  await workbench.goto();
  await expect(stats.rows()).toHaveCount(3);
  await expect(stats.panel().getByRole("cell", { name: "未分配", exact: true })).toBeVisible();
  await expect(page.getByLabel("开始日期")).toBeEnabled();
  await expect(page.getByLabel("结束日期")).toBeEnabled();
  await expect(stats.panel().getByLabel("执行人")).toBeEnabled();
  await expect(stats.panel().getByRole("button", { name: "查询", exact: true })).toBeEnabled();
  await expect(stats.value("计划任务")).toHaveText("10");
});