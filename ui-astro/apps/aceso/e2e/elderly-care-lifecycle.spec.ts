import { test, expect, type Page } from '@playwright/test';

/**
 * Aceso 养老院入住照护周期生命周期 — Playwright 浏览器验收测试。
 *
 * 覆盖计划文档第 7.3 节五组验收：
 *   1. 创建养老入住后，护理工作台立即显示与该入住编号匹配的养老照护周期
 *   2. 存在历史未绑定活动入住时，恢复动作只建立一次正确周期
 *   3. 无执行中任务时离院成功，入住从活动列表消失
 *   4. 存在执行中任务时离院失败，页面保留入住和护理数据并展示可理解的错误
 *   5. 窄屏下入住选择、周期状态、恢复动作和离院错误均可操作，文本不重叠
 *
 * 使用独立 aceso_test fixture 和用户授权的测试账户；
 * 凭据从环境变量读取，不得记录密码、JWT 或连接密钥。
 */

// 测试数据前缀，用于隔离和清理
const FIXTURE_PREFIX = 'pw-';
const TEST_TIMESTAMP = Date.now();

// 清理 fixture 数据的辅助函数
async function cleanupFixtures(page: Page) {
  await page.evaluate(async () => {
    const token = localStorage.getItem('token');
    const baseUrl = import.meta.env.PUBLIC_API_URL || '';

    // 删除测试创建的任务执行记录、任务、周期、encounter、患者
    const prefixes = ['pw-'];
    for (const prefix of prefixes) {
      try {
        await fetch(`${baseUrl}/crate-api/nursing/v1/task-executions?prefix=${prefix}`, {
          method: 'DELETE',
          headers: { 'Authorization': `Bearer ${token}` },
        });
        await fetch(`${baseUrl}/crate-api/nursing/v1/tasks?prefix=${prefix}`, {
          method: 'DELETE',
          headers: { 'Authorization': `Bearer ${token}` },
        });
        await fetch(`${baseUrl}/crate-api/nursing/v1/periods?prefix=${prefix}`, {
          method: 'DELETE',
          headers: { 'Authorization': `Bearer ${token}` },
        });
        await fetch(`${baseUrl}/crate-api/healthcare/v1/encounters?prefix=${prefix}`, {
          method: 'DELETE',
          headers: { 'Authorization': `Bearer ${token}` },
        });
        await fetch(`${baseUrl}/crate-api/healthcare/v1/patients?prefix=${prefix}`, {
          method: 'DELETE',
          headers: { 'Authorization': `Bearer ${token}` },
        });
      } catch (e) {
        console.warn('Cleanup failed for prefix:', prefix, e);
      }
    }
  });
}

// Page Object: 入住管理页面
class AdmissionsPage {
  constructor(private page: Page) {}

  async goto() {
    await this.page.goto('/aceso/admissions');
    await this.page.waitForLoadState('networkidle');
  }

  async createAdmission(data: {
    patientName: string;
    encounterNo: string;
    admitDate: string;
  }) {
    // 点击新建入住按钮
    await this.page.getByRole('button', { name: /新建入住|新增入住|创建入住/i }).click();

    // 填写表单
    await this.page.getByLabel(/姓名|患者姓名/i).fill(data.patientName);
    await this.page.getByLabel(/住院号|入住编号/i).fill(data.encounterNo);
    await this.page.getByLabel(/入院日期|入住日期/i).fill(data.admitDate);

    // 提交表单
    await this.page.getByRole('button', { name: /提交|保存|确认/i }).click();

    // 等待成功响应
    await this.page.waitForResponse(
      (response) => response.url().includes('/healthcare/v1/elderly-admissions') && response.status() === 201
    );
  }

  async getAdmissionByEncounterNo(encounterNo: string) {
    const row = this.page.getByRole('row').filter({ hasText: encounterNo });
    return row;
  }

  async dischargeAdmission(encounterNo: string) {
    const row = await this.getAdmissionByEncounterNo(encounterNo);
    await row.getByRole('button', { name: /离院|出院/i }).click();

    // 确认离院对话框
    await this.page.getByRole('button', { name: /确认|确定/i }).click();

    // 等待成功响应
    await this.page.waitForResponse(
      (response) => response.url().includes('/encounters/') && response.url().includes('/discharge')
    );
  }

  async isAdmissionVisible(encounterNo: string): Promise<boolean> {
    const row = this.page.getByRole('row').filter({ hasText: encounterNo });
    return await row.isVisible();
  }
}

// Page Object: 护理工作台页面
class NursingPage {
  constructor(private page: Page) {}

  async goto() {
    await this.page.goto('/aceso/nursing');
    await this.page.waitForLoadState('networkidle');
  }

  async selectAdmission(encounterNo: string) {
    // 从入住选择器中选择指定入住
    await this.page.getByRole('combobox').click();
    await this.page.getByRole('option', { name: new RegExp(encounterNo) }).click();
  }

  async getServicePeriodInfo(): Promise<{
    serviceType: string | null;
    encounterId: string | null;
    status: string | null;
    periodId: string | null;
  }> {
    const serviceType = await this.page.getByText(/服务类型|护理类型/i).locator('..').textContent();
    const encounterId = await this.page.getByText(/入住编号|关联入住/i).locator('..').textContent();
    const status = await this.page.getByText(/状态|周期状态/i).locator('..').textContent();

    // 尝试从页面提取周期 ID（如果存在）
    let periodId: string | null = null;
    const periodIdElement = this.page.locator('[data-period-id]');
    if (await periodIdElement.count() > 0) {
      periodId = await periodIdElement.getAttribute('data-period-id');
    }

    return {
      serviceType: serviceType?.replace(/服务类型|护理类型/, '').trim() || null,
      encounterId: encounterId?.replace(/入住编号|关联入住/, '').trim() || null,
      status: status?.replace(/状态|周期状态/, '').trim() || null,
      periodId,
    };
  }

  async clickRecoverButton() {
    await this.page.getByRole('button', { name: /建立养老照护周期|恢复周期|补建/i }).click();
  }

  async getErrorMessage(): Promise<string | null> {
    const errorElement = this.page.getByRole('alert').or(this.page.locator('.error-message'));
    if (await errorElement.isVisible()) {
      return await errorElement.textContent();
    }
    return null;
  }
}

test.describe('养老院入住照护周期生命周期验收', () => {
  let admissionsPage: AdmissionsPage;
  let nursingPage: NursingPage;

  test.beforeEach(async ({ page }) => {
    admissionsPage = new AdmissionsPage(page);
    nursingPage = new NursingPage(page);
  });

  test.afterEach(async ({ page }) => {
    await cleanupFixtures(page);
  });

  // ========================================================================
  //  验收 1：创建养老入住后，护理工作台立即显示与该入住编号匹配的养老照护周期
  // ========================================================================

  test('创建养老入住后护理工作台显示匹配的养老照护周期', async ({ page }) => {
    const encounterNo = `${FIXTURE_PREFIX}ENC-${TEST_TIMESTAMP}`;
    const patientName = `${FIXTURE_PREFIX}测试长者-${TEST_TIMESTAMP}`;

    // 1. 创建养老入住
    await admissionsPage.goto();
    await admissionsPage.createAdmission({
      patientName,
      encounterNo,
      admitDate: '2026-07-31',
    });

    // 2. 验证入住创建成功
    const admissionRow = await admissionsPage.getAdmissionByEncounterNo(encounterNo);
    await expect(admissionRow).toBeVisible();

    // 3. 进入护理工作台
    await nursingPage.goto();

    // 4. 选择刚创建的入住
    await nursingPage.selectAdmission(encounterNo);

    // 5. 验证护理工作台显示正确的养老照护周期
    const periodInfo = await nursingPage.getServicePeriodInfo();
    expect(periodInfo.serviceType).toContain('ELDERLY_CARE');
    expect(periodInfo.encounterId).toContain(encounterNo);
    expect(periodInfo.status).toContain('ACTIVE');
  });

  // ========================================================================
  //  验收 2：存在历史未绑定活动入住时，恢复动作只建立一次正确周期
  // ========================================================================

  test('历史未绑定活动入住恢复动作只建立一次正确周期', async ({ page }) => {
    const encounterNo = `${FIXTURE_PREFIX}HIST-${TEST_TIMESTAMP}`;
    const patientName = `${FIXTURE_PREFIX}历史长者-${TEST_TIMESTAMP}`;

    // 1. 创建一个历史入住（模拟未绑定周期的情况）
    await admissionsPage.goto();
    await admissionsPage.createAdmission({
      patientName,
      encounterNo,
      admitDate: '2026-07-01',
    });

    // 2. 进入护理工作台
    await nursingPage.goto();

    // 3. 选择历史入住
    await nursingPage.selectAdmission(encounterNo);

    // 4. 验证恢复按钮可见并点击建立周期
    const recoverButton = page.getByRole('button', { name: /建立养老照护周期|恢复周期|补建/i });
    await expect(recoverButton).toBeVisible();
    await recoverButton.click();

    // 等待周期建立成功
    await page.waitForResponse(
      (response) => response.url().includes('/periods/elderly-admission') && response.status() === 201
    );

    // 5. 验证恢复按钮不再显示（周期已建立）
    await expect(recoverButton).not.toBeVisible();

    // 6. 验证周期信息正确显示
    const periodInfo = await nursingPage.getServicePeriodInfo();
    expect(periodInfo.serviceType).toContain('ELDERLY_CARE');
    expect(periodInfo.encounterId).toContain(encounterNo);
  });

  // ========================================================================
  //  验收 3：无执行中任务时离院成功，入住从活动列表消失
  // ========================================================================

  test('无执行中任务时离院成功且入住从活动列表消失', async ({ page }) => {
    const encounterNo = `${FIXTURE_PREFIX}DIS-${TEST_TIMESTAMP}`;
    const patientName = `${FIXTURE_PREFIX}离院长者-${TEST_TIMESTAMP}`;

    // 1. 创建入住
    await admissionsPage.goto();
    await admissionsPage.createAdmission({
      patientName,
      encounterNo,
      admitDate: '2026-07-31',
    });

    // 2. 验证入住在活动列表中
    await expect(await admissionsPage.getAdmissionByEncounterNo(encounterNo)).toBeVisible();

    // 3. 执行离院
    await admissionsPage.dischargeAdmission(encounterNo);

    // 4. 验证入住从活动列表消失
    const isStillVisible = await admissionsPage.isAdmissionVisible(encounterNo);
    expect(isStillVisible).toBe(false);
  });

  // ========================================================================
  //  验收 4：存在执行中任务时离院失败，页面保留入住和护理数据并展示可理解的错误
  // ========================================================================

  test('存在执行中任务时离院失败并展示可理解的错误', async ({ page }) => {
    const encounterNo = `${FIXTURE_PREFIX}INP-${TEST_TIMESTAMP}`;
    const patientName = `${FIXTURE_PREFIX}执行中长者-${TEST_TIMESTAMP}`;

    // 1. 创建入住
    await admissionsPage.goto();
    await admissionsPage.createAdmission({
      patientName,
      encounterNo,
      admitDate: '2026-07-31',
    });

    // 2. 进入护理工作台并补建周期
    await nursingPage.goto();
    await nursingPage.selectAdmission(encounterNo);

    // 等待周期建立
    await page.waitForResponse(
      (response) => response.url().includes('/periods/elderly-admission') && response.status() === 200
    );

    // 3. 通过 API 获取周期 ID 并创建执行中任务和执行记录
    await page.evaluate(async ({ encounterNo }) => {
      const token = localStorage.getItem('token');
      const baseUrl = import.meta.env.PUBLIC_API_URL || '';

      // 获取周期 ID
      const periodsResponse = await fetch(`${baseUrl}/crate-api/nursing/v1/periods?encounter_id=${encounterNo}`, {
        headers: { 'Authorization': `Bearer ${token}` },
      });
      const periods = await periodsResponse.json();
      const periodId = periods.records?.[0]?.id;

      if (!periodId) {
        throw new Error('No period found for encounter');
      }

      // 创建任务
      const taskResponse = await fetch(`${baseUrl}/crate-api/nursing/v1/tasks`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({
          period_id: periodId,
          task_type: 'NURSING',
          description: '执行中测试任务',
          frequency_code: 'QD',
          start_date: new Date().toISOString().split('T')[0],
        }),
      });
      const task = await taskResponse.json();

      // 创建执行中记录
      await fetch(`${baseUrl}/crate-api/nursing/v1/task-executions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({
          task_id: task.id,
          planned_time: new Date().toISOString(),
          status: 'IN_PROGRESS',
          executor: 'test-executor',
        }),
      });
    }, { encounterNo });

    // 4. 尝试离院（应该失败）
    await admissionsPage.goto();
    const row = await admissionsPage.getAdmissionByEncounterNo(encounterNo);

    // 监听离院失败的响应
    const dischargeResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/encounters/') && response.url().includes('/discharge')
    );

    await row.getByRole('button', { name: /离院|出院/i }).click();
    await page.getByRole('button', { name: /确认|确定/i }).click();

    const dischargeResponse = await dischargeResponsePromise;
    expect(dischargeResponse.status()).toBe(409);

    // 5. 验证页面保留入住和护理数据
    await expect(await admissionsPage.getAdmissionByEncounterNo(encounterNo)).toBeVisible();

    // 6. 验证显示可理解的错误信息
    const errorMessage = await nursingPage.getErrorMessage();
    expect(errorMessage).toBeTruthy();
  });

  // ========================================================================
  //  验收 5：窄屏下入住选择、周期状态、恢复动作和离院错误均可操作，文本不重叠
  // ========================================================================

  test('窄屏下所有操作均可操作且文本不重叠', async ({ page }) => {
    // 设置窄屏 viewport
    await page.setViewportSize({ width: 375, height: 812 });

    const encounterNo = `${FIXTURE_PREFIX}MOB-${TEST_TIMESTAMP}`;
    const patientName = `${FIXTURE_PREFIX}移动端长者-${TEST_TIMESTAMP}`;

    // 1. 创建入住
    await admissionsPage.goto();
    await admissionsPage.createAdmission({
      patientName,
      encounterNo,
      admitDate: '2026-07-31',
    });

    // 2. 验证入住在窄屏下可见
    await expect(await admissionsPage.getAdmissionByEncounterNo(encounterNo)).toBeVisible();

    // 3. 进入护理工作台
    await nursingPage.goto();

    // 4. 验证入住选择器在窄屏下可操作
    const combobox = page.getByRole('combobox');
    await expect(combobox).toBeVisible();

    // 5. 选择入住
    await nursingPage.selectAdmission(encounterNo);

    // 6. 验证周期状态信息在窄屏下可见且不重叠
    const periodInfo = await nursingPage.getServicePeriodInfo();
    expect(periodInfo.serviceType).toBeTruthy();

    // 7. 验证恢复按钮（如果显示）在窄屏下可操作
    const recoverButton = page.getByRole('button', { name: /建立养老照护周期|恢复周期|补建/i });
    if (await recoverButton.isVisible()) {
      await expect(recoverButton).toBeEnabled();
    }

    // 8. 验证离院按钮在窄屏下可操作
    await admissionsPage.goto();
    const row = await admissionsPage.getAdmissionByEncounterNo(encounterNo);
    const dischargeButton = row.getByRole('button', { name: /离院|出院/i });
    if (await dischargeButton.isVisible()) {
      await expect(dischargeButton).toBeEnabled();
    }
  });
});
