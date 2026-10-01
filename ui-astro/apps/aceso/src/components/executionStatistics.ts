// Aceso 护理 / 康复执行统计的**共用口径**（照护管理页与康复活动页）。
//
// 背景（2026-10-01 QA）：两个页面各写了一套完成率展示，出现两处分叉——
//   1) 康复活动把「计划次数 / 已完成（全部）」与「completion_rate（应完成口径）」并排，
//      于是出现「计划 1 / 已完成 1，完成率却是 —」的自相矛盾；
//   2) 同一个 null 值在照护管理显示「暂无应完成任务」，在康复活动显示「—」。
//
// 后端语义（本模块不改动、只做中文呈现）：completion_rate =
// completed_due_total / due_total，其中 due_total = 计划时间已到
// （planned_time < now）的条数，due_total = 0 时返回 null
// （见 libs/nursing TaskExecutionService.completionRate）。
//
// 因此「完成率格式化」与「完成率指标卡」收敛到本模块，两个页面共用，
// 同一份 meta 必然渲染成同一口径、同一文案，不会再各自漂移。

/** 完成率口径所需字段的结构化类型；两个页面各自的 meta 类型都可直接赋值 */
export interface ExecutionStatisticsRateMeta {
  scheduled_total: number;
  due_total: number;
  completed_due_total: number;
  overdue_total: number;
  completion_rate: number | null;
}

export interface ExecutionStatCard {
  label: string;
  value: string;
  highlight?: boolean;
}

/**
 * 完成率展示：两位小数；null 表示范围内「还没有到执行时间的任务」，
 * 给中文说明而不是破折号，避免用户把它误读成「算不出来 / 0%」。
 */
export function formatCompletionRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return "暂无应完成任务";
  return `${value.toFixed(2)}%`;
}

/**
 * 完成率指标卡：「应完成 / 已完成」必须与「计划完成率」同时出现——
 * 完成率的分母是「应完成（已到计划时间）」而不是「计划任务」，
 * 否则该卡会被读成「已完成 / 计划次数」，进而与本卡其它数字打架。
 */
export function executionRateCards(meta: ExecutionStatisticsRateMeta): ExecutionStatCard[] {
  return [
    { label: "计划任务", value: String(meta.scheduled_total) },
    { label: "应完成", value: String(meta.due_total) },
    { label: "已完成", value: String(meta.completed_due_total) },
    { label: "逾期", value: String(meta.overdue_total), highlight: meta.overdue_total > 0 },
    {
      label: "计划完成率",
      value: formatCompletionRate(meta.completion_rate),
      highlight: meta.completion_rate !== null && meta.completion_rate < 60,
    },
  ];
}
