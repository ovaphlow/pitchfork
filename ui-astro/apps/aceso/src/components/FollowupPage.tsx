import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createFollowupPlan,
  createFollowupRecord,
  getFollowupPlanStats,
  listElderlyAdmissions,
  listFollowupPlans,
  listFollowupRecords,
  listPatientFollowups,
  listPatients,
  updateFollowupPlanStatus,
  type FollowupPatientTimeline,
  type FollowupPlan,
  type FollowupPlanStats,
  type FollowupRecord,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

const FOLLOWUP_TYPES = ["出院后随访", "慢病随访", "常规电话随访"] as const;
const FOLLOWUP_WAYS = ["电话", "上门", "门诊"] as const;
const FOLLOWUP_RESULTS = ["正常", "异常", "需复访", "需转诊"] as const;

type Tab = "todo" | "plans" | "records";

interface AdmissionOption {
  id: string;
  patient_id: string;
  patient_name: string;
  encounter_no: string | null;
  status: string;
  admit_date: string | null;
  discharge_date: string | null;
}

interface CreateForm {
  encounter_id: string;
  followup_type: string;
  planned_date: string;
  planned_way: string;
  remark: string;
}

interface RecordForm {
  followup_way: string;
  followup_date: string;
  contact_object: string;
  condition_summary: string;
  systolic: string;
  diastolic: string;
  heart_rate: string;
  blood_glucose: string;
  temperature: string;
  guidance: string;
  result: string;
  next_followup_date: string;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function displayValue(value: string | null | undefined): string {
  return value?.trim() || "-";
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.slice(0, 16).replace("T", " ") : "-";
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

/** 本地时区的 date input 值（YYYY-MM-DD） */
function todayLocal(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 本地时区的 datetime-local 值（YYYY-MM-DDTHH:mm） */
function nowLocalInput(): string {
  const d = new Date();
  return `${todayLocal()}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local → OffsetDateTime 字符串（Asia/Shanghai，服务端拒绝未来时间） */
function toOffsetDateTime(localInput: string): string {
  if (!localInput) return "";
  const withSeconds = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(localInput) ? `${localInput}:00` : localInput;
  return `${withSeconds}+08:00`;
}

function statusBadge(status: string) {
  switch (status) {
    case "已逾期":
      return <Badge variant="warning">已逾期</Badge>;
    case "待随访":
      return <Badge variant="info">待随访</Badge>;
    case "已完成":
      return <Badge variant="success">已完成</Badge>;
    case "已取消":
      return <Badge variant="default">已取消</Badge>;
    default:
      return <Badge>{status}</Badge>;
  }
}

function resultBadge(result: string) {
  switch (result) {
    case "正常":
      return <Badge variant="success">正常</Badge>;
    case "异常":
      return <Badge variant="danger">异常</Badge>;
    case "需复访":
      return <Badge variant="warning">需复访</Badge>;
    case "需转诊":
      return <Badge variant="danger">需转诊</Badge>;
    default:
      return <Badge>{result}</Badge>;
  }
}

const createFormDefaults: CreateForm = {
  encounter_id: "",
  followup_type: "",
  planned_date: todayLocal(),
  planned_way: "电话",
  remark: "",
};

const recordFormDefaults: RecordForm = {
  followup_way: "电话",
  followup_date: nowLocalInput(),
  contact_object: "",
  condition_summary: "",
  systolic: "",
  diastolic: "",
  heart_rate: "",
  blood_glucose: "",
  temperature: "",
  guidance: "",
  result: "",
  next_followup_date: "",
};

export default function FollowupPage() {
  // ——— 概览统计 ———
  const [stats, setStats] = useState<FollowupPlanStats | null>(null);

  // ——— 页签与列表 ———
  const [tab, setTab] = useState<Tab>("todo");
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  const [todoPlans, setTodoPlans] = useState<FollowupPlan[]>([]);
  const [todoTotal, setTodoTotal] = useState(0);
  const [todoPage, setTodoPage] = useState(1);

  const [plans, setPlans] = useState<FollowupPlan[]>([]);
  const [plansTotal, setPlansTotal] = useState(0);
  const [plansPage, setPlansPage] = useState(1);
  const [plansStatus, setPlansStatus] = useState("");
  const [plansType, setPlansType] = useState("");

  const [records, setRecords] = useState<FollowupRecord[]>([]);
  const [recordsTotal, setRecordsTotal] = useState(0);
  const [recordsPage, setRecordsPage] = useState(1);
  const [recordsResult, setRecordsResult] = useState("");

  // ——— 老人/入住选项（新建计划与临时随访共用） ———
  const [admissionOptions, setAdmissionOptions] = useState<AdmissionOption[]>([]);
  const [patientQuery, setPatientQuery] = useState("");

  // ——— 弹窗状态 ———
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateForm>(createFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  /** 记录随访：plan 非空表示从计划进入；为空表示无计划临时随访 */
  const [recordPlan, setRecordPlan] = useState<FollowupPlan | null>(null);
  const [recordOpen, setRecordOpen] = useState(false);
  const [recordForm, setRecordForm] = useState<RecordForm>(recordFormDefaults);
  const [recordError, setRecordError] = useState("");
  const [savingRecord, setSavingRecord] = useState(false);
  /** 临时随访时选择的对象 */
  const [tempEncounterId, setTempEncounterId] = useState("");
  const [tempFollowupType, setTempFollowupType] = useState("");

  const [cancelPlan, setCancelPlan] = useState<FollowupPlan | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelError, setCancelError] = useState("");
  const [cancelling, setCancelling] = useState(false);

  const [historyPatient, setHistoryPatient] = useState<{ id: string; name: string } | null>(null);
  const [history, setHistory] = useState<FollowupPatientTimeline | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState("");

  // ——— 数据加载 ———

  const loadStats = useCallback(async () => {
    try {
      setStats(await getFollowupPlanStats());
    } catch (error) {
      setPageError(errorMessage(error, "无法加载随访统计"));
    }
  }, []);

  const loadTodo = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listFollowupPlans({
        status: "待随访",
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setTodoPlans(response.records);
      setTodoTotal(response.meta.total);
      setTodoPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载待办随访"));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadPlans = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listFollowupPlans({
        ...(plansStatus === "已逾期" ? { overdue: true } : plansStatus ? { status: plansStatus } : {}),
        ...(plansType ? { followup_type: plansType } : {}),
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setPlans(response.records);
      setPlansTotal(response.meta.total);
      setPlansPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载随访计划"));
    } finally {
      setLoading(false);
    }
  }, [plansStatus, plansType]);

  const loadRecords = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listFollowupRecords({
        ...(recordsResult ? { result: recordsResult } : {}),
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setRecordsTotal(response.meta.total);
      setRecordsPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载随访记录"));
    } finally {
      setLoading(false);
    }
  }, [recordsResult]);

  const loadAdmissionOptions = useCallback(async () => {
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 500 }),
        listElderlyAdmissions({ status: "", limit: 500 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      const options = encounterResponse.records
        .filter((encounter) => patientById.get(encounter.patient_id)?.status !== "DECEASED")
        .map((encounter) => ({
          id: encounter.id,
          patient_id: encounter.patient_id,
          patient_name: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
          encounter_no: encounter.encounter_no,
          status: encounter.status,
          admit_date: encounter.admit_date,
          discharge_date: encounter.discharge_date,
        }));
      setAdmissionOptions(options);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载老人入住信息"));
    }
  }, []);

  const refresh = useCallback(async () => {
    await Promise.all([loadStats(), loadTodo(todoPage), loadPlans(plansPage), loadRecords(recordsPage)]);
  }, [loadStats, loadTodo, todoPage, loadPlans, plansPage, loadRecords, recordsPage]);

  useEffect(() => {
    void loadStats();
    void loadTodo(1);
  }, [loadStats, loadTodo]);

  // 页签切换时加载对应列表
  useEffect(() => {
    if (tab === "plans") void loadPlans(1);
    if (tab === "records") void loadRecords(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const filteredOptions = useMemo(() => {
    const query = patientQuery.trim().toLowerCase();
    if (!query) return admissionOptions;
    return admissionOptions.filter(
      (option) =>
        option.patient_name.toLowerCase().includes(query) ||
        option.encounter_no?.toLowerCase().includes(query),
    );
  }, [admissionOptions, patientQuery]);

  const selectedOption = useMemo(
    () => admissionOptions.find((option) => option.id === createForm.encounter_id) ?? null,
    [admissionOptions, createForm.encounter_id],
  );

  // ——— 新建随访计划 ———

  function openCreate() {
    setCreateForm({ ...createFormDefaults, planned_date: todayLocal() });
    setCreateError("");
    setPatientQuery("");
    setCreateOpen(true);
    void loadAdmissionOptions();
  }

  async function handleCreate() {
    if (!createForm.encounter_id) {
      setCreateError("请选择老人入住记录");
      return;
    }
    if (!createForm.followup_type) {
      setCreateError("请选择随访类型");
      return;
    }
    if (!createForm.planned_date) {
      setCreateError("请选择计划随访日期");
      return;
    }
    const option = admissionOptions.find((item) => item.id === createForm.encounter_id);
    if (!option) {
      setCreateError("请选择老人入住记录");
      return;
    }
    setCreating(true);
    setCreateError("");
    try {
      await createFollowupPlan({
        patient_id: option.patient_id,
        encounter_id: option.id,
        followup_type: createForm.followup_type,
        planned_date: createForm.planned_date,
        planned_way: createForm.planned_way,
        ...(createForm.remark.trim() ? { remark: createForm.remark.trim() } : {}),
      });
      setCreateOpen(false);
      await refresh();
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建随访计划"));
    } finally {
      setCreating(false);
    }
  }

  // ——— 记录随访 ———

  function openRecordForPlan(plan: FollowupPlan) {
    setRecordPlan(plan);
    setTempEncounterId("");
    setTempFollowupType("");
    setRecordForm({ ...recordFormDefaults, followup_way: plan.planned_way, followup_date: nowLocalInput() });
    setRecordError("");
    setRecordOpen(true);
  }

  function openTempRecord() {
    setRecordPlan(null);
    setTempEncounterId("");
    setTempFollowupType("");
    setPatientQuery("");
    setRecordForm(recordFormDefaults);
    setRecordError("");
    setRecordOpen(true);
    void loadAdmissionOptions();
  }

  function buildRecordInput() {
    const result = recordForm.result;
    if (!result) return { error: "请选择随访结果" };
    const plan = recordPlan;
    let patientId = "";
    let encounterId = "";
    let followupType = "";
    if (plan) {
      patientId = plan.patient_id;
      encounterId = plan.encounter_id;
      followupType = plan.followup_type;
    } else {
      if (!tempEncounterId) return { error: "请选择老人入住记录" };
      const option = admissionOptions.find((item) => item.id === tempEncounterId);
      if (!option) return { error: "请选择老人入住记录" };
      patientId = option.patient_id;
      encounterId = option.id;
      if (!tempFollowupType) return { error: "请选择随访类型" };
      followupType = tempFollowupType;
    }
    const vitals: Record<string, number> = {};
    const vitalsFields: Array<[keyof RecordForm, string]> = [
      ["systolic", "收缩压"],
      ["diastolic", "舒张压"],
      ["heart_rate", "心率"],
      ["blood_glucose", "血糖"],
      ["temperature", "体温"],
    ];
    for (const [key, label] of vitalsFields) {
      const raw = recordForm[key].trim();
      if (!raw) continue;
      const value = Number(raw);
      if (!Number.isFinite(value)) return { error: `${label}必须为数字` };
      vitals[key] = value;
    }
    return {
      input: {
        ...(plan ? { plan_id: plan.id } : {}),
        patient_id: patientId,
        encounter_id: encounterId,
        followup_type: followupType,
        followup_way: recordForm.followup_way,
        ...(recordForm.followup_date ? { followup_date: toOffsetDateTime(recordForm.followup_date) } : {}),
        ...(recordForm.contact_object.trim() ? { contact_object: recordForm.contact_object.trim() } : {}),
        ...(recordForm.condition_summary.trim() ? { condition_summary: recordForm.condition_summary.trim() } : {}),
        ...(Object.keys(vitals).length > 0 ? { vitals } : {}),
        ...(recordForm.guidance.trim() ? { guidance: recordForm.guidance.trim() } : {}),
        result,
        ...(recordForm.next_followup_date ? { next_followup_date: recordForm.next_followup_date } : {}),
      },
      error: null,
    };
  }

  async function handleSaveRecord() {
    const built = buildRecordInput();
    if (built.error) {
      setRecordError(built.error);
      return;
    }
    setSavingRecord(true);
    setRecordError("");
    try {
      await createFollowupRecord(built.input!);
      setRecordOpen(false);
      await refresh();
    } catch (error) {
      setRecordError(errorMessage(error, "无法保存随访记录"));
    } finally {
      setSavingRecord(false);
    }
  }

  // ——— 取消计划 ———

  function openCancel(plan: FollowupPlan) {
    setCancelPlan(plan);
    setCancelReason("");
    setCancelError("");
  }

  async function handleCancel() {
    if (!cancelPlan) return;
    if (!cancelReason.trim()) {
      setCancelError("取消必须填写原因");
      return;
    }
    setCancelling(true);
    setCancelError("");
    try {
      await updateFollowupPlanStatus(cancelPlan.id, {
        status: "已取消",
        cancel_reason: cancelReason.trim(),
      });
      setCancelPlan(null);
      await refresh();
    } catch (error) {
      setCancelError(errorMessage(error, "无法取消随访计划"));
    } finally {
      setCancelling(false);
    }
  }

  // ——— 老人随访历史 ———

  async function openHistory(patientId: string, patientName: string) {
    setHistoryPatient({ id: patientId, name: patientName });
    setHistory(null);
    setHistoryError("");
    setHistoryLoading(true);
    try {
      setHistory(await listPatientFollowups(patientId));
    } catch (error) {
      setHistoryError(errorMessage(error, "无法加载随访历史"));
    } finally {
      setHistoryLoading(false);
    }
  }

  // ——— 表格列 ———

  const planColumns: Column<FollowupPlan>[] = [
    {
      key: "patient_name",
      header: "老人",
      className: "min-w-[140px]",
      render: (row) => (
        <div className="flex items-center gap-2">
          <span className="font-medium text-fg-emphasis">{displayValue(row.patient_name)}</span>
          <button
            type="button"
            className="text-xs text-accent hover:underline"
            onClick={() => openHistory(row.patient_id, displayValue(row.patient_name))}
          >
            历史
          </button>
        </div>
      ),
    },
    { key: "followup_type", header: "随访类型", className: "min-w-[120px]", render: (row) =>
      row.followup_type === "慢病随访" ? (
        <a href={`/dashboard/chronic?patient=${encodeURIComponent(row.patient_id)}`} className="text-accent hover:underline" title="查看慢病档案">
          慢病随访 · 档案
        </a>
      ) : (
        row.followup_type
      ),
    },
    { key: "planned_date", header: "计划日期", className: "min-w-[110px]", render: (row) => formatDate(row.planned_date) },
    { key: "planned_way", header: "方式", className: "w-[80px]" },
    { key: "assignee", header: "责任人", className: "min-w-[110px]", render: (row) => displayValue(row.assignee) },
    { key: "status", header: "状态", className: "w-[90px]", render: (row) => statusBadge(row.status) },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[180px]",
      render: (row) => {
        const actionable = row.status === "待随访" || row.status === "已逾期";
        return (
          <div className="flex items-center gap-1.5">
            {actionable && (
              <>
                <Button variant="primary" size="sm" onClick={() => openRecordForPlan(row)}>记录随访</Button>
                <Button variant="ghost" size="sm" onClick={() => openCancel(row)}>取消</Button>
              </>
            )}
          </div>
        );
      },
    },
  ];

  const recordColumns: Column<FollowupRecord>[] = [
    {
      key: "patient_name",
      header: "老人",
      className: "min-w-[140px]",
      render: (row) => (
        <div className="flex items-center gap-2">
          <span className="font-medium text-fg-emphasis">{displayValue(row.patient_name)}</span>
          <button
            type="button"
            className="text-xs text-accent hover:underline"
            onClick={() => openHistory(row.patient_id, displayValue(row.patient_name))}
          >
            历史
          </button>
        </div>
      ),
    },
    { key: "followup_type", header: "随访类型", className: "min-w-[120px]", render: (row) =>
      row.followup_type === "慢病随访" ? (
        <a href={`/dashboard/chronic?patient=${encodeURIComponent(row.patient_id)}`} className="text-accent hover:underline" title="查看慢病档案">
          慢病随访 · 档案
        </a>
      ) : (
        row.followup_type
      ),
    },
    { key: "followup_way", header: "方式", className: "w-[80px]" },
    { key: "followup_date", header: "随访时间", className: "min-w-[140px]", render: (row) => formatDateTime(row.followup_date) },
    { key: "contact_object", header: "联系对象", className: "min-w-[100px]", render: (row) => displayValue(row.contact_object) },
    { key: "condition_summary", header: "状况摘要", className: "min-w-[200px]", render: (row) => <span className="line-clamp-2">{displayValue(row.condition_summary)}</span> },
    { key: "result", header: "结果", className: "w-[90px]", render: (row) => resultBadge(row.result) },
    { key: "next_followup_date", header: "下次随访", className: "min-w-[110px]", render: (row) => formatDate(row.next_followup_date) },
    { key: "operator", header: "记录人", className: "min-w-[110px]", render: (row) => displayValue(row.operator) },
  ];

  const todoPageCount = Math.max(1, Math.ceil(todoTotal / PAGE_SIZE));
  const plansPageCount = Math.max(1, Math.ceil(plansTotal / PAGE_SIZE));
  const recordsPageCount = Math.max(1, Math.ceil(recordsTotal / PAGE_SIZE));

  const tabButtons: Array<{ key: Tab; label: string; count?: number }> = [
    { key: "todo", label: "待办随访" },
    { key: "plans", label: "全部计划", count: plansTotal },
    { key: "records", label: "随访记录", count: recordsTotal },
  ];

  return (
    <div className="space-y-6">
      {/* 顶部概览 */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">随访管理</h2>
          <p className="mt-1 text-sm text-fg-muted">离院老人回访与在院慢病老人定期随访，形成计划 → 执行 → 记录 → 转诊/复访闭环</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={openTempRecord}>临时随访</Button>
          <Button variant="primary" onClick={openCreate}>新建随访计划</Button>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-accent/10 flex items-center justify-center">
              <svg className="w-5 h-5 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <polyline points="12 6 12 12 16 14" />
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">今日待随访</p>
              <p className="text-xl font-semibold text-fg-emphasis">{stats?.today_pending ?? "—"}</p>
            </div>
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-warning-bg flex items-center justify-center">
              <svg className="w-5 h-5 text-warning" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                <line x1="12" y1="9" x2="12" y2="13" />
                <line x1="12" y1="17" x2="12.01" y2="17" />
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">已逾期</p>
              <p className="text-xl font-semibold text-fg-emphasis">{stats?.overdue ?? "—"}</p>
            </div>
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-success-bg flex items-center justify-center">
              <svg className="w-5 h-5 text-success" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">本月已完成</p>
              <p className="text-xl font-semibold text-fg-emphasis">{stats?.month_completed ?? "—"}</p>
            </div>
          </div>
        </div>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      {/* 页签 */}
      <div className="flex items-center gap-1 bg-surface-alt rounded-lg p-0.5 w-fit">
        {tabButtons.map((item) => (
          <button
            key={item.key}
            onClick={() => setTab(item.key)}
            className={`px-3 py-1.5 text-sm rounded-md transition-all duration-150 ${
              tab === item.key
                ? "bg-surface text-fg-emphasis font-medium shadow-sm"
                : "text-fg-muted hover:text-fg"
            }`}
          >
            {item.label}
            {item.count !== undefined && <span className="ml-1.5 text-xs text-fg-dimmed">{item.count}</span>}
          </button>
        ))}
      </div>

      {/* 待办随访 */}
      {tab === "todo" && (
        <Card title="待办随访" actions={<span className="text-sm text-fg-dimmed">共 {todoTotal} 条（已逾期由系统按计划日期计算）</span>}>
          <Table
            columns={planColumns}
            data={todoPlans}
            loading={loading}
            emptyMessage="暂无待随访计划"
          />
          {todoTotal > 0 && (
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
              <span className="text-sm text-fg-muted">第 {todoPage} / {todoPageCount} 页</span>
              <div className="flex items-center gap-2">
                <Button variant="secondary" size="sm" disabled={todoPage <= 1 || loading} onClick={() => void loadTodo(todoPage - 1)}>上一页</Button>
                <Button variant="secondary" size="sm" disabled={todoPage >= todoPageCount || loading} onClick={() => void loadTodo(todoPage + 1)}>下一页</Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* 全部计划 */}
      {tab === "plans" && (
        <Card
          title="全部计划"
          actions={
            <div className="flex flex-wrap items-center gap-2">
              <select
                value={plansStatus}
                onChange={(event) => { setPlansStatus(event.target.value); void loadPlans(1); }}
                className="h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">全部状态</option>
                <option value="待随访">待随访</option>
                <option value="已逾期">已逾期</option>
                <option value="已完成">已完成</option>
                <option value="已取消">已取消</option>
              </select>
              <select
                value={plansType}
                onChange={(event) => { setPlansType(event.target.value); void loadPlans(1); }}
                className="h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">全部类型</option>
                {FOLLOWUP_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
              </select>
            </div>
          }
        >
          <Table
            columns={planColumns}
            data={plans}
            loading={loading}
            emptyMessage="暂无随访计划"
          />
          {plansTotal > 0 && (
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
              <span className="text-sm text-fg-muted">第 {plansPage} / {plansPageCount} 页</span>
              <div className="flex items-center gap-2">
                <Button variant="secondary" size="sm" disabled={plansPage <= 1 || loading} onClick={() => void loadPlans(plansPage - 1)}>上一页</Button>
                <Button variant="secondary" size="sm" disabled={plansPage >= plansPageCount || loading} onClick={() => void loadPlans(plansPage + 1)}>下一页</Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* 随访记录 */}
      {tab === "records" && (
        <Card
          title="随访记录"
          actions={
            <select
              value={recordsResult}
              onChange={(event) => { setRecordsResult(event.target.value); void loadRecords(1); }}
              className="h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部结果</option>
              {FOLLOWUP_RESULTS.map((result) => <option key={result} value={result}>{result}</option>)}
            </select>
          }
        >
          <Table
            columns={recordColumns}
            data={records}
            loading={loading}
            emptyMessage="暂无随访记录"
          />
          {recordsTotal > 0 && (
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
              <span className="text-sm text-fg-muted">第 {recordsPage} / {recordsPageCount} 页</span>
              <div className="flex items-center gap-2">
                <Button variant="secondary" size="sm" disabled={recordsPage <= 1 || loading} onClick={() => void loadRecords(recordsPage - 1)}>上一页</Button>
                <Button variant="secondary" size="sm" disabled={recordsPage >= recordsPageCount || loading} onClick={() => void loadRecords(recordsPage + 1)}>下一页</Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* 新建随访计划 */}
      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="新建随访计划" width="36rem">
        <form
          className="space-y-5"
          onSubmit={(event) => { event.preventDefault(); void handleCreate(); }}
        >
          {createError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="followup-patient">老人（入住记录）</label>
            <Input
              id="followup-patient-search"
              value={patientQuery}
              onChange={(event) => setPatientQuery(event.target.value)}
              placeholder="输入姓名或住院号检索"
            />
            <select
              id="followup-patient"
              value={createForm.encounter_id}
              onChange={(event) => setCreateForm((current) => ({ ...current, encounter_id: event.target.value }))}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">请选择老人</option>
              {filteredOptions.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.patient_name}（{option.encounter_no ?? "-"} · {option.status === "ACTIVE" ? "在院" : "已离院"}）
                </option>
              ))}
            </select>
            {selectedOption && (
              <p className="text-xs text-fg-dimmed">
                入住周期：{formatDate(selectedOption.admit_date)} 起
                {selectedOption.status === "DISCHARGED" && selectedOption.discharge_date ? ` · ${formatDate(selectedOption.discharge_date)} 离院` : ""}
              </p>
            )}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="followup-type">随访类型</label>
              <select
                id="followup-type"
                value={createForm.followup_type}
                onChange={(event) => setCreateForm((current) => ({ ...current, followup_type: event.target.value }))}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">请选择</option>
                {FOLLOWUP_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="followup-way">随访方式</label>
              <select
                id="followup-way"
                value={createForm.planned_way}
                onChange={(event) => setCreateForm((current) => ({ ...current, planned_way: event.target.value }))}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                {FOLLOWUP_WAYS.map((way) => <option key={way} value={way}>{way}</option>)}
              </select>
            </div>
          </div>

          <Input
            label="计划随访日期"
            type="date"
            value={createForm.planned_date}
            onChange={(event) => setCreateForm((current) => ({ ...current, planned_date: event.target.value }))}
            required
          />

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="followup-remark">备注</label>
            <textarea
              id="followup-remark"
              value={createForm.remark}
              onChange={(event) => setCreateForm((current) => ({ ...current, remark: event.target.value }))}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="例如：出院后第 7 天电话回访用药情况"
            />
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button type="submit" loading={creating}>创建计划</Button>
          </div>
        </form>
      </Modal>

      {/* 记录随访 */}
      <Modal
        open={recordOpen}
        onClose={() => !savingRecord && setRecordOpen(false)}
        title={recordPlan ? `记录随访 · ${displayValue(recordPlan.patient_name)}` : "临时随访"}
        width="38rem"
      >
        <form
          className="space-y-5"
          onSubmit={(event) => { event.preventDefault(); void handleSaveRecord(); }}
        >
          {recordError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{recordError}</div>}

          {recordPlan ? (
            <div className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm text-fg-muted">
              {displayValue(recordPlan.patient_name)} · {recordPlan.followup_type} · 计划日期 {formatDate(recordPlan.planned_date)}
            </div>
          ) : (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="temp-patient">老人（入住记录）</label>
              <Input
                id="temp-patient-search"
                value={patientQuery}
                onChange={(event) => setPatientQuery(event.target.value)}
                placeholder="输入姓名或住院号检索"
              />
              <select
                id="temp-patient"
                value={tempEncounterId}
                onChange={(event) => setTempEncounterId(event.target.value)}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">请选择老人</option>
                {filteredOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.patient_name}（{option.encounter_no ?? "-"} · {option.status === "ACTIVE" ? "在院" : "已离院"}）
                  </option>
                ))}
              </select>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="temp-type">随访类型</label>
                <select
                  id="temp-type"
                  value={tempFollowupType}
                  onChange={(event) => setTempFollowupType(event.target.value)}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <option value="">请选择</option>
                  {FOLLOWUP_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
                </select>
              </div>
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="record-way">随访方式</label>
              <select
                id="record-way"
                value={recordForm.followup_way}
                onChange={(event) => setRecordForm((current) => ({ ...current, followup_way: event.target.value }))}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                {FOLLOWUP_WAYS.map((way) => <option key={way} value={way}>{way}</option>)}
              </select>
            </div>
            <Input
              label="实际随访时间"
              type="datetime-local"
              value={recordForm.followup_date}
              onChange={(event) => setRecordForm((current) => ({ ...current, followup_date: event.target.value }))}
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              label="联系对象"
              value={recordForm.contact_object}
              onChange={(event) => setRecordForm((current) => ({ ...current, contact_object: event.target.value }))}
              placeholder="老人或家属姓名"
            />
            <Input
              label="建议下次随访日期"
              type="date"
              value={recordForm.next_followup_date}
              onChange={(event) => setRecordForm((current) => ({ ...current, next_followup_date: event.target.value }))}
            />
          </div>

          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">生命体征</h4>
            <div className="mt-3 grid gap-4 sm:grid-cols-3">
              <Input
                label="收缩压 (mmHg)"
                type="number"
                value={recordForm.systolic}
                onChange={(event) => setRecordForm((current) => ({ ...current, systolic: event.target.value }))}
                placeholder="如 130"
              />
              <Input
                label="舒张压 (mmHg)"
                type="number"
                value={recordForm.diastolic}
                onChange={(event) => setRecordForm((current) => ({ ...current, diastolic: event.target.value }))}
                placeholder="如 80"
              />
              <Input
                label="心率 (次/分)"
                type="number"
                value={recordForm.heart_rate}
                onChange={(event) => setRecordForm((current) => ({ ...current, heart_rate: event.target.value }))}
                placeholder="如 72"
              />
              <Input
                label="血糖 (mmol/L)"
                type="number"
                step="0.1"
                value={recordForm.blood_glucose}
                onChange={(event) => setRecordForm((current) => ({ ...current, blood_glucose: event.target.value }))}
                placeholder="如 6.2"
              />
              <Input
                label="体温 (℃)"
                type="number"
                step="0.1"
                value={recordForm.temperature}
                onChange={(event) => setRecordForm((current) => ({ ...current, temperature: event.target.value }))}
                placeholder="如 36.5"
              />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="record-condition">状况摘要 / 主诉</label>
            <textarea
              id="record-condition"
              value={recordForm.condition_summary}
              onChange={(event) => setRecordForm((current) => ({ ...current, condition_summary: event.target.value }))}
              rows={2}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="老人近况、主诉与观察到的状况"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="record-guidance">指导内容</label>
            <textarea
              id="record-guidance"
              value={recordForm.guidance}
              onChange={(event) => setRecordForm((current) => ({ ...current, guidance: event.target.value }))}
              rows={2}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="用药 / 康复 / 饮食指导"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted">随访结果</label>
            <div className="flex flex-wrap gap-4">
              {FOLLOWUP_RESULTS.map((result) => (
                <label key={result} className="flex items-center gap-1.5 text-sm text-fg cursor-pointer">
                  <input
                    type="radio"
                    name="followup-result"
                    value={result}
                    checked={recordForm.result === result}
                    onChange={(event) => setRecordForm((current) => ({ ...current, result: event.target.value }))}
                    className="accent-[var(--accent)]"
                  />
                  {result}
                </label>
              ))}
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setRecordOpen(false)} disabled={savingRecord}>取消</Button>
            <Button type="submit" loading={savingRecord}>{recordPlan ? "保存并完成计划" : "保存记录"}</Button>
          </div>
        </form>
      </Modal>

      {/* 取消计划 */}
      <Modal open={cancelPlan !== null} onClose={() => !cancelling && setCancelPlan(null)} title="取消随访计划">
        <form
          className="space-y-5"
          onSubmit={(event) => { event.preventDefault(); void handleCancel(); }}
        >
          {cancelError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{cancelError}</div>}
          {cancelPlan && (
            <p className="text-sm text-fg-muted">
              {displayValue(cancelPlan.patient_name)} · {cancelPlan.followup_type} · 计划日期 {formatDate(cancelPlan.planned_date)}
            </p>
          )}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="cancel-reason">取消原因（必填）</label>
            <textarea
              id="cancel-reason"
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="请说明取消原因"
            />
          </div>
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setCancelPlan(null)} disabled={cancelling}>返回</Button>
            <Button type="submit" variant="warning" loading={cancelling}>确认取消</Button>
          </div>
        </form>
      </Modal>

      {/* 老人随访历史 */}
      <Modal
        open={historyPatient !== null}
        onClose={() => setHistoryPatient(null)}
        title={`随访历史 · ${historyPatient?.name ?? ""}`}
        width="44rem"
      >
        {historyError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{historyError}</div>}
        {historyLoading ? (
          <EmptyState title="加载中…" />
        ) : history ? (
          <div className="space-y-6">
            <div>
              <h4 className="text-sm font-semibold text-fg-emphasis mb-3">随访计划</h4>
              {history.plans.length === 0 ? (
                <p className="text-sm text-fg-dimmed">暂无随访计划</p>
              ) : (
                <div className="space-y-2">
                  {history.plans.map((plan) => (
                    <div key={plan.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm">
                      <span className="font-medium text-fg-emphasis">{plan.followup_type}</span>
                      <span className="text-fg-muted">计划 {formatDate(plan.planned_date)}</span>
                      <span className="text-fg-muted">方式 {plan.planned_way}</span>
                      <span className="text-fg-muted">责任人 {displayValue(plan.assignee)}</span>
                      {statusBadge(plan.status)}
                      {plan.cancel_reason && <span className="text-xs text-fg-dimmed w-full">取消原因：{plan.cancel_reason}</span>}
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div>
              <h4 className="text-sm font-semibold text-fg-emphasis mb-3">随访记录</h4>
              {history.records.length === 0 ? (
                <p className="text-sm text-fg-dimmed">暂无随访记录</p>
              ) : (
                <div className="space-y-2">
                  {history.records.map((record) => (
                    <div key={record.id} className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm">
                      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                        <span className="font-medium text-fg-emphasis">{record.followup_type}</span>
                        <span className="text-fg-muted">{formatDateTime(record.followup_date)}</span>
                        <span className="text-fg-muted">方式 {record.followup_way}</span>
                        <span className="text-fg-muted">对象 {displayValue(record.contact_object)}</span>
                        {resultBadge(record.result)}
                      </div>
                      {record.condition_summary && <p className="mt-2 text-fg-muted">{record.condition_summary}</p>}
                      {record.guidance && <p className="mt-1 text-xs text-fg-dimmed">指导：{record.guidance}</p>}
                      {record.next_followup_date && (
                        <p className="mt-1 text-xs text-fg-dimmed">建议下次随访：{formatDate(record.next_followup_date)}</p>
                      )}
                      <p className="mt-1 text-xs text-fg-dimmed">记录人 {displayValue(record.operator)}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
