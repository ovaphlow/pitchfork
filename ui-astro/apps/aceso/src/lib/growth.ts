/**
 * 儿保成长评价 —— 纯函数（无 React、无副作用）。
 *
 * 052 D3：百分位计算与曲线绘制在**前端纯函数**完成，参考数据（WHO Child Growth
 * Standards LMS，0–5 岁）为可版本化常量（`whoLms0to60.ts`）；后端只存原始测量与
 * 落库时的评价快照（`percentile_snapshot`），不复制参考数据。
 *
 * 口径：
 *  - LMS 法（Cole & Green 1992）：`z = ((X/M)^L − 1) / (L·S)`（L = 0 时取 `ln(X/M)/S`）；
 *  - 百分位 = Φ(z)（标准正态 CDF，用 Abramowitz–Stegun 7.1.26 近似，误差 < 1.5e-7）；
 *  - 月龄在 [0, 60] 之外不评价（052 裁定 2：0–5 岁 MVP），返回 null，由 UI 明示「超出曲线范围」；
 *  - 参考带固定 P3/P15/P50/P85/P97（z = ∓1.88079 / ∓1.03643 / 0），与验收口径 §9.2 一致。
 */

import {
	WHO_LMS_0_60,
	type GrowthMetric,
	type GrowthSex,
	type LmsRow,
} from "./whoLms0to60.ts";

export type { GrowthMetric, GrowthSex };

/** 参考带覆盖的最大月龄（0–5 岁 MVP）。 */
export const MAX_CURVE_MONTH = 60;

/** 参考带：百分位 → 对应 z 值（标准正态分位点，固定常量，避免运行时求逆）。 */
export const REFERENCE_PERCENTILES: ReadonlyArray<{ p: number; z: number }> = [
	{ p: 3, z: -1.88079 },
	{ p: 15, z: -1.03643 },
	{ p: 50, z: 0 },
	{ p: 85, z: 1.03643 },
	{ p: 97, z: 1.88079 },
];

/** 生长异常的百分位阈值（与后端 `ChildAlertService.GROWTH_LOW/HIGH_PERCENTILE` 同源）。 */
export const GROWTH_LOW_PERCENTILE = 3;
export const GROWTH_HIGH_PERCENTILE = 97;

/** 一次评价结果（落库快照的元素形状）。 */
export interface GrowthPercentile {
	/** 百分位（保留 1 位小数） */
	p: number;
	/** z 值（保留 2 位小数） */
	z: number;
}

/** 曲线上的一个参考点。 */
export interface GrowthCurvePoint {
	month: number;
	/** 百分位 → 参考值（cm / kg） */
	values: Record<string, number>;
}

/** `patients.gender` → WHO 表的二值性别；未知返回 null（不猜）。 */
export function sexFromGender(gender: string | null | undefined): GrowthSex | null {
	const value = gender?.trim();
	if (value === "男") return "male";
	if (value === "女") return "female";
	return null;
}

const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** 严格解析 `YYYY-MM-DD`（拒绝 2026-02-30 这类不存在的日期）。 */
function parseDateOnly(value: string | null | undefined): { year: number; month: number; day: number } | null {
	const match = DATE_ONLY_RE.exec((value ?? "").trim().slice(0, 10));
	if (match === null) return null;
	const year = Number(match[1]);
	const month = Number(match[2]);
	const day = Number(match[3]);
	const probe = new Date(0);
	probe.setUTCHours(0, 0, 0, 0);
	probe.setUTCFullYear(year, month - 1, day);
	if (probe.getUTCFullYear() !== year || probe.getUTCMonth() !== month - 1 || probe.getUTCDate() !== day) {
		return null;
	}
	return { year, month, day };
}

/**
 * 两个纯日期的**完整月龄**（不足整月不进位）。
 *
 * 与后端 `ChildHealth.ageMonths` 及 `lib/age.ts` 的 `formatAge` 同一日历差口径：
 * `2026-01-31 → 2026-02-28` 记 0 月。非法输入返回 null。
 */
export function ageMonthsBetween(birthDate: string | null | undefined, on: string | null | undefined): number | null {
	const birth = parseDateOnly(birthDate);
	const target = parseDateOnly(on);
	if (birth === null || target === null) return null;
	let months = (target.year - birth.year) * 12 + (target.month - birth.month);
	if (target.day < birth.day) months -= 1;
	return Math.max(0, months);
}

/** 绘图用月龄：后端序列给的是天龄，这里按平均月长换算（仅用于画点，不做评价）。 */
export function ageMonthsOfDays(ageDays: number): number {
	return ageDays / 30.4375;
}

/** 指标中文名。 */
export const METRIC_LABELS: Record<GrowthMetric, string> = {
	height: "身高",
	weight: "体重",
	head: "头围",
};

/** 指标单位。 */
export const METRIC_UNITS: Record<GrowthMetric, string> = {
	height: "cm",
	weight: "kg",
	head: "cm",
};

/** 是否落在曲线覆盖范围内（0–60 月）。 */
export function isCurveSupported(ageMonths: number): boolean {
	return Number.isFinite(ageMonths) && ageMonths >= 0 && ageMonths <= MAX_CURVE_MONTH;
}

/** Abramowitz–Stegun 7.1.26 误差函数近似（最大绝对误差 1.5e-7）。 */
function erf(x: number): number {
	const sign = x < 0 ? -1 : 1;
	const ax = Math.abs(x);
	const t = 1 / (1 + 0.3275911 * ax);
	const y =
		1 -
		(((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) *
			t *
			Math.exp(-ax * ax));
	return sign * y;
}

/** 标准正态分布函数 Φ(z)。 */
export function normalCdf(z: number): number {
	return 0.5 * (1 + erf(z / Math.SQRT2));
}

/**
 * 月龄处的 LMS（相邻整月线性插值；越界返回 null）。
 *
 * 插值而非取最近月，是为了让 0–1 月这段（生长最快）不出现台阶。
 */
export function lmsAt(sex: GrowthSex, metric: GrowthMetric, ageMonths: number): LmsRow | null {
	if (!isCurveSupported(ageMonths)) return null;
	const rows = WHO_LMS_0_60[sex][metric];
	const lower = Math.floor(ageMonths);
	const upper = Math.min(Math.ceil(ageMonths), MAX_CURVE_MONTH);
	const low = rows[lower];
	const high = rows[upper];
	if (!low || !high) return null;
	if (lower === upper) return low;
	const ratio = ageMonths - lower;
	const mix = (index: 1 | 2 | 3) => (low[index] as number) + (((high[index] as number) - (low[index] as number)) * ratio);
	return [ageMonths, mix(1), mix(2), mix(3)];
}

/** LMS → z 值（L = 0 时取对数形式）。 */
export function zScoreOf(value: number, row: LmsRow): number {
	const [, l, m, s] = row;
	if (!Number.isFinite(value) || value <= 0 || m <= 0 || s <= 0) return Number.NaN;
	if (Math.abs(l) < 1e-9) return Math.log(value / m) / s;
	return (Math.pow(value / m, l) - 1) / (l * s);
}

/** z 值 → 该性别/月龄/指标下的测量值（曲线参考带用）。 */
export function valueForZ(sex: GrowthSex, metric: GrowthMetric, ageMonths: number, z: number): number | null {
	const row = lmsAt(sex, metric, ageMonths);
	if (row === null) return null;
	const [, l, m, s] = row;
	if (Math.abs(l) < 1e-9) return m * Math.exp(s * z);
	const base = 1 + l * s * z;
	if (base <= 0) return null;
	return m * Math.pow(base, 1 / l);
}

/**
 * 评价一次测量：返回 `{p, z}`（p 保留 1 位小数、z 保留 2 位小数）。
 *
 * 超出 0–60 月、性别未知或数值非法时返回 null（**不猜、不外推**）。
 */
export function evaluateGrowth(
	value: number,
	sex: GrowthSex | null,
	metric: GrowthMetric,
	ageMonths: number,
): GrowthPercentile | null {
	if (sex === null) return null;
	const row = lmsAt(sex, metric, ageMonths);
	if (row === null) return null;
	const z = zScoreOf(value, row);
	if (!Number.isFinite(z)) return null;
	const p = normalCdf(z) * 100;
	return { p: Math.round(p * 10) / 10, z: Math.round(z * 100) / 100 };
}

/** 一次会话的评价快照（提交给后端 `percentile_snapshot`）。 */
export function evaluateGrowthSnapshot(
	sex: GrowthSex | null,
	ageMonths: number,
	values: Partial<Record<GrowthMetric, number | null | undefined>>,
): Partial<Record<GrowthMetric, GrowthPercentile>> {
	const snapshot: Partial<Record<GrowthMetric, GrowthPercentile>> = {};
	(Object.keys(METRIC_LABELS) as GrowthMetric[]).forEach((metric) => {
		const value = values[metric];
		if (value === null || value === undefined) return;
		const evaluated = evaluateGrowth(value, sex, metric, ageMonths);
		if (evaluated !== null) snapshot[metric] = evaluated;
	});
	return snapshot;
}

/**
 * 参考带曲线：`fromMonth`–`toMonth`（含）按月步进，每个点给出五个百分位的参考值。
 */
export function growthCurve(
	sex: GrowthSex,
	metric: GrowthMetric,
	fromMonth = 0,
	toMonth = MAX_CURVE_MONTH,
	stepMonths = 1,
): GrowthCurvePoint[] {
	const points: GrowthCurvePoint[] = [];
	for (let month = fromMonth; month <= toMonth; month += stepMonths) {
		const values: Record<string, number> = {};
		REFERENCE_PERCENTILES.forEach(({ p, z }) => {
			const value = valueForZ(sex, metric, month, z);
			if (value !== null) values[String(p)] = Math.round(value * 100) / 100;
		});
		points.push({ month, values });
	}
	return points;
}

/** 评价文本：`身高 P25`；超范围返回「超出曲线范围（0–5 岁）」；空值返回 "-"。 */
export function describePercentile(
	metric: GrowthMetric,
	evaluated: GrowthPercentile | null | undefined,
): string {
	if (evaluated === null || evaluated === undefined) return "超出曲线范围（0–5 岁）";
	return `${METRIC_LABELS[metric]} P${evaluated.p}`;
}

/** 是否落在 P3–P97 之外（与后端告警口径同源）。 */
export function isGrowthOutlier(evaluated: GrowthPercentile | null | undefined): boolean {
	if (evaluated === null || evaluated === undefined) return false;
	return evaluated.p < GROWTH_LOW_PERCENTILE || evaluated.p > GROWTH_HIGH_PERCENTILE;
}

/** 把「按指标存的快照」读回，供列表/告警展示。 */
export function snapshotOf(
	snapshot: Record<string, { p?: number; z?: number }> | null | undefined,
	metric: GrowthMetric,
): GrowthPercentile | null {
	const entry = snapshot?.[metric];
	if (!entry || typeof entry.p !== "number") return null;
	return { p: entry.p, z: typeof entry.z === "number" ? entry.z : 0 };
}
