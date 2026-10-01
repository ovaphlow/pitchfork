/**
 * 执行统计共用口径（`src/components/executionStatistics.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`，与 `datetime.test.ts` 同口径。
 * 运行（在 `ui-astro/` 下）：
 *
 *   node --test apps/aceso/tests/execution-statistics.test.ts
 *
 * 锁定的验收口径（2026-10-01 QA「康复活动两处显示不同步」）：
 *   - 完成率 null（范围内没有已到计划时间的执行）渲染为「暂无应完成任务」，
 *     不是破折号，也不与「计划任务 / 已完成」并排产生 1/1 却是 — 的矛盾；
 *   - 非 null 一律两位小数（照护管理与康复活动同文案）；
 *   - 完成率卡必须同时给出分母「应完成」与分子「已完成（应完成口径）」。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	executionRateCards,
	formatCompletionRate,
	type ExecutionStatisticsRateMeta,
} from "../src/components/executionStatistics.ts";

function meta(overrides: Partial<ExecutionStatisticsRateMeta> = {}): ExecutionStatisticsRateMeta {
	return {
		scheduled_total: 0,
		due_total: 0,
		completed_due_total: 0,
		overdue_total: 0,
		completion_rate: null,
		...overrides,
	};
}

test("完成率为 null 时给出中文说明而不是破折号", () => {
	assert.equal(formatCompletionRate(null), "暂无应完成任务");
	assert.equal(formatCompletionRate(undefined), "暂无应完成任务");
});

test("完成率一律两位小数", () => {
	assert.equal(formatCompletionRate(0), "0.00%");
	assert.equal(formatCompletionRate(100), "100.00%");
	assert.equal(formatCompletionRate(1 / 3 * 100), "33.33%");
	assert.equal(formatCompletionRate(2 / 3 * 100), "66.67%");
});

test("应完成与已完成全部达成时，卡片显示 100.00%", () => {
	const cards = executionRateCards(meta({ scheduled_total: 1, due_total: 1, completed_due_total: 1, completion_rate: 100 }));
	const byLabel = new Map(cards.map((card) => [card.label, card.value]));

	assert.equal(byLabel.get("计划任务"), "1");
	assert.equal(byLabel.get("应完成"), "1");
	assert.equal(byLabel.get("已完成"), "1");
	assert.equal(byLabel.get("计划完成率"), "100.00%");
});

test("尚无可执行任务时：计划 1 / 已完成 1 不再显示成完成率破折号矛盾", () => {
	// QA 场景：今天排了 1 条、提前打卡完成，但计划时间还没到 → due_total = 0、completion_rate = null。
	const cards = executionRateCards(meta({ scheduled_total: 1, due_total: 0, completed_due_total: 0, completion_rate: null }));
	const byLabel = new Map(cards.map((card) => [card.label, card.value]));

	assert.equal(byLabel.get("计划任务"), "1");
	assert.equal(byLabel.get("应完成"), "0", "分母必须可见，才能解释完成率为何没有数值");
	assert.equal(byLabel.get("已完成"), "0");
	assert.equal(byLabel.get("计划完成率"), "暂无应完成任务");
});

test("完成率卡始终成组给出分母与分子", () => {
	const labels = executionRateCards(meta()).map((card) => card.label);
	assert.deepEqual(labels, ["计划任务", "应完成", "已完成", "逾期", "计划完成率"]);
});

test("逾期与低于 60% 的完成率带高亮，正常值不高亮", () => {
	const healthy = executionRateCards(meta({ due_total: 2, completed_due_total: 2, completion_rate: 100 }));
	assert.equal(healthy.find((card) => card.label === "逾期")?.highlight, false);
	assert.equal(healthy.find((card) => card.label === "计划完成率")?.highlight, false);

	const risky = executionRateCards(meta({ due_total: 5, completed_due_total: 1, overdue_total: 3, completion_rate: 20 }));
	assert.equal(risky.find((card) => card.label === "逾期")?.highlight, true);
	assert.equal(risky.find((card) => card.label === "计划完成率")?.highlight, true);
});
