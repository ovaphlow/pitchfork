/**
 * 儿童健康报告模型（`src/lib/reportModel.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   node --test apps/aceso/tests/report.test.ts
 *
 * 锁定 053 C2 的导出契约：段落顺序、空段仍产出、CSV 转义、JSON 可解析且含全部段落 key、
 * 身份证号类键被剔除、文件名格式。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	REPORT_SECTIONS,
	buildReportSections,
	csvCell,
	filterReportByRange,
	reportFileName,
	toReportCsv,
	toReportJson,
} from "../src/lib/reportModel.ts";
import type { ChildHealthReport } from "@pitchfork/shared/aceso";

const PATIENT_ID = "01J000000000000000000000AA";

function fixture(): ChildHealthReport {
	return {
		patient: {
			id: PATIENT_ID,
			name: "张三",
			gender: "男",
			birth_date: "2024-03-01",
			age_months: 30,
			person_type: "儿童",
		},
		profile: {
			id: "profile-1",
			patient_id: PATIENT_ID,
			guardian_name: "李四",
			guardian_relationship: "母亲",
			guardian_phone: "13800000000",
			birth_weight_g: 3200,
			birth_height_mm: 500,
			delivery_mode: "顺产",
			feeding_method: "母乳",
			vaccination_summary: "已完成基础免疫",
			vaccination_records: [],
			remark: "无特殊",
			metadata: null,
			created_at: "2024-03-01T08:00:00+08:00",
			updated_at: "2024-03-01T08:00:00+08:00",
		},
		checkups: [
			{
				id: "checkup-1",
				patient_id: PATIENT_ID,
				checkup_date: "2026-09-01",
				checkup_type: "常规",
				age_months: 30,
				items: [
					{ item: "体重", result: "13kg", conclusion: "正常" },
					// 故意混入逗号 / 双引号 / 换行，验证 CSV 转义
					{ item: "身长, 含逗号", result: '他说"很好"', conclusion: "正常\n复测" },
				],
				conclusion: "正常",
				guidance: "注意营养",
				next_checkup_date: "2026-12-01",
				operator: "王护士",
				remark: "门诊",
				metadata: null,
				created_at: "2026-09-01T08:00:00+08:00",
				updated_at: "2026-09-01T08:00:00+08:00",
				diagnosis: "健康",
				diagnosis_code: "Z00",
				status: "已完成",
				reviewed_by: "赵医生",
				reviewed_at: "2026-09-01T09:00:00+08:00",
			},
		],
		growth: [
			{
				id: "growth-1",
				patient_id: PATIENT_ID,
				measured_at: "2026-09-01T09:30:00+08:00",
				age_days: 915,
				height_cm: 92.5,
				weight_kg: 13,
				head_circumference_cm: 49,
				percentile_snapshot: { height: { p: 50 }, weight: { p: 85 } },
				operator: "王护士",
				note: "状态良好",
				metadata: null,
				created_at: "2026-09-01T09:30:00+08:00",
				updated_at: "2026-09-01T09:30:00+08:00",
				deleted_at: null,
			},
		],
		vaccinations: {
			records: [
				{
					id: "vacc-1",
					patient_id: PATIENT_ID,
					vaccine_code: "MMR",
					vaccine_name: "麻腮风",
					dose_no: 1,
					administered_at: "2026-08-01T00:00:00+08:00",
					facility: "社区卫生中心",
					batch_no: "B123",
					status: "已接种",
					operator: "王护士",
					note: null,
					metadata: null,
					created_at: "2026-08-01T00:00:00+08:00",
					updated_at: "2026-08-01T00:00:00+08:00",
				},
			],
			schedule: [
				{
					vaccine_code: "MMR",
					vaccine_name: "麻腮风",
					dose_no: 1,
					recommended_month: 18,
					due_date: "2025-09-01",
					status: "已接种",
				},
				{
					vaccine_code: "HEPB",
					vaccine_name: "乙肝",
					dose_no: 3,
					recommended_month: 6,
					due_date: "2024-09-01",
					status: "逾期",
				},
			],
			summary: { total: 2, 已接种: 1, 待接种: 0, 临期: 0, 逾期: 1, 暂缓: 0, 拒种: 0 },
		},
		screenings: [
			{
				id: "screening-1",
				patient_id: PATIENT_ID,
				scale_code: "PLACEHOLDER",
				scale_version: "1.0",
				age_months: 30,
				administered_at: "2026-09-02T10:00:00+08:00",
				item_scores: { A: 1 },
				domain_scores: { 大运动: 1 },
				domain_max_scores: { 大运动: 1 },
				total_score: 1,
				max_total_score: 1,
				classification: "正常",
				triggered_items: [],
				conclusion: "发育正常",
				checkup_id: "checkup-1",
				operator: "王护士",
				metadata: null,
				created_at: "2026-09-02T10:00:00+08:00",
				updated_at: "2026-09-02T10:00:00+08:00",
			},
		],
		// 故意留空：验证「空段仍产出且 rows 为空」
		referrals: [],
		guidance: [
			{
				id: "guidance-1",
				patient_id: PATIENT_ID,
				template_code: "T1",
				age_band: "25-60月",
				topic: "喂养",
				kind: "疾病防控",
				content: "勤洗手",
				delivered_at: "2026-09-03T11:00:00+08:00",
				channel: "站内",
				operator: "王护士",
				metadata: null,
				created_at: "2026-09-03T11:00:00+08:00",
			},
		],
		allergies: [{ allergen: "青霉素", reaction: "皮疹" }],
		meta: {
			generated_at: "2026-10-06T15:30:00+08:00",
			counts: { checkups: 1, growth: 1, screenings: 1, referrals: 0, guidance: 1, allergies: 1 },
		},
	};
}

test("buildReportSections 段落顺序与 REPORT_SECTIONS 一致且空段仍产出", () => {
	const sections = buildReportSections(fixture());
	assert.deepEqual(
		sections.map((section) => section.key),
		REPORT_SECTIONS.map((section) => section.key),
	);
	assert.equal(sections.length, REPORT_SECTIONS.length, "每个段落都要产出，不因缺数据而缺段");

	const referrals = sections.find((section) => section.key === "referrals");
	assert.ok(referrals, "空段落 referrals 必须存在");
	assert.deepEqual(referrals.rows, [], "空段 rows 为空数组");
	assert.ok(Array.isArray(referrals.headers) && referrals.headers.length > 0, "空段仍保留表头");
});

test("toReportJson 可解析且含全部段落 key", () => {
	const parsed = JSON.parse(toReportJson(fixture())) as Record<string, unknown>;
	for (const section of REPORT_SECTIONS) {
		assert.ok(section.key in parsed, `JSON 缺少段落 key: ${section.key}`);
		assert.ok(parsed[section.key] !== null && typeof parsed[section.key] === "object");
	}
	assert.equal((parsed.patient as { name?: string }).name, "张三");
	assert.ok(parsed.generated_at, "JSON 含报告生成时间");
});

test("toReportJson 剔除身份证号类键（含意外混入）", () => {
	const report = fixture();
	// 模拟后端/上游意外带出身份证号，导出必须剔除（053 §3 Dh）
	const withSensitive = {
		...report,
		patient: { ...report.patient, id_card: "110101199001011234", id_number: "X123" },
	} as unknown as ChildHealthReport;

	const json = toReportJson(withSensitive);
	assert.equal(json.includes("id_card"), false, "导出不得出现 id_card");
	assert.equal(json.includes("id_number"), false, "导出不得出现 id_number");
	assert.equal(json.includes("110101199001011234"), false, "导出不得出现身份证号明文");

	const parsed = JSON.parse(json) as { patient: Record<string, unknown> };
	assert.equal("id_card" in parsed.patient, false);
	assert.equal("id_number" in parsed.patient, false);
});

test("toReportCsv 含全部段落标题且转义逗号/引号/换行", () => {
	const csv = toReportCsv(fixture());
	for (const section of REPORT_SECTIONS) {
		assert.ok(csv.includes(`# ${section.label}`), `CSV 缺少段落标题: ${section.label}`);
	}
	// 检查项整格 = `体重：13kg：正常；身长, 含逗号：他说"很好"：正常\n复测`
	// 含逗号/引号/换行 → 整格加引号且内部引号双写
	assert.ok(
		csv.includes('"体重：13kg：正常；身长, 含逗号：他说""很好""：正常\n复测"'),
		"含逗号/引号/换行的字段必须被正确转义",
	);
	// 汇总行与排程行都在接种区块内
	assert.ok(csv.includes("汇总"), "接种段落含汇总行");
	assert.ok(csv.includes("麻腮风"), "接种段落含排程行");
});

test("csvCell 基本转义规则", () => {
	assert.equal(csvCell("普通"), "普通");
	assert.equal(csvCell("a,b"), '"a,b"');
	assert.equal(csvCell('a"b'), '"a""b"');
	assert.equal(csvCell("a\nb"), '"a\nb"');
	assert.equal(csvCell(""), "");
	assert.equal(csvCell(null), "");
});

test("reportFileName 基名格式正确且文件名安全", () => {
	assert.equal(reportFileName("张三", "2026-10-06T15:30:00+08:00"), "儿童健康报告-张三-20261006-1530");
	assert.equal(
		reportFileName("张三/李四", "2026-10-06T15:30:00+08:00"),
		"儿童健康报告-张三_李四-20261006-1530",
		"路径分隔符等不安全字符被替换",
	);
	assert.equal(reportFileName("", null), "儿童健康报告-未知-00000000-0000");
});

test("filterReportByRange 按业务日期过滤并重算计数", () => {
	const report = fixture();
	const filtered = filterReportByRange(report, "2026-09-02", "2026-09-30");
	assert.equal(filtered.checkups.length, 0, "体检 2026-09-01 在区间外");
	assert.equal(filtered.growth.length, 0, "成长 2026-09-01 在区间外");
	assert.equal(filtered.screenings.length, 1, "筛查 2026-09-02 在区间内");
	assert.equal(filtered.guidance.length, 1, "指导 2026-09-03 在区间内");
	assert.equal(filtered.meta.counts.screenings, 1);
	assert.equal(filtered.meta.counts.checkups, 0);

	// 空区间参数返回原对象（不做无谓复制）
	assert.equal(filterReportByRange(report, "", ""), report);
});
