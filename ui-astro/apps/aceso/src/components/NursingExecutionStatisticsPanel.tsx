import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, Table, type Column } from "@pitchfork/ui";
import {
  listNursingExecutionStatistics,
  type IdentitySubject,
  type NursingExecutionStatistics,
} from "@pitchfork/shared/aceso";

// ========================================================================
//  Helpers
// ========================================================================

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function thisMonday(): string {
  const d = new Date();
  const day = d.getDay();
  const diff = d.getDate() - day + (day === 0 ? -6 : 1);
  d.setDate(diff);
  return d.toISOString().slice(0, 10);
}

function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return "暂无应完成任务";
  return `${value.toFixed(2)}%`;
}

function statusCountSummary(stat: NursingExecutionStatistics): string {
  const parts: string[] = [];
  if (stat.pending_total > 0) parts.push(`待执行 ${stat.pending_total}`);
  if (stat.in_progress_total > 0) parts.push(`执行中 ${stat.in_progress_total}`);
  if (stat.completed_total > 0) parts.push(`已完成 ${stat.completed_total}`);
  if (stat.skipped_total > 0) parts.push(`跳过 ${stat.skipped_total}`);
  if (stat.cancelled_total > 0) parts.push(`取消 ${stat.cancelled_total}`);
  return parts.join(" · ") || "无";
}

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

// ========================================================================
//  Component Props
// ========================================================================

interface Props {
  subjects: IdentitySubject[];
  /** 外部版本号自增时触发重新加载（用于状态操作后刷新） */
  reloadKey?: number;
}

export default function NursingExecutionStatisticsPanel({ subjects, reloadKey = 0 }: Props) {
  // 编辑中的筛选条件（输入框直接绑定，不触发请求）
  const [draft, setDraft] = useState(() => ({
    dateFrom: thisMonday(),
    dateTo: today(),
    executor: "",
  }));
  // 已提交的查询条件：仅“查询”按钮或 reloadKey 刷新使用同一份快照
  const [submitted, setSubmitted] = useState(() => ({
    dateFrom: thisMonday(),
    dateTo: today(),
    executor: "",
  }));
  const [records, setRecords] = useState<NursingExecutionStatistics[]>([]);
  const [meta, setMeta] = useState<NursingExecutionStatisticsPageMeta | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  // 请求序号：丢弃过期响应，避免旧结果覆盖新条件
  const requestSeq = useRef(0);

  const fetchStatistics = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setError("");
    try {
      const response = await listNursingExecutionStatistics({
        date_from: submitted.dateFrom,
        date_to: submitted.dateTo,
        executor: submitted.executor || undefined,
        limit: 100,
      });
      if (seq !== requestSeq.current) return;
      setRecords(response.records);
      setMeta(response.meta);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError(err instanceof Error ? err.message : "无法加载统计数据");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [submitted]);

  // 首次加载（已提交条件）与 reloadKey 变化时刷新；修改编辑条件不触发请求
  useEffect(() => {
    void fetchStatistics();
  }, [fetchStatistics, reloadKey]);

  const submitQuery = useCallback(() => {
    setSubmitted({ ...draft });
  }, [draft]);

  const subjectMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const subject of subjects) {
      map.set(subject.id, subject.display_name);
    }
    return map;
  }, [subjects]);

  const columns: Column<NursingExecutionStatistics>[] = useMemo(() => [
    {
      key: "executor",
      header: "执行人",
      className: "min-w-[100px]",
      render: (row) => row.executor ? (subjectMap.get(row.executor) ?? row.executor) : (
        <span className="text-fg-dimmed">未分配</span>
      ),
    },
    { key: "scheduled_total", header: "计划任务", className: "w-[80px] text-right", render: (row) => row.scheduled_total },
    { key: "due_total", header: "应完成", className: "w-[70px] text-right", render: (row) => row.due_total },
    { key: "completed_due_total", header: "已完成", className: "w-[70px] text-right", render: (row) => row.completed_due_total },
    { key: "overdue_total", header: "逾期", className: "w-[60px] text-right", render: (row) => row.overdue_total > 0 ? <span className="text-danger">{row.overdue_total}</span> : row.overdue_total },
    {
      key: "completion_rate",
      header: "完成率",
      className: "w-[90px] text-right",
      render: (row) => (
        <span className={row.completion_rate != null && row.completion_rate < 100 ? "text-fg" : "text-success"}>
          {formatRate(row.completion_rate)}
        </span>
      ),
    },
    {
      key: "status_summary",
      header: "状态摘要",
      className: "min-w-[180px]",
      render: (row) => <span className="text-xs text-fg-muted">{statusCountSummary(row)}</span>,
    },
  ], [subjectMap]);

  return (
    <Card title="工作量统计" bodyClassName="space-y-4">
      {/* 筛选栏 */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-fg-muted" htmlFor="stat-date-from">开始日期</label>
          <input
            id="stat-date-from"
            type="date"
            className={selectClass}
            value={draft.dateFrom}
            onChange={(event) => setDraft((d) => ({ ...d, dateFrom: event.target.value }))}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-fg-muted" htmlFor="stat-date-to">结束日期</label>
          <input
            id="stat-date-to"
            type="date"
            className={selectClass}
            value={draft.dateTo}
            onChange={(event) => setDraft((d) => ({ ...d, dateTo: event.target.value }))}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-xs font-medium text-fg-muted" htmlFor="stat-executor">执行人</label>
          <select
            id="stat-executor"
            className={selectClass}
            value={draft.executor}
            onChange={(event) => setDraft((d) => ({ ...d, executor: event.target.value }))}
          >
            <option value="">全部执行人</option>
            {subjects.map((subject) => (
              <option key={subject.id} value={subject.id}>{subject.display_name}</option>
            ))}
          </select>
        </div>
        <Button size="sm" onClick={submitQuery} loading={loading}>查询</Button>
      </div>

      {error && (
        <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{error}</div>
      )}

      {/* 统计卡片 */}
      {meta && !loading && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
          <StatCard label="计划任务" value={meta.scheduled_total} />
          <StatCard label="应完成" value={meta.due_total} />
          <StatCard label="已完成" value={meta.completed_due_total} />
          <StatCard label="逾期" value={meta.overdue_total} highlight={meta.overdue_total > 0} />
          <StatCard
            label="计划完成率"
            value={formatRate(meta.completion_rate)}
            highlight={meta.completion_rate != null && meta.completion_rate < 60}
          />
        </div>
      )}

      {/* 加载态 */}
      {loading && (
        <div className="py-8 text-center text-sm text-fg-dimmed">正在加载统计数据…</div>
      )}

      {/* 空态 */}
      {!loading && !error && records.length === 0 && (
        <div className="py-8 text-center text-sm text-fg-dimmed">
          {meta?.scheduled_total === 0 ? "所选范围内暂无执行记录" : "暂无分组数据"}
        </div>
      )}

      {/* 分组表格 */}
      {!loading && records.length > 0 && (
        <Table
          className="min-w-[650px]"
          columns={columns}
          data={records}
          loading={false}
          emptyMessage="暂无数据"
        />
      )}
    </Card>
  );
}

// ========================================================================
//  Helper: 统计小卡片
// ========================================================================

function StatCard({ label, value, highlight }: { label: string; value: string | number; highlight?: boolean }) {
  return (
    <div className={`rounded-md border px-3 py-2.5 ${highlight ? "border-danger/30 bg-danger-bg" : "border-border bg-surface-alt"}`}>
      <p className="text-xs text-fg-muted">{label}</p>
      <p className={`mt-1 text-lg font-semibold ${highlight ? "text-danger" : "text-fg-emphasis"}`}>{value}</p>
    </div>
  );
}

// ========================================================================
//  Local type for meta (avoids importing the full page type)
// ========================================================================

interface NursingExecutionStatisticsPageMeta {
  total: number;
  date_from: string;
  date_to: string;
  scheduled_total: number;
  pending_total: number;
  in_progress_total: number;
  completed_total: number;
  skipped_total: number;
  cancelled_total: number;
  due_total: number;
  completed_due_total: number;
  overdue_total: number;
  completion_rate: number | null;
}
