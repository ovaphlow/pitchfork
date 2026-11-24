/**
 * 首页统计的纯展示口径。
 *
 * 数据读取留在 `DashboardPage`，这里只处理跨接口一致的计数与金额格式，
 * 便于用 `node:test` 锁定首页不会把护理任务状态或金额展示错。
 */

function nonNegativeCount(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : 0;
}

/**
 * 首页“待处理护理”口径：待执行 + 执行中。
 *
 * 已完成、已跳过、已取消均不属于当前待处理量。
 */
export function nursingAttentionCount(
  pending: number | null | undefined,
  inProgress: number | null | undefined,
): number {
  return (
    nonNegativeCount(pending) +
    nonNegativeCount(inProgress)
  );
}

/**
 * 首页优先展示跨日全量逾期；旧接口未返回该字段时回退到当天逾期数。
 */
export function nursingOverdueCount(
  todayOverdue: number | null | undefined,
  allOverdue: number | null | undefined,
): number {
  return allOverdue === undefined || allOverdue === null
    ? nonNegativeCount(todayOverdue)
    : nonNegativeCount(allOverdue);
}

/** 人民币金额固定两位小数，并带千分位。 */
export function formatYuan(value: number): string {
  const safe = Number.isFinite(value) ? value : 0;
  return `¥${new Intl.NumberFormat("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(safe)}`;
}

/** 整数统计使用千分位；异常值按 0 展示。 */
export function formatCount(value: number): string {
  return new Intl.NumberFormat("zh-CN").format(nonNegativeCount(value));
}
