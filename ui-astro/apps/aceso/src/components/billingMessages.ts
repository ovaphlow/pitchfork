// Aceso 收费域（费用字典 / 账单 / 缴费 / 押金核销）后端错误文案的中文映射。
//
// 后端沿用 `{ "error": "<message>" }`，message 是英文句式内嵌中文枚举，且被 Kotlin 侧
// `contains(fragment)` 断言锁定，本期不改后端文案。因此「中文化」只能在前端做：
// 把已知失败条件翻成「中文 + 去哪修」的可操作提示；未命中的错误也一律给中文兜底，
// 英文原文只作为次要的可排障信息附在后面，绝不成为主提示。

import type { BillPrecheckBlockedBy } from "@pitchfork/shared/aceso";

/** 费用项目维护页路径：字典缺项时的「去配置」入口 */
export const FEE_ITEMS_PAGE_PATH = "/dashboard/fee-items";

/** 「系统设置 → 费用项目」在文案里的统一写法 */
const FEE_ITEMS_LOCATION = "「系统设置 → 费用项目」";

/**
 * 生成账单前置校验（precheck）的 `blocked_by` 枚举 → 中文可操作提示。
 *
 * 判定归服务端（只回机器可读枚举），文案归前端；`missing_fee_items` 的逐条缺项
 * 由页面按 `requirements` 渲染，这里只给一句总览。
 */
export const BILLING_BLOCKED_REASONS: Record<NonNullable<BillPrecheckBlockedBy>, string> = {
  missing_fee_items: `费用字典缺项，请先到${FEE_ITEMS_LOCATION}补齐并启用所需项目。`,
  already_exists: "该账期已经生成过账单，不能重复生成。",
  not_overlapping: "所选账期与该入住的在院区间没有重合。",
  no_admit_date: "该入住缺少入住日期，无法计费。",
  future_month: "该账期尚未开始，不能提前生成。",
  settled: "该入住已关账，不能生成账单。",
  not_elderly_admission: "只有养老入住可以生成账单。",
};

export interface BillingErrorMapping {
  /** 后端 `error.message` 的匹配正则（只用 exec，不加 g 标志） */
  pattern: RegExp;
  /** 由捕获组生成中文可操作提示；未用到的捕获组按 `?? ""` 兜底 */
  render: (match: RegExpExecArray) => string;
}

/**
 * 收费域错误映射表：一条 = 一条中文提示 + 其匹配规则。
 * 顺序即优先级，新增文案只追加条目，不改 function 里的分支。
 */
export const BILLING_ERROR_MAPPINGS: BillingErrorMapping[] = [
  // ─── 费用字典缺项 / 重项（BillService.requireSingleEnabled） ───────────
  {
    pattern: /^no enabled fee item for category (.+)$/,
    render: (m) => `未配置启用的「${m[1] ?? ""}」费用项目。请到${FEE_ITEMS_LOCATION}新增并启用一条「${m[1] ?? ""}」。`,
  },
  {
    pattern: /^no enabled fee item for nursing level (.+)$/,
    render: (m) =>
      `护理等级「${m[1] ?? ""}」没有对应的启用护理费项目。请到${FEE_ITEMS_LOCATION}新增（或编辑）一条「护理费」，` +
      `把它的「护理等级」绑定为「${m[1] ?? ""}」——计费按绑定的等级匹配，与项目名称无关。`,
  },
  {
    pattern: /^multiple enabled fee items for category (.+), expected exactly one$/,
    render: (m) => `「${m[1] ?? ""}」存在多条启用的费用项目，请到${FEE_ITEMS_LOCATION}只保留一条启用。`,
  },
  {
    pattern: /^multiple enabled fee items for nursing level (.+), expected exactly one$/,
    render: (m) => `护理等级「${m[1] ?? ""}」存在多条启用的护理费项目，请到${FEE_ITEMS_LOCATION}只保留一条启用。`,
  },
  {
    pattern: /^fee item not found/,
    render: () => "所选费用项目不存在或已被删除，请刷新后重新选择。",
  },
  {
    pattern: /^fee item is disabled/,
    render: () => `该费用项目已停用，请到${FEE_ITEMS_LOCATION}启用后再试。`,
  },

  // ─── 结算关账状态（BillService / PaymentService / DepositOffsetService） ─
  {
    pattern: /^encounter billing is already settled$/,
    render: () => "该入住已关账，不能重复关账。",
  },
  {
    pattern: /^encounter billing is settled, cannot pay$/,
    render: () => "该入住已关账，不能再缴费。",
  },
  {
    pattern: /^encounter billing is settled, cannot generate bills$/,
    render: () => "该入住已关账，不能生成账单。",
  },
  {
    pattern: /^encounter billing is settled, cannot add items$/,
    render: () => "该入住已关账，不能手工加项。",
  },
  {
    pattern: /^bill is settled, cannot add items$/,
    render: () => "该账单已结清或已结算，不能手工加项。",
  },
  {
    pattern: /^bill status is not 待缴费, cannot pay$/,
    render: () => "该账单不是「待缴费」状态，不能缴费。",
  },
  {
    pattern: /^bill status is not 待缴费, cannot add items$/,
    render: () => "该账单不是「待缴费」状态，不能手工加项。",
  },

  // ─── 金额上限 ────────────────────────────────────────────────────────
  {
    pattern: /^deposit offset exceeds available: available (.+), requested (.+)$/,
    render: (m) =>
      `押金核销金额超出可用上限 ¥${m[1] ?? ""}（取「押金余额」与「欠费合计」的较小值）。`,
  },
  {
    pattern: /^refund exceeds deposit balance: available (.+), requested (.+)$/,
    render: (m) => `退押金额超过当前押金余额 ¥${m[1] ?? ""}。`,
  },
  {
    pattern: /^payment exceeds bill total: bill (.+), already paid (.+), requested (.+)$/,
    render: (m) => `缴费金额超过账单剩余应缴（账单合计 ¥${m[1] ?? ""}，已缴 ¥${m[2] ?? ""}）。`,
  },

  // ─── 账期与重复生成（BillService） ────────────────────────────────────
  {
    pattern: /already exists/,
    render: () => "该账期已经生成过账单，不能重复生成。",
  },
  {
    pattern: /^encounter is not discharged or deceased/,
    render: () => "结算关账只适用于已离院/已去世的养老入住。",
  },
  {
    pattern: /^encounter is not an elderly admission/,
    render: () => "该入住不是养老入住，不适用养老收费与离院结算。",
  },
  {
    pattern: /^(?:encounter has no discharge date|encounter has no death date)/,
    render: () => "该入住缺少离院/去世日期，无法结算关账。",
  },
  {
    pattern: /^encounter has no admit date/,
    render: () => "该入住缺少入住日期，无法计费。",
  },
  {
    pattern: /^encounter does not overlap month/,
    render: () => "所选账期与该入住的在院区间没有重合。",
  },
  {
    pattern: /^unsupported /,
    render: () => "请求包含不受支持的字段，请刷新页面重试。",
  },
  {
    pattern: /^deposit_offset/,
    render: () => "押金核销金额必须是不超过两位小数的正数。",
  },
  {
    pattern: /^(?:month must be in YYYY-MM format|month is required)/,
    render: () => "账期格式应为 YYYY-MM。",
  },
  {
    // 031 W4 保守缺省：账期晚于机构时区当前月。precheck 走 `blocked_by=future_month`，
    // 这里是「直接调生成接口」的 400 路径（如弹窗账期竞态、降级直调），同样不得透出英文。
    pattern: /^month is in the future, cannot generate bills$/,
    render: () => "该账期尚未开始，不能提前生成。",
  },

  // ─── 关账未结余额与减免（BillService.settleEncounter） ─────────────────
  {
    pattern: /^unsettled bills require explicit write-off: outstanding (.+)$/,
    render: (m) =>
      `关账时仍有未结余额 ¥${m[1] ?? ""}，需要显式确认减免并填写原因后重新提交。`,
  },
  {
    pattern: /^write_off_reason must not exceed 500 characters$/,
    render: () => "减免原因不能超过 500 个字符，请精简后重新提交。",
  },
  {
    pattern: /^write_off_reason must be a string$/,
    render: () => "减免原因必须是文本，请重新填写。",
  },
  {
    pattern: /^write_off_reason/,
    render: () => "减免原因不合法（须为不超过 500 字符的文本），请重新填写。",
  },

  // ─── 红冲（034 A2：POST /healthcare/v1/bills/{id}/reversal） ───────────
  // 后端原句按同一风格给英文可映射消息；这里沿用本文件既有做法——按**关键字子串**匹配
  // （同 `/already exists/`），因此对「同一条件的不同后缀」（如 `, cannot reverse bills`、
  // 已红冲后附账单 ID）同样命中；未命中的变体仍走中文兜底 + 原文附注。
  {
    pattern: /cannot reverse a reversal bill/,
    render: () =>
      "该账单本身是红冲生成的「红冲单」，不能再被红冲。请对它的原始账单操作，或在需要时重新生成该账期账单。",
  },
  {
    pattern: /already reversed|has been reversed|is reversed/,
    render: () => "该账单已被红冲过，不能重复红冲。刷新账单列表可看到冲销它的红字单。",
  },
  {
    pattern: /bill is not payable|bill status is not 待缴费/,
    render: () => "该账单不是「待缴费」状态，不能红冲（已结清/已结算说明已发生收款或已关账）。",
  },
  {
    pattern: /encounter (?:billing )?is settled/,
    render: () => "该入住已关账，账单已冻结，不能红冲。",
  },
  {
    pattern: /bill (?:already )?has payments/,
    render: () => "该账单已有缴费记录，不能红冲。请先退款或冲正缴费流水，再红冲原单。",
  },
  {
    pattern: /^bill not found/,
    render: () => "要红冲的账单不存在或已被删除，请刷新账单列表后重试。",
  },
  {
    // 兜底：任何提到 reason 的 400（缺 reason / 空原因 / 非字符串 / 超过 500 字符等措辞变体）。
    // 必须排在 `write_off_reason` 之后，否则会抢走减免原因的专属文案。
    pattern: /reason/,
    render: () => "红冲原因不合法（必填，且 trim 后不超过 500 字符的文本），请重新填写。",
  },
];

/** 是否含中文字符（说明 message 已是本地文案，例如前端自校验抛错） */
const CJK_PATTERN = /[\u4e00-\u9fff]/;

/**
 * 把后端收费域错误映射为中文可操作提示；未命中时返回中文兜底并附原始信息。
 *
 * - 非 `Error` 或无 `message` → 返回 `fallback`；
 * - 命中 [BILLING_ERROR_MAPPINGS] → 返回对应中文文案；
 * - 未命中且 `message` 含中文 → 原样返回（已是本地文案）；
 * - 未命中且 `message` 为英文/其它 → 返回 `${fallback}（原始信息：${message}）`。
 */
export function billingErrorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : undefined;
  const message = typeof raw === "string" ? raw.trim() : "";
  if (!message) return fallback;

  for (const mapping of BILLING_ERROR_MAPPINGS) {
    const match = mapping.pattern.exec(message);
    if (match) return mapping.render(match);
  }

  if (CJK_PATTERN.test(message)) return message;
  return `${fallback}（原始信息：${message}）`;
}
