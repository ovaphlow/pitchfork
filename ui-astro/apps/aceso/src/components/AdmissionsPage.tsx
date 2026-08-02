import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createElderlyAdmission,
  createElderlyDischargeHandover,
  dischargeEncounter,
  getElderlyDischargeHandover,
  listActiveElderlyAdmissions,
  listElderlyAdmissions,
  listPatients,
  markEncounterDeath,
  type ElderlyAdmissionInput,
  type ElderlyDischargeHandover,
  type ElderlyDischargeHandoverSnapshot,
  type Encounter,
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

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
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

const EXECUTION_STATUS_LABEL: Record<string, string> = {
  PENDING: "待执行",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
};

function ExecutionSummary({ summary }: { summary: ElderlyDischargeHandoverSnapshot["execution_summary"] }) {
  const entries = [
    ["PENDING", "待执行"],
    ["IN_PROGRESS", "执行中"],
    ["COMPLETED", "已完成"],
    ["SKIPPED", "已跳过"],
    ["CANCELLED", "已取消"],
  ] as const;
  return (
    <div className="flex flex-wrap gap-2">
      {entries.map(([key, label]) => (
        <span key={key} className="inline-flex items-center gap-1 rounded-full bg-surface-alt px-3 py-1 text-sm text-fg-muted">
          <span className="font-semibold text-fg">{summary[key]}</span>
          {label}
        </span>
      ))}
    </div>
  );
}

function HandoverReadOnly({ handover }: { handover: ElderlyDischargeHandover }) {
  const snapshot = handover.snapshot;
  const patient = snapshot.patient;
  const encounter = snapshot.encounter;
  const period = snapshot.care_period;
  const emergency = patient.emergency_contact as Record<string, string> | null;

  return (
    <div className="space-y-6">
      {/* 归档说明 */}
      <div className="rounded-lg border border-info/30 bg-info-bg px-4 py-3 text-sm text-info">
        本摘要为离院时的只读归档快照（版本 {handover.snapshot_version}），归档时间 {formatDateTime(handover.generated_at)}；
        归档后源记录更正不会自动同步此摘要。
      </div>

      {/* 基础资料 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">长者基础资料</h4>
        <div className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
          <p><span className="text-fg-dimmed">姓名：</span>{patient.name || "-"}</p>
          <p><span className="text-fg-dimmed">性别：</span>{patient.gender || "-"}</p>
          <p><span className="text-fg-dimmed">出生日期：</span>{formatDate(patient.birth_date)}</p>
          <p>
            <span className="text-fg-dimmed">紧急联系人：</span>
            {emergency ? `${emergency.name ?? "-"}${emergency.phone ? `（${emergency.phone}）` : ""}` : "-"}
          </p>
          <p className="sm:col-span-2">
            <span className="text-fg-dimmed">过敏史：</span>
            {patient.allergies && patient.allergies.length > 0
              ? patient.allergies.map((item) => (item as { allergen?: string }).allergen ?? "-").join("、")
              : "无"}
          </p>
          <p className="sm:col-span-2"><span className="text-fg-dimmed">既往史摘要：</span>{patient.past_history || "-"}</p>
        </div>
      </section>

      {/* 入住与周期 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">入住与照护周期</h4>
        <div className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
          <p><span className="text-fg-dimmed">住院号：</span>{encounter.encounter_no || "-"}</p>
          <p><span className="text-fg-dimmed">照护单元：</span>{encounter.department || "-"}</p>
          <p><span className="text-fg-dimmed">房间床位：</span>{encounter.ward || "-"}</p>
          <p><span className="text-fg-dimmed">责任照护人员：</span>{encounter.attending_physician || "-"}</p>
          <p><span className="text-fg-dimmed">入住时间：</span>{formatDateTime(encounter.admit_date)}</p>
          <p><span className="text-fg-dimmed">离院时间：</span>{formatDateTime(encounter.discharge_date)}</p>
          <p className="sm:col-span-2"><span className="text-fg-dimmed">入院诊断：</span>{encounter.admitting_diagnosis || "-"}</p>
          <p className="sm:col-span-2"><span className="text-fg-dimmed">离院诊断：</span>{encounter.discharge_diagnosis || "-"}</p>
          <p><span className="text-fg-dimmed">周期 ID：</span>{period.id || "-"}</p>
          <p><span className="text-fg-dimmed">服务类型：</span>{period.service_type || "-"}</p>
          <p><span className="text-fg-dimmed">周期起止：</span>{formatDate(period.start_date)} 至 {formatDate(period.end_date)}</p>
          <p><span className="text-fg-dimmed">协调人：</span>{period.coordinator || "-"}</p>
        </div>
      </section>

      {/* 护理评估 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">护理评估（{snapshot.assessments.length}）</h4>
        {snapshot.assessments.length === 0 ? (
          <p className="text-sm text-fg-dimmed">无评估记录</p>
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="bg-surface-alt text-xs text-fg-dimmed">
                <tr>
                  <th className="px-3 py-2 font-medium">类型</th>
                  <th className="px-3 py-2 font-medium">日期</th>
                  <th className="px-3 py-2 font-medium">评估人</th>
                  <th className="px-3 py-2 font-medium">分数</th>
                  <th className="px-3 py-2 font-medium">结论</th>
                  <th className="px-3 py-2 font-medium">备注</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {snapshot.assessments.map((item) => (
                  <tr key={item.id}>
                    <td className="px-3 py-2">{item.assess_type || "-"}</td>
                    <td className="px-3 py-2">{formatDate(item.assess_date)}</td>
                    <td className="px-3 py-2">{item.assessor || "-"}</td>
                    <td className="px-3 py-2">{item.total_score ?? "-"}</td>
                    <td className="px-3 py-2">{item.result_level || "-"}</td>
                    <td className="px-3 py-2">{item.remark || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* 照护计划 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">照护计划（{snapshot.plans.length}）</h4>
        {snapshot.plans.length === 0 ? (
          <p className="text-sm text-fg-dimmed">无照护计划</p>
        ) : (
          <div className="space-y-3">
            {snapshot.plans.map((plan) => (
              <div key={plan.id} className="rounded-md border border-border p-3">
                <p className="text-sm font-medium text-fg-emphasis">
                  {plan.plan_name || "-"}
                  <span className="ml-2 text-xs text-fg-dimmed">
                    {formatDate(plan.start_date)} 至 {formatDate(plan.end_date)} · {plan.status || "-"}
                  </span>
                </p>
                {plan.goals && <p className="mt-1 text-sm text-fg-muted">目标：{plan.goals}</p>}
                {plan.created_by && <p className="mt-0.5 text-xs text-fg-dimmed">创建人：{plan.created_by}</p>}
                {plan.items.length > 0 && (
                  <ul className="mt-2 space-y-1 border-t border-border pt-2 text-sm">
                    {plan.items.map((item) => (
                      <li key={item.id} className="flex flex-wrap gap-x-2 gap-y-0.5">
                        <span className="text-fg">{item.action || "-"}</span>
                        {item.frequency_name && <span className="text-fg-dimmed">{item.frequency_name}</span>}
                        {item.duration_days != null && <span className="text-fg-dimmed">{item.duration_days} 天</span>}
                        <span className="text-fg-dimmed">{item.status || "-"}</span>
                        {item.remark && <span className="text-fg-muted">（{item.remark}）</span>}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* 任务与执行 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">任务与执行（{snapshot.tasks.length}）</h4>
        <ExecutionSummary summary={snapshot.execution_summary} />
        {snapshot.tasks.length === 0 ? (
          <p className="mt-2 text-sm text-fg-dimmed">无任务记录</p>
        ) : (
          <div className="mt-2 space-y-3">
            {snapshot.tasks.map((task) => (
              <div key={task.id} className="rounded-md border border-border p-3">
                <p className="text-sm font-medium text-fg-emphasis">
                  {task.description || "-"}
                  <span className="ml-2 text-xs text-fg-dimmed">
                    {task.task_type || ""} {task.frequency_name ? `· ${task.frequency_name}` : ""} ·
                    {task.status || "-"}
                  </span>
                </p>
                <p className="mt-0.5 text-xs text-fg-dimmed">
                  {formatDate(task.start_date)} 至 {formatDate(task.end_date)}
                </p>
                {task.executions.length > 0 && (
                  <div className="mt-2 overflow-x-auto rounded-md border border-border">
                    <table className="w-full min-w-[480px] text-left text-sm">
                      <thead className="bg-surface-alt text-xs text-fg-dimmed">
                        <tr>
                          <th className="px-3 py-2 font-medium">计划时间</th>
                          <th className="px-3 py-2 font-medium">实际时间</th>
                          <th className="px-3 py-2 font-medium">执行人</th>
                          <th className="px-3 py-2 font-medium">状态</th>
                          <th className="px-3 py-2 font-medium">备注</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {task.executions.map((exec) => (
                          <tr key={exec.id}>
                            <td className="px-3 py-2">{formatDateTime(exec.planned_time)}</td>
                            <td className="px-3 py-2">{formatDateTime(exec.actual_time)}</td>
                            <td className="px-3 py-2">{exec.executor || "-"}</td>
                            <td className="px-3 py-2">{EXECUTION_STATUS_LABEL[exec.status ?? ""] ?? exec.status ?? "-"}</td>
                            <td className="px-3 py-2">{exec.note || "-"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* 护理记录 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">护理记录（{snapshot.nursing_records.length}）</h4>
        {snapshot.nursing_records.length === 0 ? (
          <p className="text-sm text-fg-dimmed">无护理记录</p>
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full min-w-[560px] text-left text-sm">
              <thead className="bg-surface-alt text-xs text-fg-dimmed">
                <tr>
                  <th className="px-3 py-2 font-medium">种类</th>
                  <th className="px-3 py-2 font-medium">标题</th>
                  <th className="px-3 py-2 font-medium">正文</th>
                  <th className="px-3 py-2 font-medium">记录时间</th>
                  <th className="px-3 py-2 font-medium">记录人</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {snapshot.nursing_records.map((record) => (
                  <tr key={record.id}>
                    <td className="px-3 py-2 whitespace-nowrap">{record.record_kind || "-"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{record.title || "-"}</td>
                    <td className="px-3 py-2 min-w-[160px]">{record.content || "-"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{formatDateTime(record.record_time)}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{record.author || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* 归档备注 */}
      <section>
        <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">交接备注</h4>
        <p className="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg-muted">
          {handover.handover_note || "无"}
        </p>
      </section>
    </div>
  );
}

export default function AdmissionsPage() {
  const [view, setView] = useState<"active" | "discharged">("active");
  const [patients, setPatients] = useState<Patient[]>([]);
  const [admissions, setAdmissions] = useState<AdmissionRow[]>([]);
  const [dischargedAdmissions, setDischargedAdmissions] = useState<AdmissionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [dischargedLoading, setDischargedLoading] = useState(false);
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

  // 已离院档案弹窗状态
  const [handoverAdmission, setHandoverAdmission] = useState<AdmissionRow | null>(null);
  const [handoverData, setHandoverData] = useState<ElderlyDischargeHandover | null>(null);
  const [handoverLoading, setHandoverLoading] = useState(false);
  const [handoverError, setHandoverError] = useState("");
  const [handoverSubmitError, setHandoverSubmitError] = useState("");
  const [handoverAuthor, setHandoverAuthor] = useState("");
  const [handoverNote, setHandoverNote] = useState("");
  const [handoverSubmitting, setHandoverSubmitting] = useState(false);

  // 办理去世弹窗状态
  const [deathAdmission, setDeathAdmission] = useState<AdmissionRow | null>(null);
  const [deathDate, setDeathDate] = useState("");
  const [deathCause, setDeathCause] = useState("");
  const [deathError, setDeathError] = useState("");
  const [deathSubmitting, setDeathSubmitting] = useState(false);

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

  const loadDischarged = useCallback(async () => {
    setDischargedLoading(true);
    setPageError("");
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 1000 }),
        listElderlyAdmissions({ status: "DISCHARGED", limit: 100 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      setDischargedAdmissions(encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      })));
    } catch (error) {
      setPageError(errorMessage(error, "无法加载已离院档案"));
    } finally {
      setDischargedLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (view === "discharged") void loadDischarged();
  }, [view, loadDischarged]);

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

  function openDeath(admission: AdmissionRow) {
    setDeathAdmission(admission);
    setDeathDate("");
    setDeathCause("");
    setDeathError("");
    setDeathSubmitting(false);
  }

  async function handleDeath() {
    if (!deathAdmission) return;
    const deathDateValue = deathDate.trim();
    if (!deathDateValue) {
      setDeathError("去世时间不能为空");
      return;
    }
    setDeathSubmitting(true);
    setDeathError("");
    try {
      await markEncounterDeath(deathAdmission.id, {
        death_date: `${deathDateValue}:00+08:00`,
        ...(deathCause.trim() ? { death_cause: deathCause.trim() } : {}),
      });
      setDeathAdmission(null);
      setDeathDate("");
      setDeathCause("");
      await load();
    } catch (error) {
      // 409/网络/校验失败：保留表单输入，错误独立展示（不得用错误面板替换输入表单）
      setDeathError(errorMessage(error, "无法办理去世"));
    } finally {
      setDeathSubmitting(false);
    }
  }

  function closeDeath() {
    if (deathSubmitting) return;
    setDeathAdmission(null);
    setDeathDate("");
    setDeathCause("");
    setDeathError("");
  }

  /** 打开已离院档案弹窗：先单次 GET 摘要，404 时展示受控生成表单 */
  async function openHandover(admission: AdmissionRow) {
    setHandoverAdmission(admission);
    setHandoverData(null);
    setHandoverError("");
    setHandoverSubmitError("");
    setHandoverAuthor("");
    setHandoverNote("");
    setHandoverLoading(true);
    try {
      const data = await getElderlyDischargeHandover(admission.id);
      setHandoverData(data);
    } catch (error) {
      setHandoverError(errorMessage(error, "无法读取交接摘要"));
    } finally {
      setHandoverLoading(false);
    }
  }

  async function handleGenerateHandover() {
    if (!handoverAdmission) return;
    const author = handoverAuthor.trim();
    if (!author) {
      setHandoverSubmitError("交接人不能为空");
      return;
    }
    setHandoverSubmitting(true);
    setHandoverSubmitError("");
    try {
      const created = await createElderlyDischargeHandover(handoverAdmission.id, {
        author,
        ...(handoverNote.trim() ? { handover_note: handoverNote.trim() } : {}),
      });
      setHandoverData(created);
      setHandoverSubmitError("");
      await loadDischarged();
    } catch (error) {
      // 409/网络/校验失败：保留表单与用户输入，错误独立展示（6.4.3）
      setHandoverSubmitError(errorMessage(error, "无法生成交接摘要"));
    } finally {
      setHandoverSubmitting(false);
    }
  }

  function closeHandover() {
    if (handoverSubmitting) return;
    setHandoverAdmission(null);
    setHandoverData(null);
    setHandoverError("");
    setHandoverSubmitError("");
  }

  const availablePatients = patients.filter((patient) => !admissions.some((admission) => admission.patient_id === patient.id));
  const availablePatientOptions = patientOptions.filter((patient) => !admissions.some((admission) => admission.patient_id === patient.id));
  const activeColumns: Column<AdmissionRow>[] = [
    { key: "patientName", header: "长者", className: "min-w-[140px]" },
    { key: "encounter_no", header: "住院号", className: "min-w-[140px]" },
    { key: "admit_date", header: "入住日期", className: "min-w-[120px]", render: (row) => formatDate(row.admit_date) },
    { key: "department", header: "照护单元/病区", className: "min-w-[150px]", render: (row) => row.department || "-" },
    { key: "ward", header: "房间床位", className: "min-w-[120px]", render: (row) => row.ward || "-" },
    { key: "attending_physician", header: "责任医生/照护师", className: "min-w-[160px]", render: (row) => row.attending_physician || "-" },
    {
      key: "actions",
      header: "操作",
      className: "w-[240px]",
      render: (row) => (
        <div className="flex items-center gap-3">
          <a
            href={`/dashboard/orders?encounter_id=${row.id}`}
            className="text-sm text-accent hover:underline underline-offset-4"
          >
            医嘱
          </a>
          <Button
            variant="link"
            size="sm"
            disabled={dischargingId === row.id}
            onClick={() => void handleDischarge(row)}
          >
            {dischargingId === row.id ? "处理中" : "办理离院"}
          </Button>
          <Button
            variant="link"
            size="sm"
            className="text-danger!"
            disabled={dischargingId === row.id}
            onClick={() => openDeath(row)}
          >
            办理去世
          </Button>
        </div>
      ),
    },
  ];

  const dischargedColumns: Column<AdmissionRow>[] = [
    { key: "patientName", header: "长者", className: "min-w-[140px]" },
    { key: "encounter_no", header: "住院号", className: "min-w-[140px]" },
    { key: "admit_date", header: "入住日期", className: "min-w-[120px]", render: (row) => formatDate(row.admit_date) },
    { key: "discharge_date", header: "离院日期", className: "min-w-[120px]", render: (row) => formatDate(row.discharge_date) },
    {
      key: "actions",
      header: "操作",
      className: "w-[110px]",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openHandover(row)}>
          查看/生成交接摘要
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

      {/* 视图切换 */}
      <div className="flex gap-1 rounded-lg border border-border bg-surface p-1 w-fit">
        <button
          type="button"
          onClick={() => setView("active")}
          className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${
            view === "active" ? "bg-accent text-white" : "text-fg-muted hover:text-fg"
          }`}
        >
          当前活动入住
        </button>
        <button
          type="button"
          onClick={() => setView("discharged")}
          className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${
            view === "discharged" ? "bg-accent text-white" : "text-fg-muted hover:text-fg"
          }`}
        >
          已离院档案
        </button>
      </div>

      {view === "active" ? (
        <Card title="当前活动入住" actions={<span className="text-sm text-fg-dimmed">共 {admissions.length} 条</span>}>
          <Table columns={activeColumns} data={admissions} loading={loading} emptyMessage="暂无活动入住记录" />
        </Card>
      ) : (
        <Card title="已离院档案" actions={<span className="text-sm text-fg-dimmed">共 {dischargedAdmissions.length} 条</span>}>
          <Table
            columns={dischargedColumns}
            data={dischargedAdmissions}
            loading={dischargedLoading}
            emptyMessage="暂无已离院档案"
          />
        </Card>
      )}

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

      {/* 已离院交接摘要弹窗 */}
      <Modal
        open={handoverAdmission !== null}
        onClose={closeHandover}
        title={handoverAdmission ? `养老照护离院交接摘要 · ${handoverAdmission.patientName}` : "养老照护离院交接摘要"}
      >
        {handoverLoading && <p className="text-sm text-fg-dimmed">正在读取交接摘要…</p>}
        {!handoverLoading && handoverData === null && handoverError && (
          <div className="space-y-4">
            <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{handoverError}</div>
            <div className="flex justify-end">
              <Button type="button" variant="ghost" onClick={closeHandover}>关闭</Button>
            </div>
          </div>
        )}
        {!handoverLoading && handoverData === null && !handoverError && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              该入住尚未生成交接摘要。归档将把关联照护周期的评估、计划、任务、执行与护理记录封存为不可变快照，之后源记录更正不会自动同步。
            </p>
            {handoverSubmitError && (
              <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {handoverSubmitError}
              </div>
            )}
            <form
              aria-label="生成交接摘要"
              className="space-y-4"
              onSubmit={(event) => {
                event.preventDefault();
                void handleGenerateHandover();
              }}
            >
              <Input
                label="交接人（必填）"
                value={handoverAuthor}
                onChange={(event) => setHandoverAuthor(event.target.value)}
                placeholder="请输入交接人姓名"
                maxLength={100}
                required
              />
              <Input
                label="交接备注（可选）"
                value={handoverNote}
                onChange={(event) => setHandoverNote(event.target.value)}
                placeholder="如：已向家属说明居家照护注意事项"
                maxLength={2000}
              />
              <div className="flex justify-end gap-3 pt-1">
                <Button type="button" variant="ghost" onClick={closeHandover} disabled={handoverSubmitting}>取消</Button>
                <Button type="submit" loading={handoverSubmitting} disabled={handoverSubmitting}>生成交接摘要</Button>
              </div>
            </form>
          </div>
        )}
        {!handoverLoading && !handoverError && handoverData !== null && (
          <>
            <HandoverReadOnly handover={handoverData} />
            <div className="mt-6 flex justify-end">
              <Button type="button" variant="ghost" onClick={closeHandover}>关闭</Button>
            </div>
          </>
        )}
      </Modal>

      {/* 办理去世弹窗 */}
      <Modal
        open={deathAdmission !== null}
        onClose={closeDeath}
        title={deathAdmission ? `办理去世 · ${deathAdmission.patientName}` : "办理去世"}
      >
        <div className="space-y-4">
          <p className="rounded-lg border border-info/30 bg-info-bg px-4 py-3 text-sm text-info">
            办理去世将收束该入住全部医嘱、任务与照护周期，并关闭患者档案。此操作不可撤销，请确认后再提交。
          </p>
          {deathError && (
            <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
              {deathError}
            </div>
          )}
          <form
            aria-label="办理去世"
            className="space-y-4"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              void handleDeath();
            }}
          >
            <Input
              label="去世时间（必填）"
              type="datetime-local"
              value={deathDate}
              onChange={(event) => setDeathDate(event.target.value)}
              required
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="death-cause">去世原因（可选）</label>
              <textarea
                id="death-cause"
                value={deathCause}
                onChange={(event) => setDeathCause(event.target.value)}
                maxLength={500}
                rows={3}
                placeholder="如：自然死亡、疾病恶化等"
                className="w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              />
            </div>
            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="ghost" onClick={closeDeath} disabled={deathSubmitting}>取消</Button>
              <Button type="submit" variant="danger" loading={deathSubmitting} disabled={deathSubmitting}>确认办理去世</Button>
            </div>
          </form>
        </div>
      </Modal>
    </div>
  );
}
