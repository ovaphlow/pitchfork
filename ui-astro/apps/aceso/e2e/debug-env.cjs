const { chromium } = require("@playwright/test");

const BASE = process.env.PLAYWRIGHT_BASE_URL || "";
const API = process.env.PLAYWRIGHT_API_BASE_URL || "";
const USER = process.env.PLAYWRIGHT_USERNAME || "";
const PASS = process.env.PLAYWRIGHT_PASSWORD || "";
const EXEC = process.env.PLAYWRIGHT_EXECUTABLE_PATH || undefined;

(async () => {
  const browser = await chromium.launch({ executablePath: EXEC });
  const page = await browser.newPage();
  const logs = [];
  page.on("console", (msg) => logs.push(`[console.${msg.type()}] ${msg.text()}`));
  page.on("pageerror", (err) => logs.push(`[pageerror] ${err.message}`));
  page.on("requestfailed", (req) => logs.push(`[reqfailed] ${req.method()} ${req.url()} ${req.failure() ? req.failure().errorText : ""}`));
  page.on("response", (resp) => {
    if (resp.url().includes("/crate-api/")) {
      logs.push(`[response] ${resp.status()} ${resp.request().method()} ${resp.url()}`);
    }
  });

  await page.goto(`${BASE}/dashboard/admission`, { waitUntil: "networkidle" });
  console.log("URL after goto:", page.url());
  if (page.url().includes("/login")) {
    await page.getByLabel("账号").fill(USER);
    await page.getByLabel("密码").fill(PASS);
    await Promise.all([page.waitForURL(/\/dashboard/), page.getByRole("button", { name: "登录" }).click()]);
  }
  console.log("URL after login:", page.url());

  const token = await page.evaluate(() => localStorage.getItem("token") || localStorage.getItem("access_token") || "");
  console.log("token present:", token.length > 0);

  const patientRes = await page.evaluate(async (api) => {
    const t = localStorage.getItem("token") || "";
    const r = await fetch(`${api}/crate-api/healthcare/v1/patients`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(t ? { Authorization: `Bearer ${t}` } : {}) },
      body: JSON.stringify({ name: "pw-debug-患者" }),
    });
    return { status: r.status, body: await r.text() };
  }, API);
  console.log("createPatient:", patientRes.status, patientRes.body.slice(0, 200));
  let patientId = "";
  try { patientId = JSON.parse(patientRes.body).id; } catch (e) {}
  if (!patientId) { console.log("cannot continue without patient"); await browser.close(); return; }

  const admRes = await page.evaluate(async ({ api, patientId }) => {
    const t = localStorage.getItem("token") || "";
    const r = await fetch(`${api}/crate-api/healthcare/v1/elderly-admissions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(t ? { Authorization: `Bearer ${t}` } : {}) },
      body: JSON.stringify({ patient_id: patientId, encounter_no: "pw-debug-1", admit_date: "2026-08-01T00:00:00+08:00" }),
    });
    return { status: r.status, body: await r.text() };
  }, { api: API, patientId });
  console.log("createAdmission:", admRes.status, admRes.body.slice(0, 300));

  await page.goto(`${BASE}/dashboard/admission`, { waitUntil: "networkidle" });
  console.log("=== ADMISSION PAGE after create ===");
  console.log("body:", (await page.evaluate(() => document.body.innerText)).slice(0, 400));
  console.log("rows:", await page.locator("tbody tr, table tr").count());

  await page.goto(`${BASE}/dashboard/orders`, { waitUntil: "networkidle" });
  console.log("=== ORDERS PAGE ===");
  console.log("body:", (await page.evaluate(() => document.body.innerText)).slice(0, 400));
  console.log("开立医嘱 buttons:", await page.getByRole("button", { name: "开立医嘱" }).count());

  console.log("=== LOGS ===");
  console.log(logs.join("\n"));
  await browser.close();
})().catch((e) => {
  console.error("FATAL:", e);
  process.exit(1);
});
