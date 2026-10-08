/**
 * 药师工作台展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 D4）：
 * - **权威在后端**：规则的建/查/改与剂量折算、封顶、规则来源都由 D1/D2 服务端
 *   （`PediatricDoseCalculator` / `PediatricDoseRuleService`）派生；本文件只做
 *   白名单镜像、表单校验与展示格式化，**不重算剂量、不自行判定封顶**。
 * - **安全边界（054 §12 R6）**：剂量输出只是「辅助计算 + 上限提示」，
 *   **不写医嘱、不替代药师判断**；`capped` 为真时必须人工复核上限。
 * - **不伪造过敏交叉检查**：本页只只读展示 `patients.allergies`；药物过敏交叉检查
 *   属发药链（053 D3），本文件不实现、不模拟该判定。
 */

import type {
  DrugAllergyCheck,
  DrugAllergyWarning,
  PediatricDoseCalculation,
  PediatricDoseRule,
  PediatricDoseRuleInput,
} from "@pitchfork/shared/aceso";

// ─── 安全与边界文案（§12 R6 + 053 D3 交叉检查）──────────────────────────────

/** 剂量结果必须随身携带的安全边界声明。 */
export const DOSE_SAFETY_NOTICE =
  "辅助计算，不替代药师判断，不写医嘱；结果须经药师复核后使用。";

/**
 * 药物过敏交叉检查的边界声明（053 D3）。
 *
 * 本页**已接入** `GET /healthcare/v1/drug-allergy-check`：选中儿童与药品后调用，
 * 展示命中项与严重分级。结果是**辅助提示**，须经药师复核，不替代药师判断、不写医嘱。
 */
export const ALLERGY_CHECK_NOTICE =
  "交叉检查为辅助提示，不替代药师判断、不写医嘱；命中项（尤其「严重」）须经药师复核后再决定是否发药。";

/**
 * 交叉检查的接入范围说明（事实口径：本页确实调用了该接口）。
 *
 * 命中来源由后端分级：过敏史 / 交叉反应 / 年龄禁忌；严重项以红色标注。
 */
export const ALLERGY_CHECK_SCOPE_NOTICE =
  "本页在选中儿童与药品后调用 GET /healthcare/v1/drug-allergy-check（053 D3）做交叉检查；未命中不代表绝对安全，仍须药师复核。";

// ─── 交叉检查结果展示（053 D3：warnings / severe 分级）─────────────────────

/** 严重度白名单（后端 `DrugAllergyChecker.SEVERITY_*`）。 */
export const ALLERGY_SEVERITIES = ["严重", "一般"] as const;
export type AllergySeverity = (typeof ALLERGY_SEVERITIES)[number];

/** 命中来源白名单（后端 `DrugAllergyChecker.SOURCE_*`）。 */
export const ALLERGY_SOURCES = ["过敏史", "交叉反应", "年龄禁忌"] as const;

/** 严重度排序权重：严重在前（问题项优先）。 */
export const ALLERGY_SEVERITY_ORDER: Record<string, number> = { 严重: 0, 一般: 1 };

/** 严重度排序权重；未知值排最后（不隐藏、不丢弃）。 */
export function allergySeverityRank(severity: string | null | undefined): number {
  return ALLERGY_SEVERITY_ORDER[(severity ?? "").trim()] ?? 99;
}

/** 严重度徽章配色：严重=danger（error）、一般=warning。 */
export function allergySeverityVariant(
  severity: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((severity ?? "").trim()) {
    case "严重":
      return "danger";
    case "一般":
      return "warning";
    default:
      return "default";
  }
}

/** 命中来源中文口径；**未知 code 原样透出**（不猜测、不隐藏）。 */
export function formatAllergySource(source: string | null | undefined): string {
  const key = (source ?? "").trim();
  return key === "" ? "未知来源" : key;
}

/**
 * 命中项排序：**严重在前**；同严重度保持服务端返回的相对顺序（稳定排序，不用
 * `localeCompare`——那会随运行环境 locale 变化，排序结果不可复现）。不改入参。
 */
export function sortAllergyWarnings(
  warnings: readonly DrugAllergyWarning[],
): DrugAllergyWarning[] {
  return [...warnings].sort(
    (a, b) => allergySeverityRank(a.severity) - allergySeverityRank(b.severity),
  );
}

/** 命中计数（按后端 severity 分级；未知严重度计入 `other`，不丢弃）。 */
export function allergyWarningCount(warnings: readonly DrugAllergyWarning[]): {
  severe: number;
  general: number;
  other: number;
  total: number;
} {
  let severe = 0;
  let general = 0;
  let other = 0;
  for (const warning of warnings) {
    const severity = (warning.severity ?? "").trim();
    if (severity === "严重") severe += 1;
    else if (severity === "一般") general += 1;
    else other += 1;
  }
  return { severe, general, other, total: warnings.length };
}

/** 交叉检查结论：`严重 1 项 / 一般 2 项，须药师复核` / `未命中过敏或年龄禁忌（仍须药师复核）`。 */
export function allergyCheckSummary(check: Pick<DrugAllergyCheck, "warnings" | "severe">): string {
  const count = allergyWarningCount(check.warnings ?? []);
  if (count.total === 0) return "未命中过敏史、交叉反应或年龄禁忌（仍须药师复核）";
  const parts: string[] = [];
  if (count.severe > 0) parts.push(`严重 ${count.severe} 项`);
  if (count.general > 0) parts.push(`一般 ${count.general} 项`);
  if (count.other > 0) parts.push(`未分级 ${count.other} 项`);
  const suffix = check.severe ? "须药师复核后再决定是否发药" : "须药师复核";
  return `${parts.join(" / ")}，${suffix}`;
}

/** 结论徽章配色：存在严重项 → danger；否则 success（未命中不等于绝对安全，文案已注明）。 */
export function allergyCheckVariant(
  check: Pick<DrugAllergyCheck, "severe">,
): "danger" | "success" {
  return check.severe ? "danger" : "success";
}

/** 结论徽章文案。 */
export function allergyCheckLabel(check: Pick<DrugAllergyCheck, "warnings" | "severe">): string {
  const count = allergyWarningCount(check.warnings ?? []);
  if (check.severe) return `存在严重提醒（${count.severe} 项）`;
  if (count.total > 0) return "存在一般提醒";
  return "未命中";
}

/** 单条命中项文案：`过敏史 · 青霉素（严重）`。 */
export function allergyWarningLabel(warning: DrugAllergyWarning): string {
  const source = formatAllergySource(warning.source);
  const matched = (warning.matched ?? "").trim() || "未标注命中项";
  const severity = (warning.severity ?? "").trim() || "未分级";
  return `${source} · ${matched}（${severity}）`;
}

/** 是否命中严重项（以服务端 `severe` 为准，不自行推断）。 */
export function hasSevereWarning(check: Pick<DrugAllergyCheck, "severe">): boolean {
  return check.severe === true;
}

/** 规则状态白名单（后端 `PediatricDoseRule.STATUS_*`）。 */
export const DOSE_RULE_STATUSES = ["启用", "停用"] as const;
export type DoseRuleStatus = (typeof DOSE_RULE_STATUSES)[number];

/** 状态徽章配色。 */
export function doseRuleStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  return (status ?? "").trim() === "启用" ? "success" : "default";
}

// ─── 规则来源（D1 诊断码 → 中文口径）───────────────────────────────────────

/** 规则来源诊断码 → 中文口径（与 D1 `PediatricDoseSource` 一一对应）。 */
export const DOSE_SOURCE_LABELS: Record<string, string> = {
  WEIGHT_AND_AGE_BAND: "命中体重+年龄区间规则",
  WEIGHT_BAND: "命中体重区间规则",
  AGE_BAND: "命中年龄区间规则",
  GENERIC: "通用规则（该药品无区间规则）",
  GENERIC_FALLBACK: "区间未命中，回落通用规则",
};

/** 来源中文口径；未知码原样返回（不猜测）。 */
export function doseSourceLabel(source: string | null | undefined): string {
  const key = (source ?? "").trim();
  if (key === "") return "-";
  return DOSE_SOURCE_LABELS[key] ?? key;
}

/** 来源徽章配色：回落通用规则需要药师多看一眼（警告）。 */
export function doseSourceVariant(
  source: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((source ?? "").trim()) {
    case "GENERIC_FALLBACK":
      return "warning";
    case "WEIGHT_AND_AGE_BAND":
      return "success";
    case "WEIGHT_BAND":
    case "AGE_BAND":
    case "GENERIC":
      return "info";
    default:
      return "default";
  }
}

/** 封顶提示：触发上限时必须人工复核。 */
export function cappedNotice(capped: boolean): string {
  return capped
    ? "已触发上限封顶（单次或日总量），必须由药师人工复核上限后再决定"
    : "未触发上限封顶（规则本身仍须药师复核）";
}

// ─── 展示格式化 ────────────────────────────────────────────────────────────

/** 剂量数值展示：最多 3 位小数去尾零（与 D1 输出口径一致），非法值 `-`。 */
export function formatDoseNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "-";
  return String(Number(value.toFixed(3)));
}

/** 带单位剂量：`187.5 mg`。 */
export function formatDose(value: number | null | undefined, unit: string): string {
  const text = formatDoseNumber(value);
  return text === "-" ? text : `${text} ${unit}`;
}

/** 剂量定义口径：`按体重 15 mg/kg` / `固定剂量 250 mg` / `按体重（优先）+ 固定剂量`。 */
export function ruleDefinitionLabel(rule: Pick<PediatricDoseRule, "dose_per_kg" | "fixed_dose" | "unit">): string {
  const perKg = rule.dose_per_kg;
  const fixed = rule.fixed_dose;
  if (perKg !== null && perKg !== undefined && fixed !== null && fixed !== undefined) {
    return `按体重 ${formatDoseNumber(perKg)} ${rule.unit}/kg（优先）+ 固定剂量 ${formatDoseNumber(fixed)} ${rule.unit}`;
  }
  if (perKg !== null && perKg !== undefined) {
    return `按体重 ${formatDoseNumber(perKg)} ${rule.unit}/kg`;
  }
  if (fixed !== null && fixed !== undefined) {
    return `固定剂量 ${formatDoseNumber(fixed)} ${rule.unit}`;
  }
  return "未定义剂量（服务端会拒绝）";
}

/** 适用区间：`年龄 6–12 月 · 体重 5–10 kg`；两者皆缺 → `无区间（通用规则）`。 */
export function ruleBandLabel(
  rule: Pick<PediatricDoseRule, "age_min_months" | "age_max_months" | "weight_min_kg" | "weight_max_kg">,
): string {
  const parts: string[] = [];
  if (rule.age_min_months !== null || rule.age_max_months !== null) {
    parts.push(`年龄 ${rule.age_min_months ?? "不限"}–${rule.age_max_months ?? "不限"} 月`);
  }
  if (rule.weight_min_kg !== null || rule.weight_max_kg !== null) {
    parts.push(`体重 ${rule.weight_min_kg ?? "不限"}–${rule.weight_max_kg ?? "不限"} kg`);
  }
  return parts.length === 0 ? "无区间（通用规则）" : parts.join(" · ");
}

/** 上限与频次：`单次上限 500 mg · 日上限 1500 mg · 每日 3 次`；全缺 → `未设上限/频次`。 */
export function ruleLimitsLabel(
  rule: Pick<
    PediatricDoseRule,
    "single_dose_max" | "daily_dose_max" | "doses_per_day" | "unit"
  >,
): string {
  const parts: string[] = [];
  if (rule.single_dose_max !== null) parts.push(`单次上限 ${formatDose(rule.single_dose_max, rule.unit)}`);
  if (rule.daily_dose_max !== null) parts.push(`日上限 ${formatDose(rule.daily_dose_max, rule.unit)}`);
  if (rule.doses_per_day !== null) parts.push(`每日 ${rule.doses_per_day} 次`);
  return parts.length === 0 ? "未设上限/频次（按每日 1 次折算）" : parts.join(" · ");
}

/** 计算结果摘要：`单次 187.5 mg · 每日 562.5 mg`。 */
export function calculationSummary(calc: PediatricDoseCalculation): string {
  return `单次 ${formatDose(calc.single_dose, calc.unit)} · 每日 ${formatDose(calc.daily_dose, calc.unit)}`;
}

/** 计算输入回显：`体重 12.5 kg · 月龄 18`（缺失显示「未提供」）。 */
export function calculationInputLabel(calc: PediatricDoseCalculation): string {
  const weight = calc.weight_kg === null || calc.weight_kg === undefined ? "体重未提供" : `体重 ${formatDoseNumber(calc.weight_kg)} kg`;
  const age = calc.age_months === null || calc.age_months === undefined ? "月龄未提供" : `月龄 ${calc.age_months}`;
  return `${weight} · ${age}`;
}

/** 过敏史只读展示：`牛奶、鸡蛋` / `无过敏史记录`。 */
export function formatAllergyList(list: readonly string[] | null | undefined): string {
  const values = (list ?? []).map((item) => item.trim()).filter((item) => item !== "");
  return values.length === 0 ? "无过敏史记录" : values.join("、");
}

// ─── 数值解析与表单校验（与后端同口径）─────────────────────────────────────

/** 正数解析：空串 → `null`；非数字/≤0 → `null`（由校验给出中文提示）。 */
export function parsePositiveDecimal(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value <= 0) return null;
  return value;
}

/** 非负整数解析：空串 → `null`；非整数/负数 → `null`。 */
export function parseWholeNumber(text: string, min = 0): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || !Number.isInteger(value) || value < min) return null;
  return value;
}

/** 规则表单（字符串输入，便于受控组件）。 */
export interface DoseRuleForm {
  materialId: string;
  unit: string;
  dosePerKg: string;
  fixedDose: string;
  ageMinMonths: string;
  ageMaxMonths: string;
  weightMinKg: string;
  weightMaxKg: string;
  singleDoseMax: string;
  dailyDoseMax: string;
  dosesPerDay: string;
  status: string;
  note: string;
}

/** 空白规则表单（状态缺省「启用」，与后端一致）。 */
export function emptyDoseRuleForm(): DoseRuleForm {
  return {
    materialId: "",
    unit: "",
    dosePerKg: "",
    fixedDose: "",
    ageMinMonths: "",
    ageMaxMonths: "",
    weightMinKg: "",
    weightMaxKg: "",
    singleDoseMax: "",
    dailyDoseMax: "",
    dosesPerDay: "",
    status: "启用",
    note: "",
  };
}

/** 由既有规则生成编辑表单（编辑 = 全量覆盖该规则的字段）。 */
export function doseRuleFormOf(rule: PediatricDoseRule): DoseRuleForm {
  const text = (value: number | null | undefined): string =>
    value === null || value === undefined ? "" : String(value);
  return {
    materialId: rule.material_id,
    unit: rule.unit,
    dosePerKg: text(rule.dose_per_kg),
    fixedDose: text(rule.fixed_dose),
    ageMinMonths: text(rule.age_min_months),
    ageMaxMonths: text(rule.age_max_months),
    weightMinKg: text(rule.weight_min_kg),
    weightMaxKg: text(rule.weight_max_kg),
    singleDoseMax: text(rule.single_dose_max),
    dailyDoseMax: text(rule.daily_dose_max),
    dosesPerDay: text(rule.doses_per_day),
    status: rule.status,
    note: rule.note ?? "",
  };
}

/**
 * 表单校验（返回中文错误；`null` = 通过），与后端 `PediatricDoseRuleService.validate` 同口径：
 * `material_id`/`unit` 必填；`dose_per_kg` 与 `fixed_dose` 至少一项；数值必须为正；
 * 区间下界不得大于上界；`doses_per_day ≥ 1`；`status` 白名单。
 */
export function validateDoseRuleForm(form: DoseRuleForm): string | null {
  if (form.materialId.trim() === "") return "请选择药品（material_id 必填）";
  if (form.unit.trim() === "") return "单位必填（如 mg / mL）";

  const dosePerKg = form.dosePerKg.trim();
  const fixedDose = form.fixedDose.trim();
  if (dosePerKg === "" && fixedDose === "") return "「按体重剂量」与「固定剂量」至少填写一项";
  if (dosePerKg !== "" && parsePositiveDecimal(dosePerKg) === null) return "按体重剂量必须是正数";
  if (fixedDose !== "" && parsePositiveDecimal(fixedDose) === null) return "固定剂量必须是正数";

  for (const [label, text] of [
    ["单次上限", form.singleDoseMax],
    ["日上限", form.dailyDoseMax],
    ["体重下限", form.weightMinKg],
    ["体重上限", form.weightMaxKg],
  ] as const) {
    if (text.trim() !== "" && parsePositiveDecimal(text) === null) return `${label}必须是正数`;
  }

  const ageMin = form.ageMinMonths.trim();
  const ageMax = form.ageMaxMonths.trim();
  if (ageMin !== "" && parseWholeNumber(ageMin, 0) === null) return "年龄下限必须是非负整数（月龄）";
  if (ageMax !== "" && parseWholeNumber(ageMax, 0) === null) return "年龄上限必须是非负整数（月龄）";
  const ageMinValue = parseWholeNumber(ageMin, 0);
  const ageMaxValue = parseWholeNumber(ageMax, 0);
  if (ageMinValue !== null && ageMaxValue !== null && ageMinValue > ageMaxValue) {
    return "年龄下限不得大于年龄上限";
  }

  const weightMin = parsePositiveDecimal(form.weightMinKg);
  const weightMax = parsePositiveDecimal(form.weightMaxKg);
  if (weightMin !== null && weightMax !== null && weightMin > weightMax) {
    return "体重下限不得大于体重上限";
  }

  const dosesPerDay = form.dosesPerDay.trim();
  if (dosesPerDay !== "" && parseWholeNumber(dosesPerDay, 1) === null) return "每日次数必须是不小于 1 的整数";

  if (!DOSE_RULE_STATUSES.includes(form.status.trim() as DoseRuleStatus)) {
    return `状态必须是 ${DOSE_RULE_STATUSES.join(" / ")}`;
  }
  return null;
}

/**
 * 表单 → 请求体：空输入一律落 `null`（显式清空），与 PUT「只覆盖出现的字段」语义一致；
 * 编辑表单展示的是规则全量字段，因此提交即整体覆盖该规则的可写字段。
 */
export function buildDoseRulePayload(form: DoseRuleForm): PediatricDoseRuleInput {
  return {
    material_id: form.materialId.trim(),
    unit: form.unit.trim(),
    dose_per_kg: parsePositiveDecimal(form.dosePerKg),
    fixed_dose: parsePositiveDecimal(form.fixedDose),
    age_min_months: parseWholeNumber(form.ageMinMonths, 0),
    age_max_months: parseWholeNumber(form.ageMaxMonths, 0),
    weight_min_kg: parsePositiveDecimal(form.weightMinKg),
    weight_max_kg: parsePositiveDecimal(form.weightMaxKg),
    single_dose_max: parsePositiveDecimal(form.singleDoseMax),
    daily_dose_max: parsePositiveDecimal(form.dailyDoseMax),
    doses_per_day: parseWholeNumber(form.dosesPerDay, 1),
    status: form.status.trim(),
    note: form.note.trim() === "" ? null : form.note.trim(),
  };
}
