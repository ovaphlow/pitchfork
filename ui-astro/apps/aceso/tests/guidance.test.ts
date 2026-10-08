/**
 * 健康指导展示辅助（`src/lib/guidance.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。运行（在 `ui-astro/` 下）：
 *
 *   pnpm test:unit:aceso
 *   # 或单独：
 *   node --test apps/aceso/tests/guidance.test.ts
 *
 * 锁定月龄段边界与后端 `ChildGuidanceService.ageBandOf` 逐字一致（7/13/25 月进下一段）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	GUIDANCE_AGE_BANDS,
	ageBandOf,
	findTemplate,
	groupTemplatesByTopic,
	topicIcon,
} from "../src/lib/guidance.ts";
import type { ChildGuidanceTemplate } from "@pitchfork/shared/aceso";

function template(template_code: string, topic: string, age_band = "0-6月"): ChildGuidanceTemplate {
	return { template_code, topic, age_band, title: `${topic}指导`, content: `${topic}内容` };
}

test("月龄段边界与后端一致", () => {
	assert.deepEqual([...GUIDANCE_AGE_BANDS], ["0-6月", "7-12月", "13-24月", "25-60月"]);
	assert.equal(ageBandOf(0), "0-6月");
	assert.equal(ageBandOf(6), "0-6月");
	assert.equal(ageBandOf(7), "7-12月");
	assert.equal(ageBandOf(12), "7-12月");
	assert.equal(ageBandOf(13), "13-24月");
	assert.equal(ageBandOf(24), "13-24月");
	assert.equal(ageBandOf(25), "25-60月");
	assert.equal(ageBandOf(60), "25-60月");
	assert.equal(ageBandOf(120), "25-60月", "超龄不抛错，回退最大段");
});

test("主题图标与未知回退", () => {
	assert.equal(topicIcon("喂养"), "🍼");
	assert.equal(topicIcon("口腔"), "🦷");
	assert.equal(topicIcon("未知主题"), "📋");
	assert.equal(topicIcon(null), "📋");
});

test("按主题分组保持模板顺序", () => {
	const templates = [
		template("A", "喂养"),
		template("B", "睡眠"),
		template("C", "喂养"),
		template("D", "疾病预防"),
	];
	const groups = groupTemplatesByTopic(templates);
	assert.deepEqual(groups.map((group) => group.topic), ["喂养", "睡眠", "疾病预防"]);
	assert.deepEqual(groups[0]?.templates.map((item) => item.template_code), ["A", "C"]);
	assert.equal(
		groups.reduce((total, group) => total + group.templates.length, 0),
		templates.length,
		"分组后模板总数必须等于输入",
	);
});

test("无主题模板归入其他而非丢弃", () => {
	const groups = groupTemplatesByTopic([template("A", ""), template("B", "喂养")]);
	assert.deepEqual(groups.map((group) => group.topic), ["其他", "喂养"]);
});

test("按编码取模板", () => {
	const templates = [template("A", "喂养"), template("B", "睡眠")];
	assert.equal(findTemplate(templates, "B")?.topic, "睡眠");
	assert.equal(findTemplate(templates, "X"), null);
	assert.equal(findTemplate(templates, null), null);
	assert.equal(findTemplate(templates, ""), null);
});
