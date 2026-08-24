# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: pharmacy-requisition.spec.ts >> 013 主线：UI 新建双物资申领 → 审批预留 → 确认调拨后双仓库存与双向明细关联
- Location: apps/aceso/e2e/pharmacy-requisition.spec.ts:568:1

# Error details

```
TimeoutError: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for getByRole('heading', { name: '新建护理站申领' }).locator('../..').getByRole('button', { name: '提交申领' })
    - locator resolved to <button class="inline-flex items-center justify-center rounded-md font-medium transition-all duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:opacity-50 disabled:pointer-events-none cursor-pointer border-none h-9 px-4 text-sm gap-2 bg-transparent text-accent border border-accent hover:bg-transparent hover:text-accent active:bg-transparent active:text-accent ">提交申领</button>
  - attempting click action
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <div class="space-y-4">…</div> intercepts pointer events
  - retrying click action
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <div class="space-y-2">…</div> intercepts pointer events
  - retrying click action
    - waiting 20ms
    2 × waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
    - retrying click action
      - waiting 100ms
    4 × waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
    - retrying click action
      - waiting 500ms
      - waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <div class="space-y-2">…</div> intercepts pointer events
    - retrying click action
      - waiting 500ms
      - waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
    - retrying click action
      - waiting 500ms
      - waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
    - retrying click action
      - waiting 500ms
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
  - retrying click action
    - waiting 500ms
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <div class="space-y-2">…</div> intercepts pointer events
  - retrying click action
    - waiting 500ms
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <select class="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">…</select> from <div class="space-y-2">…</div> subtree intercepts pointer events
  - retrying click action
    - waiting 500ms

```

# Test source

```ts
  324 |     return [created.code, await ensureTwoWarehouses(page).then(([, dest]) => dest)];
  325 |   }
  326 |   const createdDest = await nexusCreateWarehouse(page, {
  327 |     code: "WH-E2E-WARD",
  328 |     name: "E2E护理站仓",
  329 |     description: "013 e2e fixture 护理站目标仓库",
  330 |   });
  331 |   return [source, createdDest.code];
  332 | }
  333 | 
  334 | /** 创建启用了批次管控的物资、批次和源仓库可用库存（含一次手工入库）。 */
  335 | async function createInventoryFixtureItem(
  336 |   page: Page,
  337 |   suffix: string,
  338 |   index: number,
  339 |   warehouse: string,
  340 | ): Promise<InventoryFixtureItem> {
  341 |   const marker = `${FIXTURE_PREFIX}${suffix}-${index}`;
  342 |   const material = await api<InventoryMaterial>(page, "/crate-api/inventories/v1/materials", {
  343 |     method: "POST",
  344 |     body: {
  345 |       code: `${marker}-MAT`,
  346 |       name: `${FIXTURE_PREFIX}${suffix} 药品${index}`,
  347 |       category: "药品",
  348 |       base_unit: "盒",
  349 |       quantity_scale: 0,
  350 |       package_unit: "盒",
  351 |       package_size: "1",
  352 |       enable_batch_control: true,
  353 |       cost_method: "FIFO",
  354 |       status: "ACTIVE",
  355 |     },
  356 |   });
  357 |   const lot = await api<InventoryLot>(page, "/crate-api/inventories/v1/lots", {
  358 |     method: "POST",
  359 |     body: {
  360 |       material_id: material.id,
  361 |       batch_no: `${marker}-BATCH`,
  362 |       production_date: "2026-01-01",
  363 |       expiry_date: "2027-12-31",
  364 |       manufacturer: "测试药厂",
  365 |     },
  366 |   });
  367 |   await api<unknown>(page, "/crate-api/inventories/v1/operations/inbound", {
  368 |     method: "POST",
  369 |     body: {
  370 |       warehouse,
  371 |       items: [
  372 |         {
  373 |           material_id: material.id,
  374 |           lot_id: lot.id,
  375 |           quantity: "10",
  376 |           unit_cost: "2.50",
  377 |         },
  378 |       ],
  379 |       note: "013 browser e2e fixture",
  380 |     },
  381 |   });
  382 |   return { materialId: material.id, lotId: lot.id, materialName: material.name };
  383 | }
  384 | 
  385 | /** 选择下拉框中第一个非空选项（通用辅助）。 */
  386 | async function selectFirstOption(page: Page, selectLocator: Locator) {
  387 |   await expect(selectLocator.locator("option")).not.toHaveCount(1, { timeout: 10_000 });
  388 |   const value = await selectLocator.locator("option").nth(1).getAttribute("value");
  389 |   if (!value) throw new Error("expected a non-empty select option");
  390 |   await selectLocator.selectOption(value);
  391 | }
  392 | 
  393 | async function selectOptionIfPresent(page: Page, selectLocator: Locator, value: string) {
  394 |   await expect(selectLocator.locator(`option[value="${value}"]`)).toHaveCount(1, { timeout: 10_000 });
  395 |   await selectLocator.selectOption(value);
  396 | }
  397 | 
  398 | /** UI：护理站申领 Tab 中创建申领单（DRAFT）。 */
  399 | async function createRequisitionViaUi(
  400 |   page: Page,
  401 |   department: string,
  402 |   sourceWarehouse: string,
  403 |   destinationWarehouse: string,
  404 |   items: Array<{ materialId: string; quantity: string }>,
  405 | ): Promise<void> {
  406 |   await page.goto("/dashboard/pharmacy", { waitUntil: "networkidle" });
  407 |   await page.getByRole("button", { name: "护理站申领" }).click();
  408 |   await page.getByRole("button", { name: "新建申领" }).click();
  409 |   const modal = modalByTitle(page, "新建护理站申领");
  410 |   await expect(modal).toBeVisible();
  411 |   await modal.locator("#req-warehouse").selectOption(sourceWarehouse);
  412 |   await modal.locator("#req-destination").selectOption(destinationWarehouse);
  413 |   await modal.getByLabel("申领科室").fill(department);
  414 | 
  415 |   for (let index = 0; index < items.length; index += 1) {
  416 |     if (index > 0) {
  417 |       await modal.getByRole("button", { name: "+ 添加物资" }).click();
  418 |     }
  419 |     const materialSelect = modal.locator("select").nth(2 + index);
  420 |     await selectOptionIfPresent(page, materialSelect, items[index].materialId);
  421 |     await modal.locator('input[type="number"]').nth(index).fill(items[index].quantity);
  422 |   }
  423 | 
> 424 |   await modal.getByRole("button", { name: "提交申领" }).click();
      |                                                     ^ TimeoutError: locator.click: Timeout 10000ms exceeded.
  425 |   await expect(modal).not.toBeVisible({ timeout: 10_000 });
  426 |   const row = page.getByRole("row").filter({ hasText: department });
  427 |   await expect(row).toBeVisible({ timeout: 10_000 });
  428 | }
  429 | 
  430 | /** UI：审批弹窗中逐项填写批准数量并选择批次。 */
  431 | async function approveRequisitionViaUi(
  432 |   page: Page,
  433 |   department: string,
  434 |   approvals: Array<{ approvedQuantity: string; lotId: string }>,
  435 | ): Promise<void> {
  436 |   const row = page.getByRole("row").filter({ hasText: department });
  437 |   await expect(row).toContainText("待审核");
  438 |   await row.getByRole("button", { name: "审批" }).click();
  439 |   const modal = modalByTitle(page, "审批申领（预留库存）");
  440 |   await expect(modal).toBeVisible();
  441 |   const tableRows = modal.locator("tbody tr");
  442 |   await expect(tableRows).toHaveCount(approvals.length);
  443 |   for (let index = 0; index < approvals.length; index += 1) {
  444 |     const itemRow = tableRows.nth(index);
  445 |     await itemRow.locator('input[type="number"]').fill(approvals[index].approvedQuantity);
  446 |     await selectOptionIfPresent(page, itemRow.locator("select"), approvals[index].lotId);
  447 |   }
  448 |   await modal.getByRole("button", { name: "确认审批并预留" }).click();
  449 |   await expect(modal).not.toBeVisible({ timeout: 10_000 });
  450 |   await expect(row).toContainText("待调拨");
  451 | }
  452 | 
  453 | /** UI：确认调拨（APPROVED → DISPENSED）。 */
  454 | async function dispenseRequisitionViaUi(page: Page, department: string): Promise<void> {
  455 |   const row = page.getByRole("row").filter({ hasText: department });
  456 |   await expect(row).toContainText("待调拨");
  457 |   await row.getByRole("button", { name: "确认调拨" }).click();
  458 |   const modal = modalByTitle(page, "确认调拨");
  459 |   await expect(modal).toBeVisible();
  460 |   await modal.getByRole("button", { name: "确认调拨" }).click();
  461 |   await expect(modal).not.toBeVisible({ timeout: 10_000 });
  462 |   await expect(row).toContainText("已完成");
  463 | }
  464 | 
  465 | /** UI：取消申领（草稿或已审批均可，按状态断言前置）。 */
  466 | async function cancelRequisitionViaUi(page: Page, department: string, expectedStatus: string, reason: string): Promise<void> {
  467 |   const row = page.getByRole("row").filter({ hasText: department });
  468 |   await expect(row).toContainText(expectedStatus);
  469 |   await row.getByRole("button", { name: "取消" }).click();
  470 |   const modal = modalByTitle(page, "取消申领");
  471 |   await expect(modal).toBeVisible();
  472 |   await modal.getByLabel("取消原因").fill(reason);
  473 |   await modal.getByRole("button", { name: "确认取消" }).click();
  474 |   await expect(modal).not.toBeVisible({ timeout: 10_000 });
  475 |   await expect(row).toContainText("已取消");
  476 | }
  477 | 
  478 | /** API 列表按科室取回申领单，并记录到清理集合。 */
  479 | async function captureRequisition(page: Page, department: string): Promise<PharmacyRequisition> {
  480 |   const list = await api<PharmacyRequisitionList>(
  481 |     page,
  482 |     `/crate-api/pharmacy/v1/requisitions?department=${encodeURIComponent(department)}&limit=10`,
  483 |   );
  484 |   const listRecord = list.records.find((record) => record.department === department);
  485 |   if (!listRecord) throw new Error(`requisition not found for department ${department}`);
  486 |   if (!createdRequisitionIds.includes(listRecord.id)) createdRequisitionIds.push(listRecord.id);
  487 |   const detail = await api<PharmacyRequisition>(page, `/crate-api/pharmacy/v1/requisitions/${listRecord.id}`);
  488 |   return detail;
  489 | }
  490 | 
  491 | /** DB：读取指定仓库/物资/批次结存。 */
  492 | async function readStock(warehouse: string, materialId: string, lotId: string): Promise<StockSnapshot | null> {
  493 |   const result = await databasePool.query<{ quantity: string; locked_quantity: string; total_cost: string }>(
  494 |     `SELECT quantity::text AS quantity, locked_quantity::text AS locked_quantity, total_cost::text AS total_cost
  495 |      FROM public.stocks
  496 |      WHERE warehouse = $1 AND material_id = $2 AND lot_id = $3`,
  497 |     [warehouse, materialId, lotId],
  498 |   );
  499 |   const row = result.rows[0];
  500 |   return row ? { quantity: Number(row.quantity), lockedQuantity: Number(row.locked_quantity), totalCost: Number(row.total_cost) } : null;
  501 | }
  502 | 
  503 | /** DB：013 调拨来源的库存操作数量（应为 2：源 OUTBOUND + 目标 INBOUND）。 */
  504 | async function countRequisitionTransferOperations(materialId: string): Promise<number> {
  505 |   const result = await databasePool.query<{ count: string }>(
  506 |     `SELECT count(DISTINCT operation.id)::text AS count
  507 |      FROM public.stock_operations operation
  508 |      JOIN public.stock_operation_details detail ON detail.operation_id = operation.id
  509 |      WHERE detail.material_id = $1
  510 |        AND operation.metadata->>'source' = 'PHARMACY_REQUISITION_TRANSFER'`,
  511 |     [materialId],
  512 |   );
  513 |   return Number(result.rows[0]?.count ?? "0");
  514 | }
  515 | 
  516 | /** DB：013 调拨来源的库存操作明细数量（每物资一条出库 + 一条入库）。 */
  517 | async function countRequisitionTransferDetails(materialId: string): Promise<number> {
  518 |   const result = await databasePool.query<{ count: string }>(
  519 |     `SELECT count(*)::text AS count
  520 |      FROM public.stock_operation_details detail
  521 |      JOIN public.stock_operations operation ON operation.id = detail.operation_id
  522 |      WHERE detail.material_id = $1
  523 |        AND operation.metadata->>'source' = 'PHARMACY_REQUISITION_TRANSFER'`,
  524 |     [materialId],
```