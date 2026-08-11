import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createChronicDisease,
  getChronicDisease,
  getChronicDiseaseTimeline,
  listChronicDiseases,
  listElderlyAdmissions,
  listPatients,
  updateChronicDiseaseStatus,
  type ChronicControlStatus,
  type ChronicDiseaseRegistration,
  type ChronicDiseaseTimeline,
  type ChronicFollowupFrequency,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

const CONTROL_STATUSES: ChronicControlStatus[] = ["良好", "一般", "较差", "未控制"];
const FREQUENCIES: ChronicFollowupFrequency[] = ["每月", "每两月", "每季度", "每半年", "每年"];
const ARCHIVE_STATUSES = ["管理中", "已缓解", "已停管"] as const;

/** 病种默认随访频率（服务端常量表，前端仅作提示；不填时按默认） */
const DEFAULT_FREQUENCY_HINTS: Record<string, ChronicFollowupFrequency> = {
  高血压: "每月",
  糖尿病: "每季度",
  冠心病: "每季度",
  脑卒中: "每季度",
  慢阻肺: "每季度",
  高脂血症: "每半年",
  骨质疏松: "每半年",
};

interface AdmissionOption {
  id: string;
  patient_id: string;
  patient_name: string;
  encounter_no: string | null;
  admit_date: string | null;
}

interface CreateForm {
  patient_id: string;
  encounter_id: string;
  disease_name: string;
  icd_code: string;
  confirmed_date: string;
  control_status: ChronicControlStatus;
  followup_frequency: string;
  physician: string;
  remark: string;
}

const createFormDefaults: CreateForm = {
  patient_id: "",
  encounter_id: "",
  disease_name: "",
  icd_code: "",
  confirmed_date: todayLocal(),
  control_status: "良好",
  followup_frequency: "",
  physician: "",
  remark: "",
};

function todayLocal(): string {
  return new Date().toISOString().slice(0, 10);
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  return value.slice(0, 10);
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function statusBadge(status: string): React.ReactNode {
  if (status === "管理中") return <Badge variant="success">管理中</Badge>;
  if (status === "已缓解") return <Badge variant="info">已缓解</Badge>;
  if (status === "已停管") return <Badge variant="default">已停管</Badge>;
  return <Badge>{status}</Badge>;
}

function controlBadge(status: string): React.ReactNode {
  if (status === "良好") return <Badge variant="success">{status}</Badge>;
  if (status === "一般") return <Badge variant="info">{status}</Badge>;
  if (status === "较差") return <Badge variant="warning">{status}</Badge>;
  if (status === "未控制") return <Badge variant="danger">{status}</Badge>;
  return <Badge>{status}</Badge>;
}

export default function ChronicDiseasePage() {
  const [records, setRecords] = useState<ChronicDiseaseRegistration[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  const [filterPatient, setFilterPatient] = useState("");
  const [filterDisease, setFilterDisease] = useState("");
  const [filterControl, setFilterControl] = useState("");
  const [filterStatus, setFilterStatus] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateForm>(createFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);
  const [admissionOptions, setAdmissionOptions] = useState<AdmissionOption[]>([]);
  const [patientOptions, setPatientOptions] = useState<{ id: string; name: string }[]>([]);

  const [detailId, setDetailId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChronicDiseaseRegistration | null>(null);
  const [timeline, setTimeline] = useState<ChronicDiseaseTimeline | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [statusBusy, setStatusBusy] = useState(false);

  const initialPatientFilter = useMemo(
    () => (typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("patient")),
    [],
  );

  /** 从医生工作台「登记为慢病」跳转带入：老人/病种/ICD */
  const quickCreate = useMemo(() => {
    if (typeof window === "undefined") return null;
    const params = new URLSearchParams(window.location.search);
    const patient = params.get("patient");
    const disease = params.get("disease");
    const icd = params.get("icd");
    if (!patient && !disease && !icd) return null;
    return { patient, disease, icd };
  }, []);

  const loadList = useCallback(
    async (targetPage: number) => {
      setLoading(true);
      setPageError("");
      try {
        const response = await listChronicDiseases({
          patient_id: filterPatient.trim() || undefined,
          disease_name: filterDisease.trim() || undefined,
          control_status: (filterControl as ChronicControlStatus) || undefined,
          status: (filterStatus as ChronicDiseaseRegistration["status"]) || undefined,
          limit: PAGE_SIZE,
          offset: (targetPage - 1) * PAGE_SIZE,
        });
        setRecords(response.records);
        setTotal(response.meta.total);
        setPage(targetPage);
      } catch (error) {
        setPageError(errorMessage(error, "无法加载慢病档案"));
      } finally {
        setLoading(false);
      }
    },
    [filterPatient, filterDisease, filterControl, filterStatus],
  );

  useEffect(() => {
    if (initialPatientFilter) setFilterPatient(initialPatientFilter);
    void loadList(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 筛选条件变化后回到第一页
  useEffect(() => {
    void loadList(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterControl, filterStatus]);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  // ——— 登记 ———

  const openCreate = useCallback(async () => {
    setCreateForm({
      ...createFormDefaults,
      ...(quickCreate?.patient ? { patient_id: quickCreate.patient } : {}),
      ...(quickCreate?.disease ? { disease_name: quickCreate.disease } : {}),
      ...(quickCreate?.icd ? { icd_code: quickCreate.icd } : {}),
      ...(quickCreate?.disease && DEFAULT_FREQUENCY_HINTS[quickCreate.disease.trim()]
        ? { followup_frequency: DEFAULT_FREQUENCY_HINTS[quickCreate.disease.trim()] }
        : {}),
    });
    setCreateError("");
    setCreateOpen(true);
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ status: "ACTIVE", limit: 500 }),
        listElderlyAdmissions({ status: "ACTIVE", limit: 500 }),
      ]);
      setPatientOptions(patientResponse.records.map((p) => ({ id: p.id, name: p.name })));
      const patientById = new Map(patientResponse.records.map((p) => [p.id, p.name]));
      setAdmissionOptions(
        encounterResponse.records.map((encounter) => ({
          id: encounter.id,
          patient_id: encounter.patient_id,
          patient_name: patientById.get(encounter.patient_id) ?? encounter.patient_id,
          encounter_no: encounter.encounter_no,
          admit_date: encounter.admit_date,
        })),
      );
    } catch (error) {
      setCreateError(errorMessage(error, "无法加载老人与活动入住信息"));
    }
  }, [quickCreate]);

  const patientAdmissions = useMemo(() => {
    if (!createForm.patient_id) return [];
    return admissionOptions.filter((option) => option.patient_id === createForm.patient_id);
  }, [createForm.patient_id, admissionOptions]);

  const selectedAdmission = useMemo(
    () => admissionOptions.find((option) => option.id === createForm.encounter_id) ?? null,
    [admissionOptions, createForm.encounter_id],
  );

  // 选择老人后自动选中其唯一活动入住；病种变化时提示默认频率
  function handlePatientChange(patientId: string) {
    const options = admissionOptions.filter((option) => option.patient_id === patientId);
    setCreateForm((form) => ({
      ...form,
      patient_id: patientId,
      encounter_id: options.length === 1 ? options[0].id : "",
    }));
  }

  // 老人/入住选项加载完成后：URL 带入的老人自动选中其唯一活动入住
  useEffect(() => {
    if (!createOpen || !createForm.patient_id || createForm.encounter_id) return;
    const options = admissionOptions.filter((option) => option.patient_id === createForm.patient_id);
    if (options.length === 1) {
      setCreateForm((form) => ({ ...form, encounter_id: options[0].id }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admissionOptions, createOpen]);

  function handleDiseaseChange(diseaseName: string) {
    const hint = DEFAULT_FREQUENCY_HINTS[diseaseName.trim()];
    setCreateForm((form) => ({
      ...form,
      disease_name: diseaseName,
      followup_frequency: hint ?? form.followup_frequency,
    }));
  }

  async function handleCreate() {
    if (!createForm.patient_id || !createForm.encounter_id) {
      setCreateError("请选择老人及其活动入住");
      return;
    }
    if (!createForm.disease_name.trim()) {
      setCreateError("病种名称必填");
      return;
    }
    if (!createForm.confirmed_date) {
      setCreateError("确诊日期必填");
      return;
    }
    setCreating(true);
    setCreateError("");
    try {
      await createChronicDisease({
        patient_id: createForm.patient_id,
        encounter_id: createForm.encounter_id,
        disease_name: createForm.disease_name.trim(),
        icd_code: createForm.icd_code.trim() || undefined,
        confirmed_date: createForm.confirmed_date,
        control_status: createForm.control_status,
        followup_frequency: (createForm.followup_frequency as ChronicFollowupFrequency) || undefined,
        physician: createForm.physician.trim() || undefined,
        remark: createForm.remark.trim() || undefined,
      });
      setCreateOpen(false);
      void loadList(1);
    } catch (error) {
      // 失败保留表单内容，仅提示错误
      setCreateError(errorMessage(error, "登记失败"));
    } finally {
      setCreating(false);
    }
  }

  // ——— 详情时间线 ———

  const openDetail = useCallback(async (id: string) => {
    setDetailId(id);
    setDetailOpen(true);
    setDetailError("");
    try {
      const [registration, timelineData] = await Promise.all([
        getChronicDisease(id),
        getChronicDiseaseTimeline(id),
      ]);
      setTimeline(timelineData);
      setDetail(registration);
    } catch (error) {
      setDetailError(errorMessage(error, "无法加载档案时间线"));
    }
  }, []);

  async function handleStatusChange(status: "已缓解" | "已停管" | "管理中") {
    if (!detailId) return;
    setStatusBusy(true);
    setDetailError("");
    try {
      await updateChronicDiseaseStatus(detailId, { status });
      // 重新加载时间线（档案状态变化）与列表
      setTimeline(await getChronicDiseaseTimeline(detailId));
      await loadList(page);
    } catch (error) {
      setDetailError(errorMessage(error, "状态变更失败"));
    } finally {
      setStatusBusy(false);
    }
  }

  // ——— 表格 ———

  const columns: Column<ChronicDiseaseRegistration>[] = [
    {
      key: "disease_name",
      header: "病种",
      render: (row) => (
        <div>
          <div className="font-medium text-fg">{row.disease_name}</div>
          {row.icd_code && <div className="text-xs text-fg-dimmed">{row.icd_code}</div>}
        </div>
      ),
    },
    {
      key: "patient_name",
      header: "老人",
      render: (row) => (
        <div>
          <div className="text-fg">{row.patient_name ?? row.patient_id}</div>
          {row.encounter_no && <div className="text-xs text-fg-dimmed">{row.encounter_no}</div>}
        </div>
      ),
    },
    { key: "confirmed_date", header: "确诊日期", render: (row) => formatDate(row.confirmed_date) },
    { key: "control_status", header: "控制状态", render: (row) => controlBadge(row.control_status) },
    { key: "followup_frequency", header: "随访频率", render: (row) => row.followup_frequency },
    {
      key: "next_followup_date",
      header: "下次随访",
      render: (row) => (
        <div>
          <div className={row.is_overdue ? "text-danger font-medium" : "text-fg"}>
            {formatDate(row.next_followup_date)}
            {row.is_overdue && <span className="ml-1 text-xs">逾期</span>}
          </div>
          {row.recent_followup_result && (
            <div className="text-xs text-fg-dimmed">最近: {row.recent_followup_result}</div>
          )}
        </div>
      ),
    },
    { key: "physician", header: "责任医生", render: (row) => row.physician ?? "—" },
    { key: "status", header: "状态", render: (row) => statusBadge(row.status) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openDetail(row.id)}>
          详情
        </Button>
      ),
    },
  ];

  const admissionSelectDisabled = !createForm.patient_id;

  return (
    <div className="flex flex-col gap-4 p-4">
      <Card
        title="慢病档案"
        actions={
          <Button variant="primary" size="sm" onClick={() => void openCreate()}>
            ＋ 登记慢病
          </Button>
        }
      >
        <div className="flex flex-wrap items-center gap-3 pb-4">
          <div className="w-48">
            <Input
              label="老人筛选"
              placeholder="按老人姓名/ID"
              value={filterPatient}
              onChange={(event) => setFilterPatient(event.target.value)}
            />
          </div>
          <div className="w-44">
            <Input
              label="病种"
              placeholder="如 高血压"
              value={filterDisease}
              onChange={(event) => setFilterDisease(event.target.value)}
            />
          </div>
          <div className="w-36">
            <label className="text-sm font-medium text-fg-muted">控制状态</label>
            <select
              className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              value={filterControl}
              onChange={(event) => setFilterControl(event.target.value)}
            >
              <option value="">全部</option>
              {CONTROL_STATUSES.map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </div>
          <div className="w-36">
            <label className="text-sm font-medium text-fg-muted">档案状态</label>
            <select
              className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              value={filterStatus}
              onChange={(event) => setFilterStatus(event.target.value)}
            >
              <option value="">全部</option>
              {ARCHIVE_STATUSES.map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </div>
          <div className="flex items-end gap-2">
            <Button variant="secondary" size="md" onClick={() => void loadList(1)}>
              查询
            </Button>
            <Button
              variant="ghost"
              size="md"
              onClick={() => {
                setFilterPatient("");
                setFilterDisease("");
                setFilterControl("");
                setFilterStatus("");
              }}
            >
              重置
            </Button>
          </div>
        </div>

        {pageError && <div className="mb-3 text-sm text-danger">{pageError}</div>}

        {loading ? (
          <div className="flex justify-center py-16"><LoadingSpinner /></div>
        ) : records.length === 0 ? (
          <EmptyState
            icon="🩺"
            title="暂无慢病档案"
            description="登记老人慢病后，将自动生成「慢病随访」计划"
            action={<Button variant="primary" size="sm" onClick={() => void openCreate()}>登记慢病</Button>}
          />
        ) : (
          <>
            <Table columns={columns} data={records} keyField="id" />
            <div className="flex items-center justify-between pt-4">
              <span className="text-sm text-fg-dimmed">共 {total} 条</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page <= 1}
                  onClick={() => void loadList(page - 1)}
                >
                  上一页
                </Button>
                <span className="text-sm text-fg-muted">{page} / {totalPages}</span>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page >= totalPages}
                  onClick={() => void loadList(page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </Card>

      {/* ——— 登记弹窗 ——— */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="登记慢病档案">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="text-sm font-medium text-fg-muted">老人（活动入住）</label>
              <select
                className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                value={createForm.patient_id}
                onChange={(event) => handlePatientChange(event.target.value)}
              >
                <option value="">请选择老人</option>
                {patientOptions.map((patient) => (
                  <option key={patient.id} value={patient.id}>{patient.name}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-sm font-medium text-fg-muted">活动入住</label>
              <select
                className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:opacity-50"
                value={createForm.encounter_id}
                disabled={admissionSelectDisabled}
                onChange={(event) => setCreateForm((form) => ({ ...form, encounter_id: event.target.value }))}
              >
                <option value="">{admissionSelectDisabled ? "请先选择老人" : "请选择入住"}</option>
                {patientAdmissions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.encounter_no ?? option.id}（{formatDate(option.admit_date)}入住）
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Input
              label="病种名称"
              placeholder="如 高血压（必填）"
              value={createForm.disease_name}
              onChange={(event) => handleDiseaseChange(event.target.value)}
            />
            <Input
              label="ICD 编码"
              placeholder="如 I10（可选）"
              value={createForm.icd_code}
              onChange={(event) => setCreateForm((form) => ({ ...form, icd_code: event.target.value }))}
            />
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Input
              label="确诊日期"
              type="date"
              value={createForm.confirmed_date}
              onChange={(event) => setCreateForm((form) => ({ ...form, confirmed_date: event.target.value }))}
            />
            <div>
              <label className="text-sm font-medium text-fg-muted">控制状态</label>
              <select
                className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                value={createForm.control_status}
                onChange={(event) => setCreateForm((form) => ({ ...form, control_status: event.target.value as ChronicControlStatus }))}
              >
                {CONTROL_STATUSES.map((status) => (
                  <option key={status} value={status}>{status}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-sm font-medium text-fg-muted">随访频率</label>
              <select
                className="h-10 w-full px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                value={createForm.followup_frequency}
                onChange={(event) => setCreateForm((form) => ({ ...form, followup_frequency: event.target.value }))}
              >
                <option value="">按病种默认</option>
                {FREQUENCIES.map((frequency) => (
                  <option key={frequency} value={frequency}>{frequency}</option>
                ))}
              </select>
              {createForm.disease_name.trim() && DEFAULT_FREQUENCY_HINTS[createForm.disease_name.trim()] && (
                <p className="text-xs text-fg-dimmed mt-1">
                  默认：{DEFAULT_FREQUENCY_HINTS[createForm.disease_name.trim()]}
                </p>
              )}
            </div>
          </div>
          <Input
            label="责任医生"
            placeholder="可选"
            value={createForm.physician}
            onChange={(event) => setCreateForm((form) => ({ ...form, physician: event.target.value }))}
          />
          <Input
            label="备注"
            placeholder="可选"
            value={createForm.remark}
            onChange={(event) => setCreateForm((form) => ({ ...form, remark: event.target.value }))}
          />
          {selectedAdmission && (
            <p className="text-xs text-fg-dimmed">
              登记后将自动生成首轮「慢病随访」计划（{createForm.followup_frequency || "按病种默认频率"}），计划日不早于入住开始日。
            </p>
          )}
          {createError && <div className="text-sm text-danger">{createError}</div>}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" size="md" onClick={() => setCreateOpen(false)}>取消</Button>
            <Button variant="primary" size="md" loading={creating} onClick={() => void handleCreate()}>
              登记
            </Button>
          </div>
        </div>
      </Modal>

      {/* ——— 详情弹窗：档案信息 + 时间线 ——— */}
      <Modal
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        title={detail ? `${detail.disease_name} · ${detail.patient_name ?? ""}` : "慢病档案详情"}
        width="52rem"
      >
        {detailError && <div className="mb-3 text-sm text-danger">{detailError}</div>}
        {timeline ? (
          <div className="flex flex-col gap-4">
            {/* 档案摘要 */}
            <div className="rounded-md border border-border bg-surface-alt/50 p-3 text-sm">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <span className="font-medium text-fg">{timeline.chronic_disease_id ? detail?.disease_name ?? "慢病档案" : ""}</span>
                {detail && (
                  <>
                    <span className="text-fg-muted">确诊 {formatDate(detail.confirmed_date)}</span>
                    {detail.icd_code && <span className="text-fg-muted">ICD {detail.icd_code}</span>}
                    <span>{controlBadge(detail.control_status)}</span>
                    <span className="text-fg-muted">频率 {detail.followup_frequency}</span>
                    {detail.physician && <span className="text-fg-muted">医生 {detail.physician}</span>}
                    <span>{statusBadge(detail.status)}</span>
                  </>
                )}
              </div>
              {detail && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {detail.status === "管理中" ? (
                    <>
                      <Button variant="warning" size="sm" disabled={statusBusy} onClick={() => void handleStatusChange("已缓解")}>
                        标记已缓解
                      </Button>
                      <Button variant="danger" size="sm" disabled={statusBusy} onClick={() => void handleStatusChange("已停管")}>
                        停管
                      </Button>
                    </>
                  ) : (
                    <Button variant="primary" size="sm" disabled={statusBusy} onClick={() => void handleStatusChange("管理中")}>
                      恢复管理
                    </Button>
                  )}
                  {detail.next_followup_date && (
                    <span className="text-xs text-fg-dimmed self-center">
                      下次随访 {formatDate(detail.next_followup_date)}
                      {detail.is_overdue && <span className="text-danger">（逾期）</span>}
                    </span>
                  )}
                </div>
              )}
            </div>

            {/* 病程记录 */}
            <div>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">病程记录</h4>
              {timeline.progress_notes.length === 0 ? (
                <p className="text-sm text-fg-dimmed">暂无病程记录（可在医生诊疗页按入住记录慢病病程）</p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {timeline.progress_notes.map((note) => (
                    <li key={note.id} className="rounded-md border border-border p-3 text-sm">
                      <div className="flex items-center justify-between text-xs text-fg-dimmed">
                        <span>{formatDate(note.record_time ?? note.created_at)} {note.physician ? `· ${note.physician}` : ""}</span>
                        <span>病程</span>
                      </div>
                      <p className="mt-1 whitespace-pre-wrap text-fg">{note.content}</p>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 随访计划 */}
            <div>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">随访计划</h4>
              {timeline.followup_plans.length === 0 ? (
                <p className="text-sm text-fg-dimmed">暂无随访计划</p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {timeline.followup_plans.map((plan) => (
                    <li key={plan.id} className="flex items-center justify-between rounded-md border border-border p-3 text-sm">
                      <div>
                        <div className="text-fg">{formatDate(plan.planned_date)} · {plan.planned_way}</div>
                        <div className="text-xs text-fg-dimmed">
                          {plan.status === "已逾期" ? "已逾期" : ""}
                          {plan.metadata?.source ? ` · ${String(plan.metadata.source)}` : ""}
                        </div>
                      </div>
                      <div>
                        {plan.status === "已完成" && <Badge variant="success">已完成</Badge>}
                        {plan.status === "已取消" && <Badge variant="default">已取消</Badge>}
                        {plan.status === "待随访" && <Badge variant="info">待随访</Badge>}
                        {plan.status === "已逾期" && <Badge variant="danger">已逾期</Badge>}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 随访记录 */}
            <div>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">随访记录</h4>
              {timeline.followup_records.length === 0 ? (
                <p className="text-sm text-fg-dimmed">暂无随访记录</p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {timeline.followup_records.map((record) => (
                    <li key={record.id} className="rounded-md border border-border p-3 text-sm">
                      <div className="flex items-center justify-between text-xs text-fg-dimmed">
                        <span>{formatDate(record.followup_date)} · {record.followup_way} · {record.operator}</span>
                        <Badge variant={record.result === "正常" ? "success" : record.result === "异常" ? "danger" : "warning"}>
                          {record.result}
                        </Badge>
                      </div>
                      {record.condition_summary && <p className="mt-1 text-fg">{record.condition_summary}</p>}
                      {record.next_followup_date && (
                        <p className="mt-1 text-xs text-fg-dimmed">下次随访 {formatDate(record.next_followup_date)}</p>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        ) : (
          <div className="flex justify-center py-12"><LoadingSpinner /></div>
        )}
      </Modal>
    </div>
  );
}
