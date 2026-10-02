/**
 * 月龄 / 年龄派生 —— 纯函数，无 React、无副作用。
 *
 * 口径与 `lib/datetime.ts` 完全一致：业务时区固定 `Asia/Shanghai`，结果与主机时区
 * 无关。用「日历年 / 月 / 日」做日历差，**绝不**用毫秒差除以固定月长（28/30/31 天
 * 会让 2 月出生的儿童在跨月时算错）。
 *
 * 输出口径：
 *  - 空 / 非法 → `"-"`
 *  - 出生当天 → `"0 天"`；不足 1 个月 → `"N 天"`
 *  - ≥1 月且 <24 月 → `"N 月"`
 *  - ≥24 月 → `"N 岁 M 月"`
 */

import { formatDate } from "./datetime.ts";

const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const MS_PER_DAY = 86_400_000;

interface CalendarDate {
	year: number;
	/** 1-12 */
	month: number;
	/** 1-31 */
	day: number;
}

/** 严格解析 "YYYY-MM-DD"（拒绝 2026-02-30 这类不存在的日期）。 */
function parseDateOnly(value: string): CalendarDate | null {
	const match = DATE_ONLY_RE.exec(value.trim());
	if (match === null) return null;
	const year = Number(match[1]);
	const month = Number(match[2]);
	const day = Number(match[3]);
	const probe = new Date(0);
	probe.setUTCHours(0, 0, 0, 0);
	// setUTCFullYear（不是 Date.UTC）避免 0-99 年被重映射到 19xx。
	probe.setUTCFullYear(year, month - 1, day);
	if (
		probe.getUTCFullYear() !== year ||
		probe.getUTCMonth() !== month - 1 ||
		probe.getUTCDate() !== day
	) {
		return null;
	}
	return { year, month, day };
}

/** 两个纯日期（UTC 零点）之间的整天差，与主机时区无关。 */
function daysBetween(from: CalendarDate, to: CalendarDate): number {
	const fromMs = Date.UTC(from.year, from.month - 1, from.day);
	const toMs = Date.UTC(to.year, to.month - 1, to.day);
	return Math.round((toMs - fromMs) / MS_PER_DAY);
}

/** 业务时区「今天」的日历日期；`formatDate` 已保证 instant → Asia/Shanghai。 */
function businessToday(now: Date): CalendarDate | null {
	if (Number.isNaN(now.getTime())) return null;
	return parseDateOnly(formatDate(now.toISOString()));
}

/**
 * 把出生日期渲染为月龄文本。
 *
 * @param birthDate 出生日期：`"YYYY-MM-DD"` 纯日期（后端 DATE 列）或可解析为 instant
 *   的字符串；空 / 非法返回 `"-"`。
 * @param now 参考时刻，缺省 `new Date()`；测试传入固定值以锁定边界。
 */
export function formatAge(birthDate: string | null | undefined, now: Date = new Date()): string {
	const birth = parseDateOnly(formatDate(birthDate));
	const today = businessToday(now);
	if (birth === null || today === null) return "-";

	// 日历差：先按月对齐，再看「日」是否够一个月（不足则退一个月）。
	let months = (today.year - birth.year) * 12 + (today.month - birth.month);
	if (today.day < birth.day) months -= 1;

	if (months <= 0) {
		// 出生当天为 0 天；未来日期（脏数据）夹到 0 天，不出现负数。
		const days = daysBetween(birth, today);
		return `${days > 0 ? days : 0} 天`;
	}
	if (months < 24) return `${months} 月`;

	return `${Math.floor(months / 12)} 岁 ${months % 12} 月`;
}
