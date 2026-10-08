/**
 * 保育员工作台展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 C3）：
 * - **权威在后端**：活动日志/行为观测/班级日程/安全巡查的持久化与派生都在 C1/C2 服务端；
 *   本文件只做**常量镜像、状态机镜像、表单校验与展示格式化**，不重算服务端口径。
 * - **常量镜像**（后端无查询端点，只能镜像；改动必须同步，`childcare.test.ts` 有漂移守卫）：
 *   `ChildcareClassScheduleService.TIME_SLOTS`（8 个固定时段）、
 *   `ChildcareSafetyInspectionService.CHECK_ITEMS`（12 项检查项）/ `RESULTS`、
 *   `ChildcareBehaviorObservationService.LEVELS`、两处 `STATUSES`/`TRANSITIONS`。
 * - 行为观测是 **append-only**（只新增、不可改，响应无 `updated_at`）。
 * - 状态流转按钮只渲染合法目标；非法流转（同时段冲突、跳步关闭）仍由服务端 409 兜底，
 *   其文案必须原样透出。
 */

import type {
  ChildcareBehaviorObservation,
  ChildcareSafetyInspection,
  ChildcareSafetyInspectionItem,
} from "@pitchfork/shared/aceso";

// ─── 行为观测级别（镜像后端 LEVELS）─────────────────────────────────────────

/** 级别白名单（后端 `ChildcareBehaviorObservationService.LEVELS`）。 */
export const BEHAVIOR_LEVELS = ["一般", "关注", "需干预"] as const;
export type BehaviorLevel = (typeof BEHAVIOR_LEVELS)[number];

/** 级别排序权重：需干预在前（问题项优先）。 */
export const BEHAVIOR_LEVEL_ORDER: Record<string, number> = { 需干预: 0, 关注: 1, 一般: 2 };

/** 级别排序权重；未知/空值排最后（不隐藏、不丢弃）。 */
export function behaviorLevelRank(level: string | null | undefined): number {
  const key = (level ?? "").trim();
  if (key === "") return 50; // 未填级别：排在已知问题之后、已知「一般」之后
  return BEHAVIOR_LEVEL_ORDER[key] ?? 99;
}

/** 级别徽章配色：需干预=danger、关注=warning、一般=default、未填=info。 */
export function behaviorLevelVariant(
  level: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((level ?? "").trim()) {
    case "需干预":
      return "danger";
    case "关注":
      return "warning";
    case "一般":
      return "default";
    case "":
      return "info";
    default:
      return "default";
  }
}

/** 级别展示：空值显示「未填级别」（不猜测）。 */
export function behaviorLevelLabel(level: string | null | undefined): string {
  const key = (level ?? "").trim();
  return key === "" ? "未填级别" : key;
}

/** 观测排序：需干预 → 关注 → 一般 → 未填/未知；同级保持服务端顺序（稳定，不改入参）。 */
export function sortBehaviorObservations(
  observations: readonly ChildcareBehaviorObservation[],
): ChildcareBehaviorObservation[] {
  return [...observations].sort(
    (a, b) => behaviorLevelRank(a.level) - behaviorLevelRank(b.level),
  );
}

/** 级别计数（未知级别计入 `other`，不丢弃）。 */
export function behaviorLevelCount(
  observations: readonly ChildcareBehaviorObservation[],
): { 需干预: number; 关注: number; 一般: number; other: number; total: number } {
  const summary = { 需干预: 0, 关注: 0, 一般: 0, other: 0, total: observations.length };
  for (const observation of observations) {
    const level = (observation.level ?? "").trim();
    if (level === "需干预") summary.需干预 += 1;
    else if (level === "关注") summary.关注 += 1;
    else if (level === "一般") summary.一般 += 1;
    else summary.other += 1;
  }
  return summary;
}

// ─── 班级日程（镜像后端 STATUSES / TRANSITIONS / TIME_SLOTS）─────────────────

/** 日程状态白名单（后端 `ChildcareClassScheduleService.STATUSES`）。 */
export const SCHEDULE_STATUSES = ["草稿", "已发布", "已取消"] as const;
export type ScheduleStatus = (typeof SCHEDULE_STATUSES)[number];

/** 状态机（后端 `TRANSITIONS` 逐字镜像）：草稿→{已发布,已取消}、已发布→已取消、已取消为终态。 */
export const SCHEDULE_TRANSITIONS: Record<string, readonly ScheduleStatus[]> = {
  草稿: ["已发布", "已取消"],
  已发布: ["已取消"],
  已取消: [],
};

/** 当前状态允许的目标状态；未知状态返回空数组（不可流转）。 */
export function allowedScheduleTargets(current: string | null | undefined): ScheduleStatus[] {
  const key = (current ?? "").trim();
  return [...(SCHEDULE_TRANSITIONS[key] ?? [])];
}

/** 合法流转判定（非法流转由服务端 409 兜底）。 */
export function canTransitionSchedule(
  from: string | null | undefined,
  to: string | null | undefined,
): boolean {
  const target = (to ?? "").trim();
  if (target === "") return false;
  return allowedScheduleTargets(from).some((status) => status === target);
}

/** 终态（已取消）不可再流转。 */
export function isScheduleTerminal(status: string | null | undefined): boolean {
  return (status ?? "").trim() === "已取消";
}

/** 状态徽章配色。 */
export function scheduleStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "已发布":
      return "success";
    case "草稿":
      return "warning";
    case "已取消":
      return "default";
    default:
      return "info";
  }
}

/**
 * 固定时段（后端 `ChildcareClassScheduleService.TIME_SLOTS` 镜像，8 个）。
 * 后端无查询端点，只能镜像；`childcare.test.ts` 会与后端源码比对，漂移即失败。
 */
export const TIME_SLOTS = [
  "08:00-09:00",
  "09:00-10:00",
  "10:00-11:00",
  "11:00-12:00",
  "14:00-15:00",
  "15:00-16:00",
  "16:00-17:00",
  "17:00-18:00",
] as const;

/** 时段是否属于固定白名单（提交前自检；服务端仍会再校验）。 */
export function isValidTimeSlot(slot: string | null | undefined): boolean {
  return TIME_SLOTS.includes((slot ?? "").trim() as (typeof TIME_SLOTS)[number]);
}

// ─── 安全巡查（镜像后端 STATUSES / TRANSITIONS / RESULTS / CHECK_ITEMS）──────

/** 巡查状态白名单（后端 `ChildcareSafetyInspectionService.STATUSES`）。 */
export const INSPECTION_STATUSES = ["待整改", "已整改", "已关闭"] as const;
export type InspectionStatus = (typeof INSPECTION_STATUSES)[number];

/** 隐患闭环状态机（后端 `TRANSITIONS` 逐字镜像）：严格线性。 */
export const INSPECTION_TRANSITIONS: Record<string, readonly InspectionStatus[]> = {
  待整改: ["已整改"],
  已整改: ["已关闭"],
  已关闭: [],
};

/** 当前状态允许的目标状态；未知状态返回空数组。 */
export function allowedInspectionTargets(current: string | null | undefined): InspectionStatus[] {
  const key = (current ?? "").trim();
  return [...(INSPECTION_TRANSITIONS[key] ?? [])];
}

/**
 * 是否可**关闭**隐患：仅「已整改 → 已关闭」。
 * 「待整改」跳步关闭 → 服务端 409（页面据此不渲染关闭按钮，但文案仍由服务端兜底）。
 */
export function canCloseInspection(status: string | null | undefined): boolean {
  return (status ?? "").trim() === "已整改";
}

/** 是否可追加整改项：「已关闭」不可追加（服务端 409）。 */
export function canAppendInspectionItem(status: string | null | undefined): boolean {
  return (status ?? "").trim() !== "已关闭";
}

/** 追加整改项后状态会如何变化（待整改 → 已整改；其余不变）。 */
export function inspectionStatusAfterAppend(status: string | null | undefined): InspectionStatus {
  const key = (status ?? "").trim();
  if (key === "待整改") return "已整改";
  if (key === "已整改" || key === "已关闭") return key;
  return "待整改";
}

/** 巡查状态徽章配色：待整改=danger（未闭环）、已整改=warning、已关闭=success。 */
export function inspectionStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "待整改":
      return "danger";
    case "已整改":
      return "warning";
    case "已关闭":
      return "success";
    default:
      return "info";
  }
}

/** 检查项结果白名单（后端 `RESULTS`）。 */
export const INSPECTION_RESULTS = ["合格", "不合格", "不适用"] as const;
export type InspectionResult = (typeof INSPECTION_RESULTS)[number];

/** 结果徽章配色：不合格=danger、合格=success、不适用=default。 */
export function inspectionResultVariant(
  result: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((result ?? "").trim()) {
    case "合格":
      return "success";
    case "不合格":
      return "danger";
    case "不适用":
      return "default";
    default:
      return "info";
  }
}

/** 检查项常量（后端 `CHECK_ITEMS` 镜像，12 项；`childcare.test.ts` 与后端源码比对）。 */
export const CHECK_ITEMS: ReadonlyArray<{ code: string; name: string }> = [
  { code: "FLOOR_SLIP", name: "地面防滑与积水" },
  { code: "FURNITURE_EDGE", name: "家具棱角防护" },
  { code: "ELECTRIC_SOCKET", name: "电源插座防护" },
  { code: "TOY_SMALL_PARTS", name: "玩具小零件与破损" },
  { code: "WINDOW_GUARD", name: "窗户护栏与限位" },
  { code: "STAIR_GATE", name: "楼梯口安全门" },
  { code: "MEDICINE_STORAGE", name: "药品与消毒剂存放" },
  { code: "FOOD_HYGIENE", name: "食品留样与卫生" },
  { code: "FIRE_EQUIPMENT", name: "消防器材与疏散通道" },
  { code: "OUTDOOR_EQUIPMENT", name: "户外器械稳固与磨损" },
  { code: "WATER_AREA", name: "水域与饮水设备安全" },
  { code: "SANITATION", name: "卫生间清洁与防滑" },
];

/** 检查项 code → 名称；未知 code 原样返回（不隐藏历史数据）。 */
export function checkItemName(code: string | null | undefined): string {
  const key = (code ?? "").trim();
  if (key === "") return "未命名检查项";
  return CHECK_ITEMS.find((item) => item.code === key)?.name ?? key;
}

/** 是否属于 12 项常量表（提交前自检；服务端仍会再校验）。 */
export function isKnownCheckItem(code: string | null | undefined): boolean {
  const key = (code ?? "").trim();
  return CHECK_ITEMS.some((item) => item.code === key);
}

/** 不合格（隐患）项。 */
export function hazardItems(
  items: readonly ChildcareSafetyInspectionItem[] | null | undefined,
): ChildcareSafetyInspectionItem[] {
  return (items ?? []).filter((item) => (item.result ?? "").trim() === "不合格");
}

/** 巡查结果计数（未知结果计入 `other`，不丢弃）。 */
export function inspectionResultCount(
  inspection: Pick<ChildcareSafetyInspection, "items">,
): { 合格: number; 不合格: number; 不适用: number; other: number; total: number } {
  const items = inspection.items ?? [];
  const summary = { 合格: 0, 不合格: 0, 不适用: 0, other: 0, total: items.length };
  for (const item of items) {
    const result = (item.result ?? "").trim();
    if (result === "合格") summary.合格 += 1;
    else if (result === "不合格") summary.不合格 += 1;
    else if (result === "不适用") summary.不适用 += 1;
    else summary.other += 1;
  }
  return summary;
}

/** 巡查摘要：`不合格 2 / 合格 8 / 不适用 1（共 11 项）`。 */
export function inspectionHeadline(
  inspection: Pick<ChildcareSafetyInspection, "items" | "status">,
): string {
  const count = inspectionResultCount(inspection);
  const status = (inspection.status ?? "").trim() || "未知状态";
  return `不合格 ${count.不合格} / 合格 ${count.合格} / 不适用 ${count.不适用}（共 ${count.total} 项）· ${status}`;
}

/** 单条检查项展示：`地面防滑与积水（不合格）· 隐患：…`。 */
export function inspectionItemLabel(item: ChildcareSafetyInspectionItem): string {
  const name = checkItemName(item.item_code);
  const result = (item.result ?? "").trim() || "未填结果";
  const hazard = (item.hazard ?? "").trim();
  return hazard === "" ? `${name}（${result}）` : `${name}（${result}）· 隐患：${hazard}`;
}

// ─── 表单校验（与后端同口径）───────────────────────────────────────────────

/** ISO 日期（yyyy-MM-dd）且真实存在。 */
export function isIsoDate(value: string | null | undefined): boolean {
  const text = (value ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text;
}

/** 参与人数解析：空串 → `null`；非负整数以外 → `null`。 */
export function parseParticipantCount(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || !Number.isInteger(value) || value < 0) return null;
  return value;
}

/** 活动日志校验：班级与活动名称必填、日期（若填）合法、参与人数（若填）为非负整数。 */
export function validateActivityLogForm(input: {
  className: string;
  activityName: string;
  activityDate: string;
  participantCount: string;
}): string | null {
  if (input.className.trim() === "") return "班级必填";
  if (input.activityName.trim() === "") return "活动名称必填";
  if (input.activityDate.trim() !== "" && !isIsoDate(input.activityDate)) return "活动日期必须是合法日期";
  if (input.participantCount.trim() !== "" && parseParticipantCount(input.participantCount) === null) {
    return "参与人数必须是非负整数";
  }
  return null;
}

/** 行为观测校验：儿童、分类、描述必填；级别（若填）白名单；观测时刻（若填）为 ISO 日期/日期时间。 */
export function validateBehaviorObservationForm(input: {
  patientId: string;
  category: string;
  description: string;
  level: string;
  observedAt: string;
}): string | null {
  if (input.patientId.trim() === "") return "请选择儿童";
  if (input.category.trim() === "") return "行为分类必填";
  if (input.description.trim() === "") return "行为描述必填";
  const level = input.level.trim();
  if (level !== "" && !BEHAVIOR_LEVELS.includes(level as BehaviorLevel)) {
    return `级别必须是 ${BEHAVIOR_LEVELS.join(" / ")}`;
  }
  const observedAt = input.observedAt.trim();
  if (observedAt !== "" && !isIsoDate(observedAt) && Number.isNaN(new Date(observedAt).getTime())) {
    return "观测时刻必须是合法日期或日期时间";
  }
  return null;
}

/** 班级日程校验：班级、日期、时段、活动名称必填；时段必须在 8 个固定值内。 */
export function validateClassScheduleForm(input: {
  className: string;
  scheduleDate: string;
  timeSlot: string;
  activityName: string;
}): string | null {
  if (input.className.trim() === "") return "班级必填";
  if (!isIsoDate(input.scheduleDate)) return "日程日期必填且必须是合法日期";
  if (input.timeSlot.trim() === "") return "请选择时段";
  if (!isValidTimeSlot(input.timeSlot)) return "时段必须是固定时段之一";
  if (input.activityName.trim() === "") return "活动名称必填";
  return null;
}

/**
 * 安全巡查建单校验：区域必填；`items` 中每项须有合法 `item_code` 与 `result`
 * （后端允许空 `items` 建单，随后追加；此处不强制至少一项）。
 */
export function validateSafetyInspectionForm(input: {
  area: string;
  items: readonly ChildcareSafetyInspectionItem[];
}): string | null {
  if (input.area.trim() === "") return "巡查区域必填";
  for (const [index, item] of input.items.entries()) {
    const code = (item.item_code ?? "").trim();
    if (code === "") return `第 ${index + 1} 项缺少检查项 code`;
    if (!isKnownCheckItem(code)) return `第 ${index + 1} 项检查项不在常量表内：${code}`;
    const result = (item.result ?? "").trim();
    if (result === "") return `第 ${index + 1} 项缺少检查结果`;
    if (!INSPECTION_RESULTS.includes(result as InspectionResult)) {
      return `第 ${index + 1} 项结果必须是 ${INSPECTION_RESULTS.join(" / ")}`;
    }
  }
  return null;
}

/** 追加整改项校验：检查项必填且在常量表内、结果必填且白名单。 */
export function validateAppendItemForm(input: { itemCode: string; result: string }): string | null {
  const code = input.itemCode.trim();
  if (code === "") return "请选择检查项";
  if (!isKnownCheckItem(code)) return `检查项不在常量表内：${code}`;
  const result = input.result.trim();
  if (result === "") return "请选择检查结果";
  if (!INSPECTION_RESULTS.includes(result as InspectionResult)) {
    return `结果必须是 ${INSPECTION_RESULTS.join(" / ")}`;
  }
  return null;
}

/** 追加项规范化：去空白、隐患为空则省略（与后端 `normalizeItem` 同口径）。 */
export function normalizeInspectionItem(input: {
  itemCode: string;
  result: string;
  hazard: string;
}): ChildcareSafetyInspectionItem {
  const item: ChildcareSafetyInspectionItem = {
    item_code: input.itemCode.trim(),
    result: input.result.trim(),
  };
  const hazard = input.hazard.trim();
  if (hazard !== "") item.hazard = hazard;
  return item;
}

// ─── 日期时间展示 ──────────────────────────────────────────────────────────

/** 当前时段（`datetime-local` 值）→ ISO 日期时间文本；空串返回 `undefined`（由服务端取当前时刻）。 */
export function optionalInstant(value: string): string | undefined {
  const text = value.trim();
  return text === "" ? undefined : text;
}
