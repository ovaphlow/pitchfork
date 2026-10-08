import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createChildGrowthMeasurement,
  deleteChildGrowthMeasurement,
  getChildGrowthSeries,
  listChildGrowthMeasurements,
  listPatients,
  type ChildGrowthMeasurement,
  type ChildGrowthSeries,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, formatDateTime, nowLocalInput, toOffsetDateTime } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import {
  METRIC_LABELS,
  METRIC_UNITS,
  REFERENCE_PERCENTILES,
  ageMonthsBetween,
  ageMonthsOfDays,
  describePercentile,
  evaluateGrowthSnapshot,
  growthCurve,
  isGrowthOutlier,
  sexFromGender,
  snapshotOf,
  type GrowthMetric,
  type GrowthSex,
} from "../lib/growth";
import { useDomain } from "../lib/useDomain";

/**
 * 成长曲线页（052 P2）。
 *
 * 一次测量会话一行（身高/体重/头围可空），体温同一次提交写回 `vital_sign_records`
 * （052 裁定 7）。百分位评价与曲线为前端纯函数（`lib/growth.ts` + WHO LMS 常量），
 * 落库时把评价快照随测量一起提交（`percentile_snapshot`），后端只存不算（052 D3）。
 */

const PAGE_SIZE = 20;

interface MeasureForm {
  measured_at: string;
  height: string;
  weight: string;
  head: string;
  temperature: string;
  note: string;
}

const defaultForm: MeasureForm = {
  measured_at: nowLocalInput(),
  height: "",
  weight: "",
  head: "",
  temperature: "",
  note: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function toNumber(value: string): number | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function percentileBadge(metric: GrowthMetric, snapshot: Record<string, { p?: number; z?: number }> | null) {
  const evaluated = snapshotOf(snapshot, metric);
  if (evaluated === null) return <span className="text-fg-dimmed">-</span>;
  const variant = isGrowthOutlier(evaluated) ? "danger" : evaluated.p < 15 || evaluated.p > 85 ? "warning" : "success";
  return <Badge variant={variant}>{describePercentile(metric, evaluated)}</Badge>;
}

/**
 * 参考带 + 实测点（内联 SVG，无第三方图表依赖）。
 *
 * 五条参考线固定 P3/P15/P50/P85/P97；纵轴范围由该窗口内的 P3/P97 决定，
 * 因此不同指标的曲线各自成比例，不互相挤压。
 */
function GrowthChart({
  sex,
  metric,
  points,
}: {
  sex: GrowthSex;
  metric: GrowthMetric;
  points: { age_days: number; value: number; measured_at: string }[];
}) {
  const width = 720;
  const height = 260;
  const padding = { top: 16, right: 16, bottom: 28, left: 44 };

  const curve = useMemo(() => growthCurve(sex, metric, 0, 60, 1), [sex, metric]);
  const bands = useMemo(() => {
    const values = curve.flatMap((point) => REFERENCE_PERCENTILES.map(({ p }) => point.values[String(p)] ?? 0));
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min || 1;
    return { min: min - span * 0.05, max: max + span * 0.05 };
  }, [curve]);

  const x = (month: number) => padding.left + (month / 60) * (width - padding.left - padding.right);
  const y = (value: number) =>
    padding.top + (1 - (value - bands.min) / (bands.max - bands.min)) * (height - padding.top - padding.bottom);

  const colors: Record<string, string> = {
    "3": "#dc2626",
    "15": "#f59e0b",
    "50": "#2563eb",
    "85": "#f59e0b",
    "97": "#dc2626",
  };

  return (
    <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full min-w-[560px]" role="img" aria-label={`${METRIC_LABELS[metric]}成长曲线`}>
        {[0, 12, 24, 36, 48, 60].map((month) => (
          <g key={month}>
            <line
              x1={x(month)}
              y1={padding.top}
              x2={x(month)}
              y2={height - padding.bottom}
              stroke="currentColor"
              className="text-border"
              strokeWidth={0.5}
            />
            <text x={x(month)} y={height - 8} textAnchor="middle" className="fill-current text-fg-dimmed" fontSize={10}>
              {month}月
            </text>
          </g>
        ))}
        <text x={padding.left - 6} y={y(bands.max)} textAnchor="end" className="fill-current text-fg-dimmed" fontSize={10}>
          {bands.max.toFixed(1)}
        </text>
        <text x={padding.left - 6} y={y(bands.min)} textAnchor="end" className="fill-current text-fg-dimmed" fontSize={10}>
          {bands.min.toFixed(1)}
        </text>

        {REFERENCE_PERCENTILES.map(({ p }) => (
          <polyline
            key={p}
            fill="none"
            stroke={colors[String(p)] ?? "#94a3b8"}
            strokeWidth={p === 50 ? 1.6 : 1}
            strokeDasharray={p === 50 ? undefined : "4 3"}
            points={curve.map((point) => `${x(point.month)},${y(point.values[String(p)] ?? 0)}`).join(" ")}
          />
        ))}

        {points.map((point) => (
          <g key={`${point.measured_at}-${point.value}`}>
            <circle cx={x(Math.min(60, ageMonthsOfDays(point.age_days)))} cy={y(point.value)} r={3.5} fill="#0f172a" />
            <title>
              {formatDateTime(point.measured_at)} · {point.value} {METRIC_UNITS[metric]}
            </title>
          </g>
        ))}
      </svg>
      <div className="mt-2 flex flex-wrap gap-3 text-xs text-fg-muted">
        {REFERENCE_PERCENTILES.map(({ p }) => (
          <span key={p} className="inline-flex items-center gap-1">
            <span className="inline-block h-0.5 w-4" style={{ background: colors[String(p)] }} />
            P{p}
          </span>
        ))}
        <span>· 实测点（黑点）</span>
      </div>
    </div>
  );
}

export default function ChildGrowthPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [metric, setMetric] = useState<GrowthMetric>("height");
  const [records, setRecords] = useState<ChildGrowthMeasurement[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [series, setSeries] = useState<ChildGrowthSeries | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<MeasureForm>(defaultForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

  const selected = useMemo(() => children.find((child) => child.id === patientId) ?? null, [children, patientId]);
  const sex = sexFromGender(selected?.gender);

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
      setRecords([]);
      setTotal(0);
      setSeries(null);
      return;
    }
    setLoading(true);
    try {
      const [list, growth] = await Promise.all([
        listChildGrowthMeasurements({ patient_id: patientId, limit: PAGE_SIZE, offset: (page - 1) * PAGE_SIZE }),
        getChildGrowthSeries(patientId),
      ]);
      setRecords(list.records);
      setTotal(list.meta.total);
      setSeries(growth);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载成长测量"));
    } finally {
      setLoading(false);
    }
  }, [patientId, page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitCreate() {
    if (!patientId) return;
    setSaving(true);
    setFormError("");
    try {
      const measuredAt = toOffsetDateTime(form.measured_at);
      const measuredDate = form.measured_at.slice(0, 10);
      const ageMonths = ageMonthsBetween(selected?.birth_date, measuredDate) ?? 0;
      const height = toNumber(form.height);
      const weight = toNumber(form.weight);
      const head = toNumber(form.head);
      const temperature = toNumber(form.temperature);
      const snapshot = evaluateGrowthSnapshot(sex, ageMonths, { height, weight, head });
      await createChildGrowthMeasurement({
        patient_id: patientId,
        measured_at: measuredAt,
        height_cm: height,
        weight_kg: weight,
        head_circumference_cm: head,
        temperature,
        percentile_snapshot: Object.keys(snapshot).length > 0 ? snapshot : undefined,
        note: form.note || undefined,
      });
      setCreateOpen(false);
      setForm({ ...defaultForm, measured_at: nowLocalInput() });
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "保存测量失败"));
    } finally {
      setSaving(false);
    }
  }

  async function removeRecord(id: string) {
    try {
      await deleteChildGrowthMeasurement(id);
      await load();
    } catch (cause) {
      setError(errorMessage(cause, "作废测量失败"));
    }
  }

  const columns: Column<ChildGrowthMeasurement>[] = [
    { key: "measured_at", header: "测量时间", render: (row) => formatDateTime(row.measured_at) },
    { key: "age_days", header: "天龄", render: (row) => (row.age_days === null ? "-" : `${row.age_days} 天`) },
    { key: "height_cm", header: "身高 cm", render: (row) => row.height_cm ?? "-" },
    { key: "weight_kg", header: "体重 kg", render: (row) => row.weight_kg ?? "-" },
    { key: "head_circumference_cm", header: "头围 cm", render: (row) => row.head_circumference_cm ?? "-" },
    { key: "height_p", header: "身高评价", render: (row) => percentileBadge("height", row.percentile_snapshot) },
    { key: "weight_p", header: "体重评价", render: (row) => percentileBadge("weight", row.percentile_snapshot) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="ghost" onClick={() => void removeRecord(row.id)}>
          作废
        </Button>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const points = series?.series?.[metric] ?? [];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`成长测量与曲线（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="growth-patient">
              选择{entity.person}
            </label>
            <select
              id="growth-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setPage(1);
              }}
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
              出生日期 {formatDate(selected.birth_date)} · 性别 {selected.gender || "未知"} · 当前 {formatAge(selected.birth_date)}
            </span>
          )}
          <div className="ml-auto">
            <Button onClick={() => { setForm({ ...defaultForm, measured_at: nowLocalInput() }); setFormError(""); setCreateOpen(true); }} disabled={!patientId}>
              录入测量
            </Button>
          </div>
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card
        title={`成长曲线（WHO 0–5 岁参考带）`}
        actions={
          <div className="flex items-center gap-2">
            {(Object.keys(METRIC_LABELS) as GrowthMetric[]).map((key) => (
              <Button key={key} variant={metric === key ? "primary" : "ghost"} onClick={() => setMetric(key)}>
                {METRIC_LABELS[key]}
              </Button>
            ))}
          </div>
        }
      >
        {sex === null ? (
          <EmptyState
            icon="⚠️"
            title="无法评价成长曲线"
            description={`该${entity.person}的性别为「${selected?.gender || "未知"}」，WHO 参考表只有男女二值，请先完善档案性别`}
          />
        ) : points.length === 0 ? (
          <EmptyState icon="📈" title="暂无测量点" description="录入第一条身高/体重/头围后显示实测点与参考带" />
        ) : (
          <GrowthChart sex={sex} metric={metric} points={points} />
        )}
        {series && (
          <p className="mt-3 text-sm text-fg-muted">
            当前天龄 {series.age_days} 天（约 {Math.floor(series.age_days / 30.4375)} 月）；
            实测点 {points.length} 个。曲线仅覆盖 0–5 岁（052 裁定 2），超龄只存原始测量。
          </p>
        )}
      </Card>

      <Card
        title={`测量记录（${total}）`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {page} / {totalPages}
            </span>
            <Button variant="ghost" onClick={() => setPage((value) => Math.min(totalPages, value + 1))} disabled={page >= totalPages}>
              下一页
            </Button>
          </div>
        }
      >
        {loading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : records.length === 0 ? (
          <EmptyState icon="📏" title="暂无测量记录" />
        ) : (
          <Table columns={columns} data={records} />
        )}
      </Card>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="录入成长测量" width="640px">
        <div className="flex flex-col gap-3">
          <Input
            label="测量时间"
            type="datetime-local"
            value={form.measured_at}
            max={nowLocalInput()}
            onChange={(event) => setForm({ ...form, measured_at: event.target.value })}
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="身高/身长（cm）"
              type="number"
              step="0.1"
              value={form.height}
              onChange={(event) => setForm({ ...form, height: event.target.value })}
            />
            <Input
              label="体重（kg）"
              type="number"
              step="0.01"
              value={form.weight}
              onChange={(event) => setForm({ ...form, weight: event.target.value })}
            />
            <Input
              label="头围（cm）"
              type="number"
              step="0.1"
              value={form.head}
              onChange={(event) => setForm({ ...form, head: event.target.value })}
            />
            <Input
              label="体温（℃，可选）"
              type="number"
              step="0.1"
              value={form.temperature}
              onChange={(event) => setForm({ ...form, temperature: event.target.value })}
            />
          </div>
          <p className="text-xs text-fg-dimmed">
            四项至少填一项；体温会同时写入体征记录（健康监测可见），不必分两处录。
          </p>
          <Input label="备注" value={form.note} onChange={(event) => setForm({ ...form, note: event.target.value })} />
          {formError && <p className="text-sm text-danger">{formError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submitCreate} loading={saving}>
              保存
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
