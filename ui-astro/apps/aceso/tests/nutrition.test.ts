/**
 * 054 A6：营养师工作台展示/表单辅助（`src/lib/nutrition.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `guidance.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 枚举白名单与状态机**逐字镜像后端**（`DiningConstants.MEAL_TIMES`、
 *     `SpecialDietRequestService.TRANSITIONS`）——前端只渲染合法流转按钮，
 *     非法流转仍由服务端 400 兜底；
 *   - 摄入登记表单校验与后端 `normalizeItems`/`parseRatio` 同口径；
 *   - 参考值展示必须带 `reference_version` 口径标注（054 §12 R5）；
 *   - 菜单接线：营养师工作台在儿保域可见、受 `dining:manage` 显隐控制（F1/R9），
 *     且菜单路径有对应 Astro 页面（静态构建下不会 404）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type { NutritionAnalysis, NutritionWeeklyReport, SpecialDietRequest } from "@pitchfork/shared/aceso";

import {
	MEAL_TIMES,
	SPECIAL_DIET_REQUEST_TYPES,
	SPECIAL_DIET_STATUSES,
	addFoodItem,
	allergenOverwriteNotice,
	allergenProfileNotice,
	allergenSavedHint,
	allowedSpecialDietTargets,
	canTransitionSpecialDiet,
	compositionSummary,
	dailyEnergyPercent,
	defaultMealTime,
	emptyFoodItem,
	formatAgeBand,
	formatAllergens,
	formatNutrientValue,
	formatNutrientWithUnit,
	formatRatio,
	isSpecialDietTerminal,
	maxDailyEnergy,
	normalizeFoodItems,
	nutrientRatio,
	nutrientRows,
	nutrientVerdict,
	parseAllergenText,
	parseRatioInput,
	referenceVersionLabel,
	removeFoodItem,
	specialDietHeadline,
	specialDietStatusVariant,
	totalAmountG,
	updateFoodItem,
	validateIntakeForm,
	weekRangeLabel,
	weeklyAverageRows,
	weeklyHeadline,
} from "../src/lib/nutrition.ts";

function analysisFixture(overrides: Partial<NutritionAnalysis> = {}): NutritionAnalysis {
	return {
		patient_id: "01JTESTCHILD0000000000000",
		patient_name: "测试儿童",
		intake_date: "2026-10-06",
		meal_time: "全部",
		age_months: 36,
		band: { code: "4-6y", label: "4–6 岁" },
		energy_kcal: 1100,
		protein_g: 25,
		fat_g: 43.56,
		carb_g: 151.25,
		sodium_mg: 700,
		reference: { energy_kcal: 1400, protein_g: 30, fat_g: 43.56, carb_g: 192.5, sodium_mg: 900 },
		conclusion: "能量与蛋白质低于参考",
		gaps: ["能量", "蛋白质"],
		items: [
			{ food_name: "米饭", amount_g: 200, energy_kcal: 232, matched: true },
			{ food_name: "自制辅食", amount_g: 100, energy_kcal: 0, matched: false },
		],
		unmatched: ["自制辅食"],
		reference_version: "CN-DRIS-2026.10",
		...overrides,
	};
}

function reportFixture(overrides: Partial<NutritionWeeklyReport> = {}): NutritionWeeklyReport {
	return {
		id: "01JTESTREPORT000000000000",
		patient_id: "01JTESTCHILD0000000000000",
		patient_name: "测试儿童",
		week_start: "2026-10-05",
		week_end: "2026-10-11",
		intake_days: 3,
		energy_avg_kcal: 1200,
		protein_avg_g: 26.5,
		fat_avg_g: 40,
		carb_avg_g: 160,
		sodium_avg_mg: 800,
		conclusion: "本周能量接近参考",
		gaps: ["钠"],
		daily: [
			{ intake_date: "2026-10-05", energy_kcal: 1200, protein_g: 26, fat_g: 40, carb_g: 160, sodium_mg: 800, conclusion: "达标" },
			{ intake_date: "2026-10-06", energy_kcal: 600, protein_g: 20, fat_g: 30, carb_g: 120, sodium_mg: 600, conclusion: "偏低" },
		],
		reference_version: "CN-DRIS-2026.10",
		generated_by: "营养师甲",
		metadata: null,
		created_at: "2026-10-11T10:00:00+08:00",
		updated_at: "2026-10-11T10:00:00+08:00",
		...overrides,
	};
}

function requestFixture(overrides: Partial<SpecialDietRequest> = {}): SpecialDietRequest {
	return {
		id: "01JTESTREQUEST00000000000",
		patient_id: "01JTESTCHILD0000000000000",
		patient_name: "测试儿童",
		request_type: "过敏回避",
		meal_type: "午餐",
		allergens: ["牛奶"],
		reason: null,
		status: "待审批",
		requested_by: "营养师甲",
		reviewed_by: null,
		reviewed_at: null,
		effective_from: null,
		effective_to: null,
		metadata: null,
		created_at: "2026-10-06T09:00:00+08:00",
		updated_at: "2026-10-06T09:00:00+08:00",
		...overrides,
	};
}

// ─── 白名单与状态机（镜像后端）─────────────────────────────────────────────

test("餐次与申请类型白名单与后端逐字一致", () => {
	assert.deepEqual([...MEAL_TIMES], ["早餐", "午餐", "晚餐", "加餐"]);
	assert.deepEqual([...SPECIAL_DIET_REQUEST_TYPES], ["过敏回避", "治疗膳食", "其他"]);
	assert.deepEqual([...SPECIAL_DIET_STATUSES], ["待审批", "已批准", "已驳回", "已终止"]);
});

test("状态机合法流转：待审批 → 已批准/已驳回/已终止", () => {
	assert.deepEqual(allowedSpecialDietTargets("待审批"), ["已批准", "已驳回", "已终止"]);
	assert.equal(canTransitionSpecialDiet("待审批", "已批准"), true);
	assert.equal(canTransitionSpecialDiet("待审批", "已驳回"), true);
	assert.equal(canTransitionSpecialDiet("待审批", "已终止"), true);
});

test("状态机非法流转：不允许回到待审批、不允许已批准 ⇄ 已驳回、终态不可再流转", () => {
	assert.equal(canTransitionSpecialDiet("已批准", "待审批"), false);
	assert.equal(canTransitionSpecialDiet("已批准", "已驳回"), false);
	assert.equal(canTransitionSpecialDiet("已驳回", "已批准"), false);
	assert.deepEqual(allowedSpecialDietTargets("已终止"), []);
	assert.equal(canTransitionSpecialDiet("已终止", "已批准"), false);
	assert.equal(canTransitionSpecialDiet("已终止", "已终止"), false);
	// 未知状态与空值一律不可流转（不猜测）
	assert.deepEqual(allowedSpecialDietTargets("未知状态"), []);
	assert.deepEqual(allowedSpecialDietTargets(null), []);
	assert.equal(canTransitionSpecialDiet(null, "已批准"), false);
	assert.equal(canTransitionSpecialDiet("待审批", ""), false);
});

test("终态判定与状态徽章配色", () => {
	assert.equal(isSpecialDietTerminal("已终止"), true);
	assert.equal(isSpecialDietTerminal("待审批"), false);
	assert.equal(specialDietStatusVariant("待审批"), "warning");
	assert.equal(specialDietStatusVariant("已批准"), "success");
	assert.equal(specialDietStatusVariant("已驳回"), "danger");
	assert.equal(specialDietStatusVariant("已终止"), "default");
	assert.equal(specialDietStatusVariant(null), "info");
});

test("申请单摘要：类型 · 餐次 · 状态", () => {
	assert.equal(specialDietHeadline(requestFixture()), "过敏回避 · 午餐 · 待审批");
});

// ─── 摄入登记表单 ──────────────────────────────────────────────────────────

test("食物项编辑是纯函数：增删改都不修改入参", () => {
	const base = [emptyFoodItem()];
	const snapshot = JSON.parse(JSON.stringify(base)) as typeof base;

	const added = addFoodItem(base, { food_name: "米饭", amount_g: 200 });
	assert.equal(base.length, 1, "addFoodItem 不得修改入参");
	assert.equal(added.length, 2);
	assert.deepEqual(base, snapshot);

	const updated = updateFoodItem(added, 1, { amount_g: 150 });
	assert.equal(added[1]?.amount_g, 200, "updateFoodItem 不得修改入参");
	assert.equal(updated[1]?.amount_g, 150);
	assert.equal(updated[1]?.food_name, "米饭", "未 patch 的字段必须保留");

	const removed = removeFoodItem(updated, 0);
	assert.equal(updated.length, 2, "removeFoodItem 不得修改入参");
	assert.deepEqual(
		removed.map((item) => item.food_name),
		["米饭"],
	);

	// 越界不抛、返回原样副本
	assert.equal(updateFoodItem(base, 5, { amount_g: 1 })[0]?.amount_g, 0);
	assert.equal(removeFoodItem(base, 5).length, 1);
});

test("份量合计与规范化：去空白、丢弃空食物名、备注去空白后省略", () => {
	assert.equal(totalAmountG([{ food_name: "米饭", amount_g: 200 }, { food_name: "鸡蛋", amount_g: 50.5 }]), 250.5);
	assert.equal(totalAmountG([]), 0);

	assert.deepEqual(
		normalizeFoodItems([
			{ food_name: "  米饭 ", amount_g: 200, note: "   " },
			{ food_name: "   ", amount_g: 100 },
			{ food_name: "鸡蛋", amount_g: 50, note: " 全熟 " },
		]),
		[
			{ food_name: "米饭", amount_g: 200 },
			{ food_name: "鸡蛋", amount_g: 50, note: "全熟" },
		],
	);
});

test("提交前校验与后端同口径：至少一项、份量为正、比例 (0,1]", () => {
	assert.equal(validateIntakeForm([{ food_name: "米饭", amount_g: 200 }], 1), null);
	assert.equal(validateIntakeForm([{ food_name: "米饭", amount_g: 200 }], 0.5), null);

	assert.match(validateIntakeForm([], 1) ?? "", /至少填写一项食物/);
	assert.match(validateIntakeForm([emptyFoodItem()], 1) ?? "", /至少填写一项食物/);
	assert.match(validateIntakeForm([{ food_name: "米饭", amount_g: 0 }], 1) ?? "", /份量必须是正数/);
	assert.match(validateIntakeForm([{ food_name: "米饭", amount_g: -1 }], 1) ?? "", /份量必须是正数/);
	assert.match(validateIntakeForm([{ food_name: "米饭", amount_g: 200 }], null) ?? "", /摄入比例必须是/);
	assert.match(validateIntakeForm([{ food_name: "米饭", amount_g: 200 }], 0) ?? "", /大于 0 且不超过 1/);
	assert.match(validateIntakeForm([{ food_name: "米饭", amount_g: 200 }], 1.5) ?? "", /大于 0 且不超过 1/);
});

test("摄入比例解析与展示", () => {
	assert.equal(parseRatioInput("1"), 1);
	assert.equal(parseRatioInput(" 0.6 "), 0.6);
	assert.equal(parseRatioInput(""), null);
	assert.equal(parseRatioInput("abc"), null);

	assert.equal(formatRatio(1), "100%");
	assert.equal(formatRatio(0.6), "60%");
	assert.equal(formatRatio(0.333), "33%");
	assert.equal(formatRatio(null), "-");
	assert.equal(formatRatio(Number.NaN), "-");
});

test("默认餐次按小时预选，非法小时回退早餐", () => {
	assert.equal(defaultMealTime(0), "早餐");
	assert.equal(defaultMealTime(9), "早餐");
	assert.equal(defaultMealTime(10), "午餐");
	assert.equal(defaultMealTime(13), "午餐");
	assert.equal(defaultMealTime(14), "晚餐");
	assert.equal(defaultMealTime(19), "晚餐");
	assert.equal(defaultMealTime(20), "加餐");
	assert.equal(defaultMealTime(23), "加餐");
	assert.equal(defaultMealTime(24), "早餐");
	assert.equal(defaultMealTime(Number.NaN), "早餐");
});

// ─── 过敏原文本 ────────────────────────────────────────────────────────────

test("过敏原文本解析：半/全角逗号、顿号、分号、换行分隔并去重", () => {
	assert.deepEqual(parseAllergenText("牛奶, 鸡蛋、花生；牛奶\n虾"), ["牛奶", "鸡蛋", "花生", "虾"]);
	assert.deepEqual(parseAllergenText("   "), []);
	assert.deepEqual(parseAllergenText("牛奶，牛奶"), ["牛奶"]);
});

test("过敏原展示：顿号分隔，空数组显示「无」", () => {
	assert.equal(formatAllergens(["牛奶", "鸡蛋"]), "牛奶、鸡蛋");
	assert.equal(formatAllergens([]), "无");
	assert.equal(formatAllergens(null), "无");
	assert.equal(formatAllergens([" 牛奶 ", ""]), "牛奶");
});

// ─── 过敏原覆盖式写入（A4b）───────────────────────────────────────────────

test("覆盖式写入提示：整体替换而非追加，空列表明确「将清空」", () => {
	assert.match(allergenOverwriteNotice(["牛奶", "鸡蛋"]), /整体替换为 2 项/);
	assert.match(allergenOverwriteNotice(["牛奶", "鸡蛋"]), /不是追加/);
	assert.match(allergenOverwriteNotice([]), /将清空/);
	assert.match(allergenOverwriteNotice([]), /整体替换/);
});

test("保存成功提示回显服务端返回的过敏原（覆盖式口径）", () => {
	assert.equal(allergenSavedHint(["鸡蛋"]), "已覆盖写入过敏原：鸡蛋（整体替换，非追加）");
	assert.equal(allergenSavedHint([]), "已覆盖写入过敏原：无（整体替换，非追加）");
});

test("过敏原区块状态说明按「是否儿童 / 有无档案」分流", () => {
	assert.match(allergenProfileNotice(0, true), /自动建立最小启用档案（无入住）/);
	assert.match(allergenProfileNotice(0, false), /非儿童必须先建档/);
	assert.match(allergenProfileNotice(0, false), /400/);
	assert.match(allergenProfileNotice(2, true), /已关联 2 份饮食档案/);
	assert.match(allergenProfileNotice(2, false), /已关联 2 份饮食档案/);
});

test("页面按 patient 维度写过敏原（A4b），不再依赖档案已存在", () => {
	const page = readFileSync(
		new URL("../src/components/DietitianWorkbenchPage.tsx", import.meta.url),
		"utf8",
	);
	assert.match(
		page,
		/upsertDietProfileAllergensByPatient\(/,
		"A4b：过敏原必须走 by-patient 覆盖式写入",
	);
	assert.doesNotMatch(
		page,
		/disabled=\{!selectedProfile\}/,
		"保存按钮不得再以「已有档案」为前置（儿童无入住必须可保存）",
	);
});

test("共享客户端提供 by-patient 过敏原端点且请求体字段名为 allergies", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	const start = client.indexOf("export function upsertDietProfileAllergensByPatient");
	assert.ok(start > 0, "aceso.ts 必须导出 upsertDietProfileAllergensByPatient");
	const body = client.slice(start, start + 900);
	assert.match(body, /\/dining\/v1\/diet-profiles\/by-patient\/\$\{encodeURIComponent\(patientId\)\}\/allergens/);
	assert.match(body, /\{ allergies \}/, "请求体字段名必须是 allergies（与列同口径）");
});

// ─── 营养分析展示 ──────────────────────────────────────────────────────────

test("五项营养素行固定顺序，实际/参考与描述性对比", () => {
	const rows = nutrientRows(analysisFixture());
	assert.deepEqual(
		rows.map((row) => row.key),
		["energy_kcal", "protein_g", "fat_g", "carb_g", "sodium_mg"],
	);
	assert.deepEqual(
		rows.map((row) => row.label),
		["热量", "蛋白质", "脂肪", "碳水", "钠"],
	);

	const energy = rows[0]!;
	assert.equal(energy.value, 1100);
	assert.equal(energy.reference, 1400);
	assert.equal(energy.ratio, 0.79);
	assert.equal(energy.verdict, "低于参考");

	const fat = rows[2]!;
	assert.equal(fat.ratio, 1);
	assert.equal(fat.verdict, "达到参考");
});

test("对比与比值：参考值为 0/非法时不给结论（不除零、不臆断）", () => {
	assert.equal(nutrientVerdict(10, 10), "达到参考");
	assert.equal(nutrientVerdict(9.99, 10), "低于参考");
	assert.equal(nutrientVerdict(10, 0), "无参考");
	assert.equal(nutrientVerdict(10, Number.NaN), "无参考");

	assert.equal(nutrientRatio(700, 1400), 0.5);
	assert.equal(nutrientRatio(10, 0), null);
	assert.equal(nutrientRatio(Number.NaN, 10), null);
});

test("数值展示：最多两位小数、去掉尾零、非法值显示 -", () => {
	assert.equal(formatNutrientValue(1100), "1100");
	assert.equal(formatNutrientValue(43.56), "43.56");
	assert.equal(formatNutrientValue(0.5), "0.5");
	assert.equal(formatNutrientValue(null), "-");
	assert.equal(formatNutrientValue(Number.NaN), "-");
	assert.equal(formatNutrientWithUnit(116.5, "kcal"), "116.5 kcal");
	assert.equal(formatNutrientWithUnit(null, "kcal"), "-");
});

test("月龄段与参考值口径标注（缺版本时明确「未标注」）", () => {
	assert.equal(formatAgeBand(analysisFixture()), "36 月 · 4–6 岁");
	assert.equal(formatAgeBand(analysisFixture({ age_months: 3, band: { code: "0-6m", label: "" } })), "3 月");
	assert.equal(referenceVersionLabel("CN-DRIS-2026.10"), "参考值口径 CN-DRIS-2026.10");
	assert.equal(referenceVersionLabel(null), "参考值口径未标注");
	assert.equal(referenceVersionLabel("  "), "参考值口径未标注");
});

test("成分匹配概览：命中数 + 未匹配食物名（计 0 不是错误）", () => {
	assert.deepEqual(compositionSummary(analysisFixture()), { matched: 1, unmatched: ["自制辅食"] });
	assert.deepEqual(
		compositionSummary(analysisFixture({ items: [], unmatched: [] })),
		{ matched: 0, unmatched: [] },
	);
});

// ─── 营养周报展示 ──────────────────────────────────────────────────────────

test("周报周界、摘要与均值行（均值为按有记录天数平均）", () => {
	const report = reportFixture();
	assert.equal(weekRangeLabel(report), "2026-10-05 ~ 2026-10-11");
	assert.equal(weeklyHeadline(report), "3 天有记录 · 日均 1200 kcal");
	assert.equal(
		weeklyHeadline(reportFixture({ intake_days: 0, energy_avg_kcal: 0 })),
		"当周无摄入记录（快照仍生成）",
	);

	const rows = weeklyAverageRows(report);
	assert.deepEqual(
		rows.map((row) => row.key),
		["energy_kcal", "protein_g", "fat_g", "carb_g", "sodium_mg"],
	);
	assert.equal(rows[0]?.value, 1200);
	assert.equal(rows[0]?.unit, "kcal");
	assert.equal(rows[4]?.value, 800);
});

test("逐日热量柱宽：最大值为 0 时不除零", () => {
	const report = reportFixture();
	assert.equal(maxDailyEnergy(report), 1200);
	assert.equal(maxDailyEnergy(reportFixture({ daily: [] })), 0);
	assert.equal(dailyEnergyPercent(1200, 1200), 100);
	assert.equal(dailyEnergyPercent(600, 1200), 50);
	assert.equal(dailyEnergyPercent(0, 0), 0);
	assert.equal(dailyEnergyPercent(100, 0), 0);
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("营养师工作台菜单项：儿保域可见 + dining:manage 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source
		.split("\n")
		.find((text) => text.includes('"/dashboard/dietitian-workbench"'));
	assert.ok(line, "Sidebar 必须包含营养师工作台菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/, "054 A6：工作台必须在儿保域可见（R9）");
	assert.match(
		line,
		/requiredPermissions:\s*\["dining:manage"\]/,
		"054 F1：写入口必须按 dining:manage 显隐（前端隐藏不替代 403）",
	);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(
		new URL("../src/pages/dashboard/dietitian-workbench.astro", import.meta.url),
		"utf8",
	);
	assert.match(page, /DietitianWorkbenchPage/, "Astro 页面必须挂载工作台组件");
});
