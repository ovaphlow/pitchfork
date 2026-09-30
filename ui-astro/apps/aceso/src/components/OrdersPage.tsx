import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  closeExpiredMedicalOrders,
  createDiagnosis,
  createMedicalOrder,
  createProgressNote,
  getMedicalOrder,
  listDiagnoses,
  listElderlyAdmissions,
  listInventoryMaterials,
  listMedicalOrders,
  listOrderAdministrations,
  listPatients,
  listProgressNotes,
  updateMedicalOrderStatus,
  type Diagnosis,
  type Encounter,
  type InventoryMaterial,
  type MedicalOrder,
  type MedicalOrderAdministration,
  type MedicalOrderAdministrationSummary,
  type MedicalOrderExecutionSummary,
  type MedicalOrderInput,
  type ProgressNote,
} from "@pitchfork/shared/aceso";
import { formatDateTime, toOffsetDateTime, todayLocal } from "../lib/datetime";
import { formatOrderDetailValue, formatOrderItemLabel } from "../lib/orderDetailDisplay";

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
  /** 药品目录物资 ID（仅 MEDICATION 必填，025 起取代自由文本药名） */
  materialId: string;
  /** 目录物资名，选中目录药品后由目录覆写，随 drug_name 提交 */
  drugName: string;
  dose: string;
  unit: string;
  /**
   * 每次数量（基础单位个数，如 1 片）；默认 `1`（计划 033 §7 C），可清空、可改。
   * 留空时提交维持现状（032 P4：不写 dose_quantity → 药房不做数量预填）。
   */
  doseQuantity: string;
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
  remark: string;
}

const orderFormDefaults: OrderForm = {
  orderType: "MEDICATION",
  orderClass: "LONG_TERM",
  orderContent: "",
  doctor: "",
  startTime: "",
  endTime: "",
  materialId: "",
  drugName: "",
  dose: "",
  unit: "",
  doseQuantity: "1",
  route: "",
  frequencyCode: "",
  frequencyName: "",
  durationDays: "",
  remark: "",
  treatmentItem: "",
  itemName: "",
};

/** 十进制正数校验（每次数量）：非空时必须是正数；空值表示不填、提交时维持现状 */
function isPositiveDecimalText(value: string): boolean {
  return /^\d+(\.\d+)?$/.test(value) && Number(value) > 0;
}

function hasOrderFieldError(form: OrderForm, field: string): boolean {
  switch (field) {
    case "orderContent":
      return !form.orderContent.trim();
    case "doctor":
      return !form.doctor.trim();
    case "startTime":
      return !form.startTime.trim();
    case "materialId":
      // 用药医嘱只能从药品目录选择：必须有目录物资 ID，服务端同样以 material_id 强校验
      return form.orderType === "MEDICATION" && !form.materialId.trim();
    case "treatmentItem":
      return form.orderType === "THERAPY" && !form.treatmentItem.trim();
    case "itemName":
      return (form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && !form.itemName.trim();
    case "durationDays": {
      const value = form.durationDays.trim();
      if (value === "") return false;
      const days = Number(value);
      return !Number.isInteger(days) || days < 1;
    }
    case "endTime":
      return form.orderClass === "TEMPORARY" && !form.endTime.trim() && !form.durationDays.trim() && form.frequencyCode !== "STAT";
    case "doseQuantity": {
      const value = form.doseQuantity.trim();
      if (value === "") return false;
      return !isPositiveDecimalText(value);
    }
    default:
      return false;
  }
}

const noteFormDefaults: NoteForm = { content: "", physician: "", recordTime: "" };

const diagnosisFormDefaults: DiagnosisForm = {
  diagnosisType: "PRIMARY",
  diagnosisText: "",
  icdCode: "",
  diagnosisDate: todayLocal(),
  physician: "",
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

/** 给药汇总项：已给/拒服/漏服/暂缓次数与数量，未发药时 dispensed/remaining 为 null */
const ADMINISTRATION_SUMMARY_ITEMS: Array<[keyof MedicalOrderAdministrationSummary, string]> = [
  ["administered_count", "已给次数"],
  ["administered_quantity", "已给数量"],
  ["partial_count", "部分服"],
  ["refused_count", "拒服"],
  ["missed_count", "漏服"],
  ["deferred_count", "暂缓"],
  ["dispensed_quantity", "已发数量"],
  ["remaining_quantity", "剩余数量"],
];

function isConsumingResult(result: string): boolean {
  return result === "已服" || result === "部分服";
}

const ORDER_DETAIL_LABELS: Record<string, string> = {
  drug_name: "药名",
  material_id: "药品物资 ID",
  material_code: "药品目录编码",
  material_name: "药品目录名称",
  material_bound_by: "补绑操作人",
  material_bound_at: "补绑时间",
  dose: "剂量",
  dose_quantity: "每次数量（基础单位）",
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

/** 各医嘱类型的主明细字段见 `../lib/orderDetailDisplay`（与护理页共用同一推导规则） */

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const textareaClass = "w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const radioClass = "h-4 w-4 border-border bg-surface accent-accent";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 已到期时长（§3.3 派生字段 expired_minutes）：按天/小时展示，避免超长小时数 */
function formatExpiredMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "";
  if (minutes < 60) return `已到期 ${minutes} 分钟`;
  if (minutes < 1440) {
    const hours = Math.floor(minutes / 60);
    const remaining = minutes % 60;
    return remaining === 0 ? `已到期 ${hours} 小时` : `已到期 ${hours} 小时 ${remaining} 分钟`;
  }
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  return hours === 0 ? `已到期 ${days} 天` : `已到期 ${days} 天 ${hours} 小时`;
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
  // ——— 收束已到期医嘱（027 §4.5，显式、幂等） ———
  const [converging, setConverging] = useState(false);
  const [convergeError, setConvergeError] = useState("");
  const [convergeMessage, setConvergeMessage] = useState("");
  /** 上次收束的实际条数：0 时反馈必须是中性提示，不能用成功语气（P2-1） */
  const [convergeClosed, setConvergeClosed] = useState<number | null>(null);
  const [convergeWarningCount, setConvergeWarningCount] = useState(0);

  // —— 药品目录（025：药品 = materials 中 category='药品' 且 status='ACTIVE'）——
  const [drugCatalog, setDrugCatalog] = useState<InventoryMaterial[]>([]);
  const [drugCatalogLoading, setDrugCatalogLoading] = useState(true);
  const [drugCatalogError, setDrugCatalogError] = useState("");

  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<OrderForm>(orderFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const [detailTarget, setDetailTarget] = useState<MedicalOrder | null>(null);
  // ——— 给药汇总与明细（医嘱侧只读） ———
  const [adminRecords, setAdminRecords] = useState<MedicalOrderAdministration[]>([]);
  const [adminRecordsLoading, setAdminRecordsLoading] = useState(false);
  const [adminRecordsError, setAdminRecordsError] = useState("");
  const [detail, setDetail] = useState<MedicalOrder | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [statusAction, setStatusAction] = useState("");
  const [statusError, setStatusError] = useState("");

  /** 加载药品目录：药品 = materials 中 category='药品' 且 status='ACTIVE'（025 冻结契约） */
  const loadDrugCatalog = useCallback(async () => {
    setDrugCatalogLoading(true);
    setDrugCatalogError("");
    try {
      const response = await listInventoryMaterials({ category: "药品", status: "ACTIVE", limit: 200 });
      setDrugCatalog(response.records);
    } catch (error) {
      setDrugCatalog([]);
      setDrugCatalogError(errorMessage(error, "无法加载药品目录"));
    } finally {
      setDrugCatalogLoading(false);
    }
  }, []);

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
    void loadDrugCatalog();
  }, [loadDrugCatalog]);

  useEffect(() => {
    void loadNotes();
  }, [loadNotes]);

  useEffect(() => {
    void loadDiagnoses();
  }, [loadDiagnoses]);

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  // 切换入住时清空收束反馈，避免上一位长者的收束结果残留在界面上
  useEffect(() => {
    setConvergeError("");
    setConvergeMessage("");
    setConvergeClosed(null);
    setConvergeWarningCount(0);
  }, [selectedEncounterId]);

  const selectedAdmission = admissions.find((admission) => admission.id === selectedEncounterId) ?? null;
  // 已离院/已去世等非活动入住只读历史
  const isReadOnly = selectedAdmission !== null && selectedAdmission.status !== "ACTIVE";

  /**
   * 当前可见医嘱中已到期（ACTIVE 且 end_time < now）的条数（P2-1）：
   * 仅作为按钮上的提示计数，不作按钮可见性判据；可见列表受 order_type/status 筛选
   * 与 limit 100 影响，可能低于服务端实际收束范围，故不作声明。
   */
  const expiredOrderCount = orders.filter((order) => order.is_expired).length;

  const selectedDrugMaterial = drugCatalog.find((material) => material.id === form.materialId) ?? null;

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
        ...(recordTime ? { record_time: toOffsetDateTime(recordTime) } : {}),
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
        // is_major 由服务端按 diagnosis_type 派生，不再由表单提交
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

  /** 选中目录药品：写入 material_id，用目录物资名填充药名，并在「单位」为空时预填基础单位 */
  function handleDrugMaterialChange(materialId: string) {
    const material = drugCatalog.find((item) => item.id === materialId);
    setForm((current) => ({
      ...current,
      materialId,
      drugName: material?.name ?? "",
      unit: current.unit.trim() || material?.base_unit || "",
    }));
  }

  function buildOrderInput(): MedicalOrderInput | null {
    const orderContent = form.orderContent.trim();
    const doctor = form.doctor.trim();
    const startTime = form.startTime.trim();
    if (!orderContent || !doctor || !startTime) return null;

    const details: Record<string, unknown> = {};
    if (form.orderType === "MEDICATION") {
      // 服务端要求 material_id 必填，并以目录物资名覆写 drug_name；
      // 这里始终提交目录物资名，避免与目录漂移触发 400（drug_name must match catalog material name）
      const materialId = form.materialId.trim();
      if (!materialId) return null;
      details.material_id = materialId;
      details.drug_name = drugCatalog.find((item) => item.id === materialId)?.name ?? form.drugName.trim();
      if (form.dose.trim()) details.dose = form.dose.trim();
      if (form.unit.trim()) details.unit = form.unit.trim();
      const doseQuantity = form.doseQuantity.trim();
      if (doseQuantity) {
        // 仅在非空时写入 dose_quantity；正数小校验在前，权威精度校验在服务端
        if (!isPositiveDecimalText(doseQuantity)) return null;
        details.dose_quantity = doseQuantity;
      }
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
      start_time: toOffsetDateTime(startTime),
      ...(endTime ? { end_time: toOffsetDateTime(endTime) } : {}),
      ...(Object.keys(details).length > 0 ? { order_details: details } : {}),
    };
  }

  async function handleSave() {
    const input = buildOrderInput();
    if (!input) {
      if (!form.orderContent.trim() || !form.doctor.trim() || !form.startTime.trim()) {
        setFormError("医嘱说明、医生和开始时间不能为空");
      } else if (form.orderType === "MEDICATION" && !form.materialId.trim()) {
        setFormError("用药医嘱必须从药品目录选择药品");
      } else if (form.orderType === "THERAPY" && !form.treatmentItem.trim()) {
        setFormError("治疗医嘱必须填写治疗项目");
      } else if ((form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && !form.itemName.trim()) {
        setFormError("检查/检验医嘱必须填写项目名称");
      } else if (form.orderClass === "TEMPORARY" && !form.endTime.trim() && !form.durationDays.trim() && form.frequencyCode !== "STAT") {
        setFormError("临时医嘱必须填写结束时间、持续天数，或选择立即执行（STAT）");
      } else if (form.orderType === "MEDICATION" && form.doseQuantity.trim() && !isPositiveDecimalText(form.doseQuantity.trim())) {
        setFormError("每次数量（基础单位）必须为正数");
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
    setAdminRecords([]);
    setAdminRecordsError("");
    setAdminRecordsLoading(order.order_type === "MEDICATION");
    setDetailLoading(true);
    try {
      const data = await getMedicalOrder(order.id);
      setDetail(data);
      // 给药明细只读加载（仅用药医嘱），失败不阻塞详情展示
      if (order.order_type === "MEDICATION") {
        try {
          const response = await listOrderAdministrations(order.id);
          setAdminRecords(response.records);
        } catch (error) {
          setAdminRecordsError(errorMessage(error, "无法加载给药明细"));
        } finally {
          setAdminRecordsLoading(false);
        }
      }
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
    setAdminRecords([]);
    setAdminRecordsError("");
    setAdminRecordsLoading(false);
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

  /** 收束已到期医嘱（027 §4.5，显式、幂等）：显式二次确认 → 幂等 POST → 刷新列表与详情 */
  async function handleCloseExpiredOrders() {
    if (!selectedAdmission) return;
    // 服务端按 encounter_id 收束「该入住全部 ACTIVE 且已到期」的医嘱，
    // 可见列表可能被类型/状态筛选与 limit 截断，故确认文案只描述范围，不声称具体条数（P2-1）。
    const confirmed = window.confirm(
      `确认收束「${selectedAdmission.patientName}」本入住全部已到期（结束时间已过）且状态为进行中的医嘱吗？\n` +
        "收束后医嘱状态变为已完成：结束时间保留原值，其护理任务被结束；" +
        "未执行的护理记录保持原状态，仍会出现在逾期队列中提醒。",
    );
    if (!confirmed) return;
    setConverging(true);
    setConvergeError("");
    setConvergeMessage("");
    setConvergeClosed(null);
    setConvergeWarningCount(0);
    try {
      const result = await closeExpiredMedicalOrders({ encounter_id: selectedAdmission.id });
      // closed > 0 才是成功语气；closed === 0 用中性提示（幂等重放/确实无到期医嘱）
      setConvergeMessage(result.closed > 0 ? `已收束 ${result.closed} 条已到期医嘱` : "没有已到期医嘱");
      setConvergeClosed(result.closed);
      setConvergeWarningCount(result.warnings.length);
      await loadOrders();
      if (detailTarget) {
        // 详情刷新失败不覆盖收束结果，避免误导为「收束失败」
        try {
          setDetail(await getMedicalOrder(detailTarget.id));
        } catch {
          /* 忽略：列表已刷新，详情可手动重开 */
        }
      }
    } catch (error) {
      setConvergeError(errorMessage(error, "无法收束已到期医嘱"));
    } finally {
      setConverging(false);
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
      // 列表原本只显示自由文本的医嘱正文，用药医嘱看上去「没有药」。这里补出各类型的
      // 主明细字段；用药医嘱额外带剂量与用法，因为只有药名的医嘱无法执行。
      // 推导规则与护理页共用（`../lib/orderDetailDisplay`），避免两页口径漂移。
      key: "order_detail",
      header: "药品 / 项目",
      className: "min-w-[170px] max-w-[240px]",
      render: (row) => {
        const text = formatOrderItemLabel(row.order_type, row.order_details);
        if (text === null) return <span className="text-fg-dimmed">-</span>;
        return (
          <span className="block truncate" title={text}>
            {text}
          </span>
        );
      },
    },
    {
      key: "order_content",
      header: "医嘱说明",
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
        <div className="flex flex-wrap items-center gap-1">
          <Badge variant={ORDER_STATUS_VARIANT[row.status] ?? "default"}>
            {ORDER_STATUS_LABEL[row.status] ?? row.status}
          </Badge>
          {row.is_expired && (
            <span title={row.expired_minutes != null ? formatExpiredMinutes(row.expired_minutes) : "已过结束时间"}>
              <Badge variant="danger">已到期</Badge>
            </span>
          )}
        </div>
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
      // 主/次只由 diagnosis_type 决定；这里按派生值渲染，存量数据中不一致的 is_major 不会显示成矛盾行
      render: (row) =>
        row.diagnosis_type === "PRIMARY" ? <Badge variant="success">主要</Badge> : <span className="text-fg-dimmed">-</span>,
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
          <p className="mt-1 text-sm text-fg-muted">选择入住记录，书写病程记录、录入诊断并开立医嘱；已离院记录仅可查看</p>
        </div>
        {admissions.length > 0 && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="orders-encounter">入住长者</label>
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
            title="暂无入住长者"
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
                <span>住院号：{selectedAdmission.encounter_no}</span>
                <span>床位：{selectedAdmission.ward || selectedAdmission.department || "未设置"}</span>
                <span>入住日期：{formatDateTime(selectedAdmission.admit_date)}</span>
                <Badge variant={selectedAdmission.status === "ACTIVE" ? "success" : "warning"}>
                  {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
                </Badge>
              </div>
              {isReadOnly && (
                <p className="mt-1 text-sm">该入住{ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? "已结束"}，仅可查看历史病程、诊断和医嘱。</p>
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
              {!isReadOnly && (
                <Button
                  variant="warning"
                  size="md"
                  loading={converging}
                  disabled={converging}
                  onClick={() => void handleCloseExpiredOrders()}
                  title="把已过结束时间但仍为「进行中」的医嘱收束为「已完成」，并结束其护理任务；作用范围为本入住全部已到期医嘱，与当前筛选无关"
                >
                  收束已到期医嘱{expiredOrderCount > 0 ? ` (${expiredOrderCount})` : ""}
                </Button>
              )}
            </div>

            {convergeError && (
              <div className="mb-4 rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {convergeError}
              </div>
            )}

            {convergeMessage && (
              <div className={`mb-4 rounded-lg border px-4 py-3 text-sm ${convergeClosed === 0 ? "border-info/30 bg-info-bg text-info" : "border-success/30 bg-success-bg text-success"}`}>
                {convergeMessage}
              </div>
            )}

            {convergeWarningCount > 0 && (
              <div className="mb-4 rounded-lg border border-info/30 bg-info-bg px-4 py-3 text-sm text-info">
                另有 {convergeWarningCount} 条医嘱的关联护理任务结束异常，已记录为警告；收束本身已完成，不影响医嘱状态。
              </div>
            )}

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
            <div id="order-form-error" role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
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
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-content">医嘱说明（用法/临床备注，必填）</label>
              <textarea
                id="order-content"
                className={textareaClass}
                rows={3}
                maxLength={2000}
                value={form.orderContent}
                onChange={(event) => setForm((current) => ({ ...current, orderContent: event.target.value }))}
                placeholder="请输入医嘱说明（用法/临床备注），最多 2000 字"
                required
                aria-invalid={formError && hasOrderFieldError(form, "orderContent") ? true : undefined}
                aria-describedby={formError && hasOrderFieldError(form, "orderContent") ? "order-form-error" : undefined}
              />
              <p className="text-xs text-fg-dimmed">
                这里填写用法与临床备注；
                <span className="font-medium text-fg-muted">药品/项目等结构化事实请在下方『药品/项目』中选择</span>
                ，不要只写在说明里。
              </p>
            </div>

            <div className="sm:col-span-2">
              <Input
                id="order-doctor"
                label="医生（必填）"
                value={form.doctor}
                onChange={(event) => setForm((current) => ({ ...current, doctor: event.target.value }))}
                placeholder="请输入开嘱医生"
                maxLength={100}
                required
                aria-invalid={formError && hasOrderFieldError(form, "doctor") ? true : undefined}
                aria-describedby={formError && hasOrderFieldError(form, "doctor") ? "order-form-error" : undefined}
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                id="order-start-time"
                label="开始时间（必填）"
                type="datetime-local"
                value={form.startTime}
                onChange={(event) => setForm((current) => ({ ...current, startTime: event.target.value }))}
                required
                aria-invalid={formError && hasOrderFieldError(form, "startTime") ? true : undefined}
                aria-describedby={formError && hasOrderFieldError(form, "startTime") ? "order-form-error" : undefined}
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                id="order-end-time"
                label="结束时间（临时医嘱可选）"
                type="datetime-local"
                value={form.endTime}
                onChange={(event) => setForm((current) => ({ ...current, endTime: event.target.value }))}
                min={form.startTime || undefined}
                aria-invalid={formError && hasOrderFieldError(form, "endTime") ? true : undefined}
                aria-describedby={formError && hasOrderFieldError(form, "endTime") ? "order-form-error" : undefined}
              />
            </div>

            {form.orderType === "MEDICATION" && (
              <>
                <div className="flex flex-col gap-1.5 sm:col-span-2">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="order-drug-material">
                    药名（必填，取自药品目录）
                  </label>
                  <select
                    id="order-drug-material"
                    className={selectClass}
                    value={form.materialId}
                    disabled={drugCatalogLoading || drugCatalogError !== ""}
                    onChange={(event) => handleDrugMaterialChange(event.target.value)}
                    required
                    aria-invalid={formError && hasOrderFieldError(form, "materialId") ? true : undefined}
                    aria-describedby={formError && hasOrderFieldError(form, "materialId") ? "order-form-error" : undefined}
                  >
                    <option value="">{drugCatalogLoading ? "正在加载药品目录…" : "请选择药品"}</option>
                    {drugCatalog.map((material) => (
                      <option key={material.id} value={material.id}>
                        {material.name}（{material.code}
                        {material.spec ? ` · ${material.spec}` : ""}）
                      </option>
                    ))}
                  </select>
                  {selectedDrugMaterial && (
                    <p className="text-xs text-fg-dimmed">
                      目录编码 {selectedDrugMaterial.code} · 基础单位 {selectedDrugMaterial.base_unit}
                      {selectedDrugMaterial.spec ? ` · 规格 ${selectedDrugMaterial.spec}` : ""}
                      {selectedDrugMaterial.enable_batch_control ? " · 批次管控" : ""}
                    </p>
                  )}
                  {drugCatalogError && (
                    <p role="alert" className="flex flex-wrap items-center gap-2 text-xs text-danger">
                      药品目录加载失败：{drugCatalogError}
                      <Button type="button" variant="link" size="sm" onClick={() => void loadDrugCatalog()}>
                        重试
                      </Button>
                    </p>
                  )}
                  {!drugCatalogLoading && !drugCatalogError && drugCatalog.length === 0 && (
                    <p className="text-xs text-warning">
                      药品目录为空：请先在「库存计量 → 物资」中新建类别为「药品」且状态为「启用」的物资，再回到此处开药。
                      <a href="/dashboard/materials" className="ml-1 text-accent hover:underline">
                        前往物资
                      </a>
                    </p>
                  )}
                </div>
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
                  label="每次数量（基础单位）"
                  value={form.doseQuantity}
                  onChange={(event) => setForm((current) => ({ ...current, doseQuantity: event.target.value }))}
                  placeholder="如 1 或 1.5（可清空）"
                  inputMode="decimal"
                  aria-invalid={formError && hasOrderFieldError(form, "doseQuantity") ? true : undefined}
                  aria-describedby={formError && hasOrderFieldError(form, "doseQuantity") ? "order-form-error" : undefined}
                />
                <Input
                  label="途径"
                  value={form.route}
                  onChange={(event) => setForm((current) => ({ ...current, route: event.target.value }))}
                  placeholder="如 口服"
                />
                <p className="text-xs text-fg-dimmed sm:col-span-2">
                  三者区别：剂量 = 单剂强度（如 500mg）；每次数量 = 每次给药的基础单位个数（如 1 片）；单位 = 每次给药的计量单位（如 片/次）。默认 1 表示每次 1 个基础单位；每次数量不是 1 时必须改成实际数量。留空（清空）则药房不做自动预填，发药时数量由药房自行填写。
                </p>
              </>
            )}

            {form.orderType === "THERAPY" && (
              <div className="sm:col-span-2">
                <Input
                  id="order-treatment-item"
                  label="诊疗项目（必填）"
                  value={form.treatmentItem}
                  onChange={(event) => setForm((current) => ({ ...current, treatmentItem: event.target.value }))}
                  placeholder="请输入诊疗项目"
                  required
                  aria-invalid={formError && hasOrderFieldError(form, "treatmentItem") ? true : undefined}
                  aria-describedby={formError && hasOrderFieldError(form, "treatmentItem") ? "order-form-error" : undefined}
                />
              </div>
            )}

            {(form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && (
              <div className="sm:col-span-2">
                <Input
                  id="order-item-name"
                  label="项目名称（必填）"
                  value={form.itemName}
                  onChange={(event) => setForm((current) => ({ ...current, itemName: event.target.value }))}
                  placeholder="请输入检查/检验项目名称"
                  required
                  aria-invalid={formError && hasOrderFieldError(form, "itemName") ? true : undefined}
                  aria-describedby={formError && hasOrderFieldError(form, "itemName") ? "order-form-error" : undefined}
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
                    id="order-duration-days"
                    label="时长（天）"
                    type="number"
                    min={1}
                    step={1}
                    value={form.durationDays}
                    onChange={(event) => setForm((current) => ({ ...current, durationDays: event.target.value }))}
                    placeholder="正整数"
                    aria-invalid={formError && hasOrderFieldError(form, "durationDays") ? true : undefined}
                    aria-describedby={formError && hasOrderFieldError(form, "durationDays") ? "order-form-error" : undefined}
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
                {detail.is_expired && (
                  <span className="ml-2 align-middle">
                    <Badge variant="danger">已到期</Badge>
                  </span>
                )}
              </p>
              <p className="sm:col-span-2"><span className="text-fg-dimmed">医嘱说明：</span>{detail.order_content}</p>
              <p><span className="text-fg-dimmed">医生：</span>{detail.doctor || "-"}</p>
              <p><span className="text-fg-dimmed">开始时间：</span>{formatDateTime(detail.start_time)}</p>
              <p><span className="text-fg-dimmed">结束时间：</span>{formatDateTime(detail.end_time)}</p>
              {detail.is_expired && detail.expired_minutes != null && (
                <p><span className="text-fg-dimmed">已到期时长：</span><span className="text-danger">{formatExpiredMinutes(detail.expired_minutes)}</span></p>
              )}
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
                      {formatOrderDetailValue(value)}
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

            {detail.order_type === "MEDICATION" && (
              <section>
                <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">给药汇总</h4>
                {detail.administration_summary ? (
                  <div className="flex flex-wrap gap-2">
                    {ADMINISTRATION_SUMMARY_ITEMS.map(([key, label]) => {
                      const value = detail.administration_summary?.[key];
                      return (
                        <span key={key} className="inline-flex items-center gap-1 rounded-full bg-surface-alt px-3 py-1 text-sm text-fg-muted">
                          <span className="font-semibold text-fg">{value == null ? "—" : String(value)}</span>
                          {label}
                        </span>
                      );
                    })}
                  </div>
                ) : (
                  <p className="text-sm text-fg-dimmed">暂无给药汇总</p>
                )}
              </section>
            )}

            {detail.order_type === "MEDICATION" && (
              <section>
                <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">给药明细</h4>
                {adminRecordsLoading ? (
                  <p className="text-sm text-fg-dimmed">正在读取给药明细…</p>
                ) : adminRecordsError ? (
                  <p className="text-sm text-danger">{adminRecordsError}</p>
                ) : adminRecords.length === 0 ? (
                  <p className="text-sm text-fg-dimmed">暂无给药记录</p>
                ) : (
                  <div className="space-y-2">
                    {adminRecords.map((record) => (
                      <div key={record.id} className="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm">
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                          <Badge variant={isConsumingResult(record.result) ? "success" as const : "default" as const}>{record.result}</Badge>
                          <span className="font-medium text-fg">{record.administered_quantity != null ? `${record.administered_quantity} ${record.unit ?? ""}` : "—"}</span>
                          <span className="text-fg-muted">{formatDateTime(record.administered_at)}</span>
                          <span className="text-fg-muted">{record.administered_by ?? "-"}</span>
                        </div>
                        <div className="mt-1 text-xs text-fg-muted">
                          {record.task_description ?? "-"}
                          {record.material_name ? ` · ${record.material_name}` : ""}
                          {record.batch_no ? ` · 批次 ${record.batch_no}` : ""}
                          {record.dispense_no ? ` · ${record.dispense_no}` : ""}
                        </div>
                        {record.reason && <div className="mt-1 text-xs text-fg-dimmed">原因：{record.reason}</div>}
                      </div>
                    ))}
                  </div>
                )}
              </section>
            )}

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
