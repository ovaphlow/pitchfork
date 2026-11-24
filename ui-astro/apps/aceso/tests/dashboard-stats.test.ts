/**
 * 首页运营统计的展示口径。
 *
 * 运行（在 `ui-astro/` 下）：
 *   node --test apps/aceso/tests/dashboard-stats.test.ts
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  formatCount,
  formatYuan,
  nursingAttentionCount,
  nursingOverdueCount,
} from "../src/components/dashboardStats.ts";

const PAGE_SOURCE = readFileSync(
  new URL("../src/components/DashboardPage.tsx", import.meta.url),
  "utf8",
);

test("护理待处理只合计待执行与执行中", () => {
  assert.equal(nursingAttentionCount(4, 2), 6);
});

test("护理状态计数缺失或异常时按 0 处理", () => {
  assert.equal(nursingAttentionCount(undefined, undefined), 0);
  assert.equal(nursingAttentionCount(-1, Number.NaN), 0);
});

test("首页优先展示跨日全量逾期，缺失时回退当天逾期", () => {
  assert.equal(nursingOverdueCount(2, 5), 5);
  assert.equal(nursingOverdueCount(2, undefined), 2);
  assert.equal(nursingOverdueCount(undefined, null), 0);
});

test("金额固定两位小数并带人民币符号与千分位", () => {
  assert.equal(formatYuan(0), "¥0.00");
  assert.equal(formatYuan(12345.6), "¥12,345.60");
  assert.equal(formatYuan(Number.NaN), "¥0.00");
});

test("计数使用千分位，异常值按 0 展示", () => {
  assert.equal(formatCount(1234), "1,234");
  assert.equal(formatCount(-3), "0");
});

test("首页护理统计只读，不调用会补齐执行记录的今日看板接口", () => {
  assert.match(PAGE_SOURCE, /listNursingExecutionStatistics\(/);
  assert.doesNotMatch(PAGE_SOURCE, /listNursingTodayExecutions\(/);
  assert.doesNotMatch(PAGE_SOURCE, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
});
