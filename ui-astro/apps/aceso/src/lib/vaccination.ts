/**
 * 预防接种展示辅助 —— 纯函数（无 React、无副作用）。
 *
 * 分工（052 §8 P3）：
 *  - **排程与状态判定的权威在后端**（`ChildVaccinationService.VACCINATION_SCHEDULE` /
 *    `statusOf`，随 `GET /child-vaccination-status/:patient_id` 返回）。本文件**不重复**排程表，
 *    只做展示层：状态徽标、排序、分组、汇总文案与「下一剂」提示；
 *  - 这样避免前后端各存一份免疫规划排程而悄悄漂移（052 裁定 3：只含国家免疫规划疫苗）。
 */

import type { ChildVaccinationScheduleRow, ChildVaccinationStatus } from "@pitchfork/shared/aceso";

/** 状态展示顺序：先看问题项，再看正常项。 */
export const VACCINATION_STATUS_ORDER: ChildVaccinationStatus[] = [
	"逾期",
	"临期",
	"待接种",
	"暂缓",
	"拒种",
	"已接种",
];

export type VaccinationBadgeVariant = "default" | "success" | "warning" | "danger" | "info";

/** 状态 → 徽标样式（逾期红、临期黄、已接种绿）。 */
export const VACCINATION_STATUS_VARIANTS: Record<ChildVaccinationStatus, VaccinationBadgeVariant> = {
	已接种: "success",
	待接种: "info",
	临期: "warning",
	逾期: "danger",
	暂缓: "warning",
	拒种: "default",
};

/** 状态 → 给护士的一句话解释。 */
export const VACCINATION_STATUS_HINTS: Record<ChildVaccinationStatus, string> = {
	已接种: "已完成接种",
	待接种: "已到龄或即将到龄，请安排接种",
	临期: "距推荐接种月龄不足 30 天，可预约",
	逾期: "超过推荐月龄 30 天仍未接种，请尽快补种并提醒家长",
	暂缓: "有医学暂缓原因，需复评后补种",
	拒种: "家长拒种，已留痕（不得视为已接种）",
};

/** 状态解释；未知状态返回空串（不猜）。 */
export function statusHint(status: string): string {
	return VACCINATION_STATUS_HINTS[status as ChildVaccinationStatus] ?? "";
}

/** 状态排序权重（越小越靠前）。 */
export function statusWeight(status: ChildVaccinationStatus): number {
	const index = VACCINATION_STATUS_ORDER.indexOf(status);
	return index === -1 ? VACCINATION_STATUS_ORDER.length : index;
}

/** 按状态排序（同状态保持排程原顺序，稳定排序）。 */
export function sortByStatus(rows: ChildVaccinationScheduleRow[]): ChildVaccinationScheduleRow[] {
	return rows
		.map((row, index) => ({ row, index }))
		.sort((a, b) => {
			const diff = statusWeight(a.row.status) - statusWeight(b.row.status);
			return diff !== 0 ? diff : a.index - b.index;
		})
		.map((entry) => entry.row);
}

/** 逾期项（后端已按排程判定，前端只筛）。 */
export function overdueRows(rows: ChildVaccinationScheduleRow[]): ChildVaccinationScheduleRow[] {
	return rows.filter((row) => row.status === "逾期");
}

/** 临期项。 */
export function dueSoonRows(rows: ChildVaccinationScheduleRow[]): ChildVaccinationScheduleRow[] {
	return rows.filter((row) => row.status === "临期");
}

/** 尚未完成的剂次（不含已接种/拒种）。 */
export function pendingRows(rows: ChildVaccinationScheduleRow[]): ChildVaccinationScheduleRow[] {
	return rows.filter((row) => row.status !== "已接种" && row.status !== "拒种");
}

/**
 * 「下一剂」提示：按排程顺序找第一个未完成剂次。
 *
 * 排程顺序即后端返回顺序（疫苗 × 剂次，按推荐月龄升序），因此这里不重新排序。
 */
export function nextDose(rows: ChildVaccinationScheduleRow[]): ChildVaccinationScheduleRow | null {
	return rows.find((row) => row.status === "逾期" || row.status === "临期" || row.status === "待接种") ?? null;
}

/** 一株疫苗（同 code 的所有剂次）。 */
export interface VaccineGroup {
	vaccine_code: string;
	vaccine_name: string;
	rows: ChildVaccinationScheduleRow[];
	/** 该疫苗是否还有未完成剂次 */
	hasPending: boolean;
}

/** 按疫苗分组（保持首次出现顺序 = 排程顺序）。 */
export function groupScheduleByVaccine(rows: ChildVaccinationScheduleRow[]): VaccineGroup[] {
	const groups = new Map<string, VaccineGroup>();
	rows.forEach((row) => {
		const existing = groups.get(row.vaccine_code);
		if (existing) {
			existing.rows.push(row);
			existing.hasPending = existing.hasPending || (row.status !== "已接种" && row.status !== "拒种");
			return;
		}
		groups.set(row.vaccine_code, {
			vaccine_code: row.vaccine_code,
			vaccine_name: row.vaccine_name,
			rows: [row],
			hasPending: row.status !== "已接种" && row.status !== "拒种",
		});
	});
	return Array.from(groups.values());
}

export interface VaccinationMeta {
	total: number;
	administered: number;
	pending: number;
	due_soon: number;
	overdue: number;
	deferred: number;
	refused: number;
}

/** 汇总文案：`已接种 12/20 · 逾期 2 · 临期 1`（无异常时只显示完成度）。 */
export function summarizeVaccinationMeta(meta: VaccinationMeta): string {
	const parts = [`已接种 ${meta.administered}/${meta.total}`];
	if (meta.overdue > 0) parts.push(`逾期 ${meta.overdue}`);
	if (meta.due_soon > 0) parts.push(`临期 ${meta.due_soon}`);
	if (meta.deferred > 0) parts.push(`暂缓 ${meta.deferred}`);
	if (meta.refused > 0) parts.push(`拒种 ${meta.refused}`);
	return parts.join(" · ");
}

/** 提醒动作是否可用（只有逾期项才生成台账）。 */
export function canRemind(meta: VaccinationMeta): boolean {
	return meta.overdue > 0;
}
