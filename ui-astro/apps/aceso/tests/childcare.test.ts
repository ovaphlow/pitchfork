/**
 * 054 C3：保育员工作台展示/表单辅助（`src/lib/childcare.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `nutrition.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 级别/状态/时段/检查项常量与后端**逐字一致**（含直接读后端 Kotlin 源码的**漂移守卫**：
 *     前端镜像了 `TIME_SLOTS` / `RESULTS` / `CHECK_ITEMS` / `LEVELS` / 两处 `STATUSES`，
 *     后端一改前端测试即红）；
 *   - 状态机镜像：日程 `草稿→{已发布,已取消}→已取消`、巡查 `待整改→已整改→已关闭`；
 *   - 表单校验与后端 `IllegalArgumentException` 口径一致；
 *   - 菜单接线：保育员工作台在儿保域可见、受 `nursing:document` 显隐控制（F1/R9），
 *     菜单路径有对应 Astro 页面（静态构建下不会 404）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type {
	ChildcareBehaviorObservation,
	ChildcareSafetyInspection,
	ChildcareSafetyInspectionItem,
} from "@pitchfork/shared/aceso";

import {
	BEHAVIOR_LEVELS,
	CHECK_ITEMS,
	INSPECTION_RESULTS,
	INSPECTION_STATUSES,
	INSPECTION_TRANSITIONS,
	SCHEDULE_STATUSES,
	SCHEDULE_TRANSITIONS,
	TIME_SLOTS,
	allowedInspectionTargets,
	allowedScheduleTargets,
	behaviorLevelCount,
	behaviorLevelLabel,
	behaviorLevelRank,
	behaviorLevelVariant,
	canAppendInspectionItem,
	canCloseInspection,
	canTransitionSchedule,
	checkItemName,
	hazardItems,
	inspectionHeadline,
	inspectionItemLabel,
	inspectionResultCount,
	inspectionResultVariant,
	inspectionStatusAfterAppend,
	inspectionStatusVariant,
	isIsoDate,
	isKnownCheckItem,
	isScheduleTerminal,
	isValidTimeSlot,
	normalizeInspectionItem,
	optionalInstant,
	parseParticipantCount,
	scheduleStatusVariant,
	sortBehaviorObservations,
	validateActivityLogForm,
	validateAppendItemForm,
	validateBehaviorObservationForm,
	validateClassScheduleForm,
	validateSafetyInspectionForm,
} from "../src/lib/childcare.ts";

const BACKEND_DIR = "../../../../service-vertx-kotlin/libs/nursing/src/main/kotlin/com/ovaphlow/crate/nursing/";

function readBackend(file: string): string {
	return readFileSync(new URL(`${BACKEND_DIR}${file}`, import.meta.url), "utf8");
}

function quoted(text: string): string[] {
	return [...text.matchAll(/"([^"]*)"/g)].map((match) => match[1] ?? "");
}

function observationFixture(
	overrides: Partial<ChildcareBehaviorObservation> = {},
): ChildcareBehaviorObservation {
	return {
		id: "01JTESTOBS00000000000000",
		patient_id: "01JTESTCHILD0000000000000",
		observed_at: "2026-10-06T10:00:00+08:00",
		category: "进餐",
		description: "进食较慢",
		level: "一般",
		operator: "保育员甲",
		metadata: null,
		created_at: "2026-10-06T10:00:00+08:00",
		...overrides,
	};
}

function itemFixture(overrides: Partial<ChildcareSafetyInspectionItem> = {}): ChildcareSafetyInspectionItem {
	return { item_code: "FLOOR_SLIP", result: "合格", ...overrides };
}

function inspectionFixture(
	overrides: Partial<ChildcareSafetyInspection> = {},
): ChildcareSafetyInspection {
	return {
		id: "01JTESTINSPECT0000000000",
		area: "小班教室",
		inspected_at: "2026-10-06T09:00:00+08:00",
		items: [],
		status: "待整改",
		operator: "保育员甲",
		metadata: null,
		created_at: "2026-10-06T09:00:00+08:00",
		updated_at: "2026-10-06T09:00:00+08:00",
		...overrides,
	};
}

// ─── 常量漂移守卫（直接读后端源码）─────────────────────────────────────────

test("固定时段 TIME_SLOTS 与后端 ChildcareClassScheduleService 逐字一致（8 个）", () => {
	const source = readBackend("ChildcareClassScheduleService.kt");
	const backendSlots = [...source.matchAll(/"(\d{2}:\d{2}-\d{2}:\d{2})"/g)].map((match) => match[1]);
	assert.deepEqual(backendSlots, [...TIME_SLOTS], "前端镜像的 TIME_SLOTS 与后端不一致");
	assert.equal(TIME_SLOTS.length, 8);
	assert.equal(isValidTimeSlot("08:00-09:00"), true);
	assert.equal(isValidTimeSlot("12:00-13:00"), false, "午休时段不在白名单");
	assert.equal(isValidTimeSlot(""), false);
});

test("检查项结果 RESULTS 与后端 ChildcareSafetyInspectionService 逐字一致", () => {
	const source = readBackend("ChildcareSafetyInspectionService.kt");
	const matched = /val RESULTS = setOf\(([^)]*)\)/.exec(source);
	assert.ok(matched, "后端必须声明 RESULTS 常量");
	assert.deepEqual(quoted(matched[1] ?? ""), [...INSPECTION_RESULTS]);
});

test("12 项检查项 CHECK_ITEMS（code + 名称）与后端逐字一致", () => {
	const source = readBackend("ChildcareSafetyInspectionService.kt");
	const backendItems = [...source.matchAll(/"([A-Z_]+)" to "([^"]+)"/g)].map((match) => ({
		code: match[1] ?? "",
		name: match[2] ?? "",
	}));
	assert.equal(backendItems.length, 12, "后端检查项应为 12 项");
	assert.deepEqual(backendItems, CHECK_ITEMS.map((item) => ({ code: item.code, name: item.name })));
});

test("行为观测级别 LEVELS 与后端 ChildcareBehaviorObservationService 逐字一致", () => {
	const source = readBackend("ChildcareBehaviorObservationService.kt");
	const matched = /val LEVELS = setOf\(([^)]*)\)/.exec(source);
	assert.ok(matched, "后端必须声明 LEVELS 常量");
	assert.deepEqual(quoted(matched[1] ?? ""), [...BEHAVIOR_LEVELS]);
});

test("日程与巡查状态常量与后端逐字一致", () => {
	const schedule = readBackend("ChildcareClassScheduleService.kt");
	const scheduleStatuses = [
		/const val STATUS_DRAFT = "([^"]+)"/.exec(schedule)?.[1],
		/const val STATUS_PUBLISHED = "([^"]+)"/.exec(schedule)?.[1],
		/const val STATUS_CANCELLED = "([^"]+)"/.exec(schedule)?.[1],
	];
	assert.deepEqual(scheduleStatuses, [...SCHEDULE_STATUSES]);

	const inspection = readBackend("ChildcareSafetyInspectionService.kt");
	const inspectionStatuses = [
		/const val STATUS_PENDING = "([^"]+)"/.exec(inspection)?.[1],
		/const val STATUS_RECTIFIED = "([^"]+)"/.exec(inspection)?.[1],
		/const val STATUS_CLOSED = "([^"]+)"/.exec(inspection)?.[1],
	];
	assert.deepEqual(inspectionStatuses, [...INSPECTION_STATUSES]);
});

// ─── 行为观测（级别与排序）─────────────────────────────────────────────────

test("级别徽章配色与展示：需干预=danger、关注=warning、一般=default、未填=info", () => {
	assert.deepEqual([...BEHAVIOR_LEVELS], ["一般", "关注", "需干预"]);
	assert.equal(behaviorLevelVariant("需干预"), "danger");
	assert.equal(behaviorLevelVariant("关注"), "warning");
	assert.equal(behaviorLevelVariant("一般"), "default");
	assert.equal(behaviorLevelVariant(""), "info");
	assert.equal(behaviorLevelVariant(null), "info");
	assert.equal(behaviorLevelLabel("需干预"), "需干预");
	assert.equal(behaviorLevelLabel(null), "未填级别");
	assert.equal(behaviorLevelRank("需干预"), 0);
	assert.equal(behaviorLevelRank("关注"), 1);
	assert.equal(behaviorLevelRank("一般"), 2);
	assert.equal(behaviorLevelRank(null), 50);
	assert.equal(behaviorLevelRank("未知"), 99);
});

test("观测排序：需干预在前，同级保持服务端顺序（稳定且不改入参）", () => {
	const observations = [
		observationFixture({ level: "一般", description: "A" }),
		observationFixture({ level: "需干预", description: "B" }),
		observationFixture({ level: "关注", description: "C" }),
		observationFixture({ level: null, description: "D" }),
	];
	const sorted = sortBehaviorObservations(observations);
	assert.deepEqual(
		sorted.map((observation) => observation.description),
		["B", "C", "A", "D"],
	);
	assert.equal(observations[0]?.description, "A", "排序不得修改入参");
});

test("级别计数：未填/未知计入 other，不丢弃", () => {
	assert.deepEqual(behaviorLevelCount([]), { 需干预: 0, 关注: 0, 一般: 0, other: 0, total: 0 });
	assert.deepEqual(
		behaviorLevelCount([
			observationFixture({ level: "需干预" }),
			observationFixture({ level: "关注" }),
			observationFixture({ level: null }),
			observationFixture({ level: "未知级别" }),
		]),
		{ 需干预: 1, 关注: 1, 一般: 0, other: 2, total: 4 },
	);
});

// ─── 班级日程状态机 ────────────────────────────────────────────────────────

test("日程状态机：草稿→{已发布,已取消}、已发布→已取消、已取消为终态", () => {
	assert.deepEqual([...SCHEDULE_STATUSES], ["草稿", "已发布", "已取消"]);
	assert.deepEqual(allowedScheduleTargets("草稿"), ["已发布", "已取消"]);
	assert.deepEqual(allowedScheduleTargets("已发布"), ["已取消"]);
	assert.deepEqual(allowedScheduleTargets("已取消"), []);
	assert.equal(canTransitionSchedule("草稿", "已发布"), true);
	assert.equal(canTransitionSchedule("已发布", "草稿"), false, "发布后不可回到草稿");
	assert.equal(canTransitionSchedule("已取消", "已发布"), false, "取消为终态");
	assert.equal(canTransitionSchedule("草稿", ""), false);
	assert.equal(isScheduleTerminal("已取消"), true);
	assert.equal(isScheduleTerminal("草稿"), false);
	assert.equal(scheduleStatusVariant("已发布"), "success");
	assert.equal(scheduleStatusVariant("草稿"), "warning");
	assert.equal(scheduleStatusVariant("已取消"), "default");
	// 与后端 TRANSITIONS 的键一致（漂移守卫的补充：键集合）
	assert.deepEqual(Object.keys(SCHEDULE_TRANSITIONS).sort(), [...SCHEDULE_STATUSES].sort());
});

// ─── 安全巡查（隐患闭环）───────────────────────────────────────────────────

test("巡查状态机：严格线性 待整改 → 已整改 → 已关闭", () => {
	assert.deepEqual([...INSPECTION_STATUSES], ["待整改", "已整改", "已关闭"]);
	assert.deepEqual(allowedInspectionTargets("待整改"), ["已整改"]);
	assert.deepEqual(allowedInspectionTargets("已整改"), ["已关闭"]);
	assert.deepEqual(allowedInspectionTargets("已关闭"), []);
	assert.deepEqual(Object.keys(INSPECTION_TRANSITIONS).sort(), [...INSPECTION_STATUSES].sort());
});

test("关闭与追加的可行性：仅「已整改」可关闭；「已关闭」不可追加", () => {
	assert.equal(canCloseInspection("已整改"), true);
	assert.equal(canCloseInspection("待整改"), false, "待整改跳步关闭由服务端 409 兜底");
	assert.equal(canCloseInspection("已关闭"), false);
	assert.equal(canAppendInspectionItem("待整改"), true);
	assert.equal(canAppendInspectionItem("已整改"), true);
	assert.equal(canAppendInspectionItem("已关闭"), false);
});

test("追加整改项后的状态：待整改 → 已整改，其余不变", () => {
	assert.equal(inspectionStatusAfterAppend("待整改"), "已整改");
	assert.equal(inspectionStatusAfterAppend("已整改"), "已整改");
	assert.equal(inspectionStatusAfterAppend("已关闭"), "已关闭");
	assert.equal(inspectionStatusAfterAppend(null), "待整改");
});

test("巡查状态与结果徽章配色", () => {
	assert.equal(inspectionStatusVariant("待整改"), "danger");
	assert.equal(inspectionStatusVariant("已整改"), "warning");
	assert.equal(inspectionStatusVariant("已关闭"), "success");
	assert.equal(inspectionResultVariant("合格"), "success");
	assert.equal(inspectionResultVariant("不合格"), "danger");
	assert.equal(inspectionResultVariant("不适用"), "default");
	assert.equal(inspectionResultVariant(null), "info");
});

test("检查项名称：已知 code 取常量名，未知 code 原样透出（不隐藏历史数据）", () => {
	assert.equal(checkItemName("FLOOR_SLIP"), "地面防滑与积水");
	assert.equal(checkItemName("LEGACY_CODE"), "LEGACY_CODE");
	assert.equal(checkItemName(null), "未命名检查项");
	assert.equal(isKnownCheckItem("FLOOR_SLIP"), true);
	assert.equal(isKnownCheckItem("LEGACY_CODE"), false);
});

test("隐患项筛选、结果计数与摘要", () => {
	const inspection = inspectionFixture({
		items: [
			itemFixture({ item_code: "FLOOR_SLIP", result: "不合格", hazard: "地面有积水" }),
			itemFixture({ item_code: "FURNITURE_EDGE", result: "合格" }),
			itemFixture({ item_code: "WATER_AREA", result: "不适用" }),
			itemFixture({ item_code: "SANITATION", result: "未知结果" }),
		],
	});
	assert.deepEqual(
		hazardItems(inspection.items).map((item) => item.item_code),
		["FLOOR_SLIP"],
	);
	assert.deepEqual(inspectionResultCount(inspection), {
		合格: 1,
		不合格: 1,
		不适用: 1,
		other: 1,
		total: 4,
	});
	assert.equal(
		inspectionHeadline(inspection),
		"不合格 1 / 合格 1 / 不适用 1（共 4 项）· 待整改",
	);
	assert.equal(inspectionItemLabel(itemFixture({ result: "不合格", hazard: "地面有积水" })), "地面防滑与积水（不合格）· 隐患：地面有积水");
	assert.equal(inspectionItemLabel(itemFixture()), "地面防滑与积水（合格）");
	assert.deepEqual(inspectionResultCount({ items: [] }), {
		合格: 0,
		不合格: 0,
		不适用: 0,
		other: 0,
		total: 0,
	});
});

// ─── 表单校验 ──────────────────────────────────────────────────────────────

test("ISO 日期校验与参与人数解析", () => {
	assert.equal(isIsoDate("2026-10-06"), true);
	assert.equal(isIsoDate("2026-02-30"), false);
	assert.equal(isIsoDate(""), false);
	assert.equal(parseParticipantCount("12"), 12);
	assert.equal(parseParticipantCount("0"), 0);
	assert.equal(parseParticipantCount(""), null);
	assert.equal(parseParticipantCount("-1"), null);
	assert.equal(parseParticipantCount("1.5"), null);
	assert.equal(parseParticipantCount("abc"), null);
});

test("活动日志校验：班级/活动必填，日期与人数（若填）合法", () => {
	const valid = { className: "小班", activityName: "户外游戏", activityDate: "2026-10-06", participantCount: "12" };
	assert.equal(validateActivityLogForm(valid), null);
	assert.equal(validateActivityLogForm({ ...valid, activityDate: "", participantCount: "" }), null);
	assert.match(validateActivityLogForm({ ...valid, className: " " }) ?? "", /班级必填/);
	assert.match(validateActivityLogForm({ ...valid, activityName: "" }) ?? "", /活动名称必填/);
	assert.match(validateActivityLogForm({ ...valid, activityDate: "2026-02-30" }) ?? "", /活动日期必须是合法日期/);
	assert.match(validateActivityLogForm({ ...valid, participantCount: "-2" }) ?? "", /参与人数必须是非负整数/);
});

test("行为观测校验：儿童/分类/描述必填，级别白名单，观测时刻合法", () => {
	const valid = {
		patientId: "p1",
		category: "进餐",
		description: "进食较慢",
		level: "关注",
		observedAt: "2026-10-06T10:00",
	};
	assert.equal(validateBehaviorObservationForm(valid), null);
	assert.equal(validateBehaviorObservationForm({ ...valid, level: "", observedAt: "" }), null);
	assert.match(validateBehaviorObservationForm({ ...valid, patientId: "" }) ?? "", /请选择儿童/);
	assert.match(validateBehaviorObservationForm({ ...valid, category: "" }) ?? "", /行为分类必填/);
	assert.match(validateBehaviorObservationForm({ ...valid, description: " " }) ?? "", /行为描述必填/);
	assert.match(validateBehaviorObservationForm({ ...valid, level: "严重" }) ?? "", /级别必须是/);
	assert.match(
		validateBehaviorObservationForm({ ...valid, observedAt: "not-a-date" }) ?? "",
		/观测时刻必须是合法日期或日期时间/,
	);
});

test("班级日程校验：班级/日期/时段/活动必填，时段必须在 8 个固定值内", () => {
	const valid = { className: "小班", scheduleDate: "2026-10-06", timeSlot: "08:00-09:00", activityName: "音乐律动" };
	assert.equal(validateClassScheduleForm(valid), null);
	assert.match(validateClassScheduleForm({ ...valid, className: "" }) ?? "", /班级必填/);
	assert.match(validateClassScheduleForm({ ...valid, scheduleDate: "" }) ?? "", /日程日期必填/);
	assert.match(validateClassScheduleForm({ ...valid, timeSlot: "" }) ?? "", /请选择时段/);
	assert.match(validateClassScheduleForm({ ...valid, timeSlot: "12:00-13:00" }) ?? "", /时段必须是固定时段之一/);
	assert.match(validateClassScheduleForm({ ...valid, activityName: " " }) ?? "", /活动名称必填/);
});

test("安全巡查建单校验：区域必填，各项 code/result 合法（空 items 允许）", () => {
	assert.equal(validateSafetyInspectionForm({ area: "小班教室", items: [] }), null);
	assert.equal(
		validateSafetyInspectionForm({
			area: "小班教室",
			items: [itemFixture({ result: "不合格", hazard: "积水" })],
		}),
		null,
	);
	assert.match(validateSafetyInspectionForm({ area: " ", items: [] }) ?? "", /巡查区域必填/);
	assert.match(
		validateSafetyInspectionForm({ area: "小班教室", items: [itemFixture({ item_code: "UNKNOWN" })] }) ?? "",
		/不在常量表内/,
	);
	assert.match(
		validateSafetyInspectionForm({ area: "小班教室", items: [itemFixture({ result: "" })] }) ?? "",
		/缺少检查结果/,
	);
	assert.match(
		validateSafetyInspectionForm({ area: "小班教室", items: [itemFixture({ result: "待定" })] }) ?? "",
		/结果必须是/,
	);
});

test("追加整改项校验与规范化（隐患为空则省略）", () => {
	assert.equal(validateAppendItemForm({ itemCode: "FLOOR_SLIP", result: "不合格" }), null);
	assert.match(validateAppendItemForm({ itemCode: "", result: "合格" }) ?? "", /请选择检查项/);
	assert.match(validateAppendItemForm({ itemCode: "UNKNOWN", result: "合格" }) ?? "", /不在常量表内/);
	assert.match(validateAppendItemForm({ itemCode: "FLOOR_SLIP", result: "" }) ?? "", /请选择检查结果/);
	assert.match(validateAppendItemForm({ itemCode: "FLOOR_SLIP", result: "待定" }) ?? "", /结果必须是/);

	assert.deepEqual(
		normalizeInspectionItem({ itemCode: " FLOOR_SLIP ", result: " 不合格 ", hazard: "  地面有积水 " }),
		{ item_code: "FLOOR_SLIP", result: "不合格", hazard: "地面有积水" },
	);
	assert.deepEqual(
		normalizeInspectionItem({ itemCode: "FLOOR_SLIP", result: "合格", hazard: "   " }),
		{ item_code: "FLOOR_SLIP", result: "合格" },
	);
});

test("可选时刻：空串返回 undefined（由服务端取当前时刻）", () => {
	assert.equal(optionalInstant(""), undefined);
	assert.equal(optionalInstant("   "), undefined);
	assert.equal(optionalInstant("2026-10-06T10:00"), "2026-10-06T10:00");
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("保育员工作台菜单项：儿保域可见 + nursing:document 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source
		.split("\n")
		.find((text) => text.includes('"/dashboard/childcare-workbench"'));
	assert.ok(line, "Sidebar 必须包含保育员工作台菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/, "054 C3：工作台必须在儿保域可见（R9）");
	assert.match(
		line,
		/requiredPermissions:\s*\["nursing:document"\]/,
		"054 F1：写入口必须按 nursing:document 显隐（前端隐藏不替代 403）",
	);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(
		new URL("../src/pages/dashboard/childcare-workbench.astro", import.meta.url),
		"utf8",
	);
	assert.match(page, /ChildcareWorkbenchPage/, "Astro 页面必须挂载保育员工作台组件");
});

test("共享客户端提供 C 链端点且路径与后端一致", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	for (const path of [
		"/nursing/v1/childcare-activity-logs",
		"/nursing/v1/childcare-behavior-observations",
		"/nursing/v1/childcare-class-schedules",
		"/nursing/v1/childcare-safety-inspections",
	]) {
		assert.ok(client.includes(path), `aceso.ts 必须包含 C 链端点 ${path}`);
	}
	assert.match(client, /export function appendChildcareSafetyInspectionItem/, "必须有追加整改项客户端函数");
	assert.match(client, /export function closeChildcareSafetyInspection/, "必须有关闭隐患客户端函数");
	assert.match(client, /export function updateChildcareClassScheduleStatus/, "必须有日程状态流转客户端函数");
});
