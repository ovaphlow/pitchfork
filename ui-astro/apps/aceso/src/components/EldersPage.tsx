import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createPatient,
  listElderlyAdmissions,
  listPatients,
  updatePatient,
  type Encounter,
  type Patient,
  type PatientInput,
} from "@pitchfork/shared/aceso";
import { formatDate } from "../lib/datetime";
import { ENTITY_LABELS } from "../lib/labels";

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

/** 表单原生控件样式（与 `@pitchfork/ui` 的 Input / select 保持一致） */
const FORM_CONTROL_CLASS =
  "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

/** 入住状态 → 入住管理页视图参数（URL ?view=），与 AdmissionsPage 的 `view` 取值同源 */
const ENCOUNTER_VIEW: Record<string, string> = {
  ACTIVE: "active",
  DISCHARGED: "discharged",
  DECEASED: "deceased",
};

/**
 * 每页长者各自解析一条代表入住：优先活动入住，其次按入住日期取最新的一条
 * （含已离院/已去世），用于「住院号」列与入住/医嘱/账单跳转；无入住则不返回。
 */
function resolveEncountersByPatient(encounters: Encounter[], patientIds: string[]): Record<string, Encounter> {
  const byPatient: Record<string, Encounter> = {};
  for (const patientId of patientIds) {
    const candidates = encounters.filter((encounter) => encounter.patient_id === patientId);
    if (candidates.length === 0) continue;
    const active = candidates.find((encounter) => encounter.status === "ACTIVE");
    const latest = [...candidates].sort((a, b) => (b.admit_date ?? "").localeCompare(a.admit_date ?? ""))[0];
    byPatient[patientId] = active ?? latest;
  }
  return byPatient;
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

function buildPatientInput(form: ElderForm, editing: boolean): PatientInput | null {
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
    person_type: ENTITY_LABELS.personType,
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

  return input;
}

function formFromPatient(patient: Patient): ElderForm {
  const emergencyContact = patient.emergency_contact ?? {};
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
  };
}

export default function EldersPage() {
  // 056：养老单产品，命名直接取 ENTITY_LABELS（与菜单、浏览器标题同源）
  const { person, archive } = ENTITY_LABELS;
  const [elders, setElders] = useState<Patient[]>([]);
  const [encountersByPatient, setEncountersByPatient] = useState<Record<string, Encounter>>({});
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
      const [response, encounterResponse] = await Promise.all([
        listPatients({
          ...(statusFilter ? { status: statusFilter } : {}),
          limit: PAGE_SIZE,
          offset: (targetPage - 1) * PAGE_SIZE,
        }),
        // 取全部状态的养老入住：档案页签（有效/已去世/全部）下都要能拿到代表入住，
        // 否则已去世/已离院长者的「住院号」与入住/医嘱/账单跳转都为空。
        listElderlyAdmissions({ status: "", limit: 1000 }),
      ]);
      setElders(response.records);
      setEncountersByPatient(
        resolveEncountersByPatient(
          encounterResponse.records,
          response.records.map((patient) => patient.id),
        ),
      );
      setTotal(response.meta.total);
      setPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, `无法加载${ENTITY_LABELS.archive}`));
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

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
    setForm(formFromPatient(patient));
    setFormError("");
    setEditorOpen(true);
  }




  async function handleSave() {
    const input = buildPatientInput(form, editTarget !== null);
    if (!input) {
      setFormError("姓名不能为空");
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
  columns.push(
    { key: "id_card_no", header: "身份证号", className: "min-w-[190px]", render: (row) => displayValue(row.id_card_no) },
    { key: "encounter_no", header: "住院号", className: "min-w-[140px]", render: (row) => displayValue(encountersByPatient[row.id]?.encounter_no) },
  );
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
      className: "w-[220px]",
      render: (row) => {
        const encounter = encountersByPatient[row.id];
        const view = encounter ? ENCOUNTER_VIEW[encounter.status] : undefined;
        return (
          <div className="flex items-center gap-3">
            <Button variant="link" size="sm" onClick={() => openEdit(row)}>编辑</Button>
            {encounter && (
              <>
                {view && (
                  <a
                    href={`/eldercare/admission?view=${view}&patient=${encodeURIComponent(row.id)}`}
                    className="text-sm text-accent hover:underline underline-offset-4"
                  >
                    入住
                  </a>
                )}
                <a
                  href={`/eldercare/orders?encounter_id=${encounter.id}`}
                  className="text-sm text-accent hover:underline underline-offset-4"
                >
                  医嘱
                </a>
                <a
                  href={`/eldercare/billing?encounter_id=${encounter.id}`}
                  className="text-sm text-accent hover:underline underline-offset-4"
                >
                  账单
                </a>
              </>
            )}
          </div>
        );
      },
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

          <>
            {/* 长者档案表单 */}
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

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving}>{editTarget ? "更新档案" : "保存档案"}</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
