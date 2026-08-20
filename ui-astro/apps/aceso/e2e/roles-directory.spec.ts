import { expect, test, type Page } from "@playwright/test";

/**
 * 018 计划 — Nexus Shared 角色目录（roles）浏览器验收。
 *
 * 前置环境（隔离实例，均由测试启动并在结束后销毁）：
 *   - IDP 测试实例：127.0.0.1:8430（独立 SQLite，bootstrap admin 账号登录）
 *   - Nexus 测试实例：127.0.0.1:8423（独立 SQLite 测试库）
 *   - Aceso UI dev：127.0.0.1:4324（PUBLIC_IDENTITY_API_PORT=8430、
 *     PUBLIC_NEXUS_API_HOST=127.0.0.1、PUBLIC_NEXUS_API_PORT=8423）
 * 环境变量：PLAYWRIGHT_BASE_URL、PLAYWRIGHT_USERNAME、PLAYWRIGHT_PASSWORD、
 *   PLAYWRIGHT_NEXUS_API_BASE_URL。
 */

const BASE_URL = requiredEnvironment("PLAYWRIGHT_BASE_URL", process.env.PLAYWRIGHT_BASE_URL);
const LOGIN_IDENTIFIER = requiredEnvironment("PLAYWRIGHT_USERNAME", process.env.PLAYWRIGHT_USERNAME);
const LOGIN_PASSWORD = requiredEnvironment("PLAYWRIGHT_PASSWORD", process.env.PLAYWRIGHT_PASSWORD);
const NEXUS_API_BASE = requiredEnvironment(
  "PLAYWRIGHT_NEXUS_API_BASE_URL",
  process.env.PLAYWRIGHT_NEXUS_API_BASE_URL,
);

const FIXTURE = {
  prefix: "e2e018.",
  nursingCode: "e2e018.nursing.staff",
  nursingName: "护理人员E2E",
  nursingNameEdited: "护理人员E2E改",
  pharmacyCode: "e2e018.pharmacy.manager",
  pharmacyName: "药房管理员E2E",
  narrowCode: "e2e018.narrow.ui",
  narrowName: "窄屏角色",
  narrowNameEdited: "窄屏角色改",
};

// @pitchfork/ui 的 Modal 渲染为带 <h3> 标题的浮层（无 role="dialog"），按标题定位浮层容器
function modalByTitle(page: Page, title: string) {
  return page.getByRole("heading", { name: title }).locator("xpath=../..");
}

function requiredEnvironment(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} must be set for the Aceso roles directory tests`);
  return value;
}

async function login(page: Page) {
  // networkidle 等待登录守卫与 401 重定向全部稳定后再判断 URL，避免竞态
  await page.goto("/dashboard/roles", { waitUntil: "networkidle" });
  if (page.url().includes("/login")) {
    await page.getByLabel("账号").fill(LOGIN_IDENTIFIER);
    await page.getByLabel("密码").fill(LOGIN_PASSWORD);
    // AuthCard 登录成功固定跳转 /dashboard
    await Promise.all([
      page.waitForURL(/\/dashboard\/?/),
      page.getByRole("button", { name: "登录" }).click(),
    ]);
    await page.goto("/dashboard/roles", { waitUntil: "domcontentloaded" });
  }
}

/** 行按 role_code 升序排列，行内包含显示名称 */
function roleRow(page: Page, roleCode: string) {
  return page.locator("tbody tr").filter({ hasText: roleCode });
}

/** 清理历史运行残留的 e2e018 前缀角色，保证空态断言与创建流程可重现 */
async function cleanupFixtureRoles(page: Page) {
  await page.evaluate(async ({ apiBase, prefix }) => {
    const list = await fetch(`${apiBase}/roles`, { credentials: "include" });
    if (!list.ok) return;
    const roles: { id: string; role_code: string }[] = await list.json();
    for (const role of roles) {
      if (role.role_code.startsWith(prefix)) {
        await fetch(`${apiBase}/roles/${encodeURIComponent(role.id)}`, {
          method: "DELETE",
          credentials: "include",
        });
      }
    }
  }, { apiBase: NEXUS_API_BASE, prefix: FIXTURE.prefix });
}

/**
 * Pixel 5 设备模拟（projects mobile-chrome）在 headless 下 CSS 视口与命中坐标不一致
 * （实际渲染 507×937 vs 配置 393×851），Playwright 点击命中会被误判拦截，与产品渲染无关。
 * 相关用例仅在桌面 chromium 执行，窄屏布局由显式 375×760 视口用例覆盖。
 */
function skipBrokenMobileEmulation(testInfo: { project: { name: string } }): void {
  test.skip(
    testInfo.project.name === "mobile-chrome",
    "Pixel 5 设备模拟在 headless 下视口与命中坐标不一致，窄屏验收由显式 375×760 视口用例覆盖",
  );
}

test.describe.configure({ mode: "serial" });

test("未认证访问：Nexus API 返回 401，角色页重定向登录页", async ({ page, request }) => {
  const response = await request.get(`${NEXUS_API_BASE}/roles`);
  expect(response.status()).toBe(401);
  const problem = await response.json();
  expect(problem.type).toContain("not-authenticated");

  await page.goto("/dashboard/roles", { waitUntil: "domcontentloaded" });
  await expect(page).toHaveURL(/\/login/);
});

test("登录后角色列表为空态展示", async ({ page }) => {
  await login(page);
  await cleanupFixtureRoles(page);
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "角色管理" })).toBeVisible();
  await expect(page.getByText("管理共享角色目录与每个角色的权限码集合")).toBeVisible();
  await expect(page.getByText("暂无角色")).toBeVisible();
});

test.describe("角色目录 CRUD 全流程（桌面）", () => {
  test("创建角色：权限码去重、列表按 role_code 升序", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);
    await page.getByRole("button", { name: "添加角色" }).click();
    const createModal = modalByTitle(page, "添加角色");
    await createModal.getByLabel("角色编码").fill(FIXTURE.nursingCode);
    await createModal.getByLabel("显示名称").fill(FIXTURE.nursingName);
    await createModal.getByLabel("描述").fill("护理站日常护理与执行");
    // 重复项 "nursing:execute" 应被服务端去重
    await createModal.getByLabel("权限码").fill("nursing:execute, nursing:execute, nursing:record");
    await createModal.getByRole("button", { name: "保存" }).click();

    const nursingRow = roleRow(page, FIXTURE.nursingCode);
    await expect(nursingRow).toBeVisible();
    await expect(nursingRow.getByText(FIXTURE.nursingName)).toBeVisible();
    await expect(nursingRow.getByText("nursing:execute")).toBeVisible();
    await expect(nursingRow.getByText("nursing:record")).toBeVisible();
    // 权限码去重后只有两个 chip，不存在重复 chip
    await expect(nursingRow.getByText("nursing:execute", { exact: true })).toHaveCount(1);

    await page.getByRole("button", { name: "添加角色" }).click();
    const secondModal = modalByTitle(page, "添加角色");
    await secondModal.getByLabel("角色编码").fill(FIXTURE.pharmacyCode);
    await secondModal.getByLabel("显示名称").fill(FIXTURE.pharmacyName);
    await secondModal.getByRole("button", { name: "保存" }).click();
    await expect(roleRow(page, FIXTURE.pharmacyCode)).toBeVisible();

    // 列表按 role_code 升序：nursing.staff 在 pharmacy.manager 之前
    const codes = page.locator("tbody tr td:first-child");
    await expect(codes.nth(0)).toContainText(FIXTURE.nursingCode);
    await expect(codes.nth(1)).toContainText(FIXTURE.pharmacyCode);
  });

  test("重复角色编码创建返回 409 并保留表单", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);
    await page.getByRole("button", { name: "添加角色" }).click();
    const createModal = modalByTitle(page, "添加角色");
    await createModal.getByLabel("角色编码").fill(FIXTURE.nursingCode);
    await createModal.getByLabel("显示名称").fill("重复角色");
    await createModal.getByRole("button", { name: "保存" }).click();

    // 冲突错误展示在表单内（Problem Details detail: role_code already exists），弹窗保持打开
    await expect(createModal.getByText("already exists")).toBeVisible();
    await expect(createModal.getByLabel("角色编码")).toHaveValue(FIXTURE.nursingCode);
    // 错误文案不包含 SQL 或堆栈
    await expect(createModal.getByText(/UNIQUE|sqlite|stack/i)).toHaveCount(0);
    await createModal.getByRole("button", { name: "取消" }).click();
  });

  test("非法角色编码与空显示名称被拒绝且错误可见", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);

    await page.getByRole("button", { name: "添加角色" }).click();
    let createModal = modalByTitle(page, "添加角色");
    await createModal.getByLabel("角色编码").fill("E2E018.Invalid");
    await createModal.getByLabel("显示名称").fill("非法编码");
    await createModal.getByRole("button", { name: "保存" }).click();
    await expect(createModal.getByText("角色编码仅允许小写字母、数字和点")).toBeVisible();
    await createModal.getByRole("button", { name: "取消" }).click();

    await page.getByRole("button", { name: "添加角色" }).click();
    createModal = modalByTitle(page, "添加角色");
    await createModal.getByLabel("角色编码").fill("e2e018.blankname");
    await createModal.getByLabel("显示名称").fill("   ");
    await createModal.getByRole("button", { name: "保存" }).click();
    await expect(createModal.getByText("角色编码和显示名称不能为空")).toBeVisible();
    await createModal.getByRole("button", { name: "取消" }).click();
  });

  test("编辑角色：role_code 不可改，显示名称与权限码可更新", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);
    const row = roleRow(page, FIXTURE.nursingCode);
    await expect(row).toBeVisible();
    await row.getByRole("button", { name: "编辑" }).click();

    const editModal = modalByTitle(page, "编辑角色");
    await expect(editModal.getByLabel("角色编码")).toBeDisabled();
    await expect(editModal.getByText("角色编码创建后不可修改。")).toBeVisible();
    await editModal.getByLabel("显示名称").fill(FIXTURE.nursingNameEdited);
    await editModal.getByLabel("权限码").fill("nursing:record");
    await editModal.getByRole("button", { name: "保存" }).click();

    const editedRow = roleRow(page, FIXTURE.nursingCode);
    await expect(editedRow.getByText(FIXTURE.nursingNameEdited)).toBeVisible();
    await expect(editedRow.getByText("nursing:execute")).toHaveCount(0);
    await expect(editedRow.getByText("nursing:record")).toBeVisible();
  });

  test("删除角色：确认后从列表移除", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);
    const row = roleRow(page, FIXTURE.pharmacyCode);
    await expect(row).toBeVisible();
    await row.getByRole("button", { name: "删除" }).click();

    const confirmModal = modalByTitle(page, "确认删除角色");
    await expect(confirmModal.getByText(`将删除角色「${FIXTURE.pharmacyName}（${FIXTURE.pharmacyCode}）」`)).toBeVisible();
    await confirmModal.getByRole("button", { name: "确认删除" }).click();

    await expect(roleRow(page, FIXTURE.pharmacyCode)).toHaveCount(0);
    // 未删除的角色仍在
    await expect(roleRow(page, FIXTURE.nursingCode)).toBeVisible();
  });
});

test.describe("窄屏布局（显式 375×760 视口）", () => {
  test.use({ viewport: { width: 375, height: 760 } });

  test("窄屏下角色列表、创建/编辑/删除弹窗与错误展示可用", async ({ page }, testInfo) => {
    skipBrokenMobileEmulation(testInfo);
    await login(page);
    await cleanupFixtureRoles(page);
    await page.reload({ waitUntil: "networkidle" });
    await expect(page.getByRole("heading", { name: "角色管理" })).toBeVisible();

    // 创建：窄屏下弹窗内填写并保存，保存按钮未被遮挡
    await page.getByRole("button", { name: "添加角色" }).click();
    const createModal = modalByTitle(page, "添加角色");
    await createModal.getByLabel("角色编码").fill(FIXTURE.narrowCode);
    await createModal.getByLabel("显示名称").fill(FIXTURE.narrowName);
    await createModal.getByLabel("权限码").fill("nursing:execute");
    await createModal.getByRole("button", { name: "保存" }).click();
    await expect(roleRow(page, FIXTURE.narrowCode)).toBeVisible();

    // 错误展示：重复编码 409 文案可见，且不含 SQL/堆栈
    await page.getByRole("button", { name: "添加角色" }).click();
    const dupModal = modalByTitle(page, "添加角色");
    await dupModal.getByLabel("角色编码").fill(FIXTURE.narrowCode);
    await dupModal.getByLabel("显示名称").fill("窄屏重复");
    await dupModal.getByRole("button", { name: "保存" }).click();
    await expect(dupModal.getByText("already exists")).toBeVisible();
    await expect(dupModal.getByText(/UNIQUE|sqlite|stack/i)).toHaveCount(0);
    await dupModal.getByRole("button", { name: "取消" }).click();

    // 编辑
    await roleRow(page, FIXTURE.narrowCode).getByRole("button", { name: "编辑" }).click();
    const editModal = modalByTitle(page, "编辑角色");
    await editModal.getByLabel("显示名称").fill(FIXTURE.narrowNameEdited);
    await editModal.getByRole("button", { name: "保存" }).click();
    await expect(roleRow(page, FIXTURE.narrowCode).getByText(FIXTURE.narrowNameEdited)).toBeVisible();

    // 删除
    await roleRow(page, FIXTURE.narrowCode).getByRole("button", { name: "删除" }).click();
    await modalByTitle(page, "确认删除角色").getByRole("button", { name: "确认删除" }).click();
    await expect(roleRow(page, FIXTURE.narrowCode)).toHaveCount(0);
  });
});
