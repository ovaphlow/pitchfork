// 课程与章节管理的固定选项常量。
//
// 取值必须与后端枚举 / CHECK 约束完全一致（中文值）：
// - topic ∈ 客流评估与引导 / 基础设施保障 / 自然灾害防范 / 安全应急处置 / 舆情应对（5 值固定）
// - type ∈ 线上授课 / 线下授课
// - status ∈ 启用 / 停用
// - 内容块类型 ∈ 视频 / 图文 / 互动问答
//
// 列表筛选与表单下拉共用本模块常量，保证前端固定选项单一来源；
// 类型标注来自 @pitchfork/shared/merit 客户端（经 lib/merit-client 绑定），
// 编译期保证常量取值与客户端类型一致。

import type {
  ContentBlockType,
  CourseDeliveryType,
  CourseStatus,
  CourseTopic,
} from "./merit-client";

/** 5 个课程专题（固定选项） */
export const COURSE_TOPICS: readonly CourseTopic[] = [
  "客流评估与引导",
  "基础设施保障",
  "自然灾害防范",
  "安全应急处置",
  "舆情应对",
];

/** 课程类型（固定选项：线上授课 / 线下授课） */
export const COURSE_DELIVERY_TYPES: readonly CourseDeliveryType[] = [
  "线上授课",
  "线下授课",
];

/** 课程状态（固定选项：启用 / 停用） */
export const COURSE_STATUSES: readonly CourseStatus[] = ["启用", "停用"];

/** 章节内容块类型（固定选项：视频 / 图文 / 互动问答） */
export const CONTENT_BLOCK_TYPES: readonly ContentBlockType[] = [
  "视频",
  "图文",
  "互动问答",
];

/** 课程列表每页条数（limit 分页参数） */
export const COURSE_PAGE_SIZE = 10;

/** 章节列表一次拉取上限 */
export const CHAPTER_LIST_LIMIT = 200;
