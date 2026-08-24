import { Pool } from "pg";
import { expect, test } from "@playwright/test";

/**
 * Aceso 护理任务执行逾期提醒（docs/plans/003.aceso-nursing-overdue-execution-alerts.md）
 * 浏览器验收场景。
 *
 * 验收口径（第 7.2 节 + 12.2 前端筛选行为）：
 *  1. 默认无逾期时显示「无逾期」，不改变既有列表
 *  2. 逾期按钮显示准确数量；点击后只显示逾期行，再次点击恢复全部
 *  3. 逾期行显示正确中文时长；完成该行后刷新，行标签与逾期数量减少
 *  4. 日期、状态筛选与逾期开关互不重置（进入逾期时清空终态筛选、退出时恢复）
 *  5. 窄屏（375×812）下按钮、标签、表格仍可操作
 *
 * 运行前提（已有服务，仅校验不启动）：
 *  - Aceso API :8422 连接独立测试库 aceso_test（127.0.0.1:55432，Flyway 全量迁移）
 *  - Aceso UI :4324、身份服务 :8420、Nexus :8421
 *  - PLAYWRIGHT_BASE_URL 必须指向 http://192.168.0.109:4324（与 API/IDP 同站点，
 *    否则 SameSite=Lax 的 identityd_session cookie 不会随跨站 fetch 发送）
 *
 * fixture 通过数据库直接插入（前缀 ov-），计划时间相对服务端 now 偏移，得到
 * 2 条逾期 + 2 条非逾期；不经过浏览器 API 调用，避免受登录 token 存储形态影响。
 */

const FIXTURE_PREFIX = "ov-";

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso overdue browser tests`);
  return value;
}

function todayLocalDate(): string {
  const d = new Date();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

/** 服务端 now 与窗口边界：今日工作台查询窗口为 date 的 UTC 日界（本地 +08 即 08:00 至次日 08:00）。
 *  逾期 3 小时的任务需要在本地 11:00 后运行才落在窗口内，故设置硬性运行前提。 */
function canSeedOverdueFixtures(): boolean {
  return new Date().getHours() >= 11;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `DELETE FROM nursing.nursing_task_execution_consumptions
       WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE $1)`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_executions
       WHERE id LIKE $1 OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(`DELETE FROM nursing.nursing_tasks WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM nursing.nursing_service_periods WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE $1) +
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1)
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

/**
 * fixture：
 *  - task A「喂药照护」：PENDING now-35min（逾期）、COMPLETED now-3h（终态非逾期）
 *  - task B「翻身护理」：IN_PROGRESS now-2h（逾期）、PENDING now+2h（未来非逾期）
 * 频次 PRN（不可自动生成），避免 todayExecutions 的 ensureExecutionsForDate 补建执行。
 */
async function seedOverdueFixtures() {
  const now = Date.now();
  const iso = (offsetMs: number) => new Date(now + offsetMs).toISOString();
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `INSERT INTO healthcare.patients (id, name, status) VALUES ($1, $2, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}patient`, "逾期测试患者"],
    );
    await client.query(
      `INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
       VALUES ($1, $2, 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}period`, `${FIXTURE_PREFIX}patient`],
    );
    await client.query(
      `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
       VALUES ($1, $2, 'NURSING', '喂药照护', 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}task-a`, `${FIXTURE_PREFIX}period`],
    );
    await client.query(
      `INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
       VALUES ($1, $2, 'NURSING', '翻身护理', 'PRN', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING`,
      [`${FIXTURE_PREFIX}task-b`, `${FIXTURE_PREFIX}period`],
    );
    const executions: Array<[string, string, string, string | null, string | null]> = [
      // id, task, status, planned_time, actual_time
      [`${FIXTURE_PREFIX}exec-pending-35min`, `${FIXTURE_PREFIX}task-a`, "PENDING", iso(-35 * 60_000), null],
      [`${FIXTURE_PREFIX}exec-completed`, `${FIXTURE_PREFIX}task-a`, "COMPLETED", iso(-3 * 3_600_000), iso(0)],
      [`${FIXTURE_PREFIX}exec-inprogress-2h`, `${FIXTURE_PREFIX}task-b`, "IN_PROGRESS", iso(-2 * 3_600_000), null],
      [`${FIXTURE_PREFIX}exec-pending-future`, `${FIXTURE_PREFIX}task-b`, "PENDING", iso(2 * 3_600_000), null],
    ];
    for (const [id, taskId, status, planned, actual] of executions) {
      await client.query(
        `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time)
         VALUES ($1, $2, $3::timestamptz, $4, $5::timestamptz) ON CONFLICT (id) DO NOTHING`,
        [id, taskId, planned, status, actual],
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

async function ensureAuthenticated(page: import("@playwright/test").Page) {
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

class TodayWorkbench {
  constructor(private readonly page: import("@playwright/test").Page) {}

  async goto() {
    await this.page.goto("/dashboard/inpatient", { waitUntil: "networkidle" });
    await expect(this.page.getByRole("button", { name: "刷新" })).toBeVisible();
  }

  async refresh() {
    await this.page.getByRole("button", { name: "刷新" }).click();
    await expect(this.page.locator("tbody tr").first()).toBeVisible();
  }

  /** 今日执行表：以表头「计划时间」区分于工作量统计面板自己的表格 */
  rows() {
    return this.page
      .locator("table")
      .filter({ hasText: "计划时间" })
      .locator("tbody tr");
  }

  /** 今日执行表的横向滚动容器 */
  tableScrollBox() {
    return this.page
      .locator("table")
      .filter({ hasText: "计划时间" })
      .locator("xpath=ancestor::div[contains(@class,'overflow-x-auto')]");
  }

  overdueButton() {
    return this.page.getByRole("button", { name: /逾期\s*\d+/ });
  }

  statusFilter() {
    return this.page.locator("#today-status-filter");
  }

  workbenchDateInput() {
    return this.page.locator('input[type="date"]').first();
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

test("默认无逾期时显示「无逾期」且空态列表不被改变", async ({ page }) => {
  const workbench = new TodayWorkbench(page);
  await workbench.goto();
  await expect(page.getByText("无逾期", { exact: true })).toBeVisible();
  await expect(page.getByText(/今日暂无待执行任务/)).toBeVisible();
  await expect(workbench.statusFilter()).toHaveValue("");
  await expect(workbench.workbenchDateInput()).toHaveValue(todayLocalDate());
});

test("逾期按钮数量准确、点击只显示逾期行、再点恢复全部", async ({ page }) => {
  test.skip(!canSeedOverdueFixtures(), "本地时间需 ≥ 11:00，保证逾期 fixture 落在今日 UTC 窗口内");
  await seedOverdueFixtures();
  const workbench = new TodayWorkbench(page);
  await workbench.goto();
  await workbench.refresh();

  // 4 行：PENDING 逾期、IN_PROGRESS 逾期、COMPLETED、PENDING 未来
  await expect(workbench.rows()).toHaveCount(4);
  await expect(workbench.overdueButton()).toHaveText(/逾期\s*2/);

  // 两条逾期行显示正确中文时长
  const pendingOverdueRow = workbench.rows().filter({ hasText: "喂药照护" }).filter({ hasText: "待执行" });
  const inProgressOverdueRow = workbench.rows().filter({ hasText: "翻身护理" }).filter({ hasText: "执行中" });
  await expect(pendingOverdueRow.getByText("已逾期 35 分钟", { exact: true })).toBeVisible();
  await expect(inProgressOverdueRow.getByText("已逾期 2 小时", { exact: true })).toBeVisible();

  // 终态与未来行绝不带逾期标签
  const completedOverdueLabel = workbench.rows().filter({ hasText: "喂药照护" }).filter({ hasText: "已完成" });
  const futureRow = workbench.rows().filter({ hasText: "翻身护理" }).filter({ hasText: "待执行" });
  await expect(completedOverdueLabel).toHaveCount(1);
  await expect(completedOverdueLabel).not.toContainText("已逾期");
  await expect(futureRow).toHaveCount(1);
  await expect(futureRow).not.toContainText("已逾期");

  // 进入逾期筛选：只剩 2 条逾期行
  await workbench.overdueButton().click();
  await expect(workbench.rows()).toHaveCount(2);
  await expect(workbench.rows().filter({ hasText: "已逾期" })).toHaveCount(2);

  // 一键恢复全部
  await page.getByRole("button", { name: "显示全部" }).click();
  await expect(workbench.rows()).toHaveCount(4);
});

test("状态筛选与逾期开关互不重置（终态筛选进入时清空、退出时恢复）", async ({ page }) => {
  test.skip(!canSeedOverdueFixtures(), "本地时间需 ≥ 11:00，保证逾期 fixture 落在今日 UTC 窗口内");
  await seedOverdueFixtures();
  const workbench = new TodayWorkbench(page);
  await workbench.goto();
  await workbench.refresh();

  // 终态筛选下的列表只有 1 行
  await workbench.statusFilter().selectOption("COMPLETED");
  await expect(workbench.rows()).toHaveCount(1);
  const dateBefore = await workbench.workbenchDateInput().inputValue();

  // 进入逾期：与逾期互斥的终态筛选被清空，日期保留，只显示逾期行
  await workbench.overdueButton().click();
  await expect(workbench.statusFilter()).toHaveValue("");
  await expect(workbench.workbenchDateInput()).toHaveValue(dateBefore);
  await expect(workbench.rows()).toHaveCount(2);

  // 退出逾期：恢复进入前的终态筛选与列表
  await page.getByRole("button", { name: "显示全部" }).click();
  await expect(workbench.statusFilter()).toHaveValue("COMPLETED");
  await expect(workbench.rows()).toHaveCount(1);

  // 可组合筛选：PENDING 不被逾期开关清空，且可与逾期叠加
  await workbench.statusFilter().selectOption("PENDING");
  await expect(workbench.rows()).toHaveCount(2);
  await workbench.overdueButton().click();
  await expect(workbench.statusFilter()).toHaveValue("PENDING");
  await expect(workbench.rows()).toHaveCount(1);
  await expect(workbench.rows().filter({ hasText: "已逾期 35 分钟" })).toHaveCount(1);
  await page.getByRole("button", { name: "显示全部" }).click();
  await expect(workbench.statusFilter()).toHaveValue("PENDING");
  await expect(workbench.rows()).toHaveCount(2);
});

test("完成逾期任务后刷新，行标签与逾期数量减少", async ({ page }) => {
  test.skip(!canSeedOverdueFixtures(), "本地时间需 ≥ 11:00，保证逾期 fixture 落在今日 UTC 窗口内");
  await seedOverdueFixtures();
  const workbench = new TodayWorkbench(page);
  await workbench.goto();
  await workbench.refresh();
  await expect(workbench.overdueButton()).toHaveText(/逾期\s*2/);

  // 操作列在表格最右侧，窄屏下先横向滚动表格容器露出操作按钮（等同触摸滑动）
  await workbench.tableScrollBox().evaluate((el) => {
    el.scrollLeft = el.scrollWidth;
  });

  // 完成执行中的逾期任务（翻身护理）
  const inProgressOverdueRow = workbench.rows().filter({ hasText: "翻身护理" }).filter({ hasText: "已逾期 2 小时" });
  await inProgressOverdueRow.getByRole("button", { name: "完成" }).click();
  await expect(page.getByText("完成任务", { exact: true })).toBeVisible();
  const confirmButton = page.getByRole("button", { name: "确认完成" });
  await confirmButton.click({ force: true });

  // 刷新后该行变为已完成、无逾期标签，逾期总数降为 1
  await expect(workbench.overdueButton()).toHaveText(/逾期\s*1/);
  const completedRow = workbench.rows().filter({ hasText: "翻身护理" }).filter({ hasText: "已完成" });
  await expect(completedRow).toHaveCount(1);
  await expect(completedRow).not.toContainText("已逾期");
  await expect(workbench.rows()).toHaveCount(4);
  await expect(workbench.rows().filter({ hasText: "已逾期" })).toHaveCount(1);
});

test("窄屏 375×812 下逾期按钮、标签与表格可操作", async ({ page }) => {
  test.skip(!canSeedOverdueFixtures(), "本地时间需 ≥ 11:00，保证逾期 fixture 落在今日 UTC 窗口内");
  await page.setViewportSize({ width: 375, height: 812 });
  await seedOverdueFixtures();
  const workbench = new TodayWorkbench(page);
  await workbench.goto();
  await workbench.refresh();

  const overdueButton = workbench.overdueButton();
  await expect(overdueButton).toBeVisible();
  await expect(overdueButton).toBeEnabled();
  await expect(page.getByText("已逾期 35 分钟", { exact: true })).toBeVisible();

  // 今日执行表容器在窄屏下可横向滚动
  const scrollable = workbench.tableScrollBox();
  const box = await scrollable.evaluate((el) => ({ scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }));
  expect(box.scrollWidth).toBeGreaterThan(box.clientWidth);

  // 逾期筛选与既有状态操作按钮仍可用
  await overdueButton.click();
  await expect(workbench.rows()).toHaveCount(2);
  const inProgressRow = workbench.rows().filter({ hasText: "已逾期 2 小时" });
  await expect(inProgressRow.getByRole("button", { name: "完成" })).toBeEnabled();
  await expect(inProgressRow.getByRole("button", { name: "取消" })).toBeEnabled();
});