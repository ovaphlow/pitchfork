/**
 * 054 D4：药师工作台展示/表单辅助（`src/lib/pharmacyReview.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `nutrition.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 规则状态白名单与 D1 规则来源诊断码的中文口径；
 *   - 表单校验与后端 `PediatricDoseRuleService.validate` 同口径；
 *   - **安全边界（§12 R6）**：剂量结果必须带「辅助计算，不替代药师判断，不写医嘱」；
 *   - **不伪造 053 D3**：页面不得调用药物过敏交叉检查（只只读展示过敏史）；
 *   - 菜单接线：药师工作台在儿保域可见、受 `pharmacy:dispense` 显隐控制（F1/R9），
 *     菜单路径有对应 Astro 页面（静态构建下不会 404）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type {
	DrugAllergyCheck,
	DrugAllergyWarning,
	PediatricDoseCalculation,
	PediatricDoseRule,
} from "@pitchfork/shared/aceso";

import {
	ALLERGY_CHECK_NOTICE,
	ALLERGY_CHECK_SCOPE_NOTICE,
	ALLERGY_SEVERITIES,
	ALLERGY_SOURCES,
	DOSE_RULE_STATUSES,
	DOSE_SAFETY_NOTICE,
	DOSE_SOURCE_LABELS,
	allergyCheckLabel,
	allergyCheckSummary,
	allergyCheckVariant,
	allergySeverityRank,
	allergySeverityVariant,
	allergyWarningCount,
	allergyWarningLabel,
	buildDoseRulePayload,
	calculationInputLabel,
	calculationSummary,
	cappedNotice,
	doseRuleFormOf,
	doseRuleStatusVariant,
	doseSourceLabel,
	doseSourceVariant,
	emptyDoseRuleForm,
	formatAllergyList,
	formatAllergySource,
	formatDose,
	formatDoseNumber,
	hasSevereWarning,
	parsePositiveDecimal,
	parseWholeNumber,
	ruleBandLabel,
	ruleDefinitionLabel,
	ruleLimitsLabel,
	sortAllergyWarnings,
	validateDoseRuleForm,
} from "../src/lib/pharmacyReview.ts";

function ruleFixture(overrides: Partial<PediatricDoseRule> = {}): PediatricDoseRule {
	return {
		id: "01JTESTRULE00000000000000",
		material_id: "01JTESTMATERIAL0000000000",
		material_name: "阿莫西林颗粒",
		age_min_months: 6,
		age_max_months: 24,
		weight_min_kg: 5,
		weight_max_kg: 15,
		dose_per_kg: 15,
		fixed_dose: null,
		single_dose_max: 500,
		daily_dose_max: 1500,
		doses_per_day: 3,
		unit: "mg",
		status: "启用",
		note: null,
		created_by: "药师甲",
		created_at: "2026-10-06T10:00:00+08:00",
		updated_at: "2026-10-06T10:00:00+08:00",
		...overrides,
	};
}

function calculationFixture(overrides: Partial<PediatricDoseCalculation> = {}): PediatricDoseCalculation {
	return {
		material_id: "01JTESTMATERIAL0000000000",
		material_name: "阿莫西林颗粒",
		weight_kg: 12.5,
		age_months: 18,
		single_dose: 187.5,
		daily_dose: 562.5,
		unit: "mg",
		capped: false,
		rule_id: "01JTESTRULE00000000000000",
		source: "WEIGHT_AND_AGE_BAND",
		...overrides,
	};
}

function warningFixture(overrides: Partial<DrugAllergyWarning> = {}): DrugAllergyWarning {
	return {
		severity: "一般",
		reason: "既往过敏史命中药品名",
		source: "过敏史",
		matched: "青霉素",
		...overrides,
	};
}

function checkFixture(overrides: Partial<DrugAllergyCheck> = {}): DrugAllergyCheck {
	return {
		patient_id: "01JTESTCHILD0000000000000",
		material_id: "01JTESTMATERIAL0000000000",
		drug_name: "阿莫西林颗粒",
		drug_code: "AMOX-125",
		warnings: [],
		severe: false,
		...overrides,
	};
}

// ─── 状态与来源（镜像后端词汇）─────────────────────────────────────────────

test("规则状态白名单与徽章配色", () => {
	assert.deepEqual([...DOSE_RULE_STATUSES], ["启用", "停用"]);
	assert.equal(doseRuleStatusVariant("启用"), "success");
	assert.equal(doseRuleStatusVariant("停用"), "default");
	assert.equal(doseRuleStatusVariant(null), "default");
});

test("规则来源诊断码中文口径：五种码全覆盖，未知码原样返回", () => {
	assert.deepEqual(Object.keys(DOSE_SOURCE_LABELS).sort(), [
		"AGE_BAND",
		"GENERIC",
		"GENERIC_FALLBACK",
		"WEIGHT_AND_AGE_BAND",
		"WEIGHT_BAND",
	]);
	assert.equal(doseSourceLabel("WEIGHT_AND_AGE_BAND"), "命中体重+年龄区间规则");
	assert.equal(doseSourceLabel("WEIGHT_BAND"), "命中体重区间规则");
	assert.equal(doseSourceLabel("AGE_BAND"), "命中年龄区间规则");
	assert.equal(doseSourceLabel("GENERIC"), "通用规则（该药品无区间规则）");
	assert.equal(doseSourceLabel("GENERIC_FALLBACK"), "区间未命中，回落通用规则");
	assert.equal(doseSourceLabel("UNKNOWN_CODE"), "UNKNOWN_CODE");
	assert.equal(doseSourceLabel(null), "-");
});

test("来源徽章配色：区间缺失回落必须警示", () => {
	assert.equal(doseSourceVariant("GENERIC_FALLBACK"), "warning");
	assert.equal(doseSourceVariant("WEIGHT_AND_AGE_BAND"), "success");
	assert.equal(doseSourceVariant("GENERIC"), "info");
	assert.equal(doseSourceVariant(null), "default");
});

test("封顶提示：触发上限时必须人工复核", () => {
	assert.match(cappedNotice(true), /必须由药师人工复核/);
	assert.match(cappedNotice(false), /未触发上限封顶/);
});

// ─── 展示格式化 ────────────────────────────────────────────────────────────

test("剂量数值与单位展示：最多 3 位小数去尾零", () => {
	assert.equal(formatDoseNumber(187.5), "187.5");
	assert.equal(formatDoseNumber(150), "150");
	assert.equal(formatDoseNumber(0.125), "0.125");
	assert.equal(formatDoseNumber(null), "-");
	assert.equal(formatDoseNumber(Number.NaN), "-");
	assert.equal(formatDose(187.5, "mg"), "187.5 mg");
	assert.equal(formatDose(null, "mg"), "-");
});

test("剂量定义口径：按体重 / 固定 / 两者并存（按体重优先）", () => {
	assert.equal(ruleDefinitionLabel(ruleFixture()), "按体重 15 mg/kg");
	assert.equal(
		ruleDefinitionLabel(ruleFixture({ dose_per_kg: null, fixed_dose: 250 })),
		"固定剂量 250 mg",
	);
	assert.equal(
		ruleDefinitionLabel(ruleFixture({ dose_per_kg: 15, fixed_dose: 250 })),
		"按体重 15 mg/kg（优先）+ 固定剂量 250 mg",
	);
	assert.equal(
		ruleDefinitionLabel(ruleFixture({ dose_per_kg: null, fixed_dose: null })),
		"未定义剂量（服务端会拒绝）",
	);
});

test("适用区间口径：年龄/体重区间与通用规则", () => {
	assert.equal(ruleBandLabel(ruleFixture()), "年龄 6–24 月 · 体重 5–15 kg");
	assert.equal(ruleBandLabel(ruleFixture({ weight_min_kg: null, weight_max_kg: null })), "年龄 6–24 月");
	assert.equal(ruleBandLabel(ruleFixture({ age_min_months: null, age_max_months: null })), "体重 5–15 kg");
	assert.equal(
		ruleBandLabel(
			ruleFixture({
				age_min_months: null,
				age_max_months: null,
				weight_min_kg: null,
				weight_max_kg: null,
			}),
		),
		"无区间（通用规则）",
	);
	assert.equal(
		ruleBandLabel(ruleFixture({ age_min_months: 6, age_max_months: null })),
		"年龄 6–不限 月 · 体重 5–15 kg",
	);
	assert.equal(
		ruleBandLabel(
			ruleFixture({ age_min_months: 6, age_max_months: null, weight_min_kg: null, weight_max_kg: null }),
		),
		"年龄 6–不限 月",
	);
});

test("上限与频次口径：全填 / 部分 / 全缺", () => {
	assert.equal(ruleLimitsLabel(ruleFixture()), "单次上限 500 mg · 日上限 1500 mg · 每日 3 次");
	assert.equal(
		ruleLimitsLabel(ruleFixture({ daily_dose_max: null, doses_per_day: null })),
		"单次上限 500 mg",
	);
	assert.equal(
		ruleLimitsLabel(
			ruleFixture({ single_dose_max: null, daily_dose_max: null, doses_per_day: null }),
		),
		"未设上限/频次（按每日 1 次折算）",
	);
});

test("计算结果摘要与输入回显", () => {
	assert.equal(calculationSummary(calculationFixture()), "单次 187.5 mg · 每日 562.5 mg");
	assert.equal(calculationInputLabel(calculationFixture()), "体重 12.5 kg · 月龄 18");
	assert.equal(
		calculationInputLabel(calculationFixture({ weight_kg: null, age_months: null })),
		"体重未提供 · 月龄未提供",
	);
});

test("过敏史只读展示", () => {
	assert.equal(formatAllergyList(["牛奶", "鸡蛋"]), "牛奶、鸡蛋");
	assert.equal(formatAllergyList([]), "无过敏史记录");
	assert.equal(formatAllergyList(null), "无过敏史记录");
	assert.equal(formatAllergyList([" 牛奶 ", ""]), "牛奶");
});

// ─── 数值解析与表单校验 ────────────────────────────────────────────────────

test("正数与非负整数解析", () => {
	assert.equal(parsePositiveDecimal("15"), 15);
	assert.equal(parsePositiveDecimal(" 0.5 "), 0.5);
	assert.equal(parsePositiveDecimal(""), null);
	assert.equal(parsePositiveDecimal("0"), null);
	assert.equal(parsePositiveDecimal("-3"), null);
	assert.equal(parsePositiveDecimal("abc"), null);

	assert.equal(parseWholeNumber("6", 0), 6);
	assert.equal(parseWholeNumber("0", 0), 0);
	assert.equal(parseWholeNumber("3", 1), 3);
	assert.equal(parseWholeNumber("0", 1), null);
	assert.equal(parseWholeNumber("2.5", 0), null);
	assert.equal(parseWholeNumber("", 0), null);
});

test("空白表单缺省：状态「启用」（与后端一致）", () => {
	const form = emptyDoseRuleForm();
	assert.equal(form.status, "启用");
	assert.equal(form.materialId, "");
	assert.equal(form.unit, "");
	assert.equal(form.dosePerKg, "");
	assert.equal(form.note, "");
});

test("由规则生成编辑表单：全量字段回填，null 显示为空", () => {
	const form = doseRuleFormOf(ruleFixture({ fixed_dose: null, note: "仅住院使用" }));
	assert.equal(form.materialId, "01JTESTMATERIAL0000000000");
	assert.equal(form.unit, "mg");
	assert.equal(form.dosePerKg, "15");
	assert.equal(form.fixedDose, "");
	assert.equal(form.ageMinMonths, "6");
	assert.equal(form.ageMaxMonths, "24");
	assert.equal(form.weightMinKg, "5");
	assert.equal(form.weightMaxKg, "15");
	assert.equal(form.singleDoseMax, "500");
	assert.equal(form.dailyDoseMax, "1500");
	assert.equal(form.dosesPerDay, "3");
	assert.equal(form.status, "启用");
	assert.equal(form.note, "仅住院使用");
});

test("表单校验：必填、剂量二选一、正数、区间顺序、频次、状态", () => {
	const valid = { ...emptyDoseRuleForm(), materialId: "m1", unit: "mg", dosePerKg: "15" };
	assert.equal(validateDoseRuleForm(valid), null);
	assert.equal(validateDoseRuleForm({ ...valid, dosePerKg: "", fixedDose: "250" }), null);

	assert.match(validateDoseRuleForm({ ...valid, materialId: "" }) ?? "", /请选择药品/);
	assert.match(validateDoseRuleForm({ ...valid, unit: "  " }) ?? "", /单位必填/);
	assert.match(validateDoseRuleForm({ ...valid, dosePerKg: "" }) ?? "", /至少填写一项/);
	assert.match(validateDoseRuleForm({ ...valid, dosePerKg: "0" }) ?? "", /按体重剂量必须是正数/);
	assert.match(validateDoseRuleForm({ ...valid, fixedDose: "-1" }) ?? "", /固定剂量必须是正数/);
	assert.match(validateDoseRuleForm({ ...valid, singleDoseMax: "abc" }) ?? "", /单次上限必须是正数/);
	assert.match(validateDoseRuleForm({ ...valid, dailyDoseMax: "0" }) ?? "", /日上限必须是正数/);
	assert.match(validateDoseRuleForm({ ...valid, weightMinKg: "-2" }) ?? "", /体重下限必须是正数/);
	assert.match(validateDoseRuleForm({ ...valid, ageMinMonths: "1.5" }) ?? "", /年龄下限必须是非负整数/);
	assert.match(
		validateDoseRuleForm({ ...valid, ageMinMonths: "24", ageMaxMonths: "6" }) ?? "",
		/年龄下限不得大于年龄上限/,
	);
	assert.match(
		validateDoseRuleForm({ ...valid, weightMinKg: "15", weightMaxKg: "5" }) ?? "",
		/体重下限不得大于体重上限/,
	);
	assert.match(validateDoseRuleForm({ ...valid, dosesPerDay: "0" }) ?? "", /每日次数必须是不小于 1 的整数/);
	assert.match(validateDoseRuleForm({ ...valid, status: "未知" }) ?? "", /状态必须是/);
});

test("表单 → 请求体：空输入落 null（显式清空），数值转 number", () => {
	const payload = buildDoseRulePayload({
		...emptyDoseRuleForm(),
		materialId: " m1 ",
		unit: " mg ",
		dosePerKg: "15",
		fixedDose: "",
		ageMinMonths: "6",
		ageMaxMonths: "",
		weightMinKg: "5",
		weightMaxKg: "",
		singleDoseMax: "",
		dailyDoseMax: "1500",
		dosesPerDay: "3",
		note: " 仅住院使用 ",
	});
	assert.deepEqual(payload, {
		material_id: "m1",
		unit: "mg",
		dose_per_kg: 15,
		fixed_dose: null,
		age_min_months: 6,
		age_max_months: null,
		weight_min_kg: 5,
		weight_max_kg: null,
		single_dose_max: null,
		daily_dose_max: 1500,
		doses_per_day: 3,
		status: "启用",
		note: "仅住院使用",
	});
	assert.equal(buildDoseRulePayload(emptyDoseRuleForm()).note, null);
});

// ─── 交叉检查展示（053 D3）─────────────────────────────────────────────────

test("严重度白名单与分级配色：严重=danger（error）、一般=warning", () => {
	assert.deepEqual([...ALLERGY_SEVERITIES], ["严重", "一般"]);
	assert.deepEqual([...ALLERGY_SOURCES], ["过敏史", "交叉反应", "年龄禁忌"]);
	assert.equal(allergySeverityVariant("严重"), "danger");
	assert.equal(allergySeverityVariant("一般"), "warning");
	assert.equal(allergySeverityVariant("未知级别"), "default");
	assert.equal(allergySeverityRank("严重"), 0);
	assert.equal(allergySeverityRank("一般"), 1);
	assert.equal(allergySeverityRank("未知级别"), 99);
});

test("命中来源：已知来源原样透出，未知 code 不隐藏、不猜测", () => {
	assert.equal(formatAllergySource("过敏史"), "过敏史");
	assert.equal(formatAllergySource("交叉反应"), "交叉反应");
	assert.equal(formatAllergySource("年龄禁忌"), "年龄禁忌");
	assert.equal(formatAllergySource("NEW_UNKNOWN_SOURCE"), "NEW_UNKNOWN_SOURCE");
	assert.equal(formatAllergySource(null), "未知来源");
});

test("命中项排序：严重在前，同严重度保持服务端顺序（稳定且不改入参）", () => {
	const warnings = [
		warningFixture({ severity: "一般", source: "过敏史", matched: "青霉素" }),
		warningFixture({ severity: "严重", source: "交叉反应", matched: "头孢" }),
		warningFixture({ severity: "严重", source: "年龄禁忌", matched: "12 月龄以下禁用" }),
		warningFixture({ severity: "未知级别", source: "自定义", matched: "x" }),
	];
	const sorted = sortAllergyWarnings(warnings);
	assert.deepEqual(
		sorted.map((warning) => `${warning.severity}/${warning.source}`),
		["严重/交叉反应", "严重/年龄禁忌", "一般/过敏史", "未知级别/自定义"],
	);
	assert.equal(warnings[0]?.severity, "一般", "排序不得修改入参");
	// 同严重度不得被重排（顺序可复现，不依赖 locale）
	assert.deepEqual(
		sortAllergyWarnings([warningFixture({ matched: "A" }), warningFixture({ matched: "B" })]).map(
			(warning) => warning.matched,
		),
		["A", "B"],
	);
});

test("命中计数与结论摘要：空 warnings / 一般 / 严重 / 未分级", () => {
	assert.deepEqual(allergyWarningCount([]), { severe: 0, general: 0, other: 0, total: 0 });

	const generalOnly = checkFixture({
		warnings: [warningFixture(), warningFixture({ matched: "阿莫西林" })],
	});
	assert.deepEqual(allergyWarningCount(generalOnly.warnings), { severe: 0, general: 2, other: 0, total: 2 });
	assert.equal(allergyCheckVariant(generalOnly), "success");
	assert.equal(allergyCheckLabel(generalOnly), "存在一般提醒");
	assert.match(allergyCheckSummary(generalOnly), /一般 2 项/);
	assert.match(allergyCheckSummary(generalOnly), /须药师复核/);

	const severe = checkFixture({
		warnings: [
			warningFixture({ severity: "严重", source: "年龄禁忌", matched: "12 月龄以下禁用" }),
			warningFixture(),
			warningFixture({ severity: "未知级别", source: "自定义", matched: "x" }),
		],
		severe: true,
	});
	assert.deepEqual(allergyWarningCount(severe.warnings), { severe: 1, general: 1, other: 1, total: 3 });
	assert.equal(allergyCheckVariant(severe), "danger");
	assert.equal(allergyCheckLabel(severe), "存在严重提醒（1 项）");
	assert.match(allergyCheckSummary(severe), /严重 1 项 \/ 一般 1 项 \/ 未分级 1 项/);
	assert.match(allergyCheckSummary(severe), /须药师复核后再决定是否发药/);
	assert.equal(hasSevereWarning(severe), true);
	assert.equal(hasSevereWarning(generalOnly), false);
});

test("空 warnings：明确「未命中不代表绝对安全」，不输出 0 剂量式误导", () => {
	const clear = checkFixture({ warnings: [], severe: false });
	assert.equal(allergyCheckLabel(clear), "未命中");
	assert.equal(allergyCheckVariant(clear), "success");
	assert.match(allergyCheckSummary(clear), /未命中过敏史、交叉反应或年龄禁忌/);
	assert.match(allergyCheckSummary(clear), /仍须药师复核/);
});

test("单条命中项文案：来源 · 命中项（严重度），缺字段有兜底", () => {
	assert.equal(allergyWarningLabel(warningFixture()), "过敏史 · 青霉素（一般）");
	assert.equal(
		allergyWarningLabel(warningFixture({ severity: "严重", source: "年龄禁忌", matched: "6 月龄以下" })),
		"年龄禁忌 · 6 月龄以下（严重）",
	);
	assert.equal(
		allergyWarningLabel({ severity: "", source: "", matched: "" } as DrugAllergyWarning),
		"未知来源 · 未标注命中项（未分级）",
	);
});

// ─── 安全边界与交叉检查接入 ────────────────────────────────────────────────

test("安全边界文案必须含「辅助计算 / 不替代药师判断 / 不写医嘱」（§12 R6）", () => {
	assert.match(DOSE_SAFETY_NOTICE, /辅助计算/);
	assert.match(DOSE_SAFETY_NOTICE, /不替代药师判断/);
	assert.match(DOSE_SAFETY_NOTICE, /不写医嘱/);
});

test("交叉检查文案：辅助提示、须药师复核，并写明本页确实调用了该接口", () => {
	assert.match(ALLERGY_CHECK_NOTICE, /辅助提示/);
	assert.match(ALLERGY_CHECK_NOTICE, /不替代药师判断/);
	assert.match(ALLERGY_CHECK_NOTICE, /须经药师复核/);
	assert.match(ALLERGY_CHECK_SCOPE_NOTICE, /drug-allergy-check/);
	assert.match(ALLERGY_CHECK_SCOPE_NOTICE, /053 D3/);
	assert.match(ALLERGY_CHECK_SCOPE_NOTICE, /未命中不代表绝对安全/);
});

test("页面必须调用药物过敏交叉检查（053 D3），并展示分级与安全边界", () => {
	const page = readFileSync(
		new URL("../src/components/PharmacistWorkbenchPage.tsx", import.meta.url),
		"utf8",
	);
	assert.match(page, /checkDrugAllergy\(/, "D4 增补：必须调用 checkDrugAllergy");
	assert.match(page, /ALLERGY_CHECK_NOTICE/, "页面必须展示交叉检查边界声明");
	assert.match(page, /ALLERGY_CHECK_SCOPE_NOTICE/, "页面必须展示接入范围说明");
	assert.match(page, /allergySeverityVariant\(/, "页面必须按严重度分级配色");
	assert.match(page, /allergyCheckSummary\(/, "页面必须展示交叉检查结论");
	assert.match(page, /DOSE_SAFETY_NOTICE/, "页面必须展示剂量安全边界声明");
	assert.match(page, /cappedNotice\(/, "页面必须展示封顶提示");
	assert.match(page, /doseSourceLabel\(/, "页面必须展示规则来源");
	assert.doesNotMatch(page, /ALLERGY_READONLY_NOTICE/, "旧的「不做交叉检查」文案必须已移除");
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("药师工作台菜单项：儿保域可见 + pharmacy:dispense 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source
		.split("\n")
		.find((text) => text.includes('"/dashboard/pharmacist-workbench"'));
	assert.ok(line, "Sidebar 必须包含药师工作台菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/, "054 D4：工作台必须在儿保域可见（R9）");
	assert.match(
		line,
		/requiredPermissions:\s*\["pharmacy:dispense"\]/,
		"054 F1：写入口必须按 pharmacy:dispense 显隐（前端隐藏不替代 403）",
	);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(
		new URL("../src/pages/dashboard/pharmacist-workbench.astro", import.meta.url),
		"utf8",
	);
	assert.match(page, /PharmacistWorkbenchPage/, "Astro 页面必须挂载药师工作台组件");
});

test("共享客户端提供 D 链端点且路径与后端一致", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	for (const path of [
		"/pharmacy/v1/pediatric-dose-rules",
		"/pharmacy/v1/pediatric-dose-calculations",
	]) {
		assert.ok(client.includes(path), `aceso.ts 必须包含 D 链端点 ${path}`);
	}
	assert.match(client, /export function getPediatricDoseCalculation/, "必须有计算辅助客户端函数");
	assert.match(client, /export function updatePediatricDoseRule/, "必须有规则修改客户端函数");
});
