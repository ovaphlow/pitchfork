import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, type Column } from "@pitchfork/ui";
import {
  createNursingTask,
  generateNursingExecutions,
  listIdentitySubjects,
  listNursingExecutionStatistics,
  listNursingServicePeriods,
  listNursingTaskExecutions,
  listNursingTasks,
  listNursingTodayExecutions,
  listPatients,
  updateNursingTaskExecutionStatus,
  updateNursingTaskStatus,
  type IdentitySubject,
  type NursingExecutionStatistics,
  type NursingExecutionStatisticsPage,
  type NursingServicePeriod,
  type NursingTask,
  type NursingTaskExecution,
  type NursingTodayExecution,
  type Patient,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 10;

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const textareaClass = "w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
/** 筛选栏紧凑控件（与 h-8 按钮对齐） */
const filterInputClass = "h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const filterSelectClass = "h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

/** 频次选项：与护理任务一致（PRN/STAT 不自动生成排期） */
const FREQUENCY_OPTIONS: [string, string][] = [
  ["QD", "每日一次"],
  ["BID", "每日两次"],
  ["TID", "每日三次"],
  ["QID", "每日四次"],
  ["QOD", "隔日一次"],
  ["QW", "每周一次"],
  ["BIW", "每周两次"],
  ["TIW", "每周三次"],
  ["PRN", "按需"],
  ["STAT", "立即/临时"],
];

const TASK_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "进行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};

const EXECUTION_STATUS_LABELS: Record<string, string> = {
  PENDING: "待执行",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function todayLocal(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function formatTime(value: string | null | undefined): string {
  return value ? value.slice(11, 16) : "-";
}

function formatOverdueMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "";
  if (minutes < 60) return `已逾期 ${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const remaining = minutes % 60;
  if (remaining === 0) return `已逾期 ${hours} 小时`;
  return `已逾期 ${hours} 小时 ${remaining} 分钟`;
}

function taskStatusBadge(status: string) {
  const variant =
    status === "ACTIVE" ? ("info" as const)
      : status === "COMPLETED" ? ("success" as const)
        : ("default" as const);
  return <Badge variant={variant}>{TASK_STATUS_LABELS[status] ?? status}</Badge>;
}

function executionStatusBadge(status: string) {
  const variant =
    status === "PENDING" ? ("warning" as const)
      : status === "IN_PROGRESS" ? ("info" as const)
        : status === "COMPLETED" ? ("success" as const)
          : ("default" as const);
  return <Badge variant={variant}>{EXECUTION_STATUS_LABELS[status] ?? status}</Badge>;
}

interface ActivityForm {
  description: string;
  category: string;
  organizer: string;
  venue: string;
  participants: string;
  remark: string;
  frequencyCode: string;
  frequencyName: string;
  startDate: string;
  endDate: string;
  scheduleTimes: string;
  patientIds: string[];
}

const formDefaults = (): ActivityForm => ({
  description: "",
  category: "",
  organizer: "",
  venue: "",
  participants: "",
  remark: "",
  frequencyCode: "QD",
  frequencyName: "每日一次",
  startDate: todayLocal(),
  endDate: "",
  scheduleTimes: "",
  patientIds: [],
});

/** 每活动执行进度（按任务拉取） */
interface TaskExecProgress {
  loading: boolean;
  records: NursingTaskExecution[];
}

export default function ActivitiesPage() {
  // ========================================================================
  //  基础数据：老人、照护周期、执行人（用于展示与创建时选择）
  // ========================================================================
  const [patients, setPatients] = useState<Patient[]>([]);
  const [periodByPatient, setPeriodByPatient] = useState<Map<string, NursingServicePeriod>>(new Map());
  const [patientNameById, setPatientNameById] = useState<Map<string, string>>(new Map());
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);
  const subjectMap = useMemo(() => new Map(subjects.map((s) => [s.id, s.display_name])), [subjects]);

  useEffect(() => {
    void (async () => {
      try {
        const [patientRes, periodRes, subjectRes] = await Promise.all([
          listPatients({ status: "ACTIVE", limit: 500 }),
          listNursingServicePeriods({ status: "ACTIVE", limit: 1000 }),
          listIdentitySubjects(1, 100),
        ]);
        setPatients(patientRes.records);
        const periodMap = new Map<string, NursingServicePeriod>();
        for (const p of periodRes.records) {
          if (!periodMap.has(p.patient_id)) periodMap.set(p.patient_id, p);
        }
        setPeriodByPatient(periodMap);
        setPatientNameById(new Map(patientRes.records.map((p) => [p.id, p.name])));
        setSubjects(subjectRes.records);
      } catch {
        // 基础数据加载失败不影响页面主体，创建时选择老人会提示重试
      }
    })();
  }, []);

  /** period_id → 长者姓名（全院性活动无 period，显示「全院活动」） */
  const periodPatientName = useCallback(
    (periodId: string | null | undefined): string | null => {
      if (!periodId) return null;
      const period = periodByPatient.get(periodId);
      if (!period) return null;
      return patientNameById.get(period.patient_id) ?? null;
    },
    [periodByPatient, patientNameById],
  );

  // ========================================================================
  //  活动任务列表（F1）— 按 task_type=REHABILITATION 过滤，状态/日期范围前端筛选
  // ========================================================================
  const [allTasks, setAllTasks] = useState<NursingTask[]>([]);
  const [tasksLoading, setTasksLoading] = useState(false);
  const [tasksError, setTasksError] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [dateFromFilter, setDateFromFilter] = useState("");
  const [dateToFilter, setDateToFilter] = useState("");
  const [page, setPage] = useState(1);

  const loadTasks = useCallback(async () => {
    setTasksLoading(true);
    setTasksError("");
    try {
      const res = await listNursingTasks({ task_type: "REHABILITATION", limit: 500 });
      setAllTasks(res.records);
    } catch (error) {
      setTasksError(errorMessage(error, "无法加载活动任务"));
    } finally {
      setTasksLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTasks();
  }, [loadTasks]);

  /** 活动周期与筛选日期范围有交集的任务（start_date ≤ dateTo 且 end_date ≥ dateFrom） */
  const visibleTasks = useMemo(() => {
    return allTasks.filter((task) => {
      if (statusFilter && task.status !== statusFilter) return false;
      if (dateFromFilter || dateToFilter) {
        const start = task.start_date ? task.start_date.slice(0, 10) : null;
        const end = task.end_date ? task.end_date.slice(0, 10) : null;
        if (dateFromFilter && end !== null && end < dateFromFilter) return false;
        if (dateToFilter && start !== null && start > dateToFilter) return false;
        // 活动无起止日期时按「贯穿任意范围」处理
      }
      return true;
    });
  }, [allTasks, statusFilter, dateFromFilter, dateToFilter]);

  const totalPages = Math.max(1, Math.ceil(visibleTasks.length / PAGE_SIZE));
  const pageTasks = useMemo(
    () => visibleTasks.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE),
    [visibleTasks, page],
  );

  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);

  // ——— 每活动执行进度（当前页任务逐条拉取，按 task_id 过滤） ———
  const [execByTask, setExecByTask] = useState<Record<string, TaskExecProgress>>({});

  useEffect(() => {
    let cancelled = false;
    const ids = pageTasks.map((t) => t.id);
    if (ids.length === 0) return;
    setExecByTask((prev) => {
      const next = { ...prev };
      for (const id of ids) next[id] = { loading: true, records: prev[id]?.records ?? [] };
      return next;
    });
    void (async () => {
      const results = await Promise.allSettled(ids.map((id) => listNursingTaskExecutions({ task_id: id, limit: 500 })));
      if (cancelled) return;
      const next: Record<string, TaskExecProgress> = {};
      ids.forEach((id, index) => {
        const result = results[index];
        next[id] = {
          loading: false,
          records: result.status === "fulfilled" ? result.value.records : [],
        };
      });
      setExecByTask((prev) => ({ ...prev, ...next }));
    })();
    return () => {
      cancelled = true;
    };
  }, [pageTasks]);

  // ========================================================================
  //  今日活动看板（F5）— 复用 GET /task-executions/today + task_type 过滤
  // ========================================================================
  const [todayDate, setTodayDate] = useState(todayLocal);
  const [todayExecutions, setTodayExecutions] = useState<NursingTodayExecution[]>([]);
  const [todayLoading, setTodayLoading] = useState(false);
  const [todayError, setTodayError] = useState("");
  const [todayStatusFilter, setTodayStatusFilter] = useState("");
  const [todayOverdueOnly, setTodayOverdueOnly] = useState(false);
  const [todayOverdueTotal, setTodayOverdueTotal] = useState(0);

  const loadToday = useCallback(async () => {
    setTodayLoading(true);
    setTodayError("");
    try {
      const res = await listNursingTodayExecutions({
        date: todayDate,
        task_type: "REHABILITATION",
        limit: 200,
      });
      setTodayExecutions(res.records);
      setTodayOverdueTotal(res.meta.overdue_total ?? 0);
    } catch (error) {
      setTodayError(errorMessage(error, "无法加载今日活动"));
    } finally {
      setTodayLoading(false);
    }
  }, [todayDate]);

  useEffect(() => {
    void loadToday();
  }, [loadToday]);

  /** Q2：按活动名聚合展示「一场多人」的打卡记录 */
  const todayGroups = useMemo(() => {
    const filtered = todayExecutions.filter((exec) => {
      if (todayStatusFilter && exec.status !== todayStatusFilter) return false;
      if (todayOverdueOnly && !exec.is_overdue) return false;
      return true;
    });
    const groups = new Map<string, NursingTodayExecution[]>();
    for (const exec of filtered) {
      const key = exec.task_description || exec.task_id || exec.id;
      const list = groups.get(key) ?? [];
      list.push(exec);
      groups.set(key, list);
    }
    return [...groups.entries()]
      .map(([key, records]) => {
        const sorted = [...records].sort((a, b) => (a.planned_time ?? "").localeCompare(b.planned_time ?? ""));
        return {
          key,
          description: records[0].task_description ?? "未命名活动",
          frequency: records[0].task_frequency_name ?? "",
          plannedTime: sorted[0]?.planned_time ?? null,
          records: sorted,
          participants: new Set(records.map((r) => r.patient_name ?? `task:${r.task_id}`)).size,
          completed: records.filter((r) => r.status === "COMPLETED").length,
        };
      })
      .sort((a, b) => (a.plannedTime ?? "").localeCompare(b.plannedTime ?? ""));
  }, [todayExecutions, todayStatusFilter, todayOverdueOnly]);

  // ========================================================================
  //  进度统计（F7）— 复用 GET /task-executions/statistics + task_type 过滤
  // ========================================================================
  const [statsFrom, setStatsFrom] = useState(() => daysAgo(6));
  const [statsTo, setStatsTo] = useState(todayLocal);
  const [stats, setStats] = useState<NursingExecutionStatisticsPage | null>(null);
  const [statsLoading, setStatsLoading] = useState(false);
  const [statsError, setStatsError] = useState("");

  const loadStats = useCallback(async () => {
    if (!statsFrom || !statsTo) return;
    setStatsLoading(true);
    setStatsError("");
    try {
      const res = await listNursingExecutionStatistics({
        date_from: statsFrom,
        date_to: statsTo,
        task_type: "REHABILITATION",
        limit: 100,
      });
      setStats(res);
    } catch (error) {
      setStatsError(errorMessage(error, "无法加载进度统计"));
    } finally {
      setStatsLoading(false);
    }
  }, [statsFrom, statsTo]);

  useEffect(() => {
    void loadStats();
  }, [loadStats]);

  // ========================================================================
  //  创建活动任务（F2）
  // ========================================================================
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<ActivityForm>(formDefaults());
  const [createSaving, setCreateSaving] = useState(false);
  const [createError, setCreateError] = useState("");

  function togglePatient(patientId: string) {
    setCreateForm((current) => ({
      ...current,
      patientIds: current.patientIds.includes(patientId)
        ? current.patientIds.filter((id) => id !== patientId)
        : [...current.patientIds, patientId],
    }));
  }

  async function handleCreateActivity() {
    const form = createForm;
    if (!form.description.trim()) {
      setCreateError("请填写活动名称");
      return;
    }
    if (!form.frequencyCode) {
      setCreateError("请选择活动频次");
      return;
    }
    if (!form.startDate) {
      setCreateError("请选择开始日期");
      return;
    }
    if (form.endDate && form.endDate < form.startDate) {
      setCreateError("结束日期不能早于开始日期");
      return;
    }
    const times = form.scheduleTimes
      .split(/[,，\s]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (times.some((t) => !/^\d{2}:\d{2}$/.test(t))) {
      setCreateError("自定义时段格式应为 HH:mm，多个时段用逗号分隔，例如：09:00, 15:00");
      return;
    }

    const metadata: Record<string, unknown> = {};
    if (times.length > 0) metadata.schedule_times = times;
    if (form.category.trim()) metadata.category = form.category.trim();
    if (form.organizer.trim()) metadata.organizer = form.organizer.trim();
    if (form.venue.trim()) metadata.venue = form.venue.trim();
    if (form.participants.trim()) metadata.participants = form.participants.trim();
    if (form.remark.trim()) metadata.remark = form.remark.trim();

    // Q2：按老人维度各建一条 REHABILITATION 任务；不选择老人则创建为全院性活动（Q4）
    const targetPatientIds = form.patientIds.length > 0 ? form.patientIds : [null];
    setCreateSaving(true);
    setCreateError("");
    try {
      for (const patientId of targetPatientIds) {
        const period = patientId ? periodByPatient.get(patientId) : undefined;
        await createNursingTask({
          task_type: "REHABILITATION",
          description: form.description.trim(),
          frequency_code: form.frequencyCode,
          frequency_name: form.frequencyName || undefined,
          start_date: form.startDate,
          end_date: form.endDate || undefined,
          ...(period ? { period_id: period.id, encounter_id: period.encounter_id ?? undefined } : {}),
          ...(Object.keys(metadata).length > 0 ? { metadata } : {}),
        });
      }
      setCreateOpen(false);
      setCreateForm(formDefaults());
      await Promise.all([loadTasks(), loadToday(), loadStats()]);
    } catch (error) {
      setCreateError(errorMessage(error, "创建活动失败"));
    } finally {
      setCreateSaving(false);
    }
  }

  // ========================================================================
  //  生成排期（F4）— 复用 POST /task-executions/generate
  // ========================================================================
  const [generateOpen, setGenerateOpen] = useState(false);
  const [generateFrom, setGenerateFrom] = useState(todayLocal);
  const [generateTo, setGenerateTo] = useState(todayLocal);
  const [generateResult, setGenerateResult] = useState<{ generated: number; skipped: number; errors: unknown[] } | null>(null);
  const [generateSaving, setGenerateSaving] = useState(false);
  const [generateError, setGenerateError] = useState("");

  async function handleGenerate() {
    if (!generateFrom || !generateTo) {
      setGenerateError("请选择起止日期");
      return;
    }
    if (generateTo < generateFrom) {
      setGenerateError("结束日期不能早于开始日期");
      return;
    }
    setGenerateSaving(true);
    setGenerateError("");
    try {
      const result = await generateNursingExecutions({ date_from: generateFrom, date_to: generateTo });
      setGenerateResult(result);
      await Promise.all([loadToday(), loadStats()]);
    } catch (error) {
      setGenerateError(errorMessage(error, "生成排期失败"));
    } finally {
      setGenerateSaving(false);
    }
  }

  // ========================================================================
  //  执行打卡（F6）— 完成（可带备注）/ 跳过 / 取消
  // ========================================================================
  const [actionTarget, setActionTarget] = useState<NursingTodayExecution | null>(null);
  const [actionMode, setActionMode] = useState<"complete" | "skip" | "cancel" | null>(null);
  const [actionNote, setActionNote] = useState("");
  const [actionSaving, setActionSaving] = useState(false);
  const [actionError, setActionError] = useState("");

  function openActionModal(execution: NursingTodayExecution, mode: "complete" | "skip" | "cancel") {
    setActionTarget(execution);
    setActionMode(mode);
    setActionNote("");
    setActionError("");
  }

  async function handleConfirmAction() {
    if (!actionTarget || !actionMode) return;
    if ((actionMode === "skip" || actionMode === "cancel") && !actionNote.trim()) {
      setActionError("请填写原因");
      return;
    }
    setActionSaving(true);
    setActionError("");
    try {
      const status = actionMode === "complete" ? "COMPLETED" : actionMode === "skip" ? "SKIPPED" : "CANCELLED";
      await updateNursingTaskExecutionStatus(actionTarget.id, status, actionNote.trim() || undefined);
      setActionTarget(null);
      setActionMode(null);
      setActionNote("");
      await Promise.all([loadToday(), loadStats()]);
    } catch (error) {
      setActionError(errorMessage(error, "操作失败"));
    } finally {
      setActionSaving(false);
    }
  }

  // ========================================================================
  //  活动任务状态管理（F3）— 完成 / 取消
  // ========================================================================
  const [taskStatusTarget, setTaskStatusTarget] = useState<NursingTask | null>(null);
  const [taskStatusMode, setTaskStatusMode] = useState<"COMPLETED" | "CANCELLED" | null>(null);
  const [taskStatusSaving, setTaskStatusSaving] = useState(false);
  const [taskStatusError, setTaskStatusError] = useState("");

  async function handleConfirmTaskStatus() {
    if (!taskStatusTarget || !taskStatusMode) return;
    setTaskStatusSaving(true);
    setTaskStatusError("");
    try {
      await updateNursingTaskStatus(taskStatusTarget.id, taskStatusMode);
      setTaskStatusTarget(null);
      setTaskStatusMode(null);
      await Promise.all([loadTasks(), loadToday(), loadStats()]);
    } catch (error) {
      setTaskStatusError(errorMessage(error, "更新活动状态失败"));
    } finally {
      setTaskStatusSaving(false);
    }
  }

  // ========================================================================
  //  今日看板 — 打卡操作按钮
  // ========================================================================
  function renderTodayActions(record: NursingTodayExecution) {
    const btnClass = "px-2 py-0.5 text-xs rounded";
    if (actionSaving) return <span className="text-xs text-fg-dimmed">处理中…</span>;
    switch (record.status) {
      case "PENDING":
        return (
          <div className="flex gap-1">
            <button type="button" className={btnClass + " bg-accent/10 text-accent hover:bg-accent/20"} onClick={() => void updateNursingTaskExecutionStatus(record.id, "IN_PROGRESS").then(() => Promise.all([loadToday(), loadStats()])).catch((error) => setTodayError(errorMessage(error, "开始执行失败")))}>
              开始
            </button>
            <button type="button" className={btnClass + " bg-amber-100 text-amber-700 hover:bg-amber-200"} onClick={() => openActionModal(record, "skip")}>
              跳过
            </button>
            <button type="button" className={btnClass + " bg-red-100 text-red-600 hover:bg-red-200"} onClick={() => openActionModal(record, "cancel")}>
              取消
            </button>
          </div>
        );
      case "IN_PROGRESS":
        return (
          <div className="flex gap-1">
            <button type="button" className={btnClass + " bg-green-100 text-green-700 hover:bg-green-200"} onClick={() => openActionModal(record, "complete")}>
              完成
            </button>
            <button type="button" className={btnClass + " bg-red-100 text-red-600 hover:bg-red-200"} onClick={() => openActionModal(record, "cancel")}>
              取消
            </button>
          </div>
        );
      default:
        return <span className="text-xs text-fg-dimmed">—</span>;
    }
  }

  // ========================================================================
  //  活动任务列表列定义
  // ========================================================================
  const taskColumns: Column<NursingTask>[] = [
    {
      key: "description",
      header: "活动名称",
      className: "min-w-[200px]",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.description}</div>
          {(() => {
            const meta = row.metadata as Record<string, unknown> | null;
            const parts: string[] = [];
            if (meta?.category) parts.push(String(meta.category));
            if (meta?.venue) parts.push(String(meta.venue));
            if (meta?.organizer) parts.push(`组织者：${String(meta.organizer)}`);
            return parts.length > 0 ? (
              <div className="mt-0.5 text-xs text-fg-dimmed">{parts.join(" · ")}</div>
            ) : null;
          })()}
        </div>
      ),
    },
    {
      key: "period_id",
      header: "归属",
      className: "min-w-[100px]",
      render: (row) =>
        row.period_id ? (
          <Badge variant="info">{periodPatientName(row.period_id) ?? "个人计划"}</Badge>
        ) : (
          <Badge variant="warning">全院活动</Badge>
        ),
    },
    {
      key: "frequency_name",
      header: "频次",
      className: "min-w-[90px]",
      render: (row) => row.frequency_name || row.frequency_code || "-",
    },
    {
      key: "period",
      header: "活动周期",
      className: "min-w-[180px]",
      render: (row) => (
        <span className="text-xs text-fg-muted">
          {formatDate(row.start_date)} 至 {formatDate(row.end_date)}
        </span>
      ),
    },
    {
      key: "status",
      header: "状态",
      className: "min-w-[80px]",
      render: (row) => taskStatusBadge(row.status),
    },
    {
      key: "progress",
      header: "进度",
      className: "min-w-[110px]",
      render: (row) => {
        const progress = execByTask[row.id];
        if (!progress || progress.loading) return <span className="text-xs text-fg-dimmed">加载中…</span>;
        const total = progress.records.length;
        const completed = progress.records.filter((r) => r.status === "COMPLETED").length;
        const skipped = progress.records.filter((r) => r.status === "SKIPPED").length;
        const cancelled = progress.records.filter((r) => r.status === "CANCELLED").length;
        if (total === 0) return <span className="text-xs text-fg-dimmed">未排期</span>;
        return (
          <span className="text-xs text-fg-muted" title={`已完成 ${completed} · 已跳过 ${skipped} · 已取消 ${cancelled} · 共 ${total}`}>
            已完成 {completed}/{total}
            {skipped > 0 ? ` · 跳过 ${skipped}` : ""}
          </span>
        );
      },
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[120px]",
      render: (row) =>
        row.status === "ACTIVE" ? (
          <div className="flex gap-1">
            <button
              type="button"
              className="px-2 py-0.5 text-xs rounded bg-green-100 text-green-700 hover:bg-green-200"
              onClick={() => {
                setTaskStatusTarget(row);
                setTaskStatusMode("COMPLETED");
                setTaskStatusError("");
              }}
            >
              完成
            </button>
            <button
              type="button"
              className="px-2 py-0.5 text-xs rounded bg-red-100 text-red-600 hover:bg-red-200"
              onClick={() => {
                setTaskStatusTarget(row);
                setTaskStatusMode("CANCELLED");
                setTaskStatusError("");
              }}
            >
              取消
            </button>
          </div>
        ) : (
          <span className="text-xs text-fg-dimmed">—</span>
        ),
    },
  ];

  // ========================================================================
  //  进度统计 — 指标与按执行人明细
  // ========================================================================
  const statMetaItems = useMemo(() => {
    const meta = stats?.meta;
    return [
      { label: "计划次数", value: meta ? String(meta.scheduled_total) : "-" },
      { label: "已完成", value: meta ? String(meta.completed_total) : "-" },
      { label: "已跳过", value: meta ? String(meta.skipped_total) : "-" },
      { label: "已取消", value: meta ? String(meta.cancelled_total) : "-" },
      { label: "逾期", value: meta ? String(meta.overdue_total) : "-" },
      { label: "完成率", value: meta ? (meta.completion_rate != null ? `${meta.completion_rate}%` : "—") : "-" },
    ];
  }, [stats]);

  const statColumns: Column<NursingExecutionStatistics>[] = [
    {
      key: "executor",
      header: "执行人",
      className: "min-w-[100px]",
      render: (row) => (row.executor ? (subjectMap.get(row.executor) ?? row.executor) : "未指定"),
    },
    { key: "scheduled_total", header: "计划", className: "min-w-[60px]", render: (row) => String(row.scheduled_total) },
    { key: "pending_total", header: "待执行", className: "min-w-[60px]", render: (row) => String(row.pending_total) },
    { key: "in_progress_total", header: "执行中", className: "min-w-[60px]", render: (row) => String(row.in_progress_total) },
    { key: "completed_total", header: "已完成", className: "min-w-[60px]", render: (row) => String(row.completed_total) },
    { key: "skipped_total", header: "已跳过", className: "min-w-[60px]", render: (row) => String(row.skipped_total) },
    { key: "cancelled_total", header: "已取消", className: "min-w-[60px]", render: (row) => String(row.cancelled_total) },
    {
      key: "completion_rate",
      header: "完成率",
      className: "min-w-[70px]",
      render: (row) => (row.completion_rate != null ? `${String(row.completion_rate)}%` : "—"),
    },
  ];

  return (
    <div className="space-y-6">
      {/* ========================================================================
          页面操作栏
      ======================================================================== */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">活动管理</h2>
          <p className="mt-1 text-sm text-fg-muted">
            机构服务项目 · 复用护理任务模式（活动创建 → 按频次排期 → 每日执行打卡 → 进度统计）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={() => { setGenerateResult(null); setGenerateError(""); setGenerateOpen(true); }}>
            生成排期
          </Button>
          <Button onClick={() => { setCreateError(""); setCreateForm(formDefaults()); setCreateOpen(true); }}>
            + 创建活动
          </Button>
        </div>
      </div>

      {/* ========================================================================
          今日活动看板（F5 / F6）
      ======================================================================== */}
      <Card
        title="今日活动看板"
        actions={
          <div className="flex items-center gap-2">
            <input type="date" value={todayDate} onChange={(event) => setTodayDate(event.target.value)} className={filterInputClass + " w-40"} />
            <select id="today-status-filter" className={filterSelectClass + " w-28"} value={todayStatusFilter} onChange={(event) => setTodayStatusFilter(event.target.value)}>
              <option value="">全部状态</option>
              <option value="PENDING">待执行</option>
              <option value="IN_PROGRESS">执行中</option>
              <option value="COMPLETED">已完成</option>
              <option value="SKIPPED">已跳过</option>
              <option value="CANCELLED">已取消</option>
            </select>
            <Button variant="secondary" size="sm" onClick={() => void loadToday()} loading={todayLoading}>
              刷新
            </Button>
          </div>
        }
      >
        <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-fg-muted">
          <span>
            待执行 <Badge variant="warning">{todayExecutions.filter((e) => e.status === "PENDING").length}</Badge>
          </span>
          <span>
            已完成 <Badge variant="success">{todayExecutions.filter((e) => e.status === "COMPLETED").length}</Badge>
          </span>
          <span className="mx-1 h-4 w-px bg-border" />
          <button
            type="button"
            onClick={() => setTodayOverdueOnly((v) => !v)}
            className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 font-medium transition-colors ${todayOverdueOnly ? "bg-red-100 text-red-700 hover:bg-red-200" : "hover:bg-surface-alt"}`}
          >
            {todayOverdueOnly ? "显示全部" : "只看逾期"}
            <Badge variant="danger">{todayOverdueTotal}</Badge>
          </button>
          {todayError && <span className="text-danger">{todayError}</span>}
        </div>

        {todayLoading ? (
          <div className="py-12 text-center text-sm text-fg-dimmed">正在加载今日活动…</div>
        ) : todayGroups.length === 0 ? (
          <EmptyState
            icon="🎯"
            title={todayOverdueOnly ? "今日无逾期活动" : "今日暂无康复活动"}
            description={todayOverdueOnly ? "所有已到计划时间的活动均已处理。" : "请先创建活动并生成排期，今日的活动会出现在这里。"}
            action={
              <Button size="sm" onClick={() => { setCreateError(""); setCreateForm(formDefaults()); setCreateOpen(true); }}>
                + 创建活动
              </Button>
            }
          />
        ) : (
          <div className="space-y-4">
            {todayGroups.map((group) => (
              <div key={group.key} className="overflow-hidden rounded-lg border border-border">
                <div className="flex flex-wrap items-center justify-between gap-2 bg-surface-alt/60 px-4 py-3">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate font-medium text-fg-emphasis">{group.description}</span>
                    {group.frequency && <span className="text-xs text-fg-dimmed">{group.frequency}</span>}
                    <span className="text-xs text-fg-dimmed">计划 {formatTime(group.plannedTime)}</span>
                  </div>
                  <div className="flex items-center gap-2 text-xs text-fg-muted">
                    <span>参与 {group.participants} 人</span>
                    <span>
                      完成 {group.completed}/{group.records.length}
                    </span>
                  </div>
                </div>
                <div className="divide-y divide-border/50">
                  {group.records.map((exec) => (
                    <div key={exec.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5">
                      <div className="flex min-w-0 items-center gap-2">
                        <span className="text-sm text-fg">{exec.patient_name ?? "全院活动"}</span>
                        {exec.is_overdue && exec.overdue_minutes != null && (
                          <span className="text-xs text-danger">{formatOverdueMinutes(exec.overdue_minutes)}</span>
                        )}
                        {exec.note && (
                          <span className="max-w-[180px] truncate text-xs text-fg-dimmed" title={exec.note}>
                            {exec.note}
                          </span>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        {executionStatusBadge(exec.status)}
                        {exec.executor && (
                          <span className="text-xs text-fg-dimmed">{subjectMap.get(exec.executor) ?? exec.executor}</span>
                        )}
                        {renderTodayActions(exec)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* ========================================================================
          活动任务列表（F1 / F2 / F3）
      ======================================================================== */}
      <Card
        title="活动任务"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select id="task-status-filter" className={filterSelectClass + " w-28"} value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="ACTIVE">进行中</option>
              <option value="COMPLETED">已完成</option>
              <option value="CANCELLED">已取消</option>
            </select>
            <input type="date" value={dateFromFilter} onChange={(event) => { setDateFromFilter(event.target.value); setPage(1); }} className={filterInputClass + " w-36"} placeholder="周期起" />
            <span className="text-xs text-fg-dimmed">至</span>
            <input type="date" value={dateToFilter} onChange={(event) => { setDateToFilter(event.target.value); setPage(1); }} className={filterInputClass + " w-36"} placeholder="周期止" />
          </div>
        }
      >
        {tasksError && <p className="mb-3 text-sm text-danger">{tasksError}</p>}
        {allTasks.length === 0 && !tasksLoading ? (
          <EmptyState
            icon="🎯"
            title="暂无活动"
            description="创建第一个康复活动（如晨间太极、肢体功能训练），系统将按频次自动生成每日执行计划。"
            action={
              <Button size="sm" onClick={() => { setCreateError(""); setCreateForm(formDefaults()); setCreateOpen(true); }}>
                + 创建活动
              </Button>
            }
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    {taskColumns.map((col) => (
                      <th key={col.key} className={`text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-3 px-4 ${col.className || ""}`}>
                        {col.header}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pageTasks.length === 0 ? (
                    <tr>
                      <td colSpan={taskColumns.length} className="py-16 text-center text-sm text-fg-dimmed">
                        没有符合筛选条件的活动
                      </td>
                    </tr>
                  ) : (
                    pageTasks.map((row) => (
                      <tr key={row.id} className="border-b border-border/50 hover:bg-surface-alt transition-colors duration-100">
                        {taskColumns.map((col) => (
                          <td key={col.key} className={`py-3 px-4 text-fg ${col.className || ""}`}>
                            {col.render ? col.render(row) : (row[col.key as keyof NursingTask] as unknown as ReactNode) ?? "—"}
                          </td>
                        ))}
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <div className="mt-4 flex items-center justify-between text-xs text-fg-muted">
              <span>
                共 {visibleTasks.length} 条 · 第 {page} / {totalPages} 页
              </span>
              <div className="flex gap-2">
                <Button variant="secondary" size="sm" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>
                  上一页
                </Button>
                <Button variant="secondary" size="sm" disabled={page >= totalPages} onClick={() => setPage((p) => Math.min(totalPages, p + 1))}>
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </Card>

      {/* ========================================================================
          进度统计（F7）
      ======================================================================== */}
      <Card
        title="进度统计"
        actions={
          <div className="flex items-center gap-2">
            <input type="date" value={statsFrom} onChange={(event) => setStatsFrom(event.target.value)} className={filterInputClass + " w-40"} />
            <span className="text-xs text-fg-dimmed">至</span>
            <input type="date" value={statsTo} onChange={(event) => setStatsTo(event.target.value)} className={filterInputClass + " w-40"} />
            <Button variant="secondary" size="sm" onClick={() => void loadStats()} loading={statsLoading}>
              查询
            </Button>
          </div>
        }
      >
        {statsError && <p className="mb-3 text-sm text-danger">{statsError}</p>}
        <div className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {statMetaItems.map((item) => (
            <div key={item.label} className="rounded-lg border border-border bg-surface-alt/50 px-4 py-3">
              <div className="text-xs text-fg-muted">{item.label}</div>
              <div className="mt-1 text-xl font-semibold text-fg-emphasis">{item.value}</div>
            </div>
          ))}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                {statColumns.map((col) => (
                  <th key={col.key} className={`text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-3 px-4 ${col.className || ""}`}>
                    {col.header}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {stats && stats.records.length > 0 ? (
                stats.records.map((row, index) => (
                  <tr key={index} className="border-b border-border/50 hover:bg-surface-alt transition-colors duration-100">
                    {statColumns.map((col) => (
                      <td key={col.key} className={`py-3 px-4 text-fg ${col.className || ""}`}>
                        {col.render ? col.render(row) : "—"}
                      </td>
                    ))}
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={statColumns.length} className="py-12 text-center text-sm text-fg-dimmed">
                    {statsLoading ? "正在加载统计…" : "所选日期范围内暂无康复活动执行记录"}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>

      {/* ========================================================================
          创建活动弹窗（F2）
      ======================================================================== */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="创建康复活动" width="40rem">
        <div className="space-y-4">
          <Input
            label="活动名称"
            value={createForm.description}
            onChange={(event) => setCreateForm((current) => ({ ...current, description: event.target.value }))}
            placeholder="例如：晨间太极 / 肢体功能训练"
            required
          />
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="activity-frequency">频次</label>
              <select
                id="activity-frequency"
                className={selectClass}
                value={createForm.frequencyCode}
                onChange={(event) => {
                  const code = event.target.value;
                  const name = FREQUENCY_OPTIONS.find(([c]) => c === code)?.[1] ?? "";
                  setCreateForm((current) => ({ ...current, frequencyCode: code, frequencyName: name }));
                }}
              >
                {FREQUENCY_OPTIONS.map(([code, name]) => (
                  <option key={code} value={code}>
                    {code} — {name}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="活动类别"
              value={createForm.category}
              onChange={(event) => setCreateForm((current) => ({ ...current, category: event.target.value }))}
              placeholder="例如：运动训练 / 文娱活动"
            />
            <Input
              label="开始日期"
              type="date"
              value={createForm.startDate}
              onChange={(event) => setCreateForm((current) => ({ ...current, startDate: event.target.value }))}
            />
            <Input
              label="结束日期（可选）"
              type="date"
              value={createForm.endDate}
              onChange={(event) => setCreateForm((current) => ({ ...current, endDate: event.target.value }))}
            />
            <Input
              label="自定义时段（可选）"
              value={createForm.scheduleTimes}
              onChange={(event) => setCreateForm((current) => ({ ...current, scheduleTimes: event.target.value }))}
              placeholder="例如：09:00, 15:00（留空按频次默认时段）"
            />
            <Input
              label="场地（可选）"
              value={createForm.venue}
              onChange={(event) => setCreateForm((current) => ({ ...current, venue: event.target.value }))}
              placeholder="例如：一楼活动室"
            />
            <Input
              label="组织者（可选）"
              value={createForm.organizer}
              onChange={(event) => setCreateForm((current) => ({ ...current, organizer: event.target.value }))}
              placeholder="例如：康复师 王老师"
            />
            <Input
              label="参与对象（可选）"
              value={createForm.participants}
              onChange={(event) => setCreateForm((current) => ({ ...current, participants: event.target.value }))}
              placeholder="例如：自理老人 / 轮椅长者"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="activity-remark">
              备注（可选）
            </label>
            <textarea
              id="activity-remark"
              className={textareaClass}
              rows={2}
              value={createForm.remark}
              onChange={(event) => setCreateForm((current) => ({ ...current, remark: event.target.value }))}
              placeholder="活动注意事项等"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-fg-muted">参与老人（可选，可多选）</span>
            <p className="text-xs text-fg-dimmed">
              每位老人将各建一条康复活动任务（按老人维度打卡）；不选择老人则创建为全院性活动（不挂老人）。
            </p>
            {patients.length === 0 ? (
              <p className="text-xs text-fg-dimmed">暂无在住老人可选，将创建为全院性活动。</p>
            ) : (
              <div className="max-h-44 overflow-y-auto rounded-md border border-border divide-y divide-border/50">
                {patients.map((patient) => {
                  const period = periodByPatient.get(patient.id);
                  const checked = createForm.patientIds.includes(patient.id);
                  const selectable = Boolean(period);
                  return (
                    <label
                      key={patient.id}
                      className={`flex items-center gap-2 px-3 py-2 text-sm cursor-pointer ${selectable ? "hover:bg-surface-alt" : "opacity-50 cursor-not-allowed"}`}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={!selectable}
                        onChange={() => togglePatient(patient.id)}
                        className="h-4 w-4 border-border bg-surface accent-accent"
                      />
                      <span className="text-fg">{patient.name}</span>
                      {!selectable && <span className="text-xs text-fg-dimmed">（无活跃照护周期）</span>}
                    </label>
                  );
                })}
              </div>
            )}
          </div>
          {createError && <p className="text-sm text-danger">{createError}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void handleCreateActivity()} loading={createSaving} disabled={createSaving}>
              创建{createForm.patientIds.length > 1 ? ` ${createForm.patientIds.length} 条任务` : ""}
            </Button>
          </div>
        </div>
      </Modal>

      {/* ========================================================================
          生成排期弹窗（F4）
      ======================================================================== */}
      <Modal open={generateOpen} onClose={() => setGenerateOpen(false)} title="生成每日排期" width="28rem">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            为指定日期范围内的全部进行中活动按频次自动生成每日执行计划（重复生成不会产生重复记录）。
          </p>
          <div className="grid gap-4 grid-cols-2">
            <Input label="开始日期" type="date" value={generateFrom} onChange={(event) => setGenerateFrom(event.target.value)} />
            <Input label="结束日期" type="date" value={generateTo} onChange={(event) => setGenerateTo(event.target.value)} />
          </div>
          {generateResult && (
            <div className="rounded-md border border-border bg-surface-alt/50 px-4 py-3 text-sm text-fg-muted">
              本次生成 <span className="font-medium text-fg-emphasis">{generateResult.generated}</span> 条，
              跳过 <span className="font-medium text-fg-emphasis">{generateResult.skipped}</span> 条
              {generateResult.errors.length > 0 && `，错误 ${generateResult.errors.length} 条`}。
            </div>
          )}
          {generateError && <p className="text-sm text-danger">{generateError}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setGenerateOpen(false)}>
              关闭
            </Button>
            <Button onClick={() => void handleGenerate()} loading={generateSaving} disabled={generateSaving}>
              生成排期
            </Button>
          </div>
        </div>
      </Modal>

      {/* ========================================================================
          执行打卡弹窗（F6）
      ======================================================================== */}
      <Modal
        open={actionTarget !== null && actionMode !== null}
        onClose={() => {
          setActionTarget(null);
          setActionMode(null);
        }}
        title={actionMode === "complete" ? "完成活动打卡" : actionMode === "skip" ? "跳过活动" : "取消活动"}
        width="28rem"
      >
        {actionTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border bg-surface-alt/50 px-4 py-3 text-sm">
              <div className="font-medium text-fg-emphasis">{actionTarget.task_description ?? "未命名活动"}</div>
              <div className="mt-1 text-xs text-fg-muted">
                {actionTarget.patient_name ?? "全院活动"} · 计划 {formatDateTime(actionTarget.planned_time)}
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="action-note">
                {actionMode === "complete" ? "完成备注（可选）" : "原因（必填）"}
              </label>
              <textarea
                id="action-note"
                className={textareaClass}
                rows={3}
                value={actionNote}
                onChange={(event) => setActionNote(event.target.value)}
                placeholder={actionMode === "complete" ? "例如：完成情况、参与人数等" : "请填写跳过 / 取消原因"}
              />
            </div>
            {actionError && <p className="text-sm text-danger">{actionError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button
                variant="secondary"
                onClick={() => {
                  setActionTarget(null);
                  setActionMode(null);
                }}
              >
                取消
              </Button>
              <Button onClick={() => void handleConfirmAction()} loading={actionSaving} disabled={actionSaving}>
                确认
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ========================================================================
          活动任务状态确认弹窗（F3）
      ======================================================================== */}
      <Modal
        open={taskStatusTarget !== null && taskStatusMode !== null}
        onClose={() => {
          setTaskStatusTarget(null);
          setTaskStatusMode(null);
        }}
        title={taskStatusMode === "COMPLETED" ? "完成活动" : "取消活动"}
        width="28rem"
      >
        {taskStatusTarget && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              {taskStatusMode === "COMPLETED"
                ? `确认将活动「${taskStatusTarget.description}」标记为已完成？之后将不能再打卡。`
                : `确认取消活动「${taskStatusTarget.description}」？取消后不再生成新的执行计划。`}
            </p>
            {taskStatusError && <p className="text-sm text-danger">{taskStatusError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button
                variant="secondary"
                onClick={() => {
                  setTaskStatusTarget(null);
                  setTaskStatusMode(null);
                }}
              >
                返回
              </Button>
              <Button
                variant={taskStatusMode === "CANCELLED" ? "danger" : "primary"}
                onClick={() => void handleConfirmTaskStatus()}
                loading={taskStatusSaving}
                disabled={taskStatusSaving}
              >
                确认{taskStatusMode === "COMPLETED" ? "完成" : "取消"}
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
