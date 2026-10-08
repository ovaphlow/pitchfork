/**
 * 儿童发育筛查量表包与评分引擎（`src/lib/developmentalScales.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   node --test apps/aceso/tests/developmental.test.ts
 *
 * 锁定与后端 A1 `DevelopmentalScale.kt` 逐条一致的：量表编码 / 版本 / 领域 / 条目编码 /
 * 适龄区间 / 选项 / 切点 0.90、0.70，以及计分、触发项、领域分/满分与校验语义。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	CLASSIFICATIONS,
	DOMAIN_FINE_MOTOR,
	DOMAIN_GROSS_MOTOR,
	DOMAIN_LANGUAGE,
	DOMAINS,
	NORMAL_THRESHOLD,
	PLACEHOLDER_SCALE_CODE,
	SCALE,
	SUSPECT_THRESHOLD,
	ageAppropriateItems,
	canRefer,
	previewScreening,
	scoreScreening,
} from "../src/lib/developmentalScales.ts";

/** 25–60 月分档的 5 条适龄条目（全部 0/1/2，含一条 0/2）。 */
const AGE_25_CODES = ["GM_JUMP", "GM_BALANCE", "FM_DRAW", "LA_SENTENCE", "LA_UNDERSTAND"];

function answersAt25(
	gmJump: number,
	gmBalance: number,
	fmDraw: number,
	laSentence: number,
	laUnderstand: number,
): Record<string, number> {
	return {
		GM_JUMP: gmJump,
		GM_BALANCE: gmBalance,
		FM_DRAW: fmDraw,
		LA_SENTENCE: laSentence,
		LA_UNDERSTAND: laUnderstand,
	};
}

test("量表包与后端 A1 同源：编码 / 版本 / 领域", () => {
	assert.equal(PLACEHOLDER_SCALE_CODE, "CHILD_DEV_PLACEHOLDER");
	assert.equal(SCALE.code, "CHILD_DEV_PLACEHOLDER");
	assert.equal(SCALE.version, "0.1");
	assert.equal(SCALE.minMonths, 0);
	assert.equal(SCALE.maxMonths, 60);
	assert.deepEqual(
		DOMAINS.map((domain) => [domain.code, domain.name]),
		[
			[DOMAIN_GROSS_MOTOR, "大运动"],
			[DOMAIN_FINE_MOTOR, "精细动作"],
			[DOMAIN_LANGUAGE, "语言"],
		],
	);
	assert.equal(SCALE.domains, DOMAINS, "SCALE.domains 直接复用 DOMAINS");
	assert.equal(NORMAL_THRESHOLD, 0.9);
	assert.equal(SUSPECT_THRESHOLD, 0.7);
});

test("量表包自检：条目编码唯一、领域合法、区间合法、选项合法", () => {
	assert.equal(SCALE.items.length, 14);
	const codes = SCALE.items.map((item) => item.code);
	assert.equal(new Set(codes).size, codes.length, "条目编码必须全局唯一");
	const domainCodes = new Set(DOMAINS.map((domain) => domain.code));
	SCALE.items.forEach((item) => {
		assert.ok(domainCodes.has(item.domain), `${item.code} 的领域 ${item.domain} 必须合法`);
		assert.ok(item.minMonths <= item.maxMonths, `${item.code} 区间下界不得大于上界`);
		assert.ok(item.minMonths >= SCALE.minMonths && item.maxMonths <= SCALE.maxMonths, `${item.code} 区间须在量表区间内`);
		assert.ok(item.options.length >= 2 && item.options.length <= 3, `${item.code} 选项数须为 2–3`);
		const values = item.options.map((option) => option.value);
		assert.equal(new Set(values).size, values.length, `${item.code} 选项值必须互不相同`);
	});
});

test("适龄条目随月龄变化，边界与后端 A1 一致", () => {
	const codesAt = (age: number) => ageAppropriateItems(age).map((item) => item.code);

	// 0–6 月
	assert.deepEqual(codesAt(0), ["GM_HEAD_CONTROL", "FM_GRASP", "LA_VOICE"]);
	assert.deepEqual(codesAt(6), ["GM_HEAD_CONTROL", "FM_GRASP", "LA_VOICE"]);
	// 7–12 月
	assert.deepEqual(codesAt(7), ["GM_SIT", "FM_TRANSFER", "LA_BABBLE"]);
	assert.deepEqual(codesAt(12), ["GM_SIT", "FM_TRANSFER", "LA_BABBLE"]);
	// 13–24 月
	assert.deepEqual(codesAt(13), ["GM_WALK", "FM_STACK", "LA_WORD"]);
	assert.deepEqual(codesAt(24), ["GM_WALK", "FM_STACK", "LA_WORD"]);
	// 25–60 月
	assert.deepEqual(codesAt(25), AGE_25_CODES);
	assert.deepEqual(codesAt(60), AGE_25_CODES);

	// 覆盖 0–60 月无空档
	for (let age = 0; age <= 60; age += 1) {
		assert.ok(ageAppropriateItems(age).length > 0, `${age} 月应有适龄条目`);
	}
});

test("计分：正常（达标率 1.0 与 0.90 精确边界）", () => {
	const perfect = scoreScreening(25, answersAt25(2, 2, 2, 2, 2));
	assert.equal(perfect.totalScore, 10);
	assert.equal(perfect.maxTotalScore, 10);
	assert.equal(perfect.classification, CLASSIFICATIONS.NORMAL);

	const exactBoundary = scoreScreening(25, answersAt25(2, 2, 2, 1, 2));
	assert.equal(exactBoundary.totalScore, 9);
	assert.equal(exactBoundary.maxTotalScore, 10);
	assert.equal(exactBoundary.totalScore / exactBoundary.maxTotalScore, 0.9);
	assert.equal(exactBoundary.classification, CLASSIFICATIONS.NORMAL, "0.90 恰好判正常");
});

test("计分：可疑（0.70 精确边界与区间内）", () => {
	const exactBoundary = scoreScreening(25, answersAt25(2, 2, 2, 1, 0));
	assert.equal(exactBoundary.totalScore, 7);
	assert.equal(exactBoundary.classification, CLASSIFICATIONS.SUSPECT, "0.70 恰好判可疑");

	const inside = scoreScreening(25, answersAt25(2, 2, 2, 2, 0));
	assert.equal(inside.totalScore, 8);
	assert.equal(inside.classification, CLASSIFICATIONS.SUSPECT);
});

test("计分：异常（低于 0.70）", () => {
	const result = scoreScreening(25, answersAt25(2, 2, 2, 0, 0));
	assert.equal(result.totalScore, 6);
	assert.equal(result.totalScore / result.maxTotalScore, 0.6);
	assert.equal(result.classification, CLASSIFICATIONS.ABNORMAL);
});

test("计分：领域分 / 领域满分 / 总分", () => {
	const result = scoreScreening(25, answersAt25(2, 2, 2, 1, 2));
	assert.deepEqual(result.domainScores, { GM: 4, FM: 2, LA: 3 });
	assert.deepEqual(result.domainMaxScores, { GM: 4, FM: 2, LA: 4 });
	assert.equal(result.totalScore, 9);
	assert.equal(result.maxTotalScore, 10);

	// 0–6 月：三领域各一条，满分各 2。
	const infant = scoreScreening(0, { GM_HEAD_CONTROL: 2, FM_GRASP: 1, LA_VOICE: 0 });
	assert.deepEqual(infant.domainScores, { GM: 2, FM: 1, LA: 0 });
	assert.deepEqual(infant.domainMaxScores, { GM: 2, FM: 2, LA: 2 });
	assert.equal(infant.totalScore, 3);
	assert.equal(infant.maxTotalScore, 6);
});

test("triggeredItems：取到条目最低选项值，升序；无触发为空", () => {
	const triggered = scoreScreening(0, { GM_HEAD_CONTROL: 0, FM_GRASP: 1, LA_VOICE: 0 });
	assert.deepEqual(triggered.triggeredItems, ["GM_HEAD_CONTROL", "LA_VOICE"], "升序且仅含最低值项");

	const none = scoreScreening(0, { GM_HEAD_CONTROL: 2, FM_GRASP: 2, LA_VOICE: 2 });
	assert.deepEqual(none.triggeredItems, []);

	// 二元条目 LA_UNDERSTAND 的最低值为 0。
	const binary = scoreScreening(25, answersAt25(2, 2, 2, 2, 0));
	assert.deepEqual(binary.triggeredItems, ["LA_UNDERSTAND"]);
});

test("校验：月龄越界抛错（含负数与超上限）", () => {
	assert.throws(() => scoreScreening(-1, {}), /age_months/);
	assert.throws(() => scoreScreening(61, {}), /age_months/);
});

test("校验：缺项 / 多余项 / 非法分值抛错", () => {
	assert.throws(
		() => scoreScreening(0, { GM_HEAD_CONTROL: 2, FM_GRASP: 1 }),
		/missing/,
	);
	assert.throws(
		() => scoreScreening(0, { GM_HEAD_CONTROL: 2, FM_GRASP: 1, LA_VOICE: 0, GM_SIT: 2 }),
		/unexpected/,
	);
	assert.throws(
		() => scoreScreening(0, { GM_HEAD_CONTROL: 3, FM_GRASP: 1, LA_VOICE: 0 }),
		/invalid/,
	);
	assert.throws(
		// 二元条目不接受 1。
		() => scoreScreening(25, answersAt25(2, 2, 2, 2, 1)),
		/invalid/,
	);
});

test("previewScreening：仅累计已作答条目，用于实时展示（非权威）", () => {
	const preview = previewScreening(25, { GM_JUMP: 2 });
	assert.deepEqual(preview.domainScores, { GM: 2, FM: 0, LA: 0 });
	assert.deepEqual(preview.domainMaxScores, { GM: 4, FM: 2, LA: 4 });
	assert.equal(preview.totalScore, 2);
	assert.equal(preview.maxTotalScore, 10);
	assert.equal(preview.answeredCount, 1);
	assert.equal(preview.itemCount, 5);

	const empty = previewScreening(0, {});
	assert.equal(empty.answeredCount, 0);
	assert.equal(empty.itemCount, 3);
	assert.equal(empty.maxTotalScore, 6);
});

test("canRefer：可疑 / 异常可转诊，正常 / 空不可", () => {
	assert.equal(canRefer(CLASSIFICATIONS.SUSPECT), true);
	assert.equal(canRefer(CLASSIFICATIONS.ABNORMAL), true);
	assert.equal(canRefer(CLASSIFICATIONS.NORMAL), false);
	assert.equal(canRefer(null), false);
	assert.equal(canRefer(undefined), false);
	assert.equal(canRefer(""), false);
});
