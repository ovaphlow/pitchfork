import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createVitalSigns,
  deleteVitalSign,
  getVitalSignSnapshot,
  getVitalSignTrend,
  listPatients,
  listVitalSigns,
  updateVitalSign,
  type Patient,
  type VitalSignRecord,
  type VitalSignType,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

/** 体征类型展示映射（后端英文枚举 → 中文） */
const TYPE_LABELS: Record<VitalSignType, string> = {
  TEMPERATURE: "体温",
  PULSE: "脉搏",
  RESPIRATION: "呼吸",
  SYSTOLIC_BP: "收缩压",
  DIASTOLIC_BP: "舒张压",
  SPO2: "血氧饱和度",
  BLOOD_GLUCOSE: "血糖",
  WEIGHT: "体重",
};

const TYPE_UNITS: Record<VitalSignType, string> = {
  TEMPERATURE: "℃",
  PULSE: "次/分",
  RESPIRATION: "次/分",
  SYSTOLIC_BP: "mmHg",
  DIASTOLIC_BP: "mmHg",
  SPO2: "%",
  BLOOD_GLUCOSE: "mmol/L",
  WEIGHT: "kg",
};

/** 表单输入顺序与占位提示 */
const FORM_FIELDS: { type: VitalSignType; placeholder: string; step?: string }[] = [
  { type: "TEMPERATURE", placeholder: "36.0–37.3", step: "0.1" },
  { type: "PULSE", placeholder: "60–100", step: "1" },
  { type: "RESPIRATION", placeholder: "12–20", step: "1" },
  { type: "SYSTOLIC_BP", placeholder: "90–140", step: "1" },
  { type: "DIASTOLIC_BP", placeholder: "60–90", step: "1" },
  { type: "SPO2", placeholder: "≥95（0–100）", step: "1" },
  { type: "BLOOD_GLUCOSE", placeholder: "3.9–6.1", step: "0.1" },
  { type: "WEIGHT", placeholder: "kg，不判异常", step: "0.1" },
];

/** 参考范围（与服务端内置常量一致，仅用于趋势图参考带展示） */
const REFERENCE_RANGES: Partial<Record<VitalSignType, { min: number; max: number }>> = {
  TEMPERATURE: { min: 36.0, max: 37.3 },
  PULSE: { min: 60, max: 100 },
  RESPIRATION: { min: 12, max: 20 },
  SYSTOLIC_BP: { min: 90, max: 140 },
  DIASTOLIC_BP: { min: 60, max: 90 },
  SPO2: { min: 95, max: 100 },
  BLOOD_GLUCOSE: { min: 3.9, max: 6.1 },
};

const TREND_RANGES = [
  { key: "7d", label: "近 7 天" },
  { key: "30d", label: "近 30 天" },
  { key: "all", label: "全部" },
] as const;

type TrendRangeKey = (typeof TREND_RANGES)[number]["key"];

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

function todayLocal(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function nowLocalInput(): string {
  const d = new Date();
  return `${todayLocal()}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local → OffsetDateTime 字符串（Asia/Shanghai） */
function toOffsetDateTime(localInput: string): string {
  if (!localInput) return "";
  const withSeconds = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(localInput) ? `${localInput}:00` : localInput;
  return `${withSeconds}+08:00`;
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.slice(0, 16).replace("T", " ") : "-";
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatValue(value: number | null | undefined): string {
  return value === null || value === undefined ? "-" : String(value);
}

function abnormalBadge(abnormal: boolean) {
  return abnormal ? <Badge variant="danger">异常</Badge> : <Badge variant="success">正常</Badge>;
}

/** 简单 SVG 折线图：无重依赖，横轴按测量顺序均分，纵轴按数据/参考范围缩放 */
function TrendChart({ records, type }: { records: VitalSignRecord[]; type: VitalSignType }) {
  const { width, height, padLeft, padRight, padTop, padBottom } = {
    width: 640,
    height: 240,
    padLeft: 44,
    padRight: 16,
    padTop: 14,
    padBottom: 30,
  };
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const range = REFERENCE_RANGES[type];
  const values = records.map((r) => r.value);

  const { min, max } = useMemo(() => {
    if (values.length === 0) return { min: 0, max: 1 };
    const rawMin = Math.min(...values);
    const rawMax = Math.max(...values);
    let lo = range ? Math.min(rawMin, range.min) : rawMin;
    let hi = range ? Math.max(rawMax, range.max) : rawMax;
    if (lo === hi) {
      lo -= 1;
      hi += 1;
    }
    const pad = (hi - lo) * 0.12;
    return { min: lo - pad, max: hi + pad };
  }, [values, range]);

  const x = (index: number) => padLeft + (values.length === 1 ? plotW / 2 : (index / (values.length - 1)) * plotW);
  const y = (value: number) => padTop + ((max - value) / (max - min)) * plotH;

  const linePoints = records.map((r, i) => `${x(i)},${y(r.value)}`).join(" ");
  const gridLines = [0, 1, 2, 3].map((i) => {
    const ratio = i / 3;
    const value = max - ratio * (max - min);
    const yy = padTop + ratio * plotH;
    return { yy, value };
  });

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-auto" role="img" aria-label={`${TYPE_LABELS[type]}趋势图`}>
      {gridLines.map((line) => (
        <g key={line.yy}>
          <line x1={padLeft} y1={line.yy} x2={width - padRight} y2={line.yy} stroke="currentColor" className="text-border" strokeWidth="1" strokeDasharray="4 4" />
          <text x={padLeft - 6} y={line.yy + 4} textAnchor="end" fontSize="10" className="fill-fg-dimmed">{line.value.toFixed(1)}</text>
        </g>
      ))}
      {range && (
        <rect
          x={padLeft}
          y={y(range.max)}
          width={plotW}
          height={y(range.min) - y(range.max)}
          fill="currentColor"
          className="text-accent/10"
        />
      )}
      {range && (
        <text x={width - padRight} y={y(range.max) - 4} textAnchor="end" fontSize="10" className="fill-fg-dimmed">
          参考上限 {range.max}
        </text>
      )}
      {records.length > 1 && (
        <polyline points={linePoints} fill="none" stroke="currentColor" className="text-accent" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      )}
      {records.map((r, i) => (
        <g key={r.id}>
          <circle cx={x(i)} cy={y(r.value)} r="3.5" className={r.abnormal ? "fill-danger" : "fill-accent"}>
            <title>{`${formatDateTime(r.measured_at)} — ${formatValue(r.value)}${r.unit}${r.abnormal ? "（异常）" : ""}`}</title>
          </circle>
        </g>
      ))}
      {records.length > 0 && (
        <>
          <text x={padLeft} y={height - 8} fontSize="10" className="fill-fg-dimmed">{formatDate(records[0].measured_at)}</text>
          <text x={width - padRight} y={height - 8} textAnchor="end" fontSize="10" className="fill-fg-dimmed">{formatDate(records[records.length - 1].measured_at)}</text>
        </>
      )}
    </svg>
  );
}

export default function HealthMonitorPage() {
  // ——— 异常告警页跳转参数：?patient=<id>&type=<TYPE> 自动选中老人与趋势类型 ———
  const initialPatientId = useMemo(
    () => (typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("patient")),
    [],
  );
  const initialType = useMemo(
    () => (typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("type")),
    [],
  );

  // ——— 老人选择 ———
  const [patientQuery, setPatientQuery] = useState("");
  const [patientOptions, setPatientOptions] = useState<Patient[]>([]);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [patientError, setPatientError] = useState("");

  // ——— 快照 / 列表 ———
  const [snapshot, setSnapshot] = useState<VitalSignRecord[]>([]);
  const [records, setRecords] = useState<VitalSignRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  // ——— 录入表单 ———
  const [form, setForm] = useState<Record<string, string>>({});
  const [measuredAt, setMeasuredAt] = useState(nowLocalInput());
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

  // ——— 修正 / 删除 ———
  const [editing, setEditing] = useState<VitalSignRecord | null>(null);
  const [editForm, setEditForm] = useState({ value: "", measured_at: "", note: "" });
  const [editError, setEditError] = useState("");
  const [savingEdit, setSavingEdit] = useState(false);
  const [deleting, setDeleting] = useState<VitalSignRecord | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [deletingNow, setDeletingNow] = useState(false);

  // ——— 趋势 ———
  const [trendType, setTrendType] = useState<VitalSignType>("TEMPERATURE");
  const [trendRange, setTrendRange] = useState<TrendRangeKey>("30d");
  const [trendPoints, setTrendPoints] = useState<VitalSignRecord[]>([]);
  const [trendLoading, setTrendLoading] = useState(false);
  const [trendError, setTrendError] = useState("");

  // ——— 老人搜索 ———
  const searchPatients = useCallback(async (query: string) => {
    setPatientError("");
    try {
      const response = await listPatients({ name: query.trim() || undefined, status: "ACTIVE", limit: 20 });
      setPatientOptions(response.records);
    } catch (error) {
      setPatientError(errorMessage(error, "无法加载老人列表"));
      setPatientOptions([]);
    }
  }, []);

  useEffect(() => {
    searchPatients("");
  }, [searchPatients]);

  // 从异常告警页跳转时，老人列表加载后自动选中目标老人与体征类型（首屏 20 条未命中则扩大查找）
  useEffect(() => {
    if (selectedPatient || !initialPatientId || patientOptions.length === 0) return;
    const match = patientOptions.find((patient) => patient.id === initialPatientId);
    if (match) {
      if (initialType && initialType in TYPE_LABELS) setTrendType(initialType as VitalSignType);
      selectPatient(match);
      return;
    }
    void listPatients({ status: "ACTIVE", limit: 100 })
      .then((response) => {
        const found = response.records.find((patient) => patient.id === initialPatientId);
        if (found) {
          if (initialType && initialType in TYPE_LABELS) setTrendType(initialType as VitalSignType);
          selectPatient(found);
        }
      })
      .catch(() => undefined);
  }, [selectedPatient, patientOptions, initialPatientId, initialType]);

  const selectPatient = (patient: Patient) => {
    setSelectedPatient(patient);
    setPatientQuery(patient.name);
    setPatientOptions([]);
    setPage(1);
  };

  const loadSnapshot = useCallback(async (patientId: string) => {
    try {
      const response = await getVitalSignSnapshot(patientId);
      setSnapshot(response.records);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载体征快照"));
    }
  }, []);

  const loadRecords = useCallback(async (patientId: string, targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listVitalSigns({
        patient_id: patientId,
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
      setPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载体征记录"));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadTrend = useCallback(async (patientId: string, type: VitalSignType, range: TrendRangeKey) => {
    setTrendLoading(true);
    setTrendError("");
    try {
      const now = new Date();
      const params: { date_from?: string; date_to?: string } = {};
      if (range === "7d") {
        const from = new Date(now.getTime() - 7 * 86400000);
        params.date_from = `${from.getFullYear()}-${pad(from.getMonth() + 1)}-${pad(from.getDate())}T00:00:00+08:00`;
      } else if (range === "30d") {
        const from = new Date(now.getTime() - 30 * 86400000);
        params.date_from = `${from.getFullYear()}-${pad(from.getMonth() + 1)}-${pad(from.getDate())}T00:00:00+08:00`;
      }
      const response = await getVitalSignTrend(patientId, type, params);
      setTrendPoints(response.records);
    } catch (error) {
      setTrendError(errorMessage(error, "无法加载趋势数据"));
      setTrendPoints([]);
    } finally {
      setTrendLoading(false);
    }
  }, []);

  const refreshAll = useCallback(async (patientId: string) => {
    await Promise.all([loadSnapshot(patientId), loadRecords(patientId, page), loadTrend(patientId, trendType, trendRange)]);
  }, [loadSnapshot, loadRecords, loadTrend, page, trendType, trendRange]);

  useEffect(() => {
    if (selectedPatient) {
      void refreshAll(selectedPatient.id);
    }
  }, [selectedPatient, refreshAll]);

  const snapshotByType = useMemo(() => {
    const map = new Map<VitalSignType, VitalSignRecord>();
    snapshot.forEach((record) => {
      if (!map.has(record.type)) map.set(record.type, record);
    });
    return map;
  }, [snapshot]);

  // ——— 录入 ———
  const submitForm = async () => {
    if (!selectedPatient) return;
    setFormError("");
    const inputs: { type: VitalSignType; value: number }[] = [];
    for (const field of FORM_FIELDS) {
      const raw = form[field.type]?.trim();
      if (!raw) continue;
      const value = Number(raw);
      if (!Number.isFinite(value) || value <= 0) {
        setFormError(`${TYPE_LABELS[field.type]}必须是大于 0 的数字`);
        return;
      }
      if (field.type === "SPO2" && (value < 0 || value > 100)) {
        setFormError("血氧饱和度必须在 0–100 之间");
        return;
      }
      inputs.push({ type: field.type, value });
    }
    if (inputs.length === 0) {
      setFormError("请至少填写一项体征数值");
      return;
    }
    setSaving(true);
    try {
      await createVitalSigns(
        inputs.map((input) => ({
          patient_id: selectedPatient.id,
          type: input.type,
          value: input.value,
          measured_at: toOffsetDateTime(measuredAt),
          note: note.trim() || undefined,
        })),
      );
      setForm({});
      setMeasuredAt(nowLocalInput());
      setNote("");
      await refreshAll(selectedPatient.id);
    } catch (error) {
      setFormError(errorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  // ——— 修正 ———
  const openEdit = (record: VitalSignRecord) => {
    setEditing(record);
    setEditForm({
      value: String(record.value),
      measured_at: record.measured_at.slice(0, 16),
      note: record.note ?? "",
    });
    setEditError("");
  };

  const submitEdit = async () => {
    if (!editing) return;
    setEditError("");
    const value = Number(editForm.value);
    if (!Number.isFinite(value) || value <= 0) {
      setEditError("数值必须是大于 0 的数字");
      return;
    }
    setSavingEdit(true);
    try {
      await updateVitalSign(editing.id, {
        value,
        measured_at: toOffsetDateTime(editForm.measured_at),
        note: editForm.note.trim() || null,
      });
      setEditing(null);
      if (selectedPatient) await refreshAll(selectedPatient.id);
    } catch (error) {
      setEditError(errorMessage(error, "修正失败"));
    } finally {
      setSavingEdit(false);
    }
  };

  // ——— 删除 ———
  const confirmDelete = async () => {
    if (!deleting || !selectedPatient) return;
    setDeleteError("");
    setDeletingNow(true);
    try {
      await deleteVitalSign(deleting.id);
      setDeleting(null);
      await refreshAll(selectedPatient.id);
    } catch (error) {
      setDeleteError(errorMessage(error, "删除失败"));
    } finally {
      setDeletingNow(false);
    }
  };

  // ——— 趋势 ———
  const switchTrend = (type: VitalSignType, range: TrendRangeKey) => {
    setTrendType(type);
    setTrendRange(range);
    if (selectedPatient) void loadTrend(selectedPatient.id, type, range);
  };

  const columns: Column<VitalSignRecord>[] = [
    {
      key: "measured_at",
      header: "测量时间",
      render: (row) => <span className="text-fg-emphasis">{formatDateTime(row.measured_at)}</span>,
    },
    {
      key: "type",
      header: "体征",
      render: (row) => (
        <span className={row.abnormal ? "text-danger font-medium" : ""}>
          {TYPE_LABELS[row.type] ?? row.type}
        </span>
      ),
    },
    {
      key: "value",
      header: "数值",
      render: (row) => (
        <span className={row.abnormal ? "text-danger font-semibold" : ""}>
          {formatValue(row.value)}
          <span className="text-fg-dimmed ml-1">{row.unit}</span>
        </span>
      ),
    },
    {
      key: "abnormal",
      header: "状态",
      render: (row) => abnormalBadge(row.abnormal),
    },
    {
      key: "recorded_by",
      header: "记录人",
      render: (row) => <span className="text-fg-muted">{row.recorded_by || "-"}</span>,
    },
    {
      key: "note",
      header: "备注",
      render: (row) => <span className="text-fg-muted max-w-[16rem] truncate block">{row.note || "-"}</span>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <span className="inline-flex gap-2">
          <Button variant="functional" size="sm" onClick={() => openEdit(row)}>修正</Button>
          <Button variant="ghost" size="sm" className="text-danger" onClick={() => setDeleting(row)}>删除</Button>
        </span>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-6">
      {/* 老人选择 */}
      <Card title="选择老人">
        <div className="relative">
          <Input
            placeholder="输入姓名搜索入住老人…"
            value={patientQuery}
            onChange={(event) => {
              setPatientQuery(event.target.value);
              void searchPatients(event.target.value);
            }}
          />
          {patientOptions.length > 0 && (
            <ul className="absolute z-10 mt-1 w-full max-h-60 overflow-auto border border-border rounded-lg bg-surface shadow-lg">
              {patientOptions.map((patient) => (
                <li key={patient.id}>
                  <button
                    type="button"
                    className="w-full text-left px-3 py-2 text-sm hover:bg-surface-alt flex items-center justify-between"
                    onClick={() => selectPatient(patient)}
                  >
                    <span>{patient.name}</span>
                    <span className="text-xs text-fg-dimmed">{patient.gender || "—"}{patient.birth_date ? ` · ${formatDate(patient.birth_date)}出生` : ""}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        {patientError && <p className="text-sm text-danger mt-2">{patientError}</p>}
        {selectedPatient && (
          <p className="text-sm text-fg-muted mt-3">
            当前老人：<span className="text-fg-emphasis font-medium">{selectedPatient.name}</span>
            {selectedPatient.birth_date ? `（${formatDate(selectedPatient.birth_date)}出生）` : ""}
          </p>
        )}
      </Card>

      {!selectedPatient ? (
        <Card>
          <EmptyState icon="❤️" title="请先选择老人" description="选择入住老人后即可查看体征快照、录入与趋势" />
        </Card>
      ) : (
        <>
          {/* 体征快照 */}
          <Card title="最新体征" actions={<span className="text-xs text-fg-dimmed">每种类型最近一次测量</span>}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {FORM_FIELDS.map((field) => {
                const record = snapshotByType.get(field.type);
                return (
                  <div
                    key={field.type}
                    className={`rounded-lg border p-3 ${record?.abnormal ? "border-danger/60 bg-danger/5" : "border-border bg-surface"}`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-fg-muted">{TYPE_LABELS[field.type]}</span>
                      {record && abnormalBadge(record.abnormal)}
                    </div>
                    {record ? (
                      <>
                        <div className={`text-xl font-semibold mt-1 ${record.abnormal ? "text-danger" : "text-fg-emphasis"}`}>
                          {formatValue(record.value)}
                          <span className="text-sm font-normal text-fg-dimmed ml-1">{record.unit}</span>
                        </div>
                        <div className="text-xs text-fg-dimmed mt-1">{formatDateTime(record.measured_at)}</div>
                      </>
                    ) : (
                      <div className="text-xl text-fg-dimmed mt-1">—</div>
                    )}
                  </div>
                );
              })}
            </div>
          </Card>

          {/* 体征录入 */}
          <Card
            title="体征录入"
            actions={<span className="text-xs text-fg-dimmed">血压请分别填写收缩压/舒张压，一次提交</span>}
          >
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {FORM_FIELDS.map((field) => (
                <label key={field.type} className="block">
                  <span className="text-xs text-fg-muted">
                    {TYPE_LABELS[field.type]}
                    <span className="text-fg-dimmed ml-1">({field.placeholder})</span>
                  </span>
                  <div className="relative mt-1">
                    <Input
                      type="number"
                      inputMode="decimal"
                      step={field.step}
                      placeholder={TYPE_UNITS[field.type]}
                      value={form[field.type] ?? ""}
                      onChange={(event) => setForm((prev) => ({ ...prev, [field.type]: event.target.value }))}
                    />
                    <span className="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-fg-dimmed pointer-events-none">
                      {TYPE_UNITS[field.type]}
                    </span>
                  </div>
                </label>
              ))}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mt-4">
              <label className="block">
                <span className="text-xs text-fg-muted">测量时间</span>
                <Input
                  type="datetime-local"
                  className="mt-1"
                  value={measuredAt}
                  onChange={(event) => setMeasuredAt(event.target.value)}
                />
              </label>
              <label className="block md:col-span-2">
                <span className="text-xs text-fg-muted">备注</span>
                <Input
                  className="mt-1"
                  placeholder="如：晨起空腹测量、服药后复测…"
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                />
              </label>
            </div>
            {formError && <p className="text-sm text-danger mt-3">{formError}</p>}
            <div className="mt-4 flex items-center gap-3">
              <Button onClick={() => void submitForm()} loading={saving}>保存记录</Button>
              {saving && <span className="text-xs text-fg-dimmed">正在提交…</span>}
            </div>
          </Card>

          {/* 趋势图 */}
          <Card
            title="趋势分析"
            actions={
              <span className="inline-flex items-center gap-2">
                <select
                  className="h-8 rounded-lg border border-border bg-surface px-2 text-sm"
                  value={trendType}
                  onChange={(event) => switchTrend(event.target.value as VitalSignType, trendRange)}
                >
                  {FORM_FIELDS.map((field) => (
                    <option key={field.type} value={field.type}>{TYPE_LABELS[field.type]}</option>
                  ))}
                </select>
                <select
                  className="h-8 rounded-lg border border-border bg-surface px-2 text-sm"
                  value={trendRange}
                  onChange={(event) => switchTrend(trendType, event.target.value as TrendRangeKey)}
                >
                  {TREND_RANGES.map((range) => (
                    <option key={range.key} value={range.key}>{range.label}</option>
                  ))}
                </select>
              </span>
            }
          >
            {trendLoading ? (
              <div className="flex items-center justify-center py-16 text-sm text-fg-dimmed">加载中…</div>
            ) : trendError ? (
              <p className="text-sm text-danger py-8 text-center">{trendError}</p>
            ) : trendPoints.length === 0 ? (
              <EmptyState icon="📈" title="暂无趋势数据" description={`该老人在所选时间段内没有${TYPE_LABELS[trendType]}记录`} />
            ) : (
              <>
                <TrendChart records={trendPoints} type={trendType} />
                <p className="text-xs text-fg-dimmed mt-2">
                  共 {trendPoints.length} 个测量点；阴影区域为参考范围，红点为异常值
                </p>
              </>
            )}
          </Card>

          {/* 记录列表 */}
          <Card
            title="记录列表"
            actions={<span className="text-xs text-fg-dimmed">共 {total} 条，按测量时间倒序</span>}
          >
            <Table
              columns={columns}
              data={records}
              loading={loading}
              emptyMessage="暂无体征记录"
            />
            {total > PAGE_SIZE && (
              <div className="flex items-center justify-between mt-4">
                <Button variant="functional" size="sm" disabled={page <= 1} onClick={() => void loadRecords(selectedPatient.id, page - 1)}>
                  上一页
                </Button>
                <span className="text-xs text-fg-dimmed">第 {page} / {totalPages} 页</span>
                <Button variant="functional" size="sm" disabled={page >= totalPages} onClick={() => void loadRecords(selectedPatient.id, page + 1)}>
                  下一页
                </Button>
              </div>
            )}
          </Card>
        </>
      )}

      {pageError && (
        <p className="text-sm text-danger rounded-lg border border-danger/40 bg-danger/5 px-3 py-2">{pageError}</p>
      )}

      {/* 修正弹窗 */}
      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing ? `修正${TYPE_LABELS[editing.type] ?? editing.type}记录` : ""}
      >
        {editing && (
          <div className="space-y-4">
            <div className="text-sm text-fg-muted">
              当前记录：{formatValue(editing.value)}{editing.unit} · {formatDateTime(editing.measured_at)}
              {editing.note ? ` · ${editing.note}` : ""}
            </div>
            <label className="block">
              <span className="text-xs text-fg-muted">数值（{editing.unit}）</span>
              <Input
                type="number"
                inputMode="decimal"
                step="0.1"
                className="mt-1"
                value={editForm.value}
                onChange={(event) => setEditForm((prev) => ({ ...prev, value: event.target.value }))}
              />
            </label>
            <label className="block">
              <span className="text-xs text-fg-muted">测量时间</span>
              <Input
                type="datetime-local"
                className="mt-1"
                value={editForm.measured_at}
                onChange={(event) => setEditForm((prev) => ({ ...prev, measured_at: event.target.value }))}
              />
            </label>
            <label className="block">
              <span className="text-xs text-fg-muted">备注</span>
              <Input
                className="mt-1"
                value={editForm.note}
                onChange={(event) => setEditForm((prev) => ({ ...prev, note: event.target.value }))}
              />
            </label>
            {editError && <p className="text-sm text-danger">{editError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setEditing(null)}>取消</Button>
              <Button onClick={() => void submitEdit()} loading={savingEdit}>保存修正</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 删除确认弹窗 */}
      <Modal open={deleting !== null} onClose={() => setDeleting(null)} title="删除体征记录">
        {deleting && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              确认删除 {formatDateTime(deleting.measured_at)} 的{TYPE_LABELS[deleting.type] ?? deleting.type}记录
              （{formatValue(deleting.value)}{deleting.unit}）？删除后记录不再展示，但数据保留可追溯。
            </p>
            {deleteError && <p className="text-sm text-danger">{deleteError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setDeleting(null)}>取消</Button>
              <Button variant="danger" onClick={() => void confirmDelete()} loading={deletingNow}>确认删除</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
