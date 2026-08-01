import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

const FIXTURE_PREFIX = "pw-dha-";
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

interface HandoverResponse {
  id: string;
  record_type: string;
  title: string;
  encounter_id: string;
  period_id: string;
  record_date: string;
  author: string;
  handover_note: string | null;
  snapshot_version: number;
  snapshot: {
    patient: { id: string; name: string };
    encounter: { id: string; status: string };
    care_period: { id: string; status: string };
    assessments: unknown[];
    plans: unknown[];
    tasks: unknown[];
    execution_summary: Record<string, number>;
    nursing_records: unknown[];
  };
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso discharge handover tests`);
  return value;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  const fixturePattern = `${FIXTURE_PREFIX}%`;
  try {
    await client.query("BEGIN");
    await client.query(
      `DELETE FROM nursing.nursing_visit_schedules schedule
       USING nursing.nursing_service_periods period
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE schedule.period_id = period.id
         AND (period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
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
    await client.query(
      `DELETE FROM nursing.nursing_plans plan
       USING nursing.nursing_service_periods period
       LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id
       LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id
       WHERE plan.period_id = period.id
         AND (plan.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
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
    await client.query(
      `DELETE FROM nursing.nursing_service_periods period
       WHERE period.id LIKE $1 OR period.encounter_id LIKE $1
          OR period.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)
          OR period.patient_id IN (SELECT id FROM healthcare.patients WHERE name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.medical_records record
       USING healthcare.encounters encounter
       JOIN healthcare.patients patient ON patient.id = encounter.patient_id
       WHERE record.encounter_id = encounter.id
         AND (record.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.encounters encounter
       USING healthcare.patients patient
       WHERE encounter.patient_id = patient.id
         AND (encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.patients
       WHERE id LIKE $1 OR name LIKE $1`,
      [fixturePattern],
    );

    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM healthcare.medical_records record WHERE record.id LIKE $1 OR record.encounter_id IN (SELECT encounter.id FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.encounter_no LIKE $1 OR patient.name LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_service_periods period LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id WHERE period.id LIKE $1 OR period.encounter_id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_assessments assessment WHERE assessment.id LIKE $1 OR assessment.encounter_id LIKE $1 OR assessment.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plans plan WHERE plan.id LIKE $1 OR plan.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plan_items item WHERE item.id LIKE $1 OR item.plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_tasks task WHERE task.id LIKE $1 OR task.encounter_id LIKE $1 OR task.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_task_executions execution WHERE execution.id LIKE $1 OR execution.task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_visit_schedules schedule WHERE schedule.id LIKE $1 OR schedule.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1))
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

async function createPatient(page: Page, suffix: string): Promise<string> {
  const patient = await api<{ id: string }>(page, "/crate-api/healthcare/v1/patients", {
    method: "POST",
    body: { name: `${FIXTURE_PREFIX}${suffix}` },
  });
  return patient.id;
}

async function createDischargedAdmission(page: Page, suffix: string): Promise<AdmissionResponse> {
  const patientId = await createPatient(page, `${suffix}-patient`);
  const admission = await api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_no: `${FIXTURE_PREFIX}${suffix}`,
      admit_date: "2026-07-01T00:00:00+08:00",
    },
  });
  // 离院
  await api(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/discharge`, {
    method: "PATCH",
    body: { discharge_date: "2026-07-31T00:00:00+08:00" },
  });
  return admission;
}

class AdmissionsPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/admission");
    await this.page.waitForLoadState("networkidle");
  }

  async switchToDischargedTab() {
    await this.page.getByRole("button", { name: "已离院档案" }).click();
    await this.page.waitForLoadState("networkidle");
  }

  row(encounterNo: string) {
    return this.page.getByRole("row").filter({ hasText: encounterNo });
  }

  async openHandover(encounterNo: string) {
    await this.row(encounterNo).getByRole("button", { name: /查看|摘要|交接/ }).click();
    await this.page.waitForLoadState("networkidle");
  }

  handoverDialog() {
    return this.page.locator("h3").filter({ hasText: "养老照护离院交接摘要" }).locator("..").locator("..");
  }

  async generateHandover(author: string, note?: string) {
    const authorInput = this.page.getByLabel(/交接人|author/i);
    await authorInput.fill(author);
    if (note) {
      const noteInput = this.page.getByLabel(/备注|note/i);
      await noteInput.fill(note);
    }
    const submitButton = this.page.getByRole("button", { name: "生成交接摘要", exact: true });
    await submitButton.click();
    await this.page.waitForLoadState("networkidle");
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

test("已离院档案中可定位正确入住并查看只读摘要", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "VIEW");
  await api(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`, {
    method: "POST",
    body: { author: "已归档交接人", handover_note: "已归档备注" },
  });
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();
  await expect(admissionsPage.row(admission.encounter.encounter_no)).toBeVisible();

  // 打开摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();

  // 验证快照内容
  await expect(dialog.getByText("姓名：pw-dha-VIEW-patient", { exact: true })).toBeVisible();
  await expect(dialog.getByText("服务类型：ELDERLY_CARE", { exact: true })).toBeVisible();
  await expect(dialog.getByText("周期起止：2026-07-01 至 2026-07-31", { exact: true })).toBeVisible();
});

test("未生成摘要的合资格记录可生成一次", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "GEN");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();

  // 点击查看，应该显示生成表单（404）
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const generateForm = page.locator('form[aria-label="生成交接摘要"]');
  await expect(generateForm).toBeVisible();

  // 生成摘要
  await admissionsPage.generateHandover("测试交接人", "测试备注");
  await page.waitForLoadState("networkidle");

  // 验证摘要已显示
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();
  const generated = await api<HandoverResponse>(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`);
  expect(generated.author).toBe("测试交接人");
  expect(generated.handover_note).toBe("测试备注");
});

test("重复点击不重复请求", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "IDEM");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();

  // 生成摘要并验证提交期间只发送一次请求
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const form = page.locator('form[aria-label="生成交接摘要"]');
  await form.getByLabel("交接人（必填）").fill("幂等测试员");
  let postCount = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().includes("/discharge-handover")) postCount += 1;
  });
  const submitButton = form.getByRole("button", { name: "生成交接摘要" });
  await submitButton.dblclick();
  await expect.poll(() => postCount).toBe(1);

  // 关闭对话框
  await admissionsPage.handoverDialog().getByRole("button", { name: "关闭" }).click();

  // 再次打开，应该直接显示摘要而非生成表单
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();
  const existing = await api<HandoverResponse>(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`);
  expect(existing.author).toBe("幂等测试员");
  // 不应该有生成表单
  await expect(page.locator('form[aria-label="生成交接摘要"]')).not.toBeVisible();
});

test("活动入住没有生成入口", async ({ page }) => {
  const patientId = await createPatient(page, "ACT-patient");
  const admission = await api<AdmissionResponse>(page, "/crate-api/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_no: `${FIXTURE_PREFIX}ACT`,
      admit_date: "2026-08-01T00:00:00+08:00",
    },
  });

  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();
  // 活动入住列表中不应该有生成交接摘要的按钮
  const row = admissionsPage.row(admission.encounter.encounter_no);
  await expect(row).toBeVisible();
  await expect(row.getByRole("button", { name: /生成|交接摘要/ })).not.toBeVisible();
});

test("归档后新增护理记录更正不影响已归档快照", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "CORR");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();

  // 生成摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  await admissionsPage.generateHandover("原始交接人");
  const dialog = admissionsPage.handoverDialog();
  const original = await api<HandoverResponse>(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`);
  await dialog.getByRole("button", { name: "关闭" }).click();

  // 通过 API 新增护理记录更正
  const record = await api<{ id: string }>(page, "/crate-api/healthcare/v1/nursing-records", {
    method: "POST",
    body: {
      encounter_id: admission.encounter.id,
      period_id: admission.nursing_period.id,
      title: "新增更正",
      content: "这是一条新的更正记录",
    },
  });
  await api(page, `/crate-api/healthcare/v1/nursing-records/${record.id}/corrections`, {
    method: "POST",
    body: { content: "这是一条新的更正内容", record_time: "2026-07-31T12:00:00+08:00" },
  });

  // 重新打开摘要，内容应该不变
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  await expect(dialog).toBeVisible();
  const current = await api<HandoverResponse>(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`);
  expect(current.id).toBe(original.id);
  expect(current.author).toBe("原始交接人");
  expect(current.snapshot).toEqual(original.snapshot);
  await expect(dialog).toContainText("归档后源记录更正不会自动同步此摘要");
});

test("窄屏下档案切换和查看可操作且文字不重叠", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const admission = await createDischargedAdmission(page, "MOB");
  await api(page, `/crate-api/healthcare/v1/elderly-admissions/${admission.encounter.id}/discharge-handover`, {
    method: "POST",
    body: { author: "窄屏交接人" },
  });
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();
  await expect(admissionsPage.row(admission.encounter.encounter_no)).toBeVisible();

  // 打开摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();

  // 检查对话框内容可操作
  await expect(dialog.getByRole("button", { name: "关闭" })).toBeEnabled();
  const sectionBounds = await dialog.locator("section").evaluateAll((sections) =>
    sections.map((section) => {
      const rect = section.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom };
    }),
  );
  for (let index = 1; index < sectionBounds.length; index += 1) {
    expect(sectionBounds[index].top).toBeGreaterThanOrEqual(sectionBounds[index - 1].bottom - 1);
  }
});
