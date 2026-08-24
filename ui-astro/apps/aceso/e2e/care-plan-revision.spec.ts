import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

/**
 * Aceso 定期复评与照护计划修订（docs/plans/009.aceso-periodic-reassessment-care-plan-revision.md）
 * 浏览器端到端验收。
 *
 * 覆盖计划第「用户后续手动测试」与后置验证核心主线：
 *  1. 活动养老入住已有初次计划时，从 UI 提交复评并修订计划；
 *     成功后旧计划转已终止、新计划转执行中、新任务生成、修订历史出现。
 *  2. 修订历史详情可读：展示复评、上一版计划（已终止）、新计划版本与任务。
 *  3. 当前计划存在 IN_PROGRESS 执行时，复评提交被拒绝且页面数据不变。
 *
 * 运行前提（已有服务，仅校验不启动）：
 *  - Aceso API :8422 连接隔离测试库 aceso_test（127.0.0.1:55432）
 *  - Aceso UI :4324、身份服务 :8420、Nexus :8421
 *  - PLAYWRIGHT_BASE_URL 使用与 API/IDP 同站点地址
 *
 * 基线数据通过页面内登录态调用真实 API 创建（fixture 前缀 r9-），
 * 每场景前后按外键顺序清理并断言残差为零。
 */

const FIXTURE_PREFIX = "r9-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;

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

interface NursingPlan {
  id: string;
  period_id: string;
  encounter_id: string | null;
  plan_name: string;
  goals: string | null;
  status: string;
  start_date: string | null;
  end_date: string | null;
  items?: Array<{ id: string; action: string; frequency_code: string | null; frequency_name: string | null; duration_days: number | null }>;
}

interface ApiResult<T> {
  status: number;
  body: T;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso care plan revision tests`);
  return value;
}

function todayLocalDate(): string {
  const d = new Date();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    // 修订关系表先于计划/评估删除
    await client.query(
      `DELETE FROM nursing.nursing_care_plan_revisions revision
       WHERE revision.period_id IN (
         SELECT period.id
         FROM nursing.nursing_service_periods period
         LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
         LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id OR patient.id = encounter.patient_id
         WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
            OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1
       )`,
      [fixturePattern],
    );
    // 执行记录
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
    // 任务
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
    // 计划措施
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
    // 计划
    await client.query(
      `DELETE FROM nursing.nursing_plans plan
       USING nursing.nursing_service_periods period
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE plan.period_id = period.id
         AND (plan.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    // 评估
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
    // 周期
    await client.query(
      `DELETE FROM nursing.nursing_service_periods period
       WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
          OR period.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
          OR period.patient_id IN (SELECT id FROM healthcare.patients WHERE name LIKE $1)`,
      [fixturePattern],
    );
    // 入住记录
    await client.query(
      `DELETE FROM healthcare.encounters encounter
       USING healthcare.patients patient
       WHERE encounter.patient_id = patient.id
         AND (encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    // 患者
    await client.query(
      `DELETE FROM healthcare.patients
       WHERE id LIKE $1 OR name LIKE $1`,
      [fixturePattern],
    );

    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods period LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id WHERE period.id LIKE $1 OR period.encounter_id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_assessments assessment WHERE assessment.id LIKE $1 OR assessment.encounter_id LIKE $1 OR assessment.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plans plan WHERE plan.id LIKE $1 OR plan.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plan_items item WHERE item.id LIKE $1 OR item.plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_tasks task WHERE task.id LIKE $1 OR task.encounter_id LIKE $1 OR task.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_task_executions execution WHERE execution.id LIKE $1 OR execution.task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_care_plan_revisions revision WHERE revision.id LIKE $1 OR revision.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1))
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
  );
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}: ${JSON.stringify(result.body)}`);
  return result.body as T;
}

async function ensureAuthenticated(page: Page) {
  await page.goto("/dashboard/admission", { waitUntil: "networkidle" });
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

interface Baseline {
  patientId: string;
  encounterId: string;
  encounterNo: string;
  periodId: string;
  planId: string;
  itemId: string;
  taskId: string;
}

async function createBaseline(page: Page, suffix: string): Promise<Baseline> {
  const today = todayLocalDate();
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}-患者` },
  });
  const admission = await api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patient.id,
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: "2026-08-01T00:00:00+08:00",
    },
  });
  const encounterId = admission.encounter.id;
  const periodId = admission.nursing_period.id;

  await api(page, "/crate-api/nursing/v1/assessments/", {
    method: "POST",
    body: {
      period_id: periodId,
      encounter_id: encounterId,
      assess_type: "BARTHEL",
      assess_date: today,
      assessor: "r9-e2e",
      total_score: 80,
      result_level: "低风险",
    },
  });

  const plan = await api<NursingPlan>(page, "/crate-api/nursing/v1/plans/", {
    method: "POST",
    body: {
      period_id: periodId,
      encounter_id: encounterId,
      plan_name: "基线计划",
      goals: "保持日常活动能力",
      created_by: "r9-e2e",
      start_date: today,
      end_date: "2026-09-05",
      items: [
        { action: "每日步行训练", frequency_code: "QD", frequency_name: "每日一次", duration_days: 14 },
      ],
    },
  });
  const detail = await api<NursingPlan>(page, `/crate-api/nursing/v1/plans/${plan.id}`);
  const itemId = detail.items?.[0]?.id;
  if (!itemId) throw new Error("baseline plan item was not created");

  const task = await api<{ id: string }>(page, "/crate-api/nursing/v1/tasks/", {
    method: "POST",
    body: {
      period_id: periodId,
      encounter_id: encounterId,
      plan_item_id: itemId,
      task_type: "NURSING",
      description: "每日步行训练",
      frequency_code: "QD",
      frequency_name: "每日一次",
      start_date: today,
      end_date: "2026-09-05",
    },
  });

  return {
    patientId: patient.id,
    encounterId,
    encounterNo: admission.encounter.encounter_no,
    periodId,
    planId: plan.id,
    itemId,
    taskId: task.id,
  };
}

async function openResidentPlans(page: Page, encounterNo: string) {
  await page.goto("/dashboard/inpatient", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "长者照护档案" }).click();
  await page.getByRole("button").filter({ hasText: encounterNo }).first().click();
  await page.getByRole("button", { name: "照护计划", exact: true }).click();
  await expect(page.getByRole("button", { name: "复评并修订计划" })).toBeVisible();
}

async function fillRevisionForm(page: Page, planName: string) {
  await page.locator("#计划名称").fill(planName);
  await page.locator("#开始日期").fill(todayLocalDate());
  await page.locator("#revision-goals").fill("提高日常活动能力");
  await page.locator("#revision-detail").fill("复评详情：步行能力下降");
  await page.locator("#revision-level").selectOption("中风险");
  await page.locator("#总分").fill("65");
  await page.locator("#措施").fill("每日协助步行训练");
  await page.locator("#revision-freq-0").selectOption("QD");
  await page.locator("#天数").fill("14");
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

test("活动养老入住通过 UI 完成复评并修订计划：旧计划终止、新计划执行中、新任务生成、修订历史出现", async ({ page }) => {
  const baseline = await createBaseline(page, "ok");
  await openResidentPlans(page, baseline.encounterNo);

  // 修订前：只有一个执行中基线计划，无修订历史
  await expect(page.getByText("基线计划", { exact: true })).toBeVisible();
  await expect(page.getByText("已终止", { exact: true })).toHaveCount(0);
  await expect(page.getByText("修订历史（复评）", { exact: true })).toHaveCount(0);

  await page.getByRole("button", { name: "复评并修订计划" }).click();
  await expect(page.getByText("复评并修订照护计划", { exact: true })).toBeVisible();
  await fillRevisionForm(page, "复评后计划");
  await page.getByRole("button", { name: "提交复评并修订" }).evaluate((el: HTMLButtonElement) => el.click());

  // 弹窗关闭，刷新后出现新计划与旧计划终态
  await expect(page.getByText("复评并修订照护计划", { exact: true })).not.toBeVisible();
  await expect(page.getByRole("heading", { name: "复评后计划", exact: true })).toBeVisible();
  await expect(page.getByText("基线计划", { exact: true })).toBeVisible();
  await expect(page.getByText("已终止", { exact: true })).toBeVisible();
  await expect(page.getByText("修订历史（复评）", { exact: true })).toBeVisible();
  await expect(page.getByText(/修订\s+1/)).toBeVisible();

  // 任务页签：旧任务已取消，新任务已生成
  await page.getByRole("button", { name: "任务执行", exact: true }).click();
  await expect(page.getByText("每日步行训练", { exact: true })).toBeVisible();
  await expect(page.getByText("每日协助步行训练", { exact: true })).toBeVisible();
  await expect(page.getByText("已取消", { exact: true })).toBeVisible();
  await expect(page.getByText("执行中", { exact: true })).toBeVisible();
});

test("UI 可读取修订历史详情：复评、上一版计划、新计划版本和新任务", async ({ page }) => {
  const baseline = await createBaseline(page, "detail");
  // 先通过 API 完成一次修订，再进入 UI 查看详情
  await api(page, `/crate-api/healthcare/v1/encounters/${baseline.encounterId}/care-plan-revisions`, {
    method: "POST",
    body: {
      assessment: {
        assess_type: "BARTHEL",
        assess_date: todayLocalDate(),
        assessor: "r9-e2e",
        total_score: 65,
        result_level: "中度依赖",
        detail: { note: "步行能力下降" },
        remark: "复评说明",
      },
      plan: {
        plan_name: "复评后计划",
        goals: "提高日常活动能力",
        created_by: "r9-e2e",
        start_date: todayLocalDate(),
        end_date: "2026-09-05",
        items: [
          { action: "每日协助步行训练", frequency_code: "QD", frequency_name: "每日一次", duration_days: 14 },
        ],
      },
    },
  });

  await openResidentPlans(page, baseline.encounterNo);
  await expect(page.getByText("修订历史（复评）", { exact: true })).toBeVisible();
  await page.getByRole("button").filter({ hasText: "修订 1" }).evaluate((el: HTMLButtonElement) => el.click());
  await expect(page.getByText("上一版计划（已终止）", { exact: true })).toBeVisible();
  await expect(page.getByText("新计划版本（执行中）", { exact: true })).toBeVisible();
  await expect(page.getByText("基线计划", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("复评后计划", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("每日协助步行训练", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("任务：每日协助步行训练", { exact: true })).toBeVisible();
});

test("当前计划存在 IN_PROGRESS 执行时，复评被拒绝且页面数据不变", async ({ page }) => {
  const baseline = await createBaseline(page, "block");
  // 为基线任务直接插入一条 IN_PROGRESS 执行（fixture，不走认证状态接口）
  {
    const client = await databasePool.connect();
    try {
      await client.query(
        `INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
         VALUES ($1, $2, $3::timestamptz, 'IN_PROGRESS', 'r9-executor')`,
        [`${FIXTURE_PREFIX}exec-block`, baseline.taskId, new Date().toISOString()],
      );
    } finally {
      client.release();
    }
  }

  await openResidentPlans(page, baseline.encounterNo);
  await expect(page.getByText("基线计划", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "复评并修订计划" }).click();
  await fillRevisionForm(page, "不应出现的新计划");
  await page.getByRole("button", { name: "提交复评并修订" }).evaluate((el: HTMLButtonElement) => el.click());

  // 被拒绝：弹窗保留并显示错误
  await expect(page.getByText("复评并修订照护计划", { exact: true })).toBeVisible();
  await expect(page.getByText(/cannot revise care plan while a task execution is in progress|执行.*进行中/i)).toBeVisible();

  // 关闭弹窗后数据不变：无新计划、无修订历史、旧计划仍执行中
  await page.getByRole("button", { name: "取消" }).evaluate((el: HTMLButtonElement) => el.click());
  await expect(page.getByText("复评后计划", { exact: true })).toHaveCount(0);
  await expect(page.getByText("不应出现的新计划", { exact: true })).toHaveCount(0);
  await expect(page.getByText("修订历史（复评）", { exact: true })).toHaveCount(0);
  await expect(page.getByText("基线计划", { exact: true })).toBeVisible();
  await expect(page.getByText("已终止", { exact: true })).toHaveCount(0);
});