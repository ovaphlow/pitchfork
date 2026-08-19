// 题库管理的固定选项常量。
//
// 取值必须与后端枚举 / 校验规则完全一致（中文值）：
// - 题型 ∈ 单选 / 多选 / 判断 / 填空（4 值固定）
// - 难度 ∈ 1–5（整数）
// - 判断题答案 ∈ 正确 / 错误
// - 单选/多选 options 至少 2 项；判断/填空无选项（[]）
//
// 列表筛选与表单下拉共用本模块常量，保证前端固定选项单一来源；
// 类型标注来自 @pitchfork/shared/merit 客户端（经 lib/merit-client 绑定），
// 编译期保证常量取值与客户端类型一致。

import type { JudgmentAnswer, QuestionType } from "./merit-client";

/** 4 种题型（固定选项：单选 / 多选 / 判断 / 填空） */
export const QUESTION_TYPES: readonly QuestionType[] = [
  "单选",
  "多选",
  "判断",
  "填空",
];

/** 难度 1–5（固定选项） */
export const QUESTION_DIFFICULTIES: readonly number[] = [1, 2, 3, 4, 5];

/** 判断题答案（固定二选一：正确 / 错误） */
export const JUDGMENT_ANSWERS: readonly JudgmentAnswer[] = ["正确", "错误"];

/** 题目列表每页条数（limit 分页参数） */
export const QUESTION_PAGE_SIZE = 10;

/** 单选/多选 options 最少项数（后端校验下限） */
export const QUESTION_OPTIONS_MIN = 2;
