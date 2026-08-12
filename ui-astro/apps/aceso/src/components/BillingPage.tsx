import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  addBillItem,
  createPayment,
  generateBill,
  getBill,
  getPaymentSummary,
  listArrears,
  listBills,
  listElderlyAdmissions,
  listFeeItems,
  listPatients,
  listPayments,
  settleEncounterBilling,
  type Arrear,
  type Bill,
  type BillItem,
  type Encounter,
  type FeeItem,
  type Payment,
  type PaymentMethod,
  type PaymentSummary,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 50;

const ENCOUNTER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "在住",
  DISCHARGED: "已离院",
  TRANSFERRED: "已转出",
  DECEASED: "已去世",
};

const ENCOUNTER_STATUS_VARIANT: Record<string, "success" | "default" | "warning" | "danger"> = {
  ACTIVE: "success",
  DISCHARGED: "default",
  TRANSFERRED: "warning",
  DECEASED: "danger",
};

const BILL_STATUS_VARIANT: Record<string, "success" | "default" | "warning"> = {
  待缴费: "warning",
  已结清: "success",
  已结算: "default",
};

const PAYMENT_METHODS: PaymentMethod[] = ["现金", "转账", "银行卡", "微信", "支付宝"];

interface Admission extends Encounter {
  patientName: string;
}

/** 可缴费对象（账单或欠费行，欠费行 id 即账单 ID） */
interface PayableBill {
  id: string;
  total_amount: number;
  period_start: string;
  period_end: string;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

function currentMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

export default function BillingPage() {
  // 入住与费用字典
  const [admissions, setAdmissions] = useState<Admission[]>([]);
  const [admissionsLoading, setAdmissionsLoading] = useState(true);
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [feeItems, setFeeItems] = useState<FeeItem[]>([]);
  const [feeItemsLoading, setFeeItemsLoading] = useState(true);

  // 账单与欠费
  const [bills, setBills] = useState<Bill[]>([]);
  const [billsLoading, setBillsLoading] = useState(false);
  const [billTotal, setBillTotal] = useState(0);
  const [arrears, setArrears] = useState<Arrear[]>([]);
  const [arrearsLoading, setArrearsLoading] = useState(false);
  const [summary, setSummary] = useState<PaymentSummary | null>(null);

  const [pageError, setPageError] = useState("");
  const [actionError, setActionError] = useState("");
  const [arrearsError, setArrearsError] = useState("");

  // 账单生成
  const [generateOpen, setGenerateOpen] = useState(false);
  const [month, setMonth] = useState(currentMonth());
  const [generating, setGenerating] = useState(false);

  // 手工加项
  const [addItemBill, setAddItemBill] = useState<Bill | null>(null);
  const [itemId, setItemId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [unitPrice, setUnitPrice] = useState("");
  const [itemRemark, setItemRemark] = useState("");
  const [addingItem, setAddingItem] = useState(false);

  // 缴费
  const [payTarget, setPayTarget] = useState<{ bill: PayableBill; remaining: number } | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState<PaymentMethod>("现金");
  const [payRemark, setPayRemark] = useState("");
  const [paying, setPaying] = useState(false);

  // 账单明细
  const [detail, setDetail] = useState<{ bill: Bill; items: BillItem[]; payments: Payment[] } | null>(null);

  // 结算收束
  const [settleOpen, setSettleOpen] = useState(false);
  const [settling, setSettling] = useState(false);

  const loadAdmissions = useCallback(async () => {
    setAdmissionsLoading(true);
    setPageError("");
    try {
      // 含已离院/已去世入住（结算收束入口需要），ACTIVE 优先排列
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 200 }),
        listElderlyAdmissions({ status: "", limit: 200 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      const statusRank: Record<string, number> = { ACTIVE: 0, DISCHARGED: 1, DECEASED: 2, TRANSFERRED: 3 };
      records.sort((a, b) => (statusRank[a.status] ?? 9) - (statusRank[b.status] ?? 9) || (b.admit_date ?? "").localeCompare(a.admit_date ?? ""));
      setAdmissions(records);
      setSelectedEncounterId((current) => {
        if (records.some((record) => record.id === current)) return current;
        return records.find((record) => record.status === "ACTIVE")?.id || records[0]?.id || "";
      });
    } catch (error) {
      setPageError(errorMessage(error, "无法加载入住列表"));
    } finally {
      setAdmissionsLoading(false);
    }
  }, []);

  const loadFeeItems = useCallback(async () => {
    setFeeItemsLoading(true);
    try {
      const response = await listFeeItems({ status: "启用", limit: 200 });
      setFeeItems(response.records);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载费用字典"));
    } finally {
      setFeeItemsLoading(false);
    }
  }, []);

  const loadBills = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setBills([]);
      setBillTotal(0);
      return;
    }
    setBillsLoading(true);
    setActionError("");
    try {
      const response = await listBills(encounterId, { limit: PAGE_SIZE });
      setBills(response.records);
      setBillTotal(response.meta.total);
    } catch (error) {
      setActionError(errorMessage(error, "无法加载账单列表"));
    } finally {
      setBillsLoading(false);
    }
  }, []);

  const loadArrearsAndSummary = useCallback(async () => {
    setArrearsLoading(true);
    setArrearsError("");
    try {
      const [arrearsResponse, summaryResponse] = await Promise.all([
        listArrears({ limit: PAGE_SIZE }),
        getPaymentSummary(),
      ]);
      setArrears(arrearsResponse.records);
      setSummary(summaryResponse);
    } catch (error) {
      setArrearsError(errorMessage(error, "无法加载欠费列表"));
    } finally {
      setArrearsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAdmissions();
    void loadFeeItems();
    void loadArrearsAndSummary();
  }, [loadAdmissions, loadFeeItems, loadArrearsAndSummary]);

  useEffect(() => {
    void loadBills(selectedEncounterId);
  }, [selectedEncounterId, loadBills]);

  const selectedAdmission = useMemo(
    () => admissions.find((admission) => admission.id === selectedEncounterId) ?? null,
    [admissions, selectedEncounterId],
  );

  const admissionById = useMemo(() => new Map(admissions.map((admission) => [admission.id, admission])), [admissions]);

  /** 变更后联动刷新账单与欠费汇总 */
  const refreshAfterChange = useCallback(() => {
    void loadBills(selectedEncounterId);
    void loadArrearsAndSummary();
  }, [selectedEncounterId, loadBills, loadArrearsAndSummary]);

  // ─── 账单生成 ───────────────────────────────────────────────────────

  function openGenerate() {
    setMonth(currentMonth());
    setActionError("");
    setGenerateOpen(true);
  }

  async function handleGenerate() {
    if (!selectedEncounterId) return;
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
      setActionError("账期格式应为 YYYY-MM");
      return;
    }
    setGenerating(true);
    setActionError("");
    try {
      await generateBill(selectedEncounterId, month);
      setGenerateOpen(false);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "账单生成失败"));
    } finally {
      setGenerating(false);
    }
  }

  // ─── 手工加项 ───────────────────────────────────────────────────────

  function openAddItem(bill: Bill) {
    setItemId("");
    setQuantity("1");
    setUnitPrice("");
    setItemRemark("");
    setActionError("");
    setAddItemBill(bill);
  }

  async function handleAddItem() {
    if (!addItemBill) return;
    if (!itemId) {
      setActionError("请选择费用项目");
      return;
    }
    const parsedQuantity = Number(quantity);
    if (!Number.isFinite(parsedQuantity) || parsedQuantity <= 0) {
      setActionError("数量必须为正数");
      return;
    }
    let parsedUnitPrice: number | undefined;
    if (unitPrice.trim() !== "") {
      parsedUnitPrice = Number(unitPrice);
      if (!Number.isFinite(parsedUnitPrice) || parsedUnitPrice <= 0) {
        setActionError("单价必须为正数");
        return;
      }
    }
    setAddingItem(true);
    setActionError("");
    try {
      await addBillItem(addItemBill.id, {
        item_id: itemId,
        quantity: parsedQuantity,
        ...(parsedUnitPrice !== undefined ? { unit_price: parsedUnitPrice } : {}),
        ...(itemRemark.trim() ? { remark: itemRemark.trim() } : {}),
      });
      setAddItemBill(null);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "手工加项失败"));
    } finally {
      setAddingItem(false);
    }
  }

  // ─── 缴费 ───────────────────────────────────────────────────────────

  async function openPayment(payable: PayableBill, suggestedAmount?: number) {
    setActionError("");
    setPayTarget(null);
    let remaining = payable.total_amount;
    try {
      const payments = await listPayments(payable.id, { limit: 100 });
      const paid = payments.records.reduce((acc, payment) => acc + payment.amount, 0);
      remaining = payable.total_amount - paid;
    } catch {
      // 流水加载失败时以账单合计为上限，超缴由服务端兜底
    }
    remaining = Math.max(remaining, 0);
    setPayTarget({ bill: payable, remaining });
    setPayAmount(suggestedAmount !== undefined ? formatAmount(suggestedAmount) : formatAmount(remaining));
    setPayMethod("现金");
    setPayRemark("");
  }

  async function handlePay() {
    if (!payTarget) return;
    const parsed = Number(payAmount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setActionError("缴费金额必须为正数");
      return;
    }
    if (parsed > payTarget.remaining + 1e-9) {
      setActionError(`缴费金额不得超过剩余应缴 ¥ ${formatAmount(payTarget.remaining)}`);
      return;
    }
    setPaying(true);
    setActionError("");
    try {
      await createPayment(payTarget.bill.id, {
        amount: parsed,
        method: payMethod,
        ...(payRemark.trim() ? { remark: payRemark.trim() } : {}),
      });
      setPayTarget(null);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "缴费失败"));
    } finally {
      setPaying(false);
    }
  }

  // ─── 账单明细 ───────────────────────────────────────────────────────

  async function openDetail(bill: Bill) {
    setActionError("");
    try {
      const [detailResponse, paymentsResponse] = await Promise.all([
        getBill(bill.id),
        listPayments(bill.id, { limit: PAGE_SIZE }),
      ]);
      setDetail({ bill: detailResponse, items: detailResponse.items ?? [], payments: paymentsResponse.records });
    } catch (error) {
      setActionError(errorMessage(error, "无法加载账单明细"));
    }
  }

  // ─── 结算收束 ───────────────────────────────────────────────────────

  const canSettle =
    selectedAdmission != null &&
    (selectedAdmission.status === "DISCHARGED" || selectedAdmission.status === "DECEASED") &&
    !selectedAdmission.settled_at;

  async function handleSettle() {
    if (!selectedEncounterId) return;
    setSettling(true);
    setActionError("");
    try {
      const encounter = await settleEncounterBilling(selectedEncounterId);
      setAdmissions((current) =>
        current.map((admission) =>
          admission.id === encounter.id ? { ...admission, ...encounter, patientName: admission.patientName } : admission,
        ),
      );
      setSettleOpen(false);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "结算收束失败"));
    } finally {
      setSettling(false);
    }
  }

  // ─── 表格列 ─────────────────────────────────────────────────────────

  const billColumns: Column<Bill>[] = [
    {
      key: "period",
      header: "账期",
      render: (row) => (
        <span className="text-fg">{formatDate(row.period_start)} ~ {formatDate(row.period_end)}</span>
      ),
    },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={BILL_STATUS_VARIANT[row.status] ?? "default"}>{row.status}</Badge>,
    },
    {
      key: "total_amount",
      header: "合计（元）",
      render: (row) => <span className="font-medium">{formatAmount(row.total_amount)}</span>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => void openDetail(row)}>明细</Button>
          {row.status === "待缴费" && (
            <>
              <Button variant="link" size="sm" onClick={() => openAddItem(row)}>加项</Button>
              <Button variant="link" size="sm" onClick={() => void openPayment(row)}>缴费</Button>
            </>
          )}
        </div>
      ),
    },
  ];

  const itemColumns: Column<BillItem>[] = [
    {
      key: "source",
      header: "来源",
      render: (row) => <Badge variant={row.source === "自动" ? "default" : "info"}>{row.source}</Badge>,
    },
    { key: "item_name", header: "项目", render: (row) => <span className="text-fg">{row.item_name}</span> },
    { key: "unit_price", header: "单价（元）", render: (row) => formatAmount(row.unit_price) },
    { key: "quantity", header: "数量", render: (row) => row.quantity },
    {
      key: "amount",
      header: "金额（元）",
      render: (row) => <span className="font-medium">{formatAmount(row.amount)}</span>,
    },
    { key: "remark", header: "备注", render: (row) => <span className="text-fg-muted text-sm">{row.remark ?? "—"}</span> },
  ];

  const paymentColumns: Column<Payment>[] = [
    {
      key: "created_at",
      header: "时间",
      render: (row) => <span className="text-fg-muted text-sm">{formatDateTime(row.created_at)}</span>,
    },
    { key: "method", header: "方式", render: (row) => row.method },
    {
      key: "amount",
      header: "金额（元）",
      render: (row) => <span className="text-success font-medium">−{formatAmount(row.amount)}</span>,
    },
    { key: "operator", header: "操作人", render: (row) => <span className="text-fg-muted text-sm">{row.operator}</span> },
    { key: "remark", header: "备注", render: (row) => <span className="text-fg-muted text-sm">{row.remark ?? "—"}</span> },
  ];

  const arrearColumns: Column<Arrear>[] = [
    {
      key: "encounter",
      header: "长者",
      render: (row) => (
        <span className="text-fg">{admissionById.get(row.encounter_id)?.patientName ?? row.encounter_id}</span>
      ),
    },
    {
      key: "period",
      header: "账期",
      render: (row) => (
        <span className="text-fg-muted text-sm">{formatDate(row.period_start)} ~ {formatDate(row.period_end)}</span>
      ),
    },
    { key: "total_amount", header: "合计（元）", render: (row) => formatAmount(row.total_amount) },
    { key: "paid_amount", header: "已缴（元）", render: (row) => formatAmount(row.paid_amount) },
    {
      key: "balance",
      header: "欠费（元）",
      render: (row) => <span className="text-danger font-medium">{formatAmount(row.balance)}</span>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openPayment(row, row.balance)}>缴费</Button>
      ),
    },
  ];

  const modalError = actionError && (
    <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
  );

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">养老收费</h2>
        <p className="text-sm text-fg-muted mt-1">
          账单生成（按月自动计费）、手工加项、缴费、欠费列表与结算收束入口。押金登记/退押见「押金管理」页。
        </p>
      </div>

      {pageError && (
        <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>
      )}

      <Card title="入住选择" actions={
        <Button variant="ghost" size="sm" onClick={() => void loadAdmissions()} disabled={admissionsLoading}>
          刷新
        </Button>
      }>
        {admissionsLoading ? (
          <div className="flex justify-center py-8"><LoadingSpinner /></div>
        ) : admissions.length === 0 ? (
          <EmptyState icon="🏠" title="暂无入住记录" description="请先在「入住管理」登记养老入住" />
        ) : (
          <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_auto] items-end">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">入住（encounter）</label>
              <select
                value={selectedEncounterId}
                onChange={(event) => setSelectedEncounterId(event.target.value)}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                {admissions.map((admission) => (
                  <option key={admission.id} value={admission.id}>
                    {admission.patientName} · {admission.encounter_no}（{ENCOUNTER_STATUS_LABEL[admission.status] ?? admission.status}）
                  </option>
                ))}
              </select>
            </div>
            {selectedAdmission && (
              <div className="flex items-center gap-3 pb-1">
                <Badge variant={ENCOUNTER_STATUS_VARIANT[selectedAdmission.status] ?? "default"}>
                  {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
                </Badge>
                {selectedAdmission.settled_at && <Badge variant="default">已结算收束</Badge>}
                <span className="text-sm text-fg-muted">
                  {selectedAdmission.patientName} · {selectedAdmission.department ?? "—"} {selectedAdmission.ward ?? ""}
                </span>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* 账单列表：账单生成 + 手工加项 + 缴费 */}
      <Card
        title="账单列表"
        actions={
          <Button size="sm" onClick={openGenerate} disabled={!selectedEncounterId || Boolean(selectedAdmission?.settled_at)}>
            生成账单
          </Button>
        }
      >
        {!selectedEncounterId ? (
          <EmptyState icon="🧾" title="请先选择入住" description="选择入住后展示账单，并可生成账单、手工加项、缴费" />
        ) : (
          <>
            {actionError && !generateOpen && !addItemBill && !payTarget && !settleOpen && !detail && (
              <div className="mb-4 rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
            )}
            {selectedAdmission?.settled_at && (
              <p className="mb-4 text-sm text-fg-muted">该入住已完成结算收束，账单已冻结（不可生成账单、手工加项或缴费）。</p>
            )}
            <Table
              columns={billColumns}
              data={bills}
              keyField="id"
              loading={billsLoading}
              emptyMessage="暂无账单，点击右上角「生成账单」按月自动计费"
            />
            {billTotal > 0 && (
              <p className="text-xs text-fg-dimmed mt-3">共 {billTotal} 条账单（当前显示最近 {bills.length} 条）</p>
            )}
          </>
        )}
      </Card>

      {/* 结算入口 */}
      <Card title="结算收束">
        {!selectedAdmission ? (
          <EmptyState icon="🏁" title="请先选择入住" description="结算收束需要选定一位入住长者" />
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-3">
              <Badge variant={ENCOUNTER_STATUS_VARIANT[selectedAdmission.status] ?? "default"}>
                {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
              </Badge>
              <span className="text-sm text-fg-muted">
                {selectedAdmission.patientName} · {selectedAdmission.encounter_no}
                {selectedAdmission.department ? ` · ${selectedAdmission.department} ${selectedAdmission.ward ?? ""}` : ""}
              </span>
              {selectedAdmission.settled_at ? (
                <Badge variant="default">已结算收束 {formatDateTime(selectedAdmission.settled_at)}</Badge>
              ) : (
                <Badge variant="info">未结算</Badge>
              )}
            </div>
            <p className="text-sm text-fg-muted">
              {selectedAdmission.settled_at
                ? "该入住已完成结算收束：全部账单已冻结，不可再生成账单、手工加项或缴费。"
                : canSettle
                  ? "该入住已离院/去世且未结算：结算收束将生成区间最终账单并冻结全部账单，冻结后不可再生成账单、手工加项或缴费。"
                  : "结算收束适用于已离院/去世的养老入住；在住入住不可结算。"}
            </p>
            {!selectedAdmission.settled_at && (
              <Button
                variant="warning"
                disabled={!canSettle}
                onClick={() => {
                  setActionError("");
                  setSettleOpen(true);
                }}
              >
                结算收束
              </Button>
            )}
          </div>
        )}
      </Card>

      {/* 欠费列表 */}
      <Card
        title="欠费列表"
        actions={
          <Button variant="ghost" size="sm" onClick={() => void loadArrearsAndSummary()} disabled={arrearsLoading}>
            刷新
          </Button>
        }
      >
        {summary && (
          <div className="grid gap-4 md:grid-cols-3 mb-5">
            <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
              <p className="text-xs text-fg-dimmed">应缴合计（元）</p>
              <p className="text-lg font-bold text-fg mt-0.5">{formatAmount(summary.due_amount)}</p>
            </div>
            <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
              <p className="text-xs text-fg-dimmed">已缴合计（元）</p>
              <p className="text-lg font-bold text-success mt-0.5">{formatAmount(summary.paid_amount)}</p>
            </div>
            <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
              <p className="text-xs text-fg-dimmed">欠费合计（元）</p>
              <p className="text-lg font-bold text-danger mt-0.5">{formatAmount(summary.arrears_amount)}</p>
            </div>
          </div>
        )}
        {arrearsError && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{arrearsError}</div>
        )}
        <Table
          columns={arrearColumns}
          data={arrears}
          keyField="id"
          loading={arrearsLoading}
          emptyMessage="暂无欠费账单"
        />
        {arrears.length > 0 && (
          <p className="text-xs text-fg-dimmed mt-3">共 {arrears.length} 条欠费账单（当前显示最近 {PAGE_SIZE} 条）</p>
        )}
      </Card>

      {/* 生成账单 */}
      <Modal open={generateOpen} onClose={() => setGenerateOpen(false)} title="生成账单">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            按自然月自动计费（床位费/护理费/伙食费），账期裁剪到在院区间；同入住同账期仅能生成一次。
            {selectedAdmission ? ` 当前入住：${selectedAdmission.patientName}。` : ""}
          </p>
          <Input label="账期（月）" value={month} onChange={(event) => setMonth(event.target.value)} placeholder="YYYY-MM" />
          {modalError}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setGenerateOpen(false)}>取消</Button>
            <Button loading={generating} onClick={() => void handleGenerate()}>生成账单</Button>
          </div>
        </div>
      </Modal>

      {/* 手工加项 */}
      <Modal open={addItemBill !== null} onClose={() => setAddItemBill(null)} title="手工加项">
        {addItemBill && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              为 {formatDate(addItemBill.period_start)} ~ {formatDate(addItemBill.period_end)} 账单添加自费药/检查费等手工项目；
              明细为字典快照，单价缺省取字典单价，可覆盖。
            </p>
            {feeItemsLoading ? (
              <div className="flex justify-center py-6"><LoadingSpinner /></div>
            ) : feeItems.length === 0 ? (
              <EmptyState icon="🧾" title="暂无启用费用项目" description="请先配置启用的费用字典项目" />
            ) : (
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">费用项目</label>
                <select
                  value={itemId}
                  onChange={(event) => setItemId(event.target.value)}
                  className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <option value="">请选择</option>
                  {feeItems.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.category} · {item.name}（¥ {formatAmount(item.unit_price)}）
                    </option>
                  ))}
                </select>
              </div>
            )}
            <Input label="数量" type="number" min="0.01" step="0.01" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
            <Input
              label="单价覆盖（元，可选）"
              type="number"
              min="0.01"
              step="0.01"
              value={unitPrice}
              onChange={(event) => setUnitPrice(event.target.value)}
              placeholder="缺省取字典单价"
            />
            <Input label="备注（可选）" value={itemRemark} onChange={(event) => setItemRemark(event.target.value)} maxLength={500} />
            {modalError}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="ghost" onClick={() => setAddItemBill(null)}>取消</Button>
              <Button loading={addingItem} onClick={() => void handleAddItem()}>确认加项</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 缴费 */}
      <Modal open={payTarget !== null} onClose={() => setPayTarget(null)} title="缴费">
        {payTarget && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              账单 {formatDate(payTarget.bill.period_start)} ~ {formatDate(payTarget.bill.period_end)}：
              合计 ¥ {formatAmount(payTarget.bill.total_amount)}，剩余应缴 ¥ {formatAmount(payTarget.remaining)}。
              支持多次部分缴费，余额归零后账单转为已结清。
            </p>
            <Input
              label="缴费金额（元）"
              type="number"
              min="0.01"
              step="0.01"
              value={payAmount}
              onChange={(event) => setPayAmount(event.target.value)}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">缴费方式</label>
              <div className="flex flex-wrap gap-2">
                {PAYMENT_METHODS.map((method) => (
                  <button
                    key={method}
                    type="button"
                    onClick={() => setPayMethod(method)}
                    className={`h-9 px-3 rounded-md text-sm border transition-colors cursor-pointer ${
                      payMethod === method
                        ? "border-accent text-accent bg-accent/10"
                        : "border-border text-fg-muted hover:text-fg"
                    }`}
                  >
                    {method}
                  </button>
                ))}
              </div>
            </div>
            <Input label="备注（可选）" value={payRemark} onChange={(event) => setPayRemark(event.target.value)} maxLength={500} />
            {modalError}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="ghost" onClick={() => setPayTarget(null)}>取消</Button>
              <Button loading={paying} onClick={() => void handlePay()}>确认缴费</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 账单明细 */}
      <Modal open={detail !== null} onClose={() => setDetail(null)} title="账单明细" width="48rem">
        {detail && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center gap-3">
              <Badge variant={BILL_STATUS_VARIANT[detail.bill.status] ?? "default"}>{detail.bill.status}</Badge>
              <span className="text-sm text-fg-muted">
                {formatDate(detail.bill.period_start)} ~ {formatDate(detail.bill.period_end)} · 合计 ¥ {formatAmount(detail.bill.total_amount)}
              </span>
              {detail.bill.settled_at && <Badge variant="default">已收束 {formatDateTime(detail.bill.settled_at)}</Badge>}
            </div>
            <div>
              <h4 className="text-sm font-semibold text-fg-muted mb-2">明细（{detail.items.length}）</h4>
              <Table columns={itemColumns} data={detail.items} keyField="id" emptyMessage="暂无明细" />
            </div>
            <div>
              <h4 className="text-sm font-semibold text-fg-muted mb-2">缴费流水（{detail.payments.length}）</h4>
              <Table columns={paymentColumns} data={detail.payments} keyField="id" emptyMessage="暂无缴费记录" />
            </div>
            {detail.bill.status === "待缴费" && (
              <div className="flex justify-end gap-2 pt-1">
                <Button
                  variant="ghost"
                  onClick={() => {
                    const bill = detail.bill;
                    setDetail(null);
                    openAddItem(bill);
                  }}
                >
                  手工加项
                </Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    const bill = detail.bill;
                    setDetail(null);
                    void openPayment(bill);
                  }}
                >
                  缴费
                </Button>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* 结算收束确认 */}
      <Modal open={settleOpen} onClose={() => setSettleOpen(false)} title="确认结算收束">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            将为{selectedAdmission?.patientName ?? ""}生成区间最终账单并冻结全部账单（收束后不可再生成账单、手工加项或缴费），
            确定继续？
          </p>
          {modalError}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setSettleOpen(false)}>取消</Button>
            <Button variant="warning" loading={settling} onClick={() => void handleSettle()}>确认结算</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
