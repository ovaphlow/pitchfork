import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  createChildPackageSubscription,
  createChildServiceInvoice,
  createChildServicePackage,
  createChildServiceSettlement,
  listChildPackageSubscriptions,
  listChildServiceInvoices,
  listChildServicePackages,
  listChildServiceSettlements,
  listPatients,
  registerChildServiceSettlementPayment,
  updateChildPackageSubscriptionStatus,
  updateChildServiceInvoiceStatus,
  updateChildServicePackage,
  type ChildPackageSubscription,
  type ChildServiceInvoice,
  type ChildServicePackage,
  type ChildServiceSettlement,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDateTime } from "../lib/datetime";
import { DOMAIN_ENTITY, currentEntityLabels } from "../lib/domain";
import { useDomain } from "../lib/useDomain";
import {
  INVOICE_STATUSES,
  PACKAGE_STATUSES,
  PAYMENT_METHODS,
  SETTLEMENT_STATUSES,
  addDraft,
  canTransitionInvoice,
  canTransitionSubscription,
  emptyPackageItem,
  emptySettlementItem,
  formatMoney,
  formatQuantity,
  invoiceSourceLabel,
  invoiceStatusVariant,
  packageItemsLabel,
  packageStatusVariant,
  payStateCount,
  payStateLabel,
  payStateVariant,
  removeDraft,
  settlementOutstanding,
  settlementPaidRatio,
  settlementStatusVariant,
  settlementSummary,
  sortSubscriptions,
  subscriptionRatio,
  subscriptionStatusVariant,
  toSettlementItems,
  updateDraft,
  validateInvoiceForm,
  validatePackageForm,
  validatePaymentForm,
  validateSettlementForm,
  validateSubscriptionForm,
  type SettlementItemDraft,
} from "../lib/cashier";

/**
 * 收银工作台（054 E4）。
 *
 * 消费 E1–E3 的冻结路由（§5.5），把收银员的链路收在一页：
 *   儿保服务套餐定义 → 套餐订阅（**付费状态四态可视化**）→ 结算单 → 收款登记 → 发票开具/作废。
 *
 * 金额纪律（必须遵守）：
 * - **金额一律服务端计算**：明细 `amount = unit_price × quantity`（HALF_UP 到分）、
 *   `total_amount = Σ`、订阅 `amount` 为购买时套餐价快照、`pay_state`/结算 `status` 读时派生。
 *   本页**只展示**服务端金额，不做前端重算，也不提交派生字段。
 * - 服务端 400/409（**超额收款**、**票号重复**、**状态回退**、互斥字段）文案**原样透出**。
 * - 入口显隐由 F1 机制按 `billing:write` 控制；**后端仍是唯一强制点**，前端隐藏不替代 403。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

type PackageItemDraft = { item_code: string; item_name: string; quantity: number };

export default function CashierWorkbenchPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [error, setError] = useState("");

  // —— E1 套餐定义 ——
  const [packages, setPackages] = useState<ChildServicePackage[]>([]);
  const [packageStatusFilter, setPackageStatusFilter] = useState("");
  const [packagesLoading, setPackagesLoading] = useState(false);
  const [packageCode, setPackageCode] = useState("");
  const [packageName, setPackageName] = useState("");
  const [packagePrice, setPackagePrice] = useState("");
  const [packageItems, setPackageItems] = useState<PackageItemDraft[]>([emptyPackageItem()]);
  const [packageSaving, setPackageSaving] = useState(false);
  const [packageHint, setPackageHint] = useState("");
  const [packageError, setPackageError] = useState("");
  const [togglingPackageId, setTogglingPackageId] = useState("");

  // —— E1 订阅 ——
  const [subscriptions, setSubscriptions] = useState<ChildPackageSubscription[]>([]);
  const [subscriptionPackageId, setSubscriptionPackageId] = useState("");
  const [subscriptionsLoading, setSubscriptionsLoading] = useState(false);
  const [subscriptionSaving, setSubscriptionSaving] = useState(false);
  const [subscriptionHint, setSubscriptionHint] = useState("");
  const [subscriptionError, setSubscriptionError] = useState("");
  const [togglingSubscriptionId, setTogglingSubscriptionId] = useState("");

  // —— E2 结算单 ——
  const [settlements, setSettlements] = useState<ChildServiceSettlement[]>([]);
  const [settlementStatusFilter, setSettlementStatusFilter] = useState("");
  const [settlementsLoading, setSettlementsLoading] = useState(false);
  const [settlementSubscriptionId, setSettlementSubscriptionId] = useState("");
  const [settlementItems, setSettlementItems] = useState<SettlementItemDraft[]>([emptySettlementItem()]);
  const [settlementSaving, setSettlementSaving] = useState(false);
  const [settlementHint, setSettlementHint] = useState("");
  const [settlementError, setSettlementError] = useState("");

  // —— E2 收款 ——
  const [paymentSettlementId, setPaymentSettlementId] = useState("");
  const [paymentAmount, setPaymentAmount] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<string>(PAYMENT_METHODS[0]);
  const [paymentRemark, setPaymentRemark] = useState("");
  const [paymentSaving, setPaymentSaving] = useState(false);
  const [paymentHint, setPaymentHint] = useState("");
  const [paymentError, setPaymentError] = useState("");

  // —— E3 发票 ——
  const [invoices, setInvoices] = useState<ChildServiceInvoice[]>([]);
  const [invoiceStatusFilter, setInvoiceStatusFilter] = useState("");
  const [invoicesLoading, setInvoicesLoading] = useState(false);
  const [invoiceTitle, setInvoiceTitle] = useState("");
  const [invoiceNo, setInvoiceNo] = useState("");
  const [invoiceAmount, setInvoiceAmount] = useState("");
  const [invoiceSettlementId, setInvoiceSettlementId] = useState("");
  const [invoiceSubscriptionId, setInvoiceSubscriptionId] = useState("");
  const [invoiceSaving, setInvoiceSaving] = useState(false);
  const [invoiceHint, setInvoiceHint] = useState("");
  const [invoiceError, setInvoiceError] = useState("");
  const [voidingId, setVoidingId] = useState("");

  const selected = useMemo(
    () => children.find((child) => child.id === patientId) ?? null,
    [children, patientId],
  );
  const activePackages = useMemo(
    () => packages.filter((item) => item.status === "启用"),
    [packages],
  );
  const payableSettlements = useMemo(
    () => settlements.filter((item) => item.status === "待收款" || item.status === "部分收款"),
    [settlements],
  );
  const selectedSettlement = useMemo(
    () => settlements.find((item) => item.id === paymentSettlementId) ?? null,
    [settlements, paymentSettlementId],
  );
  const subscriptionSummary = useMemo(() => payStateCount(subscriptions), [subscriptions]);

  // —— 儿童列表（默认取 URL 上的 patient，其次第一个）——
  useEffect(() => {
    let active = true;
    listPatients({ person_type: "儿童", status: "ACTIVE", limit: 200 })
      .then((response) => {
        if (!active) return;
        setChildren(response.records);
        const fromUrl =
          typeof window === "undefined"
            ? null
            : new URLSearchParams(window.location.search).get("patient");
        const initial =
          fromUrl && response.records.some((child) => child.id === fromUrl)
            ? fromUrl
            : response.records[0]?.id ?? "";
        setPatientId(initial);
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, `无法加载${currentEntityLabels().person}列表`));
      });
    return () => {
      active = false;
    };
  }, []);

  // —— 套餐列表 ——
  const loadPackages = useCallback(async () => {
    setPackagesLoading(true);
    try {
      const response = await listChildServicePackages({
        status: packageStatusFilter.trim() === "" ? undefined : packageStatusFilter,
        limit: PAGE_SIZE,
      });
      setPackages(response.records);
      setSubscriptionPackageId((current) =>
        current && response.records.some((item) => item.id === current)
          ? current
          : response.records.find((item) => item.status === "启用")?.id ?? response.records[0]?.id ?? "",
      );
    } catch (cause) {
      setPackageError(errorMessage(cause, "无法加载套餐定义"));
    } finally {
      setPackagesLoading(false);
    }
  }, [packageStatusFilter]);

  useEffect(() => {
    void loadPackages();
  }, [loadPackages]);

  // —— 订阅列表（按儿童）——
  const loadSubscriptions = useCallback(async () => {
    if (!patientId) {
      setSubscriptions([]);
      return;
    }
    setSubscriptionsLoading(true);
    try {
      const response = await listChildPackageSubscriptions({ patient_id: patientId, limit: PAGE_SIZE });
      setSubscriptions(response.records);
      setSettlementSubscriptionId((current) =>
        current && response.records.some((item) => item.id === current) ? current : "",
      );
    } catch (cause) {
      setSubscriptionError(errorMessage(cause, "无法加载套餐订阅"));
    } finally {
      setSubscriptionsLoading(false);
    }
  }, [patientId]);

  useEffect(() => {
    void loadSubscriptions();
  }, [loadSubscriptions]);

  // —— 结算单列表（按儿童）——
  const loadSettlements = useCallback(async () => {
    if (!patientId) {
      setSettlements([]);
      setPaymentSettlementId("");
      return;
    }
    setSettlementsLoading(true);
    try {
      const response = await listChildServiceSettlements({
        patient_id: patientId,
        status: settlementStatusFilter.trim() === "" ? undefined : settlementStatusFilter,
        limit: PAGE_SIZE,
      });
      setSettlements(response.records);
      setPaymentSettlementId((current) => {
        if (current && response.records.some((item) => item.id === current)) return current;
        return (
          response.records.find((item) => item.status === "待收款" || item.status === "部分收款")?.id ?? ""
        );
      });
    } catch (cause) {
      setSettlementError(errorMessage(cause, "无法加载结算单"));
    } finally {
      setSettlementsLoading(false);
    }
  }, [patientId, settlementStatusFilter]);

  useEffect(() => {
    void loadSettlements();
  }, [loadSettlements]);

  // —— 发票台账（按儿童）——
  const loadInvoices = useCallback(async () => {
    if (!patientId) {
      setInvoices([]);
      return;
    }
    setInvoicesLoading(true);
    try {
      const response = await listChildServiceInvoices({
        patient_id: patientId,
        status: invoiceStatusFilter.trim() === "" ? undefined : invoiceStatusFilter,
        limit: PAGE_SIZE,
      });
      setInvoices(response.records);
    } catch (cause) {
      setInvoiceError(errorMessage(cause, "无法加载发票台账"));
    } finally {
      setInvoicesLoading(false);
    }
  }, [patientId, invoiceStatusFilter]);

  useEffect(() => {
    void loadInvoices();
  }, [loadInvoices]);

  // —— 动作：创建套餐 ——
  async function submitPackage() {
    const validation = validatePackageForm({
      code: packageCode,
      name: packageName,
      priceText: packagePrice,
      items: packageItems,
    });
    if (validation) {
      setPackageError(validation);
      return;
    }
    setPackageSaving(true);
    setPackageError("");
    setPackageHint("");
    try {
      const created = await createChildServicePackage({
        code: packageCode.trim(),
        name: packageName.trim(),
        price: Number(packagePrice.trim()),
        items: packageItems.map((item) => ({
          item_code: item.item_code.trim(),
          item_name: item.item_name.trim(),
          quantity: item.quantity,
        })),
      });
      setPackageHint(`已创建套餐「${created.name}」（价格 ${formatMoney(created.price)} 元）`);
      setPackageCode("");
      setPackageName("");
      setPackagePrice("");
      setPackageItems([emptyPackageItem()]);
      await loadPackages();
    } catch (cause) {
      // 编码重复 → 409；未知字段/非法金额 → 400：服务端文案原样透出
      setPackageError(errorMessage(cause, "创建套餐失败"));
    } finally {
      setPackageSaving(false);
    }
  }

  // —— 动作：套餐启用/停用 ——
  async function togglePackage(item: ChildServicePackage) {
    setTogglingPackageId(item.id);
    setPackageError("");
    try {
      await updateChildServicePackage(item.id, { status: item.status === "启用" ? "停用" : "启用" });
      await loadPackages();
    } catch (cause) {
      setPackageError(errorMessage(cause, "套餐状态更新失败"));
    } finally {
      setTogglingPackageId("");
    }
  }

  // —— 动作：订阅套餐（金额/状态由服务端派生）——
  async function submitSubscription() {
    const validation = validateSubscriptionForm({ patientId, packageId: subscriptionPackageId });
    if (validation) {
      setSubscriptionError(validation);
      return;
    }
    setSubscriptionSaving(true);
    setSubscriptionError("");
    setSubscriptionHint("");
    try {
      const created = await createChildPackageSubscription({
        patient_id: patientId,
        package_id: subscriptionPackageId,
      });
      setSubscriptionHint(
        `已订阅「${created.package_name ?? created.package_id}」；金额快照 ${formatMoney(created.amount)} 元，付费状态 ${created.pay_state}`,
      );
      await loadSubscriptions();
    } catch (cause) {
      // 提交 amount/paid_amount/used_amount/status → 400：服务端文案原样透出
      setSubscriptionError(errorMessage(cause, "订阅套餐失败"));
    } finally {
      setSubscriptionSaving(false);
    }
  }

  // —— 动作：订阅启用/停用 ——
  async function toggleSubscription(item: ChildPackageSubscription) {
    const target = item.status === "启用" ? "停用" : "启用";
    if (!canTransitionSubscription(item.status, target)) return;
    setTogglingSubscriptionId(item.id);
    setSubscriptionError("");
    try {
      await updateChildPackageSubscriptionStatus(item.id, target);
      await loadSubscriptions();
    } catch (cause) {
      setSubscriptionError(errorMessage(cause, "订阅状态更新失败"));
    } finally {
      setTogglingSubscriptionId("");
    }
  }

  // —— 动作：创建结算单（金额由服务端按明细计算）——
  async function submitSettlement() {
    const validation = validateSettlementForm({ patientId, items: settlementItems });
    if (validation) {
      setSettlementError(validation);
      return;
    }
    setSettlementSaving(true);
    setSettlementError("");
    setSettlementHint("");
    try {
      const created = await createChildServiceSettlement({
        patient_id: patientId,
        subscription_id: settlementSubscriptionId.trim() === "" ? null : settlementSubscriptionId.trim(),
        items: toSettlementItems(settlementItems),
      });
      setSettlementHint(
        `已创建结算单 ${created.settlement_no}；合计 ${formatMoney(created.total_amount)} 元（服务端按明细计算）`,
      );
      setSettlementItems([emptySettlementItem()]);
      await loadSettlements();
      setPaymentSettlementId(created.id);
    } catch (cause) {
      // 提交 total_amount/paid_amount/status 或明细非法 → 400：服务端文案原样透出
      setSettlementError(errorMessage(cause, "创建结算单失败"));
    } finally {
      setSettlementSaving(false);
    }
  }

  // —— 动作：登记收款（超额 400 文案透出）——
  async function submitPayment() {
    const validation = validatePaymentForm({ amountText: paymentAmount, method: paymentMethod });
    if (validation) {
      setPaymentError(validation);
      return;
    }
    if (!paymentSettlementId) {
      setPaymentError("请选择结算单");
      return;
    }
    setPaymentSaving(true);
    setPaymentError("");
    setPaymentHint("");
    try {
      const updated = await registerChildServiceSettlementPayment(paymentSettlementId, {
        amount: Number(paymentAmount.trim()),
        method: paymentMethod,
        remark: paymentRemark.trim() === "" ? null : paymentRemark.trim(),
      });
      setPaymentHint(
        `已登记收款；结算单 ${updated.settlement_no} 现为「${updated.status}」（已收 ${formatMoney(updated.paid_amount)} 元）`,
      );
      setPaymentAmount("");
      setPaymentRemark("");
      await loadSettlements();
    } catch (cause) {
      // 超额收款 → 400「payment exceeds outstanding amount: ...」：原样透出
      setPaymentError(errorMessage(cause, "登记收款失败"));
    } finally {
      setPaymentSaving(false);
    }
  }

  // —— 动作：开具发票（票号唯一 409、互斥字段 400 文案透出）——
  async function submitInvoice() {
    const validation = validateInvoiceForm({
      patientId,
      title: invoiceTitle,
      invoiceNo,
      amountText: invoiceAmount,
      settlementId: invoiceSettlementId,
      subscriptionId: invoiceSubscriptionId,
    });
    if (validation) {
      setInvoiceError(validation);
      return;
    }
    setInvoiceSaving(true);
    setInvoiceError("");
    setInvoiceHint("");
    try {
      const created = await createChildServiceInvoice({
        patient_id: patientId,
        title: invoiceTitle.trim(),
        invoice_no: invoiceNo.trim() === "" ? undefined : invoiceNo.trim(),
        amount:
          invoiceSettlementId.trim() === "" && invoiceSubscriptionId.trim() === ""
            ? Number(invoiceAmount.trim())
            : undefined,
        settlement_id: invoiceSettlementId.trim() === "" ? null : invoiceSettlementId.trim(),
        subscription_id: invoiceSubscriptionId.trim() === "" ? null : invoiceSubscriptionId.trim(),
      });
      setInvoiceHint(`已开具发票 ${created.invoice_no}（金额 ${formatMoney(created.amount)} 元，服务端派生）`);
      setInvoiceTitle("");
      setInvoiceNo("");
      setInvoiceAmount("");
      await loadInvoices();
    } catch (cause) {
      // 票号重复 → 409；互斥/金额规则 → 400：服务端文案原样透出
      setInvoiceError(errorMessage(cause, "开具发票失败"));
    } finally {
      setInvoiceSaving(false);
    }
  }

  // —— 动作：发票作废（单向：已开具 → 已作废）——
  async function voidInvoice(invoice: ChildServiceInvoice) {
    setVoidingId(invoice.id);
    setInvoiceError("");
    setInvoiceHint("");
    try {
      await updateChildServiceInvoiceStatus(invoice.id, "已作废");
      setInvoiceHint(`发票 ${invoice.invoice_no} 已作废（单向，不可恢复）`);
      await loadInvoices();
    } catch (cause) {
      // 非法流转 → 400：原样透出
      setInvoiceError(errorMessage(cause, "发票作废失败"));
    } finally {
      setVoidingId("");
    }
  }

  const packageColumns: Column<ChildServicePackage>[] = [
    { key: "code", header: "编码", render: (row) => row.code },
    { key: "name", header: "名称", render: (row) => row.name },
    { key: "price", header: "价格（元）", render: (row) => formatMoney(row.price) },
    { key: "items", header: "明细", render: (row) => <span className="text-xs text-fg-muted">{packageItemsLabel(row.items)}</span> },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={packageStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "updated_at", header: "更新时间", render: (row) => formatDateTime(row.updated_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button size="sm" variant="ghost" loading={togglingPackageId === row.id} onClick={() => void togglePackage(row)}>
          {row.status === "启用" ? "停用" : "启用"}
        </Button>
      ),
    },
  ];

  const subscriptionColumns: Column<ChildPackageSubscription>[] = [
    {
      key: "package",
      header: "套餐",
      render: (row) => (
        <div className="flex flex-col">
          <span className="text-sm text-fg">{row.package_name ?? "-"}</span>
          <span className="text-xs text-fg-dimmed">{row.package_code ?? row.package_id}</span>
        </div>
      ),
    },
    {
      key: "pay_state",
      header: "付费状态",
      render: (row) => <Badge variant={payStateVariant(row.pay_state)}>{payStateLabel(row.pay_state)}</Badge>,
    },
    {
      key: "amounts",
      header: "金额（元）",
      render: (row) => (
        <div className="flex flex-col text-xs">
          <span>总额 {formatMoney(row.amount)}</span>
          <span className="text-fg-muted">已收 {formatMoney(row.paid_amount)}</span>
          <span className="text-fg-muted">已用 {formatMoney(row.used_amount)}</span>
        </div>
      ),
    },
    {
      key: "progress",
      header: "收款 / 耗用进度",
      render: (row) => (
        <div className="flex w-[140px] flex-col gap-1">
          <div className="h-1.5 w-full rounded-full bg-surface-alt">
            <div className="h-1.5 rounded-full bg-success/60" style={{ width: `${subscriptionRatio(row, "paid") * 100}%` }} />
          </div>
          <div className="h-1.5 w-full rounded-full bg-surface-alt">
            <div className="h-1.5 rounded-full bg-accent/60" style={{ width: `${subscriptionRatio(row, "used") * 100}%` }} />
          </div>
          <span className="text-xs text-fg-dimmed">
            收 {formatMoney(row.paid_amount)} / 用 {formatMoney(row.used_amount)}
          </span>
        </div>
      ),
    },
    {
      key: "status",
      header: "订阅状态",
      render: (row) => <Badge variant={subscriptionStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "purchased_at", header: "购买时间", render: (row) => formatDateTime(row.purchased_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          size="sm"
          variant="ghost"
          loading={togglingSubscriptionId === row.id}
          onClick={() => void toggleSubscription(row)}
        >
          {row.status === "启用" ? "停用" : "启用"}
        </Button>
      ),
    },
  ];

  const settlementColumns: Column<ChildServiceSettlement>[] = [
    { key: "settlement_no", header: "结算单号", render: (row) => row.settlement_no },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={settlementStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "summary", header: "金额（服务端计算）", render: (row) => <span className="text-xs">{settlementSummary(row)}</span> },
    {
      key: "items",
      header: "明细",
      render: (row) => (
        <span className="text-xs text-fg-muted">
          {row.items
            .map(
              (item) =>
                `${item.item_name} ${formatMoney(item.unit_price)}×${formatQuantity(item.quantity)}=${formatMoney(item.amount)}`,
            )
            .join("；") || "-"}
        </span>
      ),
    },
    {
      key: "progress",
      header: "收款进度",
      render: (row) => (
        <div className="flex w-[120px] flex-col gap-1">
          <div className="h-1.5 w-full rounded-full bg-surface-alt">
            <div className="h-1.5 rounded-full bg-success/60" style={{ width: `${settlementPaidRatio(row) * 100}%` }} />
          </div>
          <span className="text-xs text-fg-dimmed">已收 {formatMoney(row.paid_amount)}</span>
        </div>
      ),
    },
    {
      key: "subscription",
      header: "关联订阅",
      render: (row) => (row.subscription_id ? row.subscription_id.slice(-6) : "-"),
    },
    {
      key: "actions",
      header: "收款",
      render: (row) => (
        <Button
          size="sm"
          variant={paymentSettlementId === row.id ? "primary" : "ghost"}
          onClick={() => setPaymentSettlementId(row.id)}
          disabled={row.status === "已结清" || row.status === "已作废"}
        >
          选择
        </Button>
      ),
    },
  ];

  const invoiceColumns: Column<ChildServiceInvoice>[] = [
    { key: "invoice_no", header: "票号", render: (row) => row.invoice_no },
    { key: "title", header: "抬头", render: (row) => row.title },
    { key: "amount", header: "金额（元）", render: (row) => formatMoney(row.amount) },
    { key: "source", header: "关联", render: (row) => invoiceSourceLabel(row) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={invoiceStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "issued_at", header: "开具时间", render: (row) => formatDateTime(row.issued_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) =>
        canTransitionInvoice(row.status, "已作废") ? (
          <Button size="sm" variant="ghost" loading={voidingId === row.id} onClick={() => void voidInvoice(row)}>
            作废
          </Button>
        ) : (
          <span className="text-xs text-fg-dimmed">已作废（单向）</span>
        ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`收银工作台（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-patient">
              选择{entity.person}
            </label>
            <select
              id="cashier-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setSubscriptionHint("");
                setSettlementHint("");
                setPaymentHint("");
                setInvoiceHint("");
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
          {selected && <span className="text-sm text-fg-muted">当前 {formatAge(selected.birth_date)}</span>}
          <span className="text-xs text-fg-dimmed">
            订阅付费状态：未付 {subscriptionSummary.未付} / 部分 {subscriptionSummary.部分} / 已付{" "}
            {subscriptionSummary.已付} / 已用完 {subscriptionSummary.已用完}
          </span>
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      {/* ── E1 套餐定义 ─────────────────────────────────────────────── */}
      <Card title="儿保服务套餐定义（E1）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[160px]">
              <Input label="套餐编码" value={packageCode} onChange={(event) => setPackageCode(event.target.value)} />
            </div>
            <div className="min-w-[180px] flex-1">
              <Input label="套餐名称" value={packageName} onChange={(event) => setPackageName(event.target.value)} />
            </div>
            <div className="w-[150px]">
              <Input
                label="价格（元）"
                value={packagePrice}
                placeholder="如 1280.00"
                onChange={(event) => setPackagePrice(event.target.value)}
              />
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-fg-muted">套餐明细</span>
            {packageItems.map((item, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <div className="min-w-[160px] flex-1">
                  <Input
                    label={index === 0 ? "项目编码" : undefined}
                    value={item.item_code}
                    onChange={(event) =>
                      setPackageItems((current) => updateDraft(current, index, { item_code: event.target.value }))
                    }
                  />
                </div>
                <div className="min-w-[180px] flex-1">
                  <Input
                    label={index === 0 ? "项目名称" : undefined}
                    value={item.item_name}
                    onChange={(event) =>
                      setPackageItems((current) => updateDraft(current, index, { item_name: event.target.value }))
                    }
                  />
                </div>
                <div className="w-[120px]">
                  <Input
                    label={index === 0 ? "数量" : undefined}
                    type="number"
                    min={1}
                    value={String(item.quantity)}
                    onChange={(event) =>
                      setPackageItems((current) =>
                        updateDraft(current, index, { quantity: Number(event.target.value) }),
                      )
                    }
                  />
                </div>
                <Button
                  variant="ghost"
                  onClick={() => setPackageItems((current) => removeDraft(current, index))}
                  disabled={packageItems.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
            <div>
              <Button variant="secondary" onClick={() => setPackageItems((current) => addDraft(current, emptyPackageItem()))}>
                添加明细
              </Button>
            </div>
          </div>
          <p className="text-xs text-fg-dimmed">
            套餐编码业务唯一（重复由服务端 409 兜底）；价格非负且最多 2 位小数；明细数量必须为正数。
          </p>
          {packageError && <p className="text-sm text-danger">{packageError}</p>}
          {packageHint && <p className="text-sm text-success">{packageHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitPackage()} loading={packageSaving}>
              创建套餐
            </Button>
          </div>

          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-fg-emphasis">套餐列表（{packages.length}）</span>
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={packageStatusFilter}
              onChange={(event) => setPackageStatusFilter(event.target.value)}
            >
              <option value="">全部状态</option>
              {PACKAGE_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {packagesLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : packages.length === 0 ? (
            <EmptyState icon="🎁" title="暂无套餐定义" description="先定义套餐，再为儿童订阅" />
          ) : (
            <Table columns={packageColumns} data={packages} />
          )}
        </div>
      </Card>

      {/* ── E1 订阅与付费状态四态 ───────────────────────────────────── */}
      <Card title="套餐订阅与付费状态（E1，读时派生四态）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[240px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-package">
                套餐（仅启用）
              </label>
              <select
                id="cashier-package"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={subscriptionPackageId}
                onChange={(event) => setSubscriptionPackageId(event.target.value)}
              >
                <option value="">请选择</option>
                {(activePackages.length > 0 ? activePackages : packages).map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}（{formatMoney(item.price)} 元）
                  </option>
                ))}
              </select>
            </div>
            <Button
              onClick={() => void submitSubscription()}
              loading={subscriptionSaving}
              disabled={!patientId}
            >
              订阅套餐
            </Button>
            <span className="text-xs text-fg-dimmed">
              订阅金额取购买时套餐价快照，付费状态由服务端按「已收/已用 vs 总额」派生
            </span>
          </div>
          {subscriptionError && <p className="text-sm text-danger">{subscriptionError}</p>}
          {subscriptionHint && <p className="text-sm text-success">{subscriptionHint}</p>}
          {subscriptionsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : subscriptions.length === 0 ? (
            <EmptyState icon="💳" title="暂无套餐订阅" description="订阅后在此按付费状态四态可视化" />
          ) : (
            <Table columns={subscriptionColumns} data={sortSubscriptions(subscriptions)} />
          )}
        </div>
      </Card>

      {/* ── E2 结算单 ───────────────────────────────────────────────── */}
      <Card title="儿保服务结算单（E2，金额服务端计算）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[220px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-settlement-subscription">
                关联套餐订阅（可选）
              </label>
              <select
                id="cashier-settlement-subscription"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={settlementSubscriptionId}
                onChange={(event) => setSettlementSubscriptionId(event.target.value)}
              >
                <option value="">不关联</option>
                {subscriptions.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.package_name ?? item.package_id}（{item.pay_state}）
                  </option>
                ))}
              </select>
            </div>
            <span className="text-xs text-fg-dimmed">
              明细金额 = 单价 × 数量（HALF_UP 到分），合计 = 明细之和，均由服务端计算
            </span>
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-fg-muted">结算明细</span>
            {settlementItems.map((item, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <div className="min-w-[150px] flex-1">
                  <Input
                    label={index === 0 ? "项目编码" : undefined}
                    value={item.item_code}
                    onChange={(event) =>
                      setSettlementItems((current) => updateDraft(current, index, { item_code: event.target.value }))
                    }
                  />
                </div>
                <div className="min-w-[170px] flex-1">
                  <Input
                    label={index === 0 ? "项目名称" : undefined}
                    value={item.item_name}
                    onChange={(event) =>
                      setSettlementItems((current) => updateDraft(current, index, { item_name: event.target.value }))
                    }
                  />
                </div>
                <div className="w-[140px]">
                  <Input
                    label={index === 0 ? "单价（元）" : undefined}
                    value={item.unit_price}
                    onChange={(event) =>
                      setSettlementItems((current) => updateDraft(current, index, { unit_price: event.target.value }))
                    }
                  />
                </div>
                <div className="w-[120px]">
                  <Input
                    label={index === 0 ? "数量" : undefined}
                    value={item.quantity}
                    onChange={(event) =>
                      setSettlementItems((current) => updateDraft(current, index, { quantity: event.target.value }))
                    }
                  />
                </div>
                <Button
                  variant="ghost"
                  onClick={() => setSettlementItems((current) => removeDraft(current, index))}
                  disabled={settlementItems.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
            <div>
              <Button
                variant="secondary"
                onClick={() => setSettlementItems((current) => addDraft(current, emptySettlementItem()))}
              >
                添加明细
              </Button>
            </div>
          </div>
          <p className="text-xs text-fg-dimmed">
            本页不预填金额：合计与明细金额提交后由服务端计算并回显，避免前后端两套口径。
          </p>
          {settlementError && <p className="text-sm text-danger">{settlementError}</p>}
          {settlementHint && <p className="text-sm text-success">{settlementHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitSettlement()} loading={settlementSaving} disabled={!patientId}>
              创建结算单
            </Button>
          </div>

          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-fg-emphasis">结算单（{settlements.length}）</span>
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={settlementStatusFilter}
              onChange={(event) => setSettlementStatusFilter(event.target.value)}
            >
              <option value="">全部状态</option>
              {SETTLEMENT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {settlementsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : settlements.length === 0 ? (
            <EmptyState icon="🧾" title="暂无结算单" description="创建结算单后即可登记收款" />
          ) : (
            <Table columns={settlementColumns} data={settlements} />
          )}
        </div>
      </Card>

      {/* ── E2 收款登记 ─────────────────────────────────────────────── */}
      <Card title="收款登记（E2）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[260px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-payment-settlement">
                结算单（仅未结清）
              </label>
              <select
                id="cashier-payment-settlement"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={paymentSettlementId}
                onChange={(event) => setPaymentSettlementId(event.target.value)}
              >
                <option value="">请选择</option>
                {payableSettlements.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.settlement_no}（{item.status} · 未收 {formatMoney(settlementOutstanding(item))} 元）
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[160px]">
              <Input
                label="收款金额（元）"
                value={paymentAmount}
                onChange={(event) => setPaymentAmount(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-payment-method">
                收款方式
              </label>
              <select
                id="cashier-payment-method"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={paymentMethod}
                onChange={(event) => setPaymentMethod(event.target.value)}
              >
                {PAYMENT_METHODS.map((method) => (
                  <option key={method} value={method}>
                    {method}
                  </option>
                ))}
              </select>
            </div>
            <div className="min-w-[180px] flex-1">
              <Input label="备注（可选）" value={paymentRemark} onChange={(event) => setPaymentRemark(event.target.value)} />
            </div>
            <Button onClick={() => void submitPayment()} loading={paymentSaving} disabled={!paymentSettlementId}>
              登记收款
            </Button>
          </div>
          {selectedSettlement && (
            <p className="text-xs text-fg-muted">
              当前结算单：{settlementSummary(selectedSettlement)}；单笔收款不得超过未收金额（超额由服务端 400 兜底）。
            </p>
          )}
          {paymentError && <p className="text-sm text-danger">{paymentError}</p>}
          {paymentHint && <p className="text-sm text-success">{paymentHint}</p>}
        </div>
      </Card>

      {/* ── E3 发票 ─────────────────────────────────────────────────── */}
      <Card title="儿保发票开具与台账（E3）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[200px] flex-1">
              <Input label="发票抬头" value={invoiceTitle} onChange={(event) => setInvoiceTitle(event.target.value)} />
            </div>
            <div className="w-[200px]">
              <Input
                label="票号（可选，缺省服务端生成）"
                value={invoiceNo}
                onChange={(event) => setInvoiceNo(event.target.value)}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="金额（元，手动）"
                value={invoiceAmount}
                placeholder="关联单据时留空"
                onChange={(event) => setInvoiceAmount(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5 min-w-[220px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-invoice-settlement">
                关联结算单（可选）
              </label>
              <select
                id="cashier-invoice-settlement"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={invoiceSettlementId}
                onChange={(event) => {
                  setInvoiceSettlementId(event.target.value);
                  if (event.target.value) setInvoiceSubscriptionId("");
                }}
              >
                <option value="">不关联</option>
                {settlements.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.settlement_no}（{formatMoney(item.total_amount)} 元）
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5 min-w-[220px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="cashier-invoice-subscription">
                关联套餐订阅（可选）
              </label>
              <select
                id="cashier-invoice-subscription"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={invoiceSubscriptionId}
                onChange={(event) => {
                  setInvoiceSubscriptionId(event.target.value);
                  if (event.target.value) setInvoiceSettlementId("");
                }}
              >
                <option value="">不关联</option>
                {subscriptions.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.package_name ?? item.package_id}（{formatMoney(item.amount)} 元）
                  </option>
                ))}
              </select>
            </div>
            <Button onClick={() => void submitInvoice()} loading={invoiceSaving} disabled={!patientId}>
              开具发票
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            结算单与订阅互斥；关联其一时金额由服务端按该单派生（再填金额会被服务端 400 拒绝）；
            两者都不关联时必须填写正数金额。票号唯一（重复 409）、状态单向（已开具 → 已作废）。
          </p>
          {invoiceError && <p className="text-sm text-danger">{invoiceError}</p>}
          {invoiceHint && <p className="text-sm text-success">{invoiceHint}</p>}

          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-fg-emphasis">发票台账（{invoices.length}）</span>
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={invoiceStatusFilter}
              onChange={(event) => setInvoiceStatusFilter(event.target.value)}
            >
              <option value="">全部状态</option>
              {INVOICE_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {invoicesLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : invoices.length === 0 ? (
            <EmptyState icon="📄" title="暂无发票" description="开具后在此查看与作废" />
          ) : (
            <Table columns={invoiceColumns} data={invoices} />
          )}
        </div>
      </Card>
    </div>
  );
}
