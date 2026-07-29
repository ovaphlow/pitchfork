import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createElderlyAdmission,
  dischargeEncounter,
  listActiveElderlyAdmissions,
  listPatients,
  type Encounter,
  type ElderlyAdmissionInput,
  type Patient,
} from "@pitchfork/shared/aceso";

interface AdmissionForm {
  patientId: string;
  encounterNo: string;
  admitDate: string;
  department: string;
  ward: string;
  attendingPhysician: string;
}

interface AdmissionRow extends Encounter {
  patientName: string;
}

const admissionFormDefaults: AdmissionForm = {
  patientId: "",
  encounterNo: "",
  admitDate: "",
  department: "",
  ward: "",
  attendingPhysician: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function toAdmissionInput(form: AdmissionForm): ElderlyAdmissionInput | null {
  const encounterNo = form.encounterNo.trim();
  if (!form.patientId || !encounterNo || !form.admitDate) return null;
  return {
    patient_id: form.patientId,
    encounter_no: encounterNo,
    admit_date: `${form.admitDate}T00:00:00+08:00`,
    ...(form.department.trim() ? { department: form.department.trim() } : {}),
    ...(form.ward.trim() ? { ward: form.ward.trim() } : {}),
    ...(form.attendingPhysician.trim() ? { attending_physician: form.attendingPhysician.trim() } : {}),
  };
}

export default function AdmissionsPage() {
  const [patients, setPatients] = useState<Patient[]>([]);
  const [admissions, setAdmissions] = useState<AdmissionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<AdmissionForm>(admissionFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [dischargingId, setDischargingId] = useState<string | null>(null);
  const [patientSearch, setPatientSearch] = useState("");
  const [patientOptions, setPatientOptions] = useState<Patient[]>([]);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [patientDropdownOpen, setPatientDropdownOpen] = useState(false);
  const [patientSearchLoading, setPatientSearchLoading] = useState(false);
  const [patientSearchError, setPatientSearchError] = useState("");
  const patientSearchRequest = useRef(0);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ status: "ACTIVE", limit: 100 }),
        listActiveElderlyAdmissions({ limit: 100 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      setPatients(patientResponse.records);
      setAdmissions(encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      })));
    } catch (error) {
      setPageError(errorMessage(error, "无法加载入住记录"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!editorOpen) return;

    const query = patientSearch.trim();
    const requestId = ++patientSearchRequest.current;
    if (!query) {
      setPatientOptions(patients);
      setPatientSearchLoading(false);
      setPatientSearchError("");
      return;
    }
    if (selectedPatient?.name === patientSearch) {
      setPatientOptions([selectedPatient]);
      setPatientSearchLoading(false);
      setPatientSearchError("");
      return;
    }

    setPatientSearchLoading(true);
    setPatientSearchError("");
    const timeoutId = window.setTimeout(() => {
      void listPatients({ name: query, status: "ACTIVE", limit: 20 })
        .then((response) => {
          if (requestId !== patientSearchRequest.current) return;
          setPatientOptions(response.records);
        })
        .catch((error) => {
          if (requestId !== patientSearchRequest.current) return;
          setPatientOptions([]);
          setPatientSearchError(errorMessage(error, "无法搜索长者"));
        })
        .finally(() => {
          if (requestId === patientSearchRequest.current) setPatientSearchLoading(false);
        });
    }, 300);

    return () => window.clearTimeout(timeoutId);
  }, [editorOpen, patientSearch, patients, selectedPatient]);

  function openCreate() {
    setForm(admissionFormDefaults);
    setFormError("");
    setPatientSearch("");
    setPatientOptions(availablePatients);
    setSelectedPatient(null);
    setPatientSearchError("");
    setPatientDropdownOpen(false);
    setEditorOpen(true);
  }

  function handlePatientSearchChange(value: string) {
    setPatientSearch(value);
    setSelectedPatient(null);
    setForm((current) => ({ ...current, patientId: "" }));
    setPatientDropdownOpen(true);
  }

  function selectPatient(patient: Patient) {
    setSelectedPatient(patient);
    setPatientSearch(patient.name);
    setForm((current) => ({ ...current, patientId: patient.id }));
    setPatientDropdownOpen(false);
    setPatientSearchError("");
  }

  async function handleSave() {
    const input = toAdmissionInput(form);
    if (!input) {
      setFormError("长者、住院号和入住日期不能为空");
      return;
    }

    setSaving(true);
    setFormError("");
    try {
      await createElderlyAdmission(input);
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, "无法保存入住记录"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDischarge(encounter: Encounter) {
    if (!window.confirm(`确认办理住院号 ${encounter.encounter_no} 的离院吗？`)) return;
    setDischargingId(encounter.id);
    setPageError("");
    try {
      await dischargeEncounter(encounter.id, new Date().toISOString());
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法办理离院"));
    } finally {
      setDischargingId(null);
    }
  }

  const availablePatients = patients.filter((patient) => !admissions.some((admission) => admission.patient_id === patient.id));
  const availablePatientOptions = patientOptions.filter((patient) => !admissions.some((admission) => admission.patient_id === patient.id));
  const columns: Column<AdmissionRow>[] = [
    { key: "patientName", header: "长者", className: "min-w-[140px]" },
    { key: "encounter_no", header: "住院号", className: "min-w-[140px]" },
    { key: "admit_date", header: "入住日期", className: "min-w-[120px]", render: (row) => formatDate(row.admit_date) },
    { key: "department", header: "照护单元/病区", className: "min-w-[150px]", render: (row) => row.department || "-" },
    { key: "ward", header: "房间床位", className: "min-w-[120px]", render: (row) => row.ward || "-" },
    { key: "attending_physician", header: "责任医生/照护师", className: "min-w-[160px]", render: (row) => row.attending_physician || "-" },
    {
      key: "actions",
      header: "操作",
      className: "w-[110px]",
      render: (row) => (
        <Button
          variant="link"
          size="sm"
          disabled={dischargingId === row.id}
          onClick={() => void handleDischarge(row)}
        >
          {dischargingId === row.id ? "处理中" : "办理离院"}
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">入住管理</h2>
          <p className="mt-1 text-sm text-fg-muted">每次入住建立独立住院号，住院号创建后不可修改</p>
        </div>
        <Button variant="primary" onClick={openCreate}>办理入住</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="当前活动入住" actions={<span className="text-sm text-fg-dimmed">共 {admissions.length} 条</span>}>
        <Table columns={columns} data={admissions} loading={loading} emptyMessage="暂无活动入住记录" />
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title="办理入住">
        <form
          className="space-y-5"
          onSubmit={(event) => {
            event.preventDefault();
            void handleSave();
          }}
        >
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="relative flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="admission-patient">长者</label>
              <input
                id="admission-patient"
                role="combobox"
                aria-autocomplete="list"
                aria-expanded={patientDropdownOpen}
                aria-controls="admission-patient-options"
                value={selectedPatient?.name ?? patientSearch}
                onChange={(event) => handlePatientSearchChange(event.target.value)}
                onFocus={() => setPatientDropdownOpen(true)}
                onBlur={() => window.setTimeout(() => setPatientDropdownOpen(false), 150)}
                onKeyDown={(event) => {
                  if (event.key === "Escape") setPatientDropdownOpen(false);
                }}
                placeholder="请输入姓名搜索"
                required
                autoComplete="off"
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              />
              {patientDropdownOpen && (
                <div
                  id="admission-patient-options"
                  role="listbox"
                  className="absolute inset-x-0 top-[4.75rem] z-10 max-h-56 overflow-y-auto rounded-md border border-border bg-surface py-1 shadow-lg"
                >
                  {patientSearchLoading && <p className="px-3 py-2 text-sm text-fg-dimmed">搜索中…</p>}
                  {!patientSearchLoading && patientSearchError && <p className="px-3 py-2 text-sm text-danger">{patientSearchError}</p>}
                  {!patientSearchLoading && !patientSearchError && availablePatientOptions.length === 0 && (
                    <p className="px-3 py-2 text-sm text-fg-dimmed">{patientSearch.trim() ? "未找到匹配的可入住长者" : "暂无可办理入住的长者"}</p>
                  )}
                  {!patientSearchLoading && !patientSearchError && availablePatientOptions.slice(0, 20).map((patient) => (
                    <button
                      key={patient.id}
                      type="button"
                      role="option"
                      aria-selected={form.patientId === patient.id}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => selectPatient(patient)}
                      className="flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left text-sm text-fg hover:bg-surface-alt"
                    >
                      <span>{patient.name}</span>
                      {(patient.id_card_no || patient.phone) && <span className="text-xs text-fg-dimmed">{patient.id_card_no ?? patient.phone}</span>}
                    </button>
                  ))}
                </div>
              )}
              {patientSearch.trim() === "" && availablePatientOptions.length > 20 && <p className="text-xs text-fg-dimmed">请输入姓名缩小搜索范围，当前显示前 20 条。</p>}
              {availablePatients.length === 0 && <p className="text-xs text-fg-dimmed">暂无可办理入住的长者，请先录入长者档案或办理活动入住的离院。</p>}
            </div>
            <Input
              label="住院号"
              value={form.encounterNo}
              onChange={(event) => setForm((current) => ({ ...current, encounterNo: event.target.value }))}
              placeholder="请输入住院号"
              required
              autoComplete="off"
            />
            <Input
              label="入住日期"
              type="date"
              value={form.admitDate}
              onChange={(event) => setForm((current) => ({ ...current, admitDate: event.target.value }))}
              required
            />
            <Input
              label="照护单元/病区"
              value={form.department}
              onChange={(event) => setForm((current) => ({ ...current, department: event.target.value }))}
              placeholder="请输入照护单元或病区"
            />
            <Input
              label="房间床位"
              value={form.ward}
              onChange={(event) => setForm((current) => ({ ...current, ward: event.target.value }))}
              placeholder="请输入房间和床位"
            />
            <Input
              label="责任医生/照护师"
              value={form.attendingPhysician}
              onChange={(event) => setForm((current) => ({ ...current, attendingPhysician: event.target.value }))}
              placeholder="请输入责任人"
              className="sm:col-span-2"
            />
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving} disabled={availablePatients.length === 0}>保存入住</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
