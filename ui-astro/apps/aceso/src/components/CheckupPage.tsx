import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  addHealthCheckupMembers,
  convertCheckupResultToFollowup,
  convertCheckupResultToVitalSign,
  createHealthCheckup,
  createHealthCheckupResults,
  getHealthCheckup,
  getHealthCheckupStats,
  listHealthCheckupMembers,
  listHealthCheckupResults,
  listHealthCheckups,
  listPatients,
  updateHealthCheckupResult,
  updateHealthCheckupStatus,
  type HealthCheckup,
  type HealthCheckupMember,
  type HealthCheckupResult,
  type HealthCheckupResultInput,
  type HealthCheckupStats,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

const CHECKUP_STATUSES = ["草稿", "进行中", "已完成"] as const;
const filterSelectClass = "h-8 rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const ITEM_CATEGORIES = ["数值", "文本"] as const;
const FOLLOWUP_TYPES = ["慢病随访", "常规电话随访"] as const;
const FOLLOWUP_WAYS = ["电话", "上门", "门诊"] as const;

/** 数值体检项快捷录入模板：项目名、单位、参考范围（可改） */
const NUMERIC_ITEM_PRESETS: { item_name: string; unit: string; ref_min: string; ref_max: string }[] = [
  { item_name: "收缩压", unit: "mmHg", ref_min: "90", ref_max: "139" },
  { item_name: "舒张压", unit: "mmHg", ref_min: "60", ref_max: "89" },
  { item_name: "空腹血糖", unit: "mmol/L", ref_min: "3.9", ref_max: "6.1" },
  { item_name: "体温", unit: "℃", ref_min: "36.0", ref_max: "37.3" },
  { item_name: "心率", unit: "次/分", ref_min: "60", ref_max: "100" },
  { item_name: "血氧饱和度", unit: "%", ref_min: "95", ref_max: "100" },
  { item_name: "体重", unit: "kg", ref_min: "", ref_max: "" },
];

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

function todayLocal(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  return value.slice(0, 10);
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  return value.slice(0, 16).replace("T", " ");
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00`);
  d.setDate(d.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function statusBadge(status: string) {
  switch (status) {
    case "草稿":
      return <Badge>草稿</Badge>;
    case "进行中":
      return <Badge variant="warning">进行中</Badge>;
    case "已完成":
      return <Badge variant="success">已完成</Badge>;
    default:
      return <Badge>{status}</Badge>;
  }
}

function abnormalBadge(abnormal: boolean) {
  return abnormal ? <Badge variant="danger">异常</Badge> : <Badge variant="success">正常</Badge>;
}

interface CreateForm {
  checkup_year: string;
  name: string;
  start_date: string;
  end_date: string;
}

const createFormDefaults: CreateForm = {
  checkup_year: String(new Date().getFullYear()),
  name: "",
  start_date: "",
  end_date: "",
};

interface ResultForm {
  patient_id: string;
  item_name: string;
  item_category: "数值" | "文本";
  value: string;
  unit: string;
  text_value: string;
  ref_min: string;
  ref_max: string;
  abnormal: string;
  exam_date: string;
}

const resultFormDefaults: ResultForm = {
  patient_id: "",
  item_name: "",
  item_category: "数值",
  value: "",
  unit: "",
  text_value: "",
  ref_min: "",
  ref_max: "",
  abnormal: "false",
  exam_date: todayLocal(),
};

interface FollowupForm {
  followup_type: string;
  planned_date: string;
  planned_way: string;
  remark: string;
}

export default function CheckupPage() {
  // ——— 批次列表 ———
  const [checkups, setCheckups] = useState<HealthCheckup[]>([]);
  const [checkupTotal, setCheckupTotal] = useState(0);
  const [checkupPage, setCheckupPage] = useState(1);
  const [checkupStatus, setCheckupStatus] = useState("");
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  // ——— 批次详情 ———
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detail, setDetail] = useState<HealthCheckup | null>(null);
  const [stats, setStats] = useState<HealthCheckupStats | null>(null);
  const [detailTab, setDetailTab] = useState<"members" | "results" | "abnormal">("members");

  // 名单
  const [members, setMembers] = useState<HealthCheckupMember[]>([]);
  const [memberTotal, setMemberTotal] = useState(0);
  const [memberPage, setMemberPage] = useState(1);
  const [memberFilter, setMemberFilter] = useState("");

  // 结果
  const [results, setResults] = useState<HealthCheckupResult[]>([]);
  const [resultTotal, setResultTotal] = useState(0);
  const [resultPage, setResultPage] = useState(1);
  const [resultFilter, setResultFilter] = useState("");
  const [patientOptions, setPatientOptions] = useState<HealthCheckupMember[]>([]);

  // ——— 弹窗状态 ———
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateForm>(createFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  const [addMembersOpen, setAddMembersOpen] = useState(false);
  const [addMemberQuery, setAddMemberQuery] = useState("");
  const [addMemberCandidates, setAddMemberCandidates] = useState<
    { id: string; name: string }[]
  >([]);
  const [addMemberSelected, setAddMemberSelected] = useState<string[]>([]);
  const [addMemberError, setAddMemberError] = useState("");
  const [addingMembers, setAddingMembers] = useState(false);

  const [entryOpen, setEntryOpen] = useState(false);
  const [entryForm, setEntryForm] = useState<ResultForm>(resultFormDefaults);
  const [entryError, setEntryError] = useState("");
  const [savingEntry, setSavingEntry] = useState(false);

  const [patchResult, setPatchResult] = useState<HealthCheckupResult | null>(null);
  const [patchOpen, setPatchOpen] = useState(false);
  const [patchForm, setPatchForm] = useState<ResultForm>(resultFormDefaults);
  const [patchError, setPatchError] = useState("");
  const [savingPatch, setSavingPatch] = useState(false);

  const [converting, setConverting] = useState<string>("");
  const [conversionError, setConversionError] = useState("");

  const [followupResult, setFollowupResult] = useState<HealthCheckupResult | null>(null);
  const [followupOpen, setFollowupOpen] = useState(false);
  const [followupForm, setFollowupForm] = useState<FollowupForm>({
    followup_type: "慢病随访",
    planned_date: "",
    planned_way: "电话",
    remark: "",
  });
  const [followupError, setFollowupError] = useState("");
  const [savingFollowup, setSavingFollowup] = useState(false);

  // ——— 批次列表加载 ———

  const loadCheckups = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listHealthCheckups({
        ...(checkupStatus ? { status: checkupStatus as "草稿" | "进行中" | "已完成" } : {}),
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setCheckups(response.records);
      setCheckupTotal(response.meta.total);
      setCheckupPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载体检批次"));
    } finally {
      setLoading(false);
    }
  }, [checkupStatus]);

  useEffect(() => {
    loadCheckups(1);
  }, [loadCheckups]);

  // ——— 批次详情加载 ———

  const loadDetail = useCallback(async (id: string) => {
    setDetailId(id);
    setDetail(null);
    setStats(null);
    setPageError("");
    try {
      const [checkup, stat] = await Promise.all([getHealthCheckup(id), getHealthCheckupStats(id)]);
      setDetail(checkup);
      setStats(stat);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载批次详情"));
    }
  }, []);

  const closeDetail = useCallback(() => {
    setDetailId(null);
    setDetail(null);
    setStats(null);
    setMembers([]);
    setResults([]);
    setPatientOptions([]);
    loadCheckups(checkupPage);
  }, [checkupPage, loadCheckups]);

  const loadMembers = useCallback(
    async (targetPage: number) => {
      if (!detailId) return;
      setLoading(true);
      try {
        const response = await listHealthCheckupMembers(detailId, {
          ...(memberFilter ? { checked: memberFilter === "已检" } : {}),
          limit: PAGE_SIZE,
          offset: (targetPage - 1) * PAGE_SIZE,
        });
        setMembers(response.records);
        setMemberTotal(response.meta.total);
        setMemberPage(targetPage);
      } catch (error) {
        setPageError(errorMessage(error, "无法加载参检名单"));
      } finally {
        setLoading(false);
      }
    },
    [detailId, memberFilter],
  );

  const loadResults = useCallback(
    async (targetPage: number) => {
      if (!detailId) return;
      setLoading(true);
      try {
        const response = await listHealthCheckupResults(detailId, {
          ...(resultFilter === "异常" ? { abnormal: true } : resultFilter === "正常" ? { abnormal: false } : {}),
          limit: PAGE_SIZE,
          offset: (targetPage - 1) * PAGE_SIZE,
        });
        setResults(response.records);
        setResultTotal(response.meta.total);
        setResultPage(targetPage);
      } catch (error) {
        setPageError(errorMessage(error, "无法加载体检结果"));
      } finally {
        setLoading(false);
      }
    },
    [detailId, resultFilter],
  );

  const loadPatientOptions = useCallback(async () => {
    if (!detailId) return;
    try {
      const response = await listHealthCheckupMembers(detailId, { limit: 500 });
      setPatientOptions(response.records);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载参检名单"));
    }
  }, [detailId]);

  useEffect(() => {
    if (detailId) {
      loadMembers(1);
      loadResults(1);
      loadPatientOptions();
    }
  }, [detailId, loadMembers, loadResults, loadPatientOptions]);

  const refreshDetail = useCallback(async () => {
    if (!detailId) return;
    const [checkup, stat] = await Promise.all([getHealthCheckup(detailId), getHealthCheckupStats(detailId)]);
    setDetail(checkup);
    setStats(stat);
    await Promise.all([loadMembers(memberPage), loadResults(resultPage)]);
  }, [detailId, loadMembers, memberPage, loadResults, resultPage]);

  // ——— 创建批次 ———

  function openCreate() {
    setCreateForm({
      ...createFormDefaults,
      checkup_year: String(new Date().getFullYear()),
    });
    setCreateError("");
    setCreateOpen(true);
  }

  async function handleCreate() {
    setCreating(true);
    setCreateError("");
    try {
      const year = Number(createForm.checkup_year);
      if (!Number.isInteger(year) || year < 2000 || year > 2100) {
        throw new Error("年度须为 2000–2100 之间的整数");
      }
      if (!createForm.name.trim()) throw new Error("请填写批次名称");
      await createHealthCheckup({
        checkup_year: year,
        name: createForm.name.trim(),
        start_date: createForm.start_date || undefined,
        end_date: createForm.end_date || undefined,
      });
      setCreateOpen(false);
      loadCheckups(1);
    } catch (error) {
      setCreateError(errorMessage(error, "创建批次失败"));
    } finally {
      setCreating(false);
    }
  }

  // ——— 状态流转 ———

  const [transitioning, setTransitioning] = useState(false);
  const [transitionError, setTransitionError] = useState("");

  async function handleStatusTransition(next: "进行中" | "已完成") {
    if (!detail) return;
    setTransitioning(true);
    setTransitionError("");
    try {
      const updated = await updateHealthCheckupStatus(detail.id, { status: next });
      setDetail(updated);
      loadCheckups(checkupPage);
    } catch (error) {
      setTransitionError(errorMessage(error, "状态流转失败"));
    } finally {
      setTransitioning(false);
    }
  }

  // ——— 名单补录 ———

  async function openAddMembers() {
    setAddMembersOpen(true);
    setAddMemberQuery("");
    setAddMemberSelected([]);
    setAddMemberError("");
    try {
      const response = await listPatients({ status: "ACTIVE", limit: 500 });
      setAddMemberCandidates(response.records.map((patient) => ({ id: patient.id, name: patient.name })));
    } catch (error) {
      setAddMemberError(errorMessage(error, "无法加载在册患者"));
    }
  }

  function toggleCandidate(id: string) {
    setAddMemberSelected((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id],
    );
  }

  async function handleAddMembers() {
    if (!detailId) return;
    if (addMemberSelected.length === 0) {
      setAddMemberError("请至少选择一位患者");
      return;
    }
    setAddingMembers(true);
    setAddMemberError("");
    try {
      await addHealthCheckupMembers(detailId, { patient_ids: addMemberSelected });
      setAddMembersOpen(false);
      loadMembers(1);
      loadPatientOptions();
      refreshDetail();
    } catch (error) {
      setAddMemberError(errorMessage(error, "补录名单失败"));
    } finally {
      setAddingMembers(false);
    }
  }

  // ——— 结果录入 ———

  function openEntry(member?: HealthCheckupMember) {
    setEntryForm({
      ...resultFormDefaults,
      patient_id: member?.patient_id ?? "",
      exam_date: todayLocal(),
    });
    setEntryError("");
    setEntryOpen(true);
  }

  function applyPreset(item: string) {
    const preset = NUMERIC_ITEM_PRESETS.find((entry) => entry.item_name === item);
    setEntryForm((prev) => ({
      ...prev,
      item_name: item,
      item_category: "数值",
      unit: preset?.unit ?? prev.unit,
      ref_min: preset?.ref_min ?? prev.ref_min,
      ref_max: preset?.ref_max ?? prev.ref_max,
    }));
  }

  function buildResultInput(form: ResultForm): HealthCheckupResultInput {
    const base = {
      patient_id: form.patient_id,
      item_name: form.item_name,
      item_category: form.item_category,
      exam_date: form.exam_date || todayLocal(),
    };
    if (form.item_category === "数值") {
      if (form.value.trim() === "") throw new Error("请填写数值");
      const value = Number(form.value);
      if (!Number.isFinite(value) || value <= 0) throw new Error("数值必须为正数");
      const unit = form.unit.trim();
      if (!unit) throw new Error("请填写单位");
      const input: HealthCheckupResultInput = {
        ...base,
        value,
        unit,
      };
      if (form.ref_min.trim() !== "") {
        const refMin = Number(form.ref_min);
        const refMax = Number(form.ref_max);
        if (!Number.isFinite(refMin) || !Number.isFinite(refMax) || refMin < 0 || refMax < 0) {
          throw new Error("参考范围必须为非负数");
        }
        if (refMin > refMax) throw new Error("参考下限不能大于上限");
        input.ref_min = refMin;
        input.ref_max = refMax;
      }
      return input;
    }
    if (form.text_value.trim() === "") throw new Error("请填写文本结论");
    return {
      ...base,
      text_value: form.text_value.trim(),
      abnormal: form.abnormal === "true",
    };
  }

  async function handleSaveEntry() {
    if (!detailId) return;
    setSavingEntry(true);
    setEntryError("");
    try {
      const input = buildResultInput(entryForm);
      await createHealthCheckupResults(detailId, input);
      setEntryOpen(false);
      loadResults(1);
      refreshDetail();
    } catch (error) {
      setEntryError(errorMessage(error, "录入结果失败"));
    } finally {
      setSavingEntry(false);
    }
  }

  // ——— 结果修正 ———

  function openPatch(result: HealthCheckupResult) {
    setPatchResult(result);
    setPatchForm({
      patient_id: result.patient_id,
      item_name: result.item_name,
      item_category: result.item_category,
      value: result.value != null ? String(result.value) : "",
      unit: result.unit ?? "",
      text_value: result.text_value ?? "",
      ref_min: result.ref_min != null ? String(result.ref_min) : "",
      ref_max: result.ref_max != null ? String(result.ref_max) : "",
      abnormal: String(result.abnormal),
      exam_date: result.exam_date,
    });
    setPatchError("");
    setPatchOpen(true);
  }

  async function handleSavePatch() {
    if (!patchResult) return;
    setSavingPatch(true);
    setPatchError("");
    try {
      const form = patchForm;
      const input: Record<string, unknown> = {};
      if (form.item_category === "数值") {
        if (form.value.trim() === "") throw new Error("请填写数值");
        const value = Number(form.value);
        if (!Number.isFinite(value) || value <= 0) throw new Error("数值必须为正数");
        input.value = value;
        if (form.unit.trim()) input.unit = form.unit.trim();
        if (form.ref_min.trim() !== "" || form.ref_max.trim() !== "") {
          if (form.ref_min.trim() === "" || form.ref_max.trim() === "") {
            throw new Error("参考下限与上限须同时填写");
          }
          const refMin = Number(form.ref_min);
          const refMax = Number(form.ref_max);
          if (!Number.isFinite(refMin) || !Number.isFinite(refMax) || refMin < 0 || refMax < 0) {
            throw new Error("参考范围必须为非负数");
          }
          if (refMin > refMax) throw new Error("参考下限不能大于上限");
          input.ref_min = refMin;
          input.ref_max = refMax;
        }
        if (form.exam_date) input.exam_date = form.exam_date;
      } else {
        if (form.text_value.trim()) input.text_value = form.text_value.trim();
        input.abnormal = form.abnormal === "true";
        if (form.exam_date) input.exam_date = form.exam_date;
      }
      await updateHealthCheckupResult(patchResult.id, input);
      setPatchOpen(false);
      loadResults(resultPage);
      refreshDetail();
    } catch (error) {
      setPatchError(errorMessage(error, "修正结果失败"));
    } finally {
      setSavingPatch(false);
    }
  }

  // ——— 异常转体征 / 转随访 ———

  const [conversionMessage, setConversionMessage] = useState("");

  async function handleToVitalSign(result: HealthCheckupResult) {
    setConverting(result.id);
    setConversionError("");
    setConversionMessage("");
    try {
      const response = await convertCheckupResultToVitalSign(result.id);
      setConversionMessage(
        `已将「${result.item_name}」转为体征记录（${response.vital_sign.type}，` +
          `${response.vital_sign.value} ${response.vital_sign.unit}），进入异常复核流程。`,
      );
      loadResults(resultPage);
      refreshDetail();
    } catch (error) {
      setConversionError(errorMessage(error, "转体征失败"));
    } finally {
      setConverting("");
    }
  }

  function openToFollowup(result: HealthCheckupResult) {
    setFollowupResult(result);
    setFollowupForm({
      followup_type: "慢病随访",
      planned_date: addDays(result.exam_date, 7),
      planned_way: "电话",
      remark: "",
    });
    setFollowupError("");
    setFollowupOpen(true);
  }

  async function handleToFollowup() {
    if (!followupResult) return;
    setSavingFollowup(true);
    setFollowupError("");
    try {
      const response = await convertCheckupResultToFollowup(followupResult.id, {
        followup_type: followupForm.followup_type,
        ...(followupForm.planned_date ? { planned_date: followupForm.planned_date } : {}),
        planned_way: followupForm.planned_way,
        ...(followupForm.remark.trim() ? { remark: followupForm.remark.trim() } : {}),
      });
      setFollowupOpen(false);
      setConversionMessage(
        `已为「${followupResult.item_name}」生成随访计划（${response.followup_plan.followup_type}，` +
          `计划日 ${response.followup_plan.planned_date}，责任人 ${response.followup_plan.assignee}）。`,
      );
      loadResults(resultPage);
      refreshDetail();
    } catch (error) {
      setFollowupError(errorMessage(error, "转随访失败"));
    } finally {
      setSavingFollowup(false);
    }
  }

  // ——— 表格列 ———

  const checkupColumns: Column<HealthCheckup>[] = [
    {
      key: "checkup_year",
      header: "年度",
      render: (row) => <span className="font-medium">{row.checkup_year}</span>,
    },
    { key: "name", header: "批次名称", render: (row) => <span className="text-fg-emphasis">{row.name}</span> },
    { key: "status", header: "状态", render: (row) => statusBadge(row.status) },
    {
      key: "progress",
      header: "完成进度",
      render: (row) => (
        <div className="flex items-center gap-2">
          <span className="text-fg-muted">
            {row.checked_total}/{row.member_total}
          </span>
          {row.member_total > 0 && (
            <div className="w-24 h-1.5 rounded-full bg-surface-alt overflow-hidden">
              <div
                className="h-full rounded-full bg-accent"
                style={{ width: `${Math.min(100, Math.round((row.checked_total * 100) / row.member_total))}%` }}
              />
            </div>
          )}
        </div>
      ),
    },
    {
      key: "period",
      header: "体检日期",
      render: (row) => (
        <span className="text-fg-muted">
          {formatDate(row.start_date)} ~ {formatDate(row.end_date)}
        </span>
      ),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button size="sm" variant="secondary" onClick={() => loadDetail(row.id)}>
          详情
        </Button>
      ),
    },
  ];

  const memberColumns: Column<HealthCheckupMember>[] = [
    { key: "patient_name", header: "姓名", render: (row) => <span className="text-fg-emphasis">{row.patient_name || row.patient_id}</span> },
    { key: "encounter_no", header: "周期号", render: (row) => <span className="text-fg-muted">{row.encounter_no || "—"}</span> },
    {
      key: "checked",
      header: "状态",
      render: (row) =>
        row.checked ? <Badge variant="success">已检</Badge> : <Badge variant="warning">未检</Badge>,
    },
    { key: "checked_at", header: "完成时间", render: (row) => <span className="text-fg-muted">{formatDateTime(row.checked_at)}</span> },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          size="sm"
          variant={row.checked ? "functional" : "primary"}
          disabled={detail?.status === "已完成"}
          onClick={() => (row.checked ? openEntry(row) : openEntry(row))}
        >
          {row.checked ? "补录" : "录入结果"}
        </Button>
      ),
    },
  ];

  const resultColumns: Column<HealthCheckupResult>[] = [
    { key: "patient_name", header: "姓名", render: (row) => <span className="text-fg-emphasis">{row.patient_name || row.patient_id}</span> },
    { key: "item_name", header: "项目", render: (row) => <span>{row.item_name}</span> },
    {
      key: "value",
      header: "结果",
      render: (row) => (
        <span className={row.abnormal ? "text-danger font-medium" : ""}>
          {row.item_category === "数值"
            ? `${row.value ?? "—"} ${row.unit ?? ""}`
            : row.text_value || "—"}
        </span>
      ),
    },
    {
      key: "range",
      header: "参考范围",
      render: (row) =>
        row.item_category === "数值" && row.ref_min != null && row.ref_max != null ? (
          <span className="text-fg-muted">
            {row.ref_min} ~ {row.ref_max} {row.unit ?? ""}
          </span>
        ) : (
          <span className="text-fg-dimmed">—</span>
        ),
    },
    { key: "abnormal", header: "判定", render: (row) => abnormalBadge(row.abnormal) },
    {
      key: "exam_date",
      header: "体检日期",
      render: (row) => <span className="text-fg-muted">{formatDate(row.exam_date)}</span>,
    },
    {
      key: "converted",
      header: "转出",
      render: (row) => (
        <div className="flex flex-wrap gap-1">
          {row.vital_sign_id && <Badge variant="info">已转体征</Badge>}
          {row.followup_plan_id && <Badge variant="info">已转随访</Badge>}
          {!row.vital_sign_id && !row.followup_plan_id && (
            <span className="text-fg-dimmed">—</span>
          )}
        </div>
      ),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex items-center gap-2">
          <Button size="sm" variant="functional" disabled={detail?.status === "已完成"} onClick={() => openPatch(row)}>
            修正
          </Button>
          {row.abnormal && !row.vital_sign_id && (
            <Button
              size="sm"
              variant="warning"
              loading={converting === row.id}
              disabled={detail?.status === "已完成"}
              onClick={() => handleToVitalSign(row)}
            >
              转体征
            </Button>
          )}
          {row.abnormal && !row.followup_plan_id && (
            <Button
              size="sm"
              variant="secondary"
              disabled={detail?.status === "已完成"}
              onClick={() => openToFollowup(row)}
            >
              转随访
            </Button>
          )}
        </div>
      ),
    },
  ];

  // ——— 渲染 ———

  if (!detailId) {
    return (
      <div className="space-y-6">
        {pageError && (
          <div className="px-4 py-3 rounded-md bg-danger-bg text-danger text-sm">{pageError}</div>
        )}
        <Card
          title="体检批次"
          actions={
            <Button size="sm" onClick={openCreate}>
              + 新建批次
            </Button>
          }
        >
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xs text-fg-muted">状态筛选</span>
            <select
              className={filterSelectClass + " w-28"}
              value={checkupStatus}
              onChange={(event) => {
                setCheckupStatus(event.target.value);
                setCheckupPage(1);
              }}
            >
              <option value="">全部</option>
              {CHECKUP_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          <Table
            columns={checkupColumns}
            data={checkups}
            loading={loading}
            emptyMessage="暂无体检批次，点击右上角新建"
          />
          {checkupTotal > PAGE_SIZE && (
            <div className="flex items-center justify-between mt-4">
              <span className="text-xs text-fg-dimmed">共 {checkupTotal} 条</span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={checkupPage <= 1}
                  onClick={() => loadCheckups(checkupPage - 1)}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={checkupPage * PAGE_SIZE >= checkupTotal}
                  onClick={() => loadCheckups(checkupPage + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </Card>

        <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="新建体检批次">
          <div className="space-y-4">
            <Input
              label="年度"
              value={createForm.checkup_year}
              onChange={(event) => setCreateForm({ ...createForm, checkup_year: event.target.value })}
              placeholder="如 2026"
            />
            <Input
              label="批次名称"
              value={createForm.name}
              onChange={(event) => setCreateForm({ ...createForm, name: event.target.value })}
              placeholder="如 2026 年度体检"
            />
            <div className="grid grid-cols-2 gap-4">
              <Input
                label="开始日期"
                type="date"
                value={createForm.start_date}
                onChange={(event) => setCreateForm({ ...createForm, start_date: event.target.value })}
              />
              <Input
                label="结束日期"
                type="date"
                value={createForm.end_date}
                onChange={(event) => setCreateForm({ ...createForm, end_date: event.target.value })}
              />
            </div>
            <p className="text-xs text-fg-dimmed">
              创建时将自动快照本机构在册人员作为参检名单（可后续补录）。
            </p>
            {createError && <p className="text-sm text-danger">{createError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setCreateOpen(false)}>
                取消
              </Button>
              <Button loading={creating} onClick={handleCreate}>
                创建
              </Button>
            </div>
          </div>
        </Modal>
      </div>
    );
  }

  // ——— 批次详情视图 ———
  return (
    <div className="space-y-6">
      {pageError && (
        <div className="px-4 py-3 rounded-md bg-danger-bg text-danger text-sm">{pageError}</div>
      )}

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button variant="functional" size="sm" onClick={closeDetail}>
            ← 返回
          </Button>
          <h2 className="text-lg font-semibold text-fg-emphasis">
            {detail?.name ?? "批次详情"}
            <span className="ml-2 text-sm font-normal text-fg-muted">
              {detail?.checkup_year} 年度
            </span>
          </h2>
          {detail && statusBadge(detail.status)}
        </div>
        <div className="flex items-center gap-2">
          {detail?.status === "草稿" && (
            <Button size="sm" loading={transitioning} onClick={() => handleStatusTransition("进行中")}>
              发布名单
            </Button>
          )}
          {detail?.status === "进行中" && (
            <Button size="sm" variant="secondary" loading={transitioning} onClick={() => handleStatusTransition("已完成")}>
              完成批次
            </Button>
          )}
        </div>
      </div>

      {transitionError && (
        <div className="px-4 py-3 rounded-md bg-danger-bg text-danger text-sm">{transitionError}</div>
      )}

      {/* 统计卡片 */}
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">应检人数</p>
          <p className="text-2xl font-semibold text-fg-emphasis mt-1">{stats?.member_total ?? 0}</p>
        </Card>
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">已检人数</p>
          <p className="text-2xl font-semibold text-fg-emphasis mt-1">{stats?.checked_total ?? 0}</p>
        </Card>
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">完成率</p>
          <p className="text-2xl font-semibold text-accent mt-1">
            {stats?.member_total ? `${stats.completion_rate}%` : "—"}
          </p>
        </Card>
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">异常结果</p>
          <p className="text-2xl font-semibold text-warning mt-1">{stats?.abnormal_total ?? 0}</p>
        </Card>
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">已转体征</p>
          <p className="text-2xl font-semibold text-fg-emphasis mt-1">{stats?.vital_sign_total ?? 0}</p>
        </Card>
        <Card bodyClassName="p-4">
          <p className="text-xs text-fg-dimmed">已转随访</p>
          <p className="text-2xl font-semibold text-fg-emphasis mt-1">{stats?.followup_total ?? 0}</p>
        </Card>
      </div>

      {conversionMessage && (
        <div className="px-4 py-3 rounded-md bg-success-bg text-success text-sm flex items-start justify-between gap-2">
          <span>{conversionMessage}</span>
          <Button size="sm" variant="functional" onClick={() => setConversionMessage("")}>
            关闭
          </Button>
        </div>
      )}
      {conversionError && (
        <div className="px-4 py-3 rounded-md bg-danger-bg text-danger text-sm">{conversionError}</div>
      )}

      {/* 页签 */}
      <div className="flex items-center gap-1 border-b border-border">
        {(
          [
            ["members", "参检名单"],
            ["results", "体检结果"],
            ["abnormal", "异常处理"],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setDetailTab(key)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
              detailTab === key
                ? "border-accent text-accent"
                : "border-transparent text-fg-muted hover:text-fg"
            }`}
          >
            {label}
            {key === "abnormal" && (stats?.abnormal_total ?? 0) > 0 && (
              <span className="ml-1 px-1.5 py-0.5 rounded-full bg-danger-bg text-danger text-xs">
                {stats?.abnormal_total}
              </span>
            )}
          </button>
        ))}
      </div>

      {detailTab === "members" && (
        <Card
          title="参检名单"
          actions={
            <div className="flex items-center gap-2">
              <select
                value={memberFilter}
                onChange={(event) => {
                  setMemberFilter(event.target.value);
                  setMemberPage(1);
                }}
                className="h-8 px-2 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none"
              >
                <option value="">全部</option>
                <option value="已检">已检</option>
                <option value="未检">未检</option>
              </select>
              <Button
                size="sm"
                variant="secondary"
                disabled={detail?.status === "已完成"}
                onClick={openAddMembers}
              >
                + 补录名单
              </Button>
              <Button size="sm" onClick={() => openEntry()}>
                + 录入结果
              </Button>
            </div>
          }
        >
          <Table
            columns={memberColumns}
            data={members}
            loading={loading}
            emptyMessage="暂无参检人员"
          />
          {memberTotal > PAGE_SIZE && (
            <div className="flex items-center justify-between mt-4">
              <span className="text-xs text-fg-dimmed">共 {memberTotal} 条</span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={memberPage <= 1}
                  onClick={() => loadMembers(memberPage - 1)}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={memberPage * PAGE_SIZE >= memberTotal}
                  onClick={() => loadMembers(memberPage + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {detailTab === "results" && (
        <Card
          title="体检结果"
          actions={
            <select
              value={resultFilter}
              onChange={(event) => {
                setResultFilter(event.target.value);
                setResultPage(1);
              }}
              className="h-8 px-2 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none"
            >
              <option value="">全部</option>
              <option value="异常">仅异常</option>
              <option value="正常">仅正常</option>
            </select>
          }
        >
          <Table
            columns={resultColumns}
            data={results}
            loading={loading}
            emptyMessage="暂无体检结果，请在名单中录入"
          />
          {resultTotal > PAGE_SIZE && (
            <div className="flex items-center justify-between mt-4">
              <span className="text-xs text-fg-dimmed">共 {resultTotal} 条</span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={resultPage <= 1}
                  onClick={() => loadResults(resultPage - 1)}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={resultPage * PAGE_SIZE >= resultTotal}
                  onClick={() => loadResults(resultPage + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {detailTab === "abnormal" && (
        <Card
          title="异常处理"
          actions={
            <Button size="sm" variant="secondary" onClick={() => setDetailTab("results")}>
              查看全部结果
            </Button>
          }
        >
          {results.filter((row) => row.abnormal).length === 0 && !loading ? (
            <EmptyState
              icon="✅"
              title="暂无异常结果"
              description="异常项可一键转为体征（进入复核闭环）或转为随访计划。"
            />
          ) : (
            <Table
              columns={resultColumns}
              data={results.filter((row) => row.abnormal)}
              loading={loading}
              emptyMessage="暂无异常结果"
            />
          )}
        </Card>
      )}

      {/* 名单补录 Modal */}
      <Modal open={addMembersOpen} onClose={() => setAddMembersOpen(false)} title="补录参检名单" width="36rem">
        <div className="space-y-4">
          <Input
            label="筛选患者"
            value={addMemberQuery}
            onChange={(event) => setAddMemberQuery(event.target.value)}
            placeholder="输入姓名检索"
          />
          <div className="max-h-72 overflow-y-auto border border-border rounded-md divide-y divide-border/50">
            {addMemberCandidates
              .filter((candidate) => !addMemberQuery.trim() || candidate.name.includes(addMemberQuery.trim()))
              .map((candidate) => (
                <label
                  key={candidate.id}
                  className="flex items-center gap-3 px-4 py-2.5 cursor-pointer hover:bg-surface-alt"
                >
                  <input
                    type="checkbox"
                    checked={addMemberSelected.includes(candidate.id)}
                    onChange={() => toggleCandidate(candidate.id)}
                    className="accent-[var(--color-accent)]"
                  />
                  <span className="text-sm text-fg">{candidate.name}</span>
                  <span className="text-xs text-fg-dimmed">{candidate.id}</span>
                </label>
              ))}
            {addMemberCandidates.length === 0 && (
              <p className="py-8 text-center text-sm text-fg-dimmed">暂无在册患者</p>
            )}
          </div>
          <p className="text-xs text-fg-dimmed">已选择 {addMemberSelected.length} 人；已在名单中的患者将自动跳过。</p>
          {addMemberError && <p className="text-sm text-danger">{addMemberError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setAddMembersOpen(false)}>
              取消
            </Button>
            <Button loading={addingMembers} onClick={handleAddMembers}>
              补录
            </Button>
          </div>
        </div>
      </Modal>

      {/* 结果录入 Modal */}
      <Modal
        open={entryOpen}
        onClose={() => setEntryOpen(false)}
        title={entryForm.patient_id ? "录入体检结果" : "录入体检结果"}
        width="40rem"
      >
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">参检人员</label>
              <select
                value={entryForm.patient_id}
                onChange={(event) => setEntryForm({ ...entryForm, patient_id: event.target.value })}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none"
              >
                <option value="">请选择</option>
                {patientOptions.map((member) => (
                  <option key={member.id} value={member.patient_id}>
                    {member.patient_name || member.patient_id}
                    {member.checked ? "（已检）" : "（未检）"}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">体检日期</label>
              <input
                type="date"
                value={entryForm.exam_date}
                onChange={(event) => setEntryForm({ ...entryForm, exam_date: event.target.value })}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none"
              />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted">项目类别</label>
            <div className="flex gap-2">
              {ITEM_CATEGORIES.map((category) => (
                <button
                  key={category}
                  onClick={() =>
                    setEntryForm({ ...entryForm, item_category: category, item_name: "" })
                  }
                  className={`px-3 h-8 rounded-md text-sm border transition-colors ${
                    entryForm.item_category === category
                      ? "border-accent text-accent"
                      : "border-border text-fg-muted hover:text-fg"
                  }`}
                >
                  {category}
                </button>
              ))}
            </div>
          </div>

          {entryForm.item_category === "数值" ? (
            <>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">项目</label>
                <div className="flex flex-wrap gap-1.5">
                  {NUMERIC_ITEM_PRESETS.map((preset) => (
                    <button
                      key={preset.item_name}
                      onClick={() => applyPreset(preset.item_name)}
                      className={`px-2.5 h-7 rounded-md text-xs border transition-colors ${
                        entryForm.item_name === preset.item_name
                          ? "border-accent text-accent"
                          : "border-border text-fg-muted hover:text-fg"
                      }`}
                    >
                      {preset.item_name}
                    </button>
                  ))}
                </div>
              </div>
              <Input
                label="项目名称"
                value={entryForm.item_name}
                onChange={(event) => setEntryForm({ ...entryForm, item_name: event.target.value })}
                placeholder="可自定义，如 总胆固醇"
              />
              <div className="grid grid-cols-4 gap-4">
                <Input
                  label="数值"
                  value={entryForm.value}
                  onChange={(event) => setEntryForm({ ...entryForm, value: event.target.value })}
                  placeholder="如 135"
                />
                <Input
                  label="单位"
                  value={entryForm.unit}
                  onChange={(event) => setEntryForm({ ...entryForm, unit: event.target.value })}
                  placeholder="如 mmHg"
                />
                <Input
                  label="参考下限"
                  value={entryForm.ref_min}
                  onChange={(event) => setEntryForm({ ...entryForm, ref_min: event.target.value })}
                  placeholder="可空"
                />
                <Input
                  label="参考上限"
                  value={entryForm.ref_max}
                  onChange={(event) => setEntryForm({ ...entryForm, ref_max: event.target.value })}
                  placeholder="可空"
                />
              </div>
              <p className="text-xs text-fg-dimmed">
                异常由服务端按参考范围自动判定（含边界值）；不填参考范围时按项目内置范围判定，体重不判异常。
              </p>
            </>
          ) : (
            <>
              <Input
                label="项目名称"
                value={entryForm.item_name}
                onChange={(event) => setEntryForm({ ...entryForm, item_name: event.target.value })}
                placeholder="如 心电图 / 胸透"
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">结论</label>
                <textarea
                  value={entryForm.text_value}
                  onChange={(event) => setEntryForm({ ...entryForm, text_value: event.target.value })}
                  placeholder="如 窦性心律，未见明显异常"
                  rows={3}
                  className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                />
              </div>
              <label className="flex items-center gap-2 text-sm text-fg cursor-pointer">
                <input
                  type="checkbox"
                  checked={entryForm.abnormal === "true"}
                  onChange={(event) => setEntryForm({ ...entryForm, abnormal: String(event.target.checked) })}
                  className="accent-[var(--color-accent)]"
                />
                标记为异常（文本项由录入人判定）
              </label>
            </>
          )}

          {entryError && <p className="text-sm text-danger">{entryError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEntryOpen(false)}>
              取消
            </Button>
            <Button loading={savingEntry} onClick={handleSaveEntry}>
              保存
            </Button>
          </div>
        </div>
      </Modal>

      {/* 结果修正 Modal */}
      <Modal open={patchOpen} onClose={() => setPatchOpen(false)} title="修正体检结果" width="40rem">
        <div className="space-y-4">
          <div className="text-sm text-fg-muted">
            {patchResult?.patient_name || patchResult?.patient_id} · {patchResult?.item_name}
            <span className="ml-2">{abnormalBadge(Boolean(patchResult?.abnormal))}</span>
          </div>
          {patchForm.item_category === "数值" ? (
            <div className="grid grid-cols-4 gap-4">
              <Input
                label="数值"
                value={patchForm.value}
                onChange={(event) => setPatchForm({ ...patchForm, value: event.target.value })}
              />
              <Input
                label="单位"
                value={patchForm.unit}
                onChange={(event) => setPatchForm({ ...patchForm, unit: event.target.value })}
              />
              <Input
                label="参考下限"
                value={patchForm.ref_min}
                onChange={(event) => setPatchForm({ ...patchForm, ref_min: event.target.value })}
              />
              <Input
                label="参考上限"
                value={patchForm.ref_max}
                onChange={(event) => setPatchForm({ ...patchForm, ref_max: event.target.value })}
              />
            </div>
          ) : (
            <>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">结论</label>
                <textarea
                  value={patchForm.text_value}
                  onChange={(event) => setPatchForm({ ...patchForm, text_value: event.target.value })}
                  rows={3}
                  className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                />
              </div>
              <label className="flex items-center gap-2 text-sm text-fg cursor-pointer">
                <input
                  type="checkbox"
                  checked={patchForm.abnormal === "true"}
                  onChange={(event) => setPatchForm({ ...patchForm, abnormal: String(event.target.checked) })}
                  className="accent-[var(--color-accent)]"
                />
                标记为异常
              </label>
            </>
          )}
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="体检日期"
              type="date"
              value={patchForm.exam_date}
              onChange={(event) => setPatchForm({ ...patchForm, exam_date: event.target.value })}
            />
          </div>
          <p className="text-xs text-fg-dimmed">
            数值项修正后异常标记将按新参考范围重算；已生成的体征/随访为快照，不随修正级联更新。
          </p>
          {patchError && <p className="text-sm text-danger">{patchError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setPatchOpen(false)}>
              取消
            </Button>
            <Button loading={savingPatch} onClick={handleSavePatch}>
              保存修正
            </Button>
          </div>
        </div>
      </Modal>

      {/* 转随访 Modal */}
      <Modal
        open={followupOpen}
        onClose={() => setFollowupOpen(false)}
        title={`转随访：${followupResult?.item_name ?? ""}`}
        width="36rem"
      >
        <div className="space-y-4">
          <div className="text-sm text-fg-muted">
            {followupResult?.patient_name || followupResult?.patient_id} · {followupResult?.item_name}
            <span className="ml-2">{abnormalBadge(true)}</span>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted">随访类型</label>
            <div className="flex gap-2">
              {FOLLOWUP_TYPES.map((type) => (
                <button
                  key={type}
                  onClick={() => setFollowupForm({ ...followupForm, followup_type: type })}
                  className={`px-3 h-8 rounded-md text-sm border transition-colors ${
                    followupForm.followup_type === type
                      ? "border-accent text-accent"
                      : "border-border text-fg-muted hover:text-fg"
                  }`}
                >
                  {type}
                </button>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="计划随访日"
              type="date"
              value={followupForm.planned_date}
              onChange={(event) => setFollowupForm({ ...followupForm, planned_date: event.target.value })}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">随访方式</label>
              <select
                value={followupForm.planned_way}
                onChange={(event) => setFollowupForm({ ...followupForm, planned_way: event.target.value })}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none"
              >
                {FOLLOWUP_WAYS.map((way) => (
                  <option key={way} value={way}>
                    {way}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted">备注</label>
            <textarea
              value={followupForm.remark}
              onChange={(event) => setFollowupForm({ ...followupForm, remark: event.target.value })}
              rows={2}
              placeholder="可空"
              className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            />
          </div>
          <p className="text-xs text-fg-dimmed">
            将生成随访计划并锚定该人员当前活动周期；无活动周期的异常项无法转随访，可转体征。
          </p>
          {followupError && <p className="text-sm text-danger">{followupError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setFollowupOpen(false)}>
              取消
            </Button>
            <Button loading={savingFollowup} onClick={handleToFollowup}>
              生成随访计划
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
