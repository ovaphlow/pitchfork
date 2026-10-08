import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import { listAuditLogs, type AuditLogEntry } from "@pitchfork/shared/aceso";
import { formatDateTime, toOffsetDateTime } from "../lib/datetime";
import {
  AUDIT_GATE_NOTICE,
  AUDIT_READONLY_NOTICE,
  AUDITED_METHODS,
  actorLabel,
  auditOutcomeCount,
  auditSummary,
  formatStatusCode,
  methodVariant,
  resourceLabel,
  statusCodeVariant,
  validateInstantRange,
  validateMethodFilter,
} from "../lib/auditLog";

/**
 * 操作审计查询页（054 F3 / F 链前端第二步）。
 *
 * **只读**：本页只调用 `GET /healthcare/v1/audit-logs`，**不提供任何新增/修改/删除入口**；
 * 审计记录由服务端旁路中间件（`AuditMiddleware`）在写请求响应后落库，只记元数据、不记请求体。
 *
 * 门禁：审计查询是**全系统唯一受门禁的读路由**（`access:manage`，054 §11.2 Q8）。
 * 菜单项同样按 `access:manage` 显隐（F1 机制）；直接请求仍可能 403，服务端文案原样透出。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function AuditLogPage() {
  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [actor, setActor] = useState("");
  const [method, setMethod] = useState("");
  const [path, setPath] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [filterError, setFilterError] = useState("");

  const outcome = useMemo(() => auditOutcomeCount(entries), [entries]);
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const load = useCallback(async () => {
    const validation = validateMethodFilter(method) ?? validateInstantRange(from, to);
    if (validation) {
      setFilterError(validation);
      setEntries([]);
      setTotal(0);
      return;
    }
    setFilterError("");
    setLoading(true);
    try {
      const response = await listAuditLogs({
        actor: actor.trim() === "" ? undefined : actor.trim(),
        method: method.trim() === "" ? undefined : method.trim(),
        path: path.trim() === "" ? undefined : path.trim(),
        // datetime-local → ISO-8601 带业务时区偏移（后端要求 OffsetDateTime）
        from: from.trim() === "" ? undefined : toOffsetDateTime(from.trim()),
        to: to.trim() === "" ? undefined : toOffsetDateTime(to.trim()),
        limit: PAGE_SIZE,
        offset: (page - 1) * PAGE_SIZE,
      });
      setEntries(response.records);
      setTotal(response.meta.total);
      setError("");
    } catch (cause) {
      // 无 access:manage → 403；非法筛选 → 400：服务端文案原样透出
      setEntries([]);
      setTotal(0);
      setError(errorMessage(cause, "无法加载审计台账"));
    } finally {
      setLoading(false);
    }
  }, [actor, method, path, from, to, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const columns: Column<AuditLogEntry>[] = [
    { key: "occurred_at", header: "发生时间", render: (row) => formatDateTime(row.occurred_at) },
    { key: "actor", header: "操作主体", render: (row) => actorLabel(row.actor) },
    {
      key: "method",
      header: "方法",
      render: (row) => <Badge variant={methodVariant(row.method)}>{row.method}</Badge>,
    },
    {
      key: "path",
      header: "路径",
      render: (row) => <span className="text-xs text-fg-muted break-all">{row.path}</span>,
    },
    {
      key: "status_code",
      header: "状态码",
      render: (row) => <Badge variant={statusCodeVariant(row.status_code)}>{formatStatusCode(row.status_code)}</Badge>,
    },
    { key: "resource_id", header: "资源 ID", render: (row) => resourceLabel(row.resource_id) },
    {
      key: "summary",
      header: "操作摘要",
      render: (row) => <span className="text-xs text-fg-muted">{auditSummary(row)}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title="操作审计（F3，只读）">
        <div className="flex flex-col gap-2">
          <p className="text-sm text-fg-muted">{AUDIT_READONLY_NOTICE}</p>
          <p className="text-xs text-fg-dimmed">{AUDIT_GATE_NOTICE}</p>
          <p className="text-xs text-fg-dimmed">
            结果分布（当前页）：成功 {outcome.ok} / 客户端错误（含 403 越权尝试）{outcome.clientError} /
            服务端错误 {outcome.serverError} / 其它 {outcome.other}
          </p>
        </div>
      </Card>

      <Card
        title={`审计台账（${total}）`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {page} / {totalPages}
            </span>
            <Button
              variant="ghost"
              onClick={() => setPage((value) => Math.min(totalPages, value + 1))}
              disabled={page >= totalPages}
            >
              下一页
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[180px] flex-1">
              <Input
                label="操作主体（精确）"
                value={actor}
                onChange={(event) => {
                  setActor(event.target.value);
                  setPage(1);
                }}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="audit-method">
                方法
              </label>
              <select
                id="audit-method"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={method}
                onChange={(event) => {
                  setMethod(event.target.value);
                  setPage(1);
                }}
              >
                <option value="">全部方法</option>
                {AUDITED_METHODS.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </div>
            <div className="min-w-[240px] flex-1">
              <Input
                label="路径前缀"
                value={path}
                placeholder="如 /crate-api/healthcare/v1/"
                onChange={(event) => {
                  setPath(event.target.value);
                  setPage(1);
                }}
              />
            </div>
            <div className="w-[210px]">
              <Input
                label="起始时间（含）"
                type="datetime-local"
                value={from}
                onChange={(event) => {
                  setFrom(event.target.value);
                  setPage(1);
                }}
              />
            </div>
            <div className="w-[210px]">
              <Input
                label="结束时间（含）"
                type="datetime-local"
                value={to}
                onChange={(event) => {
                  setTo(event.target.value);
                  setPage(1);
                }}
              />
            </div>
            <Button variant="secondary" onClick={() => void load()} loading={loading}>
              查询
            </Button>
          </div>

          <p className="text-xs text-fg-dimmed">
            路径为前缀匹配；方法精确匹配（服务端会统一大写）；时间按业务时区（Asia/Shanghai）转换为
            ISO-8601 带偏移时间提交。列表按发生时间倒序。
          </p>

          {filterError && <p className="text-sm text-danger">{filterError}</p>}
          {error && <p className="text-sm text-danger">{error}</p>}

          {loading ? (
            <div className="flex justify-center py-10">
              <LoadingSpinner />
            </div>
          ) : entries.length === 0 ? (
            <EmptyState
              icon="📜"
              title="暂无审计记录"
              description="审计只记录写请求（POST/PUT/PATCH/DELETE）；可调整筛选条件或等待产生写操作"
            />
          ) : (
            <Table columns={columns} data={entries} />
          )}
        </div>
      </Card>
    </div>
  );
}
