/**
 * 054 F1：前端权限感知（菜单显隐）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`。
 * 运行（在 `ui-astro/` 下）：`bun run test:unit:aceso`
 *
 * 锁定的验收口径（计划 §8.6 / §7 F1 + 架构师 BL3）：
 *   - 无权限码账号看不到声明 `requiredPermissions` 的菜单入口；
 *   - 有对应权限码的账号可见（多个码为「任一命中」）；
 *   - **平台管理员全量可见**：后端 `SubjectPermissionGate` 对 `platform_admin` 短路放行，
 *     平台管理员不持有权限码，前端必须同口径（否则管理员菜单会全空，与后端语义相反）；
 *   - 未声明门槛的既有菜单项在**任何**权限码集合下都不受影响（机制不误伤现有菜单）；
 *   - 权限未知（读取中 / 读取失败）时不放行受限入口——与后端 fail-closed 同口径；
 *   - 前端隐藏只是体验层：**后端仍是唯一强制点**，本模块不做鉴权判定。
 *
 * 覆盖方式说明：`node:test` 无法加载 `.tsx`（没有 JSX 变换），所以菜单显隐由
 * `permissions.ts` 的纯函数 + `Sidebar.tsx` 源码接线断言共同覆盖；组件自身的类型
 * 与编译由 `bun run --filter @pitchfork/aceso build`（`astro check` 硬门槛）把关。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
	filterVisibleMenuEntries,
	hasRequiredPermissions,
	isMenuEntryVisible,
	normalizePermissionCodes,
	useSubjectPermissions,
	type PermissionAwareEntry,
} from "../src/lib/permissions.ts";

/** 与 `Sidebar.tsx` 的 `MenuChild` 同形的测试夹具（只保留显隐相关字段） */
interface MenuEntry extends PermissionAwareEntry {
	label: string;
	path: string;
}

const MENU: MenuEntry[] = [
	{ label: "长者管理", path: "/eldercare/elders" },
	{ label: "膳食营养", path: "/eldercare/dining", requiredPermissions: ["dining:manage"] },
	{ label: "系统日志", path: "/eldercare/audit-logs", requiredPermissions: ["access:manage"] },
	{
		label: "营养周报",
		path: "/eldercare/nutrition-weekly",
		requiredPermissions: ["dining:manage", "access:manage"],
	},
];

const paths = (entries: MenuEntry[]): string[] => entries.map((entry) => entry.path);

test("无权限码账号看不到受限菜单入口", () => {
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, [])), ["/eldercare/elders"]);
});

test("有对应权限码账号可见受限菜单入口", () => {
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, ["dining:manage"])), [
		"/eldercare/elders",
		"/eldercare/dining",
		"/eldercare/nutrition-weekly",
	]);
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, ["access:manage"])), [
		"/eldercare/elders",
		"/eldercare/audit-logs",
		"/eldercare/nutrition-weekly",
	]);
});

test("声明多个权限码时任一命中即可见", () => {
	assert.equal(hasRequiredPermissions(["dining:manage", "access:manage"], ["access:manage"]), true);
	assert.equal(hasRequiredPermissions(["dining:manage", "access:manage"], ["dining:manage"]), true);
	assert.equal(hasRequiredPermissions(["dining:manage", "access:manage"], ["billing:write"]), false);
});

test("权限未知（读取中 / 读取失败）时不放行受限入口", () => {
	// codes === null：加载中或 Nexus 读取失败，一律按无权限处理（fail-closed）
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, null)), ["/eldercare/elders"]);
	assert.equal(hasRequiredPermissions(["dining:manage"], null), false);
});

test("平台管理员全量可见（后端短路放行，前端同口径）", () => {
	// 平台管理员不持有权限码（codes=[]），若只按权限码判定菜单会全空——必须靠 platformAdmin 短路
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, [], true)), paths(MENU));
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, null, true)), paths(MENU));
	assert.equal(hasRequiredPermissions(["dining:manage"], [], true), true);
	assert.equal(hasRequiredPermissions(["dining:manage"], null, true), true);
	assert.equal(isMenuEntryVisible({ requiredPermissions: ["access:manage"] }, [], true), true);
});

test("非管理员的无码账号仍看不到受限入口（平台管理员短路不扩散）", () => {
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, [], false)), ["/eldercare/elders"]);
	assert.deepEqual(paths(filterVisibleMenuEntries(MENU, [], undefined)), ["/eldercare/elders"]);
});

test("未声明门槛的既有菜单项在任何权限码集合下都可见（机制不误伤现有菜单）", () => {
	const noRequirement: MenuEntry = { label: "床位管理", path: "/eldercare/beds" };
	for (const codes of [null, [], ["dining:manage"], ["access:manage"], ["unknown:code"]]) {
		assert.equal(isMenuEntryVisible(noRequirement, codes), true, `codes=${JSON.stringify(codes)}`);
	}
	// 空数组与缺省同义：不设前端门槛
	assert.equal(hasRequiredPermissions([], []), true);
	assert.equal(hasRequiredPermissions(undefined, null), true);
});

test("过滤是纯函数：不修改入参、保持项的相对顺序", () => {
	const snapshot = [...MENU];
	const visible = filterVisibleMenuEntries(MENU, ["access:manage"]);
	assert.notEqual(visible, MENU, "必须返回新数组");
	assert.deepEqual(MENU, snapshot, "不得修改入参");
	assert.deepEqual(paths(visible), ["/eldercare/elders", "/eldercare/audit-logs", "/eldercare/nutrition-weekly"]);
});

test("权限码规范化：去空白、丢空串、按首次出现去重且顺序稳定", () => {
	assert.deepEqual(normalizePermissionCodes([" dining:manage ", "", "dining:manage", "access:manage"]), [
		"dining:manage",
		"access:manage",
	]);
	assert.deepEqual(normalizePermissionCodes(null), []);
	assert.deepEqual(normalizePermissionCodes(undefined), []);
	// 服务端返回带空白的码时仍能命中
	assert.equal(hasRequiredPermissions(["dining:manage"], [" dining:manage "]), true);
});

test("权限感知钩子导出存在（不在单测中调用：需浏览器会话与 Nexus）", () => {
	// 调用会经 `@pitchfork/shared/aceso` 发真实请求，属浏览器/E2E 范围；
	// 这里只锁定导出契约，防止 Sidebar 的接线失去依赖。
	assert.equal(typeof useSubjectPermissions, "function");
});

// —— Sidebar 接线断言（源码级）——

const SIDEBAR_SOURCE = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");

test("Sidebar 菜单模型支持 requiredPermissions 并接入权限过滤", () => {
	assert.match(
		SIDEBAR_SOURCE,
		/requiredPermissions\?: string\[\]/,
		"MenuChild 必须支持 requiredPermissions（054 F1 机制）",
	);
	assert.match(
		SIDEBAR_SOURCE,
		/useSubjectPermissions\(\)/,
		"Sidebar 必须订阅本人权限码",
	);
	assert.match(
		SIDEBAR_SOURCE,
		/filterVisibleMenuEntries\(/,
		"Sidebar 渲染路径必须调用权限过滤",
	);
	assert.match(
		SIDEBAR_SOURCE,
		/platformAdmin/,
		"Sidebar 必须把平台管理员标记传给权限过滤（BL3：管理员短路放行）",
	);
});

test("056：菜单已无域过滤（养老单产品）", () => {
	// 只看代码，不看注释（文件头注释会说明「056 之前有什么」，那是历史说明）
	const code = SIDEBAR_SOURCE.split("\n")
		.filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
		.join("\n");
	assert.doesNotMatch(code, /useDomain|readDomain|DOMAIN_ENTITY/, "056 后菜单不得再依赖域模块");
	assert.doesNotMatch(code, /domains:\s*\[/, "056 后菜单项不得再带 domains 数组");
	assert.doesNotMatch(code, /domainLabels/, "056 后菜单项不得再带 domainLabels");
	// 所有菜单路径必须落在 /eldercare/*（儿童保健未来走独立 /childcare/*）
	const paths = [...SIDEBAR_SOURCE.matchAll(/path:\s*"([^"]+)"/g)].map((m) => m[1]);
	assert.ok(paths.length > 0, "必须能解析出菜单路径");
	assert.ok(paths.every((p) => p.startsWith("/eldercare/")), `菜单路径必须全在 /eldercare/*：${paths}`);
});
