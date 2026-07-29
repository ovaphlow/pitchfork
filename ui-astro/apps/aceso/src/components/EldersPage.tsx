import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createPatient,
  listActiveElderlyAdmissions,
  listPatients,
  updatePatient,
  type Encounter,
  type Patient,
  type PatientInput,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

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

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
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

  return {
    name,
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
  const [elders, setElders] = useState<Patient[]>([]);
  const [activeEncounters, setActiveEncounters] = useState<Record<string, Encounter>>({});
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
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
          status: "ACTIVE",
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
      setPageError(errorMessage(error, "无法加载长者档案"));
    } finally {
      setLoading(false);
    }
  }, []);

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
      setFormError(errorMessage(error, editTarget ? "无法更新长者档案" : "无法保存长者档案"));
    } finally {
      setSaving(false);
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns: Column<Patient>[] = [
    { key: "name", header: "姓名", className: "min-w-[140px]" },
    { key: "gender", header: "性别", className: "w-[90px]", render: (row) => displayValue(row.gender) },
    { key: "birth_date", header: "出生日期", className: "min-w-[130px]", render: (row) => formatDate(row.birth_date) },
    { key: "id_card_no", header: "身份证号", className: "min-w-[190px]", render: (row) => displayValue(row.id_card_no) },
    { key: "encounter_no", header: "住院号", className: "min-w-[140px]", render: (row) => displayValue(activeEncounters[row.id]?.encounter_no) },
    { key: "phone", header: "联系电话", className: "min-w-[140px]", render: (row) => displayValue(row.phone) },
    {
      key: "status",
      header: "状态",
      className: "w-[100px]",
      render: (row) => <Badge variant={row.status === "ACTIVE" ? "success" : "default"}>{row.status === "ACTIVE" ? "有效" : row.status}</Badge>,
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[90px]",
      render: (row) => <Button variant="link" size="sm" onClick={() => openEdit(row)}>编辑</Button>,
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">长者档案</h2>
          <p className="mt-1 text-sm text-fg-muted">建立长者基础档案，后续入住与照护记录将关联到此档案</p>
        </div>
        <Button variant="primary" onClick={openCreate}>录入长者</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="长者档案列表" actions={<span className="text-sm text-fg-dimmed">共 {total} 条</span>}>
        <Table columns={columns} data={elders} loading={loading} emptyMessage="暂无长者档案，点击右上角开始录入" />
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
          <span className="text-sm text-fg-muted">第 {page} / {pageCount} 页</span>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={page <= 1 || loading} onClick={() => void load(page - 1)}>上一页</Button>
            <Button variant="secondary" size="sm" disabled={page >= pageCount || loading} onClick={() => void load(page + 1)}>下一页</Button>
          </div>
        </div>
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑长者档案" : "录入长者档案"}>
        <form
          className="space-y-5"
          onSubmit={(event) => {
            event.preventDefault();
            void handleSave();
          }}
        >
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}

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

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving}>{editTarget ? "更新档案" : "保存档案"}</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
