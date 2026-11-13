import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createChildVaccination,
  getChildVaccinationStatus,
  listPatients,
  remindChildVaccinations,
  type ChildVaccinationRemindResult,
  type ChildVaccinationScheduleRow,
  type ChildVaccinationStatusReport,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import {
  VACCINATION_STATUS_VARIANTS,
  canRemind,
  groupScheduleByVaccine,
  sortByStatus,
  statusHint,
  summarizeVaccinationMeta,
} from "../lib/vaccination";
import { useDomain } from "../lib/useDomain";

/**
 * 预防接种页（052 P3）。
 *
 * 排程与状态判定由后端读时计算（`GET /child-vaccination-status/:patient_id`）；
 * 前端只做徽标、排序与「生成提醒」动作。提醒只对**逾期**项写通报台账并调用
 * platformd 共享 message，临期项只在结果里提示护士（临期不是异常，不制造虚假告警）。
 */

interface RecordForm {
  vaccine_code: string;
  vaccine_name: string;
  dose_no: number;
  administered_at: string;
  facility: string;
  batch_no: string;
  status: string;
  note: string;
}

const defaultForm: RecordForm = {
  vaccine_code: "",
  vaccine_name: "",
  dose_no: 1,
  administered_at: todayLocal(),
  facility: "",
  batch_no: "",
  status: "已接种",
  note: "",
};

const RECORD_STATUSES = ["已接种", "暂缓", "拒种"] as const;

type ScheduleRow = ChildVaccinationScheduleRow & { row_key: string };

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function statusBadge(status: ChildVaccinationScheduleRow["status"]) {
  return <Badge variant={VACCINATION_STATUS_VARIANTS[status]}>{status}</Badge>;
}

export default function ChildVaccinationPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [report, setReport] = useState<ChildVaccinationStatusReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [onlyPending, setOnlyPending] = useState(false);

  const [recordOpen, setRecordOpen] = useState(false);
  const [form, setForm] = useState<RecordForm>(defaultForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

  const [reminding, setReminding] = useState(false);
  const [remindResult, setRemindResult] = useState<ChildVaccinationRemindResult | null>(null);

  const selected = useMemo(() => children.find((child) => child.id === patientId) ?? null, [children, patientId]);

  useEffect(() => {
    let active = true;
    listPatients({ person_type: "儿童", status: "ACTIVE", limit: 200 })
      .then((response) => {
        if (!active) return;
        setChildren(response.records);
        const fromUrl = typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("patient");
        const initial = fromUrl && response.records.some((child) => child.id === fromUrl) ? fromUrl : response.records[0]?.id ?? "";
        setPatientId(initial);
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, "无法加载儿童列表"));
      });
    return () => {
      active = false;
    };
  }, []);

  const load = useCallback(async () => {
    if (!patientId) {
      setReport(null);
      return;
    }
    setLoading(true);
    try {
      const response = await getChildVaccinationStatus(patientId);
      setReport(response);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载接种排程"));
    } finally {
      setLoading(false);
    }
  }, [patientId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitRecord() {
    if (!patientId) return;
    setSaving(true);
    setFormError("");
    try {
      await createChildVaccination({
        patient_id: patientId,
        vaccine_code: form.vaccine_code,
        dose_no: form.dose_no,
        administered_at: form.status === "已接种" ? form.administered_at : null,
        facility: form.facility || null,
        batch_no: form.batch_no || null,
        status: form.status,
        note: form.note || null,
      });
      setRecordOpen(false);
      setForm(defaultForm);
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "登记接种失败"));
    } finally {
      setSaving(false);
    }
  }

  async function remind() {
    if (!patientId) return;
    setReminding(true);
    setError("");
    try {
      const result = await remindChildVaccinations(patientId, { channel: "站内" });
      setRemindResult(result);
      await load();
    } catch (cause) {
      setError(errorMessage(cause, "生成提醒失败"));
    } finally {
      setReminding(false);
    }
  }

  const rows = report?.records ?? [];
  const visible = onlyPending ? rows.filter((row) => row.status !== "已接种") : sortByStatus(rows);
  const groups = groupScheduleByVaccine(visible);

  const columns: Column<ScheduleRow>[] = [
    { key: "vaccine_name", header: "疫苗", render: (row) => row.vaccine_name },
    { key: "dose_no", header: "剂次", render: (row) => `第 ${row.dose_no} 剂` },
    { key: "recommended_month", header: "推荐月龄", render: (row) => `${row.recommended_month} 月` },
    { key: "due_date", header: "应种日期", render: (row) => formatDate(row.due_date) },
    { key: "status", header: "状态", render: (row) => statusBadge(row.status) },
    { key: "administered_at", header: "接种日期", render: (row) => formatDate(row.administered_at) },
    { key: "facility", header: "接种机构", render: (row) => row.facility ?? "-" },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          variant="ghost"
          onClick={() => {
            setForm({
              ...defaultForm,
              vaccine_code: row.vaccine_code,
              vaccine_name: row.vaccine_name,
              dose_no: row.dose_no,
            });
            setFormError("");
            setRecordOpen(true);
          }}
        >
          登记接种
        </Button>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`预防接种（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="vaccination-patient">
              选择{entity.person}
            </label>
            <select
              id="vaccination-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => setPatientId(event.target.value)}
            >
              <option value="">请选择</option>
              {children.map((child) => (
                <option key={child.id} value={child.id}>
                  {child.name}（{formatAge(child.birth_date)}）
                </option>
              ))}
            </select>
          </div>
          {selected && (
            <span className="text-sm text-fg-muted">
              出生日期 {formatDate(selected.birth_date)} · 当前 {formatAge(selected.birth_date)}
            </span>
          )}
          <div className="ml-auto flex items-center gap-2">
            <label className="flex items-center gap-2 text-sm text-fg-muted">
              <input type="checkbox" checked={onlyPending} onChange={(event) => setOnlyPending(event.target.checked)} />
              只看未完成
            </label>
            <Button variant="ghost" onClick={() => void load()} disabled={!patientId || loading}>
              刷新
            </Button>
            <Button onClick={() => void remind()} loading={reminding} disabled={!report || !canRemind(report.meta)}>
              生成逾期提醒
            </Button>
          </div>
        </div>
        {report && (
          <p className="mt-3 text-sm text-fg-muted">
            {summarizeVaccinationMeta(report.meta)}
            {report.meta.overdue === 0 && report.meta.due_soon > 0 && "（临期不生成台账，只提示预约）"}
          </p>
        )}
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card title="免疫规划排程（0–5 岁）">
        {loading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : groups.length === 0 ? (
          <EmptyState icon="💉" title="暂无排程" description="选择儿童后显示国家免疫规划疫苗排程与状态" />
        ) : (
          <div className="flex flex-col gap-5">
            {groups.map((group) => (
              <div key={group.vaccine_code} className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold text-fg-emphasis">{group.vaccine_name}</span>
                  <span className="text-xs text-fg-dimmed">{group.vaccine_code}</span>
                  {group.hasPending ? <Badge variant="warning">待完成</Badge> : <Badge variant="success">已收口</Badge>}
                </div>
                <Table
                  columns={columns}
                  data={group.rows.map((row) => ({ ...row, row_key: `${row.vaccine_code}-${row.dose_no}` }))}
                  keyField="row_key"
                />
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal open={recordOpen} onClose={() => setRecordOpen(false)} title="登记接种记录" width="640px">
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg-muted">
            {form.vaccine_name} 第 {form.dose_no} 剂
          </p>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="vaccination-status">
                记录状态
              </label>
              <select
                id="vaccination-status"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={form.status}
                onChange={(event) => setForm({ ...form, status: event.target.value })}
              >
                {RECORD_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            {form.status === "已接种" && (
              <Input
                label="接种日期"
                type="date"
                value={form.administered_at}
                max={todayLocal()}
                onChange={(event) => setForm({ ...form, administered_at: event.target.value })}
              />
            )}
            <Input
              label="接种机构"
              value={form.facility}
              onChange={(event) => setForm({ ...form, facility: event.target.value })}
            />
            <Input
              label="疫苗批号"
              value={form.batch_no}
              onChange={(event) => setForm({ ...form, batch_no: event.target.value })}
            />
          </div>
          <Input label="备注" value={form.note} onChange={(event) => setForm({ ...form, note: event.target.value })} />
          <p className="text-xs text-fg-dimmed">{statusHint(form.status)}</p>
          {formError && <p className="text-sm text-danger">{formError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRecordOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submitRecord} loading={saving}>
              保存
            </Button>
          </div>
        </div>
      </Modal>

      <Modal open={remindResult !== null} onClose={() => setRemindResult(null)} title="提醒结果" width="720px">
        {remindResult && (
          <div className="flex flex-col gap-4">
            <div>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">
                逾期项通报台账（{remindResult.meta.notified}）
              </h4>
              {remindResult.notified.length === 0 ? (
                <p className="text-sm text-fg-muted">无逾期项，未生成台账。</p>
              ) : (
                <div className="flex flex-col gap-2">
                  {remindResult.notified.map((item) => (
                    <div key={item.id} className="rounded-md border border-border p-3 text-sm">
                      <div className="flex items-center gap-2">
                        <Badge variant={item.status === "已发送" ? "success" : item.status === "发送失败" ? "danger" : "warning"}>
                          {item.status}
                        </Badge>
                        <span className="text-fg-muted">{item.alert_type}</span>
                        {item.message_id && <span className="text-xs text-fg-dimmed">message {item.message_id}</span>}
                      </div>
                      <p className="mt-1 text-fg-muted">{item.content}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">
                临期项（{remindResult.meta.due_soon}，仅提示不通报）
              </h4>
              {remindResult.due_soon.length === 0 ? (
                <p className="text-sm text-fg-muted">无临期项。</p>
              ) : (
                <ul className="list-inside list-disc text-sm text-fg-muted">
                  {remindResult.due_soon.map((row) => (
                    <li key={`${row.vaccine_code}-${row.dose_no}`}>
                      {row.vaccine_name} 第 {row.dose_no} 剂，应种日期 {formatDate(row.due_date)}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <p className="text-xs text-fg-dimmed">
              渠道未接入时状态为「待接入」，不表示已送达；短信/微信接入前请以电话或面谈补充告知。
            </p>
            <div className="flex justify-end">
              <Button variant="ghost" onClick={() => setRemindResult(null)}>
                关闭
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
