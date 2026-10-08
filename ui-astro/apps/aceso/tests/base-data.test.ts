/**
 * 054 F2：基础数据维护展示/表单辅助（`src/lib/baseData.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `childcare.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 写入白名单与状态白名单**与后端 `BaseDataService` 逐字一致**（源码漂移守卫）；
 *   - **读库优先、常量回落**口径文案必须常驻（库空时消费方与改造前一致）；
 *   - 表单校验与后端 `parseVaccinationCreate` / `requiredDefinition` 同口径；
 *   - 菜单接线：基础数据维护受 `base_data:manage` 显隐控制（F1/R9）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
	CONTENT_STATUSES,
	ENABLED_ONLY_NOTICE,
	FALLBACK_NOTICE,
	SCALE_WRITE_KEYS,
	VACCINATION_WRITE_KEYS,
	addDraftLike,
	contentStatusVariant,
	emptyDomainDraft,
	formatMonths,
	formatOptionsText,
	optionsLabel,
	parseMonthsText,
	parseNonNegativeInt,
	parseOptionsText,
	removeDraftLike,
	scaleAgeLabel,
	scaleSummary,
	schedulePreview,
	schedulePreviewLabel,
	toDefinitionDomains,
	toDefinitionItems,
	toDomainDrafts,
	toItemDrafts,
	updateDraftLike,
	vaccinationSummary,
	validateScaleForm,
	validateVaccinationForm,
	type DomainDraft,
	type ItemDraft,
} from "../src/lib/baseData.ts";

const BACKEND_DIR =
	"../../../../service-vertx-kotlin/libs/healthcare/src/main/kotlin/com/ovaphlow/crate/healthcare/";

function readBackend(file: string): string {
	return readFileSync(new URL(`${BACKEND_DIR}${file}`, import.meta.url), "utf8");
}

function quoted(text: string): string[] {
	return [...text.matchAll(/"([^"]*)"/g)].map((match) => match[1] ?? "");
}

// ─── 常量漂移守卫（直接读后端源码）─────────────────────────────────────────

test("疫苗类型写入白名单与后端 VACCINATION_KEYS 逐字一致", () => {
	const source = readBackend("BaseDataService.kt");
	const matched = /private val VACCINATION_KEYS =\s*setOf\(([^)]*)\)/s.exec(source);
	assert.ok(matched, "后端必须声明 VACCINATION_KEYS");
	assert.deepEqual(quoted(matched[1] ?? ""), [...VACCINATION_WRITE_KEYS]);
});

test("发育量表写入白名单与后端 SCALE_KEYS 逐字一致", () => {
	const source = readBackend("BaseDataService.kt");
	const matched = /private val SCALE_KEYS =\s*setOf\(([^)]*)\)/s.exec(source);
	assert.ok(matched, "后端必须声明 SCALE_KEYS");
	assert.deepEqual(quoted(matched[1] ?? ""), [...SCALE_WRITE_KEYS]);
});

test("内容状态白名单与后端 STATUSES 逐字一致", () => {
	const source = readBackend("BaseDataService.kt");
	const matched = /val STATUSES: Set<String> = linkedSetOf\(([^)]*)\)/.exec(source);
	assert.ok(matched, "后端必须声明内容 STATUSES");
	assert.deepEqual(quoted(matched[1] ?? ""), [...CONTENT_STATUSES]);
});

// ─── 读库优先 / 常量回落口径 ───────────────────────────────────────────────

test("回落口径文案必须常驻（读库优先、库空回落常量、与改造前一致）", () => {
	assert.match(FALLBACK_NOTICE, /读库优先/);
	assert.match(FALLBACK_NOTICE, /常量回落/);
	assert.match(FALLBACK_NOTICE, /库为空/);
	assert.match(FALLBACK_NOTICE, /改造前一致/);
	assert.match(FALLBACK_NOTICE, /不是使用前置条件/);
	assert.match(ENABLED_ONLY_NOTICE, /只有「启用」记录参与内容加载/);
	assert.match(ENABLED_ONLY_NOTICE, /停用/);
});

test("页面必须展示回落口径与启用口径", () => {
	const page = readFileSync(new URL("../src/components/BaseDataPage.tsx", import.meta.url), "utf8");
	assert.match(page, /FALLBACK_NOTICE/);
	assert.match(page, /ENABLED_ONLY_NOTICE/);
	assert.match(page, /回落既有常量/, "空态文案也要说明回落");
});

// ─── 疫苗类型：月龄数组与排程预览 ──────────────────────────────────────────

test("月龄文本解析：逗号/顿号/空格分隔的非负整数；非法或空 → null", () => {
	assert.deepEqual(parseMonthsText("0、1、6"), [0, 1, 6]);
	assert.deepEqual(parseMonthsText("0, 1, 6"), [0, 1, 6]);
	assert.deepEqual(parseMonthsText(" 3  6 "), [3, 6]);
	assert.equal(parseMonthsText(""), null);
	assert.equal(parseMonthsText("0、a"), null);
	assert.equal(parseMonthsText("0、-1"), null);
	assert.equal(parseMonthsText("0.5"), null);
});

test("月龄数组展示", () => {
	assert.equal(formatMonths([0, 1, 6]), "0、1、6");
	assert.equal(formatMonths([]), "-");
	assert.equal(formatMonths(null), "-");
	assert.equal(vaccinationSummary({ dose_count: 3, recommended_months: [0, 1, 6] }), "3 剂 · 0、1、6 月");
});

test("排程展开预览与后端 toScheduleEntries 同口径（code × 剂次 1..dose_count）", () => {
	const entries = schedulePreview({
		code: "HEPB",
		name: "乙肝疫苗",
		dose_count: 3,
		recommended_months: [0, 1, 6],
	});
	assert.deepEqual(entries, [
		{ doseNo: 1, code: "HEPB", name: "乙肝疫苗", month: 0 },
		{ doseNo: 2, code: "HEPB", name: "乙肝疫苗", month: 1 },
		{ doseNo: 3, code: "HEPB", name: "乙肝疫苗", month: 6 },
	]);
	assert.equal(schedulePreviewLabel({ code: "HEPB", name: "乙肝疫苗", dose_count: 3, recommended_months: [0, 1, 6] }), "第1剂 0 月、第2剂 1 月、第3剂 6 月");
	// 月龄缺失的剂次跳过（读侧兜底）
	assert.deepEqual(
		schedulePreview({ code: "X", name: "X", dose_count: 3, recommended_months: [0] }).map((entry) => entry.doseNo),
		[1],
	);
	assert.equal(
		schedulePreviewLabel({ code: "X", name: "X", dose_count: 2, recommended_months: [] }),
		"无可用剂次（月龄数组缺失）",
	);
});

// ─── 发育量表：definition 编辑 ─────────────────────────────────────────────

test("选项文本解析：每行 `值|文案`，值非负整数、文案非空", () => {
	assert.deepEqual(parseOptionsText("0|正常\n1|异常"), [
		{ value: 0, label: "正常" },
		{ value: 1, label: "异常" },
	]);
	assert.deepEqual(parseOptionsText("0｜正常"), [{ value: 0, label: "正常" }]);
	assert.deepEqual(parseOptionsText("0\t正常"), [{ value: 0, label: "正常" }]);
	assert.equal(parseOptionsText(""), null);
	assert.equal(parseOptionsText("正常"), null, "缺少分隔符必须拒绝");
	assert.equal(parseOptionsText("a|正常"), null, "值必须是非负整数");
	assert.equal(parseOptionsText("0|"), null, "文案必须非空");
});

test("选项展示与格式化", () => {
	assert.equal(formatOptionsText([{ value: 0, label: "正常" }, { value: 1, label: "异常" }]), "0|正常\n1|异常");
	assert.equal(formatOptionsText([]), "");
	assert.equal(optionsLabel([{ value: 0, label: "正常" }]), "0=正常");
	assert.equal(optionsLabel([]), "-");
});

test("后端 definition → 编辑草稿 → 提交体 往返一致", () => {
	const definition = {
		domains: [{ code: "GM", name: "粗大运动" }],
		items: [
			{
				code: "GM-1",
				domain: "GM",
				prompt: "能否独坐",
				min_months: 6,
				max_months: 12,
				options: [{ value: 0, label: "否" }, { value: 1, label: "是" }],
			},
		],
	};
	const domainDrafts = toDomainDrafts(definition);
	const itemDrafts = toItemDrafts(definition);
	assert.deepEqual(domainDrafts, [{ code: "GM", name: "粗大运动" }]);
	assert.equal(itemDrafts[0]?.optionsText, "0|否\n1|是");
	assert.deepEqual(toDefinitionDomains(domainDrafts), [{ code: "GM", name: "粗大运动" }]);
	assert.deepEqual(toDefinitionItems(itemDrafts), definition.items);
	// 空 definition → 一行空草稿（可直接填写）
	assert.deepEqual(toDomainDrafts({ domains: [] }), [{ code: "", name: "" }]);
	assert.deepEqual(toDomainDrafts(null), [{ code: "", name: "" }]);
	assert.equal(toItemDrafts(null).length, 1);
});

test("草稿规范化：去空白、丢弃空 code、领域名可省略", () => {
	assert.deepEqual(
		toDefinitionDomains([
			{ code: " GM ", name: " 粗大运动 " },
			{ code: "  ", name: "空行" },
			{ code: "FM", name: "" },
		]),
		[{ code: "GM", name: "粗大运动" }, { code: "FM" }],
	);
	assert.deepEqual(
		toDefinitionItems([
			{ code: "GM-1", domain: " GM ", prompt: " 独坐 ", min_months: "6", max_months: "12", optionsText: "0|否" },
			{ code: " ", domain: "GM", prompt: "x", min_months: "0", max_months: "1", optionsText: "0|否" },
		]),
		[{ code: "GM-1", domain: "GM", prompt: "独坐", min_months: 6, max_months: 12, options: [{ value: 0, label: "否" }] }],
	);
});

test("量表摘要与适用月龄展示", () => {
	const scale = {
		definition: { domains: [{ code: "GM" }, { code: "FM" }], items: [{}, {}, {}] as never[] },
	};
	assert.equal(scaleSummary(scale), "2 个领域 · 3 个条目");
	assert.equal(scaleSummary({ definition: { domains: [], items: [] } }), "0 个领域 · 0 个条目");
	assert.equal(scaleAgeLabel({ age_min_months: 0, age_max_months: 36 }), "0–36 月");
});

// ─── 草稿数组编辑（纯函数）─────────────────────────────────────────────────

test("草稿数组编辑是纯函数：增删改都不修改入参", () => {
	const base: DomainDraft[] = [emptyDomainDraft()];
	const added = addDraftLike(base, { code: "GM", name: "粗大运动" });
	assert.equal(base.length, 1, "addDraftLike 不得修改入参");
	assert.equal(added.length, 2);

	const updated = updateDraftLike(added, 1, { name: "粗大运动发育" });
	assert.equal(added[1]?.name, "粗大运动", "updateDraftLike 不得修改入参");
	assert.equal(updated[1]?.name, "粗大运动发育");
	assert.equal(updated[1]?.code, "GM");

	const removed = removeDraftLike(updated, 0);
	assert.equal(updated.length, 2, "removeDraftLike 不得修改入参");
	assert.deepEqual(removed.map((item) => item.code), ["GM"]);

	assert.equal(updateDraftLike(base, 9, { code: "X" })[0]?.code, "");
	assert.equal(removeDraftLike(base, 9).length, 1);
});

// ─── 表单校验（与后端同口径）───────────────────────────────────────────────

test("非负整数解析", () => {
	assert.equal(parseNonNegativeInt("0"), 0);
	assert.equal(parseNonNegativeInt("12"), 12);
	assert.equal(parseNonNegativeInt(""), null);
	assert.equal(parseNonNegativeInt("-1"), null);
	assert.equal(parseNonNegativeInt("1.5"), null);
	assert.equal(parseNonNegativeInt("abc"), null);
});

test("疫苗类型校验：必填、剂次数 ≥1、月龄个数 = 剂次数、排序号、状态", () => {
	const valid = {
		code: "HEPB",
		name: "乙肝疫苗",
		doseCountText: "3",
		monthsText: "0、1、6",
		sortOrderText: "0",
		status: "启用",
	};
	assert.equal(validateVaccinationForm(valid), null);
	assert.equal(validateVaccinationForm({ ...valid, sortOrderText: "" }), null, "排序号可留空（默认 0）");
	assert.match(validateVaccinationForm({ ...valid, code: " " }) ?? "", /疫苗编码必填/);
	assert.match(validateVaccinationForm({ ...valid, name: "" }) ?? "", /疫苗名称必填/);
	assert.match(validateVaccinationForm({ ...valid, doseCountText: "0" }) ?? "", /剂次数必须是 ≥ 1 的整数/);
	assert.match(validateVaccinationForm({ ...valid, monthsText: "" }) ?? "", /推荐月龄必须是非负整数数组/);
	assert.match(
		validateVaccinationForm({ ...valid, monthsText: "0、1" }) ?? "",
		/推荐月龄个数必须等于剂次数（当前 2 个，剂次数 3）/,
	);
	assert.match(validateVaccinationForm({ ...valid, sortOrderText: "-1" }) ?? "", /排序号必须是非负整数/);
	assert.match(validateVaccinationForm({ ...valid, status: "未知" }) ?? "", /状态必须是/);
});

test("发育量表校验：必填、月龄区间、领域非空且不重复、条目字段与选项格式", () => {
	const domains: DomainDraft[] = [{ code: "GM", name: "粗大运动" }];
	const items: ItemDraft[] = [
		{ code: "GM-1", domain: "GM", prompt: "能否独坐", min_months: "6", max_months: "12", optionsText: "0|否\n1|是" },
	];
	const valid = {
		scaleCode: "ASQ",
		scaleVersion: "v1",
		name: "发育筛查量表",
		ageMinText: "0",
		ageMaxText: "36",
		domains,
		items,
		status: "启用",
	};
	assert.equal(validateScaleForm(valid), null);
	assert.match(validateScaleForm({ ...valid, scaleCode: " " }) ?? "", /量表编码必填/);
	assert.match(validateScaleForm({ ...valid, scaleVersion: "" }) ?? "", /量表版本必填/);
	assert.match(validateScaleForm({ ...valid, name: "" }) ?? "", /量表名称必填/);
	assert.match(validateScaleForm({ ...valid, ageMinText: "-1" }) ?? "", /适用月龄下界必须是非负整数/);
	assert.match(validateScaleForm({ ...valid, ageMaxText: "abc" }) ?? "", /适用月龄上界必须是非负整数/);
	assert.match(validateScaleForm({ ...valid, ageMinText: "12", ageMaxText: "6" }) ?? "", /上界不得小于下界/);
	assert.match(validateScaleForm({ ...valid, domains: [] }) ?? "", /至少填写一个领域/);
	assert.match(
		validateScaleForm({ ...valid, domains: [{ code: "GM", name: "" }, { code: "GM", name: "" }] }) ?? "",
		/领域编码重复：GM/,
	);
	assert.match(validateScaleForm({ ...valid, items: [] }) ?? "", /至少填写一个条目/);
	assert.match(
		validateScaleForm({ ...valid, items: [{ ...items[0]!, domain: "" } as ItemDraft] }) ?? "",
		/缺少所属领域/,
	);
	assert.match(
		validateScaleForm({ ...valid, items: [{ ...items[0]!, domain: "FM" } as ItemDraft] }) ?? "",
		/所属领域不在领域清单内：FM/,
	);
	assert.match(
		validateScaleForm({ ...valid, items: [{ ...items[0]!, prompt: " " } as ItemDraft] }) ?? "",
		/缺少题目文本/,
	);
	assert.match(
		validateScaleForm({ ...valid, items: [{ ...items[0]!, min_months: "12", max_months: "6" } as ItemDraft] }) ?? "",
		/月龄上界不得小于下界/,
	);
	assert.match(
		validateScaleForm({ ...valid, items: [{ ...items[0]!, optionsText: "正常" } as ItemDraft] }) ?? "",
		/选项必须每行/,
	);
	assert.match(validateScaleForm({ ...valid, status: "停用中" }) ?? "", /状态必须是/);
});

test("内容状态徽章配色", () => {
	assert.equal(contentStatusVariant("启用"), "success");
	assert.equal(contentStatusVariant("停用"), "default");
	assert.equal(contentStatusVariant(null), "default");
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("基础数据维护菜单项：儿保域可见 + base_data:manage 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source.split("\n").find((text) => text.includes('"/dashboard/base-data"'));
	assert.ok(line, "Sidebar 必须包含基础数据维护菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/);
	assert.match(line, /requiredPermissions:\s*\["base_data:manage"\]/);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(new URL("../src/pages/dashboard/base-data.astro", import.meta.url), "utf8");
	assert.match(page, /BaseDataPage/);
});

test("共享客户端提供 F2 端点且路径与后端一致", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	for (const path of ["/healthcare/v1/base-vaccination-types", "/healthcare/v1/base-developmental-scales"]) {
		assert.ok(client.includes(path), `aceso.ts 必须包含 F2 端点 ${path}`);
	}
	assert.match(client, /export function updateBaseVaccinationType/);
	assert.match(client, /export function updateBaseDevelopmentalScale/);
});
