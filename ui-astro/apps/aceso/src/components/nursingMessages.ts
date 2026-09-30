// Aceso 护理域（给药记录 / 发药来源）后端错误文案的中文映射。
//
// 后端沿用 `{ "error": "<message>" }`，message 是英文句式内嵌中文枚举，本期不改后端文案。
// 因此「中文化」只能在前端做：把已知失败条件翻成「中文 + 下一步」的可操作提示；
// 未命中的错误也一律给中文兜底，英文原文只作为次要的排障信息附在后面，绝不成为主提示，
// 更不允许把 `{"error":...}` 这类 JSON 原文直接展示。
//
// 口径与 `admissionMessages.ts` / `billingMessages.ts` 一致；状态判定归服务端，文案归前端。

import { ApiRequestError } from "@pitchfork/shared/aceso";

export interface NursingErrorMapping {
  /** 后端 `error.message` 的匹配正则（只用 exec，不加 g 标志） */
  pattern: RegExp;
  /** 由捕获组生成中文可操作提示；未用到的捕获组按 `?? ""` 兜底 */
  render: (match: RegExpExecArray) => string;
}

/** 执行终态 → 中文短语（用于「该执行已…」句式） */
function nursingExecutionStatusZh(value: string): string {
  return { COMPLETED: "已完成", SKIPPED: "已跳过", CANCELLED: "已取消" }[value] ?? value;
}

/** 护理任务状态 → 中文短语（用于「当前：…」句式） */
function nursingTaskStatusZh(value: string): string {
  return { ACTIVE: "执行中", COMPLETED: "已完成", CANCELLED: "已取消" }[value] ?? value;
}

/** 医嘱状态 → 中文短语（用于「当前：…」句式） */
function nursingOrderStatusZh(value: string): string {
  return { ACTIVE: "进行中", COMPLETED: "已完成", DISCONTINUED: "已停止", CANCELLED: "已取消", EXPIRED: "已到期" }[value] ?? value;
}

/**
 * 给药记录与发药来源端点错误映射表：一条 = 一条中文提示 + 其匹配规则。
 * 顺序即优先级，新增文案只追加条目，不改 function 里的分支。
 */
export const NURSING_ERROR_MAPPINGS: NursingErrorMapping[] = [
  // ─── 执行终态 / 重复记录（409） ───────────────────────────────────────
  {
    pattern: /^execution is already (.+), cannot record administration$/,
    render: (m) => `该执行${nursingExecutionStatusZh(m[1])}，不能再记录给药；如需留痕请查看给药记录。`,
  },
  {
    pattern: /^execution already has an administration record, cannot record twice$/,
    render: () => "该执行已有给药记录，不能重复记录；如需留痕请查看给药记录。",
  },

  // ─── 数量对账（409） ─────────────────────────────────────────────────
  {
    pattern: /^administered quantity exceeds dispensed remaining quantity \(remaining: (.+)\)$/,
    render: (m) => `给药数量超过该来源剩余可给数量（剩余 ${m[1]}），请调低数量或换用其他发药明细。`,
  },

  // ─── 发药明细门禁（404/409） ─────────────────────────────────────────
  {
    pattern: /^dispense is not DISPENSED, cannot administer from it$/,
    render: () => "该发药明细尚未完成发药（状态不是已发药），不能用于记录给药。",
  },
  {
    pattern: /^dispense item has no dispensed quantity$/,
    render: () => "该发药明细没有实发数量，不能用于记录给药。",
  },
  {
    pattern: /^dispense item does not belong to this medical order$/,
    render: () => "该发药明细不属于这条医嘱，请刷新来源列表后重新选择。",
  },
  {
    pattern: /^dispense item not found: .+$/,
    render: () => "所选发药明细不存在或已被删除，请刷新来源列表后重新选择。",
  },

  // ─── 任务 / 医嘱门禁（400/409） ─────────────────────────────────────
  {
    pattern: /^task is not active: (.+)$/,
    render: (m) => `该护理任务已不是执行中状态（当前：${nursingTaskStatusZh(m[1])}），不能记录给药。`,
  },
  {
    pattern: /^order is not active: (.+)$/,
    render: (m) => `该医嘱已不是进行中状态（当前：${nursingOrderStatusZh(m[1])}），不能记录给药。`,
  },
  {
    pattern: /^order has not been nurse-checked$/,
    render: () => "该医嘱尚未经护士核对；请先到「医嘱核对」确认后，药房才能发药、也才能记录给药。",
  },
  {
    pattern: /^order is not a medication order$/,
    render: () => "关联医嘱不是用药医嘱，不能记录给药。",
  },
  {
    pattern: /^task is not linked to a medical order$/,
    render: () => "该护理任务未关联用药医嘱，不能记录给药。",
  },
  {
    pattern: /^only MEDICATION tasks can record administration$/,
    render: () => "只有用药任务才能记录给药。",
  },
  {
    pattern: /^only MEDICATION tasks have administration sources$/,
    render: () => "只有用药任务才有发药来源。",
  },
  {
    pattern: /^execution has no task$/,
    render: () => "该执行未关联护理任务，无法加载给药信息。",
  },

  // ─── 入参校验（400） ────────────────────────────────────────────────
  {
    pattern: /^reason is required for result (.+)$/,
    render: (m) => `该给药结果（${m[1]}）必须填写原因。`,
  },
  {
    pattern: /^invalid result, must be one of: .+$/,
    render: () => "给药结果不合法，请从已服/部分服/拒服/漏服/暂缓中选择。",
  },
  {
    pattern: /^invalid result: .+$/,
    render: () => "给药结果不合法，请从已服/部分服/拒服/漏服/暂缓中选择。",
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
 * 把后端给药记录/发药来源错误映射为中文可操作提示。
 *
 * - 非 `Error` 或无 `message` → 返回 `fallback`；
 * - 命中 [NURSING_ERROR_MAPPINGS] → 返回对应中文文案；
 * - 未命中且 `message` 含中文 → 原样返回（已是本地文案）；
 * - 未命中且为 5xx / `internal error` / JSON 原文 → 返回通用服务端错误文案；
 * - 其余未命中 → `${fallback}（原始信息：${message}）`（英文原文仅作次要排障信息）。
 */
export function nursingErrorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : undefined;
  const message = typeof raw === "string" ? raw.trim() : "";
  if (!message) return fallback;

  for (const mapping of NURSING_ERROR_MAPPINGS) {
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
