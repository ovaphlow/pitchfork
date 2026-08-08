import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createCarePlanRevision,
  createNursingAssessment,
  createNursingPlan,
  createNursingRecord,
  createNursingRecordCorrection,
  createNursingTask,
  createNursingTaskExecution,
  enrollElderlyAdmissionCarePeriod,
  getCarePlanRevision,
  getCurrentSession,
  getNursingPlan,
  getNursingRecord,
  listActiveElderlyAdmissions,
  listCarePlanRevisions,
  listIdentitySubjects,
  listNursingAssessments,
  listNursingPlans,
  listNursingServicePeriods,
  listNursingTaskExecutions,
  listNursingTasks,
  listNursingTimeline,
  listNursingTodayExecutions,
  listMedicalOrders,
  listPatients,
  nurseCheckMedicalOrder,
  updateNursingPlanStatus,
  updateNursingTaskExecutionStatus,
  updateNursingTaskStatus,
  updateNursingTaskExecutionStatusWithConsumptions,
  listInventoryStocks,
  listWarehouseOptions,
  listNursingExecutionConsumptions,
  type CarePlanRevisionDetail,
  type CarePlanRevisionListItem,
  type Encounter,
  type IdentitySubject,
  type NursingAssessment,
  type NursingPlan,
  type NursingServicePeriod,
  type NursingTask,
  type NursingTaskExecution,
  type NursingTodayExecution,
  type NursingRecord,
  type NursingTimelineEvent,
  type Patient,
  type InventoryStockAvailability,
  type MedicalOrder,
  type NursingConsumptionInput,
  type NursingExecutionConsumption,
  type WarehouseOption,
} from "@pitchfork/shared/aceso";
import NursingExecutionStatisticsPanel from "./NursingExecutionStatisticsPanel";

type Tab = "overview" | "assessments" | "plans" | "tasks" | "orders" | "timeline";
type MainView = "today" | "resident";

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

interface ConsumptionDraft {
  stock_id: string;
  quantity: number;
  material_name: string;
  material_id: string;
  unit: string;
  available_quantity: number;
}

interface ExecutionForm {
  plannedTime: string;
  executor: string;
  note: string;
}

interface RevisionItemForm {
  action: string;
  frequencyCode: string;
  frequencyName: string;
  durationDays: number | undefined;
  remark: string;
}

interface RevisionForm {
  assessType: string;
  assessDate: string;
  assessor: string;
  totalScore: string;
  resultLevel: string;
  detail: string;
  remark: string;
  planName: string;
  goals: string;
  createdBy: string;
  startDate: string;
  endDate: string;
  items: RevisionItemForm[];
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

function formatOverdueMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "";
  if (minutes < 60) return `已逾期 ${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const remaining = minutes % 60;
  if (remaining === 0) return `已逾期 ${hours} 小时`;
  return `已逾期 ${hours} 小时 ${remaining} 分钟`;
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

const revisionDefaults = (): RevisionForm => ({
  assessType: "BARTHEL",
  assessDate: today(),
  assessor: "",
  totalScore: "",
  resultLevel: "",
  detail: "",
  remark: "",
  planName: "",
  goals: "",
  createdBy: "",
  startDate: today(),
  endDate: "",
  items: [{ action: "", frequencyCode: "", frequencyName: "", durationDays: undefined, remark: "" }],
});

export default function NursingPage() {
  const [admissions, setAdmissions] = useState<ActiveAdmission[]>([]);
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [period, setPeriod] = useState<NursingServicePeriod | null>(null);
  const [assessments, setAssessments] = useState<NursingAssessment[]>([]);
  const [plans, setPlans] = useState<NursingPlan[]>([]);
  const [tasks, setTasks] = useState<NursingTask[]>([]);
  const [medicationOrders, setMedicationOrders] = useState<MedicalOrder[]>([]);
  const [ordersError, setOrdersError] = useState("");
  const [checkingOrderId, setCheckingOrderId] = useState<string | null>(null);
  const [executionsByTaskId, setExecutionsByTaskId] = useState<Record<string, NursingTaskExecution[]>>({});
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [pageError, setPageError] = useState("");
  const [actionError, setActionError] = useState("");
  const [activeTab, setActiveTab] = useState<Tab>("overview");
  const [assessmentOpen, setAssessmentOpen] = useState(false);
  const [planOpen, setPlanOpen] = useState(false);
  const [taskOpen, setTaskOpen] = useState(false);
  // ——— 复评与照护计划修订 ———
  const [revisionOpen, setRevisionOpen] = useState(false);
  const [revisionForm, setRevisionForm] = useState<RevisionForm>(revisionDefaults);
  const [revisionSaving, setRevisionSaving] = useState(false);
  const [revisionError, setRevisionError] = useState("");
  const [revisions, setRevisions] = useState<CarePlanRevisionListItem[]>([]);
  const [revisionDetail, setRevisionDetail] = useState<CarePlanRevisionDetail | null>(null);
  const [revisionDetailOpen, setRevisionDetailOpen] = useState(false);
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
  // ——— 时间线 ———
  const [timelineEvents, setTimelineEvents] = useState<NursingTimelineEvent[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineDateFrom, setTimelineDateFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 30); return d.toISOString().slice(0, 10); });
  const [timelineDateTo, setTimelineDateTo] = useState(today());
  const [timelineEventType, setTimelineEventType] = useState("");
  // ——— 护理记录 ———
  const [recordOpen, setRecordOpen] = useState(false);
  const [recordForm, setRecordForm] = useState({ title: "日常护理记录", content: "", recordTime: new Date().toISOString().slice(0, 16), taskExecutionId: "" });
  const [recordSaving, setRecordSaving] = useState(false);
  const [recordError, setRecordError] = useState("");
  const [selectedRecord, setSelectedRecord] = useState<NursingRecord | null>(null);
  const [recordDetailOpen, setRecordDetailOpen] = useState(false);
  const [correctionOpen, setCorrectionOpen] = useState(false);
  const [correctionForm, setCorrectionForm] = useState({ content: "", recordTime: new Date().toISOString().slice(0, 16) });
  // ——— 今日执行工作台 ———
  const [mainView, setMainView] = useState<MainView>("today");
  const [todayDate, setTodayDate] = useState(today());
  const [todayExecutions, setTodayExecutions] = useState<NursingTodayExecution[]>([]);
  const [todayLoading, setTodayLoading] = useState(true);
  const [todayStatusFilter, setTodayStatusFilter] = useState("");
  const [todayExecutorFilter, setTodayExecutorFilter] = useState("");
  const [todayOverdueOnly, setTodayOverdueOnly] = useState(false);
  const [todayOverdueTotal, setTodayOverdueTotal] = useState(0);
  /** 进入逾期筛选前保存的状态筛选值，用于退出时恢复 */
  const [todayStatusFilterBeforeOverdue, setTodayStatusFilterBeforeOverdue] = useState("");
  const [actionTarget, setActionTarget] = useState<NursingTodayExecution | null>(null);
  const [actionNote, setActionNote] = useState("");
  const [actionSaving, setActionSaving] = useState(false);
  // 操作弹窗类型：null | "complete" | "skip" | "cancel"
  const [actionModal, setActionModal] = useState<"complete" | "skip" | "cancel" | null>(null);
  // ——— 统计面板版本号 ———
  const [statReloadKey, setStatReloadKey] = useState(0);
  // ——— 耗材详情弹窗 ———
  const [consumptionDetailOpen, setConsumptionDetailOpen] = useState(false);
  const [consumptionDetail, setConsumptionDetail] = useState<NursingExecutionConsumption[]>([]);
  const [consumptionDetailLoading, setConsumptionDetailLoading] = useState(false);
  const [consumptionDetailError, setConsumptionDetailError] = useState("");
  // ——— 耗材使用（完成任务时可选） ———
  const [consumeEnabled, setConsumeEnabled] = useState(false);
  const [consumeWarehouse, setConsumeWarehouse] = useState("");
  const [consumeWarehouses, setConsumeWarehouses] = useState<WarehouseOption[]>([]);
  const [consumeStocks, setConsumeStocks] = useState<InventoryStockAvailability[]>([]);
  const [consumeItems, setConsumeItems] = useState<ConsumptionDraft[]>([]);

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
    setOrdersError("");
    try {
      // 精确加载与所选入住记录绑定的周期，绝不按同一患者的第一条活动周期猜测
      const periodResponse = await listNursingServicePeriods({ encounter_id: admission.id, limit: 10 });
      const currentPeriod = periodResponse.records[0] ?? null;
      setPeriod(currentPeriod);
      if (!currentPeriod) {
        setAssessments([]);
        setPlans([]);
        setTasks([]);
        setMedicationOrders([]);
        setExecutionsByTaskId({});
        setRevisions([]);
        return;
      }

      const [assessmentResponse, planResponse, taskResponse, revisionResponse, ordersResponse] = await Promise.all([
        listNursingAssessments({ period_id: currentPeriod.id, limit: 100 }),
        listNursingPlans({ period_id: currentPeriod.id, limit: 100 }),
        listNursingTasks({ period_id: currentPeriod.id, limit: 100 }),
        listCarePlanRevisions(admission.id).catch(() => ({ records: [], meta: { total: 0 } })),
        listMedicalOrders(admission.id, { order_type: "MEDICATION", status: "ACTIVE", limit: 100 }).catch(() => ({ records: [], meta: { total: 0 } })),
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
      setMedicationOrders(ordersResponse.records);
      setExecutionsByTaskId(Object.fromEntries(executionEntries));
      setRevisions(revisionResponse.records);

      // 加载时间线（此时 period 已正确设置，避免跨患者错误）
      void loadTimeline(currentPeriod.id, admission.id);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载照护记录"));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  // ——— 医嘱核对：护士确认用药医嘱后，药房才可见并可发药 ———
  const handleNurseCheck = useCallback(async (order: MedicalOrder) => {
    if (!selectedAdmission || checkingOrderId !== null) return;
    setCheckingOrderId(order.id);
    setOrdersError("");
    try {
      await nurseCheckMedicalOrder(order.id);
      // 核对成功后刷新该入住医嘱与任务/执行数据，保留当前页签与上下文
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setOrdersError(errorMessage(error, "核对失败，请稍后重试"));
    } finally {
      setCheckingOrderId(null);
    }
  }, [selectedAdmission, checkingOrderId, loadResidentData]);

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
      setRevisions([]);
    }
  }, [loadResidentData, selectedAdmission]);

  // ========================================================================
  //  今日执行工作台 — 数据加载与操作
  // ========================================================================

  const loadTodayExecutions = useCallback(async () => {
    setTodayLoading(true);
    setActionError("");
    try {
      const response = await listNursingTodayExecutions({
        date: todayDate,
        status: todayStatusFilter || undefined,
        executor: todayExecutorFilter || undefined,
        period_id: period?.id || undefined,
        overdue: todayOverdueOnly || undefined,
        limit: 100,
      });
      setTodayExecutions(response.records);
      setTodayOverdueTotal(response.meta.overdue_total ?? 0);
    } catch (error) {
      setActionError(errorMessage(error, "无法加载今日执行记录"));
    } finally {
      setTodayLoading(false);
    }
  }, [todayDate, todayStatusFilter, todayExecutorFilter, todayOverdueOnly, period]);

  // ========================================================================
  //  时间线 — 数据加载
  // ========================================================================

  const loadTimeline = useCallback(async (periodId: string, encounterId: string) => {
    setTimelineLoading(true);
    setActionError("");
    try {
      const response = await listNursingTimeline({
        period_id: periodId,
        encounter_id: encounterId,
        date_from: timelineDateFrom || undefined,
        date_to: timelineDateTo || undefined,
        event_type: timelineEventType || undefined,
        limit: 100,
      });
      setTimelineEvents(response.records);
    } catch (error) {
      setActionError(errorMessage(error, "无法加载照护时间线"));
    } finally {
      setTimelineLoading(false);
    }
  }, [timelineDateFrom, timelineDateTo, timelineEventType]);

  // ========================================================================
  //  护理记录 — 操作处理
  // ========================================================================

  async function handleCreateRecord() {
    if (!period || !selectedAdmission || !recordForm.title.trim() || !recordForm.content.trim()) {
      setRecordError("标题和正文不能为空");
      return;
    }
    setRecordSaving(true);
    setRecordError("");
    try {
      const recordTime = recordForm.recordTime ? new Date(recordForm.recordTime).toISOString() : undefined;
      await createNursingRecord({
        period_id: period.id,
        encounter_id: selectedAdmission.id,
        title: recordForm.title.trim(),
        content: recordForm.content.trim(),
        record_time: recordTime,
        task_execution_id: recordForm.taskExecutionId || undefined,
        author: currentSubjectId || undefined,
      });
      setRecordOpen(false);
      setRecordForm({ title: "日常护理记录", content: "", recordTime: new Date().toISOString().slice(0, 16), taskExecutionId: "" });
      // 刷新时间线
      await loadTimeline(period.id, selectedAdmission.id);
    } catch (error) {
      setRecordError(errorMessage(error, "无法保存护理记录"));
    } finally {
      setRecordSaving(false);
    }
  }

  function openRecordCreation(taskExecutionId?: string) {
    setRecordError("");
    setRecordForm({
      title: taskExecutionId ? "执行记录补充" : "日常护理记录",
      content: "",
      recordTime: new Date().toISOString().slice(0, 16),
      taskExecutionId: taskExecutionId ?? "",
    });
    setRecordOpen(true);
  }

  async function openRecordDetail(id: string) {
    setActionError("");
    try {
      const record = await getNursingRecord(id);
      setSelectedRecord(record);
      setRecordDetailOpen(true);
    } catch (error) {
      setActionError(errorMessage(error, "无法加载护理记录详情"));
    }
  }

  function openCorrection(record: NursingRecord) {
    setSelectedRecord(record);
    setCorrectionForm({ content: "", recordTime: new Date().toISOString().slice(0, 16) });
    setCorrectionOpen(true);
  }

  async function handleCreateCorrection() {
    if (!selectedRecord || !correctionForm.content.trim()) {
      setActionError("更正内容不能为空");
      return;
    }
    setRecordSaving(true);
    setActionError("");
    try {
      const recordTime = correctionForm.recordTime ? new Date(correctionForm.recordTime).toISOString() : undefined;
      await createNursingRecordCorrection(selectedRecord.id, {
        content: correctionForm.content.trim(),
        record_time: recordTime,
        author: currentSubjectId || undefined,
      });
      setCorrectionOpen(false);
      setSelectedRecord(null);
      // 刷新时间线
      if (period && selectedAdmission) {
        await loadTimeline(period.id, selectedAdmission.id);
      }
    } catch (error) {
      setActionError(errorMessage(error, "无法创建更正记录"));
    } finally {
      setRecordSaving(false);
    }
  }

  useEffect(() => {
    void loadTodayExecutions();
  }, [loadTodayExecutions]);

  async function handleStartExecution(execution: NursingTodayExecution) {
    setActionSaving(true);
    setActionError("");
    try {
      await updateNursingTaskExecutionStatus(execution.id, "IN_PROGRESS");
      await loadTodayExecutions();
      setStatReloadKey((k) => k + 1);
    } catch (error) {
      setActionError(errorMessage(error, "操作失败"));
    } finally {
      setActionSaving(false);
    }
  }

  function openActionModal(execution: NursingTodayExecution, type: "complete" | "skip" | "cancel") {
    setActionTarget(execution);
    setActionNote("");
    setActionModal(type);
    // 重置耗材状态
    setConsumeEnabled(false);
    setConsumeWarehouse("");
    setConsumeWarehouses([]);
    setConsumeStocks([]);
    setConsumeItems([]);
  }

  async function openConsumptionDetail(execId: string) {
    setConsumptionDetailLoading(true);
    setConsumptionDetailError("");
    setConsumptionDetail([]);
    try {
      const response = await listNursingExecutionConsumptions(execId);
      setConsumptionDetail(response.records);
      setConsumptionDetailOpen(true);
    } catch (error) {
      setConsumptionDetailError(errorMessage(error, "无法加载耗材明细"));
      setConsumptionDetailOpen(true);
    } finally {
      setConsumptionDetailLoading(false);
    }
  }

  async function handleConfirmAction() {
    if (!actionTarget || !actionModal) return;
    // 跳过和取消必须填写原因
    if ((actionModal === "skip" || actionModal === "cancel") && !actionNote.trim()) {
      setActionError("请填写原因");
      return;
    }

    setActionSaving(true);
    setActionError("");
    try {
      const status = actionModal === "complete" ? "COMPLETED"
        : actionModal === "skip" ? "SKIPPED" : "CANCELLED";

      if (actionModal === "complete" && consumeEnabled && consumeItems.length > 0) {
        const invalidItem = consumeItems.find((item) => {
          return item.quantity <= 0 || item.quantity > item.available_quantity;
        });
        if (invalidItem) {
          setActionError("耗材数量必须大于零且不能超过当前可用库存");
          return;
        }
        // 带耗材完成
        const consumptions: NursingConsumptionInput[] = consumeItems.map((item) => ({
          stock_id: item.stock_id,
          quantity: item.quantity,
        }));
        await updateNursingTaskExecutionStatusWithConsumptions(
          actionTarget.id,
          status,
          actionNote.trim() || undefined,
          consumptions,
        );
      } else {
        await updateNursingTaskExecutionStatus(actionTarget.id, status, actionNote.trim() || undefined);
      }
      setActionModal(null);
      setActionTarget(null);
      setActionNote("");
      await loadTodayExecutions();
      setStatReloadKey((k) => k + 1);
    } catch (error) {
      setActionError(errorMessage(error, "操作失败"));
    } finally {
      setActionSaving(false);
    }
  }

  async function loadConsumeWarehouses() {
    try {
      const warehouses = await listWarehouseOptions();
      setConsumeWarehouses(warehouses);
    } catch {
      setActionError("无法加载护理站列表");
    }
  }

  async function loadConsumeStocks(warehouse = consumeWarehouse) {
    if (!warehouse) return;
    try {
      const response = await listInventoryStocks({ warehouse, limit: 200 });
      setConsumeStocks(response.records);
    } catch {
      setActionError("无法加载可用库存");
    }
  }

  function addConsumeItem(stock: InventoryStockAvailability) {
    if (consumeItems.some((item) => item.stock_id === stock.id)) {
      setActionError("该耗材已在列表中");
      return;
    }
    setConsumeItems((current) => [
      ...current,
      {
        stock_id: stock.id,
        quantity: 1,
        material_name: stock.material_name,
        material_id: stock.material_id,
        unit: stock.unit,
        available_quantity: stock.available_quantity,
      },
    ]);
  }

  function removeConsumeItem(stockId: string) {
    setConsumeItems((current) => current.filter((item) => item.stock_id !== stockId));
  }

  function updateConsumeItem(stockId: string, field: string, value: unknown) {
    setConsumeItems((current) =>
      current.map((item) => (item.stock_id === stockId ? { ...item, [field]: value } : item)),
    );
  }

  // ——— 今日执行列定义 ———
  const todayColumns: Column<NursingTodayExecution>[] = useMemo(() => {
    const columns: Column<NursingTodayExecution>[] = [
      { key: "planned_time", header: "计划时间", className: "min-w-[140px]", render: (row) => formatDateTime(row.planned_time) },
      { key: "patient_name", header: "长者", className: "min-w-[80px]", render: (row) => row.patient_name ?? row.patient_id ?? "-" },
      { key: "task_description", header: "任务", className: "min-w-[180px]", render: (row) => row.task_description ?? "-" },
      { key: "task_type", header: "类型", className: "min-w-[100px]", render: (row) => taskTypeLabel(row.task_type ?? "") },
      { key: "executor", header: "执行人", className: "min-w-[90px]", render: (row) => row.executor ? (subjectMap.get(row.executor) ?? row.executor) : "-" },
      { key: "status", header: "状态", className: "min-w-[90px]", render: (row) => (
        <div className="flex flex-wrap items-center gap-1">
          {executionStatusBadge(row.status)}
          {row.is_overdue && row.overdue_minutes != null && (
            <span className="inline-flex items-center rounded-md bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700">
              {formatOverdueMinutes(row.overdue_minutes)}
            </span>
          )}
        </div>
      ) },
      { key: "note", header: "备注", className: "min-w-[120px]", render: (row) => row.note ? <span className="text-xs text-fg-muted max-w-[120px] truncate block" title={row.note}>{row.note}</span> : "-" },
      { key: "consumptions", header: "耗材", className: "min-w-[100px]", render: (row) => {
        if (!row.consumption_summary || row.consumption_summary.count === 0) return <span className="text-xs text-fg-dimmed">—</span>;
        return <button type="button" className="text-xs text-accent hover:underline" onClick={() => void openConsumptionDetail(row.id)} title={`${row.consumption_summary.warehouse} · 共 ${row.consumption_summary.total_cost.toFixed(2)} 元`}>已用 {row.consumption_summary.count} 项</button>;
      } },
    ];
    if (mounted) {
      columns.push({
        key: "actions", header: "操作", className: "min-w-[180px]",
        render: (row) => renderTodayActions(row),
      });
    }
    return columns;
  }, [subjectMap, mounted, todayExecutions, actionSaving]);

  function executionStatusBadge(status: string) {
    const variant = status === "PENDING" ? "warning" as const
      : status === "IN_PROGRESS" ? "info" as const
      : status === "COMPLETED" ? "success" as const
      : status === "SKIPPED" ? "default" as const
      : "default" as const;
    return <Badge variant={variant}>{executionStatusLabel(status)}</Badge>;
  }

  function renderTodayActions(record: NursingTodayExecution) {
    const isBusy = actionSaving;
    const btnClass = "px-2 py-0.5 text-xs rounded";
    switch (record.status) {
      case "PENDING":
        return (
          <div className="flex gap-1">
            <button className={btnClass + " bg-accent/10 text-accent hover:bg-accent/20"} disabled={isBusy} onClick={() => handleStartExecution(record)}>开始</button>
            <button className={btnClass + " bg-amber-100 text-amber-700 hover:bg-amber-200"} disabled={isBusy} onClick={() => openActionModal(record, "skip")}>跳过</button>
            <button className={btnClass + " bg-red-100 text-red-600 hover:bg-red-200"} disabled={isBusy} onClick={() => openActionModal(record, "cancel")}>取消</button>
          </div>
        );
      case "IN_PROGRESS":
        return (
          <div className="flex gap-1">
            <button className={btnClass + " bg-green-100 text-green-700 hover:bg-green-200"} disabled={isBusy} onClick={() => openActionModal(record, "complete")}>完成</button>
            <button className={btnClass + " bg-red-100 text-red-600 hover:bg-red-200"} disabled={isBusy} onClick={() => openActionModal(record, "cancel")}>取消</button>
          </div>
        );
      default:
        return <span className="text-xs text-fg-dimmed">—</span>;
    }
  }

  async function handleCreatePeriod() {
    if (!selectedAdmission) return;
    setSaving(true);
    setActionError("");
    try {
      // 受控恢复：为历史活动入住幂等补建养老照护周期，绝不回退创建社区照护周期
      await enrollElderlyAdmissionCarePeriod(selectedAdmission.id);
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setActionError(errorMessage(error, "无法建立养老照护周期"));
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

  async function handleCreateRevision() {
    if (!selectedAdmission || !period) return;
    if (!revisionForm.assessDate) {
      setRevisionError("评估日期必填");
      return;
    }
    if (!revisionForm.planName.trim()) {
      setRevisionError("计划名称不能为空");
      return;
    }
    if (!revisionForm.startDate) {
      setRevisionError("计划开始日期必填");
      return;
    }
    const totalScore = revisionForm.totalScore.trim() ? Number(revisionForm.totalScore) : undefined;
    if (totalScore !== undefined && Number.isNaN(totalScore)) {
      setRevisionError("评估分数必须是数字");
      return;
    }
    const items = revisionForm.items
      .map((item) => ({
        action: item.action.trim(),
        ...(item.frequencyCode?.trim() ? { frequency_code: item.frequencyCode.trim() } : {}),
        ...(item.frequencyName?.trim() ? { frequency_name: item.frequencyName.trim() } : {}),
        ...(item.durationDays !== undefined && item.durationDays > 0 ? { duration_days: item.durationDays } : {}),
        ...(item.remark?.trim() ? { remark: item.remark.trim() } : {}),
      }))
      .filter((item) => item.action);

    setRevisionSaving(true);
    setRevisionError("");
    try {
      await createCarePlanRevision(selectedAdmission.id, {
        assessment: {
          assess_type: revisionForm.assessType,
          assess_date: revisionForm.assessDate,
          ...(revisionForm.assessor.trim() ? { assessor: revisionForm.assessor.trim() } : {}),
          ...(totalScore !== undefined ? { total_score: totalScore } : {}),
          ...(revisionForm.resultLevel ? { result_level: revisionForm.resultLevel } : {}),
          ...(revisionForm.detail.trim() ? { detail: { note: revisionForm.detail.trim() } } : {}),
          ...(revisionForm.remark.trim() ? { remark: revisionForm.remark.trim() } : {}),
        },
        plan: {
          plan_name: revisionForm.planName.trim(),
          ...(revisionForm.goals.trim() ? { goals: revisionForm.goals.trim() } : {}),
          ...(revisionForm.createdBy.trim() ? { created_by: revisionForm.createdBy.trim() } : {}),
          start_date: revisionForm.startDate,
          ...(revisionForm.endDate ? { end_date: revisionForm.endDate } : {}),
          items,
        },
      });
      setRevisionOpen(false);
      setRevisionForm(revisionDefaults());
      await loadResidentData(selectedAdmission);
    } catch (error) {
      setRevisionError(errorMessage(error, "无法提交复评与计划修订"));
    } finally {
      setRevisionSaving(false);
    }
  }

  function openRevision() {
    setActionError("");
    setRevisionError("");
    setRevisionForm({ ...revisionDefaults(), assessor: currentSubjectId, createdBy: currentSubjectId });
    setRevisionOpen(true);
  }

  async function openRevisionDetail(id: string) {
    setRevisionError("");
    try {
      const detail = await getCarePlanRevision(id);
      setRevisionDetail(detail);
      setRevisionDetailOpen(true);
    } catch (error) {
      setRevisionError(errorMessage(error, "无法加载修订历史详情"));
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
      {/* ——— 顶部栏 + 视图切换 ——— */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">照护管理</h2>
          <p className="mt-1 text-sm text-fg-muted">
            {mainView === "today" ? "查看今日所有长者的待执行任务" : "选择长者 → 评估 → 计划 → 任务 → 完成记录"}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <div className="flex rounded-lg border border-border p-0.5">
            <button type="button" onClick={() => { setMainView("today"); setActionError(""); }} className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${mainView === "today" ? "bg-accent text-white" : "text-fg-muted hover:text-fg"}`}>今日执行</button>
            <button type="button" onClick={() => { setMainView("resident"); setActionError(""); }} className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${mainView === "resident" ? "bg-accent text-white" : "text-fg-muted hover:text-fg"}`}>长者照护档案</button>
          </div>
          {mainView === "resident" && selectedAdmission && period && (
            <>
              <Button variant="secondary" onClick={openAssessment}>新增评估</Button>
              <Button variant="primary" onClick={openPlan}>制定计划</Button>
              <Button variant="secondary" onClick={openTask}>创建任务</Button>
            </>
          )}
        </div>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}
      {actionError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>}

      {/* ======================================================================== */}
      {/*  今日执行工作台 */}
      {/* ======================================================================== */}
      {mainView === "today" && (
        <div className="space-y-4">
          <Card bodyClassName="space-y-3">
            <div className="flex flex-wrap items-end gap-3">
              <Input label="日期" type="date" value={todayDate} onChange={(event) => setTodayDate(event.target.value)} />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="today-status-filter">状态</label>
                <select id="today-status-filter" className={selectClass} value={todayStatusFilter} onChange={(event) => {
                  const nextStatus = event.target.value;
                  if (todayOverdueOnly && ["COMPLETED", "SKIPPED", "CANCELLED"].includes(nextStatus)) return;
                  setTodayStatusFilter(nextStatus);
                }}>
                  <option value="">全部</option>
                  <option value="PENDING">待执行</option>
                  <option value="IN_PROGRESS">执行中</option>
                  <option value="COMPLETED" disabled={todayOverdueOnly}>已完成</option>
                  <option value="SKIPPED" disabled={todayOverdueOnly}>已跳过</option>
                  <option value="CANCELLED" disabled={todayOverdueOnly}>已取消</option>
                </select>
              </div>
              {mounted ? (
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="today-executor-filter">执行人</label>
                  <select id="today-executor-filter" className={selectClass} value={todayExecutorFilter} onChange={(event) => setTodayExecutorFilter(event.target.value)}>
                    <option value="">全部</option>
                    {subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}
                  </select>
                </div>
              ) : null}
              <Button variant="secondary" onClick={() => { void loadTodayExecutions(); }} loading={todayLoading}>刷新</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
              <span>待执行 <Badge variant="warning">{todayExecutions.filter(e => e.status === "PENDING").length}</Badge></span>
              <span>执行中 <Badge variant="info">{todayExecutions.filter(e => e.status === "IN_PROGRESS").length}</Badge></span>
              <span>已完成 <Badge variant="success">{todayExecutions.filter(e => e.status === "COMPLETED").length}</Badge></span>
              <span>已跳过 <Badge variant="default">{todayExecutions.filter(e => e.status === "SKIPPED").length}</Badge></span>
              <span>已取消 <Badge variant="default">{todayExecutions.filter(e => e.status === "CANCELLED").length}</Badge></span>
              <span className="mx-1 h-4 w-px bg-border" />
              {todayOverdueOnly ? (
                <button type="button" onClick={() => { setTodayOverdueOnly(false); setTodayStatusFilter(todayStatusFilterBeforeOverdue); }} className="inline-flex items-center gap-1 rounded-md bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 hover:bg-red-200 transition-colors">
                  显示全部
                </button>
              ) : todayOverdueTotal === 0 ? (
                <span className="inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium text-fg-dimmed opacity-50 cursor-not-allowed">
                  无逾期
                </span>
              ) : (
                <button type="button" onClick={() => { setTodayStatusFilterBeforeOverdue(todayStatusFilter); setTodayOverdueOnly(true); if (["COMPLETED", "SKIPPED", "CANCELLED"].includes(todayStatusFilter)) setTodayStatusFilter(""); }} className="inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium transition-colors hover:bg-red-50">
                  逾期 <Badge variant="danger">{todayOverdueTotal}</Badge>
                </button>
              )}
            </div>
          </Card>

          {/* ——— 工作量统计面板 ——— */}
          <NursingExecutionStatisticsPanel subjects={subjects} reloadKey={statReloadKey} />

          <Card className="min-w-0 overflow-hidden">
            <Table columns={todayColumns} data={todayExecutions} loading={todayLoading} emptyMessage="今日暂无待执行任务，所有照护任务已处理或尚未到计划时间。" />
          </Card>
        </div>
      )}

      {/* ======================================================================== */}
      {/*  长者照护档案 */}
      {/* ======================================================================== */}
      {mainView === "resident" && (
        <>
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
                  {period && <div data-period-summary data-period-id={period.id} className="text-right text-sm text-fg-muted"><p>照护周期</p><p className="mt-1 text-fg-emphasis">{formatDate(period.start_date)} 起 · {period.status === "ACTIVE" ? "进行中" : period.status}</p><p className="mt-1">服务类型：{period.service_type}</p><p className="mt-1">关联入住：{selectedAdmission.encounter_no}</p></div>}
                </div>
              </Card>
            )}

            {!detailLoading && !period && selectedAdmission && (
              <Card>
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <span className="text-4xl">🧑‍⚕️</span>
                  <h3 className="mt-4 text-base font-semibold text-fg-emphasis">尚未建立养老照护周期</h3>
                  <p className="mt-2 max-w-md text-sm text-fg-muted">为入住长者建立与本次入住绑定的养老照护周期后，才能保存评估、照护计划和日常任务。</p>
                  <Button className="mt-5" loading={saving} onClick={() => void handleCreatePeriod()}>建立养老照护周期</Button>
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
                  {([ ["overview", "概览"], ["assessments", "护理评估"], ["plans", "照护计划"], ["tasks", "任务执行"], ["orders", "医嘱核对"], ["timeline", "照护时间线"] ] as [Tab, string][]).map(([tab, label]) => (
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
                  <Card title="照护计划" actions={<><Button size="sm" variant="secondary" onClick={openRevision}>复评并修订计划</Button><Button size="sm" onClick={openPlan}>制定计划</Button></>}>
                    {plans.length === 0 ? <p className="py-12 text-center text-sm text-fg-dimmed">暂无照护计划，请根据评估结果制定第一份计划。</p> : <div className="space-y-4">{plans.map((plan) => <div key={plan.id} className="rounded-lg border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><div className="flex items-center gap-2"><h4 className="font-semibold text-fg-emphasis">{plan.plan_name}</h4><Badge variant={plan.status === "ACTIVE" ? "info" : plan.status === "COMPLETED" ? "success" : "default"}>{planStatusLabel(plan.status)}</Badge></div><p className="mt-2 text-sm text-fg-muted">目标：{plan.goals || "未填写"}</p><p className="mt-1 text-xs text-fg-dimmed">周期：{formatDate(plan.start_date)} 至 {formatDate(plan.end_date)}</p></div>{plan.status === "ACTIVE" && <div className="flex gap-2"><Button size="sm" variant="secondary" disabled={savingId === plan.id} onClick={() => void handlePlanStatus(plan, "COMPLETED")}>完成计划</Button><Button size="sm" variant="link" disabled={savingId === plan.id} onClick={() => void handlePlanStatus(plan, "DISCONTINUED")}>终止计划</Button></div>}</div>{plan.items && plan.items.length > 0 && <div className="mt-4 space-y-2 border-t border-border pt-3">{plan.items.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-2 text-sm"><span className="text-fg">{item.action}</span><span className="text-fg-dimmed">{item.frequency_name || item.frequency_code || "按需"}{item.duration_days ? ` · ${item.duration_days} 天` : ""}</span></div>)}</div>}</div>)}</div>}
                    {/* 修订历史：只读展示版本与终态，不提供正文编辑或删除 */}
                    {revisions.length > 0 && (
                      <div className="mt-6 border-t border-border pt-4">
                        <div className="flex items-center justify-between">
                          <h4 className="text-sm font-semibold text-fg-emphasis">修订历史（复评）</h4>
                          <span className="text-xs text-fg-dimmed">共 {revisions.length} 次修订</span>
                        </div>
                        <div className="mt-3 space-y-2">
                          {revisions.map((rev) => (
                            <button
                              key={rev.id}
                              type="button"
                              onClick={() => void openRevisionDetail(rev.id)}
                              className="flex w-full flex-wrap items-center justify-between gap-2 rounded-md border border-border px-3 py-2 text-left text-sm transition-colors hover:bg-surface-alt"
                            >
                              <span className="flex items-center gap-2">
                                <Badge variant="warning">修订 {rev.revision_no}</Badge>
                                <span className="font-medium text-fg-emphasis">{rev.new_plan.plan_name}</span>
                                <Badge variant={rev.new_plan.status === "ACTIVE" ? "info" : "default"}>{planStatusLabel(rev.new_plan.status)}</Badge>
                              </span>
                              <span className="text-xs text-fg-dimmed">
                                {assessmentTypeLabel(rev.assessment.assess_type ?? "")} · {formatDate(rev.assessment.assess_date)}
                                {rev.previous_plan ? ` · 前版「${rev.previous_plan.plan_name}」${planStatusLabel(rev.previous_plan.status)}` : ""}
                              </span>
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </Card>
                )}

                {activeTab === "tasks" && <Card className="min-w-0 overflow-hidden" title="照护任务" actions={<Button size="sm" onClick={openTask}>创建任务</Button>}><Table columns={taskColumns} data={tasks} loading={false} emptyMessage="暂无照护任务，可从计划措施创建或直接添加任务。" /></Card>}

                {activeTab === "timeline" && (
                  <div className="space-y-4">
                    <Card bodyClassName="space-y-3">
                      <div className="flex flex-wrap items-end gap-3">
                        <Input label="开始日期" type="date" value={timelineDateFrom} onChange={(event) => setTimelineDateFrom(event.target.value)} />
                        <Input label="结束日期" type="date" value={timelineDateTo} onChange={(event) => setTimelineDateTo(event.target.value)} />
                        <div className="flex flex-col gap-1.5">
                          <label className="text-sm font-medium text-fg-muted" htmlFor="tl-type">事件类型</label>
                          <select id="tl-type" className={selectClass} value={timelineEventType} onChange={(event) => setTimelineEventType(event.target.value)}>
                            <option value="">全部</option>
                            <option value="NURSING_RECORD">护理记录</option>
                            <option value="TASK_EXECUTION">任务执行</option>
                            <option value="ASSESSMENT">护理评估</option>
                            <option value="CARE_PLAN">照护计划</option>
                            <option value="TASK">照护任务</option>
                          </select>
                        </div>
                        <Button variant="secondary" onClick={() => openRecordCreation()} loading={recordSaving}>新增护理记录</Button>
                        <Button variant="link" onClick={() => { if (period && selectedAdmission) void loadTimeline(period.id, selectedAdmission.id); }} loading={timelineLoading}>刷新</Button>
                      </div>
                    </Card>

                    <Card className="min-w-0" title="事件时间线">
                      {timelineLoading ? (
                        <div className="py-16 text-center text-sm text-fg-dimmed">正在加载照护时间线…</div>
                      ) : timelineEvents.length === 0 ? (
                        <div className="py-16 text-center text-sm text-fg-dimmed">目前时间线暂无事件，请先完成评估、制定照护计划或创建护理记录。</div>
                      ) : (
                        <div className="space-y-0">
                          {timelineEvents.map((event, idx) => (
                            <div key={event.id ?? idx} className="relative flex gap-4 pb-6 last:pb-0">
                              {/* 时间线竖线 */}
                              <div className="flex flex-col items-center">
                                <div className={`z-10 flex h-4 w-4 items-center justify-center rounded-full border-2 ${
                                  event.event_type === "NURSING_RECORD" ? "border-accent bg-accent/20"
                                  : event.event_type === "TASK_EXECUTION" ? "border-green-500 bg-green-100"
                                  : event.event_type === "ASSESSMENT" ? "border-blue-500 bg-blue-100"
                                  : event.event_type === "CARE_PLAN" ? "border-purple-500 bg-purple-100"
                                  : "border-gray-400 bg-gray-100"
                                }`} />
                                {idx < timelineEvents.length - 1 && <div className="w-px flex-1 bg-border" />}
                              </div>
                              {/* 事件卡片 */}
                              <div className="min-w-0 flex-1 rounded-lg border border-border bg-surface p-3">
                                <div className="flex flex-wrap items-start justify-between gap-2">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <Badge variant={
                                      event.event_type === "NURSING_RECORD" ? "info" as const
                                      : event.event_type === "TASK_EXECUTION" ? "success" as const
                                      : event.event_type === "ASSESSMENT" ? "warning" as const
                                      : "default" as const
                                    }>{
                                      event.event_type === "NURSING_RECORD" ? (event.metadata?.record_kind === "CORRECTION" ? "更正" : "护理记录")
                                      : event.event_type === "TASK_EXECUTION" ? "任务执行"
                                      : event.event_type === "ASSESSMENT" ? "护理评估"
                                      : event.event_type === "CARE_PLAN" ? "照护计划"
                                      : "照护任务"
                                    }</Badge>
                                    <span className="text-xs text-fg-dimmed">{formatDateTime(event.occurred_at)}</span>
                                  </div>
                                  {event.actor && <span className="text-xs text-fg-muted">执行人：{subjectMap.get(event.actor) ?? event.actor}</span>}
                                </div>
                                <p className="mt-2 text-sm font-medium text-fg-emphasis">{event.title}</p>
                                {event.summary && <p className="mt-1 text-xs text-fg-muted line-clamp-2">{event.summary}</p>}
                                <div className="mt-2 flex flex-wrap gap-2">
                                  {event.event_type === "NURSING_RECORD" && (
                                    <Button size="sm" variant="link" onClick={() => { const recordId = event.source.id; void openRecordDetail(recordId); }}>查看详情</Button>
                                  )}
                                  {event.event_type === "NURSING_RECORD" && event.metadata?.record_kind !== "CORRECTION" && !event.metadata?.corrects_record_id && (
                                    <Button size="sm" variant="link" onClick={() => {
                                      // Parse back the record ID from event ID
                                      const recordId = event.source.id;
                                      void getNursingRecord(recordId).then((r) => openCorrection(r)).catch(() => {});
                                    }}>更正记录</Button>
                                  )}
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </Card>
                  </div>
                )}

                {activeTab === "orders" && (
                  <Card className="min-w-0" title="医嘱核对" actions={detailLoading ? undefined : <Button variant="link" onClick={() => { if (selectedAdmission) void loadResidentData(selectedAdmission); }}>刷新</Button>}>
                    {ordersError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{ordersError}</div>}
                    {detailLoading ? (
                      <div className="py-16 text-center text-sm text-fg-dimmed">正在加载医嘱…</div>
                    ) : medicationOrders.length === 0 ? (
                      <div className="py-16 text-center text-sm text-fg-dimmed">暂无执行中的用药医嘱。医生下达用药医嘱后在此核对，核对确认后药房才可见并可发药。</div>
                    ) : (
                      <div className="space-y-4">
                        {medicationOrders.map((order) => {
                          const details = order.order_details ?? {};
                          const checked = order.nurse_checked_by != null && order.nurse_checked_at != null;
                          return (
                            <div key={order.id} className="rounded-lg border border-border p-4">
                              <div className="flex flex-wrap items-start justify-between gap-3">
                                <div className="min-w-0 flex-1">
                                  <div className="flex flex-wrap items-center gap-2">
                                    <h4 className="font-semibold text-fg-emphasis">{order.order_content}</h4>
                                    <Badge variant={checked ? "success" as const : "warning" as const}>{checked ? "已核对" : "待核对"}</Badge>
                                    <Badge variant={order.order_class === "TEMPORARY" ? "warning" as const : "info" as const}>{order.order_class === "LONG_TERM" ? "长期医嘱" : order.order_class === "TEMPORARY" ? "临时医嘱" : "-"}</Badge>
                                  </div>
                                  <div className="mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
                                    {details.dosage != null && details.dosage !== "" && <p><span className="text-fg-dimmed">剂量：</span>{String(details.dosage)}{details.unit != null && details.unit !== "" ? ` ${String(details.unit)}` : ""}</p>}
                                    {details.route != null && details.route !== "" && <p><span className="text-fg-dimmed">给药途径：</span>{String(details.route)}</p>}
                                    <p><span className="text-fg-dimmed">频次：</span>{details.frequency_name != null && details.frequency_name !== "" ? String(details.frequency_name) : details.frequency_code != null && details.frequency_code !== "" ? String(details.frequency_code) : "按需"}</p>
                                    <p><span className="text-fg-dimmed">医生：</span>{order.doctor || "-"}</p>
                                    <p><span className="text-fg-dimmed">开始时间：</span>{formatDateTime(order.start_time)}</p>
                                    <p><span className="text-fg-dimmed">结束时间：</span>{formatDateTime(order.end_time)}</p>
                                    <p className="sm:col-span-2"><span className="text-fg-dimmed">核对：</span>{checked ? `${subjectMap.get(order.nurse_checked_by ?? "") ?? order.nurse_checked_by ?? "-"} · ${formatDateTime(order.nurse_checked_at)}` : "尚未核对，核对确认后药房才可发药"}</p>
                                  </div>
                                </div>
                                {!checked && (
                                  <Button size="sm" loading={checkingOrderId === order.id} disabled={checkingOrderId !== null} onClick={() => void handleNurseCheck(order)}>确认核对</Button>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </Card>
                )}
              </>
            )}
          </div>
        </div>
      )}
          </>
        )}

      {/* ——— 今日执行操作弹窗 ——— */}
      <Modal open={actionModal !== null} onClose={() => { if (!actionSaving) { setActionModal(null); setActionTarget(null); setActionNote(""); } }} title={actionModal === "complete" ? "完成任务" : actionModal === "skip" ? "跳过任务" : "取消任务"}>
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleConfirmAction(); }}>
          <div className="rounded-md bg-surface-alt px-3 py-2 text-sm text-fg-muted">
            {actionTarget?.task_description ?? "-"}
            {actionTarget?.patient_name && <span className="ml-2 text-fg-dimmed">— {actionTarget.patient_name}</span>}
          </div>
          {(actionModal === "skip" || actionModal === "cancel") && (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="action-note">
                {actionModal === "skip" ? "跳过原因" : "取消原因"} <span className="text-danger">*</span>
              </label>
              <textarea id="action-note" rows={3} className={textareaClass} value={actionNote} onChange={(event) => setActionNote(event.target.value)} placeholder={actionModal === "skip" ? "例如：长者拒绝、临时外出" : "例如：医嘱变更、不再需要"} required />
            </div>
          )}
          {actionModal === "complete" && (
            <div className="space-y-3">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="action-note">执行备注</label>
                <textarea id="action-note" rows={2} className={textareaClass} value={actionNote} onChange={(event) => setActionNote(event.target.value)} placeholder="记录执行情况、异常和长者反馈（可选）" />
              </div>
              {/* 耗材使用 */}
              <div className="rounded-md border border-border p-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium text-fg-muted">耗材使用（可选）</label>
                  <label className="flex items-center gap-2 text-sm text-fg-muted">
                    <input type="checkbox" checked={consumeEnabled} onChange={(event) => {
                      setConsumeEnabled(event.target.checked);
                      setConsumeItems([]);
                      setConsumeWarehouse("");
                      if (event.target.checked) void loadConsumeWarehouses();
                    }} />
                    本次使用耗材
                  </label>
                </div>
                {consumeEnabled && (
                  <div className="mt-3 space-y-3">
                    <div className="flex flex-col gap-1.5">
                      <label className="text-xs text-fg-muted" htmlFor="consume-warehouse">护理站/仓库</label>
                      <select id="consume-warehouse" className={selectClass} value={consumeWarehouse} onChange={(event) => { const warehouse = event.target.value; setConsumeWarehouse(warehouse); setConsumeStocks([]); setConsumeItems([]); if (warehouse) void loadConsumeStocks(warehouse); }}>
                        <option value="">选择护理站</option>
                        {consumeWarehouses.map((wh) => <option key={wh.code} value={wh.code}>{wh.name}</option>)}
                      </select>
                    </div>
                    {consumeWarehouse && (
                      <div className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between">
                          <label className="text-xs text-fg-muted">可用耗材</label>
                          <button type="button" className="text-xs text-accent hover:underline" onClick={() => void loadConsumeStocks()}>刷新</button>
                        </div>
                        {consumeStocks.length > 0 && (
                          <div className="max-h-40 overflow-y-auto space-y-1">
                            {consumeStocks.map((stock) => (
                              <div key={stock.id} className="flex items-center justify-between rounded bg-surface-alt px-2 py-1 text-xs">
                                <span className="min-w-0 truncate">{stock.material_name}{stock.batch_no ? ` (${stock.batch_no})` : ""}</span>
                                <span className="shrink-0 text-fg-dimmed">可用 {stock.available_quantity} {stock.unit}{stock.expiry_date ? ` · ${stock.expiry_date} 到期` : ""}</span>
                                <button type="button" className="ml-2 text-accent hover:underline" onClick={() => addConsumeItem(stock)}>添加</button>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                    {consumeItems.length > 0 && (
                      <div className="space-y-2">
                        <label className="text-xs font-medium text-fg-muted">已选耗材</label>
                        {consumeItems.map((item) => (
                          <div key={item.stock_id} className="flex flex-wrap items-center gap-2 rounded bg-surface-alt px-2 py-1.5 text-xs">
                            <span className="min-w-[80px] truncate">{item.material_name}</span>
                            <input
                              type="number" min={0.000001} step={0.000001}
                              className="w-20 rounded border border-border px-1 py-0.5 text-xs"
                              value={item.quantity}
                              onChange={(event) => {
                                const val = Number(event.target.value);
                                updateConsumeItem(item.stock_id, "quantity", val > 0 ? val : 1);
                              }}
                            />
                            <span className="text-fg-dimmed">{item.unit}</span>
                            {item.quantity > item.available_quantity && (
                              <span className="w-full text-danger sm:w-auto">超过可用库存 {item.available_quantity} {item.unit}</span>
                            )}
                            <button type="button" className="text-danger hover:underline" onClick={() => removeConsumeItem(item.stock_id)}>移除</button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}
          <div className="flex justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => { setActionModal(null); setActionTarget(null); setActionNote(""); }} disabled={actionSaving}>取消</Button>
            <Button type="submit" loading={actionSaving}>
              {actionModal === "complete" ? "确认完成" : actionModal === "skip" ? "确认跳过" : "确认取消"}
            </Button>
          </div>
          {actionError && <p className="text-sm text-danger">{actionError}</p>}
        </form>
      </Modal>

      {/* ——— 耗材详情弹窗 ——— */}
      <Modal open={consumptionDetailOpen} onClose={() => { setConsumptionDetailOpen(false); setConsumptionDetail([]); }} title="耗材明细">
        {consumptionDetailError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{consumptionDetailError}</div>}
        {consumptionDetailLoading ? (
          <div className="py-4 text-center text-sm text-fg-muted">加载中...</div>
        ) : consumptionDetail.length === 0 ? (
          <div className="py-4 text-center text-sm text-fg-muted">暂无耗材使用记录</div>
        ) : (
          <div className="space-y-2">
            {consumptionDetail.map((item) => (
              <div key={item.id} className="rounded-md bg-surface-alt px-3 py-2 text-sm">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="font-medium text-fg">{item.material_name ?? item.material_id}</div>
                    <div className="mt-0.5 text-xs text-fg-muted">
                      {item.warehouse}
                      {item.lot_id ? ` · ${item.lot_id.slice(0, 8)}...` : ""}
                    </div>
                  </div>
                  <div className="text-right text-xs text-fg-muted">
                    <div>
                      {item.quantity} {item.unit}
                    </div>
                    {item.batch_no && <div className="mt-0.5">批次：{item.batch_no}</div>}
                    {item.total_cost != null && <div className="mt-0.5">￥{item.total_cost.toFixed(2)}</div>}
                  </div>
                </div>
              </div>
            ))}
            <div className="pt-1 text-right text-xs text-fg-dimmed">共 {consumptionDetail.length} 项</div>
          </div>
        )}
        <div className="mt-4 flex justify-end">
          <Button type="button" variant="ghost" onClick={() => { setConsumptionDetailOpen(false); setConsumptionDetail([]); }}>关闭</Button>
        </div>
      </Modal>

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

      <Modal open={revisionOpen} onClose={() => { if (!revisionSaving) { setRevisionOpen(false); setRevisionError(""); } }} title="复评并修订照护计划">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateRevision(); }}>
          <div className="rounded-md bg-surface-alt px-3 py-2 text-sm text-fg-muted">
            复评完成后当前活动计划将转为已终止，并生成新版本计划及其护理任务。
          </div>
          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">复评信息</h4>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-type">评估类型</label><select id="revision-type" className={selectClass} value={revisionForm.assessType} onChange={(event) => setRevisionForm((current) => ({ ...current, assessType: event.target.value }))}><option value="BARTHEL">Barthel 指数</option><option value="FALL_RISK">跌倒风险</option><option value="PRESSURE_SORE">压疮风险</option><option value="NUTRITION">营养评估</option><option value="PAIN">疼痛评估</option><option value="HOME_ENVIRONMENT">居家环境</option><option value="OTHER">其他评估</option></select></div>
              <Input label="评估日期" type="date" value={revisionForm.assessDate} onChange={(event) => setRevisionForm((current) => ({ ...current, assessDate: event.target.value }))} required />
              {mounted ? (
                <div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-assessor">评估人</label><select id="revision-assessor" className={selectClass} value={revisionForm.assessor} onChange={(event) => setRevisionForm((current) => ({ ...current, assessor: event.target.value }))}><option value="">请选择评估人</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}</select></div>
              ) : (
                <Input label="评估人" value="" placeholder="加载中..." onChange={() => {}} />
              )}
              <Input label="总分" type="number" value={revisionForm.totalScore} onChange={(event) => setRevisionForm((current) => ({ ...current, totalScore: event.target.value }))} placeholder="可选" />
              <div className="flex flex-col gap-1.5 sm:col-span-2"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-level">结果等级</label><select id="revision-level" className={selectClass} value={revisionForm.resultLevel} onChange={(event) => setRevisionForm((current) => ({ ...current, resultLevel: event.target.value }))}><option value="">请选择</option><option value="低风险">低风险</option><option value="中风险">中风险</option><option value="高风险">高风险</option><option value="无需干预">无需干预</option></select></div>
            </div>
            <div className="mt-3 flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-detail">评估详情</label><textarea id="revision-detail" rows={2} className={textareaClass} value={revisionForm.detail} onChange={(event) => setRevisionForm((current) => ({ ...current, detail: event.target.value }))} placeholder="记录主要评估结果、风险因素或量表说明" /></div>
          </div>
          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">新计划版本</h4>
            <div className="mt-3 grid gap-4 sm:grid-cols-2"><Input label="计划名称" value={revisionForm.planName} onChange={(event) => setRevisionForm((current) => ({ ...current, planName: event.target.value }))} placeholder="例如：第二阶段照护计划" required />{mounted ? (<div className="flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-created-by">制定人</label><select id="revision-created-by" className={selectClass} value={revisionForm.createdBy} onChange={(event) => setRevisionForm((current) => ({ ...current, createdBy: event.target.value }))}><option value="">请选择制定人</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.display_name}</option>)}</select></div>) : (<Input label="制定人" value="" placeholder="加载中..." onChange={() => {}} />)}<Input label="开始日期" type="date" value={revisionForm.startDate} onChange={(event) => setRevisionForm((current) => ({ ...current, startDate: event.target.value }))} required /><Input label="结束日期" type="date" value={revisionForm.endDate} onChange={(event) => setRevisionForm((current) => ({ ...current, endDate: event.target.value }))} /></div>
            <div className="mt-3 flex flex-col gap-1.5"><label className="text-sm font-medium text-fg-muted" htmlFor="revision-goals">照护目标</label><textarea id="revision-goals" rows={2} className={textareaClass} value={revisionForm.goals} onChange={(event) => setRevisionForm((current) => ({ ...current, goals: event.target.value }))} placeholder="例如：提高日常活动能力，预防跌倒" /></div>
            <div className="mt-3 space-y-3"><div className="flex items-center justify-between"><h5 className="text-sm font-medium text-fg-emphasis">护理措施</h5><Button type="button" size="sm" variant="secondary" onClick={() => setRevisionForm((current) => ({ ...current, items: [...current.items, { action: "", frequencyCode: "", frequencyName: "", durationDays: undefined, remark: "" }] }))}>增加措施</Button></div>{revisionForm.items.map((item, index) => <div key={index} className="rounded-md border border-border p-3"><div className="grid gap-3 grid-cols-1 sm:grid-cols-3"><Input label="措施" value={item.action} onChange={(event) => setRevisionForm((current) => ({ ...current, items: current.items.map((revisionItem, itemIndex) => itemIndex === index ? { ...revisionItem, action: event.target.value } : revisionItem) }))} placeholder="例如：每日协助步行训练" /><div className="flex flex-col gap-1.5"><label className="text-xs text-fg-muted" htmlFor={`revision-freq-${index}`}>频次</label><select id={`revision-freq-${index}`} className={selectClass} value={item.frequencyCode} onChange={(event) => { const code = event.target.value; const name = frequencyOptions.find(([c]) => c === code)?.[1] ?? ""; setRevisionForm((current) => ({ ...current, items: current.items.map((revisionItem, itemIndex) => itemIndex === index ? { ...revisionItem, frequencyCode: code, frequencyName: name } : revisionItem) })); }}><option value="">请选择</option>{frequencyOptions.map(([code, name]) => <option key={code} value={code}>{code} — {name}</option>)}</select></div><Input label="天数" type="number" value={item.durationDays ?? ""} onChange={(event) => setRevisionForm((current) => ({ ...current, items: current.items.map((revisionItem, itemIndex) => itemIndex === index ? { ...revisionItem, durationDays: event.target.value ? Number(event.target.value) : undefined } : revisionItem) }))} placeholder="可选" /></div><div className="mt-3 flex items-end gap-3"><div className="flex-1"><Input label="措施备注" value={item.remark ?? ""} onChange={(event) => setRevisionForm((current) => ({ ...current, items: current.items.map((revisionItem, itemIndex) => itemIndex === index ? { ...revisionItem, remark: event.target.value } : revisionItem) }))} placeholder="可选" /></div>{revisionForm.items.length > 1 && <Button type="button" size="sm" variant="link" onClick={() => setRevisionForm((current) => ({ ...current, items: current.items.filter((_, itemIndex) => itemIndex !== index) }))}>移除</Button>}</div></div>)}</div>
          </div>
          {revisionError && <p className="text-sm text-danger">{revisionError}</p>}
          <div className="flex justify-end gap-3"><Button type="button" variant="ghost" onClick={() => { setRevisionOpen(false); setRevisionError(""); }} disabled={revisionSaving}>取消</Button><Button type="submit" loading={revisionSaving}>提交复评并修订</Button></div>
        </form>
      </Modal>

      {/* ——— 修订历史详情 ——— */}
      <Modal open={revisionDetailOpen} onClose={() => { setRevisionDetailOpen(false); setRevisionDetail(null); }} title="修订历史详情">
        {revisionDetail ? (
          <div className="space-y-4">
            <div className="rounded-md bg-surface-alt px-3 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="warning">修订 {revisionDetail.revision_no}</Badge>
                <span className="text-xs text-fg-dimmed">{formatDateTime(revisionDetail.created_at)}</span>
                <span className="text-xs text-fg-dimmed">计划 {revisionDetail.plan.id.slice(0, 8)}…</span>
              </div>
            </div>
            <div>
              <h4 className="text-sm font-semibold text-fg-emphasis">复评</h4>
              <div className="mt-2 rounded-md border border-border p-3 text-sm">
                <div className="flex flex-wrap gap-x-6 gap-y-1 text-fg-muted">
                  <span>类型：<span className="text-fg">{assessmentTypeLabel(revisionDetail.assessment.assess_type)}</span></span>
                  <span>日期：<span className="text-fg">{formatDate(revisionDetail.assessment.assess_date)}</span></span>
                  <span>分数：<span className="text-fg">{revisionDetail.assessment.total_score ?? "-"}</span></span>
                  <span>结果等级：<span className="text-fg">{revisionDetail.assessment.result_level || "-"}</span></span>
                </div>
                {revisionDetail.assessment.remark && <p className="mt-2 text-xs text-fg-muted">备注：{revisionDetail.assessment.remark}</p>}
              </div>
            </div>
            {revisionDetail.previous_plan && (
              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">上一版计划（已终止）</h4>
                <div className="mt-2 rounded-md border border-border p-3">
                  <div className="flex flex-wrap items-center gap-2"><span className="font-medium text-fg-emphasis">{revisionDetail.previous_plan.plan_name}</span><Badge variant="default">{planStatusLabel(revisionDetail.previous_plan.status)}</Badge></div>
                  <p className="mt-1 text-xs text-fg-muted">周期：{formatDate(revisionDetail.previous_plan.start_date)} 至 {formatDate(revisionDetail.previous_plan.end_date)}</p>
                  {revisionDetail.previous_plan.items && revisionDetail.previous_plan.items.length > 0 && <div className="mt-2 space-y-1 border-t border-border pt-2">{revisionDetail.previous_plan.items.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-2 text-xs"><span className="text-fg">{item.action}</span><span className="text-fg-dimmed">{item.frequency_name || item.frequency_code || "按需"}{item.duration_days ? ` · ${item.duration_days} 天` : ""}</span></div>)}</div>}
                </div>
              </div>
            )}
            <div>
              <h4 className="text-sm font-semibold text-fg-emphasis">新计划版本（执行中）</h4>
              <div className="mt-2 rounded-md border border-border p-3">
                <div className="flex flex-wrap items-center gap-2"><span className="font-medium text-fg-emphasis">{revisionDetail.plan.plan_name}</span><Badge variant="info">{planStatusLabel(revisionDetail.plan.status)}</Badge></div>
                <p className="mt-1 text-xs text-fg-muted">目标：{revisionDetail.plan.goals || "未填写"}</p>
                <p className="mt-1 text-xs text-fg-muted">周期：{formatDate(revisionDetail.plan.start_date)} 至 {formatDate(revisionDetail.plan.end_date)}</p>
                {revisionDetail.plan.items && revisionDetail.plan.items.length > 0 && <div className="mt-2 space-y-1 border-t border-border pt-2">{revisionDetail.plan.items.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-2 text-xs"><span className="text-fg">{item.action}</span><span className="text-fg-dimmed">{item.frequency_name || item.frequency_code || "按需"}{item.duration_days ? ` · ${item.duration_days} 天` : ""}</span></div>)}</div>}
                {revisionDetail.tasks.length > 0 && <div className="mt-2 space-y-1 border-t border-border pt-2">{revisionDetail.tasks.map((task) => <div key={task.id} className="flex flex-wrap items-center justify-between gap-2 text-xs"><span className="text-fg">任务：{task.description}</span><span className="text-fg-dimmed">{taskStatusLabel(task.status)}{task.start_date ? ` · ${formatDate(task.start_date)} 起` : ""}{task.end_date ? ` · ${formatDate(task.end_date)} 止` : ""}</span></div>)}</div>}
              </div>
            </div>
            <div className="flex justify-end gap-3">
              <Button variant="ghost" onClick={() => { setRevisionDetailOpen(false); setRevisionDetail(null); }}>关闭</Button>
            </div>
          </div>
        ) : (
          <div className="py-8 text-center text-sm text-fg-dimmed">加载中...</div>
        )}
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

      {/* ——— 新增护理记录 ——— */}
      <Modal open={recordOpen} onClose={() => { if (!recordSaving) { setRecordOpen(false); setRecordError(""); } }} title="新增护理记录">
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateRecord(); }}>
          {recordForm.taskExecutionId && <div className="rounded-md bg-surface-alt px-3 py-2 text-xs text-fg-muted">关联执行记录：{recordForm.taskExecutionId}</div>}
          <Input label="标题" value={recordForm.title} onChange={(event) => setRecordForm((current) => ({ ...current, title: event.target.value }))} required maxLength={100} />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="record-time">观察/记录时间</label>
            <input id="record-time" type="datetime-local" className={selectClass} value={recordForm.recordTime} onChange={(event) => setRecordForm((current) => ({ ...current, recordTime: event.target.value }))} />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="record-content">正文</label>
            <textarea id="record-content" rows={5} className={textareaClass} value={recordForm.content} onChange={(event) => setRecordForm((current) => ({ ...current, content: event.target.value }))} placeholder="记录护理观察、措施和结果" required />
            <span className="text-xs text-fg-dimmed">{recordForm.content.length} 字</span>
          </div>
          {recordError && <p className="text-sm text-danger">{recordError}</p>}
          <div className="flex justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => { setRecordOpen(false); setRecordError(""); }} disabled={recordSaving}>取消</Button>
            <Button type="submit" loading={recordSaving} disabled={!recordForm.title.trim() || !recordForm.content.trim()}>保存记录</Button>
          </div>
        </form>
      </Modal>

      {/* ——— 护理记录详情 ——— */}
      <Modal open={recordDetailOpen} onClose={() => { setRecordDetailOpen(false); setSelectedRecord(null); }} title="护理记录详情">
        {selectedRecord && (
          <div className="space-y-4">
            <div className="rounded-md bg-surface-alt px-3 py-2">
              <div className="flex items-center gap-2">
                <Badge variant={selectedRecord.record_kind === "CORRECTION" ? "warning" as const : "info" as const}>
                  {selectedRecord.record_kind === "CORRECTION" ? "更正记录" : selectedRecord.record_kind === "EXECUTION" ? "执行补充" : "护理记录"}
                </Badge>
                <span className="text-xs text-fg-dimmed">{formatDateTime(selectedRecord.record_time)}</span>
              </div>
              <h4 className="mt-2 text-base font-semibold text-fg-emphasis">{selectedRecord.title}</h4>
              <p className="mt-2 whitespace-pre-wrap text-sm text-fg">{selectedRecord.content || "（无正文）"}</p>
              {selectedRecord.author && <p className="mt-2 text-xs text-fg-dimmed">记录人：{subjectMap.get(selectedRecord.author) ?? selectedRecord.author}</p>}
              {selectedRecord.is_corrected && <p className="mt-2 text-xs text-amber-600">此记录已被更正，请以更正记录为准</p>}
            </div>
            {selectedRecord.metadata?.corrects_record_id != null && (
              <p className="text-xs text-fg-dimmed">更正来源记录：{String(selectedRecord.metadata.corrects_record_id)}</p>
            )}
            <div className="flex justify-end gap-3">
              {selectedRecord.record_kind !== "CORRECTION" && !selectedRecord.metadata?.corrects_record_id && (
                <Button variant="secondary" onClick={() => { setRecordDetailOpen(false); openCorrection(selectedRecord); }}>更正此记录</Button>
              )}
              <Button variant="ghost" onClick={() => { setRecordDetailOpen(false); setSelectedRecord(null); }}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ——— 更正记录 ——— */}
      <Modal open={correctionOpen} onClose={() => { if (!recordSaving) { setCorrectionOpen(false); setSelectedRecord(null); setActionError(""); } }} title="更正护理记录">
        {selectedRecord && (
          <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void handleCreateCorrection(); }}>
            <div className="rounded-md bg-surface-alt px-3 py-2 text-sm text-fg-muted">
              <p className="font-medium text-fg-emphasis">原记录：{selectedRecord.title}</p>
              <p className="mt-1 text-xs">{selectedRecord.content?.slice(0, 200)}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="correction-time">更正时间</label>
              <input id="correction-time" type="datetime-local" className={selectClass} value={correctionForm.recordTime} onChange={(event) => setCorrectionForm((current) => ({ ...current, recordTime: event.target.value }))} />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="correction-content">更正内容 <span className="text-danger">*</span></label>
              <textarea id="correction-content" rows={5} className={textareaClass} value={correctionForm.content} onChange={(event) => setCorrectionForm((current) => ({ ...current, content: event.target.value }))} placeholder="说明更正的原因和正确信息" required />
            </div>
            {actionError && <p className="text-sm text-danger">{actionError}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="ghost" onClick={() => { setCorrectionOpen(false); setSelectedRecord(null); setActionError(""); }} disabled={recordSaving}>取消</Button>
              <Button type="submit" loading={recordSaving} disabled={!correctionForm.content.trim()}>提交更正</Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
