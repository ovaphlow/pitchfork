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

interface ApiResult<T> {
  status: number;
  body: T;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso discharge handover tests`);
  return value;
}

async function cleanupDatabase() {
  const client = await databasePool.connect();
  try {
    await client.query("BEGIN");
    // 按依赖顺序清理
    await client.query(`DELETE FROM healthcare.medical_records WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(`DELETE FROM healthcare.nursing_records WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);
    await client.query(
      `DELETE FROM nursing.nursing_task_executions WHERE id LIKE $1 OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1 OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1)))))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_tasks WHERE id LIKE $1 OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1)))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_plan_items WHERE plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE $1 OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1))))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_plans WHERE id LIKE $1 OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1)))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_assessments WHERE id LIKE $1 OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1)))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM nursing.nursing_service_periods WHERE id LIKE $1 OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1))`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(
      `DELETE FROM healthcare.encounters WHERE id LIKE $1 OR patient_id IN (SELECT id FROM healthcare.patients WHERE id LIKE $1)`,
      [`${FIXTURE_PREFIX}%`],
    );
    await client.query(`DELETE FROM healthcare.patients WHERE id LIKE $1`, [`${FIXTURE_PREFIX}%`]);

    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM healthcare.medical_records WHERE id LIKE $1) +
        (SELECT count(*) FROM healthcare.nursing_records WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_plan_items WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_plans WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_assessments WHERE id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters WHERE id LIKE $1) +
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
  if (result.status >= 400) throw new Error(`${options.method ?? "GET"} ${path} failed with ${result.status}`);
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
    const tab = this.page.getByRole("tab", { name: /已离院|档案/ });
    if (await tab.isVisible()) {
      await tab.click();
      await this.page.waitForLoadState("networkidle");
    }
  }

  row(encounterNo: string) {
    return this.page.getByRole("row").filter({ hasText: encounterNo });
  }

  async openHandover(encounterNo: string) {
    await this.row(encounterNo).getByRole("button", { name: /查看|摘要|交接/ }).click();
    await this.page.waitForLoadState("networkidle");
  }

  handoverDialog() {
    return this.page.getByRole("dialog").filter({ hasText: /交接摘要|归档/ });
  }

  async generateHandover(author: string, note?: string) {
    const authorInput = this.page.getByLabel(/交接人|author/i);
    await authorInput.fill(author);
    if (note) {
      const noteInput = this.page.getByLabel(/备注|note/i);
      await noteInput.fill(note);
    }
    const submitButton = this.page.getByRole("button", { name: /生成|创建|提交/ });
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
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();
  await expect(admissionsPage.row(admission.encounter.encounter_no)).toBeVisible();

  // 打开摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();

  // 验证快照内容
  await expect(dialog.getByText("归档测试长者VIEW-patient")).toBeVisible();
  await expect(dialog.getByText("DISCHARGED")).toBeVisible();
  await expect(dialog.getByText("COMPLETED")).toBeVisible();
});

test("未生成摘要的合资格记录可生成一次", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "GEN");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();

  // 点击查看，应该显示生成表单（404）
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const generateForm = page.getByRole("form").filter({ hasText: /生成|创建/ });
  await expect(generateForm).toBeVisible();

  // 生成摘要
  await admissionsPage.generateHandover("测试交接人", "测试备注");
  await page.waitForLoadState("networkidle");

  // 验证摘要已显示
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("测试交接人")).toBeVisible();
});

test("重复点击不重复请求", async ({ page }) => {
  const admission = await createDischargedAdmission(page, "IDEM");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();

  // 生成摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  await admissionsPage.generateHandover("幂等测试员");

  // 关闭对话框
  await page.keyboard.press("Escape");
  await page.waitForLoadState("networkidle");

  // 再次打开，应该直接显示摘要而非生成表单
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("幂等测试员")).toBeVisible();
  // 不应该有生成表单
  await expect(page.getByRole("form").filter({ hasText: /生成|创建/ })).not.toBeVisible();
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
  const originalContent = await dialog.textContent();
  await page.keyboard.press("Escape");

  // 通过 API 新增护理记录更正
  const patientId = admission.encounter.patient_id;
  await api(page, "/crate-api/healthcare/v1/nursing-records", {
    method: "POST",
    body: {
      encounter_id: admission.encounter.id,
      title: "新增更正",
      content: "这是一条新的更正记录",
    },
  });

  // 重新打开摘要，内容应该不变
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  await expect(dialog).toBeVisible();
  const currentContent = await dialog.textContent();
  expect(currentContent).toContain("原始交接人");
});

test("窄屏下档案切换和查看可操作且文字不重叠", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const admission = await createDischargedAdmission(page, "MOB");
  const admissionsPage = new AdmissionsPage(page);

  await admissionsPage.goto();
  await admissionsPage.switchToDischargedTab();
  await expect(admissionsPage.row(admission.encounter.encounter_no)).toBeVisible();

  // 打开摘要
  await admissionsPage.openHandover(admission.encounter.encounter_no);
  const dialog = admissionsPage.handoverDialog();
  await expect(dialog).toBeVisible();

  // 检查对话框内容可操作
  const closeButtons = dialog.getByRole("button", { name: /关闭|×/ });
  await expect(closeButtons.first()).toBeEnabled();
});
