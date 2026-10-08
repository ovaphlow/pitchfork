/**
 * 预防接种展示辅助（`src/lib/vaccination.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   node --test apps/aceso/tests/vaccination.test.ts
 *
 * 锁定：状态排序（问题项优先）、分组不丢剂次、汇总文案、提醒可用性判定。
 * 状态本身由后端 `statusOf` 判定（见 libs/healthcare `ChildVaccinationScheduleTest`）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	VACCINATION_STATUS_HINTS,
	VACCINATION_STATUS_ORDER,
	canRemind,
	dueSoonRows,
	groupScheduleByVaccine,
	nextDose,
	overdueRows,
	pendingRows,
	sortByStatus,
	statusWeight,
	summarizeVaccinationMeta,
} from "../src/lib/vaccination.ts";
import type { ChildVaccinationScheduleRow, ChildVaccinationStatus } from "@pitchfork/shared/aceso";

function row(
	vaccine_code: string,
	vaccine_name: string,
	dose_no: number,
	status: ChildVaccinationStatus,
	recommended_month = dose_no * 2,
): ChildVaccinationScheduleRow {
	return {
		vaccine_code,
		vaccine_name,
		dose_no,
		recommended_month,
		due_date: "2026-01-01",
		status,
		record_id: status === "已接种" ? `rec-${vaccine_code}-${dose_no}` : null,
		administered_at: status === "已接种" ? "2026-01-02" : null,
		facility: null,
		batch_no: null,
		note: null,
	};
}

const schedule: ChildVaccinationScheduleRow[] = [
	row("HEPB", "乙肝疫苗", 1, "已接种", 0),
	row("HEPB", "乙肝疫苗", 2, "已接种", 1),
	row("HEPB", "乙肝疫苗", 3, "逾期", 6),
	row("BCG", "卡介苗", 1, "已接种", 0),
	row("POLIO", "脊灰疫苗", 1, "临期", 2),
	row("DTAP", "百白破疫苗", 1, "待接种", 3),
	row("MMR", "麻腮风疫苗", 1, "拒种", 8),
	row("JE", "乙脑减毒活疫苗", 1, "暂缓", 8),
];

test("状态顺序把问题项排在前面", () => {
	assert.deepEqual(VACCINATION_STATUS_ORDER, ["逾期", "临期", "待接种", "暂缓", "拒种", "已接种"]);
	assert.ok(statusWeight("逾期") < statusWeight("已接种"));
	assert.equal(statusWeight("不存在" as ChildVaccinationStatus), VACCINATION_STATUS_ORDER.length);
});

test("排序稳定：同状态保持排程原顺序", () => {
	const sorted = sortByStatus(schedule);
	assert.equal(sorted[0]?.status, "逾期");
	assert.deepEqual(
		sorted.map((item) => item.status),
		["逾期", "临期", "待接种", "暂缓", "拒种", "已接种", "已接种", "已接种"],
	);
	// 三个「已接种」的相对顺序与原数组一致（HEPB1, HEPB2, BCG1）
	assert.deepEqual(
		sorted.filter((item) => item.status === "已接种").map((item) => `${item.vaccine_code}${item.dose_no}`),
		["HEPB1", "HEPB2", "BCG1"],
	);
});

test("逾期 / 临期 / 未完成筛选", () => {
	assert.deepEqual(overdueRows(schedule).map((item) => item.vaccine_code), ["HEPB"]);
	assert.deepEqual(dueSoonRows(schedule).map((item) => item.vaccine_code), ["POLIO"]);
	assert.deepEqual(
		pendingRows(schedule).map((item) => item.status),
		["逾期", "临期", "待接种", "暂缓"],
		"拒种不算未完成（已留痕，不再催种）",
	);
});

test("下一剂取排程顺序里第一个未完成剂次", () => {
	assert.equal(nextDose(schedule)?.vaccine_code, "HEPB");
	assert.equal(nextDose(schedule)?.dose_no, 3);
	assert.equal(nextDose(schedule.filter((item) => item.status === "已接种")), null);
});

test("按疫苗分组不丢剂次且标记未完成", () => {
	const groups = groupScheduleByVaccine(schedule);
	assert.deepEqual(
		groups.map((group) => group.vaccine_code),
		["HEPB", "BCG", "POLIO", "DTAP", "MMR", "JE"],
		"分组保持排程首次出现顺序",
	);
	const hepb = groups[0];
	assert.ok(hepb);
	assert.equal(hepb.rows.length, 3);
	assert.equal(hepb.hasPending, true);
	const mmr = groups.find((group) => group.vaccine_code === "MMR");
	assert.ok(mmr);
	assert.equal(mmr.hasPending, false, "拒种视为已收口，不再标记未完成");
	assert.equal(
		groups.reduce((total, group) => total + group.rows.length, 0),
		schedule.length,
		"分组后剂次总数必须等于输入",
	);
});

test("汇总文案与提醒可用性", () => {
	assert.equal(
		summarizeVaccinationMeta({
			total: 20,
			administered: 12,
			pending: 4,
			due_soon: 1,
			overdue: 2,
			deferred: 1,
			refused: 0,
		}),
		"已接种 12/20 · 逾期 2 · 临期 1 · 暂缓 1",
	);
	assert.equal(
		summarizeVaccinationMeta({
			total: 20,
			administered: 20,
			pending: 0,
			due_soon: 0,
			overdue: 0,
			deferred: 0,
			refused: 0,
		}),
		"已接种 20/20",
	);
	assert.equal(
		canRemind({ total: 20, administered: 18, pending: 2, due_soon: 2, overdue: 0, deferred: 0, refused: 0 }),
		false,
		"只有临期项时不能生成台账（临期不是异常）",
	);
	assert.equal(
		canRemind({ total: 20, administered: 17, pending: 3, due_soon: 1, overdue: 2, deferred: 0, refused: 0 }),
		true,
	);
});

test("每种状态都有可读解释", () => {
	VACCINATION_STATUS_ORDER.forEach((status) => {
		assert.ok(VACCINATION_STATUS_HINTS[status]?.length > 0, `${status} 必须有解释文案`);
	});
});
