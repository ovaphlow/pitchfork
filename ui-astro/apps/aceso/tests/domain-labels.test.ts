/**
 * 产品域命名单一事实来源（`src/lib/domain.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`，与 `datetime.test.ts` 同口径。
 * 运行（在 `ui-astro/` 下）：
 *
 *   node --test apps/aceso/tests/domain-labels.test.ts
 *
 * 锁定的验收口径（2026-10-01 QA「医疗模式下命名不一致」）：
 *   菜单名（Sidebar 用 PAGE_TITLE_DOMAIN_LABELS）与页面内容名（EldersPage 用
 *   DOMAIN_ENTITY）必须逐域完全相同 —— 医疗：居民档案、养老：长者档案、
 *   儿保：儿童健康档案。任何一侧被单独改写，本测试立刻失败。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
	DOMAIN_ADMISSION_PHRASE,
	DOMAIN_ENTITY,
	PAGE_TITLE_DOMAIN_LABELS,
	displayLabel,
	type Domain,
} from "../src/lib/domain.ts";

const DOMAINS: Domain[] = ["医疗", "养老", "儿保"];

test("居民档案页：菜单标题与页面档案名逐域一致", () => {
	const page = PAGE_TITLE_DOMAIN_LABELS["/dashboard/elders"];
	for (const domain of DOMAINS) {
		assert.equal(
			displayLabel(page.base, page.labels, domain),
			DOMAIN_ENTITY[domain].archive,
			`${domain}：菜单名必须与页面档案名同源`,
		);
	}
});

test("居民档案页三域命名：医疗=居民档案 / 养老=长者档案 / 儿保=儿童健康档案", () => {
	const page = PAGE_TITLE_DOMAIN_LABELS["/dashboard/elders"];
	assert.equal(displayLabel(page.base, page.labels, "医疗"), "居民档案");
	assert.equal(displayLabel(page.base, page.labels, "养老"), "长者档案");
	assert.equal(displayLabel(page.base, page.labels, "儿保"), "儿童健康档案");
});

test("主体名词逐域：居民 / 长者 / 儿童", () => {
	assert.deepEqual(
		DOMAINS.map((domain) => DOMAIN_ENTITY[domain].person),
		["居民", "长者", "儿童"],
	);
});

test("域无覆盖名时回退基准名（床位管理等全域同名的页面）", () => {
	const beds = PAGE_TITLE_DOMAIN_LABELS["/dashboard/beds"];
	for (const domain of DOMAINS) {
		assert.equal(displayLabel(beds.base, beds.labels, domain), "床位管理");
	}
});

/**
 * 非养老域可见页面的「长者」只允许出现在注释里。
 *
 * 这些页面在医疗/儿保模式下同样会打开，文案必须走 DOMAIN_ENTITY（居民 / 长者 / 儿童）；
 * 一旦有人再写死「长者」，医疗模式下就会重现「菜单叫居民、页面叫长者」的分叉。
 * 养老专属页面（膳食营养、康复活动、慢病档案等）不在本清单内。
 */
const NON_ELDERLY_VISIBLE_COMPONENTS = [
	"EldersPage.tsx",
	"AdmissionsPage.tsx",
	"FollowupPage.tsx",
	"NursingPage.tsx",
	"PharmacyPage.tsx",
	"InventoryPage.tsx",
	"CheckupPage.tsx",
	"HealthMonitorPage.tsx",
	"BedsPage.tsx",
	"DashboardPage.tsx",
	// 041 Q4：两项补进医疗域后同样在医疗模式打开，文案不得再写死「长者」
	"OrdersPage.tsx",
	"OrdersCheckPage.tsx",
];

test("非养老域可见页面不再硬编码「长者」文案", () => {
	for (const file of NON_ELDERLY_VISIBLE_COMPONENTS) {
		const source = readFileSync(new URL(`../src/components/${file}`, import.meta.url), "utf8");
		const offenders = source
			.split("\n")
			.map((line, index) => ({ line: index + 1, text: line }))
			.filter(({ text }) => text.includes("长者"))
			.filter(({ text }) => !/^\s*(\/\/|\*|\/\*)/.test(text))
			.filter(({ text }) => !/\{\/\*.*\*\/\}/.test(text));
		assert.deepEqual(
			offenders,
			[],
			`${file} 仍有硬编码「长者」：${offenders.map((item) => `${item.line}: ${item.text.trim()}`).join(" | ")}`,
		);
	}
});

/**
 * 041 把「医生诊疗 / 医嘱核对」补进医疗域后，两页的空态文案必须随域取词：
 * 写死「养老入住」就会在医疗模式下显示「办理养老入住」。养老档位逐字不变。
 */
test("入院口径逐域：医疗/儿保=入院，养老保持「养老入住」", () => {
	assert.equal(DOMAIN_ADMISSION_PHRASE["养老"], "养老入住");
	assert.equal(DOMAIN_ADMISSION_PHRASE["医疗"], "入院");
	assert.equal(DOMAIN_ADMISSION_PHRASE["儿保"], "入院");
});

test("医疗域可见的医嘱两页不再硬编码「养老入住」", () => {
	for (const file of ["OrdersPage.tsx", "OrdersCheckPage.tsx"]) {
		const source = readFileSync(new URL(`../src/components/${file}`, import.meta.url), "utf8");
		const offenders = source
			.split("\n")
			.map((line, index) => ({ line: index + 1, text: line }))
			.filter(({ text }) => text.includes("养老入住"))
			.filter(({ text }) => !/^\s*(\/\/|\*|\/\*)/.test(text))
			.filter(({ text }) => !/\{\/\*.*\*\/\}/.test(text));
		assert.deepEqual(
			offenders,
			[],
			`${file} 仍有硬编码「养老入住」：${offenders.map((item) => `${item.line}: ${item.text.trim()}`).join(" | ")}`,
		);
	}
});
