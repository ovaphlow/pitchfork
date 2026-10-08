/**
 * 收银工作台展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 E4）：
 * - **金额纪律**：明细金额、合计、订阅付费状态、结算状态一律**服务端派生**；
 *   本文件只做**常量镜像、状态机镜像、表单校验与展示格式化**，
 *   **不重算金额、不自行判定付费状态**（`pay_state`/`status` 直接用服务端返回值）。
 * - **常量镜像**（后端无查询端点，只能镜像；`cashier.test.ts` 有漂移守卫）：
 *   `ChildServicePackageService` 的套餐/订阅状态与付费状态四态、
 *   `ChildServiceSettlementService` 的结算状态与收款方式白名单、
 *   `InvoiceService` 的发票状态与状态机。
 * - 服务端 400/409（超额收款、票号重复、状态回退、互斥字段）文案必须**原样透出**，
 *   前端不重写、不吞错。
 */

import type {
  ChildPackageSubscription,
  ChildServiceInvoice,
  ChildServiceSettlement,
  ChildServiceSettlementItemInput,
} from "@pitchfork/shared/aceso";

// ─── 付费状态四态（镜像后端 PAY_STATE_*，读时派生）──────────────────────────

/** 付费状态白名单（后端 `ChildServicePackageService.PAY_STATE_*`）。 */
export const PAY_STATES = ["未付", "部分", "已付", "已用完"] as const;
export type PayState = (typeof PAY_STATES)[number];

/** 付费状态排序权重：**未付在前**（收银员最需要跟进的是未收款）。 */
export const PAY_STATE_ORDER: Record<string, number> = { 未付: 0, 部分: 1, 已付: 2, 已用完: 3 };

/** 付费状态排序权重；未知值排最后（不隐藏、不丢弃）。 */
export function payStateRank(state: string | null | undefined): number {
  return PAY_STATE_ORDER[(state ?? "").trim()] ?? 99;
}

/** 付费状态徽章配色：未付=danger、部分=warning、已付=success、已用完=info。 */
export function payStateVariant(
  state: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((state ?? "").trim()) {
    case "未付":
      return "danger";
    case "部分":
      return "warning";
    case "已付":
      return "success";
    case "已用完":
      return "info";
    default:
      return "default";
  }
}

/** 付费状态展示：空值显示「未派生」（不猜测）。 */
export function payStateLabel(state: string | null | undefined): string {
  const key = (state ?? "").trim();
  return key === "" ? "未派生" : key;
}

/** 订阅排序：未付 → 部分 → 已付 → 已用完；同级保持服务端顺序（稳定，不改入参）。 */
export function sortSubscriptions(
  subscriptions: readonly ChildPackageSubscription[],
): ChildPackageSubscription[] {
  return [...subscriptions].sort((a, b) => payStateRank(a.pay_state) - payStateRank(b.pay_state));
}

/** 付费状态计数（未知状态计入 `other`，不丢弃）。 */
export function payStateCount(
  subscriptions: readonly ChildPackageSubscription[],
): { 未付: number; 部分: number; 已付: number; 已用完: number; other: number; total: number } {
  const summary = { 未付: 0, 部分: 0, 已付: 0, 已用完: 0, other: 0, total: subscriptions.length };
  for (const subscription of subscriptions) {
    const state = (subscription.pay_state ?? "").trim();
    if (state === "未付") summary.未付 += 1;
    else if (state === "部分") summary.部分 += 1;
    else if (state === "已付") summary.已付 += 1;
    else if (state === "已用完") summary.已用完 += 1;
    else summary.other += 1;
  }
  return summary;
}

/** 订阅状态白名单（后端 `SUBSCRIPTION_STATUSES`：启用 ⇄ 停用）。 */
export const SUBSCRIPTION_STATUSES = ["启用", "停用"] as const;
export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number];

/** 订阅状态机（后端 `canTransitionSubscription` 逐字镜像）。 */
export function canTransitionSubscription(
  from: string | null | undefined,
  to: string | null | undefined,
): boolean {
  const current = (from ?? "").trim();
  const target = (to ?? "").trim();
  if (target === "") return false;
  if (current === "启用") return target === "停用";
  if (current === "停用") return target === "启用";
  return false;
}

/** 订阅状态徽章配色。 */
export function subscriptionStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  return (status ?? "").trim() === "启用" ? "success" : "default";
}

// ─── 套餐定义（镜像后端 PACKAGE_STATUSES）───────────────────────────────────

/** 套餐状态白名单（后端 `PACKAGE_STATUSES`）。 */
export const PACKAGE_STATUSES = ["启用", "停用"] as const;
export type PackageStatus = (typeof PACKAGE_STATUSES)[number];

/** 套餐状态徽章配色。 */
export function packageStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  return (status ?? "").trim() === "启用" ? "success" : "default";
}

/** 套餐明细摘要：`基础体检 ×1、发育评估 ×2`。 */
export function packageItemsLabel(items: readonly { item_name: string; quantity: number }[]): string {
  if (items.length === 0) return "无明细";
  return items.map((item) => `${item.item_name} ×${formatQuantity(item.quantity)}`).join("、");
}

// ─── 结算单与收款（镜像后端状态与收款方式）─────────────────────────────────

/** 结算状态白名单（后端 `STATUSES`）。 */
export const SETTLEMENT_STATUSES = ["待收款", "部分收款", "已结清", "已作废"] as const;
export type SettlementStatus = (typeof SETTLEMENT_STATUSES)[number];

/** 结算状态徽章配色：待收款=danger、部分收款=warning、已结清=success、已作废=default。 */
export function settlementStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "待收款":
      return "danger";
    case "部分收款":
      return "warning";
    case "已结清":
      return "success";
    case "已作废":
      return "default";
    default:
      return "info";
  }
}

/** 收款方式白名单（后端 `PAYMENT_METHODS`，与 V516 `payments.method` 同口径）。 */
export const PAYMENT_METHODS = ["现金", "转账", "银行卡", "微信", "支付宝"] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

/** 未收金额 = 合计 − 已收（负数按 0 处理；仅展示，不改变服务端口径）。 */
export function settlementOutstanding(
  settlement: Pick<ChildServiceSettlement, "total_amount" | "paid_amount">,
): number {
  const outstanding = (settlement.total_amount ?? 0) - (settlement.paid_amount ?? 0);
  return outstanding > 0 ? outstanding : 0;
}

/** 结算单摘要：`合计 100.00 元 · 已收 40.00 元 · 未收 60.00 元`。 */
export function settlementSummary(
  settlement: Pick<ChildServiceSettlement, "total_amount" | "paid_amount">,
): string {
  return (
    `合计 ${formatMoney(settlement.total_amount)} 元 · ` +
    `已收 ${formatMoney(settlement.paid_amount)} 元 · ` +
    `未收 ${formatMoney(settlementOutstanding(settlement))} 元`
  );
}

/** 收款进度（0–1，仅用于进度条宽度；合计为 0 时按 0）。 */
export function settlementPaidRatio(
  settlement: Pick<ChildServiceSettlement, "total_amount" | "paid_amount">,
): number {
  const total = settlement.total_amount ?? 0;
  if (!Number.isFinite(total) || total <= 0) return 0;
  const ratio = (settlement.paid_amount ?? 0) / total;
  if (!Number.isFinite(ratio) || ratio <= 0) return 0;
  return Math.min(1, ratio);
}

/** 订阅付费进度（0–1，仅用于进度条宽度；金额为 0 时按 0）。 */
export function subscriptionRatio(
  subscription: Pick<ChildPackageSubscription, "amount" | "paid_amount" | "used_amount">,
  kind: "paid" | "used",
): number {
  const amount = subscription.amount ?? 0;
  if (!Number.isFinite(amount) || amount <= 0) return 0;
  const value = kind === "paid" ? subscription.paid_amount ?? 0 : subscription.used_amount ?? 0;
  if (!Number.isFinite(value) || value <= 0) return 0;
  return Math.min(1, value / amount);
}

// ─── 发票（镜像后端状态与状态机）───────────────────────────────────────────

/** 发票状态白名单（后端 `STATUSES`）。 */
export const INVOICE_STATUSES = ["已开具", "已作废"] as const;
export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];

/** 发票状态机（后端 `canTransition` 逐字镜像）：**仅** `已开具 → 已作废`。 */
export function canTransitionInvoice(
  from: string | null | undefined,
  to: string | null | undefined,
): boolean {
  return (from ?? "").trim() === "已开具" && (to ?? "").trim() === "已作废";
}

/** 终态（已作废）不可再流转。 */
export function isInvoiceTerminal(status: string | null | undefined): boolean {
  return (status ?? "").trim() === "已作废";
}

/** 发票状态徽章配色：已开具=success、已作废=default。 */
export function invoiceStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "已开具":
      return "success";
    case "已作废":
      return "default";
    default:
      return "info";
  }
}

/** 发票关联来源展示：`结算单 SN-xxx` / `套餐订阅` / `手动金额`。 */
export function invoiceSourceLabel(
  invoice: Pick<ChildServiceInvoice, "settlement_id" | "subscription_id">,
): string {
  if (invoice.settlement_id) return `结算单 ${invoice.settlement_id.slice(-6)}`;
  if (invoice.subscription_id) return `套餐订阅 ${invoice.subscription_id.slice(-6)}`;
  return "手动金额";
}

// ─── 金额与数量格式化（仅展示，不参与计算）─────────────────────────────────

/** 金额展示：两位小数（与后端 `setScale(2, HALF_UP)` 口径一致），非法值 `-`。 */
export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return value.toFixed(2);
}

/** 数量展示：最多 3 位小数去尾零（与后端 `NUMERIC(20,3)` 对齐），非法值 `-`。 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return String(Number(value.toFixed(3)));
}

// ─── 解析与校验（与后端同口径）─────────────────────────────────────────────

/**
 * 金额文本 → 数字：空串 → `null`；负数、超过 2 位小数、非数字 → `null`
 * （后端 `parseMoney` 为「非负 + 最多 2 位小数」，超出直接 400，不静默进位）。
 */
export function parseMoneyText(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** 数量文本 → 数字：空串 → `null`；非正数、超过 3 位小数、非数字 → `null`。 */
export function parseQuantityText(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  if (!/^\d+(\.\d{1,3})?$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** ISO 日期（yyyy-MM-dd）且真实存在。 */
export function isIsoDate(value: string | null | undefined): boolean {
  const text = (value ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text;
}

/** 套餐明细编辑（纯函数，不可变）。 */
export function emptyPackageItem(): { item_code: string; item_name: string; quantity: number } {
  return { item_code: "", item_name: "", quantity: 1 };
}

/** 结算明细编辑（纯函数，不可变）。 */
export function emptySettlementItem(): {
  item_code: string;
  item_name: string;
  unit_price: string;
  quantity: string;
} {
  return { item_code: "", item_name: "", unit_price: "", quantity: "1" };
}

export type SettlementItemDraft = ReturnType<typeof emptySettlementItem>;

/** 追加一行（返回新数组，不修改入参）。 */
export function addDraft<T extends Record<string, unknown>>(
  items: readonly T[],
  item: T,
): T[] {
  return [...items, { ...item }];
}

/** 修改第 index 行（越界返回原样副本）。 */
export function updateDraft<T extends Record<string, unknown>>(
  items: readonly T[],
  index: number,
  patch: Partial<T>,
): T[] {
  if (index < 0 || index >= items.length) return items.map((item) => ({ ...item }));
  return items.map((item, current) => (current === index ? { ...item, ...patch } : { ...item }));
}

/** 删除第 index 行（越界返回原样副本）。 */
export function removeDraft<T extends Record<string, unknown>>(
  items: readonly T[],
  index: number,
): T[] {
  return items.filter((_, current) => current !== index).map((item) => ({ ...item }));
}

/** 套餐表单校验：code/name 必填、price 非负 ≤2 位小数、至少一条明细且明细完整。 */
export function validatePackageForm(input: {
  code: string;
  name: string;
  priceText: string;
  items: readonly { item_code: string; item_name: string; quantity: number }[];
}): string | null {
  if (input.code.trim() === "") return "套餐编码必填";
  if (input.name.trim() === "") return "套餐名称必填";
  if (input.priceText.trim() === "") return "套餐价格必填";
  if (parseMoneyText(input.priceText) === null) return "套餐价格必须是非负数字且最多 2 位小数";
  if (input.items.length === 0) return "请至少填写一条套餐明细";
  for (const [index, item] of input.items.entries()) {
    if (item.item_code.trim() === "") return `第 ${index + 1} 条明细缺少项目编码`;
    if (item.item_name.trim() === "") return `第 ${index + 1} 条明细缺少项目名称`;
    if (!(Number.isFinite(item.quantity) && item.quantity > 0)) return `第 ${index + 1} 条明细数量必须为正数`;
  }
  return null;
}

/** 订阅表单校验：儿童与套餐必选。 */
export function validateSubscriptionForm(input: {
  patientId: string;
  packageId: string;
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.packageId.trim() === "") return "请选择套餐";
  return null;
}

/**
 * 结算单表单校验：儿童必选、至少一条明细、明细字段合法。
 * **金额不入参、不校验、不计算**（由服务端按单价 × 数量派生）。
 */
export function validateSettlementForm(input: {
  patientId: string;
  items: readonly SettlementItemDraft[];
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.items.length === 0) return "请至少填写一条结算明细";
  for (const [index, item] of input.items.entries()) {
    if (item.item_code.trim() === "") return `第 ${index + 1} 条明细缺少项目编码`;
    if (item.item_name.trim() === "") return `第 ${index + 1} 条明细缺少项目名称`;
    if (parseMoneyText(item.unit_price) === null) return `第 ${index + 1} 条明细单价必须是非负数字且最多 2 位小数`;
    if (parseQuantityText(item.quantity) === null) return `第 ${index + 1} 条明细数量必须为正数且最多 3 位小数`;
  }
  return null;
}

/** 结算明细草稿 → 提交体（数值化；金额仍由服务端计算）。 */
export function toSettlementItems(
  items: readonly SettlementItemDraft[],
): ChildServiceSettlementItemInput[] {
  return items.map((item) => ({
    item_code: item.item_code.trim(),
    item_name: item.item_name.trim(),
    unit_price: parseMoneyText(item.unit_price) ?? 0,
    quantity: parseQuantityText(item.quantity) ?? 0,
  }));
}

/** 收款表单校验：金额为正且 ≤2 位小数、收款方式白名单。 */
export function validatePaymentForm(input: {
  amountText: string;
  method: string;
}): string | null {
  const amount = parseMoneyText(input.amountText);
  if (amount === null) return "收款金额必须是非负数字且最多 2 位小数";
  if (amount <= 0) return "收款金额必须大于 0";
  if (!PAYMENT_METHODS.includes(input.method.trim() as PaymentMethod)) {
    return `收款方式必须是 ${PAYMENT_METHODS.join(" / ")}`;
  }
  return null;
}

/**
 * 发票表单校验：儿童与抬头必填；结算单/订阅**互斥**；
 * 关联单据时**不得**再填金额（服务端派生）；两者都不给时必须给正数金额；票号 ≤64 字符。
 */
export function validateInvoiceForm(input: {
  patientId: string;
  title: string;
  invoiceNo: string;
  amountText: string;
  settlementId: string;
  subscriptionId: string;
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.title.trim() === "") return "发票抬头必填";
  const settlementId = input.settlementId.trim();
  const subscriptionId = input.subscriptionId.trim();
  if (settlementId !== "" && subscriptionId !== "") {
    return "结算单与套餐订阅只能关联其一";
  }
  const amountText = input.amountText.trim();
  if (settlementId !== "" || subscriptionId !== "") {
    if (amountText !== "") return "关联结算单/订阅时金额由服务端派生，请勿填写金额";
  } else {
    if (amountText === "") return "未关联单据时必须填写金额";
    const amount = parseMoneyText(amountText);
    if (amount === null) return "金额必须是非负数字且最多 2 位小数";
    if (amount <= 0) return "金额必须大于 0";
  }
  const invoiceNo = input.invoiceNo.trim();
  if (invoiceNo.length > 64) return "票号最多 64 个字符";
  return null;
}
