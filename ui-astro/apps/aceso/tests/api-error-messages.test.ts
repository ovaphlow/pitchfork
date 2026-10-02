/**
 * 权限闸门错误文案单元测试（`packages/shared/src/problem-message.ts`）。
 *
 * Runner：内置 `node:test` + `node:assert/strict`，与 `age.test.ts` 等同一口径。
 * 运行（在 `ui-astro/` 下）：`pnpm test:unit:aceso`
 *
 * 为什么逐字锁定：403/503 是 037 权限闸门新增的两条失败路径，409 是 041 新增的删除冲突
 * 映射（部门/角色仍被引用）；用户看到的必须是"要哪个权限、去找谁"、"未被登出，稍后重试"
 * 或"该部门下还有几名成员"，退化成英文错误码就是回归。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { problemMessage } from "../../../packages/shared/src/problem-message.ts";

test("403 带 required_permission → 中文提示含权限码", () => {
	const message = problemMessage(
		403,
		{ error: "forbidden", required_permission: "nursing:execute" },
		"",
	);
	assert.match(message, /权限不足/);
	assert.match(message, /nursing:execute/);
	assert.match(message, /角色管理/);
});

test("403 无 required_permission → 保持服务端错误码，不编造权限名", () => {
	assert.equal(problemMessage(403, { error: "forbidden" }, ""), "forbidden");
});

test("503 权限服务不可用 → 明确「本次操作未执行」且不提示登出", () => {
	const message = problemMessage(503, { error: "permission service unavailable" }, "");
	assert.match(message, /角色权限服务暂时不可用/);
	assert.match(message, /本次操作未执行/);
	assert.doesNotMatch(message, /登录|重新登录/);
});

test("503 认证服务不可用 → 明确「未被登出」", () => {
	const message = problemMessage(503, { error: "identity service unavailable" }, "");
	assert.match(message, /认证服务暂时不可用/);
	assert.match(message, /未被登出/);
});

test("409 部门仍被成员引用 → 中文提示含成员数", () => {
	assert.equal(
		problemMessage(
			409,
			{ detail: "department is assigned to 1 subject(s); unassign before deleting" },
			"",
		),
		"该部门下还有 1 名成员，请先调整归属后再删除。",
	);
});

test("409 角色仍被分配 → 中文提示含用户数", () => {
	assert.equal(
		problemMessage(
			409,
			{ detail: "role is assigned to 3 subject(s); unassign before deleting" },
			"",
		),
		"该角色已分配给 3 个用户，请先取消分配后再删除。",
	);
});

test("409 计数为 0 也逐字替换", () => {
	assert.equal(
		problemMessage(
			409,
			{ detail: "department is assigned to 0 subject(s); unassign before deleting" },
			"",
		),
		"该部门下还有 0 名成员，请先调整归属后再删除。",
	);
	assert.equal(
		problemMessage(
			409,
			{ detail: "role is assigned to 0 subject(s); unassign before deleting" },
			"",
		),
		"该角色已分配给 0 个用户，请先取消分配后再删除。",
	);
});

test("409 未命中删除冲突句式 → 原样返回 detail，不吞错", () => {
	assert.equal(problemMessage(409, { detail: "department code already exists" }, ""), "department code already exists");
	assert.equal(
		problemMessage(409, { detail: "role is assigned to 1 subject(s)" }, ""),
		"role is assigned to 1 subject(s)",
	);
	assert.equal(
		problemMessage(409, { detail: "department is assigned to 1 subject(s); unassign before deleting!" }, ""),
		"department is assigned to 1 subject(s); unassign before deleting!",
	);
	assert.equal(problemMessage(409, { error: "conflict" }, ""), "conflict");
});

test("其它错误沿用 error/detail/title/正文/兜底", () => {
	assert.equal(problemMessage(400, { error: "invalid-request" }, ""), "invalid-request");
	assert.equal(problemMessage(400, { detail: "detail text" }, ""), "detail text");
	assert.equal(problemMessage(400, { title: "Bad Request" }, ""), "Bad Request");
	assert.equal(problemMessage(500, null, "plain text"), "plain text");
	assert.equal(problemMessage(500, null, ""), "请求失败 (500)");
});
