/**
 * 054 F3：操作审计查询展示辅助（`src/lib/auditLog.ts`）单元测试。
 *
 * Runner：内置 `node:test` + `node:assert/strict`（与 `childcare.test.ts` 同口径）。
 * 运行（在 `ui-astro/` 下）：`bun run test:unit:aceso`
 *
 * 覆盖口径：
 *   - 受审计方法白名单与后端 `AuditMiddleware.AUDITED_METHODS` **逐字一致**（源码漂移守卫）；
 *   - **只读**：审计页不得调用任何写客户端函数、不得发出 POST/PUT/PATCH/DELETE 请求；
 *   - 筛选校验与后端 `AuditLogService.list` 同口径（ISO-8601 带偏移时间、`from` 不得晚于 `to`）；
 *   - 菜单接线：操作审计受 `access:manage` 显隐控制（F1/R9，全系统唯一受门禁读路由）。
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import type { AuditLogEntry } from "@pitchfork/shared/aceso";

import {
	AUDITED_METHODS,
	AUDIT_GATE_NOTICE,
	AUDIT_READONLY_NOTICE,
	actorLabel,
	auditOutcomeCount,
	auditSummary,
	formatStatusCode,
	methodVariant,
	resourceLabel,
	statusCodeClass,
	statusCodeVariant,
	validateInstantRange,
	validateMethodFilter,
} from "../src/lib/auditLog.ts";

const BACKEND_DIR =
	"../../../../service-vertx-kotlin/apps/aceso/src/main/kotlin/com/ovaphlow/crate/aceso/";

function readBackend(file: string): string {
	return readFileSync(new URL(`${BACKEND_DIR}${file}`, import.meta.url), "utf8");
}

function entryFixture(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
	return {
		id: "01JTESTAUDIT00000000000",
		actor: "01JTESTACTOR0000000000",
		method: "POST",
		path: "/crate-api/dining/v1/meal-intake-records",
		status_code: 201,
		resource_id: null,
		occurred_at: "2026-10-07T09:00:00+08:00",
		metadata: { platform_admin: false },
		...overrides,
	};
}

// ─── 常量漂移守卫（直接读后端源码）─────────────────────────────────────────

test("受审计方法白名单与后端 AuditMiddleware.AUDITED_METHODS 逐字一致", () => {
	const source = readBackend("AuditMiddleware.kt");
	const matched = /val AUDITED_METHODS: Set<HttpMethod> = setOf\(([\s\S]*?)\)/.exec(source);
	assert.ok(matched, "后端必须声明 AUDITED_METHODS");
	const methods = [...(matched[1] ?? "").matchAll(/HttpMethod\.([A-Z]+)/g)].map((match) => match[1]);
	assert.deepEqual(methods, [...AUDITED_METHODS]);
	assert.deepEqual([...AUDITED_METHODS], ["POST", "PUT", "PATCH", "DELETE"]);
});

test("审计中间件只读元数据、不读请求体（源码事实核对）", () => {
	const source = readBackend("AuditMiddleware.kt");
	assert.match(source, /pathParam\("id"\)/, "resource_id 取路径参数 id");
	assert.doesNotMatch(source, /body\(\)|getBody|getBodyAsString/, "中间件不得读取请求体");
});

// ─── 只读纪律（页面不得有任何写入口）───────────────────────────────────────

test("审计页只读：只调用 listAuditLogs，不调用任何写客户端函数", () => {
	const page = readFileSync(new URL("../src/components/AuditLogPage.tsx", import.meta.url), "utf8");
	assert.match(page, /listAuditLogs\(/, "审计页必须调用查询接口");
	assert.doesNotMatch(page, /method:\s*"(POST|PUT|PATCH|DELETE)"/, "审计页不得发出写请求");
	assert.doesNotMatch(
		page,
		/\b(create|update|delete|append|register|acknowledge|close)[A-Z]\w*\(/,
		"审计页不得调用任何写客户端函数",
	);
	// 页面只从共享客户端引入查询函数（按行锚定，避免跨 import 语句误匹配）
	const importBlock = /^import\s*\{([^}]*)\}\s*from\s*"@pitchfork\/shared\/aceso"/m.exec(page);
	assert.ok(importBlock, "必须从共享客户端导入");
	const imported = (importBlock[1] ?? "")
		.split(",")
		.map((item) => item.trim())
		.filter((item) => item !== "" && !item.startsWith("type "));
	assert.deepEqual(imported, ["listAuditLogs"], "审计页只允许导入查询函数");
});

test("共享客户端的审计查询是只读 GET（无 method 选项、无请求体）", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	const start = client.indexOf("export function listAuditLogs");
	assert.ok(start > 0, "aceso.ts 必须导出 listAuditLogs");
	const body = client.slice(start, start + 1000);
	assert.match(body, /\/healthcare\/v1\/audit-logs/);
	assert.doesNotMatch(body, /method:\s*"/, "审计查询不得带写方法");
	assert.doesNotMatch(body, /body:/, "审计查询不得带请求体");
});

test("只读与门禁说明必须常驻页面", () => {
	const page = readFileSync(new URL("../src/components/AuditLogPage.tsx", import.meta.url), "utf8");
	assert.match(page, /AUDIT_READONLY_NOTICE/);
	assert.match(page, /AUDIT_GATE_NOTICE/);
	assert.match(AUDIT_READONLY_NOTICE, /只读/);
	assert.match(AUDIT_READONLY_NOTICE, /不提供任何新增、修改或删除入口/);
	assert.match(AUDIT_READONLY_NOTICE, /不记请求体/);
	assert.match(AUDIT_GATE_NOTICE, /access:manage/);
	assert.match(AUDIT_GATE_NOTICE, /唯一受门禁的读路由/);
});

// ─── 展示派生 ──────────────────────────────────────────────────────────────

test("方法徽章配色：POST=info、PUT/PATCH=warning、DELETE=danger", () => {
	assert.equal(methodVariant("POST"), "info");
	assert.equal(methodVariant("PUT"), "warning");
	assert.equal(methodVariant("PATCH"), "warning");
	assert.equal(methodVariant("DELETE"), "danger");
	assert.equal(methodVariant("GET"), "default");
	assert.equal(methodVariant(null), "default");
});

test("状态码分组与配色：2xx=success、4xx=warning（含 403 越权尝试）、5xx=danger", () => {
	assert.equal(statusCodeClass(201), "2xx");
	assert.equal(statusCodeClass(302), "3xx");
	assert.equal(statusCodeClass(403), "4xx");
	assert.equal(statusCodeClass(500), "5xx");
	assert.equal(statusCodeClass(null), "未知");
	assert.equal(statusCodeVariant(201), "success");
	assert.equal(statusCodeVariant(403), "warning");
	assert.equal(statusCodeVariant(503), "danger");
	assert.equal(statusCodeVariant(null), "default");
	assert.equal(formatStatusCode(201), "201");
	assert.equal(formatStatusCode(null), "-");
});

test("操作摘要、主体与资源展示（缺失有兜底，不猜测）", () => {
	assert.equal(auditSummary(entryFixture()), "POST /crate-api/dining/v1/meal-intake-records → 201");
	assert.equal(auditSummary(entryFixture({ status_code: null })), "POST /crate-api/dining/v1/meal-intake-records → -");
	assert.equal(actorLabel("01JTESTACTOR"), "01JTESTACTOR");
	assert.equal(actorLabel(null), "未知主体");
	assert.equal(actorLabel("  "), "未知主体");
	assert.equal(resourceLabel(null), "-");
	assert.equal(resourceLabel("01JTESTID"), "01JTESTID");
	assert.equal(resourceLabel("01JTESTRESOURCE000000000"), "…00000000", "过长资源 ID 截断显示尾部");
});

test("结果分布计数：403 计入 clientError（审计价值所在），未知状态不丢弃", () => {
	assert.deepEqual(auditOutcomeCount([]), { ok: 0, clientError: 0, serverError: 0, other: 0, total: 0 });
	assert.deepEqual(
		auditOutcomeCount([
			entryFixture({ status_code: 201 }),
			entryFixture({ status_code: 200 }),
			entryFixture({ status_code: 403 }),
			entryFixture({ status_code: 400 }),
			entryFixture({ status_code: 503 }),
			entryFixture({ status_code: null }),
		]),
		{ ok: 2, clientError: 2, serverError: 1, other: 1, total: 6 },
	);
});

// ─── 筛选校验（与后端同口径）───────────────────────────────────────────────

test("时间筛选校验：必须可解析且 from 不得晚于 to", () => {
	assert.equal(validateInstantRange("", ""), null);
	assert.equal(validateInstantRange("2026-10-07T09:00:00+08:00", "2026-10-07T10:00:00+08:00"), null);
	assert.match(validateInstantRange("not-a-date", "") ?? "", /起始时间必须是 ISO-8601 日期时间/);
	assert.match(validateInstantRange("", "nope") ?? "", /结束时间必须是 ISO-8601 日期时间/);
	assert.match(
		validateInstantRange("2026-10-07T10:00:00+08:00", "2026-10-07T09:00:00+08:00") ?? "",
		/起始时间不得晚于结束时间/,
	);
});

test("方法筛选校验：空串合法，其余必须在受审计方法白名单内", () => {
	assert.equal(validateMethodFilter(""), null);
	assert.equal(validateMethodFilter("post"), null, "小写合法（服务端会转大写）");
	assert.equal(validateMethodFilter("DELETE"), null);
	assert.match(validateMethodFilter("GET") ?? "", /方法必须是 POST \/ PUT \/ PATCH \/ DELETE/);
});

// ─── 菜单接线（F1/R9 + 路由可解析）─────────────────────────────────────────

test("操作审计菜单项：养老可见 + access:manage 显隐控制", () => {
	const source = readFileSync(new URL("../src/components/Sidebar.tsx", import.meta.url), "utf8");
	const line = source.split("\n").find((text) => text.includes('"/eldercare/audit-log"'));
	assert.ok(line, "Sidebar 必须包含操作审计菜单项");
	assert.match(line, /requiredPermissions:\s*\["access:manage"\]/);
});

test("菜单路径有对应 Astro 页面（静态构建下不会 404）", () => {
	const page = readFileSync(new URL("../src/pages/eldercare/audit-log.astro", import.meta.url), "utf8");
	assert.match(page, /AuditLogPage/);
});

test("共享客户端提供审计查询端点", () => {
	const client = readFileSync(new URL("../../../packages/shared/src/aceso.ts", import.meta.url), "utf8");
	assert.match(client, /\/healthcare\/v1\/audit-logs/);
	assert.match(client, /export function listAuditLogs/);
});
