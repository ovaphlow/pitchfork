import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

const FIXTURE_PREFIX = "pw-ed-";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL;
const LOGIN_IDENTIFIER = process.env.PLAYWRIGHT_USERNAME;
const LOGIN_PASSWORD = process.env.PLAYWRIGHT_PASSWORD;

// @pitchfork/ui 的 Modal 渲染为带 <h3> 标题的浮层（无 role="dialog"），按标题定位浮层容器
function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

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

interface Patient {
  id: string;
  status: string;
}

interface MedicalOrder {
  id: string;
  status: string;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso elderly death tests`);
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
         AND (schedule.id LIKE $1 OR period.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM nursing.nursing_task_execution_consumptions consumption
       USING nursing.nursing_task_executions execution
       WHERE consumption.task_execution_id = execution.id
         AND (consumption.id LIKE $1 OR execution.id LIKE $1)`,
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
      `DELETE FROM healthcare.medical_orders order_row
       WHERE order_row.id LIKE $1 OR order_row.encounter_id LIKE $1
          OR order_row.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM healthcare.medical_records record
       WHERE record.id LIKE $1 OR record.encounter_id LIKE $1
          OR record.encounter_id IN (SELECT id FROM healthcare.encounters WHERE encounter_no LIKE $1)`,
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
    await client.query(
      `DELETE FROM public.stock_operation_details detail
       USING public.stock_operations operation
       WHERE detail.operation_id = operation.id AND detail.id LIKE $1`,
      [fixturePattern],
    );
    await client.query(`DELETE FROM public.stock_operations WHERE id LIKE $1`, [fixturePattern]);
    await client.query(
      `DELETE FROM public.stocks WHERE id LIKE $1 OR material_id LIKE $1`,
      [fixturePattern],
    );
    await client.query(
      `DELETE FROM public.lots WHERE id LIKE $1 OR material_id LIKE $1`,
      [fixturePattern],
    );
    await client.query(`DELETE FROM public.materials WHERE id LIKE $1`, [fixturePattern]);

    const result = await client.query<{ residual: string }>(
      `SELECT (
        (SELECT count(*) FROM healthcare.patients WHERE id LIKE $1 OR name LIKE $1) +
        (SELECT count(*) FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM healthcare.medical_orders order_row WHERE order_row.id LIKE $1 OR order_row.encounter_id IN (SELECT encounter.id FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.encounter_no LIKE $1 OR patient.name LIKE $1)) +
        (SELECT count(*) FROM healthcare.medical_records record WHERE record.id LIKE $1 OR record.encounter_id IN (SELECT encounter.id FROM healthcare.encounters encounter LEFT JOIN healthcare.patients patient ON patient.id = encounter.patient_id WHERE encounter.encounter_no LIKE $1 OR patient.name LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_service_periods period LEFT JOIN healthcare.encounters encounter ON encounter.id = period.encounter_id LEFT JOIN healthcare.patients patient ON patient.id = period.patient_id WHERE period.id LIKE $1 OR period.encounter_id LIKE $1 OR encounter.encounter_no LIKE $1 OR patient.name LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_assessments assessment WHERE assessment.id LIKE $1 OR assessment.encounter_id LIKE $1 OR assessment.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plans plan WHERE plan.id LIKE $1 OR plan.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_plan_items item WHERE item.id LIKE $1 OR item.plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_tasks task WHERE task.id LIKE $1 OR task.encounter_id LIKE $1 OR task.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_task_executions execution WHERE execution.id LIKE $1 OR execution.task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE $1)) +
        (SELECT count(*) FROM nursing.nursing_task_execution_consumptions consumption WHERE consumption.id LIKE $1) +
        (SELECT count(*) FROM nursing.nursing_visit_schedules schedule WHERE schedule.id LIKE $1 OR schedule.period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE $1)) +
        (SELECT count(*) FROM public.materials WHERE id LIKE $1) +
        (SELECT count(*) FROM public.lots WHERE id LIKE $1) +
        (SELECT count(*) FROM public.stocks WHERE id LIKE $1) +
        (SELECT count(*) FROM public.stock_operations WHERE id LIKE $1) +
        (SELECT count(*) FROM public.stock_operation_details WHERE id LIKE $1)
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

async function createActiveAdmission(page: Page, suffix: string, admitDate = "2026-08-01"): Promise<AdmissionResponse> {
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

/** 创建含进行中执行的入住（通过既有 nursing API 构造阻断路径） */
async function createBusyAdmission(page: Page, suffix: string): Promise<Encounter> {
  const patientId = await createPatient(page, `${suffix}-busy-patient`);
  const encounter = await api<Encounter>(page, "/crate-api/healthcare/v1/encounters", {
    method: "POST",
    body: {
      patient_id: patientId,
      encounter_type: "ELDERLY_CARE",
      encounter_no: `${FIXTURE_PREFIX}${suffix}-busy`,
      admit_date: "2026-08-01T00:00:00+08:00",
    },
  });
  const period = await api<{ id: string }>(page, "/crate-api/nursing/v1/periods/elderly-admission", {
    method: "POST",
    body: { encounter_id: encounter.id },
  });
  const task = await api<{ id: string }>(page, "/crate-api/nursing/v1/tasks/", {
    method: "POST",
    body: {
      period_id: period.id,
      encounter_id: encounter.id,
      task_type: "NURSING",
      description: "执行中测试任务",
      frequency_code: "QD",
      start_date: "2026-08-01",
    },
  });
  const execution = await api<{ id: string }>(page, "/crate-api/nursing/v1/executions/", {
    method: "POST",
    body: {
      task_id: task.id,
      planned_time: "2026-08-02T09:00:00+08:00",
      executor: "test-executor",
    },
  });
  await api(page, `/crate-api/nursing/v1/executions/${execution.id}/status`, {
    method: "PATCH",
    body: { status: "IN_PROGRESS" },
  });
  return encounter;
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

class AdmissionsPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto("/dashboard/admission");
    await this.page.waitForLoadState("networkidle");
  }

  row(encounterNo: string) {
    return this.page.getByRole("row").filter({ hasText: encounterNo });
  }

  async openDeath(encounterNo: string) {
    await this.row(encounterNo).getByRole("button", { name: "办理去世" }).click();
  }

  deathDialog() {
    return modalByTitle(this.page, "办理去世");
  }
}

// ——— 用例 1：办理去世成功，活动清单刷新 ———

test("办理去世成功后活动清单刷新且服务端终局正确", async ({ page }) => {
  const admission = await createActiveAdmission(page, "DEATH");
  // 一条医嘱随去世收束
  await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "MEDICATION",
      order_content: "去世收束医嘱",
      doctor: "赵医生",
      start_time: "2026-08-01T10:00:00+08:00",
      order_details: { drug_name: "阿莫西林" },
    },
  });
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();

  const row = admissionsPage.row(`${FIXTURE_PREFIX}DEATH`);
  await expect(row).toBeVisible();
  // 活动入住有医嘱入口
  await expect(row.getByRole("link", { name: "医嘱" })).toBeVisible();

  await admissionsPage.openDeath(`${FIXTURE_PREFIX}DEATH`);
  const dialog = admissionsPage.deathDialog();
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("去世时间（必填）").fill("2026-08-05T14:00");
  await dialog.locator("#death-cause").fill("心力衰竭");
  await dialog.getByRole("button", { name: "确认办理去世" }).click();
  await page.waitForLoadState("networkidle");

  // 活动清单刷新：该行消失
  await expect(row).not.toBeVisible();

  // 服务端终局
  const encounter = await api<Encounter>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}`);
  expect(encounter.status).toBe("DECEASED");
  const orders = await api<{ records: MedicalOrder[] }>(
    page,
    `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`,
  );
  expect(orders.records[0].status).toBe("DISCONTINUED");
  const patient = await api<Patient>(page, `/crate-api/healthcare/v1/patients/${admission.encounter.patient_id}`);
  expect(patient.status).toBe("DECEASED");
});

// ——— 用例 2：进行中执行 409，错误可见且表单保留 ———

test("进行中执行阻断去世显示409且保留表单输入", async ({ page }) => {
  const busy = await createBusyAdmission(page, "BUSY");
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();

  const row = admissionsPage.row(`${FIXTURE_PREFIX}BUSY-busy`);
  await expect(row).toBeVisible();

  await admissionsPage.openDeath(`${FIXTURE_PREFIX}BUSY-busy`);
  const dialog = admissionsPage.deathDialog();
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("去世时间（必填）").fill("2026-08-05T14:00");
  await dialog.locator("#death-cause").fill("保留原因");
  await dialog.getByRole("button", { name: "确认办理去世" }).click();
  await page.waitForLoadState("networkidle");

  // 409 错误可见
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(dialog.getByRole("alert")).toContainText(/in progress|进行中|执行/i);
  // 表单输入保留
  await expect(dialog.getByLabel("去世时间（必填）")).toHaveValue("2026-08-05T14:00");
  await expect(dialog.locator("#death-cause")).toHaveValue("保留原因");
  // 关闭后服务端无半收束
  await dialog.getByRole("button", { name: "取消" }).click();
  const encounter = await api<Encounter>(page, `/crate-api/healthcare/v1/encounters/${busy.id}`);
  expect(encounter.status).toBe("ACTIVE");
});

// ——— 用例 3：缺去世时间的本地校验错误不丢输入 ———

test("缺去世时间本地校验显示错误且不丢输入", async ({ page }) => {
  const admission = await createActiveAdmission(page, "VALIDATE");
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();

  await admissionsPage.openDeath(`${FIXTURE_PREFIX}VALIDATE`);
  const dialog = admissionsPage.deathDialog();
  await expect(dialog).toBeVisible();
  await dialog.locator("#death-cause").fill("未填时间的输入");
  await dialog.getByRole("button", { name: "确认办理去世" }).click();
  await page.waitForLoadState("networkidle");

  // 本地校验错误（前端必填拦截，不重发请求）
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(dialog.locator("#death-cause")).toHaveValue("未填时间的输入");
  // 服务端未发生去世
  const encounter = await api<Encounter>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}`);
  expect(encounter.status).toBe("ACTIVE");
  await dialog.getByRole("button", { name: "取消" }).click();
});

// ——— 用例 4：已离院/已去世记录无开嘱入口 ———

test("已离院与已去世入住没有医嘱入口", async ({ page }) => {
  // 已离院：创建后直接离院
  const discharged = await createActiveAdmission(page, "DISC", "2026-07-01");
  await api(page, `/crate-api/healthcare/v1/encounters/${discharged.encounter.id}/discharge`, {
    method: "PATCH",
    body: { discharge_date: "2026-07-31T00:00:00+08:00" },
  });
  // 已去世：创建后直接办理去世
  const deceased = await createActiveAdmission(page, "DEC");
  await api(page, `/crate-api/healthcare/v1/encounters/${deceased.encounter.id}/death`, {
    method: "PATCH",
    body: { death_date: "2026-08-05T14:00:00+08:00" },
  });

  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();
  // 活动清单中两者均不存在
  await expect(admissionsPage.row(`${FIXTURE_PREFIX}DISC`)).not.toBeVisible();
  await expect(admissionsPage.row(`${FIXTURE_PREFIX}DEC`)).not.toBeVisible();

  // 直接访问医嘱页选择活动入住列表，不应包含已终局入住
  await page.goto("/dashboard/orders");
  await page.waitForLoadState("networkidle");
  const select = page.locator("#orders-encounter");
  const options = await select.locator("option").allTextContents();
  expect(options.join(" ")).not.toContain(`${FIXTURE_PREFIX}DISC`);
  expect(options.join(" ")).not.toContain(`${FIXTURE_PREFIX}DEC`);
});

// ——— 用例 5：窄屏去世弹窗可操作且不重叠 ———

test("窄屏下办理去世弹窗可操作且文字不重叠", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const admission = await createActiveAdmission(page, "MOBILEDEATH");
  const admissionsPage = new AdmissionsPage(page);
  await admissionsPage.goto();

  const row = admissionsPage.row(`${FIXTURE_PREFIX}MOBILEDEATH`);
  await expect(row).toBeVisible();
  await admissionsPage.openDeath(`${FIXTURE_PREFIX}MOBILEDEATH`);
  const dialog = admissionsPage.deathDialog();
  await expect(dialog).toBeVisible();

  await dialog.getByLabel("去世时间（必填）").fill("2026-08-05T14:00");
  await dialog.locator("#death-cause").fill("窄屏去世原因");
  await expect(dialog.getByRole("button", { name: "确认办理去世" })).toBeEnabled();

  // 弹窗内同层元素边界不相交
  const overlaps = await dialog.locator("p, label, textarea, input, button").evaluateAll((elements) => {
    const layoutElements = elements
      .filter((element) => !elements.some((other) => other !== element && element.contains(other)))
      .map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          tag: element.tagName.toLowerCase(),
          name: element.getAttribute("aria-label") ?? element.id ?? element.textContent?.trim() ?? "",
          left: rect.left,
          top: rect.top,
          right: rect.right,
          bottom: rect.bottom,
        };
      });
    return layoutElements.flatMap((first, firstIndex) =>
      layoutElements.slice(firstIndex + 1).flatMap((second) => {
        const horizontalOverlap = Math.min(first.right, second.right) - Math.max(first.left, second.left);
        const verticalOverlap = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top);
        return horizontalOverlap > 1 && verticalOverlap > 1 ? [{ first, second }] : [];
      }),
    );
  });
  expect(overlaps).toEqual([]);

  // 确认后成功关闭
  await dialog.getByRole("button", { name: "确认办理去世" }).click();
  await page.waitForLoadState("networkidle");
  const encounter = await api<Encounter>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}`);
  expect(encounter.status).toBe("DECEASED");
});
