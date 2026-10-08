/**
 * 成长曲线纯函数（`src/lib/growth.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   node --test apps/aceso/tests/growth.test.ts
 *
 * 锁定验收口径 §9.2：出生当天、跨月、参考点 z≈0、P50 中位、参考带单调性、
 * 超范围不外推，以及 WHO LMS 常量的完整性（61 个整月点 × 3 指标 × 2 性别）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	GROWTH_HIGH_PERCENTILE,
	GROWTH_LOW_PERCENTILE,
	MAX_CURVE_MONTH,
	REFERENCE_PERCENTILES,
	describePercentile,
	evaluateGrowth,
	evaluateGrowthSnapshot,
	growthCurve,
	isCurveSupported,
	isGrowthOutlier,
	lmsAt,
	normalCdf,
	sexFromGender,
	snapshotOf,
	valueForZ,
} from "../src/lib/growth.ts";
import { WHO_LMS_0_60 } from "../src/lib/whoLms0to60.ts";

test("WHO LMS 常量完整：61 个整月点 × 3 指标 × 2 性别", () => {
	(["male", "female"] as const).forEach((sex) => {
		(["height", "weight", "head"] as const).forEach((metric) => {
			const rows = WHO_LMS_0_60[sex][metric];
			assert.equal(rows.length, MAX_CURVE_MONTH + 1, `${sex}/${metric} 必须有 61 行`);
			rows.forEach((row, index) => {
				assert.equal(row[0], index, `${sex}/${metric} 第 ${index} 行的月龄必须是 ${index}`);
				assert.ok(row[2] > 0, `${sex}/${metric} 第 ${index} 行的 M 必须为正`);
				assert.ok(row[3] > 0, `${sex}/${metric} 第 ${index} 行的 S 必须为正`);
			});
		});
	});
	// 与 WHO 公布值对齐的锚点（出生体重中位数 / 出生身长中位数）
	assert.equal(WHO_LMS_0_60.male.weight[0][2], 3.3464);
	assert.equal(WHO_LMS_0_60.male.height[0][2], 49.8842);
	assert.equal(WHO_LMS_0_60.female.weight[0][2], 3.2322);
});

test("出生当天与跨月边界", () => {
	assert.equal(isCurveSupported(0), true);
	assert.equal(isCurveSupported(0.5), true);
	assert.equal(isCurveSupported(MAX_CURVE_MONTH), true);
	assert.equal(isCurveSupported(60.1), false);
	assert.equal(isCurveSupported(-1), false);
	assert.equal(lmsAt("male", "weight", 0)?.[0], 0);
	assert.equal(lmsAt("male", "weight", 60)?.[0], 60);
	assert.equal(lmsAt("male", "weight", 61), null);
});

test("相邻整月线性插值（0.5 月落在 0 与 1 月之间）", () => {
	const zero = lmsAt("male", "weight", 0);
	const one = lmsAt("male", "weight", 1);
	const half = lmsAt("male", "weight", 0.5);
	assert.ok(zero && one && half);
	assert.equal(Math.round(half[2] * 10000) / 10000, Math.round(((zero[2] + one[2]) / 2) * 10000) / 10000);
});

test("参考点 z ≈ 0：中位值处百分位 ≈ 50", () => {
	// 出生体重中位 3.3464kg → P50
	const atMedian = evaluateGrowth(3.3464, "male", "weight", 0);
	assert.ok(atMedian, "中位值必须能评价");
	assert.ok(Math.abs(atMedian.z) < 0.01, `z 应≈0，实际 ${atMedian.z}`);
	assert.ok(Math.abs(atMedian.p - 50) < 0.6, `P 应≈50，实际 ${atMedian.p}`);

	// 12 月龄男童身长中位（75.7488cm）
	const heightAt12 = evaluateGrowth(75.7488, "male", "height", 12);
	assert.ok(heightAt12 && Math.abs(heightAt12.p - 50) < 0.6);
});

test("P50 中位反解与参考带单调性", () => {
	[0, 6, 12, 24, 36, 48, 60].forEach((month) => {
		(["male", "female"] as const).forEach((sex) => {
			(["height", "weight", "head"] as const).forEach((metric) => {
				const median = valueForZ(sex, metric, month, 0);
				const row = lmsAt(sex, metric, month);
				assert.ok(median !== null && row !== null);
				assert.ok(Math.abs(median - row[2]) < 1e-6, "z=0 必须反解回 M（中位数）");

				const curve = growthCurve(sex, metric, month, month)[0];
				const values = REFERENCE_PERCENTILES.map(({ p }) => curve.values[String(p)]);
				for (let i = 1; i < values.length; i += 1) {
					assert.ok(
						values[i] > values[i - 1],
						`${sex}/${metric}/${month} 参考带必须随百分位单调递增：${values.join(",")}`,
					);
				}
				assert.ok(Math.abs(values[2] - row[2]) < 0.01, "P50 必须等于中位数（允许展示层 2 位小数四舍五入）");
			});
		});
	});
});

test("z 符号与百分位随数值单调", () => {
	const below = evaluateGrowth(8, "male", "weight", 12);
	const above = evaluateGrowth(12, "male", "weight", 12);
	assert.ok(below && above);
	assert.ok(below.z < 0, "低于中位数 z 必须为负");
	assert.ok(above.z > 0, "高于中位数 z 必须为正");
	assert.ok(below.p < above.p, "百分位必须随数值单调递增");
});

test("P3/P97 阈值与异常判定一致", () => {
	const low = valueForZ("male", "weight", 12, REFERENCE_PERCENTILES[0].z);
	const high = valueForZ("male", "weight", 12, REFERENCE_PERCENTILES[4].z);
	assert.ok(low !== null && high !== null);
	const atLow = evaluateGrowth(low, "male", "weight", 12);
	const atHigh = evaluateGrowth(high, "male", "weight", 12);
	assert.ok(atLow && atHigh);
	assert.ok(Math.abs(atLow.p - 3) < 0.1, `P3 反解应≈3，实际 ${atLow.p}`);
	assert.ok(Math.abs(atHigh.p - 97) < 0.1, `P97 反解应≈97，实际 ${atHigh.p}`);
	assert.equal(isGrowthOutlier(atLow), false, "P3 边界含在内，不算异常");
	assert.equal(isGrowthOutlier({ p: 2.9, z: -1.9 }), true);
	assert.equal(isGrowthOutlier({ p: 97.1, z: 1.9 }), true);
	assert.equal(GROWTH_LOW_PERCENTILE, 3);
	assert.equal(GROWTH_HIGH_PERCENTILE, 97);
});

test("超出 0–5 岁或性别未知时不评价（不外推）", () => {
	assert.equal(evaluateGrowth(20, "male", "weight", 61), null);
	assert.equal(evaluateGrowth(20, null, "weight", 12), null);
	assert.equal(evaluateGrowth(0, "male", "weight", 12), null, "非正数非法");
	assert.equal(evaluateGrowth(Number.NaN, "male", "weight", 12), null);
	assert.equal(valueForZ("male", "weight", 99, 0), null);
	assert.equal(sexFromGender("男"), "male");
	assert.equal(sexFromGender("女"), "female");
	assert.equal(sexFromGender("未知"), null);
	assert.equal(sexFromGender(null), null);
});

test("一次会话的评价快照只含提交的指标", () => {
	const snapshot = evaluateGrowthSnapshot("female", 6, { height: 65.9, weight: null, head: 42.5 });
	assert.deepEqual(Object.keys(snapshot).sort(), ["head", "height"]);
	assert.ok(snapshot.height && snapshot.head);
	assert.equal(snapshot.weight, undefined);
});

test("快照读回与展示文本", () => {
	const snapshot = { height: { p: 25.4, z: -0.66 }, weight: { p: 88 } };
	assert.deepEqual(snapshotOf(snapshot, "height"), { p: 25.4, z: -0.66 });
	assert.deepEqual(snapshotOf(snapshot, "weight"), { p: 88, z: 0 }, "缺 z 时回退 0，不丢百分位");
	assert.equal(snapshotOf(snapshot, "head"), null);
	assert.equal(describePercentile("height", { p: 25.4, z: -0.66 }), "身高 P25.4");
	assert.equal(describePercentile("weight", null), "超出曲线范围（0–5 岁）");
});

test("正态 CDF 近似在关键分位点误差可忽略", () => {
	assert.ok(Math.abs(normalCdf(0) - 0.5) < 1e-7);
	assert.ok(Math.abs(normalCdf(-1.88079) - 0.03) < 1e-5);
	assert.ok(Math.abs(normalCdf(1.88079) - 0.97) < 1e-5);
});
