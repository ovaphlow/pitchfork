// 试卷管理的固定选项常量。
//
// 取值必须与后端契约完全一致（中文值）：
// - 组卷策略键 ∈ 单选 / 多选 / 判断 / 填空（题型 → 题目数量）
// - 时长 duration_minutes 为正整数（>0）
// - 及格分 pass_score 为 0–100 整数（0 合法、必填）
// - 策略至少一个题型的数量为正数
//
// 类型标注来自 @pitchfork/shared/merit 客户端（经 lib/merit-client 绑定），
// 编译期保证常量取值与客户端类型一致。

import type { QuestionType } from "./merit-client";

/** 组卷策略可配置的 4 种题型（固定选项） */
export const PAPER_STRATEGY_TYPES: readonly QuestionType[] = [
  "单选",
  "多选",
  "判断",
  "填空",
];

/** 试卷列表每页条数（limit 分页参数） */
export const PAPER_PAGE_SIZE = 10;

/** 时长下限（分钟，>0） */
export const PAPER_DURATION_MIN = 1;

/** 及格分区间 0–100 */
export const PAPER_PASS_SCORE_MIN = 0;
export const PAPER_PASS_SCORE_MAX = 100;
