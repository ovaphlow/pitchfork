/**
 * 操作审计查询展示辅助 —— **纯函数**（无 React、无副作用、不发请求）。
 *
 * 分工（054 F3）：
 * - **只读**：审计写入由 apps/aceso 的旁路中间件负责（`AuditMiddleware`，只记
 *   POST/PUT/PATCH/DELETE、**不读请求体**、失败只 warn 不阻断、403 尝试也入账）；
 *   本模块与其调用方**只有查询**，不提供任何写入口。
 * - **全系统唯一受门禁的读路由**：`GET /healthcare/v1/audit-logs` 受 `access:manage`
 *   （054 §11.2 Q8）；无权限时服务端 403 文案必须原样透出。
 * - 常量镜像（受审计方法与状态码分组）与后端逐字比对，`audit-log.test.ts` 有漂移守卫。
 */

import type { AuditLogEntry } from "@pitchfork/shared/aceso";

// ─── 常量镜像（与后端 AuditMiddleware / AuditLogService 逐字比对）───────────

/** 受审计的写方法（后端 `AuditMiddleware.AUDITED_METHODS`）。 */
export const AUDITED_METHODS = ["POST", "PUT", "PATCH", "DELETE"] as const;
export type AuditedMethod = (typeof AUDITED_METHODS)[number];

/** 方法徽章配色：POST=info、PUT/PATCH=warning、DELETE=danger。 */
export function methodVariant(
  method: string | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch ((method ?? "").trim().toUpperCase()) {
    case "POST":
      return "info";
    case "PUT":
    case "PATCH":
      return "warning";
    case "DELETE":
      return "danger";
    default:
      return "default";
  }
}

/** 状态码分组：`2xx` / `3xx` / `4xx` / `5xx`；缺失返回 `未知`。 */
export function statusCodeClass(code: number | null | undefined): "2xx" | "3xx" | "4xx" | "5xx" | "未知" {
  if (code === null || code === undefined || !Number.isFinite(code)) return "未知";
  if (code >= 200 && code < 300) return "2xx";
  if (code >= 300 && code < 400) return "3xx";
  if (code >= 400 && code < 500) return "4xx";
  if (code >= 500 && code < 600) return "5xx";
  return "未知";
}

/** 状态码徽章配色：2xx=success、3xx=info、4xx=warning（含 403 越权尝试）、5xx=danger。 */
export function statusCodeVariant(
  code: number | null | undefined,
): "default" | "success" | "warning" | "danger" | "info" {
  switch (statusCodeClass(code)) {
    case "2xx":
      return "success";
    case "3xx":
      return "info";
    case "4xx":
      return "warning";
    case "5xx":
      return "danger";
    default:
      return "default";
  }
}

/** 状态码展示：缺失显示 `-`。 */
export function formatStatusCode(code: number | null | undefined): string {
  return code === null || code === undefined || !Number.isFinite(code) ? "-" : String(code);
}

/** 操作摘要：`POST /crate-api/... → 201`。 */
export function auditSummary(entry: Pick<AuditLogEntry, "method" | "path" | "status_code">): string {
  return `${entry.method} ${entry.path} → ${formatStatusCode(entry.status_code)}`;
}

/** 主体展示：缺失显示 `未知主体`。 */
export function actorLabel(actor: string | null | undefined): string {
  const value = (actor ?? "").trim();
  return value === "" ? "未知主体" : value;
}

/** 资源展示：`:id` 路径参数缺失显示 `-`；过长截断显示尾 8 位。 */
export function resourceLabel(resourceId: string | null | undefined): string {
  const value = (resourceId ?? "").trim();
  if (value === "") return "-";
  return value.length > 12 ? `…${value.slice(-8)}` : value;
}

/**
 * 结果分布计数（只读统计）：`{ok, clientError, serverError, other, total}`。
 * 403（越权尝试）计入 `clientError`——审计的意义正在于能看到它。
 */
export function auditOutcomeCount(entries: readonly AuditLogEntry[]): {
  ok: number;
  clientError: number;
  serverError: number;
  other: number;
  total: number;
} {
  const summary = { ok: 0, clientError: 0, serverError: 0, other: 0, total: entries.length };
  for (const entry of entries) {
    switch (statusCodeClass(entry.status_code)) {
      case "2xx":
        summary.ok += 1;
        break;
      case "4xx":
        summary.clientError += 1;
        break;
      case "5xx":
        summary.serverError += 1;
        break;
      default:
        summary.other += 1;
    }
  }
  return summary;
}

/** 审计只读声明（页面必须常驻展示）。 */
export const AUDIT_READONLY_NOTICE =
  "审计台账为只读：本页不提供任何新增、修改或删除入口；记录由服务端旁路中间件在写请求响应后落库（只记元数据，不记请求体）。";

/** 门禁说明：审计查询是全系统唯一受门禁的读路由。 */
export const AUDIT_GATE_NOTICE =
  "审计查询受「access:manage」权限控制（全系统唯一受门禁的读路由）；无权限时服务端返回 403，其文案原样展示。";

// ─── 筛选校验（与后端同口径）───────────────────────────────────────────────

/**
 * 时间筛选校验：`from`/`to` 必须是 ISO-8601 **带偏移**的日期时间（后端 `OffsetDateTime.parse`），
 * 且 `from` 不得晚于 `to`（否则服务端 400）。
 */
export function validateInstantRange(from: string, to: string): string | null {
  const fromText = from.trim();
  const toText = to.trim();
  const fromValue = fromText === "" ? null : Date.parse(fromText);
  const toValue = toText === "" ? null : Date.parse(toText);
  if (fromText !== "" && Number.isNaN(fromValue)) return "起始时间必须是 ISO-8601 日期时间";
  if (toText !== "" && Number.isNaN(toValue)) return "结束时间必须是 ISO-8601 日期时间";
  if (fromValue !== null && toValue !== null && fromValue > toValue) return "起始时间不得晚于结束时间";
  return null;
}

/** 方法筛选校验：空串合法（不过滤）；否则必须在受审计方法白名单内。 */
export function validateMethodFilter(method: string): string | null {
  const value = method.trim().toUpperCase();
  if (value === "") return null;
  if (!AUDITED_METHODS.includes(value as AuditedMethod)) {
    return `方法必须是 ${AUDITED_METHODS.join(" / ")}（中间件只审计这四类写方法）`;
  }
  return null;
}
