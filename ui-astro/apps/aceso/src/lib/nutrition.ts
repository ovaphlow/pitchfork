/**
 * 营养师工作台展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 A6）：
 * - **权威在后端**：营养汇总、结论、缺口项、参考值全部由 A1–A5 服务端派生
 *   （`MealIntakeService` / `NutritionAnalysis` / `SpecialDietRequestService` /
 *   `NutritionWeeklyReportService`）；本文件**不重算**营养素，只做输入校验、
 *   枚举白名单镜像与展示格式化，避免前后端两套口径漂移。
 * - 状态机/白名单与后端**逐字一致**（`SpecialDietRequestService.TRANSITIONS`、
 *   `DiningConstants.MEAL_TIMES`）；前端据此只渲染合法流转按钮，非法流转仍由
 *   服务端 400 兜底（前端不做鉴权，也不替代服务端校验）。
 * - 参考值是**决策支持提示**，不是诊断/处方依据（054 §12 R5）：展示时必须同时
 *   标注 `reference_version` 口径版本。
 */

import type {
  MealIntakeItem,
  NutritionAnalysis,
  NutritionWeeklyReport,
  SpecialDietRequest,
} from "@pitchfork/shared/aceso";

// ─── 枚举白名单（镜像后端，逐字一致）────────────────────────────────────────

/** 餐次（后端 `DiningConstants.MEAL_TIMES`）。 */
export const MEAL_TIMES = ["早餐", "午餐", "晚餐", "加餐"] as const;
export type MealTime = (typeof MEAL_TIMES)[number];

/** 特殊膳食申请类型（后端 `SpecialDietRequestService.REQUEST_TYPES`）。 */
export const SPECIAL_DIET_REQUEST_TYPES = ["过敏回避", "治疗膳食", "其他"] as const;
export type SpecialDietRequestType = (typeof SPECIAL_DIET_REQUEST_TYPES)[number];

/** 特殊膳食申请状态（后端 `SpecialDietRequestService.STATUSES`）。 */
export const SPECIAL_DIET_STATUSES = ["待审批", "已批准", "已驳回", "已终止"] as const;
export type SpecialDietStatus = (typeof SPECIAL_DIET_STATUSES)[number];

/**
 * 状态机（后端 `TRANSITIONS` 逐字镜像）：
 * `待审批 → 已批准 / 已驳回 / 已终止`；`已批准 / 已驳回 → 已终止`；`已终止` 为终态。
 */
export const SPECIAL_DIET_TRANSITIONS: Record<string, readonly SpecialDietStatus[]> = {
  待审批: ["已批准", "已驳回", "已终止"],
  已批准: ["已终止"],
  已驳回: ["已终止"],
  已终止: [],
};

/** 当前状态允许的目标状态；未知状态返回空数组（不可流转）。 */
export function allowedSpecialDietTargets(current: string | null | undefined): SpecialDietStatus[] {
  const key = (current ?? "").trim();
  return [...(SPECIAL_DIET_TRANSITIONS[key] ?? [])];
}

/** 合法流转判定（与后端 `canTransition` 同口径；服务端仍会再校验一次）。 */
export function canTransitionSpecialDiet(
  from: string | null | undefined,
  to: string | null | undefined,
): boolean {
  const target = (to ?? "").trim();
  if (target === "") return false;
  return allowedSpecialDietTargets(from).some((status) => status === target);
}

/** 终态（`已终止`）不可再流转。 */
export function isSpecialDietTerminal(status: string | null | undefined): boolean {
  return (status ?? "").trim() === "已终止";
}

/** 状态徽章配色（`@pitchfork/ui` 的 `Badge` variant）。 */
export function specialDietStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((status ?? "").trim()) {
    case "已批准":
      return "success";
    case "已驳回":
      return "danger";
    case "已终止":
      return "default";
    case "待审批":
      return "warning";
    default:
      return "info";
  }
}

// ─── 摄入登记表单（食物项 + 份量 + 摄入比例）────────────────────────────────

/** 空白食物项（份量为 0，提交前必须 > 0）。 */
export function emptyFoodItem(): MealIntakeItem {
  return { food_name: "", amount_g: 0 };
}

/** 追加一行食物项（返回新数组，不修改入参）。 */
export function addFoodItem(
  items: readonly MealIntakeItem[],
  item: MealIntakeItem = emptyFoodItem(),
): MealIntakeItem[] {
  return [...items, { ...item }];
}

/** 修改第 index 行的部分字段（返回新数组；越界返回原样副本）。 */
export function updateFoodItem(
  items: readonly MealIntakeItem[],
  index: number,
  patch: Partial<MealIntakeItem>,
): MealIntakeItem[] {
  if (index < 0 || index >= items.length) return items.map((item) => ({ ...item }));
  return items.map((item, current) => (current === index ? { ...item, ...patch } : { ...item }));
}

/** 删除第 index 行（返回新数组；越界返回原样副本）。 */
export function removeFoodItem(items: readonly MealIntakeItem[], index: number): MealIntakeItem[] {
  return items.filter((_, current) => current !== index).map((item) => ({ ...item }));
}

/** 份量合计（仅用于展示；营养汇总以服务端为准）。 */
export function totalAmountG(items: readonly MealIntakeItem[]): number {
  return items.reduce((sum, item) => {
    const amount = typeof item.amount_g === "number" && Number.isFinite(item.amount_g) ? item.amount_g : 0;
    return sum + amount;
  }, 0);
}

/**
 * 提交前校验（返回中文错误文案；`null` = 通过）。
 *
 * 与后端 `MealIntakeService.normalizeItems` / `parseRatio` 同口径：
 * 至少一项、食物名非空、份量为正数、比例为 (0,1]。
 */
export function validateIntakeForm(
  items: readonly MealIntakeItem[],
  intakeRatio: number | null,
): string | null {
  const named = items.filter((item) => item.food_name.trim() !== "");
  if (named.length === 0) return "请至少填写一项食物（食物名称必填）";
  const invalid = named.find(
    (item) => !Number.isFinite(item.amount_g) || item.amount_g <= 0,
  );
  if (invalid) return `食物「${invalid.food_name.trim()}」的份量必须是正数（克）`;
  if (intakeRatio === null) return "摄入比例必须是 0–1 之间的数字";
  if (!(intakeRatio > 0 && intakeRatio <= 1)) return "摄入比例必须大于 0 且不超过 1";
  return null;
}

/** 规范化食物项：去空白、丢弃空食物名、备注去空白后省略。 */
export function normalizeFoodItems(items: readonly MealIntakeItem[]): MealIntakeItem[] {
  const normalized: MealIntakeItem[] = [];
  for (const item of items) {
    const foodName = item.food_name.trim();
    if (foodName === "") continue;
    const entry: MealIntakeItem = { food_name: foodName, amount_g: item.amount_g };
    const note = item.note?.trim();
    if (note) entry.note = note;
    normalized.push(entry);
  }
  return normalized;
}

/** 摄入比例输入框 → 数字；空串/非法返回 `null`（提交前由校验拦截）。 */
export function parseRatioInput(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** 摄入比例展示：`1 → 100%`、`0.6 → 60%`（保留整数百分比，四舍五入）。 */
export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return `${Math.round(value * 100)}%`;
}

/** 按小时给出默认餐次（页面打开时预选，用户可改）。 */
export function defaultMealTime(hour: number): MealTime {
  if (!Number.isFinite(hour) || hour < 0 || hour >= 24) return "早餐";
  if (hour < 10) return "早餐";
  if (hour < 14) return "午餐";
  if (hour < 20) return "晚餐";
  return "加餐";
}

// ─── 过敏原文本 ────────────────────────────────────────────────────────────

/**
 * 过敏原输入 → 字符串数组：半/全角逗号、顿号、换行、分号分隔，去空白去重。
 * 与后端 `SpecialDietRequestService.allergenList` / `DietProfileService.allergiesOf`
 * 的字符串口径一致（后端另接受 JSON 数组）。
 */
export function parseAllergenText(text: string): string[] {
  const parts = text.split(/[,，、;；\n\r]+/);
  const seen = new Set<string>();
  const result: string[] = [];
  for (const part of parts) {
    const value = part.trim();
    if (value === "" || seen.has(value)) continue;
    seen.add(value);
    result.push(value);
  }
  return result;
}

/** 过敏原数组 → 展示文本（顿号分隔；空数组显示「无」）。 */
export function formatAllergens(list: readonly string[] | null | undefined): string {
  const values = (list ?? []).map((item) => item.trim()).filter((item) => item !== "");
  return values.length === 0 ? "无" : values.join("、");
}

// ─── 过敏原覆盖式写入（054 A4b，按 patient）─────────────────────────────────

/**
 * 覆盖式写入提示：**整体替换，不是追加**；空列表时明确「将清空」。
 *
 * 语义与后端 `DietProfileService.upsertAllergensByPatient` 一致：请求必须显式带
 * `allergies` 键（缺键 400），传空数组 = 清空该档案的全部过敏原标记。
 */
export function allergenOverwriteNotice(allergens: readonly string[]): string {
  const count = allergens.length;
  if (count === 0) return "保存空列表将清空该档案的全部过敏原标记（覆盖式写入：整体替换，非追加）";
  return `覆盖式写入：整体替换为 ${count} 项过敏原，不是追加`;
}

/** 保存成功提示（回显服务端返回的过敏原）。 */
export function allergenSavedHint(allergens: readonly string[]): string {
  return `已覆盖写入过敏原：${formatAllergens(allergens)}（整体替换，非追加）`;
}

/**
 * 过敏原区块的状态说明（按档案数与是否儿童分流）。
 *
 * - 已有档案：说明写入目标是其启用档案；
 * - 儿童无档案：A4b 允许无入住建档，保存时自动 upsert 最小启用档案；
 * - 非儿童无档案：必须先建档（需在院入住），保存会返回 400——服务端文案仍原样透出。
 */
export function allergenProfileNotice(profileCount: number, isChild: boolean): string {
  if (profileCount > 0) return `已关联 ${profileCount} 份饮食档案；写入会覆盖其启用档案的过敏原`;
  return isChild
    ? "该儿童尚无饮食档案；保存后将自动建立最小启用档案（无入住）并写入过敏原"
    : "尚无启用饮食档案；非儿童必须先建档（需在院入住），否则服务端返回 400";
}

// ─── 营养分析展示 ──────────────────────────────────────────────────────────

export const NUTRIENT_ROWS = [
  { key: "energy_kcal", label: "热量", unit: "kcal" },
  { key: "protein_g", label: "蛋白质", unit: "g" },
  { key: "fat_g", label: "脂肪", unit: "g" },
  { key: "carb_g", label: "碳水", unit: "g" },
  { key: "sodium_mg", label: "钠", unit: "mg" },
] as const;

export type NutrientKey = (typeof NUTRIENT_ROWS)[number]["key"];

export interface NutrientRow {
  key: NutrientKey;
  label: string;
  unit: string;
  /** 当日实际摄入（服务端汇总） */
  value: number;
  /** 该月龄段每日参考值（内置常量口径） */
  reference: number;
  /** 实际 / 参考（保留两位；参考为 0 时 null） */
  ratio: number | null;
  /** 描述性对比（**不是**临床判定，结论文案以服务端为准） */
  verdict: NutrientVerdict;
}

export type NutrientVerdict = "达到参考" | "低于参考" | "无参考";

/** 实际值相对参考值的描述性对比。 */
export function nutrientVerdict(value: number, reference: number): NutrientVerdict {
  if (!Number.isFinite(reference) || reference <= 0) return "无参考";
  return value >= reference ? "达到参考" : "低于参考";
}

/** 实际 / 参考；参考为 0 或非法返回 null。 */
export function nutrientRatio(value: number, reference: number): number | null {
  if (!Number.isFinite(reference) || reference <= 0) return null;
  if (!Number.isFinite(value)) return null;
  return Math.round((value / reference) * 100) / 100;
}

/** 分析结果的五项「实际 / 参考」行（顺序固定，便于表格与测试断言）。 */
export function nutrientRows(analysis: NutritionAnalysis): NutrientRow[] {
  return NUTRIENT_ROWS.map((row) => {
    const value = analysis[row.key];
    const reference = analysis.reference[row.key];
    return {
      key: row.key,
      label: row.label,
      unit: row.unit,
      value,
      reference,
      ratio: nutrientRatio(value, reference),
      verdict: nutrientVerdict(value, reference),
    };
  });
}

/** 数值展示：最多两位小数（去掉无意义的尾零），非法值显示 `-`。 */
export function formatNutrientValue(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return String(Number(value.toFixed(2)));
}

/** 带单位展示：`116.5 kcal`。 */
export function formatNutrientWithUnit(value: number | null | undefined, unit: string): string {
  const text = formatNutrientValue(value);
  return text === "-" ? text : `${text} ${unit}`;
}

/** 年龄段口径标注：`36 月 · 4–6 岁`。 */
export function formatAgeBand(analysis: NutritionAnalysis): string {
  const label = analysis.band?.label?.trim();
  return label ? `${analysis.age_months} 月 · ${label}` : `${analysis.age_months} 月`;
}

/**
 * 参考值口径标注：必须与分析结论一起展示（054 §12 R5：常量版本化、不得当作医学结论）。
 * 版本缺失时明确显示「未标注」，不假装有口径。
 */
export function referenceVersionLabel(version: string | null | undefined): string {
  const value = (version ?? "").trim();
  return value === "" ? "参考值口径未标注" : `参考值口径 ${value}`;
}

/** 成分匹配概览：命中/未命中食物名（未命中计 0，不是错误）。 */
export function compositionSummary(analysis: NutritionAnalysis): {
  matched: number;
  unmatched: string[];
} {
  const items = analysis.items ?? [];
  return {
    matched: items.filter((item) => item.matched).length,
    unmatched: [...(analysis.unmatched ?? [])],
  };
}

// ─── 营养周报展示 ──────────────────────────────────────────────────────────

/** 周界展示：`2026-10-05 ~ 2026-10-11`。 */
export function weekRangeLabel(report: NutritionWeeklyReport): string {
  return `${report.week_start} ~ ${report.week_end}`;
}

/** 周报均值行（口径：**按有摄入记录的天数**平均，见后端 `average`）。 */
export function weeklyAverageRows(report: NutritionWeeklyReport): Array<{
  key: NutrientKey;
  label: string;
  unit: string;
  value: number;
}> {
  const values: Record<NutrientKey, number> = {
    energy_kcal: report.energy_avg_kcal,
    protein_g: report.protein_avg_g,
    fat_g: report.fat_avg_g,
    carb_g: report.carb_avg_g,
    sodium_mg: report.sodium_avg_mg,
  };
  return NUTRIENT_ROWS.map((row) => ({ ...row, value: values[row.key] }));
}

/** 周报摘要：`5 天有记录 · 日均 1200 kcal`（0 天时明确「无摄入记录」）。 */
export function weeklyHeadline(report: NutritionWeeklyReport): string {
  if (report.intake_days <= 0) return "当周无摄入记录（快照仍生成）";
  return `${report.intake_days} 天有记录 · 日均 ${formatNutrientValue(report.energy_avg_kcal)} kcal`;
}

/** 逐日明细里最大热量（用于柱状宽度归一；无数据返回 0，调用方按 0 兜底）。 */
export function maxDailyEnergy(report: NutritionWeeklyReport): number {
  const values = (report.daily ?? []).map((entry) => entry.energy_kcal);
  return values.length === 0 ? 0 : Math.max(...values);
}

/** 逐日柱宽百分比（0–100，两位小数；最大值为 0 时全 0，避免除零）。 */
export function dailyEnergyPercent(value: number, max: number): number {
  if (!Number.isFinite(max) || max <= 0) return 0;
  if (!Number.isFinite(value) || value <= 0) return 0;
  return Math.round((value / max) * 10000) / 100;
}

// ─── 申请单展示 ────────────────────────────────────────────────────────────

/** 申请单摘要：`过敏回避 · 午餐 · 待审批`。 */
export function specialDietHeadline(request: SpecialDietRequest): string {
  return `${request.request_type} · ${request.meal_type} · ${request.status}`;
}
