import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createDiagnosis,
  createMedicalOrder,
  createProgressNote,
  getMedicalOrder,
  listDiagnoses,
  listElderlyAdmissions,
  listMedicalOrders,
  listPatients,
  listProgressNotes,
  updateMedicalOrderStatus,
  type Diagnosis,
  type Encounter,
  type MedicalOrder,
  type MedicalOrderExecutionSummary,
  type MedicalOrderInput,
  type ProgressNote,
} from "@pitchfork/shared/aceso";

interface ActiveAdmission extends Encounter {
  patientName: string;
}

interface OrderForm {
  orderType: string;
  orderClass: string;
  orderContent: string;
  doctor: string;
  startTime: string;
  endTime: string;
  drugName: string;
  dose: string;
  unit: string;
  route: string;
  frequencyCode: string;
  frequencyName: string;
  durationDays: string;
  remark: string;
  treatmentItem: string;
  itemName: string;
}

interface NoteForm {
  content: string;
  physician: string;
  recordTime: string;
}

interface DiagnosisForm {
  diagnosisType: string;
  diagnosisText: string;
  icdCode: string;
  diagnosisDate: string;
  physician: string;
  isMajor: boolean;
  remark: string;
}

const orderFormDefaults: OrderForm = {
  orderType: "MEDICATION",
  orderClass: "LONG_TERM",
  orderContent: "",
  doctor: "",
  startTime: "",
  endTime: "",
  drugName: "",
  dose: "",
  unit: "",
  route: "",
  frequencyCode: "",
  frequencyName: "",
  durationDays: "",
  remark: "",
  treatmentItem: "",
  itemName: "",
};

const noteFormDefaults: NoteForm = { content: "", physician: "", recordTime: "" };

function todayLocal(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}

const diagnosisFormDefaults: DiagnosisForm = {
  diagnosisType: "PRIMARY",
  diagnosisText: "",
  icdCode: "",
  diagnosisDate: todayLocal(),
  physician: "",
  isMajor: false,
  remark: "",
};

const FREQUENCY_OPTIONS: Array<{ code: string; label: string }> = [
  { code: "QD", label: "每日一次" },
  { code: "BID", label: "每日两次" },
  { code: "TID", label: "每日三次" },
  { code: "QID", label: "每日四次" },
  { code: "QOD", label: "隔日一次" },
  { code: "QW", label: "每周一次" },
  { code: "BIW", label: "每周两次" },
  { code: "TIW", label: "每周三次" },
  { code: "PRN", label: "按需" },
  { code: "STAT", label: "立即" },
];

const ORDER_TYPE_LABEL: Record<string, string> = {
  MEDICATION: "用药医嘱",
  THERAPY: "治疗医嘱",
  EXAMINATION: "检查医嘱",
  LAB_TEST: "检验医嘱",
};

const ORDER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "进行中",
  DISCONTINUED: "已停嘱",
  CANCELLED: "已作废",
  COMPLETED: "已完成",
};

const ORDER_STATUS_VARIANT: Record<string, "default" | "success" | "warning" | "danger"> = {
  ACTIVE: "default",
  DISCONTINUED: "warning",
  CANCELLED: "danger",
  COMPLETED: "success",
};

const ENCOUNTER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "在住",
  DISCHARGED: "已离院",
  TRANSFERRED: "已转出",
  DECEASED: "已去世",
};

const DIAGNOSIS_TYPE_LABEL: Record<string, string> = {
  PRIMARY: "主要诊断",
  SECONDARY: "次要诊断",
};

const EXECUTION_SUMMARY_ITEMS: Array<[keyof MedicalOrderExecutionSummary, string]> = [
  ["PENDING", "待执行"],
  ["IN_PROGRESS", "执行中"],
  ["COMPLETED", "已完成"],
  ["SKIPPED", "已跳过"],
  ["CANCELLED", "已取消"],
];

const ORDER_DETAIL_LABELS: Record<string, string> = {
  drug_name: "药名",
  dose: "剂量",
  unit: "单位",
  route: "途径",
  treatment_item: "诊疗项目",
  item_name: "项目名称",
  body_part: "检查部位",
  specimen_type: "标本类型",
  priority: "优先级",
  fasting: "是否空腹",
  clinical_note: "临床说明",
  frequency_code: "频次编码",
  frequency_name: "频次",
  duration_days: "天数",
  remark: "备注",
};

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const textareaClass = "w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const radioClass = "h-4 w-4 border-border bg-surface accent-accent";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

/** SSR 安全地读取 URL 上的 encounter_id，用于进入页面时优先选中该入住 */
function readEncounterIdFromUrl(): string {
  if (typeof window === "undefined") return "";
  try {
    return new URLSearchParams(window.location.search).get("encounter_id") ?? "";
  } catch {
    return "";
  }
}

function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined) return "-";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

function orderClassVariant(orderClass: string | null): "default" | "success" | "warning" {
  if (orderClass === "TEMPORARY") return "warning";
  if (orderClass === "LONG_TERM") return "success";
  return "default";
}

export default function OrdersPage() {
  const [admissions, setAdmissions] = useState<ActiveAdmission[]>([]);
  const [admissionsLoading, setAdmissionsLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [preferredEncounterId] = useState(readEncounterIdFromUrl);

  // —— 病程记录 ——
  const [notes, setNotes] = useState<ProgressNote[]>([]);
  const [notesTotal, setNotesTotal] = useState(0);
  const [notesLoading, setNotesLoading] = useState(false);
  const [notesError, setNotesError] = useState("");
  const [noteForm, setNoteForm] = useState<NoteForm>(noteFormDefaults);
  const [noteFormError, setNoteFormError] = useState("");
  const [noteSaving, setNoteSaving] = useState(false);
  const [noteEditorOpen, setNoteEditorOpen] = useState(false);

  // —— 诊断 ——
  const [diagnoses, setDiagnoses] = useState<Diagnosis[]>([]);
  const [diagnosesTotal, setDiagnosesTotal] = useState(0);
  const [diagnosesLoading, setDiagnosesLoading] = useState(false);
  const [diagnosesError, setDiagnosesError] = useState("");
  const [diagnosisForm, setDiagnosisForm] = useState<DiagnosisForm>(diagnosisFormDefaults);
  const [diagnosisFormError, setDiagnosisFormError] = useState("");
  const [diagnosisSaving, setDiagnosisSaving] = useState(false);
  const [diagnosisEditorOpen, setDiagnosisEditorOpen] = useState(false);

  // —— 医嘱 ——
  const [orders, setOrders] = useState<MedicalOrder[]>([]);
  const [ordersTotal, setOrdersTotal] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState("");
  const [orderTypeFilter, setOrderTypeFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");

  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<OrderForm>(orderFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const [detailTarget, setDetailTarget] = useState<MedicalOrder | null>(null);
  const [detail, setDetail] = useState<MedicalOrder | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [statusAction, setStatusAction] = useState("");
  const [statusError, setStatusError] = useState("");

  const loadAdmissions = useCallback(async () => {
    setAdmissionsLoading(true);
    setPageError("");
    try {
      // 含已离院/已去世入住（只读历史），ACTIVE 优先排列
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 200 }),
        listElderlyAdmissions({ status: "", limit: 200 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      const statusRank: Record<string, number> = { ACTIVE: 0, DISCHARGED: 1, DECEASED: 2, TRANSFERRED: 3 };
      records.sort((a, b) => (statusRank[a.status] ?? 9) - (statusRank[b.status] ?? 9) || (b.admit_date ?? "").localeCompare(a.admit_date ?? ""));
      setAdmissions(records);
      setSelectedEncounterId((current) => {
        if (records.some((record) => record.id === current)) return current;
        const candidate = preferredEncounterId || records.find((record) => record.status === "ACTIVE")?.id || "";
        return records.some((record) => record.id === candidate) ? candidate : records[0]?.id || "";
      });
    } catch (error) {
      setPageError(errorMessage(error, "无法加载入住"));
    } finally {
      setAdmissionsLoading(false);
    }
  }, [preferredEncounterId]);

  const loadNotes = useCallback(async () => {
    if (!selectedEncounterId) return;
    setNotesLoading(true);
    setNotesError("");
    try {
      const response = await listProgressNotes(selectedEncounterId, { limit: 100 });
      setNotes(response.records);
      setNotesTotal(response.meta.total);
    } catch (error) {
      setNotes([]);
      setNotesError(errorMessage(error, "无法加载病程记录"));
    } finally {
      setNotesLoading(false);
    }
  }, [selectedEncounterId]);

  const loadDiagnoses = useCallback(async () => {
    if (!selectedEncounterId) return;
    setDiagnosesLoading(true);
    setDiagnosesError("");
    try {
      const response = await listDiagnoses(selectedEncounterId, { limit: 100 });
      setDiagnoses(response.records);
      setDiagnosesTotal(response.meta.total);
    } catch (error) {
      setDiagnoses([]);
      setDiagnosesError(errorMessage(error, "无法加载诊断"));
    } finally {
      setDiagnosesLoading(false);
    }
  }, [selectedEncounterId]);

  const loadOrders = useCallback(async () => {
    if (!selectedEncounterId) return;
    setOrdersLoading(true);
    setOrdersError("");
    try {
      const response = await listMedicalOrders(selectedEncounterId, {
        order_type: orderTypeFilter || undefined,
        status: statusFilter || undefined,
        limit: 100,
      });
      setOrders(response.records);
      setOrdersTotal(response.meta.total);
    } catch (error) {
      setOrders([]);
      setOrdersError(errorMessage(error, "无法加载医嘱列表"));
    } finally {
      setOrdersLoading(false);
    }
  }, [selectedEncounterId, orderTypeFilter, statusFilter]);

  useEffect(() => {
    void loadAdmissions();
  }, [loadAdmissions]);

  useEffect(() => {
    void loadNotes();
  }, [loadNotes]);

  useEffect(() => {
    void loadDiagnoses();
  }, [loadDiagnoses]);

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  const selectedAdmission = admissions.find((admission) => admission.id === selectedEncounterId) ?? null;
  // 已离院/已去世等非活动入住只读历史
  const isReadOnly = selectedAdmission !== null && selectedAdmission.status !== "ACTIVE";

  // —— 病程记录 ——

  function openNoteEditor() {
    setNoteFormError("");
    setNoteEditorOpen(true);
  }

  async function handleSaveNote() {
    const content = noteForm.content.trim();
    const physician = noteForm.physician.trim();
    if (!content || !physician) {
      setNoteFormError("记录内容和医生不能为空");
      return;
    }
    setNoteSaving(true);
    setNoteFormError("");
    try {
      const recordTime = noteForm.recordTime.trim();
      await createProgressNote(selectedEncounterId, {
        note_type: "DAILY",
        content,
        physician,
        ...(recordTime ? { record_time: `${recordTime}:00+08:00` } : {}),
      });
      setNoteForm(noteFormDefaults);
      setNoteEditorOpen(false);
      await loadNotes();
    } catch (error) {
      setNoteFormError(errorMessage(error, "无法保存病程记录"));
    } finally {
      setNoteSaving(false);
    }
  }

  // —— 诊断 ——

  function openDiagnosisEditor() {
    setDiagnosisFormError("");
    setDiagnosisEditorOpen(true);
  }

  async function handleSaveDiagnosis() {
    const diagnosisText = diagnosisForm.diagnosisText.trim();
    const physician = diagnosisForm.physician.trim();
    const diagnosisDate = diagnosisForm.diagnosisDate.trim();
    if (!diagnosisText || !physician || !diagnosisDate) {
      setDiagnosisFormError("诊断内容、医生和诊断日期不能为空");
      return;
    }
    setDiagnosisSaving(true);
    setDiagnosisFormError("");
    try {
      await createDiagnosis(selectedEncounterId, {
        diagnosis_type: diagnosisForm.diagnosisType === "SECONDARY" ? "SECONDARY" : "PRIMARY",
        diagnosis_text: diagnosisText,
        diagnosis_date: diagnosisDate,
        physician,
        ...(diagnosisForm.icdCode.trim() ? { icd_code: diagnosisForm.icdCode.trim() } : {}),
        ...(diagnosisForm.isMajor ? { is_major: true } : {}),
        ...(diagnosisForm.remark.trim() ? { remark: diagnosisForm.remark.trim() } : {}),
      });
      setDiagnosisForm((current) => ({ ...diagnosisFormDefaults, diagnosisDate: current.diagnosisDate, physician: current.physician }));
      setDiagnosisEditorOpen(false);
      await loadDiagnoses();
    } catch (error) {
      setDiagnosisFormError(errorMessage(error, "无法保存诊断"));
    } finally {
      setDiagnosisSaving(false);
    }
  }

  // —— 医嘱 ——

  function openCreate() {
    setForm(orderFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function handleFrequencyChange(code: string) {
    const option = FREQUENCY_OPTIONS.find((item) => item.code === code);
    setForm((current) => ({
      ...current,
      frequencyCode: code,
      frequencyName: option ? option.label : current.frequencyName,
    }));
  }

  function buildOrderInput(): MedicalOrderInput | null {
    const orderContent = form.orderContent.trim();
    const doctor = form.doctor.trim();
    const startTime = form.startTime.trim();
    if (!orderContent || !doctor || !startTime) return null;

    const details: Record<string, unknown> = {};
    if (form.orderType === "MEDICATION") {
      details.drug_name = form.drugName.trim();
      if (form.dose.trim()) details.dose = form.dose.trim();
      if (form.unit.trim()) details.unit = form.unit.trim();
      if (form.route.trim()) details.route = form.route.trim();
    } else if (form.orderType === "THERAPY") {
      details.treatment_item = form.treatmentItem.trim();
    } else if (form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") {
      details.item_name = form.itemName.trim();
    }

    // 频次 code/name 必须成对提交：有 code 时补上名称，无 code 时两者都不提交
    if (form.frequencyCode) {
      const label = FREQUENCY_OPTIONS.find((option) => option.code === form.frequencyCode)?.label ?? "";
      details.frequency_code = form.frequencyCode;
      details.frequency_name = form.frequencyName.trim() || label;
    }

    const durationDays = form.durationDays.trim();
    if (durationDays) {
      const days = Number(durationDays);
      if (!Number.isInteger(days) || days < 1) return null;
      details.duration_days = days;
    }

    const endTime = form.endTime.trim();
    if (form.orderClass === "TEMPORARY" && !endTime && !durationDays && form.frequencyCode !== "STAT") return null;

    if (form.remark.trim()) details.remark = form.remark.trim();

    return {
      order_type: form.orderType,
      order_class: form.orderClass,
      order_content: orderContent,
      doctor,
      start_time: `${startTime}:00+08:00`,
      ...(endTime ? { end_time: `${endTime}:00+08:00` } : {}),
      ...(Object.keys(details).length > 0 ? { order_details: details } : {}),
    };
  }

  async function handleSave() {
    const input = buildOrderInput();
    if (!input) {
      if (!form.orderContent.trim() || !form.doctor.trim() || !form.startTime.trim()) {
        setFormError("医嘱正文、医生和开始时间不能为空");
      } else if (form.orderType === "MEDICATION" && !form.drugName.trim()) {
        setFormError("用药医嘱必须填写药名");
      } else if (form.orderType === "THERAPY" && !form.treatmentItem.trim()) {
        setFormError("治疗医嘱必须填写治疗项目");
      } else if ((form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && !form.itemName.trim()) {
        setFormError("检查/检验医嘱必须填写项目名称");
      } else if (form.orderClass === "TEMPORARY" && !form.endTime.trim() && !form.durationDays.trim() && form.frequencyCode !== "STAT") {
        setFormError("临时医嘱必须填写结束时间、持续天数，或选择立即执行（STAT）");
      } else {
        setFormError("时长必须是正整数");
      }
      return;
    }

    setSaving(true);
    setFormError("");
    try {
      await createMedicalOrder(selectedEncounterId, input);
      setEditorOpen(false);
      setForm(orderFormDefaults);
      await loadOrders();
    } catch (error) {
      setFormError(errorMessage(error, "无法开立医嘱"));
    } finally {
      setSaving(false);
    }
  }

  async function openDetail(order: MedicalOrder) {
    setDetailTarget(order);
    setDetail(null);
    setDetailError("");
    setStatusError("");
    setStatusAction("");
    setDetailLoading(true);
    try {
      const data = await getMedicalOrder(order.id);
      setDetail(data);
    } catch (error) {
      setDetailError(errorMessage(error, "无法读取医嘱详情"));
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    if (statusAction) return;
    setDetailTarget(null);
    setDetail(null);
    setDetailError("");
    setStatusError("");
    setStatusAction("");
  }

  async function handleStatusUpdate(orderId: string, status: string) {
    setStatusAction(status);
    setStatusError("");
    try {
      const updated = await updateMedicalOrderStatus(orderId, status);
      setDetail(updated);
      await loadOrders();
    } catch (error) {
      setStatusError(errorMessage(error, "无法更新医嘱状态"));
    } finally {
      setStatusAction("");
    }
  }

  const columns: Column<MedicalOrder>[] = [
    {
      key: "order_type",
      header: "类型",
      className: "min-w-[100px]",
      render: (row) => ORDER_TYPE_LABEL[row.order_type] ?? row.order_type,
    },
    {
      key: "order_class",
      header: "周期",
      className: "min-w-[90px]",
      render: (row) => (
        <Badge variant={orderClassVariant(row.order_class)}>
          {row.order_class_label ?? "-"}
        </Badge>
      ),
    },
    {
      key: "order_content",
      header: "医嘱正文",
      className: "min-w-[240px] max-w-[380px]",
      render: (row) => (
        <span className="block truncate" title={row.order_content}>
          {row.order_content}
        </span>
      ),
    },
    {
      key: "doctor",
      header: "医生",
      className: "min-w-[110px]",
      render: (row) => row.doctor || "-",
    },
    {
      key: "start_time",
      header: "开始时间",
      className: "min-w-[150px]",
      render: (row) => formatDateTime(row.start_time),
    },
    {
      key: "status",
      header: "状态",
      className: "min-w-[100px]",
      render: (row) => (
        <Badge variant={ORDER_STATUS_VARIANT[row.status] ?? "default"}>
          {ORDER_STATUS_LABEL[row.status] ?? row.status}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[90px]",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openDetail(row)}>
          详情
        </Button>
      ),
    },
  ];

  const diagnosisColumns: Column<Diagnosis>[] = [
    {
      key: "diagnosis_date",
      header: "日期",
      className: "min-w-[110px]",
      render: (row) => row.diagnosis_date || "-",
    },
    {
      key: "diagnosis_type",
      header: "类型",
      className: "min-w-[100px]",
      render: (row) => DIAGNOSIS_TYPE_LABEL[row.diagnosis_type] ?? row.diagnosis_type,
    },
    {
      key: "icd_code",
      header: "ICD",
      className: "min-w-[90px]",
      render: (row) => row.icd_code || "-",
    },
    {
      key: "diagnosis_text",
      header: "诊断内容",
      className: "min-w-[200px] max-w-[320px]",
      render: (row) => (
        <span className="block truncate" title={row.diagnosis_text}>
          {row.diagnosis_text}
        </span>
      ),
    },
    {
      key: "physician",
      header: "医生",
      className: "min-w-[110px]",
      render: (row) => row.physician || "-",
    },
    {
      key: "is_major",
      header: "主诊断",
      className: "min-w-[80px]",
      render: (row) => (row.is_major ? <Badge variant="success">主要</Badge> : <span className="text-fg-dimmed">-</span>),
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[120px]",
      render: (row) => {
        const patientId = selectedAdmission?.patient_id ?? "";
        const params = new URLSearchParams();
        if (patientId) params.set("patient", patientId);
        if (row.diagnosis_text.trim()) params.set("disease", row.diagnosis_text.trim());
        if (row.icd_code?.trim()) params.set("icd", row.icd_code.trim());
        return (
          <a
            href={`/dashboard/chronic?${params.toString()}`}
            className="text-xs text-accent hover:underline"
            title="一键带入诊断登记为慢病档案（自动生成慢病随访计划）"
          >
            登记为慢病
          </a>
        );
      },
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">医生诊疗</h2>
          <p className="mt-1 text-sm text-fg-muted">选择活动入住老人，书写病程记录、录入诊断并开立医嘱</p>
        </div>
        {admissions.length > 0 && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="orders-encounter">入住老人</label>
              <select
                id="orders-encounter"
                className={`${selectClass} min-w-[280px]`}
                value={selectedEncounterId}
                onChange={(event) => setSelectedEncounterId(event.target.value)}
                disabled={admissionsLoading}
              >
                {admissions.map((admission) => (
                  <option key={admission.id} value={admission.id}>
                    {admission.patientName} · {admission.encounter_no}（{ENCOUNTER_STATUS_LABEL[admission.status] ?? admission.status}）
                  </option>
                ))}
              </select>
            </div>
            {!isReadOnly && (
              <Button variant="primary" onClick={openCreate}>开立医嘱</Button>
            )}
          </div>
        )}
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      {admissionsLoading ? (
        <Card>
          <p className="py-10 text-center text-sm text-fg-dimmed">正在加载入住…</p>
        </Card>
      ) : admissions.length === 0 ? (
        <Card>
          <EmptyState
            icon="🏠"
            title="暂无入住老人"
            description="请先在入住管理办理养老入住，再开展诊疗工作。"
            action={
              <a
                href="/dashboard/admission"
                className="inline-flex items-center justify-center rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:brightness-110"
              >
                前往入住管理
              </a>
            }
          />
        </Card>
      ) : (
        <>
          {selectedAdmission && (
            <div className={`rounded-lg border px-4 py-3 text-sm ${isReadOnly ? "border-warning/30 bg-warning-bg text-warning" : "border-info/30 bg-info-bg text-info"}`}>
              <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
                <span className="font-medium">{selectedAdmission.patientName}</span>
                <span>入住号：{selectedAdmission.encounter_no}</span>
                <span>床位：{selectedAdmission.ward || selectedAdmission.department || "未设置"}</span>
                <span>入住日期：{formatDateTime(selectedAdmission.admit_date)}</span>
                <Badge variant={selectedAdmission.status === "ACTIVE" ? "success" : "warning"}>
                  {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
                </Badge>
              </div>
              {isReadOnly && (
                <p className="mt-1 text-sm">该入住已{ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? "结束"}，仅可查看历史病程、诊断和医嘱。</p>
              )}
            </div>
          )}

          {/* 病程记录 */}
          <Card
            title="病程记录"
            actions={
              <>
                <span className="text-sm text-fg-dimmed">共 {notesTotal} 条</span>
                {!isReadOnly && (
                  <Button type="button" variant="secondary" size="sm" onClick={openNoteEditor}>新增病程记录</Button>
                )}
              </>
            }
          >
            {notesError && (
              <div className="mb-4 rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {notesError}
              </div>
            )}

            {notesLoading ? (
              <p className="py-6 text-center text-sm text-fg-dimmed">正在加载病程记录…</p>
            ) : notes.length === 0 ? (
              <p className="py-6 text-center text-sm text-fg-dimmed">暂无病程记录</p>
            ) : (
              <ol className="max-h-[420px] space-y-3 overflow-y-auto pr-1">
                {notes.map((note) => (
                  <li key={note.id} className="rounded-md border border-border bg-surface-alt p-3">
                    <div className="mb-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-fg-muted">
                      <span className="font-medium text-fg">{formatDateTime(note.record_time)}</span>
                      <span>{note.physician}</span>
                      <span className="ml-auto break-all">{note.id}</span>
                    </div>
                    <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-fg">{note.content}</p>
                  </li>
                ))}
              </ol>
            )}
          </Card>

          {/* 诊断 */}
          <Card
            title="诊断"
            actions={
              <>
                <span className="text-sm text-fg-dimmed">共 {diagnosesTotal} 条</span>
                {!isReadOnly && (
                  <Button type="button" variant="secondary" size="sm" onClick={openDiagnosisEditor}>新增诊断</Button>
                )}
              </>
            }
          >
            {diagnosesError && (
              <div className="mb-4 rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {diagnosesError}
              </div>
            )}

            <Table
              columns={diagnosisColumns}
              data={diagnoses}
              loading={diagnosesLoading}
              emptyMessage="暂无诊断记录"
            />
          </Card>

          {/* 医嘱 */}
          <Card
            title="医嘱"
            actions={<span className="text-sm text-fg-dimmed">共 {ordersTotal} 条</span>}
          >
            <div className="mb-4 flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="order-type-filter">类型</label>
                <select
                  id="order-type-filter"
                  className={selectClass}
                  value={orderTypeFilter}
                  onChange={(event) => setOrderTypeFilter(event.target.value)}
                >
                  <option value="">全部</option>
                  <option value="MEDICATION">用药医嘱</option>
                  <option value="THERAPY">治疗医嘱</option>
                  <option value="EXAMINATION">检查医嘱</option>
                  <option value="LAB_TEST">检验医嘱</option>
                </select>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="order-status-filter">状态</label>
                <select
                  id="order-status-filter"
                  className={selectClass}
                  value={statusFilter}
                  onChange={(event) => setStatusFilter(event.target.value)}
                >
                  <option value="">全部</option>
                  <option value="ACTIVE">进行中</option>
                  <option value="DISCONTINUED">已停嘱</option>
                  <option value="CANCELLED">已作废</option>
                  <option value="COMPLETED">已完成</option>
                </select>
              </div>
              <Button variant="secondary" size="md" disabled={ordersLoading} onClick={() => void loadOrders()}>
                刷新
              </Button>
            </div>

            {ordersError && (
              <div className="mb-4 rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {ordersError}
              </div>
            )}

            <Table
              columns={columns}
              data={orders}
              loading={ordersLoading}
              emptyMessage="暂无医嘱记录"
            />
          </Card>
        </>
      )}

      {/* 新增病程记录弹窗 */}
      <Modal
        open={noteEditorOpen}
        onClose={() => !noteSaving && setNoteEditorOpen(false)}
        title="新增病程记录"
        width="42rem"
      >
        <form
          className="space-y-5"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void handleSaveNote();
          }}
        >
          {noteFormError && (
            <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {noteFormError}
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="note-content">记录内容（必填）</label>
              <textarea
                id="note-content"
                className={textareaClass}
                rows={3}
                maxLength={2000}
                value={noteForm.content}
                onChange={(event) => setNoteForm((current) => ({ ...current, content: event.target.value }))}
                placeholder="请输入病程记录内容，最多 2000 字"
                required
              />
            </div>
            <Input
              label="医生（必填）"
              value={noteForm.physician}
              onChange={(event) => setNoteForm((current) => ({ ...current, physician: event.target.value }))}
              placeholder="请输入记录医生"
              maxLength={100}
              required
            />
            <Input
              label="记录时间（可选，缺省为当前时间）"
              type="datetime-local"
              value={noteForm.recordTime}
              onChange={(event) => setNoteForm((current) => ({ ...current, recordTime: event.target.value }))}
            />
          </div>
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setNoteEditorOpen(false)} disabled={noteSaving}>取消</Button>
            <Button type="submit" loading={noteSaving} disabled={noteSaving}>保存病程记录</Button>
          </div>
        </form>
      </Modal>

      {/* 新增诊断弹窗 */}
      <Modal open={diagnosisEditorOpen} onClose={() => !diagnosisSaving && setDiagnosisEditorOpen(false)} title="新增诊断">
        <form
          className="space-y-5"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void handleSaveDiagnosis();
          }}
        >
          {diagnosisFormError && (
            <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {diagnosisFormError}
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="diagnosis-type">诊断类型</label>
              <select
                id="diagnosis-type"
                className={selectClass}
                value={diagnosisForm.diagnosisType}
                onChange={(event) => setDiagnosisForm((current) => ({ ...current, diagnosisType: event.target.value }))}
              >
                <option value="PRIMARY">主要诊断</option>
                <option value="SECONDARY">次要诊断</option>
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="diagnosis-date">诊断日期（必填）</label>
              <input
                id="diagnosis-date"
                type="date"
                className={selectClass}
                value={diagnosisForm.diagnosisDate}
                onChange={(event) => setDiagnosisForm((current) => ({ ...current, diagnosisDate: event.target.value }))}
                required
              />
            </div>
            <div className="sm:col-span-2">
              <Input
                label="诊断内容（必填）"
                value={diagnosisForm.diagnosisText}
                onChange={(event) => setDiagnosisForm((current) => ({ ...current, diagnosisText: event.target.value }))}
                placeholder="请输入诊断内容，如 高血压"
                maxLength={2000}
                required
              />
            </div>
            <Input
              label="ICD 编码（可选）"
              value={diagnosisForm.icdCode}
              onChange={(event) => setDiagnosisForm((current) => ({ ...current, icdCode: event.target.value }))}
              placeholder="如 I10"
              maxLength={32}
            />
            <Input
              label="医生（必填）"
              value={diagnosisForm.physician}
              onChange={(event) => setDiagnosisForm((current) => ({ ...current, physician: event.target.value }))}
              placeholder="请输入诊断医生"
              maxLength={100}
              required
            />
            <label className="flex items-center gap-2 text-sm text-fg-muted sm:col-span-2">
              <input
                type="checkbox"
                className={radioClass}
                checked={diagnosisForm.isMajor}
                onChange={(event) => setDiagnosisForm((current) => ({ ...current, isMajor: event.target.checked }))}
              />
              主要诊断（默认按诊断类型区分，如需标记可勾选）
            </label>
            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="diagnosis-remark">备注（可选）</label>
              <textarea
                id="diagnosis-remark"
                className={textareaClass}
                rows={2}
                maxLength={500}
                value={diagnosisForm.remark}
                onChange={(event) => setDiagnosisForm((current) => ({ ...current, remark: event.target.value }))}
                placeholder="请输入备注"
              />
            </div>
          </div>
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setDiagnosisEditorOpen(false)} disabled={diagnosisSaving}>取消</Button>
            <Button type="submit" loading={diagnosisSaving} disabled={diagnosisSaving}>保存诊断</Button>
          </div>
        </form>
      </Modal>

      {/* 开立医嘱弹窗 */}
      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title="开立医嘱">
        <form
          className="space-y-5"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void handleSave();
          }}
        >
          {formError && (
            <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-type">医嘱类型</label>
              <select
                id="order-type"
                className={selectClass}
                value={form.orderType}
                onChange={(event) => setForm((current) => ({ ...current, orderType: event.target.value }))}
              >
                <option value="MEDICATION">用药医嘱</option>
                <option value="THERAPY">治疗医嘱</option>
                <option value="EXAMINATION">检查医嘱</option>
                <option value="LAB_TEST">检验医嘱</option>
              </select>
            </div>

            <div className="flex flex-col gap-2 sm:col-span-2">
              <span className="text-sm font-medium text-fg-muted">持续周期（必选）</span>
              <div className="flex flex-wrap gap-6">
                <label className="flex items-center gap-2 text-sm text-fg">
                  <input
                    type="radio"
                    className={radioClass}
                    name="order-class"
                    value="LONG_TERM"
                    checked={form.orderClass === "LONG_TERM"}
                    onChange={() => setForm((current) => ({ ...current, orderClass: "LONG_TERM" }))}
                  />
                  长期医嘱
                </label>
                <label className="flex items-center gap-2 text-sm text-fg">
                  <input
                    type="radio"
                    className={radioClass}
                    name="order-class"
                    value="TEMPORARY"
                    checked={form.orderClass === "TEMPORARY"}
                    onChange={() => setForm((current) => ({ ...current, orderClass: "TEMPORARY" }))}
                  />
                  临时医嘱
                </label>
              </div>
            </div>

            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-content">医嘱正文（必填）</label>
              <textarea
                id="order-content"
                className={textareaClass}
                rows={3}
                maxLength={2000}
                value={form.orderContent}
                onChange={(event) => setForm((current) => ({ ...current, orderContent: event.target.value }))}
                placeholder="请输入医嘱正文，最多 2000 字"
                required
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                label="医生（必填）"
                value={form.doctor}
                onChange={(event) => setForm((current) => ({ ...current, doctor: event.target.value }))}
                placeholder="请输入开嘱医生"
                maxLength={100}
                required
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                label="开始时间（必填）"
                type="datetime-local"
                value={form.startTime}
                onChange={(event) => setForm((current) => ({ ...current, startTime: event.target.value }))}
                required
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                label="结束时间（临时医嘱可选）"
                type="datetime-local"
                value={form.endTime}
                onChange={(event) => setForm((current) => ({ ...current, endTime: event.target.value }))}
                min={form.startTime || undefined}
              />
            </div>

            {form.orderType === "MEDICATION" && (
              <>
                <Input
                  label="药名（必填）"
                  value={form.drugName}
                  onChange={(event) => setForm((current) => ({ ...current, drugName: event.target.value }))}
                  placeholder="请输入药名"
                  required
                />
                <Input
                  label="剂量"
                  value={form.dose}
                  onChange={(event) => setForm((current) => ({ ...current, dose: event.target.value }))}
                  placeholder="如 500mg"
                />
                <Input
                  label="单位"
                  value={form.unit}
                  onChange={(event) => setForm((current) => ({ ...current, unit: event.target.value }))}
                  placeholder="如 片/次"
                />
                <Input
                  label="途径"
                  value={form.route}
                  onChange={(event) => setForm((current) => ({ ...current, route: event.target.value }))}
                  placeholder="如 口服"
                />
              </>
            )}

            {form.orderType === "THERAPY" && (
              <div className="sm:col-span-2">
                <Input
                  label="治疗项目（必填）"
                  value={form.treatmentItem}
                  onChange={(event) => setForm((current) => ({ ...current, treatmentItem: event.target.value }))}
                  placeholder="请输入治疗项目"
                  required
                />
              </div>
            )}

            {(form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && (
              <div className="sm:col-span-2">
                <Input
                  label="项目名称（必填）"
                  value={form.itemName}
                  onChange={(event) => setForm((current) => ({ ...current, itemName: event.target.value }))}
                  placeholder="请输入检查/检验项目名称"
                  required
                />
              </div>
            )}

            {(form.orderType === "MEDICATION" || form.orderType === "THERAPY" || form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && (
              <>
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="order-frequency">频次</label>
                  <select
                    id="order-frequency"
                    className={selectClass}
                    value={form.frequencyCode}
                    onChange={(event) => handleFrequencyChange(event.target.value)}
                  >
                    <option value="">不指定</option>
                    {FREQUENCY_OPTIONS.map((option) => (
                      <option key={option.code} value={option.code}>{option.label}</option>
                    ))}
                  </select>
                </div>
                <Input
                  label="频次名称"
                  value={form.frequencyName}
                  onChange={(event) => setForm((current) => ({ ...current, frequencyName: event.target.value }))}
                  placeholder="选中频次后自动填写"
                />
                <div className="sm:col-span-2">
                  <Input
                    label="时长（天）"
                    type="number"
                    min={1}
                    step={1}
                    value={form.durationDays}
                    onChange={(event) => setForm((current) => ({ ...current, durationDays: event.target.value }))}
                    placeholder="正整数"
                  />
                </div>
              </>
            )}

            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-remark">备注（可选）</label>
              <textarea
                id="order-remark"
                className={textareaClass}
                rows={2}
                value={form.remark}
                onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))}
                placeholder="请输入备注"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving} disabled={saving}>保存医嘱</Button>
          </div>
        </form>
      </Modal>

      {/* 医嘱详情弹窗 */}
      <Modal
        open={detailTarget !== null}
        onClose={closeDetail}
        title="医嘱详情"
      >
        {detailLoading && <p className="text-sm text-fg-dimmed">正在读取医嘱详情…</p>}

        {!detailLoading && detailError && !detail && (
          <div className="space-y-4">
            <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{detailError}</div>
            <div className="flex justify-end">
              <Button type="button" variant="ghost" onClick={closeDetail}>关闭</Button>
            </div>
          </div>
        )}

        {!detailLoading && detail && (
          <div className="space-y-5">
            <div className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <p><span className="text-fg-dimmed">医嘱 ID：</span><span className="break-all">{detail.id}</span></p>
              <p><span className="text-fg-dimmed">入住 ID：</span><span className="break-all">{detail.encounter_id}</span></p>
              <p><span className="text-fg-dimmed">类型：</span>{detail.order_type_label ?? ORDER_TYPE_LABEL[detail.order_type] ?? detail.order_type}</p>
              <p><span className="text-fg-dimmed">周期：</span>{detail.order_class_label ?? "-"}</p>
              <p>
                <span className="text-fg-dimmed">状态：</span>
                <Badge variant={ORDER_STATUS_VARIANT[detail.status] ?? "default"}>
                  {ORDER_STATUS_LABEL[detail.status] ?? detail.status}
                </Badge>
              </p>
              <p className="sm:col-span-2"><span className="text-fg-dimmed">医嘱正文：</span>{detail.order_content}</p>
              <p><span className="text-fg-dimmed">医生：</span>{detail.doctor || "-"}</p>
              <p><span className="text-fg-dimmed">开始时间：</span>{formatDateTime(detail.start_time)}</p>
              <p><span className="text-fg-dimmed">结束时间：</span>{formatDateTime(detail.end_time)}</p>
              <p><span className="text-fg-dimmed">任务 ID：</span>{detail.task_id ?? "-"}</p>
              <p><span className="text-fg-dimmed">创建时间：</span>{formatDateTime(detail.created_at)}</p>
              <p><span className="text-fg-dimmed">更新时间：</span>{formatDateTime(detail.updated_at)}</p>
            </div>

            <section>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">医嘱明细</h4>
              {Object.keys(detail.order_details ?? {}).length === 0 ? (
                <p className="text-sm text-fg-dimmed">无结构化明细</p>
              ) : (
                <div className="grid gap-x-6 gap-y-1.5 rounded-md border border-border bg-surface-alt p-3 text-sm sm:grid-cols-2">
                  {Object.entries(detail.order_details).map(([key, value]) => (
                    <p key={key}>
                      <span className="text-fg-dimmed">{ORDER_DETAIL_LABELS[key] ?? key}：</span>
                      {formatDetailValue(value)}
                    </p>
                  ))}
                </div>
              )}
            </section>

            <section>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">执行汇总</h4>
              {detail.execution_summary ? (
                <div className="flex flex-wrap gap-2">
                  {EXECUTION_SUMMARY_ITEMS.map(([key, label]) => (
                    <span key={key} className="inline-flex items-center gap-1 rounded-full bg-surface-alt px-3 py-1 text-sm text-fg-muted">
                      <span className="font-semibold text-fg">{detail.execution_summary?.[key] ?? 0}</span>
                      {label}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-fg-dimmed">暂无执行汇总</p>
              )}
            </section>

            {detail.status === "ACTIVE" && (
              <div className="border-t border-border pt-4">
                <p className="mb-2 text-sm text-fg-muted">医嘱状态操作：</p>
                {statusError && (
                  <div role="alert" className="mb-3 rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
                    {statusError}
                  </div>
                )}
                <div className="flex flex-wrap gap-3">
                  <Button
                    variant="warning"
                    size="sm"
                    loading={statusAction === "DISCONTINUED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "DISCONTINUED")}
                  >
                    停嘱
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    loading={statusAction === "CANCELLED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "CANCELLED")}
                  >
                    作废
                  </Button>
                  <Button
                    variant="primary"
                    size="sm"
                    loading={statusAction === "COMPLETED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "COMPLETED")}
                  >
                    完成
                  </Button>
                </div>
              </div>
            )}

            <div className="flex justify-end">
              <Button type="button" variant="ghost" onClick={closeDetail}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
