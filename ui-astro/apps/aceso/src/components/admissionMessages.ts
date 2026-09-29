// Aceso 入住域（办理入住 / 床位占用 / 入住区间冲突）后端错误文案的中文映射。
//
// 后端沿用 `{ "error": "<message>" }`，message 是英文句式内嵌中文枚举，且被 Kotlin 侧
// 断言锁定（计划 029 §4.2），本期不改后端文案。因此「中文化」只能在前端做：
// 把已知失败条件翻成「中文 + 去哪改」的可操作提示；未命中的错误也一律给中文兜底，
// 英文原文只作为次要的可排障信息附在后面，绝不成为主提示。
//
// 口径与 `billingMessages.ts` 一致；冲突判定归服务端，文案与对应操作建议归前端。

import { formatDateTime } from "../lib/datetime";

/**
 * `until=<ISO8601|ongoing>` 片段 → 中文短语（用于「占用至 …」「（至 …）」两种句式）。
 * `ongoing` 表示对方长者仍在住、无计划离院日期，此时用调用方给出的中文短语（含「仍在住」）。
 */
function untilPhrase(until: string | undefined, ongoing: string): string {
  const raw = until?.trim() ?? "";
  if (!raw) return "时间未知";
  if (raw === "ongoing") return ongoing;
  return `至 ${formatDateTime(raw)}`;
}

export interface AdmissionErrorMapping {
  /** 后端 `error.message` 的匹配正则（只用 exec，不加 g 标志） */
  pattern: RegExp;
  /** 由捕获组生成中文可操作提示；未用到的捕获组按 `?? ""` 兜底 */
  render: (match: RegExpExecArray) => string;
}

/**
 * 入住域错误映射表：一条 = 一条中文提示 + 其匹配规则。
 * 顺序即优先级，新增文案只追加条目，不改 function 里的分支。
 */
export const ADMISSION_ERROR_MAPPINGS: AdmissionErrorMapping[] = [
  // ─── 床位已被占用（HealthcareService 床位区间校验，409） ───────────────
  {
    pattern: /^bed already occupied: department=(.+) ward=(.+) encounter_no=(.+) until=(.+)$/,
    render: (m) =>
      `床位「${m[2] ?? ""}」（${m[1] ?? ""}）在所选入住日期已被住院号 ${m[3] ?? ""} 占用${untilPhrase(m[4], "至今（仍在住）")}，请改选床位或等该长者离院后再办理。`,
  },
  // ─── 同一长者入住区间与既有入住重叠（409） ─────────────────────────────
  {
    pattern: /^admission interval overlaps existing encounter: encounter_no=(.+) until=(.+)$/,
    render: (m) =>
      `该长者的入住区间与住院号 ${m[1] ?? ""} 重叠（${untilPhrase(m[2], "仍在住")}），请调整入住日期。`,
  },
  // ─── 该长者已有在住记录（V501 部分唯一索引，409） ──────────────────────
  {
    pattern: /^patient already has an active elderly admission(: encounter_no=(.+))?$/,
    render: (m) =>
      m[2]
        ? `该长者已有在住记录（住院号 ${m[2]}），请先办理离院。`
        : "该长者已有在住记录，请先办理离院。",
  },
  // ─── 住院号重复（V502 唯一索引，既有错误串不变，409） ──────────────────
  {
    pattern: /^encounter_no already exists$/,
    render: () => "住院号已存在，请换一个住院号。",
  },
];

/** 是否含中文字符（说明 message 已是本地文案，例如前端自校验抛错） */
const CJK_PATTERN = /[\u4e00-\u9fff]/;

/**
 * 把后端入住域错误映射为中文可操作提示；未命中时返回中文兜底并附原始信息。
 *
 * - 非 `Error` 或无 `message` → 返回 `fallback`；
 * - 命中 [ADMISSION_ERROR_MAPPINGS] → 返回对应中文文案；
 * - 未命中且 `message` 含中文 → 原样返回（已是本地文案）；
 * - 未命中且 `message` 为英文/其它 → 返回 `${fallback}（原始信息：${message}）`。
 */
export function admissionErrorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : undefined;
  const message = typeof raw === "string" ? raw.trim() : "";
  if (!message) return fallback;

  for (const mapping of ADMISSION_ERROR_MAPPINGS) {
    const match = mapping.pattern.exec(message);
    if (match) return mapping.render(match);
  }

  if (CJK_PATTERN.test(message)) return message;
  return `${fallback}（原始信息：${message}）`;
}
