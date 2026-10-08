/**
 * 康复师工作台展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 B5）：
 * - **权威在后端**：周期/计划/打卡/里程碑/手册的持久化与派生都在 B1–B4 服务端
 *   （`ChildRehabPeriodService` / `ChildRehabPlanService` / `ChildRehabSessionService` /
 *   `ChildRehabMilestoneService` / `ChildRehabHandoutService`）；本文件只做
 *   **枚举镜像、状态机镜像、表单校验与展示格式化**，不重算服务端口径。
 * - 里程碑四态（未达/临近/已达成/逾期）是**读时派生**（后端 `ChildRehabMilestone.stateOf`），
 *   本文件只负责排序/计数/配色，不自行判定状态。
 * - 进度 `progress_ratio = 完成天数 ÷ 计划天数`，**不封顶**（可能 > 1）——展示时不得截断，
 *   只有进度条宽度才做 0–100 截断。
 * - 周期状态沿用既有英文枚举 `ACTIVE/SUSPENDED/COMPLETED/CANCELLED`，前端只做中文展示映射，
 *   **不新增英文 code**（AGENTS：业务枚举遵循既有值）。
 */

import type {
  ChildRehabHandout,
  ChildRehabMilestoneStatus,
  ChildRehabMilestoneStatusItem,
  ChildRehabPlanItemInput,
  ChildRehabProgress,
} from "@pitchfork/shared/aceso";

// ─── 周期状态（镜像后端 STATUSES / TRANSITIONS）──────────────────────────────

/** 周期状态白名单（后端 `ChildRehabPeriodService.STATUSES`）。 */
export const PERIOD_STATUSES = ["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED"] as const;
export type PeriodStatus = (typeof PERIOD_STATUSES)[number];

/** 状态中文展示映射（仅展示，不参与写入；写入一律用英文 code）。 */
export const PERIOD_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "进行中",
  SUSPENDED: "已暂停",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};

/** 状态中文名；未知状态原样返回（不猜测）。 */
export function periodStatusLabel(status: string | null | undefined): string {
  const key = (status ?? "").trim();
  if (key === "") return "-";
  return PERIOD_STATUS_LABELS[key] ?? key;
}

/**
 * 状态机（后端 `TRANSITIONS` 逐字镜像）：
 * `ACTIVE ⇄ SUSPENDED`，二者 → `COMPLETED / CANCELLED`；终态不可再流转。
 */
export const PERIOD_TRANSITIONS: Record<string, readonly PeriodStatus[]> = {
  ACTIVE: ["SUSPENDED", "COMPLETED", "CANCELLED"],
  SUSPENDED: ["ACTIVE", "COMPLETED", "CANCELLED"],
  COMPLETED: [],
  CANCELLED: [],
};

/** 当前状态允许的目标状态；未知状态返回空数组（不可流转）。 */
export function allowedPeriodTargets(current: string | null | undefined): PeriodStatus[] {
  const key = (current ?? "").trim();
  return [...(PERIOD_TRANSITIONS[key] ?? [])];
}

/** 合法流转判定（服务端仍会再校验；重新启用命中部分唯一索引会 409）。 */
export function canTransitionPeriod(
  from: string | null | undefined,
  to: string | null | undefined,
): boolean {
  const target = (to ?? "").trim();
  if (target === "") return false;
  return allowedPeriodTargets(from).some((status) => status === target);
}

/** 终态（已完成/已取消）不可再流转。 */
export function isPeriodTerminal(status: string | null | undefined): boolean {
  const key = (status ?? "").trim();
  return key === "COMPLETED" || key === "CANCELLED";
}

/** 周期状态徽章配色（`@pitchfork/ui` 的 `Badge` variant）。 */
export function periodStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "ACTIVE":
      return "success";
    case "SUSPENDED":
      return "warning";
    case "COMPLETED":
      return "info";
    case "CANCELLED":
      return "default";
    default:
      return "default";
  }
}

// ─── 里程碑四态（读时派生结果只做展示派生）──────────────────────────────────

/** 四态白名单（后端 `ChildRehabMilestone.STATES`）。 */
export const MILESTONE_STATES = ["未达", "临近", "已达成", "逾期"] as const;
export type MilestoneState = (typeof MILESTONE_STATES)[number];

/** 四态排序权重：**问题项在前**（逾期 → 临近 → 未达 → 已达成）。 */
export const MILESTONE_STATE_ORDER: Record<string, number> = {
  逾期: 0,
  临近: 1,
  未达: 2,
  已达成: 3,
};

/** 状态排序权重；未知状态排最后（不隐藏、不丢弃）。 */
export function milestoneStateRank(state: string | null | undefined): number {
  const key = (state ?? "").trim();
  return MILESTONE_STATE_ORDER[key] ?? 99;
}

/** 状态徽章配色：逾期=危险、临近=警告、已达成=成功、未达=中性。 */
export function milestoneStateVariant(
  state: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((state ?? "").trim()) {
    case "逾期":
      return "danger";
    case "临近":
      return "warning";
    case "已达成":
      return "success";
    case "未达":
      return "default";
    default:
      return "info";
  }
}

/**
 * 四态视图排序：先按状态权重，再按期望月龄升序（无期望月龄排最后），最后按 code。
 * 稳定且不修改入参。
 */
export function sortMilestoneItems(
  items: readonly ChildRehabMilestoneStatusItem[],
): ChildRehabMilestoneStatusItem[] {
  return [...items].sort((a, b) => {
    const rank = milestoneStateRank(a.state) - milestoneStateRank(b.state);
    if (rank !== 0) return rank;
    const expectedA = a.expected_age_months ?? Number.MAX_SAFE_INTEGER;
    const expectedB = b.expected_age_months ?? Number.MAX_SAFE_INTEGER;
    if (expectedA !== expectedB) return expectedA - expectedB;
    return a.milestone_code.localeCompare(b.milestone_code);
  });
}

/** 四态计数（缺失状态不计入；返回全部四态的键，便于固定展示）。 */
export function milestoneSummary(
  items: readonly ChildRehabMilestoneStatusItem[],
): Record<MilestoneState, number> {
  const summary: Record<MilestoneState, number> = { 未达: 0, 临近: 0, 已达成: 0, 逾期: 0 };
  for (const item of items) {
    const state = (item.state ?? "").trim() as MilestoneState;
    if (state in summary) summary[state] += 1;
  }
  return summary;
}

/** 里程碑视图摘要：`12 月龄 · 截至 2026-10-06 · 逾期 1 / 临近 2 / 未达 5 / 已达成 3`。 */
export function milestoneHeadline(status: ChildRehabMilestoneStatus): string {
  const summary = milestoneSummary(status.items ?? []);
  return (
    `${status.age_months} 月龄 · 截至 ${status.as_of} · ` +
    `逾期 ${summary.逾期} / 临近 ${summary.临近} / 未达 ${summary.未达} / 已达成 ${summary.已达成}`
  );
}

/** 期望月龄展示：`期望 12 月龄` / `无期望月龄`。 */
export function milestoneExpectedLabel(item: ChildRehabMilestoneStatusItem): string {
  const expected = item.expected_age_months;
  return expected === null || expected === undefined ? "无期望月龄" : `期望 ${expected} 月龄`;
}

/** 达成情况展示：`已达成 2026-09-01` / `未达成`。 */
export function milestoneAchievedLabel(item: ChildRehabMilestoneStatusItem): string {
  const achieved = (item.achieved_at ?? "").trim();
  return achieved === "" ? "未达成" : `已达成 ${achieved}`;
}

// ─── 进度（不封顶）─────────────────────────────────────────────────────────

/** 百分比展示：`0.4 → 40%`、`1.2 → 120%`（**不封顶**）、非法值 `-`。 */
export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return `${Math.round(value * 100)}%`;
}

/** 进度条宽度百分比（0–100 截断，仅用于展示；不改变真实完成率文案）。 */
export function progressBarPercent(value: number | null | undefined): number {
  if (value === null || value === undefined || !Number.isFinite(value) || value <= 0) return 0;
  return Math.min(100, Math.round(value * 10000) / 100);
}

/** 进度摘要：`已完成 3/10 天 · 完成率 30%`；计划缺起止日期时明确说明。 */
export function progressHeadline(progress: ChildRehabProgress): string {
  const planned = progress.planned_days;
  if (planned === null || planned === undefined || planned <= 0) {
    return `已完成 ${progress.completed_days} 天 · 计划未设起止日期，无法计算完成率`;
  }
  return `已完成 ${progress.completed_days}/${planned} 天 · 完成率 ${formatPercent(progress.progress_ratio)}`;
}

/** 进度口径说明（完成率可能超过 100%，不封顶）。 */
export function progressNotice(progress: ChildRehabProgress): string {
  const extra =
    progress.progress_ratio > 1
      ? "；完成天数已超过计划天数（完成率不封顶）"
      : "";
  return (
    `打卡 ${progress.session_count} 次 · 平均完成度 ${formatPercent(progress.avg_completion_ratio)}` +
    ` · 最近打卡 ${progress.last_session_date ?? "-"}${extra}`
  );
}

// ─── 手册分发与签收 ────────────────────────────────────────────────────────

/** 签收状态（后端由 `acknowledged_at` 派生）。 */
export const HANDOUT_STATUSES = ["未签收", "已签收"] as const;
export type HandoutStatus = (typeof HANDOUT_STATUSES)[number];

/** 渠道建议值（后端为自由文本，缺省「现场」）。 */
export const HANDOUT_CHANNEL_SUGGESTIONS = ["现场", "微信", "纸质"] as const;

/** 签收状态徽章配色。 */
export function handoutStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  return (status ?? "").trim() === "已签收" ? "success" : "warning";
}

/** 是否可签收：**事实源是 `acknowledged_at`**（非空即已签收，单向不可回退）。 */
export function canAcknowledgeHandout(handout: Pick<ChildRehabHandout, "acknowledged_at">): boolean {
  return (handout.acknowledged_at ?? "").trim() === "";
}

// ─── 表单校验（与后端同口径）───────────────────────────────────────────────

/** ISO 日期（yyyy-MM-dd）且真实存在。 */
export function isIsoDate(value: string | null | undefined): boolean {
  const text = (value ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text;
}

/** 完成度输入 → 数字；空串/非法返回 `null`。 */
export function parseRatioInput(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** 周期表单校验：儿童必选、开始日期必填且合法、结束日期不得早于开始。 */
export function validatePeriodForm(input: {
  patientId: string;
  startDate: string;
  endDate: string;
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (!isIsoDate(input.startDate)) return "开始日期必填且必须是合法日期";
  const end = input.endDate.trim();
  if (end !== "" && !isIsoDate(end)) return "结束日期必须是合法日期";
  if (end !== "" && end < input.startDate) return "结束日期不得早于开始日期";
  return null;
}

/** 计划明细规范化：去空白、丢弃空动作、`duration_days` 只保留正整数。 */
export function normalizePlanItems(items: readonly ChildRehabPlanItemInput[]): ChildRehabPlanItemInput[] {
  const normalized: ChildRehabPlanItemInput[] = [];
  for (const item of items) {
    const action = item.action.trim();
    if (action === "") continue;
    const entry: ChildRehabPlanItemInput = { action };
    const frequencyCode = item.frequency_code?.trim();
    if (frequencyCode) entry.frequency_code = frequencyCode;
    const frequencyName = item.frequency_name?.trim();
    if (frequencyName) entry.frequency_name = frequencyName;
    const duration = item.duration_days;
    if (typeof duration === "number" && Number.isInteger(duration) && duration > 0) {
      entry.duration_days = duration;
    }
    const remark = item.remark?.trim();
    if (remark) entry.remark = remark;
    normalized.push(entry);
  }
  return normalized;
}

/** 计划表单校验：周期必选、计划名必填、至少一条动作明细。 */
export function validatePlanForm(input: {
  periodId: string;
  planName: string;
  items: readonly ChildRehabPlanItemInput[];
}): string | null {
  if (input.periodId.trim() === "") return "请先选择康复周期";
  if (input.planName.trim() === "") return "计划名称必填";
  if (normalizePlanItems(input.items).length === 0) return "请至少填写一条训练动作明细";
  return null;
}

/** 打卡表单校验：计划必选、日期必填合法、完成度必须在 (0,1]。 */
export function validateSessionForm(input: {
  planId: string;
  sessionDate: string;
  completionRatio: number | null;
}): string | null {
  if (input.planId.trim() === "") return "请先选择训练计划";
  if (!isIsoDate(input.sessionDate)) return "训练日期必填且必须是合法日期";
  const ratio = input.completionRatio;
  if (ratio === null) return "完成度必须是 0–1 之间的数字";
  if (!(ratio > 0 && ratio <= 1)) return "完成度必须大于 0 且不超过 1";
  return null;
}

/** 里程碑登记校验：儿童必选、里程碑必选、达成日期（若填）必须合法。 */
export function validateMilestoneForm(input: {
  patientId: string;
  milestoneCode: string;
  achievedAt: string;
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.milestoneCode.trim() === "") return "请选择里程碑";
  const achieved = input.achievedAt.trim();
  if (achieved !== "" && !isIsoDate(achieved)) return "达成日期必须是合法日期";
  return null;
}

/** 手册分发校验：儿童必选、模板必选。 */
export function validateHandoutForm(input: { patientId: string; templateCode: string }): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.templateCode.trim() === "") return "请选择指导手册模板";
  return null;
}

// ─── 计划明细编辑（纯函数，不可变）─────────────────────────────────────────

/** 空白明细行。 */
export function emptyPlanItem(): ChildRehabPlanItemInput {
  return { action: "" };
}

/** 追加一行明细（返回新数组，不修改入参）。 */
export function addPlanItem(
  items: readonly ChildRehabPlanItemInput[],
  item: ChildRehabPlanItemInput = emptyPlanItem(),
): ChildRehabPlanItemInput[] {
  return [...items, { ...item }];
}

/** 修改第 index 行（越界返回原样副本）。 */
export function updatePlanItem(
  items: readonly ChildRehabPlanItemInput[],
  index: number,
  patch: Partial<ChildRehabPlanItemInput>,
): ChildRehabPlanItemInput[] {
  if (index < 0 || index >= items.length) return items.map((item) => ({ ...item }));
  return items.map((item, current) => (current === index ? { ...item, ...patch } : { ...item }));
}

/** 删除第 index 行（越界返回原样副本）。 */
export function removePlanItem(
  items: readonly ChildRehabPlanItemInput[],
  index: number,
): ChildRehabPlanItemInput[] {
  return items.filter((_, current) => current !== index).map((item) => ({ ...item }));
}
