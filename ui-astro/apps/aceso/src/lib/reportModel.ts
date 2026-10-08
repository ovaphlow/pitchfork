/**
 * 儿童健康报告展示/导出辅助 —— 纯函数（无 React、无副作用）。
 *
 * 053 C2：把 C1 的 `ChildHealthReport` 聚合 DTO（`@pitchfork/shared/aceso`）映射为
 * **有序段落**，供 `ChildReportPage.tsx` 渲染可打印视图，并生成 JSON / CSV 导出内容。
 *
 * 边界：
 *  - 只消费 C1 契约，不自行拼请求、不改后端契约（053 §5/§6）。
 *  - **不引入后端 PDF 引擎**：导出 = 前端打印 + JSON/CSV（053 §11 裁定 6/7）。
 *  - 隐私（053 §3 Dh）：导出/渲染路径**不得包含身份证号键**；`toReportJson` 还会
 *    递归剔除意外混入的敏感键（`id_card` / `id_number` / `identity` / 身份证）。
 *
 * 为什么不复用 `lib/datetime.ts`：本文件要能被 `node --test` 以 ESM 直接加载
 * （`pnpm test:unit:aceso`），而 Node 的 ESM 解析器不做无扩展名补全，import
 * `./datetime` 会失败、写 `./datetime.ts` 又需 `allowImportingTsExtensions`。
 * 故这里自带一份最小的 Asia/Shanghai 展示格式化（口径与 datetime.ts 一致）。
 */

import type {
  ChildGrowthPercentile,
  ChildHealthCheckupItem,
  ChildHealthReport,
} from "@pitchfork/shared/aceso";

// ─── 段落元数据 ──────────────────────────────────────────────────

export type ReportSectionKey =
  | "profile"
  | "checkups"
  | "growth"
  | "vaccinations"
  | "screenings"
  | "referrals"
  | "guidance"
  | "allergies";

export interface ReportSectionMeta {
  key: ReportSectionKey;
  label: string;
}

/** 有序段落元数据：顺序即报告/导出中的呈现顺序（053 §4 C2 / §8.6）。 */
export const REPORT_SECTIONS: readonly ReportSectionMeta[] = [
  { key: "profile", label: "档案" },
  { key: "checkups", label: "体检" },
  { key: "growth", label: "成长" },
  { key: "vaccinations", label: "接种" },
  { key: "screenings", label: "筛查" },
  { key: "referrals", label: "转诊" },
  { key: "guidance", label: "指导" },
  { key: "allergies", label: "过敏" },
];

/** 渲染/导出用的单个段落：表头 + 数据行（行是字符串单元格数组）。 */
export interface ReportSection {
  key: ReportSectionKey;
  label: string;
  headers: string[];
  rows: string[][];
}

// ─── 展示格式化（Asia/Shanghai，host-TZ 无关） ───────────────────

const BUSINESS_TIME_ZONE = "Asia/Shanghai";

const dateTimeFormatter = new Intl.DateTimeFormat("en-US", {
  timeZone: BUSINESS_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

interface BusinessParts {
  date: string;
  /** 仅日期值时为空串 */
  time: string;
}

function businessParts(value: string | null | undefined): BusinessParts | null {
  if (value === null || value === undefined) return null;
  const raw = value.trim();
  if (raw === "") return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return { date: raw, time: "" };
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return null;
  const parts = dateTimeFormatter.formatToParts(parsed);
  const pick = (type: string): string => parts.find((part) => part.type === type)?.value ?? "";
  return {
    date: `${pick("year")}-${pick("month")}-${pick("day")}`,
    time: `${pick("hour")}:${pick("minute")}`,
  };
}

/** 展示用日期：`2026-10-06T15:30:00+08:00` → `2026-10-06`；不可解析时原样返回。 */
export function formatReportDate(value: string | null | undefined, empty = ""): string {
  if (value === null || value === undefined) return empty;
  const parts = businessParts(value);
  return parts === null ? value.trim() : parts.date;
}

/** 展示用日期时间：`2026-10-06T15:30:00+08:00` → `2026-10-06 15:30`。 */
export function formatReportDateTime(value: string | null | undefined, empty = ""): string {
  if (value === null || value === undefined) return empty;
  const parts = businessParts(value);
  if (parts === null) return value.trim();
  return parts.time ? `${parts.date} ${parts.time}` : parts.date;
}

function text(value: string | null | undefined): string {
  return value ?? "";
}

function numberText(value: number | null | undefined): string {
  return value === null || value === undefined ? "" : String(value);
}

function ageMonthsText(value: number | null | undefined): string {
  return value === null || value === undefined ? "" : `${value} 月`;
}

/** 体检检查项 → 单行文本：`项目：结果：结论`，多项用「；」分隔。 */
function itemsText(items: ChildHealthCheckupItem[] | null): string {
  if (!items || items.length === 0) return "";
  return items
    .map((item) =>
      [item.item, item.result, item.conclusion]
        .filter((part): part is string => typeof part === "string" && part !== "")
        .join("："),
    )
    .join("；");
}

/** 成长百分位快照 → 单行评价：`身高 P50 / 体重 P85`。 */
function snapshotText(snapshot: Record<string, ChildGrowthPercentile> | null): string {
  if (!snapshot) return "";
  const labels: Record<string, string> = { height: "身高", weight: "体重", head: "头围" };
  const parts: string[] = [];
  for (const [metric, value] of Object.entries(snapshot)) {
    if (value === null || value === undefined) continue;
    const label = labels[metric] ?? metric;
    if (typeof value.p === "number") parts.push(`${label} P${value.p}`);
    else if (typeof value.z === "number") parts.push(`${label} Z${value.z}`);
  }
  return parts.join(" / ");
}

// ─── 敏感键剔除 ──────────────────────────────────────────────────

/** 身份证号类键名（大小写不敏感）；导出前剔除，避免 PHI 外泄。 */
const SENSITIVE_KEY_RE = /id[_-]?card|id[_-]?number|identity|身份证/i;

/** 递归克隆并剔除敏感键；数组逐项处理，原始值原样返回。 */
export function stripSensitive<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.map((item) => stripSensitive(item)) as unknown as T;
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      if (SENSITIVE_KEY_RE.test(key)) continue;
      out[key] = stripSensitive(entry);
    }
    return out as unknown as T;
  }
  return value;
}

// ─── 段落构建 ────────────────────────────────────────────────────

function buildProfileSection(report: ChildHealthReport): ReportSection {
  const patient = report.patient;
  const profile = report.profile;
  const rows: string[][] = [
    ["姓名", text(patient.name)],
    ["性别", text(patient.gender)],
    ["出生日期", formatReportDate(patient.birth_date)],
    ["月龄", ageMonthsText(patient.age_months)],
    ["患者域", text(patient.person_type)],
  ];
  if (profile) {
    rows.push(
      ["监护人", text(profile.guardian_name)],
      ["与儿童关系", text(profile.guardian_relationship)],
      ["监护人电话", text(profile.guardian_phone)],
      ["出生体重(g)", numberText(profile.birth_weight_g)],
      ["出生身长(mm)", numberText(profile.birth_height_mm)],
      ["分娩方式", text(profile.delivery_mode)],
      ["喂养方式", text(profile.feeding_method)],
      ["接种摘要", text(profile.vaccination_summary)],
      ["档案备注", text(profile.remark)],
    );
  }
  return { key: "profile", label: "档案", headers: ["项目", "内容"], rows };
}

function buildCheckupsSection(report: ChildHealthReport): ReportSection {
  return {
    key: "checkups",
    label: "体检",
    headers: ["体检日期", "类型", "月龄", "结论", "诊断", "诊断编码", "诊断状态", "检查项", "本次指导", "下次体检", "医生"],
    rows: report.checkups.map((checkup) => [
      formatReportDate(checkup.checkup_date),
      text(checkup.checkup_type),
      ageMonthsText(checkup.age_months),
      text(checkup.conclusion),
      text(checkup.diagnosis),
      text(checkup.diagnosis_code),
      text(checkup.status),
      itemsText(checkup.items),
      text(checkup.guidance),
      formatReportDate(checkup.next_checkup_date),
      text(checkup.reviewed_by),
    ]),
  };
}

function buildGrowthSection(report: ChildHealthReport): ReportSection {
  return {
    key: "growth",
    label: "成长",
    headers: ["测量时间", "天龄", "身高(cm)", "体重(kg)", "头围(cm)", "评价", "备注"],
    rows: report.growth.map((growth) => [
      formatReportDateTime(growth.measured_at),
      growth.age_days === null ? "" : `${growth.age_days} 天`,
      numberText(growth.height_cm),
      numberText(growth.weight_kg),
      numberText(growth.head_circumference_cm),
      snapshotText(growth.percentile_snapshot),
      text(growth.note),
    ]),
  };
}

function buildVaccinationsSection(report: ChildHealthReport): ReportSection {
  const { summary, schedule } = report.vaccinations;
  const summaryRow = [
    "汇总",
    `总${summary.total}`,
    `已接种${summary.已接种}`,
    `待接种${summary.待接种}`,
    `临期${summary.临期} 逾期${summary.逾期} 暂缓${summary.暂缓} 拒种${summary.拒种}`,
  ];
  return {
    key: "vaccinations",
    label: "接种",
    headers: ["疫苗", "剂次", "推荐月龄", "应种日期", "状态"],
    rows: [
      summaryRow,
      ...schedule.map((row) => [
        text(row.vaccine_name),
        numberText(row.dose_no),
        ageMonthsText(row.recommended_month),
        formatReportDate(row.due_date),
        text(row.status),
      ]),
    ],
  };
}

function buildScreeningsSection(report: ChildHealthReport): ReportSection {
  return {
    key: "screenings",
    label: "筛查",
    headers: ["筛查时间", "量表", "版本", "月龄", "总分", "判读", "结论", "关联体检"],
    rows: report.screenings.map((screening) => [
      formatReportDateTime(screening.administered_at),
      text(screening.scale_code),
      text(screening.scale_version),
      ageMonthsText(screening.age_months),
      `${screening.total_score}/${screening.max_total_score}`,
      text(screening.classification),
      text(screening.conclusion),
      text(screening.checkup_id),
    ]),
  };
}

function buildReferralsSection(report: ChildHealthReport): ReportSection {
  return {
    key: "referrals",
    label: "转诊",
    headers: ["来源", "问题摘要", "转诊类型", "目标机构", "目标科室", "紧急度", "状态", "转诊时间", "结果"],
    rows: report.referrals.map((referral) => [
      text(referral.source_type),
      text(referral.problem_summary),
      text(referral.referral_type),
      text(referral.target_org),
      text(referral.target_dept),
      text(referral.urgency),
      text(referral.status),
      formatReportDateTime(referral.referred_at),
      text(referral.outcome),
    ]),
  };
}

function buildGuidanceSection(report: ChildHealthReport): ReportSection {
  return {
    key: "guidance",
    label: "指导",
    headers: ["时间", "口径", "主题", "内容", "渠道", "执行人"],
    rows: report.guidance.map((record) => [
      formatReportDateTime(record.delivered_at),
      text(record.kind) || "日常",
      text(record.topic),
      text(record.content),
      text(record.channel),
      text(record.operator),
    ]),
  };
}

function buildAllergiesSection(report: ChildHealthReport): ReportSection {
  return {
    key: "allergies",
    label: "过敏",
    headers: ["过敏原", "反应"],
    rows: report.allergies.map((allergy) => [text(allergy.allergen), text(allergy.reaction)]),
  };
}

const SECTION_BUILDERS: Record<ReportSectionKey, (report: ChildHealthReport) => ReportSection> = {
  profile: buildProfileSection,
  checkups: buildCheckupsSection,
  growth: buildGrowthSection,
  vaccinations: buildVaccinationsSection,
  screenings: buildScreeningsSection,
  referrals: buildReferralsSection,
  guidance: buildGuidanceSection,
  allergies: buildAllergiesSection,
};

/**
 * 报告 → 有序段落。严格按 `REPORT_SECTIONS` 顺序产出，**空段仍产出**（`rows` 为空数组），
 * 使打印视图与导出结构稳定，不因数据缺失而缺段。
 */
export function buildReportSections(report: ChildHealthReport): ReportSection[] {
  return REPORT_SECTIONS.map((meta) => {
    const built = SECTION_BUILDERS[meta.key](report);
    return { ...built, key: meta.key, label: meta.label };
  });
}

// ─── 导出 ────────────────────────────────────────────────────────

/**
 * 报告 → 稳定 JSON。顶层含患者（已剔除敏感键）、生成时间、计数，以及**每个段落
 * 作为顶层键**（`profile`/`checkups`/…），便于机器消费。
 */
export function toReportJson(report: ChildHealthReport): string {
  const payload: Record<string, unknown> = {
    generated_at: report.meta.generated_at,
    counts: report.meta.counts,
    patient: stripSensitive(report.patient),
  };
  for (const section of buildReportSections(report)) {
    payload[section.key] = {
      label: section.label,
      headers: section.headers,
      rows: section.rows,
    };
  }
  return JSON.stringify(payload, null, 2);
}

/** 单个 CSV 单元格转义：含逗号/引号/换行时加引号，内部引号双写。 */
export function csvCell(value: string | null | undefined): string {
  const cell = value ?? "";
  if (/[",\r\n]/.test(cell)) {
    return `"${cell.replace(/"/g, '""')}"`;
  }
  return cell;
}

/**
 * 报告 → CSV：**每段一个区块**（`# 段落名` + 表头 + 数据行），区块间空行分隔。
 * 所有单元格经 `csvCell` 转义，字段内的逗号/引号/换行不会破坏结构。
 */
export function toReportCsv(report: ChildHealthReport): string {
  const blocks = buildReportSections(report).map((section) => {
    const lines = [`# ${section.label}`, section.headers.map(csvCell).join(",")];
    for (const row of section.rows) {
      lines.push(row.map(csvCell).join(","));
    }
    return lines.join("\n");
  });
  return `${blocks.join("\n\n")}\n`;
}

/** 文件名基名中不允许出现的字符（路径分隔、通配、控制字符、空白）→ 下划线。 */
function safeFileNamePart(value: string | null | undefined): string {
  const cleaned = text(value)
    .trim()
    .replace(/[\\/:*?"<>|\s]+/g, "_")
    .replace(/^_+|_+$/g, "");
  return cleaned === "" ? "未知" : cleaned;
}

/**
 * 报告文件名基名（**不含扩展名**）：`儿童健康报告-<name>-<yyyyMMdd-HHmm>`。
 * 调用方按导出格式追加 `.json` / `.csv`。时间按 Asia/Shanghai 展示；不可解析时用
 * `00000000-0000` 兜底，保证文件名始终可用。
 */
export function reportFileName(
  patientName: string | null | undefined,
  generatedAt: string | null | undefined,
): string {
  const parts = businessParts(generatedAt);
  const stamp =
    parts === null
      ? "00000000-0000"
      : `${parts.date.replace(/-/g, "")}-${(parts.time || "00:00").replace(":", "")}`;
  return `儿童健康报告-${safeFileNamePart(patientName)}-${stamp}`;
}

// ─── 时间区间筛选（纯展示，不改后端契约） ────────────────────────

/**
 * 按业务日期闭区间 `[from, to]`（`YYYY-MM-DD`，任一可空）过滤报告中的**时序段落**：
 * 体检 / 成长 / 筛查 / 转诊 / 指导 / 接种记录与排程。档案、过敏等无时间字段的段落保留。
 * 无可用日期的记录**保留**（不因格式异常而静默丢数据）；计数按过滤后重算。
 */
export function filterReportByRange(
  report: ChildHealthReport,
  from: string | null | undefined,
  to: string | null | undefined,
): ChildHealthReport {
  const lower = from?.trim() || null;
  const upper = to?.trim() || null;
  if (lower === null && upper === null) return report;

  const inRange = (value: string | null | undefined): boolean => {
    const date = formatReportDate(value, "");
    if (date === "") return true;
    if (lower !== null && date < lower) return false;
    if (upper !== null && date > upper) return false;
    return true;
  };

  const checkups = report.checkups.filter((row) => inRange(row.checkup_date));
  const growth = report.growth.filter((row) => inRange(row.measured_at));
  const screenings = report.screenings.filter((row) => inRange(row.administered_at));
  const referrals = report.referrals.filter((row) => inRange(row.referred_at ?? row.created_at));
  const guidance = report.guidance.filter((row) => inRange(row.delivered_at));
  const vaccinations = {
    ...report.vaccinations,
    records: report.vaccinations.records.filter((row) => inRange(row.administered_at)),
    schedule: report.vaccinations.schedule.filter((row) => inRange(row.due_date)),
  };

  return {
    ...report,
    checkups,
    growth,
    screenings,
    referrals,
    guidance,
    vaccinations,
    meta: {
      ...report.meta,
      counts: {
        checkups: checkups.length,
        growth: growth.length,
        screenings: screenings.length,
        referrals: referrals.length,
        guidance: guidance.length,
        allergies: report.allergies.length,
      },
    },
  };
}
