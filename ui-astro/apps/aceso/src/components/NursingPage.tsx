import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createNursingAssessment,
  createNursingPlan,
  createNursingServicePeriod,
  createNursingTask,
  createNursingTaskExecution,
  getCurrentSession,
  getNursingPlan,
  listActiveElderlyAdmissions,
  listIdentitySubjects,
  listNursingAssessments,
  listNursingPlans,
  listNursingServicePeriods,
  listNursingTaskExecutions,
  listNursingTasks,
  listPatients,
  updateNursingPlanStatus,
  updateNursingTaskExecutionStatus,
  updateNursingTaskStatus,
  type Encounter,
  type IdentitySubject,
  type NursingAssessment,
  type NursingPlan,
  type NursingServicePeriod,
  type NursingTask,
  type NursingTaskExecution,
  type Patient,
} from "@pitchfork/shared/aceso";

type Tab = "overview" | "assessments" | "plans" | "tasks";

interface ActiveAdmission extends Encounter {
  patientName: string;
}

interface AssessmentForm {
  assessType: string;
  assessDate: string;
  assessor: string;
  totalScore: string;
  resultLevel: string;
  detail: string;
  remark: string;
}

interface PlanItemForm {
  action: string;
  frequencyCode: string;
  frequencyName: string;
  durationDays: number | undefined;
  remark: string;
}

interface PlanForm {
  planName: string;
  goals: string;
  createdBy: string;
  startDate: string;
  endDate: string;
  items: PlanItemForm[];
}

interface TaskForm {
  planItemId: string;
  taskType: string;
  description: string;
  frequencyCode: string;
  frequencyName: string;
  startDate: string;
  endDate: string;
}

interface ExecutionForm {
  plannedTime: string;
  executor: string;
  note: string;
}

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const textareaClass = "w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function assessmentTypeLabel(value: string): string {
  return {
    ADMISSION: "入住评估",
    FALL_RISK: "跌倒风险",
    PRESSURE_SORE: "压疮风险",
    PAIN: "疼痛评估",
    BARTHEL: "Barthel 指数",
    NUTRITION: "营养评估",
    HOME_ENVIRONMENT: "居家环境",
    OTHER: "其他评估",
  }[value] ?? value;
}

function taskTypeLabel(value: string): string {
  return {
    NURSING: "护理操作",
    REHABILITATION: "康复训练",
    LIVING_CARE: "生活照料",
    HEALTH_EDUCATION: "健康教育",
    OTHER: "其他任务",
  }[value] ?? value;
}

function planStatusLabel(value: string): string {
  return { ACTIVE: "执行中", COMPLETED: "已完成", DISCONTINUED: "已终止" }[value] ?? value;
}

const frequencyOptions: [string, string][] = [
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

function taskStatusLabel(value: string): string {
  return { ACTIVE: "执行中", COMPLETED: "已完成", CANCELLED: "已取消" }[value] ?? value;
}

function executionStatusLabel(value: string): string {
  return {
    PENDING: "待执行",
    IN_PROGRESS: "执行中",
    COMPLETED: "已完成",
    SKIPPED: "已跳过",
    CANCELLED: "已取消",
  }[value] ?? value;
}

const assessmentDefaults = (): AssessmentForm => ({
  assessType: "BARTHEL",
  assessDate: "",
  assessor: "",
  totalScore: "",
  resultLevel: "",
  detail: "",
  remark: "",
});

const planDefaults = (): PlanForm => ({
  planName: "",
  goals: "",
  createdBy: "",
  startDate: "",
  endDate: "",
  items: [{ action: "", frequencyCode: "", frequencyName: "", durationDays: undefined, remark: "" }],
});

const taskDefaults = (): TaskForm => ({
  planItemId: "",
  taskType: "LIVING_CARE",
  description: "",
  frequencyCode: "QD",
  frequencyName: "每日一次",
  startDate: "",
  endDate: "",
});

const executionDefaults = (): ExecutionForm => ({ plannedTime: "", executor: "", note: "" });

export default function NursingPage() {
  const [admissions, setAdmissions] = useState<ActiveAdmission[]>([]);
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [period, setPeriod] = useState<NursingServicePeriod | null>(null);
  const [assessments, setAssessments] = useState<NursingAssessment[]>([]);
  const [plans, setPlans] = useState<NursingPlan[]>([]);
  const [tasks, setTasks] = useState<NursingTask[]>([]);
  const [executionsByTaskId, setExecutionsByTaskId] = useState<Record<string, NursingTaskExecution[]>>({});
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [pageError, setPageError] = useState("");
  const [actionError, setActionError] = useState("");
  const [activeTab, setActiveTab] = useState<Tab>("overview");
  const [assessmentOpen, setAssessmentOpen] = useState(false);
  const [planOpen, setPlanOpen] = useState(false);
  const [taskOpen, setTaskOpen] = useState(false);
  const [executionTask, setExecutionTask] = useState<NursingTask | null>(null);
  const [assessmentForm, setAssessmentForm] = useState<AssessmentForm>(assessmentDefaults);
  const [planForm, setPlanForm] = useState<PlanForm>(planDefaults);
  const [taskForm, setTaskForm] = useState<TaskForm>(taskDefaults);
  const [executionForm, setExecutionForm] = useState<ExecutionForm>(executionDefaults);
  const [saving, setSaving] = useState(false);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [admissionSearchInput, setAdmissionSearchInput] = useState("");
  const [admissionSearch, setAdmissionSearch] = useState("");
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);
  const [currentSubjectId, setCurrentSubjectId] = useState("");
  const [mounted, setMounted] = useState(false);

  const subjectMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const subject of subjects) {
      map.set(subject.id, subject.display_name);
    }
    return map;
  }, [subjects]);

  const selectedAdmission = admissions.find((admission) => admission.id === selectedEncounterId) ?? null;

  const loadAdmissions = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const [encounterResponse, patientResponse] = await Promise.all([
        listActiveElderlyAdmissions({ search: admissionSearch || undefined, limit: 100 }),
        listPatients({ status: "ACTIVE", limit: 100 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient: Patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      setAdmissions(records);
      setSelectedEncounterId((current) => records.some((record) => record.id === current) ? current : records[0]?.id ?? "");
    } catch (error) {
      setPageError(errorMessage(error, "无法加载当前入住长者"));
    } finally {
      setLoading(false);
    }
  }, [admissionSearch]);

  const loadResidentData = useCallback(async (admission: ActiveAdmission) => {
    setDetailLoading(true);
    setPageError("");
    setActionError("");
    try {
      const periodResponse = await listNursingServicePeriods({ patient_id: admission.patient_id, status: "ACTIVE", limit: 10 });
      const currentPeriod = periodResponse.records[0] ?? null;
      setPeriod(currentPeriod);
      if (!currentPeriod) {
        setAssessments([]);
        setPlans([]);
        setTasks([]);
        setExecutionsByTaskId({});
        return;
      }

      const [assessmentResponse, planResponse, taskResponse] = await Promise.all([
        listNursingAssessments({ period_id: currentPeriod.id, limit: 100 }),
        listNursingPlans({ period_id: currentPeriod.id, limit: 100 }),
        listNursingTasks({ period_id: currentPeriod.id, limit: 100 }),
      ]);
      const plansWithItems = await Promise.all(planResponse.records.map(async (plan) => {
        try {
          return await getNursingPlan(plan.id);
        } catch {
          return plan;
        }
      }));
      const executionEntries = await Promise.all(taskResponse.records.map(async (task) => {
        const response = await listNursingTaskExecutions({ task_id: task.id, limit: 20 });
        return [task.id, response.records] as const;
      }));
      setAssessments(assessmentResponse.records);
      setPlans(plansWithItems);
      setTasks(taskResponse.records);
      setExecutionsByTaskId(Object.fromEntries(executionEntries));
    } catch (error) {
      setPageError(errorMessage(error, "无法加载照护记录"));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAdmissions();
  }, [loadAdmissions]);

  useEffect(() => {
    Promise.all([
      listIdentitySubjects(1, 100),
      getCurrentSession(false),
    ]).then(([subjectList, session]) => {
      setSubjects(subjectList.records);
      setCurrentSubjectId(session.subject_id);
    }).catch(() => { /* 静默失败，不影响主流程 */ });
  }, []);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => setAdmissionSearch(admissionSearchInput.trim()), 1000);
    return () => window.clearTimeout(timeoutId);
  }, [admissionSearchInput]);

  useEffect(() => {
    if (selectedAdmission) {
      void loadResidentData(selectedAdmission);
    } else {
      setPeriod(null);
      setAssessments([]);
      setPlans([]);
      setTasks([]);
      setExecutionsByTaskId({});
    }
  }, [loadResidentData, selectedAdmission]);

  async function handleCreatePeriod() {
    if (!selectedAdmission) return;
    setSaving(true);
    setActionError("");
    try {
      await createNursingServicePeriod({
        patient_id: selectedAdmission.patient_id,
        service_type: "COMMUNITY_CARE",
        start_date: today(),
      });
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法建立照护周期"));
    } finally {
      setSaving(false);
    }
  }

  async function handleCreateAssessment() {
    if (!selectedAdmission || !period || !assessmentForm.assessDate) return;
    const totalScore = assessmentForm.totalScore.trim() ? Number(assessmentForm.totalScore) : undefined;
    if (totalScore !== undefined && Number.isNaN(totalScore)) {
      setActionError("评估分数必须是数字");
      return;
    }
    setSaving(true);
    setActionError("");
    try {
      await createNursingAssessment({
        encounter_id: selectedAdmission.id,
        period_id: period.id,
        assess_type: assessmentForm.assessType,
        assess_date: assessmentForm.assessDate,
        ...(assessmentForm.assessor.trim() ? { assessor: assessmentForm.assessor.trim() } : {}),
        ...(totalScore !== undefined ? { total_score: totalScore } : {}),
        ...(assessmentForm.resultLevel ? { result_level: assessmentForm.resultLevel } : {}),
        ...(assessmentForm.detail.trim() ? { detail: { note: assessmentForm.detail.trim() } } : {}),
        ...(assessmentForm.remark.trim() ? { remark: assessmentForm.remark.trim() } : {}),
      });
      setAssessmentOpen(false);
      setAssessmentForm(assessmentDefaults());
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法保存评估记录"));
    } finally {
      setSaving(false);
    }
  }

  async function handleCreatePlan() {
    if (!selectedAdmission || !period || !planForm.planName.trim()) {
      setActionError("计划名称不能为空");
      return;
    }
    const items = planForm.items
      .map((item) => ({
        action: item.action.trim(),
        ...(item.frequencyCode?.trim() ? { frequency_code: item.frequencyCode.trim() } : {}),
        ...(item.frequencyName?.trim() ? { frequency_name: item.frequencyName.trim() } : {}),
        ...(item.durationDays ? { duration_days: item.durationDays } : {}),
        ...(item.remark?.trim() ? { remark: item.remark.trim() } : {}),
      }))
      .filter((item) => item.action);
    if (items.length === 0) {
      setActionError("至少填写一条护理措施");
      return;
    }

    setSaving(true);
    setActionError("");
    try {
      await createNursingPlan({
        period_id: period.id,
        encounter_id: selectedAdmission.id,
        plan_name: planForm.planName.trim(),
        ...(planForm.goals.trim() ? { goals: planForm.goals.trim() } : {}),
        ...(planForm.createdBy.trim() ? { created_by: planForm.createdBy.trim() } : {}),
        ...(planForm.startDate ? { start_date: planForm.startDate } : {}),
        ...(planForm.endDate ? { end_date: planForm.endDate } : {}),
        items,
      });
      setPlanOpen(false);
      setPlanForm(planDefaults());
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法保存照护计划"));
    } finally {
      setSaving(false);
    }
  }

  async function handleCreateTask() {
    if (!selectedAdmission || !period || !taskForm.description.trim()) {
      setActionError("任务描述不能为空");
      return;
    }
    setSaving(true);
    setActionError("");
    try {
      await createNursingTask({
        period_id: period.id,
        encounter_id: selectedAdmission.id,
        ...(taskForm.planItemId ? { plan_item_id: taskForm.planItemId } : {}),
        task_type: taskForm.taskType,
        description: taskForm.description.trim(),
        ...(taskForm.frequencyCode.trim() ? { frequency_code: taskForm.frequencyCode.trim() } : {}),
        ...(taskForm.frequencyName.trim() ? { frequency_name: taskForm.frequencyName.trim() } : {}),
        ...(taskForm.startDate ? { start_date: taskForm.startDate } : {}),
        ...(taskForm.endDate ? { end_date: taskForm.endDate } : {}),
      });
      setTaskOpen(false);
      setTaskForm(taskDefaults());
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法创建照护任务"));
    } finally {
      setSaving(false);
    }
  }

  async function handleCreateExecution() {
    if (!executionTask) return;
    setSaving(true);
    setActionError("");
    try {
      const execution = await createNursingTaskExecution({
        task_id: executionTask.id,
        ...(executionForm.plannedTime ? { planned_time: new Date(executionForm.plannedTime).toISOString() } : {}),
        actual_time: new Date().toISOString(),
        ...(executionForm.executor.trim() ? { executor: executionForm.executor.trim() } : {}),
        ...(executionForm.note.trim() ? { note: executionForm.note.trim() } : {}),
      });
      await updateNursingTaskExecutionStatus(execution.id, "IN_PROGRESS");
      await updateNursingTaskExecutionStatus(execution.id, "COMPLETED");
      setExecutionTask(null);
      setExecutionForm(executionDefaults());
      if (selectedAdmission) await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法记录任务执行"));
    } finally {
      setSaving(false);
    }
  }

  async function handlePlanStatus(plan: NursingPlan, status: string) {
    setSavingId(plan.id);
    setActionError("");
    try {
      await updateNursingPlanStatus(plan.id, status);
      if (selectedAdmission) await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法更新计划状态"));
    } finally {
      setSavingId(null);
    }
  }

  async function handleTaskStatus(task: NursingTask, status: string) {
    setSavingId(task.id);
    setActionError("");
    try {
      await updateNursingTaskStatus(task.id, status);
      if (selectedAdmission) await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法更新任务状态"));
    } finally {
      setSavingId(null);
    }
  }

  function openAssessment() {
    setActionError("");
    setAssessmentForm({ ...assessmentDefaults(), assessor: currentSubjectId, assessDate: today() });
    setAssessmentOpen(true);
  }

  function openPlan() {
    setActionError("");
    setPlanForm({ ...planDefaults(), startDate: today(), createdBy: currentSubjectId });
    setPlanOpen(true);
  }

  function openTask() {
    setActionError("");
    setTaskForm({ ...taskDefaults(), startDate: today() });
    setTaskOpen(true);
  }

  function openExecution(task: NursingTask) {
    setActionError("");
    setExecutionTask(task);
    setExecutionForm({ ...executionDefaults(), executor: currentSubjectId });
  }

  const assessmentColumns: Column<NursingAssessment>[] = [
    { key: "assess_date", header: "日期", className: "min-w-[100px]", render: (row) => formatDate(row.assess_date) },
    { key: "assess_type", header: "评估类型", className: "min-w-[130px]", render: (row) => assessmentTypeLabel(row.assess_type) },
    { key: "total_score", header: "分数", className: "w-[80px]", render: (row) => row.total_score ?? "-" },
    { key: "result_level", header: "结果等级", className: "min-w-[100px]", render: (row) => row.result_level || "-" },
    { key: "assessor", header: "评估人", className: "min-w-[100px]", render: (row) => (row.assessor ? (subjectMap.get(row.assessor) ?? row.assessor) : "-") },
    { key: "remark", header: "备注", className: "min-w-[180px]", render: (row) => row.remark || "-" },
  ];

  const taskColumns: Column<NursingTask>[] = [
    { key: "task_type", header: "类型", className: "min-w-[110px]", render: (row) => taskTypeLabel(row.task_type) },
    { key: "description", header: "任务", className: "min-w-[220px]" },
    { key: "frequency_name", header: "频次", className: "min-w-[100px]", render: (row) => row.frequency_name || row.frequency_code || "-" },
    { key: "status", header: "状态", className: "min-w-[100px]", render: (row) => <Badge variant={row.status === "ACTIVE" ? "info" : row.status === "COMPLETED" ? "success" : "default"}>{taskStatusLabel(row.status)}</Badge> },
    {
      key: "executions",
      header: "执行记录",
      className: "min-w-[150px]",
      render: (row) => {
        const latest = executionsByTaskId[row.id]?.[0];
        return latest ? `${executionStatusLabel(latest.status)} · ${formatDateTime(latest.actual_time)}` : "暂无记录";
      },
    },
    {
      key: "actions",
      header: "操作",
      className: "sticky right-0 z-10 min-w-[190px] border-l border-border bg-surface",
      render: (row) => (
        <div className="flex flex-wrap gap-2 whitespace-nowrap">
          {row.status === "ACTIVE" && <Button size="sm" variant="secondary" disabled={savingId === row.id} onClick={() => openExecution(row)}>登记完成</Button>}
          {row.status === "ACTIVE" && <Button size="sm" variant="link" disabled={savingId === row.id} onClick={() => void handleTaskStatus(row, "CANCELLED")}>取消任务</Button>}
        </div>
      ),
    },
  ];

  return (
    <div className="flex flex-1 flex-col space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">照护管理</h2>
          <p className="mt-1 text-sm text-fg-muted">左侧选择长者 → 新增评估 → 制定计划 → 创建任务 → 登记完成</p>
        </div>
        {selectedAdmission && period && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={openAssessment}>新增评估</Button>
            <Button variant="primary" onClick={openPlan}>制定计划</Button>
            <Button variant="secondary" onClick={openTask}>创建任务</Button>
          </div>
        )}
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}
      {actionError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>}

      <div className="flex flex-wrap items-end justify-between gap-3">
        <Input
          label="查询当前入住"
          value={admissionSearchInput}
          onChange={(event) => setAdmissionSearchInput(event.target.value)}
          placeholder="姓名 / 住院号 / 身份证"
          className="w-full sm:w-96"
          autoComplete="off"
        />
        <span className="pb-2 text-xs text-fg-dimmed">
          {admissionSearch ? `匹配 ${admissions.length} 人` : `共 ${admissions.length} 人`}
        </span>
      </div>

      {loading ? (
        <Card><div className="py-16 text-center text-sm text-fg-dimmed">正在加载当前入住长者…</div></Card>
      ) : admissions.length === 0 ? (
        <EmptyState
          icon="🏠"
          title={admissionSearch ? "未找到匹配的入住长者" : "暂无活动入住"}
          description={admissionSearch ? "请尝试姓名、住院号或身份证的其他部分。" : "请先在入住管理为长者办理入住，再开始建立照护记录。"}
        />
      ) : (
        <div className="grid min-h-0 flex-1 gap-6 xl:grid-cols-[280px_minmax(0,1fr)]">
              <Card title="当前入住长者" className="flex flex-col h-full" bodyClassName="flex flex-col min-h-0">
                <div className="flex-1 space-y-2 overflow-y-auto min-h-0">
                  {admissions.map((admission) => (
                <button
                  key={admission.id}
                  type="button"
                  onClick={() => setSelectedEncounterId(admission.id)}
                  className={`w-full rounded-md border px-3 py-3 text-left transition-colors ${selectedEncounterId === admission.id ? "border-accent bg-accent/10" : "border-border hover:bg-surface-alt"}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium text-fg-emphasis">{admission.patientName}</span>
                    <Badge variant="success">在住</Badge>
                  </div>
                  <p className="mt-1 text-xs text-fg-dimmed">住院号：{admission.encounter_no}</p>
                  <p className="mt-1 text-xs text-fg-dimmed">{admission.ward || admission.department || "未设置照护单元"}</p>
                </button>
                  ))}
                </div>
              </Card>

          <div className="min-w-0 space-y-6">
            {selectedAdmission && (
              <Card>
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div>
                    <div className="flex items-center gap-3">
                      <h3 className="text-base font-semibold text-fg-emphasis">{selectedAdmission.patientName}</h3>
                      <Badge variant="success">活动入住</Badge>
                    </div>
                    <p className="mt-2 text-sm text-fg-muted">住院号 {selectedAdmission.encounter_no} · 入住 {formatDate(selectedAdmission.admit_date)}</p>
                    <p className="mt-1 text-sm text-fg-muted">{selectedAdmission.department || "未设置照护单元"} · {selectedAdmission.ward || "未设置房间床位"}</p>
                  </div>
                  {period && <div className="text-right text-sm text-fg-muted"><p>照护周期</p><p className="mt-1 text-fg-emphasis">{formatDate(period.start_date)} 起 · {period.status === "ACTIVE" ? "进行中" : period.status}</p></div>}
                </div>
              </Card>
            )}

            {!detailLoading && !period && selectedAdmission && (
              <Card>
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <span className="text-4xl">🧑‍⚕️</span>
                  <h3 className="mt-4 text-base font-semibold text-fg-emphasis">尚未建立照护周期</h3>
                  <p className="mt-2 max-w-md text-sm text-fg-muted">为入住长者建立长期照护周期后，才能保存评估、照护计划和日常任务。</p>
                  <Button className="mt-5" loading={saving} onClick={() => void handleCreatePeriod()}>建立照护档案</Button>
                </div>
              </Card>
            )}

            {detailLoading && <Card><div className="py-12 text-center text-sm text-fg-dimmed">正在加载照护档案…</div></Card>}

            {!detailLoading && period && (
              <>
                <div className="grid gap-4 sm:grid-cols-3">
                  <Card><p className="text-sm text-fg-muted">护理评估</p><p className="mt-2 text-2xl font-semibold text-fg-emphasis">{assessments.length}</p><p className="mt-1 text-xs text-fg-dimmed">已记录评估</p></Card>
                  <Card><p className="text-sm text-fg-muted">照护计划</p><p className="mt-2 text-2xl font-semibold text-fg-emphasis">{plans.filter((plan) => plan.status === "ACTIVE").length}</p><p className="mt-1 text-xs text-fg-dimmed">个执行中计划</p></Card>
                  <Card><p className="text-sm text-fg-muted">待执行任务</p><p className="mt-2 text-2xl font-semibold text-fg-emphasis">{tasks.filter((task) => task.status === "ACTIVE").length}</p><p className="mt-1 text-xs text-fg-dimmed">个活动任务</p></Card>
                </div>

                <div className="flex flex-wrap gap-1 border-b border-border">
                  {([ ["overview", "概览"], ["assessments", "护理评估"], ["plans", "照护计划"], ["tasks", "任务执行"] ] as [Tab, string][]).map(([tab, label]) => (
                    <button key={tab} type="button" onClick={() => setActiveTab(tab)} className={`border-b-2 px-4 py-3 text-sm font-medium transition-colors ${activeTab === tab ? "border-accent text-accent" : "border-transparent text-fg-muted hover:text-fg"}`}>{label}</button>
                  ))}
                </div>

                {activeTab === "overview" && (
                  <div className="grid gap-6 lg:grid-cols-2">
                    <Card className="min-w-0 overflow-hidden" title="最近评估">
                      <Table className="min-w-[680px]" columns={assessmentColumns.slice(0, 5)} data={assessments.slice(0, 5)} loading={false} emptyMessage="暂无评估记录" />
                    </Card>
                    <Card title="当前照护计划">
                      {plans.filter((plan) => plan.status === "ACTIVE").length === 0 ? <p className="py-8 text-center text-sm text-fg-dimmed">暂无执行中的照护计划</p> : <div className="space-y-3">{plans.filter((plan) => plan.status === "ACTIVE").slice(0, 4).map((plan) => <div key={plan.id} className="rounded-md border border-border p-3"><div className="flex items-center justify-between gap-3"><span className="font-medium text-fg-emphasis">{plan.plan_name}</span><Badge variant="info">执行中</Badge></div><p className="mt-2 text-sm text-fg-muted">{plan.goals || "未填写计划目标"}</p></div>)}</div>}
                    </Card>
                  </div>
                )}

                {activeTab === "assessments" && <Card className="min-w-0 overflow-hidden" title="护理评估记录" actions={<Button size="sm" onClick={openAssessment}>新增评估</Button>}><Table className="min-w-[900px]" columns={assessmentColumns} data={assessments} loading={false} emptyMessage="暂无护理评估，建议先完成入住后的 Barthel 指数和风险评估。" /></Card>}

                {activeTab === "plans" && (
                  <Card title="照护计划" actions={<Button size="sm" onClick={openPlan}>制定计划</Button>}>
                    {plans.length === 0 ? <p className="py-12 text-center text-sm text-fg-dimmed">暂无照护计划，请根据评估结果制定第一份计划。</p> : <div className="space-y-4">{plans.map((plan) => <div key={plan.id} className="rounded-lg border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><div className="flex items-center gap-2"><h4 className="font-semibold text-fg-emphasis">{plan.plan_name}</h4><Badge variant={plan.status === "ACTIVE" ? "info" : plan.status === "COMPLETED" ? "success" : "default"}>{planStatusLabel(plan.status)}</Badge></div><p className="mt-2 text-sm text-fg-muted">目标：{plan.goals || "未填写"}</p><p className="mt-1 text-xs text-fg-dimmed">周期：{formatDate(plan.start_date)} 至 {formatDate(plan.end_date)}</p></div>{plan.status === "ACTIVE" && <div className="flex gap-2"><Button size="sm" variant="secondary" disabled={savingId === plan.id} onClick={() => void handlePlanStatus(plan, "COMPLETED")}>完成计划</Button><Button size="sm" variant="link" disabled={savingId === plan.id} onClick={() => void handlePlanStatus(plan, "DISCONTINUED")}>终止计划</Button></div>}</div>{plan.items && plan.items.length > 0 && <div className="mt-4 space-y-2 border-t border-border pt-3">{plan.items.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-2 text-sm"><span className="text-fg">{item.action}</span><span className="text-fg-dimmed">{item.frequency_name || item.frequency_code || "按需"}{item.duration_days ? ` · ${item.duration_days} 天` : ""}</span></div>)}</div>}</div>)}</div>}
                  </Card>
                )}

                {activeTab === "tasks" && <Card className="min-w-0 overflow-hidden" title="照护任务" actions={<Button size="sm" onClick={openTask}>创建任务</Button>}><Table columns={taskColumns} data={tasks} loading={false} emptyMessage="暂无照护任务，可从计划措施创建或直接添加任务。" /></Card>}
              </>
            )}
          </div>
        </div>
      )}

      <Modal open={assessmentOpen} onClose={() => !saving && setAssessmentOpen(false)} title="新增护理评估">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateAssessment(); }}>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="assessment-type">评估类型</label><select id="assessment-type" className={selectClass} value={assessmentForm.assessType} onChange={(event) => setAssessmentForm((current) => ({ ...current, assessType: event.target.value }))}><option value="BARTHEL">Barthel 指数</option><option value="FALL_RISK">跌倒风险</option><option value="PRESSURE_SORE">压疮风险</option><option value="NUTRITION">营养评估</option><option value="ADMISSION">入住评估</option><option value="PAIN">疼痛评估</option><option value="OTHER">其他评估</option></select></div>
            <Input label="评估日期" type="date" value={assessmentForm.assessDate} onChange={(event) => setAssessmentForm((current) => ({ ...current, assessDate: event.target.value }))} required />
            {mounted ? (
              <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="assessment-assessor">评估人</label><select id="assessment-assessor" className={selectClass} value={assessmentForm.assessor} onChange={(event) => setAssessmentForm((current) => ({ ...current, assessor: event.target.value }))}><option value="">请选择评估人</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}</select></div>
            ) : (
              <Input label="评估人" value="" placeholder="加载中..." onChange={() => {}} />
            )}
            <Input label="总分" type="number" value={assessmentForm.totalScore} onChange={(event) => setAssessmentForm((current) => ({ ...current, totalScore: event.target.value }))} placeholder="可选" />
            <div className="flex flex-col gap-1.5 sm:col-span-2"><label className="text-sm font-medium text-fg-muted" htmlFor="assessment-level">结果等级</label><select id="assessment-level" className={selectClass} value={assessmentForm.resultLevel} onChange={(event) => setAssessmentForm((current) => ({ ...current, resultLevel: event.target.value }))}><option value="">请选择</option><option value="低风险">低风险</option><option value="中风险">中风险</option><option value="高风险">高风险</option><option value="无需干预">无需干预</option></select></div>
          </div>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="assessment-detail">评估详情</label><textarea id="assessment-detail" rows={3} className={textareaClass} value={assessmentForm.detail} onChange={(event) => setAssessmentForm((current) => ({ ...current, detail: event.target.value }))} placeholder="记录主要评估结果、风险因素或量表说明" /></div>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="assessment-remark">备注</label><textarea id="assessment-remark" rows={2} className={textareaClass} value={assessmentForm.remark} onChange={(event) => setAssessmentForm((current) => ({ ...current, remark: event.target.value }))} placeholder="请输入后续关注事项" /></div>
          <div className="flex justify-end gap-3"><Button type="button" variant="ghost" onClick={() => setAssessmentOpen(false)} disabled={saving}>取消</Button><Button type="submit" loading={saving}>保存评估</Button></div>
        </form>
      </Modal>

      <Modal open={planOpen} onClose={() => !saving && setPlanOpen(false)} title="制定照护计划">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreatePlan(); }}>
          <div className="grid gap-4 sm:grid-cols-2"><Input label="计划名称" value={planForm.planName} onChange={(event) => setPlanForm((current) => ({ ...current, planName: event.target.value }))} placeholder="例如：日常生活照护计划" required />{mounted ? (<div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="plan-created-by">制定人</label><select id="plan-created-by" className={selectClass} value={planForm.createdBy} onChange={(event) => setPlanForm((current) => ({ ...current, createdBy: event.target.value }))}><option value="">请选择制定人</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}</select></div>) : (<Input label="制定人" value="" placeholder="加载中..." onChange={() => {}} />)}<Input label="开始日期" type="date" value={planForm.startDate} onChange={(event) => setPlanForm((current) => ({ ...current, startDate: event.target.value }))} /><Input label="结束日期" type="date" value={planForm.endDate} onChange={(event) => setPlanForm((current) => ({ ...current, endDate: event.target.value }))} /></div>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="plan-goals">照护目标</label><textarea id="plan-goals" rows={3} className={textareaClass} value={planForm.goals} onChange={(event) => setPlanForm((current) => ({ ...current, goals: event.target.value }))} placeholder="例如：维持日常生活能力，预防跌倒" /></div>
          <div className="space-y-3"><div className="flex items-center justify-between"><h4 className="text-sm font-semibold text-fg-emphasis">护理措施</h4><Button type="button" size="sm" variant="secondary" onClick={() => setPlanForm((current) => ({ ...current, items: [...current.items, { action: "", frequencyCode: "", frequencyName: "", durationDays: undefined, remark: "" }] }))}>增加措施</Button></div>{planForm.items.map((item, index) => <div key={index} className="rounded-md border border-border p-3"><div className="grid gap-3 grid-cols-1 sm:grid-cols-3"><Input label="措施" value={item.action} onChange={(event) => setPlanForm((current) => ({ ...current, items: current.items.map((planItem, itemIndex) => itemIndex === index ? { ...planItem, action: event.target.value } : planItem) }))} placeholder="例如：协助晨间洗漱" /><Input label="频次" value={item.frequencyName ?? ""} onChange={(event) => setPlanForm((current) => ({ ...current, items: current.items.map((planItem, itemIndex) => itemIndex === index ? { ...planItem, frequencyName: event.target.value } : planItem) }))} placeholder="每日一次" /><Input label="天数" type="number" value={item.durationDays ?? ""} onChange={(event) => setPlanForm((current) => ({ ...current, items: current.items.map((planItem, itemIndex) => itemIndex === index ? { ...planItem, durationDays: event.target.value ? Number(event.target.value) : undefined } : planItem) }))} placeholder="可选" /></div><div className="mt-3 flex items-end gap-3"><div className="flex-1"><Input label="措施备注" value={item.remark ?? ""} onChange={(event) => setPlanForm((current) => ({ ...current, items: current.items.map((planItem, itemIndex) => itemIndex === index ? { ...planItem, remark: event.target.value } : planItem) }))} placeholder="可选" /></div>{planForm.items.length > 1 && <Button type="button" size="sm" variant="link" onClick={() => setPlanForm((current) => ({ ...current, items: current.items.filter((_, itemIndex) => itemIndex !== index) }))}>移除</Button>}</div></div>)}</div>
          <div className="flex justify-end gap-3"><Button type="button" variant="ghost" onClick={() => setPlanOpen(false)} disabled={saving}>取消</Button><Button type="submit" loading={saving}>保存计划</Button></div>
        </form>
      </Modal>

      <Modal open={taskOpen} onClose={() => !saving && setTaskOpen(false)} title="创建照护任务">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateTask(); }}>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="task-plan-item">关联计划措施</label><select id="task-plan-item" className={selectClass} value={taskForm.planItemId} onChange={(event) => setTaskForm((current) => ({ ...current, planItemId: event.target.value }))}><option value="">临时任务（不关联计划）</option>{plans.filter((plan) => plan.status === "ACTIVE").flatMap((plan) => (plan.items ?? []).map((item) => <option key={item.id} value={item.id}>{plan.plan_name} · {item.action}</option>))}</select></div>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="task-type">任务类型</label><select id="task-type" className={selectClass} value={taskForm.taskType} onChange={(event) => setTaskForm((current) => ({ ...current, taskType: event.target.value }))}><option value="LIVING_CARE">生活照料</option><option value="NURSING">护理操作</option><option value="REHABILITATION">康复训练</option><option value="HEALTH_EDUCATION">健康教育</option><option value="OTHER">其他任务</option></select></div>
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="task-description">任务描述</label><textarea id="task-description" rows={3} className={textareaClass} value={taskForm.description} onChange={(event) => setTaskForm((current) => ({ ...current, description: event.target.value }))} placeholder="请输入具体照护任务" required /></div>
          <div className="grid gap-4 sm:grid-cols-2"><div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="task-frequency-code">频次编码</label><select id="task-frequency-code" className={selectClass} value={taskForm.frequencyCode} onChange={(event) => { const code = event.target.value; const name = frequencyOptions.find(([c]) => c === code)?.[1] ?? ""; setTaskForm((current) => ({ ...current, frequencyCode: code, frequencyName: name })); }}><option value="">请选择</option>{frequencyOptions.map(([code, name]) => <option key={code} value={code}>{code} — {name}</option>)}</select></div><Input label="频次说明" value={taskForm.frequencyName} onChange={(event) => setTaskForm((current) => ({ ...current, frequencyName: event.target.value }))} placeholder="例如：每日两次" /><Input label="开始日期" type="date" value={taskForm.startDate} onChange={(event) => setTaskForm((current) => ({ ...current, startDate: event.target.value }))} /><Input label="结束日期" type="date" value={taskForm.endDate} onChange={(event) => setTaskForm((current) => ({ ...current, endDate: event.target.value }))} /></div>
          <div className="flex justify-end gap-3"><Button type="button" variant="ghost" onClick={() => setTaskOpen(false)} disabled={saving}>取消</Button><Button type="submit" loading={saving}>保存任务</Button></div>
        </form>
      </Modal>

      <Modal open={executionTask !== null} onClose={() => !saving && setExecutionTask(null)} title="登记任务完成">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateExecution(); }}>
          <div className="rounded-md bg-surface-alt px-3 py-2 text-sm text-fg-muted">{executionTask?.description}</div>
          <Input label="计划执行时间" type="datetime-local" value={executionForm.plannedTime} onChange={(event) => setExecutionForm((current) => ({ ...current, plannedTime: event.target.value }))} />
          {mounted ? (<div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="execution-executor">执行人</label><select id="execution-executor" className={selectClass} value={executionForm.executor} onChange={(event) => setExecutionForm((current) => ({ ...current, executor: event.target.value }))}><option value="">请选择执行人</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}</select></div>) : (<Input label="执行人" value="" placeholder="加载中..." onChange={() => {}} />)}
          <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="execution-note">执行备注</label><textarea id="execution-note" rows={3} className={textareaClass} value={executionForm.note} onChange={(event) => setExecutionForm((current) => ({ ...current, note: event.target.value }))} placeholder="记录执行情况、异常和长者反馈" /></div>
          <div className="flex justify-end gap-3"><Button type="button" variant="ghost" onClick={() => setExecutionTask(null)} disabled={saving}>取消</Button><Button type="submit" loading={saving}>保存完成记录</Button></div>
        </form>
      </Modal>
    </div>
  );
}
