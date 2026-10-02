/**
 * 月龄派生（`src/lib/age.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`，与 `datetime.test.ts` /
 * `domain-labels.test.ts` 同口径。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   TZ=UTC node --test apps/aceso/tests/age.test.ts
 *
 * 所有断言都用固定 `now`，不依赖运行机器的当前日期；纯日期输入与业务时区
 * （Asia/Shanghai）instant 的组合进一步锁定「结果与主机时区无关」。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { formatAge } from "../src/lib/age.ts";

/** 业务时区 2026-03-15 的参考时刻（+08:00），当天 00:00。 */
const NOW = new Date("2026-03-15T00:00:00+08:00");

test("出生当天 → 0 天", () => {
	assert.equal(formatAge("2026-03-15", NOW), "0 天");
});

test("不足 1 个月 → N 天", () => {
	// 2026-02-16 → 2026-03-15：日 15 < 16，未满一个月 → 27 天
	assert.equal(formatAge("2026-02-16", NOW), "27 天");
	// 出生次日起算
	assert.equal(formatAge("2026-03-14", NOW), "1 天");
});

test("恰 1 个月 → 1 月；日不足则退一个月", () => {
	assert.equal(formatAge("2026-02-15", NOW), "1 月");
	// 月末边界：1/31 → 2/28 未满一个整月，仍按天数
	const feb28 = new Date("2026-02-28T00:00:00+08:00");
	assert.equal(formatAge("2026-01-31", feb28), "28 天");
});

test("1–23 个月 → N 月", () => {
	assert.equal(formatAge("2024-04-15", NOW), "23 月");
	assert.equal(formatAge("2026-01-15", NOW), "2 月");
});

test("恰 24 个月 → 2 岁 0 月；超过后 → N 岁 M 月", () => {
	assert.equal(formatAge("2024-03-15", NOW), "2 岁 0 月");
	assert.equal(formatAge("2023-01-15", NOW), "3 岁 2 月");
});

test("闰日出生按日历月推进（锁定纯日历差口径）", () => {
	// 2024-02-29 → 2025-02-28：日 28 < 29，未满 12 个整月 → 11 月
	assert.equal(formatAge("2024-02-29", new Date("2025-02-28T00:00:00+08:00")), "11 月");
});

test("空 / 非法 → -", () => {
	assert.equal(formatAge(null, NOW), "-");
	assert.equal(formatAge(undefined, NOW), "-");
	assert.equal(formatAge("", NOW), "-");
	assert.equal(formatAge("   ", NOW), "-");
	assert.equal(formatAge("not-a-date", NOW), "-");
	assert.equal(formatAge("2026-02-30", NOW), "-");
});

test("instant 出生日期按业务时区取日期（主机时区无关）", () => {
	// 该 instant 在 UTC 下是 2026-03-14T16:00Z，业务区是 2026-03-15 → 出生当天
	assert.equal(formatAge("2026-03-15T00:00:00+08:00", NOW), "0 天");
});

test("now 取业务时区当天（主机时区无关）", () => {
	// 2026-03-15T00:30+08:00 在 UTC 下仍是 3/14；若误用主机时区会得到「1 天」
	const shanghaiMidnight = new Date("2026-03-15T00:30:00+08:00");
	assert.equal(formatAge("2026-03-15", shanghaiMidnight), "0 天");
	assert.equal(formatAge("2026-02-15", shanghaiMidnight), "1 月");
});
