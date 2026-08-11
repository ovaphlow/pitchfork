import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  getAbnormalVitalSignSummary,
  listAbnormalVitalSigns,
  listPatients,
  referVitalSign,
  reviewVitalSign,
  type Patient,
  type VitalSignAbnormalSummary,
  type VitalSignRecord,
  type VitalSignReferResult,
  type VitalSignReviewResult,
  type VitalSignReviewStatus,
  type VitalSignType,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 20;

/** 体征类型展示映射（与健康监测页保持一致） */
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

const TYPE_OPTIONS = Object.entries(TYPE_LABELS) as [VitalSignType, string][];

/** 处理状态展示映射 */
const REVIEW_STATUS_LABELS: Record<VitalSignReviewStatus, string> = {
  待复核: "待复核",
  已确认: "已确认",
  已误报: "已误报",
  已转诊: "已转诊",
};

const REVIEW_STATUS_OPTIONS: { value: VitalSignReviewStatus; label: string }[] = [
  { value: "待复核", label: "待复核" },
  { value: "已确认", label: "已确认" },
  { value: "已误报", label: "已误报" },
  { value: "已转诊", label: "已转诊" },
];

type ReviewBadgeVariant = "default" | "success" | "warning" | "danger" | "info";

const REVIEW_STATUS_VARIANTS: Record<VitalSignReviewStatus, ReviewBadgeVariant> = {
  待复核: "warning",
  已确认: "info",
  已误报: "default",
  已转诊: "success",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
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

function reviewStatusBadge(status: VitalSignReviewStatus) {
  return <Badge variant={REVIEW_STATUS_VARIANTS[status] ?? "default"}>{REVIEW_STATUS_LABELS[status] ?? status}</Badge>;
}

/** 统计卡片：待复核 / 今日新增 / 已转诊 */
function StatCard({ label, value, tone, icon }: { label: string; value: number; tone: string; icon: string }) {
  return (
    <div className="rounded-lg border border-border bg-surface p-4">
      <div className="flex items-center justify-between">
        <span className="text-xs text-fg-muted">{icon} {label}</span>
        <span className={`text-2xl font-semibold ${tone}`}>{value}</span>
      </div>
    </div>
  );
}

export default function AbnormalAlertsPage() {
  // ——— 统计摘要 ———
  const [summary, setSummary] = useState<VitalSignAbnormalSummary | null>(null);

  // ——— 筛选 ———
  const [patientQuery, setPatientQuery] = useState("");
  const [patientOptions, setPatientOptions] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState<string | undefined>();
  const [typeFilter, setTypeFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [patientError, setPatientError] = useState("");

  // ——— 列表 ———
  const [records, setRecords] = useState<VitalSignRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  // ——— 复核弹窗 ———
  const [reviewing, setReviewing] = useState<VitalSignRecord | null>(null);
  const [reviewResult, setReviewResult] = useState<VitalSignReviewResult>("确认异常");
  const [reviewNote, setReviewNote] = useState("");
  const [reviewError, setReviewError] = useState("");
  const [savingReview, setSavingReview] = useState(false);
  /** 复核成功后的提示阶段（误报引导修正记录） */
  const [reviewDone, setReviewDone] = useState<VitalSignReviewResult | null>(null);

  // ——— 转诊弹窗 ———
  const [referring, setReferring] = useState<VitalSignRecord | null>(null);
  const [referResult, setReferResult] = useState<VitalSignReferResult | null>(null);
  const [referError, setReferError] = useState("");
  const [savingRefer, setSavingRefer] = useState(false);

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
    void searchPatients("");
  }, [searchPatients]);

  const selectPatient = (patient: Patient) => {
    setPatientId(patient.id);
    setPatientQuery(patient.name);
    setPatientOptions([]);
    setPage(1);
  };

  // ——— 数据加载 ———
  const loadSummary = useCallback(async () => {
    try {
      setSummary(await getAbnormalVitalSignSummary());
    } catch (error) {
      setPageError(errorMessage(error, "无法加载异常统计"));
    }
  }, []);

  const loadRecords = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listAbnormalVitalSigns({
        patient_id: patientId,
        type: (typeFilter || undefined) as VitalSignType | undefined,
        review_status: (statusFilter || undefined) as VitalSignReviewStatus | undefined,
        date_from: dateFrom ? `${dateFrom}:00+08:00` : undefined,
        date_to: dateTo ? `${dateTo}:00+08:00` : undefined,
        limit: PAGE_SIZE,
        offset: (targetPage - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
      setPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载异常记录"));
    } finally {
      setLoading(false);
    }
  }, [patientId, typeFilter, statusFilter, dateFrom, dateTo]);

  const refreshAll = useCallback(async () => {
    await Promise.all([loadSummary(), loadRecords(page)]);
  }, [loadSummary, loadRecords, page]);

  useEffect(() => {
    void refreshAll();
  }, [refreshAll]);

  const resetFilters = () => {
    setPatientId(undefined);
    setPatientQuery("");
    setTypeFilter("");
    setStatusFilter("");
    setDateFrom("");
    setDateTo("");
    setPage(1);
  };

  // ——— 复核 ———
  const openReview = (record: VitalSignRecord) => {
    setReviewing(record);
    setReviewResult("确认异常");
    setReviewNote("");
    setReviewError("");
    setReviewDone(null);
  };

  const submitReview = async () => {
    if (!reviewing) return;
    setReviewError("");
    setSavingReview(true);
    try {
      await reviewVitalSign(reviewing.id, { result: reviewResult, note: reviewNote });
      setReviewDone(reviewResult);
      await refreshAll();
    } catch (error) {
      setReviewError(errorMessage(error, "复核失败"));
    } finally {
      setSavingReview(false);
    }
  };

  // ——— 转诊 ———
  const openRefer = (record: VitalSignRecord) => {
    setReferring(record);
    setReferResult(null);
    setReferError("");
  };

  const submitRefer = async () => {
    if (!referring) return;
    setReferError("");
    setSavingRefer(true);
    try {
      const result = await referVitalSign(referring.id);
      setReferResult(result);
      await refreshAll();
    } catch (error) {
      setReferError(errorMessage(error, "转诊失败"));
    } finally {
      setSavingRefer(false);
    }
  };

  // ——— 表格 ———
  const columns: Column<VitalSignRecord>[] = [
    {
      key: "measured_at",
      header: "测量时间",
      render: (row) => <span className="text-fg-emphasis">{formatDateTime(row.measured_at)}</span>,
    },
    {
      key: "patient_name",
      header: "老人",
      render: (row) => <span className="font-medium">{row.patient_name || "-"}</span>,
    },
    {
      key: "type",
      header: "体征",
      render: (row) => <span className="text-danger font-medium">{TYPE_LABELS[row.type] ?? row.type}</span>,
    },
    {
      key: "value",
      header: "数值",
      render: (row) => (
        <span className="text-danger font-semibold">
          {formatValue(row.value)}
          <span className="text-fg-dimmed ml-1">{row.unit}</span>
        </span>
      ),
    },
    {
      key: "review_status",
      header: "处理状态",
      render: (row) => reviewStatusBadge(row.review_status),
    },
    {
      key: "reviewed_by",
      header: "复核信息",
      render: (row) =>
        row.reviewed_by ? (
          <span className="text-fg-muted text-xs">
            {row.reviewed_by}
            {row.reviewed_at ? ` · ${formatDateTime(row.reviewed_at)}` : ""}
            {row.review_result ? ` · ${row.review_result}` : ""}
          </span>
        ) : (
          <span className="text-fg-dimmed">-</span>
        ),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <span className="inline-flex gap-2">
          {(row.review_status === "待复核" || row.review_status === "已确认") && (
            <Button variant="functional" size="sm" onClick={() => openReview(row)}>复核</Button>
          )}
          {row.review_status === "已确认" && (
            <Button variant="primary" size="sm" onClick={() => openRefer(row)}>转诊</Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              window.location.href = `/dashboard/health-monitor?patient=${encodeURIComponent(row.patient_id)}&type=${row.type}`;
            }}
          >
            查看趋势
          </Button>
        </span>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const typeDist = useMemo(() => summary?.by_type ?? [], [summary]);

  return (
    <div className="space-y-6">
      {/* 统计卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard label="待复核" value={summary?.pending_total ?? 0} tone="text-warning" icon="🔔" />
        <StatCard label="今日新增异常" value={summary?.today_total ?? 0} tone="text-danger" icon="🆕" />
        <StatCard label="已转诊" value={summary?.referred_total ?? 0} tone="text-success" icon="🏥" />
      </div>
      {typeDist.length > 0 && (
        <Card title="类型分布" bodyClassName="p-4">
          <div className="flex flex-wrap gap-2">
            {typeDist.map((item) => (
              <Badge key={item.type} variant="danger">
                {TYPE_LABELS[item.type] ?? item.type} · {item.count}
              </Badge>
            ))}
          </div>
        </Card>
      )}

      {/* 筛选 */}
      <Card title="异常列表" actions={<span className="text-xs text-fg-dimmed">共 {total} 条，按测量时间倒序</span>}>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-3">
          <label className="block relative">
            <span className="text-xs text-fg-muted">老人</span>
            <Input
              className="mt-1"
              placeholder="输入姓名筛选…"
              value={patientQuery}
              onChange={(event) => {
                setPatientQuery(event.target.value);
                void searchPatients(event.target.value);
              }}
            />
            {patientOptions.length > 0 && (
              <ul className="absolute z-10 mt-1 w-full max-h-48 overflow-auto border border-border rounded-lg bg-surface shadow-lg">
                {patientOptions.map((patient) => (
                  <li key={patient.id}>
                    <button
                      type="button"
                      className="w-full text-left px-3 py-2 text-sm hover:bg-surface-alt"
                      onClick={() => selectPatient(patient)}
                    >
                      {patient.name}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </label>
          <label className="block">
            <span className="text-xs text-fg-muted">体征类型</span>
            <select
              className="mt-1 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
              value={typeFilter}
              onChange={(event) => { setTypeFilter(event.target.value); setPage(1); }}
            >
              <option value="">全部</option>
              {TYPE_OPTIONS.map(([type, label]) => (
                <option key={type} value={type}>{label}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-xs text-fg-muted">处理状态</span>
            <select
              className="mt-1 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value); setPage(1); }}
            >
              <option value="">全部</option>
              {REVIEW_STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-xs text-fg-muted">开始时间</span>
            <Input type="datetime-local" className="mt-1" value={dateFrom} onChange={(event) => { setDateFrom(event.target.value); setPage(1); }} />
          </label>
          <label className="block">
            <span className="text-xs text-fg-muted">结束时间</span>
            <div className="mt-1 flex gap-2">
              <Input type="datetime-local" className="flex-1" value={dateTo} onChange={(event) => { setDateTo(event.target.value); setPage(1); }} />
              <Button variant="secondary" size="md" onClick={resetFilters}>重置</Button>
            </div>
          </label>
        </div>
        {patientError && <p className="text-sm text-danger mt-2">{patientError}</p>}
        {pageError && <p className="text-sm text-danger rounded-lg border border-danger/40 bg-danger/5 px-3 py-2 mt-3">{pageError}</p>}
        {!pageError && (
          <div className="mt-4">
            <Table
              columns={columns}
              data={records}
              loading={loading}
              emptyMessage="暂无异常记录"
            />
            {total > PAGE_SIZE && (
              <div className="flex items-center justify-between mt-4">
                <Button variant="functional" size="sm" disabled={page <= 1} onClick={() => void loadRecords(page - 1)}>
                  上一页
                </Button>
                <span className="text-xs text-fg-dimmed">第 {page} / {totalPages} 页</span>
                <Button variant="functional" size="sm" disabled={page >= totalPages} onClick={() => void loadRecords(page + 1)}>
                  下一页
                </Button>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* 复核弹窗 */}
      <Modal
        open={reviewing !== null}
        onClose={() => setReviewing(null)}
        title={reviewing ? `复核${TYPE_LABELS[reviewing.type] ?? reviewing.type}异常` : ""}
      >
        {reviewing && reviewDone === null && (
          <div className="space-y-4">
            <div className="text-sm text-fg-muted">
              老人：<span className="text-fg-emphasis font-medium">{reviewing.patient_name || "-"}</span>
              {" · "}{formatDateTime(reviewing.measured_at)} 测量
              {" · "}{formatValue(reviewing.value)}{reviewing.unit}
              {reviewing.note ? ` · ${reviewing.note}` : ""}
            </div>
            <div className="flex gap-3">
              {(["确认异常", "误报"] as VitalSignReviewResult[]).map((option) => (
                <label
                  key={option}
                  className={`flex-1 flex items-center gap-2 rounded-lg border px-3 py-2.5 cursor-pointer text-sm ${
                    reviewResult === option ? "border-accent bg-accent/10" : "border-border bg-surface"
                  }`}
                >
                  <input
                    type="radio"
                    name="review-result"
                    className="accent-current"
                    checked={reviewResult === option}
                    onChange={() => setReviewResult(option)}
                  />
                  <span>{option === "确认异常" ? "确认异常" : "误报（建议修正记录）"}</span>
                </label>
              ))}
            </div>
            <label className="block">
              <span className="text-xs text-fg-muted">复核备注（≤500 字）</span>
              <textarea
                className="mt-1 w-full min-h-20 rounded-md border border-border bg-surface px-3 py-2 text-sm resize-y focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                maxLength={500}
                placeholder="如：复测确认、家属反馈测量偏差…"
                value={reviewNote}
                onChange={(event) => setReviewNote(event.target.value)}
              />
            </label>
            {reviewError && <p className="text-sm text-danger">{reviewError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setReviewing(null)}>取消</Button>
              <Button onClick={() => void submitReview()} loading={savingReview}>提交复核</Button>
            </div>
          </div>
        )}
        {reviewing && reviewDone !== null && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              已标记为<span className="font-medium text-fg-emphasis">{reviewDone === "确认异常" ? "确认异常" : "误报"}</span>，
              复核人与时间已留痕。
            </p>
            {reviewDone === "误报" && (
              <p className="text-sm rounded-lg border border-warning/40 bg-warning/5 px-3 py-2">
                提示：误报请通过「健康监测」页修正该记录数值，系统将重算异常标记并重置处理状态。
              </p>
            )}
            <div className="flex justify-end gap-2">
              {reviewDone === "误报" && (
                <Button
                  variant="functional"
                  onClick={() => {
                    const id = reviewing.patient_id;
                    setReviewing(null);
                    window.location.href = `/dashboard/health-monitor?patient=${encodeURIComponent(id)}`;
                  }}
                >
                  去修正记录
                </Button>
              )}
              <Button onClick={() => setReviewing(null)}>完成</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 转诊弹窗 */}
      <Modal
        open={referring !== null}
        onClose={() => setReferring(null)}
        title={referring ? `转诊${TYPE_LABELS[referring.type] ?? referring.type}异常` : ""}
      >
        {referring && referResult === null && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              确认将 <span className="font-medium text-fg-emphasis">{referring.patient_name || "该老人"}</span> 的
              {TYPE_LABELS[referring.type] ?? referring.type}异常（{formatValue(referring.value)}{referring.unit}，
              {formatDateTime(referring.measured_at)} 测量）转诊？系统将自动创建
              <span className="font-medium text-fg-emphasis">慢病随访计划（门诊）</span>，安排责任人跟进。
            </p>
            {referError && <p className="text-sm text-danger">{referError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setReferring(null)}>取消</Button>
              <Button onClick={() => void submitRefer()} loading={savingRefer}>确认转诊</Button>
            </div>
          </div>
        )}
        {referring && referResult !== null && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              已创建随访计划：<span className="font-medium text-fg-emphasis">{referResult.followup_plan.followup_type}</span>
              {" · "}计划日期 {formatDate(referResult.followup_plan.planned_date)}
              {" · "}责任人 {referResult.followup_plan.assignee}
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="functional" onClick={() => { window.location.href = "/dashboard/followup"; }}>前往随访管理</Button>
              <Button onClick={() => setReferring(null)}>完成</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
