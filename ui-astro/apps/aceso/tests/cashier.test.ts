/**
 * 054 E4：收银工作台展示/表单辅助（`src/lib/cashier.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `childcare.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 付费状态四态、套餐/订阅状态、结算状态、收款方式、发票状态与**后端逐字一致**
 *     （直接读后端 Kotlin 源码的**漂移守卫**：前端镜像常量，后端一改前端测试即红）；
 *   - **金额纪律**：页面不得提交 `total_amount`/`paid_amount`/`used_amount`/`pay_state` 等
 *     服务端派生字段（金额一律服务端计算）；
 *   - 表单校验与后端 `parseMoney`/`parseQuantity`/互斥规则同口径；
 *   - 菜单接线：收银工作台在儿保域可见、受 `billing:write` 显隐控制（F1/R9）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type {
	ChildPackageSubscription,
	ChildServiceInvoice,
	ChildServiceSettlement,
} from "@pitchfork/shared/aceso";

import {
	INVOICE_STATUSES,
	PACKAGE_STATUSES,
	PAYMENT_METHODS,
	PAY_STATES,
	SETTLEMENT_STATUSES,
	SUBSCRIPTION_STATUSES,
	addDraft,
	canTransitionInvoice,
	canTransitionSubscription,
	emptyPackageItem,
	emptySettlementItem,
	formatMoney,
	formatQuantity,
	invoiceSourceLabel,
	invoiceStatusVariant,
	isInvoiceTerminal,
	isIsoDate,
	packageItemsLabel,
	packageStatusVariant,
	parseMoneyText,
	parseQuantityText,
	payStateCount,
	payStateLabel,
	payStateRank,
	payStateVariant,
	removeDraft,
	settlementOutstanding,
	settlementPaidRatio,
	settlementStatusVariant,
	settlementSummary,
	sortSubscriptions,
	subscriptionRatio,
	subscriptionStatusVariant,
	toSettlementItems,
	updateDraft,
	validateInvoiceForm,
	validatePackageForm,
	validatePaymentForm,
	validateSettlementForm,
	validateSubscriptionForm,
} from "../src/lib/cashier.ts";

const BACKEND_DIR = "../../../../service-vertx-kotlin/libs/healthcare/src/main/kotlin/com/ovaphlow/crate/healthcare/";

function readBackend(file: string): string {
	return readFileSync(new URL(`${BACKEND_DIR}${file}`, import.meta.url), "utf8");
}

function quoted(text: string): string[] {
	return [...text.matchAll(/"([^"]*)"/g)].map((match) => match[1] ?? "");
}

function subscriptionFixture(
	overrides: Partial<ChildPackageSubscription> = {},
): ChildPackageSubscription {
	return {
		id: "01JTESTSUB00000000000000",
		patient_id: "01JTESTCHILD0000000000000",
		package_id: "01JTESTPKG00000000000000",
		package_code: "PKG-BASIC",
		package_name: "基础体检套餐",
		purchased_at: "2026-10-07T09:00:00+08:00",
		amount: 100,
		paid_amount: 0,
		used_amount: 0,
		status: "启用",
		pay_state: "未付",
		metadata: null,
		operator: "收银员甲",
		created_at: "2026-10-07T09:00:00+08:00",
		updated_at: "2026-10-07T09:00:00+08:00",
		...overrides,
	};
}

function settlementFixture(overrides: Partial<ChildServiceSettlement> = {}): ChildServiceSettlement {
	return {
		id: "01JTESTSETTLE0000000000",
		settlement_no: "SN-20261007-0001",
		patient_id: "01JTESTCHILD0000000000000",
		subscription_id: null,
		total_amount: 100,
		paid_amount: 0,
		status: "待收款",
		operator: "收银员甲",
		metadata: null,
		created_at: "2026-10-07T09:00:00+08:00",
		updated_at: "2026-10-07T09:00:00+08:00",
		items: [],
		...overrides,
	};
}

function invoiceFixture(overrides: Partial<ChildServiceInvoice> = {}): ChildServiceInvoice {
	return {
		id: "01JTESTINVOICE000000000",
		invoice_no: "INV-0001",
		patient_id: "01JTESTCHILD0000000000000",
		title: "某某家长",
		amount: 100,
		status: "已开具",
		settlement_id: null,
		subscription_id: null,
		issued_at: "2026-10-07T09:00:00+08:00",
		operator: "收银员甲",
		metadata: null,
		created_at: "2026-10-07T09:00:00+08:00",
		updated_at: "2026-10-07T09:00:00+08:00",
		...overrides,
	};
}

// ─── 常量漂移守卫（直接读后端源码）─────────────────────────────────────────

test("付费状态四态与后端 ChildServicePackageService 逐字一致", () => {
	const source = readBackend("ChildServicePackageService.kt");
	const states = [
		/const val PAY_STATE_UNPAID = "([^"]+)"/.exec(source)?.[1],
		/const val PAY_STATE_PARTIAL = "([^"]+)"/.exec(source)?.[1],
		/const val PAY_STATE_PAID = "([^"]+)"/.exec(source)?.[1],
		/const val PAY_STATE_USED_UP = "([^"]+)"/.exec(source)?.[1],
	];
	assert.deepEqual(states, [...PAY_STATES]);
});

test("套餐/订阅状态白名单与后端逐字一致", () => {
	const source = readBackend("ChildServicePackageService.kt");
	const packageStatuses = /val PACKAGE_STATUSES[^=]*= linkedSetOf\(([^)]*)\)/.exec(source);
	const subscriptionStatuses = /val SUBSCRIPTION_STATUSES[^=]*= linkedSetOf\(([^)]*)\)/.exec(source);
	assert.ok(packageStatuses, "后端必须声明 PACKAGE_STATUSES");
	assert.ok(subscriptionStatuses, "后端必须声明 SUBSCRIPTION_STATUSES");
	assert.deepEqual(quoted(packageStatuses[1] ?? ""), [...PACKAGE_STATUSES]);
	assert.deepEqual(quoted(subscriptionStatuses[1] ?? ""), [...SUBSCRIPTION_STATUSES]);
});

test("结算状态与收款方式白名单与后端 ChildServiceSettlementService 逐字一致", () => {
	const source = readBackend("ChildServiceSettlementService.kt");
	const statuses = /val STATUSES: Set<String> = linkedSetOf\(([^)]*)\)/.exec(source);
	const methods = /val PAYMENT_METHODS[^=]*= linkedSetOf\(([^)]*)\)/.exec(source);
	assert.ok(statuses, "后端必须声明结算 STATUSES");
	assert.ok(methods, "后端必须声明 PAYMENT_METHODS");
	assert.deepEqual(quoted(statuses[1] ?? ""), [...SETTLEMENT_STATUSES]);
	assert.deepEqual(quoted(methods[1] ?? ""), [...PAYMENT_METHODS]);
});

test("发票状态白名单与后端 InvoiceService 逐字一致", () => {
	const source = readBackend("InvoiceService.kt");
	const statuses = /val STATUSES: Set<String> = linkedSetOf\(([^)]*)\)/.exec(source);
	assert.ok(statuses, "后端必须声明发票 STATUSES");
	assert.deepEqual(quoted(statuses[1] ?? ""), [...INVOICE_STATUSES]);
});

// ─── 付费状态四态可视化 ────────────────────────────────────────────────────

test("付费状态徽章配色与展示：未付=danger、部分=warning、已付=success、已用完=info", () => {
	assert.deepEqual([...PAY_STATES], ["未付", "部分", "已付", "已用完"]);
	assert.equal(payStateVariant("未付"), "danger");
	assert.equal(payStateVariant("部分"), "warning");
	assert.equal(payStateVariant("已付"), "success");
	assert.equal(payStateVariant("已用完"), "info");
	assert.equal(payStateVariant(null), "default");
	assert.equal(payStateLabel("已付"), "已付");
	assert.equal(payStateLabel(null), "未派生");
});

test("订阅排序：未付在前（收银员优先跟进），同级保持服务端顺序", () => {
	const subscriptions = [
		subscriptionFixture({ pay_state: "已付" }),
		subscriptionFixture({ pay_state: "未付" }),
		subscriptionFixture({ pay_state: "已用完" }),
		subscriptionFixture({ pay_state: "部分" }),
	];
	const sorted = sortSubscriptions(subscriptions);
	assert.deepEqual(
		sorted.map((item) => item.pay_state),
		["未付", "部分", "已付", "已用完"],
	);
	assert.equal(subscriptions[0]?.pay_state, "已付", "排序不得修改入参");
	assert.equal(payStateRank("未付"), 0);
	assert.equal(payStateRank("未知状态"), 99);
});

test("付费状态计数：未知状态计入 other，不丢弃", () => {
	assert.deepEqual(payStateCount([]), { 未付: 0, 部分: 0, 已付: 0, 已用完: 0, other: 0, total: 0 });
	assert.deepEqual(
		payStateCount([
			subscriptionFixture({ pay_state: "未付" }),
			subscriptionFixture({ pay_state: "已用完" }),
			subscriptionFixture({ pay_state: "未知" }),
		]),
		{ 未付: 1, 部分: 0, 已付: 0, 已用完: 1, other: 1, total: 3 },
	);
});

test("订阅状态机：启用 ⇄ 停用；同状态/未知不可流转", () => {
	assert.equal(canTransitionSubscription("启用", "停用"), true);
	assert.equal(canTransitionSubscription("停用", "启用"), true);
	assert.equal(canTransitionSubscription("启用", "启用"), false);
	assert.equal(canTransitionSubscription("未知", "启用"), false);
	assert.equal(canTransitionSubscription("启用", ""), false);
	assert.equal(subscriptionStatusVariant("启用"), "success");
	assert.equal(subscriptionStatusVariant("停用"), "default");
});

test("订阅收款/耗用进度：金额为 0 或非法时不除零", () => {
	const subscription = subscriptionFixture({ amount: 100, paid_amount: 40, used_amount: 100 });
	assert.equal(subscriptionRatio(subscription, "paid"), 0.4);
	assert.equal(subscriptionRatio(subscription, "used"), 1);
	assert.equal(subscriptionRatio(subscriptionFixture({ amount: 0 }), "paid"), 0);
	assert.equal(subscriptionRatio(subscriptionFixture({ amount: 100, paid_amount: 150 }), "paid"), 1, "进度条宽度截断到 1");
	assert.equal(subscriptionRatio(subscriptionFixture({ amount: 100, paid_amount: 0 }), "paid"), 0);
});

test("套餐状态徽章与明细摘要", () => {
	assert.equal(packageStatusVariant("启用"), "success");
	assert.equal(packageStatusVariant("停用"), "default");
	assert.equal(
		packageItemsLabel([
			{ item_name: "基础体检", quantity: 1 },
			{ item_name: "发育评估", quantity: 2 },
		]),
		"基础体检 ×1、发育评估 ×2",
	);
	assert.equal(packageItemsLabel([]), "无明细");
});

// ─── 结算与收款 ────────────────────────────────────────────────────────────

test("结算状态徽章配色：待收款=danger、部分收款=warning、已结清=success、已作废=default", () => {
	assert.equal(settlementStatusVariant("待收款"), "danger");
	assert.equal(settlementStatusVariant("部分收款"), "warning");
	assert.equal(settlementStatusVariant("已结清"), "success");
	assert.equal(settlementStatusVariant("已作废"), "default");
	assert.equal(settlementStatusVariant(null), "info");
});

test("未收金额与结算摘要（负数按 0，仅展示不改变服务端口径）", () => {
	assert.equal(settlementOutstanding(settlementFixture()), 100);
	assert.equal(settlementOutstanding(settlementFixture({ paid_amount: 40 })), 60);
	assert.equal(settlementOutstanding(settlementFixture({ paid_amount: 100 })), 0);
	assert.equal(settlementOutstanding(settlementFixture({ paid_amount: 120 })), 0);
	assert.equal(
		settlementSummary(settlementFixture({ total_amount: 100, paid_amount: 40 })),
		"合计 100.00 元 · 已收 40.00 元 · 未收 60.00 元",
	);
	assert.equal(settlementPaidRatio(settlementFixture({ total_amount: 100, paid_amount: 40 })), 0.4);
	assert.equal(settlementPaidRatio(settlementFixture({ total_amount: 0 })), 0);
});

// ─── 发票 ──────────────────────────────────────────────────────────────────

test("发票状态机：仅 已开具 → 已作废；已作废为终态", () => {
	assert.deepEqual([...INVOICE_STATUSES], ["已开具", "已作废"]);
	assert.equal(canTransitionInvoice("已开具", "已作废"), true);
	assert.equal(canTransitionInvoice("已作废", "已开具"), false);
	assert.equal(canTransitionInvoice("已开具", "已开具"), false);
	assert.equal(canTransitionInvoice("未知", "已作废"), false);
	assert.equal(isInvoiceTerminal("已作废"), true);
	assert.equal(isInvoiceTerminal("已开具"), false);
	assert.equal(invoiceStatusVariant("已开具"), "success");
	assert.equal(invoiceStatusVariant("已作废"), "default");
});

test("发票关联来源展示：结算单 / 套餐订阅 / 手动金额", () => {
	assert.match(invoiceSourceLabel(invoiceFixture({ settlement_id: "01JTEST123456" })), /^结算单 /);
	assert.match(invoiceSourceLabel(invoiceFixture({ subscription_id: "01JTESTABCDEF" })), /^套餐订阅 /);
	assert.equal(invoiceSourceLabel(invoiceFixture()), "手动金额");
});

// ─── 金额/数量格式化与解析（与后端 parseMoney/parseQuantity 同口径）──────────

test("金额与数量格式化：金额两位小数、数量最多三位去尾零", () => {
	assert.equal(formatMoney(100), "100.00");
	assert.equal(formatMoney(0.5), "0.50");
	assert.equal(formatMoney(null), "-");
	assert.equal(formatMoney(Number.NaN), "-");
	assert.equal(formatQuantity(1), "1");
	assert.equal(formatQuantity(1.5), "1.5");
	assert.equal(formatQuantity(0.125), "0.125");
	assert.equal(formatQuantity(null), "-");
});

test("金额解析：非负且最多 2 位小数（超出直接拒绝，不静默进位）", () => {
	assert.equal(parseMoneyText("0"), 0);
	assert.equal(parseMoneyText("100"), 100);
	assert.equal(parseMoneyText("100.5"), 100.5);
	assert.equal(parseMoneyText("100.55"), 100.55);
	assert.equal(parseMoneyText("100.555"), null, "3 位小数必须拒绝");
	assert.equal(parseMoneyText("-1"), null);
	assert.equal(parseMoneyText(""), null);
	assert.equal(parseMoneyText("abc"), null);
	assert.equal(parseMoneyText("1e3"), null);
});

test("数量解析：正数且最多 3 位小数", () => {
	assert.equal(parseQuantityText("1"), 1);
	assert.equal(parseQuantityText("0.125"), 0.125);
	assert.equal(parseQuantityText("0.1255"), null, "4 位小数必须拒绝");
	assert.equal(parseQuantityText("0"), null);
	assert.equal(parseQuantityText("-2"), null);
	assert.equal(parseQuantityText(""), null);
});

test("ISO 日期校验", () => {
	assert.equal(isIsoDate("2026-10-07"), true);
	assert.equal(isIsoDate("2026-02-30"), false);
	assert.equal(isIsoDate(""), false);
});

// ─── 明细草稿编辑（纯函数）─────────────────────────────────────────────────

test("明细草稿编辑是纯函数：增删改都不修改入参", () => {
	const base = [emptyPackageItem()];
	const added = addDraft(base, { item_code: "ITEM-1", item_name: "基础体检", quantity: 1 });
	assert.equal(base.length, 1, "addDraft 不得修改入参");
	assert.equal(added.length, 2);

	const updated = updateDraft(added, 1, { quantity: 3 });
	assert.equal(added[1]?.quantity, 1, "updateDraft 不得修改入参");
	assert.equal(updated[1]?.quantity, 3);
	assert.equal(updated[1]?.item_name, "基础体检");

	const removed = removeDraft(updated, 0);
	assert.equal(updated.length, 2, "removeDraft 不得修改入参");
	assert.deepEqual(removed.map((item) => item.item_code), ["ITEM-1"]);

	assert.equal(updateDraft(base, 9, { quantity: 9 })[0]?.quantity, 1);
	assert.equal(removeDraft(base, 9).length, 1);
});

test("结算明细草稿 → 提交体：数值化（金额仍由服务端计算）", () => {
	const draft = { ...emptySettlementItem(), item_code: " ITEM-1 ", item_name: " 基础体检 ", unit_price: "100.50", quantity: "2" };
	assert.deepEqual(toSettlementItems([draft]), [
		{ item_code: "ITEM-1", item_name: "基础体检", unit_price: 100.5, quantity: 2 },
	]);
	assert.deepEqual(Object.keys(toSettlementItems([draft])[0] ?? {}).sort(), [
		"item_code",
		"item_name",
		"quantity",
		"unit_price",
	]);
});

// ─── 表单校验（与后端同口径）───────────────────────────────────────────────

test("套餐表单校验：编码/名称/价格/明细", () => {
	const valid = {
		code: "PKG-BASIC",
		name: "基础体检套餐",
		priceText: "1280.00",
		items: [{ item_code: "ITEM-1", item_name: "基础体检", quantity: 1 }],
	};
	assert.equal(validatePackageForm(valid), null);
	assert.match(validatePackageForm({ ...valid, code: " " }) ?? "", /套餐编码必填/);
	assert.match(validatePackageForm({ ...valid, name: "" }) ?? "", /套餐名称必填/);
	assert.match(validatePackageForm({ ...valid, priceText: "" }) ?? "", /套餐价格必填/);
	assert.match(validatePackageForm({ ...valid, priceText: "-1" }) ?? "", /最多 2 位小数/);
	assert.match(validatePackageForm({ ...valid, items: [] }) ?? "", /至少填写一条/);
	assert.match(
		validatePackageForm({ ...valid, items: [{ item_code: "", item_name: "x", quantity: 1 }] }) ?? "",
		/缺少项目编码/,
	);
	assert.match(
		validatePackageForm({ ...valid, items: [{ item_code: "A", item_name: " ", quantity: 1 }] }) ?? "",
		/缺少项目名称/,
	);
	assert.match(
		validatePackageForm({ ...valid, items: [{ item_code: "A", item_name: "B", quantity: 0 }] }) ?? "",
		/数量必须为正数/,
	);
});

test("订阅表单校验：儿童与套餐必选", () => {
	assert.equal(validateSubscriptionForm({ patientId: "p1", packageId: "k1" }), null);
	assert.match(validateSubscriptionForm({ patientId: "", packageId: "k1" }) ?? "", /请选择儿童/);
	assert.match(validateSubscriptionForm({ patientId: "p1", packageId: " " }) ?? "", /请选择套餐/);
});

test("结算表单校验：儿童必选、明细完整（不校验金额）", () => {
	const item = { ...emptySettlementItem(), item_code: "A", item_name: "基础体检", unit_price: "100", quantity: "1" };
	assert.equal(validateSettlementForm({ patientId: "p1", items: [item] }), null);
	assert.match(validateSettlementForm({ patientId: "", items: [item] }) ?? "", /请选择儿童/);
	assert.match(validateSettlementForm({ patientId: "p1", items: [] }) ?? "", /至少填写一条/);
	assert.match(
		validateSettlementForm({ patientId: "p1", items: [{ ...item, item_code: "" }] }) ?? "",
		/缺少项目编码/,
	);
	assert.match(
		validateSettlementForm({ patientId: "p1", items: [{ ...item, unit_price: "100.555" }] }) ?? "",
		/单价必须是非负数字且最多 2 位小数/,
	);
	assert.match(
		validateSettlementForm({ patientId: "p1", items: [{ ...item, quantity: "0" }] }) ?? "",
		/数量必须为正数且最多 3 位小数/,
	);
});

test("收款表单校验：金额为正且 ≤2 位小数、方式白名单", () => {
	assert.equal(validatePaymentForm({ amountText: "100", method: "现金" }), null);
	assert.equal(validatePaymentForm({ amountText: "0.5", method: "支付宝" }), null);
	assert.match(validatePaymentForm({ amountText: "", method: "现金" }) ?? "", /收款金额必须是非负数字/);
	assert.match(validatePaymentForm({ amountText: "100.555", method: "现金" }) ?? "", /最多 2 位小数/);
	assert.match(validatePaymentForm({ amountText: "0", method: "现金" }) ?? "", /必须大于 0/);
	assert.match(validatePaymentForm({ amountText: "100", method: "支票" }) ?? "", /收款方式必须是/);
});

test("发票表单校验：互斥关联、关联时不得填金额、无关联时必须填金额、票号长度", () => {
	const base = { patientId: "p1", title: "某某家长", invoiceNo: "", amountText: "100", settlementId: "", subscriptionId: "" };
	assert.equal(validateInvoiceForm(base), null);
	assert.equal(
		validateInvoiceForm({ ...base, amountText: "", settlementId: "s1" }),
		null,
		"关联结算单时金额留空由服务端派生",
	);
	assert.equal(
		validateInvoiceForm({ ...base, amountText: "", subscriptionId: "k1" }),
		null,
		"关联订阅时金额留空由服务端派生",
	);
	assert.match(validateInvoiceForm({ ...base, patientId: "" }) ?? "", /请选择儿童/);
	assert.match(validateInvoiceForm({ ...base, title: " " }) ?? "", /发票抬头必填/);
	assert.match(
		validateInvoiceForm({ ...base, settlementId: "s1", subscriptionId: "k1" }) ?? "",
		/只能关联其一/,
	);
	assert.match(
		validateInvoiceForm({ ...base, settlementId: "s1" }) ?? "",
		/请勿填写金额/,
	);
	assert.match(validateInvoiceForm({ ...base, amountText: "" }) ?? "", /必须填写金额/);
	assert.match(validateInvoiceForm({ ...base, amountText: "0" }) ?? "", /必须大于 0/);
	assert.match(validateInvoiceForm({ ...base, invoiceNo: "X".repeat(65) }) ?? "", /最多 64 个字符/);
});

// ─── 金额纪律（页面不得提交服务端派生字段）─────────────────────────────────

test("页面不得提交 total_amount/paid_amount/used_amount/pay_state 等派生字段（金额一律服务端计算）", () => {
	const page = readFileSync(
		new URL("../src/components/CashierWorkbenchPage.tsx", import.meta.url),
		"utf8",
	);
	for (const field of ["total_amount", "paid_amount", "used_amount", "pay_state"]) {
		assert.doesNotMatch(
			page,
			new RegExp(`${field}\\s*:`),
			`不得在请求体中提交派生字段 ${field}`,
		);
	}
	// 明细金额同理不得提交（服务端按 单价 × 数量 计算）
	assert.doesNotMatch(page, /amount\s*:\s*item\.unit_price/, "不得前端计算明细金额");
	assert.match(page, /toSettlementItems\(/, "结算明细必须走统一转换函数（只含 item_code/item_name/unit_price/quantity）");
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("收银工作台菜单项：儿保域可见 + billing:write 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source.split("\n").find((text) => text.includes('"/dashboard/cashier-workbench"'));
	assert.ok(line, "Sidebar 必须包含收银工作台菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/, "054 E4：工作台必须在儿保域可见（R9）");
	assert.match(
		line,
		/requiredPermissions:\s*\["billing:write"\]/,
		"054 F1：写入口必须按 billing:write 显隐（前端隐藏不替代 403）",
	);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(
		new URL("../src/pages/dashboard/cashier-workbench.astro", import.meta.url),
		"utf8",
	);
	assert.match(page, /CashierWorkbenchPage/, "Astro 页面必须挂载收银工作台组件");
});

test("共享客户端提供 E 链端点且路径与后端一致（发票在 /invoices）", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	for (const path of [
		"/healthcare/v1/child-service-packages",
		"/healthcare/v1/child-package-subscriptions",
		"/healthcare/v1/child-service-settlements",
		"/healthcare/v1/invoices",
	]) {
		assert.ok(client.includes(path), `aceso.ts 必须包含 E 链端点 ${path}`);
	}
	assert.match(client, /export function registerChildServiceSettlementPayment/, "必须有收款客户端函数");
	assert.match(client, /export function updateChildServiceInvoiceStatus/, "必须有发票作废客户端函数");
});
