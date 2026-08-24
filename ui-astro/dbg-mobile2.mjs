import { chromium, devices } from "@playwright/test";
const BASE = process.env.PLAYWRIGHT_BASE_URL;
const b = await chromium.launch();
const ctx = await b.newContext({ ...devices["Pixel 5"] });
const page = await ctx.newPage();
await page.goto(BASE + "/login", { waitUntil: "networkidle" });
if (page.url().includes("/login")) {
  await page.getByLabel("账号").fill(process.env.PLAYWRIGHT_USERNAME);
  await page.getByLabel("密码").fill(process.env.PLAYWRIGHT_PASSWORD);
  await Promise.all([page.waitForURL(/\/dashboard/), page.getByRole("button", { name: "登录" }).click()]);
}
await page.goto(BASE + "/dashboard/pharmacy", { waitUntil: "networkidle" });
await page.getByRole("button", { name: "护理站申领" }).click();
await page.getByRole("button", { name: "新建申领" }).click();
const modal = page.getByRole("heading", { name: "新建护理站申领" }).locator("../..");
await modal.waitFor();
await modal.locator("#req-warehouse").selectOption({ index: 1 });
await page.waitForTimeout(500);
await modal.locator("#req-destination").selectOption({ index: 2 });
await modal.getByLabel("申领科室").fill("dbg-移动端");
await modal.getByRole("button", { name: "+ 添加物资" }).click();
const sels = modal.locator("select");
await sels.nth(2).selectOption({ index: 1 });
await modal.locator('input[type="number"]').nth(0).fill("2");
await sels.nth(3).selectOption({ index: 2 });
await modal.locator('input[type="number"]').nth(1).fill("1");
const btn = modal.getByRole("button", { name: "提交申领" });
await btn.scrollIntoViewIfNeeded();
await page.waitForTimeout(500);
const info = await btn.evaluate((el) => {
  const r = el.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  const top = document.elementFromPoint(cx, cy);
  const chain = []; let e = top;
  while (e && chain.length < 8) { chain.push(e.tagName + "." + ((e.className?.toString() || "").split(" ")[0] || "")); e = e.parentElement; }
  const scroll = el.closest("div[style*='overflow']") || el.closest("[style*='overflow-y']");
  return {
    rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
    center: { cx: Math.round(cx), cy: Math.round(cy) },
    viewport: { w: window.innerWidth, h: window.innerHeight },
    topEl: top ? top.tagName + " " + (top.className?.toString().slice(0, 50) || "") : null,
    sameNode: top === el,
    chain,
  };
});
console.log(JSON.stringify(info, null, 2));
try { await btn.click({ timeout: 5000 }); console.log("CLICK OK"); } catch (e) { console.log("CLICK FAILED:", e.message.split("\n").slice(0,6).join(" | ")); }
await b.close();
