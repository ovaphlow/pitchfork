import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

const FIXTURE_PREFIX = "pw-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;

interface Encounter {
  id: string;
  encounter_no: string;
  patient_id: string;
}

interface AdmissionResponse {
  encounter: Encounter;
  nursing_period: { id: string; encounter_id: string; service_type: string };
}

interface PeriodResponse {
  records: Array<{ id: string; encounter_id: string; service_type: string; status: string }>;
}

interface TaskResponse {
  id: string;
}

interface ExecutionResponse {
  id: string;
}

interface ApiResult<T> {
  status: number;
  body: T;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso lifecycle tests`);
  return value;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    await client.query(
      `DELETE FROM nursing.nursing_task_executions
       WHERE id LIKE $1
          OR task_id IN (
            SELECT id
            FROM nursing.nursing_tasks
            WHERE id LIKE $1
               OR period_id IN (
                 SELECT id
                 FROM nursing.nursing_service_periods
                 WHERE id LIKE $1
                    OR encounter_id IN (
                      SELECT id
                      FROM healthcare.encounters
                      WHERE id LIKE $1
                         OR encounter_no LIKE $1
                         OR patient_id IN (
                           SELECT id
                           FROM healthcare.patients
                           WHERE id LIKE $1 OR name LIKE $1
                         )
                    )
               )
               OR encounter_id IN (
                 SELECT id
                 FROM healthcare.encounters
                 WHERE id LIKE $1
                    OR encounter_no LIKE $1
                    OR patient_id IN (
                      SELECT id
                      FROM healthcare.patients
                      WHERE id LIKE $1 OR name LIKE $1
                    )
               )
          )`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_tasks
       WHERE id LIKE $1
          OR period_id IN (
            SELECT id
            FROM nursing.nursing_service_periods
            WHERE id LIKE $1
               OR encounter_id IN (
                 SELECT id
                 FROM healthcare.encounters
                 WHERE id LIKE $1
                    OR encounter_no LIKE $1
                    OR patient_id IN (
                      SELECT id
                      FROM healthcare.patients
                      WHERE id LIKE $1 OR name LIKE $1
                    )
               )
          )
          OR encounter_id IN (
            SELECT id
            FROM healthcare.encounters
            WHERE id LIKE $1
               OR encounter_no LIKE $1
               OR patient_id IN (
                 SELECT id
                 FROM healthcare.patients
                 WHERE id LIKE $1 OR name LIKE $1
               )
          )`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_service_periods
       WHERE id LIKE $1
          OR encounter_id IN (
            SELECT id
            FROM healthcare.encounters
            WHERE id LIKE $1
               OR encounter_no LIKE $1
               OR patient_id IN (
                 SELECT id
                 FROM healthcare.patients
                 WHERE id LIKE $1 OR name LIKE $1
               )
          )`,
      [`${FIXTURE_PREFIX}%`],
    );
    const childResult = await client.query<{ residual: string }>(`
      SELECT (
        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR encounter_no LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1)))
      )::text AS residual
    `, [`${FIXTURE_PREFIX}%`]);
    if (childResult.rows[0]?.residual !== "0") throw new Error("fixture cleanup left residual nursing data");
    await client.query(
      `DELETE FROM healthcare.encounters
       WHERE id LIKE $1
          OR encounter_no LIKE $1
          OR patient_id IN (
            SELECT id
            FROM healthcare.patients
            WHERE id LIKE $1 OR name LIKE $1
          )`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      "DELETE FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1",
      [`${FIXTURE_PREFIX}%`],
    );
    const result = await client.query<{ residual: string }>(`
      SELECT (
        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters WHERE id LIKE $1 OR encounter_no LIKE $1) +
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1)
      )::text AS residual
    `, [`${FIXTURE_PREFIX}%`]);
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
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}`);
  return result.body;
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

async function createPatient(page: Page, suffix: string): Promise<string> {
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}` },
  });
  return patient.id;
}

async function createUnboundAdmission(page: Page, suffix: string, admitDate: string): Promise<Encounter> {
  const patientId = await createPatient(page, `${suffix}-patient`);
  return api<Encounter>(page, "/crate-api/healthcare/v1/encounters", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_type: "ELDERLY_CARE",
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: `${admitDate}T00:00:00+08:00`,
    },
  });
}

async function createBoundAdmission(page: Page, suffix: string, admitDate: string): Promise<AdmissionResponse> {
  const patientId = await createPatient(page, `${suffix}-patient`);
  return api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: `${admitDate}T00:00:00+08:00`,
    },
  });
}

async function createBusyAdmission(page: Page, suffix: string): Promise<Encounter> {
  const encounter = await createUnboundAdmission(page, suffix, "2026-07-31");
  const period = await api<{ id: string }>(page, "/crate-api/nursing/v1/periods/elderly-admission", {
    method: "POST",
    body: { encounter_id: encounter.id },
  });
  const task = await api<TaskResponse>(page, "/crate-api/nursing/v1/tasks/", {
    method: "POST",
    body: {
      period_id: period.id,
      encounter_id: encounter.id,
      task_type: "NURSING",
      description: "执行中测试任务",
      frequency_code: "QD",
      start_date: "2026-07-31",
    },
  });
  const execution = await api<ExecutionResponse>(page, "/crate-api/nursing/v1/executions/", {
    method: "POST",
    body: {
      task_id: task.id,
      planned_time: "2026-08-01T09:00:00+08:00",
      executor: "test-executor",
    },
  });
  await api(page, `/crate-api/nursing/v1/executions/${execution.id}/status`, {
    method: "PATCH",
    body: { status: "IN_PROGRESS" },
  });
  return encounter;
}

class AdmissionsPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/admission");
    await this.page.waitForLoadState("networkidle");
  }

  row(encounterNo: string) {
    return this.page.getByRole("row").filter({ hasText: encounterNo });
  }

  async discharge(encounterNo: string): Promise<number> {
    const responsePromise = this.page.waitForResponse(
      (response) => response.url().includes("/encounters/") && response.url().includes("/discharge"),
    );
    const dialogPromise = this.page.waitForEvent("dialog").then((dialog) => dialog.accept());
    await this.row(encounterNo).getByRole("button", { name: /办理离院/ }).click();
    await dialogPromise;
    return (await responsePromise).status();
  }
}

class NursingPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/inpatient");
    await this.page.waitForLoadState("networkidle");
    await this.page.getByRole("button", { name: "长者照护档案" }).click();
    await this.page.waitForLoadState("networkidle");
  }

  async selectAdmission(encounterNo: string) {
    await this.page.getByRole("button").filter({ hasText: encounterNo }).click();
  }

  async periodInfo() {
    const summary = this.page.locator("[data-period-summary]");
    await expect(summary).toBeVisible();
    const paragraphs = await summary.locator("p").allTextContents();
    const periodStatus = paragraphs[1] ?? "";
    const serviceType = paragraphs.find((paragraph) => paragraph.startsWith("服务类型：")) ?? "";
    const encounter = paragraphs.find((paragraph) => paragraph.startsWith("关联入住：")) ?? "";
    return {
      serviceType: serviceType.replace("服务类型：", "").trim() || null,
      encounterNo: encounter.replace("关联入住：", "").trim() || null,
      status: periodStatus.includes("进行中") ? "ACTIVE" : null,
      periodId: await summary.getAttribute("data-period-id"),
    };
  }

  recoverButton() {
    return this.page.getByRole("button", { name: "建立养老照护周期" });
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

test("创建养老入住后护理工作台显示匹配的养老照护周期", async ({ page }) => {
  const admission = await createBoundAdmission(page, "ENC", "2026-07-31");
  const admissionsPage = new AdmissionsPage(page);
  const nursingPage = new NursingPage(page);

  await admissionsPage.goto();
  await expect(admissionsPage.row(admission.encounter.encounter_no)).toBeVisible();
  await nursingPage.goto();
  await nursingPage.selectAdmission(admission.encounter.encounter_no);

  await expect.poll(async () => (await nursingPage.periodInfo()).periodId).toBe(admission.nursing_period.id);
  const periodInfo = await nursingPage.periodInfo();
  expect(periodInfo.serviceType).toBe("ELDERLY_CARE");
  expect(periodInfo.encounterNo).toBe(admission.encounter.encounter_no);
  expect(periodInfo.status).toBe("ACTIVE");
});

test("历史未绑定活动入住恢复动作只建立一次正确周期", async ({ page }) => {
  const encounter = await createUnboundAdmission(page, "HIST", "2026-07-01");
  const nursingPage = new NursingPage(page);
  await nursingPage.goto();
  await nursingPage.selectAdmission(encounter.encounter_no);

  await expect(nursingPage.recoverButton()).toBeVisible();
  const firstResponse = page.waitForResponse(
    (response) => response.url().includes("/periods/elderly-admission") && response.status() === 201,
  );
  await nursingPage.recoverButton().click();
  await firstResponse;
  await expect(nursingPage.recoverButton()).not.toBeVisible();

  const firstPeriod = await api<PeriodResponse>(page, `/crate-api/nursing/v1/periods/?encounter_id=${encounter.id}`);
  expect(firstPeriod.records).toHaveLength(1);
  const retryResponse = page.waitForResponse(
    (response) => response.url().includes("/periods/elderly-admission") && response.status() === 200,
  );
  await api(page, "/crate-api/nursing/v1/periods/elderly-admission", {
    method: "POST",
    body: { encounter_id: encounter.id },
  });
  await retryResponse;
  const secondPeriod = await api<PeriodResponse>(page, `/crate-api/nursing/v1/periods/?encounter_id=${encounter.id}`);
  expect(secondPeriod.records).toHaveLength(1);
  expect(secondPeriod.records[0]?.id).toBe(firstPeriod.records[0]?.id);
});

test("无执行中任务时离院成功且入住从活动列表消失", async ({ page }) => {
  const admission = await createBoundAdmission(page, "DIS", "2026-07-31");
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();
  expect(await admissionsPage.discharge(admission.encounter.encounter_no)).toBe(200);
  await expect(admissionsPage.row(admission.encounter.encounter_no)).not.toBeVisible();
});

test("存在执行中任务时离院失败并展示可理解的错误", async ({ page }) => {
  const encounter = await createBusyAdmission(page, "INP");
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();
  expect(await admissionsPage.discharge(encounter.encounter_no)).toBe(409);
  await expect(admissionsPage.row(encounter.encounter_no)).toBeVisible();
  await expect(page.getByText(/执行中|in progress|cannot discharge/i)).toBeVisible();
});

test("窄屏下周期信息和恢复动作可操作且文本不重叠", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const encounter = await createUnboundAdmission(page, "MOB", "2026-07-01");
  const nursingPage = new NursingPage(page);
  await nursingPage.goto();
  await nursingPage.selectAdmission(encounter.encounter_no);
  await expect(nursingPage.recoverButton()).toBeVisible();
  await expect(nursingPage.recoverButton()).toBeEnabled();

  const summary = page.locator("[data-period-summary]");
  await nursingPage.recoverButton().click();
  await expect(summary).toBeVisible();
  const boxes = await summary.locator("p").evaluateAll((elements) =>
    elements.map((element) => {
      const rect = element.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom };
    }),
  );
  for (let index = 1; index < boxes.length; index += 1) {
    expect(boxes[index]?.top).toBeGreaterThanOrEqual(boxes[index - 1]?.bottom ?? 0);
  }
});
