/**
 * 健康指导展示辅助 —— 纯函数（无 React、无副作用）。
 *
 * 分工（052 §8 P5）：**模板内容的权威在后端**（`ChildGuidanceService.GUIDANCE_TEMPLATES`，
 * 随 `GET /child-guidance-templates` 返回）；本文件只做月龄段换算、主题图标与分组展示，
 * 不重复模板正文（避免前后端两份指导话术漂移）。
 */

import type { ChildGuidanceTemplate } from "@pitchfork/shared/aceso";

/** 月龄段（与后端 `ChildGuidanceService.ageBands` 同口径）。 */
export const GUIDANCE_AGE_BANDS = ["0-6月", "7-12月", "13-24月", "25-60月"] as const;

export type GuidanceAgeBand = (typeof GUIDANCE_AGE_BANDS)[number];

/** 月龄 → 月龄段；边界与后端逐字一致（7/13/25 月进下一段）。 */
export function ageBandOf(ageMonths: number): GuidanceAgeBand {
	if (ageMonths < 7) return "0-6月";
	if (ageMonths < 13) return "7-12月";
	if (ageMonths < 25) return "13-24月";
	return "25-60月";
}

/** 主题图标（纯展示）。 */
export const GUIDANCE_TOPIC_ICONS: Record<string, string> = {
	喂养: "🍼",
	睡眠: "🌙",
	口腔: "🦷",
	安全: "🛡️",
	发育促进: "🧩",
	疾病预防: "💉",
};

/** 主题图标；未知主题回退通用图标（不隐藏模板）。 */
export function topicIcon(topic: string | null | undefined): string {
	return GUIDANCE_TOPIC_ICONS[topic ?? ""] ?? "📋";
}

/** 按主题分组（保持模板返回顺序）。 */
export function groupTemplatesByTopic(templates: ChildGuidanceTemplate[]): Array<{
	topic: string;
	templates: ChildGuidanceTemplate[];
}> {
	const groups = new Map<string, ChildGuidanceTemplate[]>();
	templates.forEach((template) => {
		const key = template.topic || "其他";
		const bucket = groups.get(key);
		if (bucket) {
			bucket.push(template);
		} else {
			groups.set(key, [template]);
		}
	});
	return Array.from(groups.entries()).map(([topic, items]) => ({ topic, templates: items }));
}

/** 按模板编码取模板；未命中返回 null（护士可自定义内容）。 */
export function findTemplate(
	templates: ChildGuidanceTemplate[],
	code: string | null | undefined,
): ChildGuidanceTemplate | null {
	if (!code) return null;
	return templates.find((template) => template.template_code === code) ?? null;
}
