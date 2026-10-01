// Aceso 体检域（批次 / 参检名单 / 结果录入 / 转体征 / 转随访）后端错误文案的中文映射。
//
// 后端沿用 `{ "error": "<message>" }`，message 是英文句式内嵌中文枚举，本期不改后端文案。
// 因此「中文化」只能在前端做：把已知失败条件翻成「中文 + 下一步」的可操作提示；
// 未命中的错误也一律给中文兜底，英文原文只作为次要的排障信息附在后面，
// 绝不把 `internal error` / `{"error":...}` 这类技术原文当主提示展示给用户
//（2026-10-01 缺陷：新建批次 500 时弹窗把 `internal error` 原样显示）。
//
// 口径与 `admissionMessages.ts` / `billingMessages.ts` / `nursingMessages.ts` 一致；
// 判定归服务端，文案归前端。

import { ApiRequestError } from "@pitchfork/shared/aceso";

export interface CheckupErrorMapping {
  /** 后端 `error.message` 的匹配正则（只用 exec，不加 g 标志） */
  pattern: RegExp;
  /** 由捕获组生成中文可操作提示；未用到的捕获组按 `?? ""` 兜底 */
  render: (match: RegExpExecArray) => string;
}

/**
 * 体检域错误映射表：一条 = 一条中文提示 + 其匹配规则。
 * 顺序即优先级，新增文案只追加条目，不改 function 里的分支。
 */
export const CHECKUP_ERROR_MAPPINGS: CheckupErrorMapping[] = [
  // ─── 批次：年度唯一 / 状态机（409 / 400） ──────────────────────────────
  {
    pattern: /^health checkup for year (\d+) already exists$/,
    render: (m) =>
      `已存在 ${m[1] ?? ""} 年度的体检批次；同一年度只能建一个，请直接使用该批次，或换一个年度再建。`,
  },
  {
    pattern: /^health checkup not found: .+$/,
    render: () => "体检批次不存在或已被删除，请刷新批次列表后重试。",
  },
  {
    pattern: /^invalid status transition from (.+) to (.+)$/,
    render: (m) =>
      `批次状态不能从「${m[1] ?? ""}」变更为「${m[2] ?? ""}」；体检批次只能按「草稿 → 进行中 → 已完成」单向流转，且不可回退。`,
  },
  {
    pattern: /^invalid status, must be one of: .+$/,
    render: () => "批次状态不合法，只能在「草稿 / 进行中 / 已完成」中选择。",
  },
  // ─── 批次：入参校验（400） ─────────────────────────────────────────────
  {
    pattern: /^checkup_year must be between 2000 and 2100$/,
    render: () => "体检年度须在 2000–2100 之间。",
  },
  {
    pattern: /^checkup_year is required$/,
    render: () => "请填写体检年度。",
  },
  {
    pattern: /^name is required$/,
    render: () => "请填写批次名称。",
  },
  {
    pattern: /^name must not exceed 100 characters$/,
    render: () => "批次名称不能超过 100 个字符。",
  },
  {
    pattern: /^end_date must not be earlier than start_date$/,
    render: () => "体检结束日期不能早于开始日期。",
  },
  {
    pattern: /^(start_date|end_date) must be an ISO-8601 date$/,
    render: (m) => `${m[1] === "start_date" ? "开始日期" : "结束日期"}格式不正确，请重新选择日期。`,
  },

  // ─── 已完成批次：终态拒绝（400） ───────────────────────────────────────
  {
    pattern: /^cannot add members to a completed checkup$/,
    render: () => "该批次已完成，不能再补录参检名单。",
  },
  {
    pattern: /^cannot add results to a completed checkup$/,
    render: () => "该批次已完成，不能再录入体检结果。",
  },
  {
    pattern: /^cannot update results of a completed checkup$/,
    render: () => "该批次已完成，不能再修正体检结果。",
  },
  {
    pattern: /^cannot convert results of a completed checkup$/,
    render: () => "该批次已完成，不能再做转体征 / 转随访。",
  },

  // ─── 参检名单（404 / 400） ─────────────────────────────────────────────
  {
    pattern: /^patient is not in the active registry: .+$/,
    render: () => "该长者不在在册（ACTIVE）名单中，不能加入体检名单。",
  },
  {
    pattern: /^patient_ids must not contain duplicates$/,
    render: () => "参检名单里有重复的长者，请去重后再提交。",
  },
  {
    pattern: /^patient_ids must not be empty$/,
    render: () => "请至少选择一位参检长者。",
  },
  {
    pattern: /^patient_ids must not exceed 200 entries$/,
    render: () => "一次最多补录 200 位长者，请分批提交。",
  },
  {
    pattern: /^patient_ids is required$/,
    render: () => "请选择要补录的参检长者。",
  },
  {
    pattern: /^patient not found: .+$/,
    render: () => "所选长者不存在或已被删除，请刷新后重试。",
  },
  {
    pattern: /^checkup member not found: .+$/,
    render: () => "该参检记录不存在或已被移除，请刷新名单后重试。",
  },

  // ─── 结果录入 / 修正（400 / 404 / 409） ────────────────────────────────
  {
    pattern: /^checkup result not found: .+$/,
    render: () => "该体检结果不存在或已被删除，请刷新后重试。",
  },
  {
    pattern: /^this result has already been converted to a vital sign$/,
    render: () => "该结果项已转过体征，不能重复转出。",
  },
  {
    pattern: /^this result has already been converted to a followup plan$/,
    render: () => "该结果项已转过随访，不能重复转出。",
  },
  {
    pattern: /^patient has no active encounter, cannot create a followup plan$/,
    render: () => "该长者没有活动就诊 / 入住周期，无法生成随访计划；请先建立活动周期，或改为转体征。",
  },
  {
    pattern: /^abnormal is computed by the server for numeric items$/,
    render: () => "数值项的「异常」由服务端按参考范围自动判定，无需手工填写。",
  },
  {
    pattern: /^text_value is only allowed for text items$/,
    render: () => "文本结论只能填在「文本」类项目上。",
  },
  {
    pattern: /^value\/unit\/ref_min\/ref_max are only allowed for numeric items$/,
    render: () => "数值 / 单位 / 参考范围只能填在「数值」类项目上。",
  },
  {
    pattern: /^text_value\/abnormal are only allowed for text items$/,
    render: () => "文本结论与人工异常标记只能填在「文本」类项目上。",
  },
  {
    pattern: /^ref_min and ref_max must be provided together$/,
    render: () => "参考范围必须同时填写下限与上限。",
  },
  {
    pattern: /^ref_min must not be greater than ref_max$/,
    render: () => "参考范围下限不能大于上限。",
  },
  {
    pattern: /^thresholds min must not be greater than max$/,
    render: () => "参考阈值下限不能大于上限。",
  },
  {
    pattern: /^unit is required for numeric items without a built-in default$/,
    render: () => "该数值项没有内置单位，请填写单位。",
  },
  {
    pattern: /^abnormal is required for text items$/,
    render: () => "文本项需要人工勾选「异常 / 正常」。",
  },
  {
    pattern: /^text_value must not exceed 2000 characters$/,
    render: () => "文本结论不能超过 2000 个字符。",
  },
  {
    pattern: /^unit must not exceed 20 characters$/,
    render: () => "单位不能超过 20 个字符。",
  },
  {
    pattern: /^item_name must not exceed 100 characters$/,
    render: () => "项目名称不能超过 100 个字符。",
  },
  {
    pattern: /^SPO2 must be between 0 and 100$/,
    render: () => "血氧饱和度须在 0–100 之间。",
  },
  {
    pattern: /^exam_date must not be in the future$/,
    render: () => "体检日期不能晚于今天。",
  },
  {
    pattern: /^invalid item_category, must be one of: .+$/,
    render: () => "项目类别不合法，只能选「数值」或「文本」。",
  },

  // ─── 转随访入参（400） ─────────────────────────────────────────────────
  {
    pattern: /^invalid followup_type, must be one of: .+$/,
    render: () => "随访类型不合法，只能选「慢病随访」或「常规电话随访」。",
  },
  {
    pattern: /^followup_type is required$/,
    render: () => "请选择随访类型。",
  },
  {
    pattern: /^remark must not exceed 1000 characters$/,
    render: () => "备注不能超过 1000 个字符。",
  },
];

/** 是否含中文字符（说明 message 已是本地文案，例如前端自校验抛错） */
const CJK_PATTERN = /[\u4e00-\u9fff]/;

/** 通用服务端错误兜底：绝不把异常原文当主提示 */
const SERVER_ERROR_MESSAGE = "服务端错误，请刷新后重试；仍失败请联系运维";

/** `{"error":...}` 之类被整体当成 message 的 JSON 原文，禁止展示 */
function looksLikeJsonError(message: string): boolean {
  return message.startsWith("{") && message.includes("error");
}

/**
 * 把后端体检域错误映射为中文可操作提示。
 *
 * - 非 `Error` 或无 `message` → 返回 `fallback`；
 * - 命中 [CHECKUP_ERROR_MAPPINGS] → 返回对应中文文案；
 * - 未命中且 `message` 含中文 → 原样返回（已是本地文案）；
 * - 未命中且为 5xx / `internal error` / JSON 原文 → 返回通用中文服务端错误文案；
 * - 其余未命中 → `${fallback}（原始信息：${message}）`（英文原文仅作次要排障信息）。
 */
export function checkupErrorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : undefined;
  const message = typeof raw === "string" ? raw.trim() : "";
  if (!message) return fallback;

  for (const mapping of CHECKUP_ERROR_MAPPINGS) {
    const match = mapping.pattern.exec(message);
    if (match) return mapping.render(match);
  }

  if (CJK_PATTERN.test(message)) return message;

  if (error instanceof ApiRequestError && error.status >= 500) {
    return SERVER_ERROR_MESSAGE;
  }

  if (/internal error/i.test(message) || looksLikeJsonError(message)) {
    return SERVER_ERROR_MESSAGE;
  }

  return `${fallback}（原始信息：${message}）`;
}
