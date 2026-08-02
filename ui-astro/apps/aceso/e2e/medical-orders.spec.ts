import { Pool } from "pg";
import { expect, test, type Page } from "@playwright/test";

const FIXTURE_PREFIX = "pw-mo-";
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

interface MedicalOrder {
  id: string;
  order_type: string;
  order_content: string;
  doctor: string;
  status: string;
  task_id: string | null;
  end_time: string | null;
  execution_summary?: Record<string, number>;
}

let databasePool: Pool;

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso medical order tests`);
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

// ——— 用例 1：开立用药与诊疗医嘱，列表与详情一致 ———

test("选择活动入住并开立用药医嘱后列表详情与结构化输入一致", async ({ page }) => {
  const admission = await createActiveAdmission(page, "CREATE");
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  // 打开开立弹窗
  await page.getByRole("button", { name: "开立医嘱" }).click();
  const modal = modalByTitle(page, "开立医嘱");
  await expect(modal).toBeVisible();

  // 用药医嘱
  await modal.locator("#order-content").fill("阿莫西林 0.5g 每日两次");
  await modal.getByLabel("医生（必填）").fill("赵医生");
  await modal.getByLabel("开始时间（必填）").fill("2026-08-01T10:00");
  await modal.getByLabel("药名（必填）").fill("阿莫西林");
  await modal.getByLabel("剂量").fill("0.5g");
  await modal.getByLabel("单位").fill("片/次");
  await modal.getByLabel("途径").fill("口服");
  await modal.locator("#order-frequency").selectOption("QD");
  await modal.getByLabel("时长（天）").fill("2");
  await modal.getByRole("button", { name: "保存医嘱" }).click();
  await page.waitForLoadState("networkidle");

  // 列表出现该医嘱
  const row = page.getByRole("row").filter({ hasText: "阿莫西林 0.5g 每日两次" });
  await expect(row).toBeVisible();
  await expect(row).toContainText("用药");
  await expect(row).toContainText("赵医生");
  await expect(row).toContainText("进行中");

  // 详情与结构化明细一致
  await row.getByRole("button", { name: "详情" }).click();
  const detail = modalByTitle(page, "医嘱详情");
  await expect(detail).toBeVisible();
  await expect(detail).toContainText("医嘱正文：阿莫西林 0.5g 每日两次");
  await expect(detail).toContainText("医生：赵医生");
  await expect(detail).toContainText("药名：阿莫西林");
  await expect(detail).toContainText("剂量：0.5g");
  await expect(detail).toContainText("途径：口服");
  await expect(detail).toContainText("频次编码：QD");
  await expect(detail).toContainText("天数：2");
  await detail.getByRole("button", { name: "关闭" }).click();
});

test("开立诊疗医嘱后列表筛选与刷新保持一致", async ({ page }) => {
  const admission = await createActiveAdmission(page, "THERAPY");
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  // 诊疗医嘱
  await page.getByRole("button", { name: "开立医嘱" }).click();
  const modal = modalByTitle(page, "开立医嘱");
  await modal.locator("#order-type").selectOption("THERAPY");
  await modal.locator("#order-content").fill("康复理疗 30 分钟");
  await modal.getByLabel("医生（必填）").fill("钱医生");
  await modal.getByLabel("开始时间（必填）").fill("2026-08-02T09:00");
  await modal.getByLabel("诊疗项目（必填）").fill("康复理疗");
  await modal.getByRole("button", { name: "保存医嘱" }).click();
  await page.waitForLoadState("networkidle");

  const row = page.getByRole("row").filter({ hasText: "康复理疗 30 分钟" });
  await expect(row).toBeVisible();
  await expect(row).toContainText("诊疗");

  // 类型筛选
  await page.locator("#order-type-filter").selectOption("MEDICATION");
  await expect(page.getByRole("row").filter({ hasText: "康复理疗 30 分钟" })).not.toBeVisible();
  await page.locator("#order-type-filter").selectOption("THERAPY");
  await expect(row).toBeVisible();

  // 刷新后仍在
  await page.getByRole("button", { name: "刷新" }).click();
  await page.waitForLoadState("networkidle");
  await expect(row).toBeVisible();

  // API 核对服务端终态
  const orders = await api<{ records: MedicalOrder[] }>(
    page,
    `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders?order_type=THERAPY`,
  );
  expect(orders.records).toHaveLength(1);
  expect(orders.records[0].order_content).toBe("康复理疗 30 分钟");
  expect(orders.records[0].task_id).toBeTruthy();
});

// ——— 用例 2：停嘱/作废/完成 + 重复点击不重发 ———

test("停嘱后显示服务端终态且重复点击不重发请求", async ({ page }) => {
  const admission = await createActiveAdmission(page, "STATUS");
  const created = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "MEDICATION",
      order_content: "降压药 1 片",
      doctor: "周医生",
      start_time: "2026-08-03T08:00:00+08:00",
      order_details: { drug_name: "氨氯地平" },
    },
  });
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  const row = page.getByRole("row").filter({ hasText: "降压药 1 片" });
  await row.getByRole("button", { name: "详情" }).click();
  const detail = modalByTitle(page, "医嘱详情");
  await expect(detail).toBeVisible();

  // 计数停嘱请求
  let patchCount = 0;
  page.on("request", (request) => {
    if (request.method() === "PATCH" && request.url().includes("/status")) patchCount += 1;
  });
  await detail.getByRole("button", { name: "停嘱" }).dblclick();
  await expect.poll(() => patchCount).toBe(1);
  await page.waitForLoadState("networkidle");

  // 服务端终态
  await expect(detail).toContainText("已停嘱");
  const after = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/orders/${created.id}`);
  expect(after.status).toBe("DISCONTINUED");
  expect(after.end_time).toBeTruthy();
  // 停嘱后不再显示状态操作按钮
  await expect(detail.getByRole("button", { name: "作废" })).not.toBeVisible();
  await detail.getByRole("button", { name: "关闭" }).click();
});

test("作废与完成显示服务端终态", async ({ page }) => {
  const admission = await createActiveAdmission(page, "TERMINAL");
  const cancelOrder = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "THERAPY",
      order_content: "理疗作废用例",
      doctor: "孙医生",
      start_time: "2026-08-03T09:00:00+08:00",
      order_details: { treatment_item: "热敷" },
    },
  });
  const completeOrder = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "EXAMINATION",
      order_content: "胸片检查",
      doctor: "李医生",
      start_time: "2026-08-03T10:00:00+08:00",
      order_details: { item_name: "胸部X线" },
    },
  });
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  // 作废
  let row = page.getByRole("row").filter({ hasText: "理疗作废用例" });
  await row.getByRole("button", { name: "详情" }).click();
  let detail = modalByTitle(page, "医嘱详情");
  await detail.getByRole("button", { name: "作废" }).click();
  await page.waitForLoadState("networkidle");
  await expect(detail).toContainText("已作废");
  await detail.getByRole("button", { name: "关闭" }).click();

  // 完成
  row = page.getByRole("row").filter({ hasText: "胸片检查" });
  await row.getByRole("button", { name: "详情" }).click();
  detail = modalByTitle(page, "医嘱详情");
  await detail.getByRole("button", { name: "完成" }).click();
  await page.waitForLoadState("networkidle");
  await expect(detail).toContainText("已完成");
  await detail.getByRole("button", { name: "关闭" }).click();

  const cancelled = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/orders/${cancelOrder.id}`);
  expect(cancelled.status).toBe("CANCELLED");
  const completed = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/orders/${completeOrder.id}`);
  expect(completed.status).toBe("COMPLETED");
});

// ——— 用例 3：错误响应可见且表单输入保留 ———

test("服务端校验错误可见且开立表单输入保留", async ({ page }) => {
  const admission = await createActiveAdmission(page, "ERR");
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  await page.getByRole("button", { name: "开立医嘱" }).click();
  const modal = modalByTitle(page, "开立医嘱");
  // 缺正文/医生/开始时间直接保存 → 前端校验错误
  await modal.getByRole("button", { name: "保存医嘱" }).click();
  await expect(modal.getByRole("alert")).toContainText("医嘱正文、医生和开始时间不能为空");
  // 输入保留
  await modal.locator("#order-content").fill("会被保留的医嘱正文");
  await modal.getByLabel("医生（必填）").fill("保留医生");
  await modal.getByLabel("开始时间（必填）").fill("2026-08-03T10:00");

  // 非法医嘱类型 → 服务端 400，错误可见且表单保留
  const responsePromise = page.waitForResponse(
    (response) => response.url().includes("/orders") && response.request().method() === "POST",
  );
  await modal.getByRole("button", { name: "保存医嘱" }).click();
  const response = await responsePromise;
  expect(response.status()).toBeGreaterThanOrEqual(400);
  await expect(modal.getByRole("alert")).toBeVisible();
  await expect(modal.locator("#order-content")).toHaveValue("会被保留的医嘱正文");
  await expect(modal.getByLabel("医生（必填）")).toHaveValue("保留医生");
});

// ——— 用例 4：执行汇总只读且为 0 ———

test("详情执行汇总只读展示且无执行入口", async ({ page }) => {
  const admission = await createActiveAdmission(page, "SUMMARY");
  const order = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "MEDICATION",
      order_content: "汇总测试医嘱",
      doctor: "吴医生",
      start_time: "2026-08-04T08:00:00+08:00",
      order_details: { drug_name: "维生素C" },
    },
  });
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  const row = page.getByRole("row").filter({ hasText: "汇总测试医嘱" });
  await row.getByRole("button", { name: "详情" }).click();
  const detail = modalByTitle(page, "医嘱详情");
  await expect(detail).toContainText("执行汇总");
  await expect(detail.getByText("待执行")).toBeVisible();
  await expect(detail.getByText("执行中")).toBeVisible();
  await expect(detail.getByText("已完成")).toBeVisible();
  await expect(detail.getByText("已跳过")).toBeVisible();
  await expect(detail.getByText("已取消")).toBeVisible();
  // 无执行/库存/护理记录入口
  await expect(detail.getByRole("button", { name: /执行|库存|护理记录|编辑|删除/ })).not.toBeVisible();

  const detailData = await api<MedicalOrder>(page, `/crate-api/healthcare/v1/orders/${order.id}`);
  const summary = detailData.execution_summary ?? { PENDING: 0, IN_PROGRESS: 0, COMPLETED: 0, SKIPPED: 0, CANCELLED: 0 };
  expect(summary.PENDING + summary.IN_PROGRESS + summary.COMPLETED + summary.SKIPPED + summary.CANCELLED).toBe(0);
  await detail.getByRole("button", { name: "关闭" }).click();
});

// ——— 用例 5：窄屏可操作且不重叠 ———

test("窄屏下列表筛选开立与详情均可操作且文字不重叠", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const admission = await createActiveAdmission(page, "MOBILE");
  await api<MedicalOrder>(page, `/crate-api/healthcare/v1/encounters/${admission.encounter.id}/orders`, {
    method: "POST",
    body: {
      order_type: "MEDICATION",
      order_content: "窄屏用药医嘱",
      doctor: "郑医生",
      start_time: "2026-08-05T08:00:00+08:00",
      order_details: { drug_name: "布洛芬" },
    },
  });
  await page.goto(`/dashboard/orders?encounter_id=${admission.encounter.id}`);
  await page.waitForLoadState("networkidle");

  await expect(page.getByRole("row").filter({ hasText: "窄屏用药医嘱" })).toBeVisible();
  await expect(page.locator("#order-type-filter")).toBeEnabled();
  await expect(page.getByRole("button", { name: "刷新" })).toBeEnabled();

  await page.getByRole("button", { name: "开立医嘱" }).click();
  const modal = modalByTitle(page, "开立医嘱");
  await modal.locator("#order-content").fill("窄屏新增医嘱");
  await modal.getByLabel("医生（必填）").fill("郑医生");
  await modal.getByLabel("开始时间（必填）").fill("2026-08-05T10:00");
  await modal.getByLabel("药名（必填）").fill("对乙酰氨基酚");
  await modal.getByRole("button", { name: "保存医嘱" }).click();
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("row").filter({ hasText: "窄屏新增医嘱" })).toBeVisible();

  // 详情弹窗可操作
  await page.getByRole("row").filter({ hasText: "窄屏用药医嘱" }).getByRole("button", { name: "详情" }).click();
  const detail = modalByTitle(page, "医嘱详情");
  await expect(detail).toBeVisible();
  await expect(detail.getByRole("button", { name: "关闭" })).toBeEnabled();
  // 正文不重叠：详情弹窗内同层文本元素边界不相交
  const overlaps = await detail.locator("p, section > h4").evaluateAll((elements) => {
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
});
