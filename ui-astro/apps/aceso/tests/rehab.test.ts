/**
 * 054 B5：康复师工作台展示/表单辅助（`src/lib/rehab.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `nutrition.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 覆盖口径：
 *   - 周期状态机**逐字镜像后端**（`ChildRehabPeriodService.TRANSITIONS`）；
 *   - 里程碑四态是**读时派生结果**，前端只排序/计数/配色（问题项排前）；
 *   - 进度 `progress_ratio` **不封顶**：文案不截断，仅进度条宽度截断；
 *   - 手册签收**单向**，事实源是 `acknowledged_at`；
 *   - 菜单接线：康复师工作台在儿保域可见、受 `rehab:execute` 显隐控制（F1/R9），
 *     菜单路径有对应 Astro 页面（静态构建下不会 404）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type {
	ChildRehabHandout,
	ChildRehabMilestoneStatus,
	ChildRehabMilestoneStatusItem,
	ChildRehabProgress,
} from "@pitchfork/shared/aceso";

import {
	HANDOUT_CHANNEL_SUGGESTIONS,
	MILESTONE_STATES,
	PERIOD_STATUSES,
	addPlanItem,
	allowedPeriodTargets,
	canAcknowledgeHandout,
	canTransitionPeriod,
	emptyPlanItem,
	formatPercent,
	handoutStatusVariant,
	isIsoDate,
	isPeriodTerminal,
	milestoneAchievedLabel,
	milestoneExpectedLabel,
	milestoneHeadline,
	milestoneStateRank,
	milestoneStateVariant,
	milestoneSummary,
	normalizePlanItems,
	parseRatioInput,
	periodStatusLabel,
	periodStatusVariant,
	progressBarPercent,
	progressHeadline,
	progressNotice,
	removePlanItem,
	sortMilestoneItems,
	updatePlanItem,
	validateHandoutForm,
	validateMilestoneForm,
	validatePeriodForm,
	validatePlanForm,
	validateSessionForm,
} from "../src/lib/rehab.ts";

function milestoneItem(
	overrides: Partial<ChildRehabMilestoneStatusItem> = {},
): ChildRehabMilestoneStatusItem {
	return {
		milestone_code: "SIT_INDEPENDENT",
		milestone_name: "独坐",
		expected_age_months: 9,
		achieved_at: null,
		state: "未达",
		...overrides,
	};
}

function milestoneStatusFixture(
	items: ChildRehabMilestoneStatusItem[],
): ChildRehabMilestoneStatus {
	return { patient_id: "01JTESTCHILD0000000000000", age_months: 12, as_of: "2026-10-06", items };
}

function progressFixture(overrides: Partial<ChildRehabProgress> = {}): ChildRehabProgress {
	return {
		plan_id: "01JTESTPLAN00000000000000",
		session_count: 5,
		completed_days: 3,
		planned_days: 10,
		avg_completion_ratio: 0.8,
		progress_ratio: 0.3,
		last_session_date: "2026-10-06",
		...overrides,
	};
}

function handoutFixture(overrides: Partial<ChildRehabHandout> = {}): ChildRehabHandout {
	return {
		id: "01JTESTHANDOUT00000000000",
		patient_id: "01JTESTCHILD0000000000000",
		template_code: "HOME_SIT",
		template_name: "家庭独坐训练指导",
		content: "靠坐 → 扶坐 → 独坐循序渐进",
		channel: "现场",
		delivered_at: "2026-10-06T10:00:00+08:00",
		acknowledged_at: null,
		acknowledged_by: null,
		status: "未签收",
		operator: "康复师甲",
		metadata: null,
		created_at: "2026-10-06T10:00:00+08:00",
		updated_at: "2026-10-06T10:00:00+08:00",
		...overrides,
	};
}

// ─── 周期状态机（镜像后端）─────────────────────────────────────────────────

test("周期状态白名单与后端逐字一致（英文 code，不新增枚举）", () => {
	assert.deepEqual([...PERIOD_STATUSES], ["ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED"]);
	assert.deepEqual(
		PERIOD_STATUSES.map(periodStatusLabel),
		["进行中", "已暂停", "已完成", "已取消"],
	);
	assert.equal(periodStatusLabel("未知"), "未知", "未知状态原样返回，不猜测");
	assert.equal(periodStatusLabel(null), "-");
});

test("周期状态机：ACTIVE ⇄ SUSPENDED，二者 → COMPLETED/CANCELLED，终态不可流转", () => {
	assert.deepEqual(allowedPeriodTargets("ACTIVE"), ["SUSPENDED", "COMPLETED", "CANCELLED"]);
	assert.deepEqual(allowedPeriodTargets("SUSPENDED"), ["ACTIVE", "COMPLETED", "CANCELLED"]);
	assert.equal(canTransitionPeriod("ACTIVE", "SUSPENDED"), true);
	assert.equal(canTransitionPeriod("SUSPENDED", "ACTIVE"), true);
	assert.equal(canTransitionPeriod("COMPLETED", "ACTIVE"), false);
	assert.equal(canTransitionPeriod("CANCELLED", "COMPLETED"), false);
	assert.deepEqual(allowedPeriodTargets("COMPLETED"), []);
	assert.deepEqual(allowedPeriodTargets("未知"), []);
	assert.equal(canTransitionPeriod("ACTIVE", ""), false);
	assert.equal(canTransitionPeriod(null, "ACTIVE"), false);
});

test("周期终态判定与状态徽章配色", () => {
	assert.equal(isPeriodTerminal("COMPLETED"), true);
	assert.equal(isPeriodTerminal("CANCELLED"), true);
	assert.equal(isPeriodTerminal("ACTIVE"), false);
	assert.equal(periodStatusVariant("ACTIVE"), "success");
	assert.equal(periodStatusVariant("SUSPENDED"), "warning");
	assert.equal(periodStatusVariant("COMPLETED"), "info");
	assert.equal(periodStatusVariant("CANCELLED"), "default");
});

// ─── 里程碑四态（读时派生结果的展示派生）───────────────────────────────────

test("四态白名单与徽章配色", () => {
	assert.deepEqual([...MILESTONE_STATES], ["未达", "临近", "已达成", "逾期"]);
	assert.equal(milestoneStateVariant("逾期"), "danger");
	assert.equal(milestoneStateVariant("临近"), "warning");
	assert.equal(milestoneStateVariant("已达成"), "success");
	assert.equal(milestoneStateVariant("未达"), "default");
	assert.equal(milestoneStateVariant(null), "info");
});

test("四态排序：问题项在前（逾期 → 临近 → 未达 → 已达成），同态按期望月龄升序", () => {
	const items = [
		milestoneItem({ milestone_code: "WALK", milestone_name: "独立行走", expected_age_months: 18, state: "未达" }),
		milestoneItem({ milestone_code: "SIT", milestone_name: "独坐", expected_age_months: 9, state: "已达成", achieved_at: "2026-08-01" }),
		milestoneItem({ milestone_code: "CRAWL", milestone_name: "匍匐爬行", expected_age_months: 10, state: "逾期" }),
		milestoneItem({ milestone_code: "STAND", milestone_name: "扶站", expected_age_months: 12, state: "临近" }),
	];

	const sorted = sortMilestoneItems(items);
	assert.deepEqual(
		sorted.map((item) => item.milestone_code),
		["CRAWL", "STAND", "WALK", "SIT"],
	);
	assert.equal(items[0]?.milestone_code, "WALK", "排序不得修改入参");
	assert.equal(milestoneStateRank("逾期"), 0);
	assert.equal(milestoneStateRank("已达成"), 3);
	assert.equal(milestoneStateRank("未知"), 99, "未知状态排最后但不丢弃");
});

test("四态计数与摘要文案", () => {
	const items = [
		milestoneItem({ milestone_code: "A", state: "逾期" }),
		milestoneItem({ milestone_code: "B", state: "临近" }),
		milestoneItem({ milestone_code: "C", state: "临近" }),
		milestoneItem({ milestone_code: "D", state: "已达成" }),
		milestoneItem({ milestone_code: "E", state: "未达" }),
	];
	assert.deepEqual(milestoneSummary(items), { 未达: 1, 临近: 2, 已达成: 1, 逾期: 1 });
	assert.equal(
		milestoneHeadline(milestoneStatusFixture(items)),
		"12 月龄 · 截至 2026-10-06 · 逾期 1 / 临近 2 / 未达 1 / 已达成 1",
	);
	assert.deepEqual(milestoneSummary([]), { 未达: 0, 临近: 0, 已达成: 0, 逾期: 0 });
});

test("里程碑期望月龄与达成情况文案", () => {
	assert.equal(milestoneExpectedLabel(milestoneItem({ expected_age_months: 9 })), "期望 9 月龄");
	assert.equal(milestoneExpectedLabel(milestoneItem({ expected_age_months: null })), "无期望月龄");
	assert.equal(milestoneAchievedLabel(milestoneItem({ achieved_at: "2026-09-01" })), "已达成 2026-09-01");
	assert.equal(milestoneAchievedLabel(milestoneItem({ achieved_at: null })), "未达成");
});

// ─── 进度（不封顶）─────────────────────────────────────────────────────────

test("百分比展示不封顶：120% 原样展示，非法值显示 -", () => {
	assert.equal(formatPercent(0.3), "30%");
	assert.equal(formatPercent(1), "100%");
	assert.equal(formatPercent(1.2), "120%");
	assert.equal(formatPercent(0), "0%");
	assert.equal(formatPercent(null), "-");
	assert.equal(formatPercent(Number.NaN), "-");
});

test("进度条宽度做 0–100 截断（仅展示，不改变完成率文案）", () => {
	assert.equal(progressBarPercent(0.3), 30);
	assert.equal(progressBarPercent(1), 100);
	assert.equal(progressBarPercent(1.2), 100);
	assert.equal(progressBarPercent(0), 0);
	assert.equal(progressBarPercent(null), 0);
});

test("进度摘要：按完成天数/计划天数展示；计划缺起止日期时明确说明", () => {
	assert.equal(progressHeadline(progressFixture()), "已完成 3/10 天 · 完成率 30%");
	assert.equal(
		progressHeadline(progressFixture({ planned_days: null })),
		"已完成 3 天 · 计划未设起止日期，无法计算完成率",
	);
	assert.equal(
		progressHeadline(progressFixture({ planned_days: 0, completed_days: 0 })),
		"已完成 0 天 · 计划未设起止日期，无法计算完成率",
	);
});

test("进度口径说明：打卡次数、平均完成度、最近打卡；超 100% 时提示不封顶", () => {
	const notice = progressNotice(progressFixture());
	assert.match(notice, /打卡 5 次/);
	assert.match(notice, /平均完成度 80%/);
	assert.match(notice, /最近打卡 2026-10-06/);
	assert.doesNotMatch(notice, /不封顶/);

	const over = progressNotice(progressFixture({ progress_ratio: 1.5, completed_days: 15 }));
	assert.match(over, /完成率不封顶/);
});

// ─── 手册分发与签收（单向）─────────────────────────────────────────────────

test("签收事实源是 acknowledged_at：非空即已签收，不再可签", () => {
	assert.equal(canAcknowledgeHandout(handoutFixture()), true);
	assert.equal(
		canAcknowledgeHandout(
			handoutFixture({ acknowledged_at: "2026-10-06T11:00:00+08:00", status: "已签收" }),
		),
		false,
	);
	// status 是派生字段：即便与 acknowledged_at 不一致，也以 acknowledged_at 为准
	assert.equal(
		canAcknowledgeHandout(handoutFixture({ acknowledged_at: null, status: "已签收" })),
		true,
	);
	assert.equal(handoutStatusVariant("已签收"), "success");
	assert.equal(handoutStatusVariant("未签收"), "warning");
	assert.deepEqual([...HANDOUT_CHANNEL_SUGGESTIONS], ["现场", "微信", "纸质"]);
});

// ─── 表单校验（与后端同口径）───────────────────────────────────────────────

test("ISO 日期校验：格式与真实存在性", () => {
	assert.equal(isIsoDate("2026-10-06"), true);
	assert.equal(isIsoDate("2026-02-30"), false, "不存在的日期必须判非法");
	assert.equal(isIsoDate("2026-13-01"), false);
	assert.equal(isIsoDate("2026/10/06"), false);
	assert.equal(isIsoDate(""), false);
	assert.equal(isIsoDate(null), false);
});

test("周期表单校验：儿童必选、开始日期必填合法、结束不得早于开始", () => {
	assert.equal(validatePeriodForm({ patientId: "p1", startDate: "2026-10-06", endDate: "" }), null);
	assert.equal(
		validatePeriodForm({ patientId: "p1", startDate: "2026-10-06", endDate: "2026-10-20" }),
		null,
	);
	assert.match(validatePeriodForm({ patientId: "", startDate: "2026-10-06", endDate: "" }) ?? "", /请选择儿童/);
	assert.match(validatePeriodForm({ patientId: "p1", startDate: "", endDate: "" }) ?? "", /开始日期必填/);
	assert.match(
		validatePeriodForm({ patientId: "p1", startDate: "2026-10-06", endDate: "2026-10-05" }) ?? "",
		/结束日期不得早于开始日期/,
	);
});

test("计划表单校验：周期必选、计划名必填、至少一条明细", () => {
	assert.equal(
		validatePlanForm({ periodId: "pe1", planName: "计划", items: [{ action: "抬头" }] }),
		null,
	);
	assert.match(validatePlanForm({ periodId: "", planName: "计划", items: [{ action: "抬头" }] }) ?? "", /康复周期/);
	assert.match(validatePlanForm({ periodId: "pe1", planName: "  ", items: [{ action: "抬头" }] }) ?? "", /计划名称必填/);
	assert.match(validatePlanForm({ periodId: "pe1", planName: "计划", items: [] }) ?? "", /至少填写一条/);
	assert.match(
		validatePlanForm({ periodId: "pe1", planName: "计划", items: [{ action: "   " }] }) ?? "",
		/至少填写一条/,
	);
});

test("打卡表单校验：计划必选、日期合法、完成度 (0,1]", () => {
	assert.equal(
		validateSessionForm({ planId: "pl1", sessionDate: "2026-10-06", completionRatio: 1 }),
		null,
	);
	assert.equal(
		validateSessionForm({ planId: "pl1", sessionDate: "2026-10-06", completionRatio: 0.5 }),
		null,
	);
	assert.match(
		validateSessionForm({ planId: "", sessionDate: "2026-10-06", completionRatio: 1 }) ?? "",
		/请先选择训练计划/,
	);
	assert.match(
		validateSessionForm({ planId: "pl1", sessionDate: "", completionRatio: 1 }) ?? "",
		/训练日期必填/,
	);
	assert.match(
		validateSessionForm({ planId: "pl1", sessionDate: "2026-10-06", completionRatio: null }) ?? "",
		/完成度必须是/,
	);
	assert.match(
		validateSessionForm({ planId: "pl1", sessionDate: "2026-10-06", completionRatio: 0 }) ?? "",
		/大于 0 且不超过 1/,
	);
	assert.match(
		validateSessionForm({ planId: "pl1", sessionDate: "2026-10-06", completionRatio: 1.5 }) ?? "",
		/大于 0 且不超过 1/,
	);
});

test("里程碑与手册表单校验", () => {
	assert.equal(
		validateMilestoneForm({ patientId: "p1", milestoneCode: "SIT_INDEPENDENT", achievedAt: "2026-10-06" }),
		null,
	);
	assert.equal(
		validateMilestoneForm({ patientId: "p1", milestoneCode: "SIT_INDEPENDENT", achievedAt: "" }),
		null,
		"清空达成日期表示未达成，合法",
	);
	assert.match(
		validateMilestoneForm({ patientId: "p1", milestoneCode: "", achievedAt: "" }) ?? "",
		/请选择里程碑/,
	);
	assert.match(
		validateMilestoneForm({ patientId: "p1", milestoneCode: "SIT", achievedAt: "2026-02-30" }) ?? "",
		/达成日期必须是合法日期/,
	);

	assert.equal(validateHandoutForm({ patientId: "p1", templateCode: "HOME_SIT" }), null);
	assert.match(validateHandoutForm({ patientId: "p1", templateCode: "" }) ?? "", /请选择指导手册模板/);
});

test("完成度输入解析", () => {
	assert.equal(parseRatioInput("1"), 1);
	assert.equal(parseRatioInput(" 0.6 "), 0.6);
	assert.equal(parseRatioInput(""), null);
	assert.equal(parseRatioInput("abc"), null);
});

// ─── 明细编辑（纯函数）─────────────────────────────────────────────────────

test("明细编辑是纯函数：增删改都不修改入参", () => {
	const base = [emptyPlanItem()];
	const added = addPlanItem(base, { action: "抬头训练" });
	assert.equal(base.length, 1, "addPlanItem 不得修改入参");
	assert.equal(added.length, 2);

	const updated = updatePlanItem(added, 1, { duration_days: 14 });
	assert.equal(added[1]?.duration_days, undefined, "updatePlanItem 不得修改入参");
	assert.equal(updated[1]?.duration_days, 14);
	assert.equal(updated[1]?.action, "抬头训练");

	const removed = removePlanItem(updated, 0);
	assert.equal(updated.length, 2, "removePlanItem 不得修改入参");
	assert.deepEqual(removed.map((item) => item.action), ["抬头训练"]);

	assert.equal(updatePlanItem(base, 9, { action: "越界" })[0]?.action, "");
	assert.equal(removePlanItem(base, 9).length, 1);
});

test("明细规范化：去空白、丢弃空动作、持续天数只保留正整数", () => {
	assert.deepEqual(
		normalizePlanItems([
			{ action: " 抬头训练 ", frequency_name: " 每日 3 组 ", duration_days: 14, remark: " 注意 " },
			{ action: "   " },
			{ action: "翻身", duration_days: 0 },
			{ action: "独坐", duration_days: -3 },
			{ action: "爬行", duration_days: 2.5 },
		]),
		[
			{ action: "抬头训练", frequency_name: "每日 3 组", duration_days: 14, remark: "注意" },
			{ action: "翻身" },
			{ action: "独坐" },
			{ action: "爬行" },
		],
	);
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("康复师工作台菜单项：儿保域可见 + rehab:execute 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source.split("\n").find((text) => text.includes('"/dashboard/child-rehab"'));
	assert.ok(line, "Sidebar 必须包含康复师工作台菜单项");
	assert.match(line, /domains:\s*\["儿保"\]/, "054 B5：工作台必须在儿保域可见（R9）");
	assert.match(
		line,
		/requiredPermissions:\s*\["rehab:execute"\]/,
		"054 F1：写入口必须按 rehab:execute 显隐（前端隐藏不替代 403）",
	);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(
		new URL("../src/pages/dashboard/child-rehab.astro", import.meta.url),
		"utf8",
	);
	assert.match(page, /ChildRehabPage/, "Astro 页面必须挂载康复师工作台组件");
});

test("共享客户端提供 B 链端点且路径与后端一致", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	const expected = [
		"/nursing/v1/child-rehab-periods",
		"/nursing/v1/child-rehab-plans",
		"/nursing/v1/child-rehab-sessions",
		"/nursing/v1/child-rehab-milestones",
		"/nursing/v1/child-rehab-milestone-status/",
		"/nursing/v1/child-rehab-handout-templates",
		"/nursing/v1/child-rehab-handouts",
	];
	for (const path of expected) {
		assert.ok(client.includes(path), `aceso.ts 必须包含 B 链端点 ${path}`);
	}
	assert.match(client, /acknowledgeChildRehabHandout/, "签收必须有专用客户端函数（单向 PATCH）");
});
