import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createPatient,
  listActiveElderlyAdmissions,
  listPatients,
  updatePatient,
  type ChildHealthProfileInput,
  type Encounter,
  type Patient,
  type PatientInput,
  type VaccinationRecord,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { DOMAIN_ENTITY, currentEntityLabels, type PersonType } from "../lib/domain";
import { formatDate } from "../lib/datetime";
import { useDomain } from "../lib/useDomain";

const PAGE_SIZE = 20;

/** 档案状态筛选：ACTIVE=有效、DECEASED=已去世、""=全部（默认保持既有「只看有效」口径） */
type ArchiveStatusFilter = "ACTIVE" | "DECEASED" | "";

const ARCHIVE_STATUS_FILTERS: { value: ArchiveStatusFilter; label: string }[] = [
  { value: "ACTIVE", label: "有效" },
  { value: "DECEASED", label: "已去世" },
  { value: "", label: "全部" },
];

const PATIENT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "有效",
  INACTIVE: "已停用",
  DECEASED: "已去世",
};

function patientStatusBadgeVariant(status: string): "success" | "default" | "danger" {
  if (status === "ACTIVE") return "success";
  if (status === "DECEASED") return "danger";
  return "default";
}

/** 儿保表单原生控件样式（与 `@pitchfork/ui` 的 Input / select 保持一致） */
const FORM_CONTROL_CLASS =
  "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

/** 儿保接种记录行（表单态；空行在保存时被过滤） */
interface VaccinationRow {
  vaccine: string;
  dose: string;
  date: string;
  facility: string;
}

interface ElderForm {
  name: string;
  gender: string;
  birthDate: string;
  idCardNo: string;
  phone: string;
  address: string;
  emergencyName: string;
  emergencyRelationship: string;
  emergencyPhone: string;
  medicalInsurance: string;
  allergies: string;
  pastHistory: string;
  /** 儿保专属字段；医疗/养老域不渲染，恒为空 */
  guardianName: string;
  guardianRelationship: string;
  guardianPhone: string;
  birthWeightG: string;
  birthHeightMm: string;
  deliveryMode: string;
  feedingMethod: string;
  vaccinationSummary: string;
  vaccinationRecords: VaccinationRow[];
  remark: string;
}

const elderFormDefaults: ElderForm = {
  name: "",
  gender: "",
  birthDate: "",
  idCardNo: "",
  phone: "",
  address: "",
  emergencyName: "",
  emergencyRelationship: "",
  emergencyPhone: "",
  medicalInsurance: "",
  allergies: "",
  pastHistory: "",
  guardianName: "",
  guardianRelationship: "",
  guardianPhone: "",
  birthWeightG: "",
  birthHeightMm: "",
  deliveryMode: "",
  feedingMethod: "",
  vaccinationSummary: "",
  vaccinationRecords: [],
  remark: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function displayValue(value: string | null | undefined): string {
  return value?.trim() || "-";
}

/** 数字输入串 → 整数或 null（空串 / 非法值转 null，便于后端清空字段） */
function nullableInt(value: string): number | null {
  const raw = value.trim();
  if (!raw) return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
}

/** 儿保档案输入：空串转 null，接种记录过滤空行 */
function buildChildProfile(form: ElderForm): ChildHealthProfileInput {
  const vaccinationRecords: VaccinationRecord[] = form.vaccinationRecords
    .map((row) => ({
      ...(row.vaccine.trim() ? { vaccine: row.vaccine.trim() } : {}),
      ...(row.dose.trim() ? { dose: row.dose.trim() } : {}),
      ...(row.date ? { date: row.date } : {}),
      ...(row.facility.trim() ? { facility: row.facility.trim() } : {}),
    }))
    .filter((row) => Object.keys(row).length > 0);
  return {
    guardian_name: form.guardianName.trim() || null,
    guardian_relationship: form.guardianRelationship.trim() || null,
    guardian_phone: form.guardianPhone.trim() || null,
    birth_weight_g: nullableInt(form.birthWeightG),
    birth_height_mm: nullableInt(form.birthHeightMm),
    delivery_mode: form.deliveryMode || null,
    feeding_method: form.feedingMethod || null,
    vaccination_summary: form.vaccinationSummary.trim() || null,
    vaccination_records: vaccinationRecords,
    remark: form.remark.trim() || null,
  };
}

function buildPatientInput(form: ElderForm, editing: boolean, personType: PersonType): PatientInput | null {
  const name = form.name.trim();
  if (!name) return null;

  const emergencyContact = {
    ...(form.emergencyName.trim() ? { name: form.emergencyName.trim() } : {}),
    ...(form.emergencyRelationship.trim() ? { relationship: form.emergencyRelationship.trim() } : {}),
    ...(form.emergencyPhone.trim() ? { phone: form.emergencyPhone.trim() } : {}),
  };
  const allergies = form.allergies
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean);

  const input: PatientInput = {
    name,
    person_type: personType,
    ...(editing ? { gender: form.gender || null } : form.gender ? { gender: form.gender } : {}),
    ...(form.birthDate ? { birth_date: form.birthDate } : {}),
    ...(editing ? { id_card_no: form.idCardNo.trim() || null } : form.idCardNo.trim() ? { id_card_no: form.idCardNo.trim() } : {}),
    ...(editing ? { phone: form.phone.trim() || null } : form.phone.trim() ? { phone: form.phone.trim() } : {}),
    ...(editing ? { address: form.address.trim() || null } : form.address.trim() ? { address: form.address.trim() } : {}),
    ...(editing ? { emergency_contact: emergencyContact } : Object.keys(emergencyContact).length > 0 ? { emergency_contact: emergencyContact } : {}),
    ...(editing ? { medical_insurance: form.medicalInsurance.trim() || null } : form.medicalInsurance.trim() ? { medical_insurance: form.medicalInsurance.trim() } : {}),
    ...(editing ? { allergies } : allergies.length > 0 ? { allergies } : {}),
    ...(editing ? { past_history: form.pastHistory.trim() || null } : form.pastHistory.trim() ? { past_history: form.pastHistory.trim() } : {}),
  };

  // 仅儿保域携带 child_profile；医疗/养老域不带，避免服务端「child_profile 配非儿童」400
  return personType === "儿童" ? { ...input, child_profile: buildChildProfile(form) } : input;
}

function formFromPatient(patient: Patient, personType: PersonType): ElderForm {
  const emergencyContact = patient.emergency_contact ?? {};
  const profile = personType === "儿童" ? patient.child_profile : null;
  return {
    name: patient.name,
    gender: patient.gender ?? "",
    birthDate: patient.birth_date ?? "",
    idCardNo: patient.id_card_no ?? "",
    phone: patient.phone ?? "",
    address: patient.address ?? "",
    emergencyName: emergencyContact.name ?? "",
    emergencyRelationship: emergencyContact.relationship ?? "",
    emergencyPhone: emergencyContact.phone ?? "",
    medicalInsurance: patient.medical_insurance ?? "",
    allergies: patient.allergies?.join("，") ?? "",
    pastHistory: patient.past_history ?? "",
    guardianName: profile?.guardian_name ?? "",
    guardianRelationship: profile?.guardian_relationship ?? "",
    guardianPhone: profile?.guardian_phone ?? "",
    birthWeightG: profile?.birth_weight_g != null ? String(profile.birth_weight_g) : "",
    birthHeightMm: profile?.birth_height_mm != null ? String(profile.birth_height_mm) : "",
    deliveryMode: profile?.delivery_mode ?? "",
    feedingMethod: profile?.feeding_method ?? "",
    vaccinationSummary: profile?.vaccination_summary ?? "",
    vaccinationRecords: (profile?.vaccination_records ?? []).map((record) => ({
      vaccine: record.vaccine ?? "",
      dose: record.dose ?? "",
      date: record.date ?? "",
      facility: record.facility ?? "",
    })),
    remark: profile?.remark ?? "",
  };
}

export default function EldersPage() {
  // 页面命名随产品域切换（医疗：居民档案 / 养老：长者档案 / 儿保：儿童健康档案），
  // 与菜单、浏览器标题同源；此前内容写死「长者」，医疗模式下会与菜单打架。
  const domain = useDomain();
  const { person, archive, personType } = DOMAIN_ENTITY[domain];
  // 儿保域走儿童专属列表列与表单；医疗/养老保持现状
  const isChild = domain === "儿保";
  const [elders, setElders] = useState<Patient[]>([]);
  const [activeEncounters, setActiveEncounters] = useState<Record<string, Encounter>>({});
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<ArchiveStatusFilter>("ACTIVE");
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Patient | null>(null);
  const [form, setForm] = useState<ElderForm>(elderFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const [response, activeResponse] = await Promise.all([
        listPatients({
          ...(statusFilter ? { status: statusFilter } : {}),
          // 儿保域只看儿童；医疗/养老保持不过滤（既有数据默认「居民」，
          // 若养老域也过滤会把真实长者全部隐藏）
          ...(personType === "儿童" ? { person_type: personType } : {}),
          limit: PAGE_SIZE,
          offset: (targetPage - 1) * PAGE_SIZE,
        }),
        listActiveElderlyAdmissions({ limit: 100 }),
      ]);
      setElders(response.records);
      setActiveEncounters(Object.fromEntries(activeResponse.records.map((encounter) => [encounter.patient_id, encounter])));
      setTotal(response.meta.total);
      setPage(targetPage);
    } catch (error) {
      // 兜底文案按「出错那一刻」的域取词；`personType` 是刻意加入的依赖：
      // 切域必须重拉列表（儿保只看儿童），而词表本身不进入依赖，避免多余重建。
      setPageError(errorMessage(error, `无法加载${currentEntityLabels().archive}`));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, personType]);

  useEffect(() => {
    void load(1);
  }, [load]);

  function openCreate() {
    setEditTarget(null);
    setForm(elderFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function openEdit(patient: Patient) {
    setEditTarget(patient);
    setForm(formFromPatient(patient, personType));
    setFormError("");
    setEditorOpen(true);
  }

  function updateVaccinationRow(index: number, patch: Partial<VaccinationRow>) {
    setForm((current) => ({
      ...current,
      vaccinationRecords: current.vaccinationRecords.map((row, i) => (i === index ? { ...row, ...patch } : row)),
    }));
  }

  function addVaccinationRow() {
    setForm((current) => ({
      ...current,
      vaccinationRecords: [...current.vaccinationRecords, { vaccine: "", dose: "", date: "", facility: "" }],
    }));
  }

  function removeVaccinationRow(index: number) {
    setForm((current) => ({
      ...current,
      vaccinationRecords: current.vaccinationRecords.filter((_, i) => i !== index),
    }));
  }

  async function handleSave() {
    const input = buildPatientInput(form, editTarget !== null, personType);
    if (!input) {
      setFormError("姓名不能为空");
      return;
    }
    // 儿保域：出生日期必填（与服务端校验同口径，前端先提示）
    if (isChild && !form.birthDate) {
      setFormError("儿童档案必须填写出生日期");
      return;
    }

    setSaving(true);
    setFormError("");
    try {
      if (editTarget) {
        await updatePatient(editTarget.id, input);
      } else {
        await createPatient(input);
      }
      setEditorOpen(false);
      await load(editTarget ? page : 1);
    } catch (error) {
      setFormError(errorMessage(error, editTarget ? `无法更新${archive}` : `无法保存${archive}`));
    } finally {
      setSaving(false);
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns: Column<Patient>[] = [
    { key: "name", header: "姓名", className: "min-w-[140px]" },
    { key: "gender", header: "性别", className: "w-[90px]", render: (row) => displayValue(row.gender) },
    { key: "birth_date", header: "出生日期", className: "min-w-[130px]", render: (row) => formatDate(row.birth_date) },
  ];
  if (isChild) {
    // 儿保域：月龄 + 监护人，隐藏「身份证号 / 住院号」
    columns.push(
      { key: "age", header: "月龄", className: "w-[110px]", render: (row) => formatAge(row.birth_date) },
      { key: "guardian", header: "监护人", className: "min-w-[140px]", render: (row) => displayValue(row.child_profile?.guardian_name) },
    );
  } else {
    columns.push(
      { key: "id_card_no", header: "身份证号", className: "min-w-[190px]", render: (row) => displayValue(row.id_card_no) },
      { key: "encounter_no", header: "住院号", className: "min-w-[140px]", render: (row) => displayValue(activeEncounters[row.id]?.encounter_no) },
    );
  }
  columns.push(
    { key: "phone", header: "联系电话", className: "min-w-[140px]", render: (row) => displayValue(row.phone) },
    {
      key: "status",
      header: "状态",
      className: "w-[100px]",
      render: (row) => (
        <Badge variant={patientStatusBadgeVariant(row.status)}>
          {PATIENT_STATUS_LABELS[row.status] ?? row.status}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[90px]",
      render: (row) => <Button variant="link" size="sm" onClick={() => openEdit(row)}>编辑</Button>,
    },
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">{archive}</h2>
          <p className="mt-1 text-sm text-fg-muted">建立{person}基础档案，后续入住与照护记录将关联到此档案</p>
        </div>
        <Button variant="primary" onClick={openCreate}>录入{person}</Button>
      </div>

      {/* 状态筛选：接口层 status=DECEASED 一直保留，此处补齐「已去世」入口，避免档案侧看不到去世居民 */}
      <div className="flex w-fit gap-1 rounded-lg border border-border bg-surface p-1">
        {ARCHIVE_STATUS_FILTERS.map((filter) => (
          <button
            key={filter.value || "ALL"}
            type="button"
            onClick={() => setStatusFilter(filter.value)}
            className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${
              statusFilter === filter.value ? "bg-accent text-white" : "text-fg-muted hover:text-fg"
            }`}
          >
            {filter.label}
          </button>
        ))}
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title={`${archive}列表`} actions={<span className="text-sm text-fg-dimmed">共 {total} 条</span>}>
        <Table
          columns={columns}
          data={elders}
          loading={loading}
          emptyMessage={statusFilter === "DECEASED" ? `暂无已去世${person}` : `暂无${archive}，点击右上角开始录入`}
        />
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
          <span className="text-sm text-fg-muted">第 {page} / {pageCount} 页</span>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={page <= 1 || loading} onClick={() => void load(page - 1)}>上一页</Button>
            <Button variant="secondary" size="sm" disabled={page >= pageCount || loading} onClick={() => void load(page + 1)}>下一页</Button>
          </div>
        </div>
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? `编辑${archive}` : `录入${archive}`}>
        <form
          className="space-y-5"
          onSubmit={(event) => {
            event.preventDefault();
            void handleSave();
          }}
        >
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}

          {isChild ? (
            <>
              {/* 儿保域：儿童专属分组 */}
              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">基本信息</h4>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <Input
                    label="姓名"
                    value={form.name}
                    onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                    placeholder="请输入姓名"
                    required
                    autoComplete="name"
                  />
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="child-gender">性别</label>
                    <select
                      id="child-gender"
                      value={form.gender}
                      onChange={(event) => setForm((current) => ({ ...current, gender: event.target.value }))}
                      className={FORM_CONTROL_CLASS}
                    >
                      <option value="">请选择</option>
                      <option value="男">男</option>
                      <option value="女">女</option>
                    </select>
                  </div>
                  <Input
                    label="出生日期"
                    type="date"
                    value={form.birthDate}
                    onChange={(event) => setForm((current) => ({ ...current, birthDate: event.target.value }))}
                    required
                  />
                  <Input
                    label="身份证号"
                    value={form.idCardNo}
                    onChange={(event) => setForm((current) => ({ ...current, idCardNo: event.target.value }))}
                    placeholder="请输入身份证号"
                    autoComplete="off"
                  />
                </div>
              </div>

              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">监护人</h4>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <Input
                    label="监护人姓名"
                    value={form.guardianName}
                    onChange={(event) => setForm((current) => ({ ...current, guardianName: event.target.value }))}
                    placeholder="请输入监护人姓名"
                    autoComplete="off"
                  />
                  <Input
                    label="与儿童关系"
                    value={form.guardianRelationship}
                    onChange={(event) => setForm((current) => ({ ...current, guardianRelationship: event.target.value }))}
                    placeholder="例如：母亲"
                    autoComplete="off"
                  />
                  <Input
                    label="监护人电话"
                    type="tel"
                    value={form.guardianPhone}
                    onChange={(event) => setForm((current) => ({ ...current, guardianPhone: event.target.value }))}
                    placeholder="请输入监护人电话"
                    autoComplete="off"
                  />
                </div>
              </div>

              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">出生信息</h4>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <Input
                    label="出生体重（克）"
                    type="number"
                    min="0"
                    value={form.birthWeightG}
                    onChange={(event) => setForm((current) => ({ ...current, birthWeightG: event.target.value }))}
                    placeholder="例如：3200"
                  />
                  <Input
                    label="出生身长（毫米）"
                    type="number"
                    min="0"
                    value={form.birthHeightMm}
                    onChange={(event) => setForm((current) => ({ ...current, birthHeightMm: event.target.value }))}
                    placeholder="例如：500"
                  />
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="child-delivery-mode">分娩方式</label>
                    <select
                      id="child-delivery-mode"
                      value={form.deliveryMode}
                      onChange={(event) => setForm((current) => ({ ...current, deliveryMode: event.target.value }))}
                      className={FORM_CONTROL_CLASS}
                    >
                      <option value="">请选择</option>
                      <option value="顺产">顺产</option>
                      <option value="剖宫产">剖宫产</option>
                      <option value="其他">其他</option>
                    </select>
                  </div>
                </div>
              </div>

              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">喂养与接种</h4>
                <div className="mt-3 space-y-4">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="child-feeding-method">喂养方式</label>
                    <select
                      id="child-feeding-method"
                      value={form.feedingMethod}
                      onChange={(event) => setForm((current) => ({ ...current, feedingMethod: event.target.value }))}
                      className={FORM_CONTROL_CLASS}
                    >
                      <option value="">请选择</option>
                      <option value="母乳">母乳</option>
                      <option value="混合">混合</option>
                      <option value="人工">人工</option>
                    </select>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="child-vaccination-summary">预防接种摘要</label>
                    <textarea
                      id="child-vaccination-summary"
                      value={form.vaccinationSummary}
                      onChange={(event) => setForm((current) => ({ ...current, vaccinationSummary: event.target.value }))}
                      rows={3}
                      className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                      placeholder="请输入预防接种摘要"
                    />
                  </div>
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-fg-muted">接种记录</span>
                      <Button type="button" variant="secondary" size="sm" onClick={addVaccinationRow}>添加接种记录</Button>
                    </div>
                    {form.vaccinationRecords.length === 0 && (
                      <p className="text-sm text-fg-dimmed">暂无接种记录，点击「添加接种记录」录入</p>
                    )}
                    {form.vaccinationRecords.map((record, index) => (
                      <div key={index} className="grid gap-3 rounded-md border border-border p-3 sm:grid-cols-2">
                        <input
                          aria-label={`疫苗名称 ${index + 1}`}
                          value={record.vaccine}
                          onChange={(event) => updateVaccinationRow(index, { vaccine: event.target.value })}
                          placeholder="疫苗名称"
                          className={FORM_CONTROL_CLASS}
                        />
                        <input
                          aria-label={`剂次 ${index + 1}`}
                          value={record.dose}
                          onChange={(event) => updateVaccinationRow(index, { dose: event.target.value })}
                          placeholder="剂次"
                          className={FORM_CONTROL_CLASS}
                        />
                        <input
                          aria-label={`接种日期 ${index + 1}`}
                          type="date"
                          value={record.date}
                          onChange={(event) => updateVaccinationRow(index, { date: event.target.value })}
                          className={FORM_CONTROL_CLASS}
                        />
                        <input
                          aria-label={`接种机构 ${index + 1}`}
                          value={record.facility}
                          onChange={(event) => updateVaccinationRow(index, { facility: event.target.value })}
                          placeholder="接种机构"
                          className={FORM_CONTROL_CLASS}
                        />
                        <div className="flex justify-end sm:col-span-2">
                          <Button type="button" variant="ghost" size="sm" onClick={() => removeVaccinationRow(index)}>删除</Button>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="child-remark">备注</label>
                    <textarea
                      id="child-remark"
                      value={form.remark}
                      onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))}
                      rows={3}
                      className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                      placeholder="请输入备注"
                    />
                  </div>
                </div>
              </div>
            </>
          ) : (
            <>
              {/* 医疗 / 养老域：表单保持现状 */}
              <div>
                <h4 className="text-sm font-semibold text-fg-emphasis">基本信息</h4>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <Input
                label="姓名"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                placeholder="请输入姓名"
                required
                autoComplete="name"
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="elder-gender">性别</label>
                <select
                  id="elder-gender"
                  value={form.gender}
                  onChange={(event) => setForm((current) => ({ ...current, gender: event.target.value }))}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <option value="">请选择</option>
                  <option value="男">男</option>
                  <option value="女">女</option>
                </select>
              </div>
              <Input
                label="出生日期"
                type="date"
                value={form.birthDate}
                onChange={(event) => setForm((current) => ({ ...current, birthDate: event.target.value }))}
              />
              <Input
                label="身份证号"
                value={form.idCardNo}
                onChange={(event) => setForm((current) => ({ ...current, idCardNo: event.target.value }))}
                placeholder="请输入身份证号"
                autoComplete="off"
              />
            </div>
          </div>

          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">联系方式</h4>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <Input
                label="本人联系电话"
                type="tel"
                value={form.phone}
                onChange={(event) => setForm((current) => ({ ...current, phone: event.target.value }))}
                placeholder="请输入联系电话"
                autoComplete="tel"
              />
              <Input
                label="紧急联系人"
                value={form.emergencyName}
                onChange={(event) => setForm((current) => ({ ...current, emergencyName: event.target.value }))}
                placeholder="请输入联系人姓名"
                autoComplete="off"
              />
              <Input
                label="联系人关系"
                value={form.emergencyRelationship}
                onChange={(event) => setForm((current) => ({ ...current, emergencyRelationship: event.target.value }))}
                placeholder="例如：女儿"
                autoComplete="off"
              />
              <Input
                label="联系人电话"
                type="tel"
                value={form.emergencyPhone}
                onChange={(event) => setForm((current) => ({ ...current, emergencyPhone: event.target.value }))}
                placeholder="请输入联系人电话"
                autoComplete="off"
              />
            </div>
            <div className="mt-4 flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="elder-address">居住地址</label>
              <textarea
                id="elder-address"
                value={form.address}
                onChange={(event) => setForm((current) => ({ ...current, address: event.target.value }))}
                rows={2}
                className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                placeholder="请输入居住地址"
              />
            </div>
          </div>

          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">健康信息</h4>
            <div className="mt-3 space-y-4">
              <Input
                label="医保信息"
                value={form.medicalInsurance}
                onChange={(event) => setForm((current) => ({ ...current, medicalInsurance: event.target.value }))}
                placeholder="例如：城乡居民医保"
              />
              <Input
                label="过敏史"
                value={form.allergies}
                onChange={(event) => setForm((current) => ({ ...current, allergies: event.target.value }))}
                placeholder="多个过敏项请用逗号分隔，没有可不填"
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="elder-past-history">既往病史</label>
                <textarea
                  id="elder-past-history"
                  value={form.pastHistory}
                  onChange={(event) => setForm((current) => ({ ...current, pastHistory: event.target.value }))}
                  rows={3}
                  className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  placeholder="请输入既往病史、手术史等信息"
                />
              </div>
            </div>
          </div>
            </>
          )}

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving}>{editTarget ? "更新档案" : "保存档案"}</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
