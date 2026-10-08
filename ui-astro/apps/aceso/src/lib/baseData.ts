/**
 * 基础数据维护展示/表单辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 F2 / F 链前端第一步）：
 * - **权威在后端**：内容表的建/查/改与形状校验都在 `BaseDataService`（V539）；
 *   本文件只做**写入白名单镜像、表单校验与展示派生**，不重写服务端规则。
 * - **读库优先、常量回落**（054 §3 Dh / §8.6）：内容表为空或读失败时，
 *   消费方（052 接种排程、053 发育筛查）**完全回落既有常量**，判定与改造前一致；
 *   页面必须把这条口径讲清楚，避免管理员误以为「必须先把库填满才能用」。
 * - 写入白名单（`VACCINATION_WRITE_KEYS` / `SCALE_WRITE_KEYS`）与后端常量
 *   **逐字一致**，`base-data.test.ts` 有漂移守卫。
 */

import type {
  BaseDevelopmentalScale,
  BaseVaccinationType,
  DevelopmentalScaleDomain,
  DevelopmentalScaleItem,
  DevelopmentalScaleOption,
} from "@pitchfork/shared/aceso";

// ─── 常量镜像（与后端 BaseDataService 逐字比对，测试有漂移守卫）─────────────

/** 内容状态白名单（后端 `BaseDataService.STATUSES`）。 */
export const CONTENT_STATUSES = ["启用", "停用"] as const;
export type ContentStatus = (typeof CONTENT_STATUSES)[number];

/** 疫苗类型写入白名单（后端 `VACCINATION_KEYS`，逐字一致）。 */
export const VACCINATION_WRITE_KEYS = [
  "code",
  "name",
  "dose_count",
  "recommended_months",
  "status",
  "sort_order",
  "metadata",
] as const;

/** 发育量表写入白名单（后端 `SCALE_KEYS`，逐字一致）。 */
export const SCALE_WRITE_KEYS = [
  "scale_code",
  "scale_version",
  "name",
  "age_min_months",
  "age_max_months",
  "definition",
  "status",
  "metadata",
] as const;

/** 状态徽章配色：启用=success、停用=default。 */
export function contentStatusVariant(
  status: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  return (status ?? "").trim() === "启用" ? "success" : "default";
}

/**
 * 读库优先/常量回落的口径说明（页面必须常驻展示）。
 *
 * 事实来源：`BaseDataService` 的覆盖层——库为空或读失败时覆盖层为空，
 * 消费方行为与改造前**逐字节一致**。
 */
export const FALLBACK_NOTICE =
  "读库优先、常量回落：内容表有启用记录时消费方使用库内容；库为空或读取失败时完全回落既有常量（052 接种状态与 053 筛查判读结果与改造前一致）。因此本页是「可选的内容维护」，不是使用前置条件。";

/** 只有「启用」记录参与内容加载（停用记录保留在台账但不生效）。 */
export const ENABLED_ONLY_NOTICE =
  "只有「启用」记录参与内容加载；「停用」记录保留在台账中但不生效（可随时重新启用）。";

// ─── 疫苗类型：月龄数组与排程预览 ──────────────────────────────────────────

/** 月龄数组文本解析：逗号/顿号/空格分隔的非负整数；非法或空 → `null`。 */
export function parseMonthsText(text: string): number[] | null {
  const parts = text
    .split(/[,，、\s]+/)
    .map((part) => part.trim())
    .filter((part) => part !== "");
  if (parts.length === 0) return null;
  const months: number[] = [];
  for (const part of parts) {
    if (!/^\d+$/.test(part)) return null;
    months.push(Number(part));
  }
  return months;
}

/** 月龄数组展示：`0、1、6`；空数组显示 `-`。 */
export function formatMonths(months: readonly number[] | null | undefined): string {
  const values = (months ?? []).filter((month) => Number.isFinite(month));
  return values.length === 0 ? "-" : values.map((month) => String(month)).join("、");
}

/**
 * 排程展开预览（**与后端 `BaseDataService.toScheduleEntries` 同口径**）：
 * `code × 剂次 1..dose_count`，第 n 剂取 `recommended_months[n-1]`。
 *
 * 仅用于让管理员看到维护后的排程效果；**不是**权威排程（权威在服务端内容覆盖层）。
 */
export function schedulePreview(
  vaccination: Pick<BaseVaccinationType, "code" | "name" | "dose_count" | "recommended_months">,
): Array<{ doseNo: number; code: string; name: string; month: number }> {
  const months = vaccination.recommended_months ?? [];
  const entries: Array<{ doseNo: number; code: string; name: string; month: number }> = [];
  for (let doseNo = 1; doseNo <= vaccination.dose_count; doseNo += 1) {
    const month = months[doseNo - 1];
    if (month === undefined || month === null || !Number.isFinite(month)) continue;
    entries.push({ doseNo, code: vaccination.code, name: vaccination.name, month });
  }
  return entries;
}

/** 排程预览文案：`第1剂 0 月、第2剂 1 月、第3剂 6 月`。 */
export function schedulePreviewLabel(
  vaccination: Pick<BaseVaccinationType, "code" | "name" | "dose_count" | "recommended_months">,
): string {
  const entries = schedulePreview(vaccination);
  if (entries.length === 0) return "无可用剂次（月龄数组缺失）";
  return entries.map((entry) => `第${entry.doseNo}剂 ${entry.month} 月`).join("、");
}

/** 疫苗类型摘要：`3 剂 · 0、1、6 月`。 */
export function vaccinationSummary(
  vaccination: Pick<BaseVaccinationType, "dose_count" | "recommended_months">,
): string {
  return `${vaccination.dose_count} 剂 · ${formatMonths(vaccination.recommended_months)} 月`;
}

// ─── 发育量表：definition 编辑 ─────────────────────────────────────────────

/** 领域草稿（表单用，字符串输入）。 */
export interface DomainDraft {
  code: string;
  name: string;
}

/** 条目草稿（选项用文本行编辑，见 [parseOptionsText]）。 */
export interface ItemDraft {
  code: string;
  domain: string;
  prompt: string;
  min_months: string;
  max_months: string;
  optionsText: string;
}

/** 空白领域草稿。 */
export function emptyDomainDraft(): DomainDraft {
  return { code: "", name: "" };
}

/** 空白条目草稿。 */
export function emptyItemDraft(domain = ""): ItemDraft {
  return { code: "", domain, prompt: "", min_months: "0", max_months: "36", optionsText: "" };
}

// ─── 草稿数组编辑（纯函数，不可变；与收银页的同类助手各自独立，避免跨域耦合）───

/** 追加一行（返回新数组，不修改入参）。 */
export function addDraftLike<T extends object>(items: readonly T[], item: T): T[] {
  return [...items, { ...item }];
}

/** 修改第 index 行（越界返回原样副本）。 */
export function updateDraftLike<T extends object>(
  items: readonly T[],
  index: number,
  patch: Partial<T>,
): T[] {
  if (index < 0 || index >= items.length) return items.map((item) => ({ ...item }));
  return items.map((item, current) => (current === index ? { ...item, ...patch } : { ...item }));
}

/** 删除第 index 行（越界返回原样副本）。 */
export function removeDraftLike<T extends object>(
  items: readonly T[],
  index: number,
): T[] {
  return items.filter((_, current) => current !== index).map((item) => ({ ...item }));
}

/**
 * 选项文本解析：每行 `value|label`（也接受全角 `｜` 与制表符）。
 * 空行忽略；`value` 必须是非负整数；`label` 非空。非法 → `null`。
 */
export function parseOptionsText(text: string): DevelopmentalScaleOption[] | null {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== "");
  if (lines.length === 0) return null;
  const options: DevelopmentalScaleOption[] = [];
  for (const line of lines) {
    const parts = line.split(/[|｜\t]/);
    if (parts.length < 2) return null;
    const valueText = (parts[0] ?? "").trim();
    const label = (parts[1] ?? "").trim();
    if (!/^\d+$/.test(valueText) || label === "") return null;
    options.push({ value: Number(valueText), label });
  }
  return options;
}

/** 选项数组 → 文本行（`value|label`）。 */
export function formatOptionsText(options: readonly DevelopmentalScaleOption[] | null | undefined): string {
  return (options ?? []).map((option) => `${option.value}|${option.label}`).join("\n");
}

/** 量表条目选项摘要：`0=正常、1=异常`。 */
export function optionsLabel(options: readonly DevelopmentalScaleOption[] | null | undefined): string {
  const values = (options ?? []).map((option) => `${option.value}=${option.label}`);
  return values.length === 0 ? "-" : values.join("、");
}

/** 量表摘要：`2 个领域 · 5 个条目`。 */
export function scaleSummary(
  scale: Pick<BaseDevelopmentalScale, "definition">,
): string {
  const domains = scale.definition?.domains?.length ?? 0;
  const items = scale.definition?.items?.length ?? 0;
  return `${domains} 个领域 · ${items} 个条目`;
}

/** 适用月龄展示：`0–36 月`。 */
export function scaleAgeLabel(
  scale: Pick<BaseDevelopmentalScale, "age_min_months" | "age_max_months">,
): string {
  return `${scale.age_min_months}–${scale.age_max_months} 月`;
}

/** 由后端 definition 生成编辑草稿（编辑 = 全量覆盖该内容行的字段）。 */
export function toDomainDrafts(definition: { domains?: DevelopmentalScaleDomain[] } | null | undefined): DomainDraft[] {
  const domains = definition?.domains ?? [];
  if (domains.length === 0) return [emptyDomainDraft()];
  return domains.map((domain) => ({ code: domain.code, name: domain.name ?? "" }));
}

/** 由后端 definition 生成条目草稿。 */
export function toItemDrafts(definition: { items?: DevelopmentalScaleItem[] } | null | undefined): ItemDraft[] {
  const items = definition?.items ?? [];
  if (items.length === 0) return [emptyItemDraft()];
  return items.map((item) => ({
    code: item.code,
    domain: item.domain,
    prompt: item.prompt,
    min_months: String(item.min_months),
    max_months: String(item.max_months),
    optionsText: formatOptionsText(item.options),
  }));
}

/** 领域草稿 → 提交体（去空白、丢弃空 code 行）。 */
export function toDefinitionDomains(domains: readonly DomainDraft[]): DevelopmentalScaleDomain[] {
  return domains
    .map((domain) => ({ code: domain.code.trim(), name: domain.name.trim() }))
    .filter((domain) => domain.code !== "")
    .map((domain) => (domain.name === "" ? { code: domain.code } : domain));
}

/** 条目草稿 → 提交体（数值化、选项解析；调用前必须已通过校验）。 */
export function toDefinitionItems(items: readonly ItemDraft[]): DevelopmentalScaleItem[] {
  return items
    .filter((item) => item.code.trim() !== "")
    .map((item) => ({
      code: item.code.trim(),
      domain: item.domain.trim(),
      prompt: item.prompt.trim(),
      min_months: Number(item.min_months.trim()),
      max_months: Number(item.max_months.trim()),
      options: parseOptionsText(item.optionsText) ?? [],
    }));
}

// ─── 表单校验（与后端 BaseDataService 同口径）──────────────────────────────

/** 非负整数解析：空串/非整数/负数 → `null`。 */
export function parseNonNegativeInt(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isSafeInteger(value) ? value : null;
}

/**
 * 疫苗类型表单校验（镜像 `parseVaccinationCreate`）：
 * `code`/`name` 必填；`dose_count` ≥ 1 整数；`recommended_months` 非空非负整数数组且
 * **长度等于 `dose_count`**；`sort_order` ≥ 0 整数；`status` 白名单。
 */
export function validateVaccinationForm(input: {
  code: string;
  name: string;
  doseCountText: string;
  monthsText: string;
  sortOrderText: string;
  status: string;
}): string | null {
  if (input.code.trim() === "") return "疫苗编码必填";
  if (input.name.trim() === "") return "疫苗名称必填";
  const doseCount = parseNonNegativeInt(input.doseCountText);
  if (doseCount === null || doseCount < 1) return "剂次数必须是 ≥ 1 的整数";
  const months = parseMonthsText(input.monthsText);
  if (months === null) return "推荐月龄必须是非负整数数组（逗号/顿号分隔）";
  if (months.length !== doseCount) {
    return `推荐月龄个数必须等于剂次数（当前 ${months.length} 个，剂次数 ${doseCount}）`;
  }
  const sortOrder = input.sortOrderText.trim() === "" ? 0 : parseNonNegativeInt(input.sortOrderText);
  if (sortOrder === null) return "排序号必须是非负整数";
  if (!CONTENT_STATUSES.includes(input.status.trim() as ContentStatus)) {
    return `状态必须是 ${CONTENT_STATUSES.join(" / ")}`;
  }
  return null;
}

/**
 * 发育量表表单校验（镜像 `requiredDefinition` + `parseScaleCreate`）：
 * `scale_code`/`scale_version`/`name` 必填；`age_min_months` ≥ 0、
 * `age_max_months` ≥ `age_min_months`；`definition.domains` 与 `definition.items` 非空；
 * 领域 `code` 必填且不重复；条目 `code`/`domain`（必须命中领域）/`prompt`/月龄区间/选项齐全。
 */
export function validateScaleForm(input: {
  scaleCode: string;
  scaleVersion: string;
  name: string;
  ageMinText: string;
  ageMaxText: string;
  domains: readonly DomainDraft[];
  items: readonly ItemDraft[];
  status: string;
}): string | null {
  if (input.scaleCode.trim() === "") return "量表编码必填";
  if (input.scaleVersion.trim() === "") return "量表版本必填";
  if (input.name.trim() === "") return "量表名称必填";

  const ageMin = parseNonNegativeInt(input.ageMinText);
  const ageMax = parseNonNegativeInt(input.ageMaxText);
  if (ageMin === null) return "适用月龄下界必须是非负整数";
  if (ageMax === null) return "适用月龄上界必须是非负整数";
  if (ageMax < ageMin) return "适用月龄上界不得小于下界";

  const domains = toDefinitionDomains(input.domains);
  if (domains.length === 0) return "至少填写一个领域（domain.code 必填）";
  const codes = new Set<string>();
  for (const domain of domains) {
    if (codes.has(domain.code)) return `领域编码重复：${domain.code}`;
    codes.add(domain.code);
  }

  const items = input.items.filter((item) => item.code.trim() !== "");
  if (items.length === 0) return "至少填写一个条目（item.code 必填）";
  for (const [index, item] of items.entries()) {
    const label = `第 ${index + 1} 个条目`;
    if (item.domain.trim() === "") return `${label}缺少所属领域`;
    if (!codes.has(item.domain.trim())) return `${label}的所属领域不在领域清单内：${item.domain.trim()}`;
    if (item.prompt.trim() === "") return `${label}缺少题目文本`;
    const min = parseNonNegativeInt(item.min_months);
    const max = parseNonNegativeInt(item.max_months);
    if (min === null || max === null) return `${label}的月龄区间必须是非负整数`;
    if (max < min) return `${label}的月龄上界不得小于下界`;
    if (parseOptionsText(item.optionsText) === null) {
      return `${label}的选项必须每行 \`值|文案\`（值非负整数、文案非空）`;
    }
  }

  if (!CONTENT_STATUSES.includes(input.status.trim() as ContentStatus)) {
    return `状态必须是 ${CONTENT_STATUSES.join(" / ")}`;
  }
  return null;
}
