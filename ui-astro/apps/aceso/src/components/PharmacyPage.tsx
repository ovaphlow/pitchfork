import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import PurchaseOrdersSection from "./PurchaseOrdersSection";
import RequisitionsSection from "./RequisitionsSection";
import {
  cancelPharmacyDispense,
  cancelPharmacyReturn,
  confirmPharmacyDispense,
  confirmPharmacyReturn,
  createPharmacyReturnFromDispense,
  createPharmacyDispenseFromMedicalOrder,
  getPharmacyDispense,
  listActiveElderlyAdmissions,
  listIdentitySubjects,
  listInventoryStocks,
  listPatients,
  listPharmacyDispenses,
  listPharmacyMedicationOrders,
  listPharmacyReturns,
  listWarehouseOptions,
  reviewPharmacyDispense,
  startPharmacyDispense,
  type Encounter,
  type IdentitySubject,
  type InventoryStockAvailability,
  type Patient,
  type PharmacyDispense,
  type PharmacyMedicationOrder,
  type PharmacyReturn,
  type WarehouseOption,
} from "@pitchfork/shared/aceso";

type Tab = "orders" | "dispenses" | "returns" | "requisitions" | "purchase";

interface ActiveAdmission extends Encounter {
  patientName: string;
}

interface DispenseForm {
  warehouse: string;
  stockId: string;
  materialId: string;
  lotId: string;
  quantity: string;
  operator: string;
}

interface ReturnForm {
  itemId: string;
  quantity: string;
  reason: string;
  operator: string;
  remark: string;
}

const DISPENSE_STATUS: Record<string, { label: string; variant: "default" | "success" | "warning" | "danger" | "info" }> = {
  PENDING: { label: "待审方", variant: "default" },
  REVIEWED: { label: "已审方", variant: "info" },
  DISPENSING: { label: "调配中", variant: "warning" },
  DISPENSED: { label: "已发药", variant: "success" },
  CANCELLED: { label: "已取消", variant: "danger" },
};

const RETURN_STATUS: Record<string, { label: string; variant: "default" | "success" | "warning" | "danger" | "info" }> = {
  PENDING: { label: "待确认", variant: "warning" },
  CONFIRMED: { label: "已入库", variant: "success" },
  CANCELLED: { label: "已取消", variant: "danger" },
};

const DISPENSE_TYPE_LABEL: Record<string, string> = {
  ELDERLY_ROUTINE: "养老常规发药",
  OUTPATIENT: "门诊发药",
  INPATIENT: "住院发药",
  WARD_BATCH: "病区批量发药",
};

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function statusBadge(status: string | null | undefined): React.ReactNode {
  if (!status) return <span className="text-fg-dimmed">—</span>;
  const meta = DISPENSE_STATUS[status] ?? { label: status, variant: "default" as const };
  return <Badge variant={meta.variant}>{meta.label}</Badge>;
}

function returnStatusBadge(status: string | null | undefined): React.ReactNode {
  if (!status) return <span className="text-fg-dimmed">—</span>;
  const meta = RETURN_STATUS[status] ?? { label: status, variant: "default" as const };
  return <Badge variant={meta.variant}>{meta.label}</Badge>;
}

export default function PharmacyPage() {
  const [activeTab, setActiveTab] = useState<Tab>("orders");

  // ── 人员与入住（操作人下拉、发药单老人名映射）─────────────────────
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);
  const [admissions, setAdmissions] = useState<ActiveAdmission[]>([]);

  // ── 待接方用药医嘱 ────────────────────────────────────────────────
  const [orders, setOrders] = useState<PharmacyMedicationOrder[]>([]);
  const [ordersTotal, setOrdersTotal] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState("");
  const [encounterFilter, setEncounterFilter] = useState("");
  const [search, setSearch] = useState("");
  const [ordersOffset, setOrdersOffset] = useState(0);
  const ORDERS_PAGE = 50;

  // ── 发药单列表 ────────────────────────────────────────────────────
  const [dispenses, setDispenses] = useState<PharmacyDispense[]>([]);
  const [dispensesTotal, setDispensesTotal] = useState(0);
  const [dispensesLoading, setDispensesLoading] = useState(false);
  const [dispensesError, setDispensesError] = useState("");
  const [dispenseStatusFilter, setDispenseStatusFilter] = useState("");
  const [dispensesOffset, setDispensesOffset] = useState(0);
  const DISPENSES_PAGE = 50;

  // ── 退药单列表 ────────────────────────────────────────────────────
  const [returns, setReturns] = useState<PharmacyReturn[]>([]);
  const [returnsTotal, setReturnsTotal] = useState(0);
  const [returnsLoading, setReturnsLoading] = useState(false);
  const [returnsError, setReturnsError] = useState("");
  const [returnStatusFilter, setReturnStatusFilter] = useState("");
  const [returnsOffset, setReturnsOffset] = useState(0);
  const RETURNS_PAGE = 50;

  // ── 创建发药单弹窗 ────────────────────────────────────────────────
  const [createTarget, setCreateTarget] = useState<PharmacyMedicationOrder | null>(null);
  const [createForm, setCreateForm] = useState<DispenseForm>({ warehouse: "", stockId: "", materialId: "", lotId: "", quantity: "1", operator: "" });
  const [createError, setCreateError] = useState("");
  const [createSaving, setCreateSaving] = useState(false);
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [stocksLoading, setStocksLoading] = useState(false);

  // ── 操作弹窗（审方/调配/确认/取消） ───────────────────────────────
  const [actionTarget, setActionTarget] = useState<PharmacyDispense | null>(null);
  const [actionKind, setActionKind] = useState<"" | "review" | "start" | "confirm" | "cancel">("");
  const [actionOperator, setActionOperator] = useState("");
  const [actionRemark, setActionRemark] = useState("");
  const [actionError, setActionError] = useState("");
  const [actionSaving, setActionSaving] = useState(false);

  // ── 详情弹窗 ──────────────────────────────────────────────────────
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<PharmacyDispense | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");

  // ── 创建退药单弹窗 ────────────────────────────────────────────────
  const [returnTarget, setReturnTarget] = useState<PharmacyDispense | null>(null);
  const [returnForm, setReturnForm] = useState<ReturnForm>({ itemId: "", quantity: "1", reason: "老人未使用", operator: "", remark: "" });
  const [returnError, setReturnError] = useState("");
  const [returnSaving, setReturnSaving] = useState(false);
  const [returnAction, setReturnAction] = useState<PharmacyReturn | null>(null);
  const [returnActionKind, setReturnActionKind] = useState<"confirm" | "cancel" | "">("");
  const [returnActionOperator, setReturnActionOperator] = useState("");
  const [returnActionError, setReturnActionError] = useState("");
  const [returnActionSaving, setReturnActionSaving] = useState(false);

  const encounterName = useMemo(() => {
    const map = new Map<string, string>();
    for (const admission of admissions) map.set(admission.id, admission.patientName);
    return map;
  }, [admissions]);

  const patientName = useMemo(() => {
    const map = new Map<string, string>();
    for (const admission of admissions) map.set(admission.patient_id, admission.patientName);
    return map;
  }, [admissions]);

  const materialUnit = useMemo(() => {
    const map = new Map<string, string>();
    for (const stock of stocks) map.set(stock.material_id, stock.unit);
    return map;
  }, [stocks]);

  const loadSubjects = useCallback(async () => {
    try {
      const response = await listIdentitySubjects(1, 100);
      setSubjects(response.records);
    } catch {
      // 操作人下拉失败不影响页面主体
    }
  }, []);

  const loadAdmissions = useCallback(async () => {
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 200 }),
        listActiveElderlyAdmissions({ limit: 200 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient: Patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      setAdmissions(records);
    } catch {
      // 筛选下拉加载失败不阻塞列表
    }
  }, []);

  const loadOrders = useCallback(async () => {
    setOrdersLoading(true);
    setOrdersError("");
    try {
      const response = await listPharmacyMedicationOrders({
        encounter_id: encounterFilter || undefined,
        search: search.trim() || undefined,
        limit: ORDERS_PAGE,
        offset: ordersOffset,
      });
      setOrders(response.records);
      setOrdersTotal(response.meta.total);
    } catch (error) {
      setOrders([]);
      setOrdersError(errorMessage(error, "无法加载待接方用药医嘱"));
    } finally {
      setOrdersLoading(false);
    }
  }, [encounterFilter, search, ordersOffset]);

  const loadDispenses = useCallback(async () => {
    setDispensesLoading(true);
    setDispensesError("");
    try {
      const response = await listPharmacyDispenses({
        dispense_type: "ELDERLY_ROUTINE",
        status: dispenseStatusFilter || undefined,
        limit: DISPENSES_PAGE,
        offset: dispensesOffset,
      });
      setDispenses(response.records);
      setDispensesTotal(response.meta.total);
    } catch (error) {
      setDispenses([]);
      setDispensesError(errorMessage(error, "无法加载发药单"));
    } finally {
      setDispensesLoading(false);
    }
  }, [dispenseStatusFilter, dispensesOffset]);

  const loadReturns = useCallback(async () => {
    setReturnsLoading(true);
    setReturnsError("");
    try {
      const response = await listPharmacyReturns({
        status: returnStatusFilter || undefined,
        limit: RETURNS_PAGE,
        offset: returnsOffset,
      });
      setReturns(response.records);
      setReturnsTotal(response.meta.total);
    } catch (error) {
      setReturns([]);
      setReturnsError(errorMessage(error, "无法加载退药单"));
    } finally {
      setReturnsLoading(false);
    }
  }, [returnStatusFilter, returnsOffset]);

  useEffect(() => {
    void loadSubjects();
    void loadAdmissions();
  }, [loadSubjects, loadAdmissions]);

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  useEffect(() => {
    if (activeTab === "dispenses") void loadDispenses();
  }, [activeTab, loadDispenses]);

  useEffect(() => {
    if (activeTab === "returns") void loadReturns();
  }, [activeTab, loadReturns]);

  // ── 创建发药单 ────────────────────────────────────────────────────

  const openCreate = (order: PharmacyMedicationOrder) => {
    setCreateTarget(order);
    setCreateForm({ warehouse: "", stockId: "", materialId: "", lotId: "", quantity: "1", operator: "" });
    setCreateError("");
    void loadWarehouses();
  };

  const loadWarehouses = useCallback(async () => {
    try {
      setWarehouses(await listWarehouseOptions());
    } catch (error) {
      setCreateError(errorMessage(error, "无法加载仓库列表"));
    }
  }, []);

  const loadStocks = useCallback(async (warehouse: string) => {
    if (!warehouse) {
      setStocks([]);
      return;
    }
    setStocksLoading(true);
    try {
      const response = await listInventoryStocks({ warehouse, limit: 200 });
      setStocks(response.records.filter((stock) => Number(stock.available_quantity) > 0));
    } catch (error) {
      setStocks([]);
      setCreateError(errorMessage(error, "无法加载可用库存"));
    } finally {
      setStocksLoading(false);
    }
  }, []);

  const selectedStock = useMemo(
    () => stocks.find((stock) => stock.id === createForm.stockId),
    [stocks, createForm.stockId],
  );

  const createAvailableQuantity = Number(selectedStock?.available_quantity ?? "0");

  const handleCreateDispense = async () => {
    if (!createTarget) return;
    const quantity = Number(createForm.quantity);
    if (!createForm.operator.trim()) {
      setCreateError("请选择操作人");
      return;
    }
    if (!createForm.materialId) {
      setCreateError("请选择药品物资");
      return;
    }
    if (!quantity || quantity <= 0 || quantity > createAvailableQuantity) {
      setCreateError(`数量必须大于 0 且不超过可用库存（当前可用 ${createAvailableQuantity}）`);
      return;
    }
    setCreateSaving(true);
    setCreateError("");
    try {
      await createPharmacyDispenseFromMedicalOrder({
        medical_order_id: createTarget.order_id,
        warehouse: createForm.warehouse,
        material_id: createForm.materialId,
        ...(createForm.lotId ? { lot_id: createForm.lotId } : {}),
        dispensed_quantity: createForm.quantity.trim(),
      });
      setCreateTarget(null);
      void loadOrders();
      void loadDispenses();
    } catch (error) {
      setCreateError(errorMessage(error, "创建发药单失败"));
    } finally {
      setCreateSaving(false);
    }
  };

  // ── 状态流转 ──────────────────────────────────────────────────────

  const openAction = (dispense: PharmacyDispense, kind: "review" | "start" | "confirm" | "cancel") => {
    setActionTarget(dispense);
    setActionKind(kind);
    setActionOperator("");
    setActionRemark("");
    setActionError("");
  };

  const handleAction = async () => {
    if (!actionTarget || !actionKind) return;
    if (!actionOperator.trim()) {
      setActionError("请选择操作人");
      return;
    }
    setActionSaving(true);
    setActionError("");
    try {
      const input = { operator: actionOperator.trim(), ...(actionRemark.trim() ? { remark: actionRemark.trim() } : {}) };
      if (actionKind === "review") await reviewPharmacyDispense(actionTarget.id, input);
      if (actionKind === "start") await startPharmacyDispense(actionTarget.id, input);
      if (actionKind === "confirm") await confirmPharmacyDispense(actionTarget.id, input);
      if (actionKind === "cancel") await cancelPharmacyDispense(actionTarget.id, input);
      setActionTarget(null);
      setActionKind("");
      void loadDispenses();
    } catch (error) {
      setActionError(errorMessage(error, "操作失败"));
    } finally {
      setActionSaving(false);
    }
  };

  // ── 详情 ──────────────────────────────────────────────────────────

  const openDetail = async (dispense: PharmacyDispense) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetailError("");
    setDetail(dispense);
    try {
      setDetail(await getPharmacyDispense(dispense.id));
    } catch (error) {
      setDetailError(errorMessage(error, "无法加载发药单详情"));
    } finally {
      setDetailLoading(false);
    }
  };

  const openReturn = (dispense: PharmacyDispense, itemId?: string) => {
    const firstItem = dispense.items[0];
    const selectedItemId = itemId ?? firstItem?.id ?? "";
    const selectedItem = dispense.items.find((item) => item.id === selectedItemId);
    setReturnTarget(dispense);
    setReturnForm({
      itemId: selectedItemId,
      quantity: String(selectedItem?.dispensed_quantity ?? firstItem?.dispensed_quantity ?? 1),
      reason: "老人未使用",
      operator: "",
      remark: "",
    });
    setReturnError("");
  };

  const handleCreateReturn = async () => {
    if (!returnTarget) return;
    const quantity = Number(returnForm.quantity);
    if (!returnForm.itemId) return setReturnError("请选择退药明细");
    if (!returnForm.reason.trim()) return setReturnError("请填写退药原因");
    if (!returnForm.operator.trim()) return setReturnError("请选择操作人");
    const selectedItem = returnTarget.items.find((item) => item.id === returnForm.itemId);
    if (!selectedItem) return setReturnError("请选择退药明细");
    if (!quantity || quantity <= 0 || (selectedItem.dispensed_quantity != null && quantity > Number(selectedItem.dispensed_quantity))) {
      return setReturnError("退药数量必须大于 0 且不超过原发药数量");
    }
    setReturnSaving(true);
    setReturnError("");
    try {
      await createPharmacyReturnFromDispense({
        dispense_id: returnTarget.id,
        dispense_item_id: returnForm.itemId,
        quantity: returnForm.quantity.trim(),
        return_reason: returnForm.reason.trim(),
        operator: returnForm.operator.trim(),
        restockable: true,
        ...(returnForm.remark.trim() ? { remark: returnForm.remark.trim() } : {}),
      });
      setReturnTarget(null);
      setActiveTab("returns");
      void loadReturns();
    } catch (error) {
      setReturnError(errorMessage(error, "创建退药单失败"));
    } finally {
      setReturnSaving(false);
    }
  };

  const openReturnAction = (item: PharmacyReturn, kind: "confirm" | "cancel") => {
    setReturnAction(item);
    setReturnActionKind(kind);
    setReturnActionOperator("");
    setReturnActionError("");
  };

  const handleReturnAction = async () => {
    if (!returnAction || !returnActionKind) return;
    if (returnActionKind === "confirm" && !returnActionOperator.trim()) {
      setReturnActionError("请选择操作人");
      return;
    }
    setReturnActionSaving(true);
    setReturnActionError("");
    try {
      if (returnActionKind === "confirm") await confirmPharmacyReturn(returnAction.id, returnActionOperator.trim());
      else await cancelPharmacyReturn(returnAction.id);
      setReturnAction(null);
      setReturnActionKind("");
      void loadReturns();
    } catch (error) {
      setReturnActionError(errorMessage(error, "退药操作失败"));
    } finally {
      setReturnActionSaving(false);
    }
  };

  // ── 列定义 ────────────────────────────────────────────────────────

  const orderColumns: Column<PharmacyMedicationOrder>[] = [
    {
      key: "patient",
      header: "老人",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.patient_name}</div>
          <div className="text-xs text-fg-dimmed">{row.encounter_no || row.encounter_id}</div>
        </div>
      ),
    },
    {
      key: "drug",
      header: "药品",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.drug_name || "—"}</div>
          <div className="text-xs text-fg-dimmed">{row.order_content}</div>
        </div>
      ),
    },
    {
      key: "usage",
      header: "用法",
      render: (row) => (
        <div className="text-sm">
          <div className="text-fg">{row.dose ? `${row.dose}${row.unit ?? ""}` : "—"}</div>
          <div className="text-xs text-fg-dimmed">
            {[row.route, row.frequency_name || row.frequency_code].filter(Boolean).join(" · ") || "—"}
          </div>
        </div>
      ),
    },
    {
      key: "period",
      header: "医嘱周期",
      render: (row) => (
        <div className="text-sm text-fg-muted">
          {formatDate(row.start_time)} 至 {formatDate(row.end_time)}
        </div>
      ),
    },
    {
      key: "doctor",
      header: "医生",
      render: (row) => <span className="text-fg-muted">{row.doctor || "—"}</span>,
    },
    {
      key: "nurse_check",
      header: "护士核对",
      render: (row) =>
        row.nurse_checked_by && row.nurse_checked_at ? (
          <div className="text-sm">
            <div className="text-fg">{row.nurse_checked_by}</div>
            <div className="text-xs text-fg-dimmed">{formatDateTime(row.nurse_checked_at)}</div>
          </div>
        ) : (
          <span className="text-xs text-fg-dimmed">未核对</span>
        ),
    },
    {
      key: "status",
      header: "接方状态",
      render: (row) => (row.dispense_id ? statusBadge(row.dispense_status) : <Badge>未接方</Badge>),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) =>
        row.dispense_id ? (
          <span className="text-xs text-fg-dimmed">已接方</span>
        ) : (
          <Button size="sm" variant="primary" onClick={() => openCreate(row)}>
            创建发药单
          </Button>
        ),
    },
  ];

  const dispenseColumns: Column<PharmacyDispense>[] = [
    {
      key: "dispense_no",
      header: "发药单号",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.dispense_no}</div>
          <div className="text-xs text-fg-dimmed">{formatDateTime(row.created_at)}</div>
        </div>
      ),
    },
    {
      key: "patient",
      header: "老人",
      render: (row) => (
        <div>
          <div className="text-fg">{encounterName.get(row.encounter_id ?? "") ?? row.patient_id}</div>
          <div className="text-xs text-fg-dimmed">
            {row.metadata?.drug_name ? String(row.metadata.drug_name) : DISPENSE_TYPE_LABEL[row.dispense_type] ?? row.dispense_type}
          </div>
        </div>
      ),
    },
    {
      key: "status",
      header: "状态",
      render: (row) => statusBadge(row.status),
    },
    {
      key: "warehouse",
      header: "仓库",
      render: (row) => <span className="text-fg-muted">{row.warehouse || "—"}</span>,
    },
    {
      key: "operators",
      header: "操作人",
      render: (row) => (
        <div className="text-sm text-fg-muted">
          {row.reviewer ? `审方 ${row.reviewer}` : "—"}
          {row.pharmacist ? <div>调配 {row.pharmacist}</div> : null}
        </div>
      ),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex flex-wrap gap-2">
          {row.status === "PENDING" && (
            <Button size="sm" onClick={() => openAction(row, "review")}>审方</Button>
          )}
          {row.status === "REVIEWED" && (
            <Button size="sm" onClick={() => openAction(row, "start")}>开始调配</Button>
          )}
          {row.status === "DISPENSING" && (
            <Button size="sm" onClick={() => openAction(row, "confirm")}>发药确认</Button>
          )}
          {(row.status === "PENDING" || row.status === "REVIEWED" || row.status === "DISPENSING") && (
            <Button size="sm" variant="danger" onClick={() => openAction(row, "cancel")}>取消</Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => void openDetail(row)}>详情</Button>
        </div>
      ),
    },
  ];

  const returnColumns: Column<PharmacyReturn>[] = [
    {
      key: "return_no",
      header: "退药单号",
      render: (row) => <div><div className="font-medium text-fg-emphasis">{row.return_no}</div><div className="text-xs text-fg-dimmed">{formatDateTime(row.created_at)}</div></div>,
    },
    {
      key: "patient",
      header: "老人",
      render: (row) => <span className="text-fg">{patientName.get(row.patient_id) ?? row.patient_id}</span>,
    },
    { key: "reason", header: "退药原因", render: (row) => <span className="text-fg-muted">{row.return_reason || "—"}</span> },
    { key: "quantity", header: "数量", render: (row) => <span className="text-fg-muted">{row.total_quantity ?? "—"}</span> },
    { key: "status", header: "状态", render: (row) => returnStatusBadge(row.status) },
    { key: "operator", header: "操作人", render: (row) => <span className="text-fg-muted">{row.operator || "—"}</span> },
    {
      key: "actions",
      header: "操作",
      render: (row) => <div className="flex flex-wrap gap-2">
        {row.status === "PENDING" && <>
          <Button size="sm" onClick={() => openReturnAction(row, "confirm")}>确认入库</Button>
          <Button size="sm" variant="danger" onClick={() => openReturnAction(row, "cancel")}>取消</Button>
        </>}
      </div>,
    },
  ];

  const actionTitle =
    actionKind === "review" ? "审方" : actionKind === "start" ? "开始调配" : actionKind === "confirm" ? "发药确认" : "取消发药单";

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">药房管理</h2>
        <p className="text-sm text-fg-muted mt-1">养老常规发药：接方 → 审方 → 调配 → 发药确认</p>
      </div>

      <div className="flex gap-1 border-b border-border">
        {(
          [
            { key: "orders", label: "待接方用药医嘱" },
            { key: "dispenses", label: "发药单" },
            { key: "returns", label: "退药单" },
            { key: "requisitions", label: "护理站申领" },
            { key: "purchase", label: "采购收货" },
          ] as Array<{ key: Tab; label: string }>
        ).map((tab) => (
          <button
            key={tab.key}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
              activeTab === tab.key
                ? "border-accent text-fg-emphasis"
                : "border-transparent text-fg-muted hover:text-fg"
            }`}
            onClick={() => setActiveTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === "orders" && (
        <Card
          className="min-w-0 overflow-hidden"
          title="待接方用药医嘱"
          actions={
            <div className="flex flex-wrap items-center gap-2">
              <select
                className={selectClass}
                value={encounterFilter}
                onChange={(event) => {
                  setEncounterFilter(event.target.value);
                  setOrdersOffset(0);
                }}
              >
                <option value="">全部活动入住</option>
                {admissions.map((admission) => (
                  <option key={admission.id} value={admission.id}>
                    {admission.patientName} · {admission.encounter_no}
                  </option>
                ))}
              </select>
              <Input
                placeholder="搜索药名 / 医嘱内容 / 老人"
                value={search}
                onChange={(event) => {
                  setSearch(event.target.value);
                  setOrdersOffset(0);
                }}
                className="w-56"
              />
            </div>
          }
        >
          {ordersError ? (
            <p className="py-10 text-center text-sm text-danger">{ordersError}</p>
          ) : orders.length === 0 && !ordersLoading ? (
            <EmptyState
              icon="💊"
              title="暂无待接方用药医嘱"
              description="仅展示已由护士核对的用药医嘱；未核对医嘱在护理工作台核对后才可见。"
            />
          ) : (
            <Table
              className="min-w-[900px]"
              columns={orderColumns}
              data={orders}
              loading={ordersLoading}
              emptyMessage="暂无匹配的待接方用药医嘱"
            />
          )}
          {ordersTotal > ORDERS_PAGE && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-border">
              <span className="text-xs text-fg-dimmed">
                共 {ordersTotal} 条 · 第 {Math.floor(ordersOffset / ORDERS_PAGE) + 1} 页
              </span>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={ordersOffset === 0} onClick={() => setOrdersOffset((value) => Math.max(0, value - ORDERS_PAGE))}>
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={ordersOffset + ORDERS_PAGE >= ordersTotal}
                  onClick={() => setOrdersOffset((value) => value + ORDERS_PAGE)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {activeTab === "dispenses" && (
        <Card
          className="min-w-0 overflow-hidden"
          title="发药单"
          actions={
            <select
              className={selectClass}
              value={dispenseStatusFilter}
              onChange={(event) => {
                setDispenseStatusFilter(event.target.value);
                setDispensesOffset(0);
              }}
            >
              <option value="">全部状态</option>
              {Object.entries(DISPENSE_STATUS).map(([key, meta]) => (
                <option key={key} value={key}>
                  {meta.label}
                </option>
              ))}
            </select>
          }
        >
          {dispensesError ? (
            <p className="py-10 text-center text-sm text-danger">{dispensesError}</p>
          ) : dispenses.length === 0 && !dispensesLoading ? (
            <EmptyState icon="🧾" title="暂无发药单" description="从待接方用药医嘱创建发药单后在此流转状态。" />
          ) : (
            <Table
              className="min-w-[900px]"
              columns={dispenseColumns}
              data={dispenses}
              loading={dispensesLoading}
              emptyMessage="暂无匹配的发药单"
            />
          )}
          {dispensesTotal > DISPENSES_PAGE && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-border">
              <span className="text-xs text-fg-dimmed">
                共 {dispensesTotal} 条 · 第 {Math.floor(dispensesOffset / DISPENSES_PAGE) + 1} 页
              </span>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={dispensesOffset === 0} onClick={() => setDispensesOffset((value) => Math.max(0, value - DISPENSES_PAGE))}>
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={dispensesOffset + DISPENSES_PAGE >= dispensesTotal}
                  onClick={() => setDispensesOffset((value) => value + DISPENSES_PAGE)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {activeTab === "requisitions" && <RequisitionsSection />}

      {activeTab === "purchase" && <PurchaseOrdersSection />}

      {activeTab === "returns" && (
        <Card
          className="min-w-0 overflow-hidden"
          title="退药单"
          actions={
            <select
              className={selectClass}
              value={returnStatusFilter}
              onChange={(event) => {
                setReturnStatusFilter(event.target.value);
                setReturnsOffset(0);
              }}
            >
              <option value="">全部状态</option>
              {Object.entries(RETURN_STATUS).map(([key, meta]) => <option key={key} value={key}>{meta.label}</option>)}
            </select>
          }
        >
          {returnsError ? (
            <p className="py-10 text-center text-sm text-danger">{returnsError}</p>
          ) : returns.length === 0 && !returnsLoading ? (
            <EmptyState icon="↩️" title="暂无退药单" description="从已发药单详情创建退药，确认后包装将回到原仓库。" />
          ) : (
            <Table
              className="min-w-[900px]"
              columns={returnColumns}
              data={returns}
              loading={returnsLoading}
              emptyMessage="暂无匹配的退药单"
            />
          )}
          {returnsTotal > RETURNS_PAGE && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-border">
              <span className="text-xs text-fg-dimmed">共 {returnsTotal} 条 · 第 {Math.floor(returnsOffset / RETURNS_PAGE) + 1} 页</span>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={returnsOffset === 0} onClick={() => setReturnsOffset((value) => Math.max(0, value - RETURNS_PAGE))}>上一页</Button>
                <Button size="sm" variant="secondary" disabled={returnsOffset + RETURNS_PAGE >= returnsTotal} onClick={() => setReturnsOffset((value) => value + RETURNS_PAGE)}>下一页</Button>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* ── 创建退药单弹窗 ─────────────────────────────────────────── */}
      <Modal open={returnTarget !== null} onClose={() => setReturnTarget(null)} title="创建退药单">
        {returnTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="font-medium text-fg-emphasis">{returnTarget.dispense_no} · {patientName.get(returnTarget.patient_id) ?? returnTarget.patient_id}</div>
              <div className="text-xs text-fg-dimmed">退药确认后，基础数量将退回原仓库并增加库存；物资、批次和成本由原发药记录锁定。</div>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="return-item">退药明细</label>
              <select
                id="return-item"
                className={selectClass}
                value={returnForm.itemId}
                onChange={(event) => {
                  const item = returnTarget.items.find((candidate) => candidate.id === event.target.value);
                  setReturnForm((current) => ({ ...current, itemId: event.target.value, quantity: String(item?.dispensed_quantity ?? 1) }));
                }}
              >
                <option value="">请选择发药明细</option>
                {returnTarget.items.map((item) => (
                  <option key={item.id} value={item.id}>{item.material_id || "药品"} · 批次 {item.lot_id || "无"} · 原发 {item.dispensed_quantity ?? "—"}</option>
                ))}
              </select>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Input label="退药数量" type="number" min={1} value={returnForm.quantity} onChange={(event) => setReturnForm((current) => ({ ...current, quantity: event.target.value }))} />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="return-operator">操作人</label>
                <select id="return-operator" className={selectClass} value={returnForm.operator} onChange={(event) => setReturnForm((current) => ({ ...current, operator: event.target.value }))}>
                  <option value="">请选择操作人</option>
                  {subjects.map((subject) => <option key={subject.id} value={subject.display_name}>{subject.display_name}</option>)}
                </select>
              </div>
            </div>
            <Input label="退药原因" value={returnForm.reason} onChange={(event) => setReturnForm((current) => ({ ...current, reason: event.target.value }))} placeholder="例如：老人未使用" />
            <Input label="备注（可选）" value={returnForm.remark} onChange={(event) => setReturnForm((current) => ({ ...current, remark: event.target.value }))} />
            {returnError && <p className="text-sm text-danger">{returnError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setReturnTarget(null)}>取消</Button>
              <Button loading={returnSaving} onClick={() => void handleCreateReturn()}>创建退药单</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 退药确认/取消弹窗 ─────────────────────────────────────── */}
      <Modal open={returnAction !== null} onClose={() => setReturnAction(null)} title={returnActionKind === "confirm" ? "确认退药入库" : "取消退药单"}>
        {returnAction && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">{returnAction.return_no}</div>
              <div className="mt-1 text-fg-muted">{returnAction.return_reason || "—"}</div>
            </div>
            {returnActionKind === "confirm" && <p className="text-sm text-warning">确认后将包装退回原仓库并增加库存，操作不可撤销。</p>}
            {returnActionKind === "confirm" && <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="return-action-operator">操作人</label>
              <select id="return-action-operator" className={selectClass} value={returnActionOperator} onChange={(event) => setReturnActionOperator(event.target.value)}>
                <option value="">请选择操作人</option>
                {subjects.map((subject) => <option key={subject.id} value={subject.display_name}>{subject.display_name}</option>)}
              </select>
            </div>}
            {returnActionError && <p className="text-sm text-danger">{returnActionError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setReturnAction(null)}>取消</Button>
              <Button variant={returnActionKind === "cancel" ? "danger" : "primary"} loading={returnActionSaving} onClick={() => void handleReturnAction()}>{returnActionKind === "confirm" ? "确认入库" : "取消退药单"}</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 创建发药单弹窗 ─────────────────────────────────────────── */}
      <Modal open={createTarget !== null} onClose={() => setCreateTarget(null)} title="创建发药单">
        {createTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">
                {createTarget.patient_name} · {createTarget.encounter_no || "无入住号"}
              </div>
              <div className="mt-1 text-fg-muted">
                {createTarget.drug_name || createTarget.order_content} ·{" "}
                {createTarget.dose ? `${createTarget.dose}${createTarget.unit ?? ""}` : "—"} ·{" "}
                {createTarget.frequency_name || createTarget.frequency_code || "按需"}
              </div>
              <div className="mt-1 text-xs text-fg-dimmed">医嘱医生：{createTarget.doctor || "—"}</div>
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="dispense-warehouse">仓库</label>
              <select
                id="dispense-warehouse"
                className={selectClass}
                value={createForm.warehouse}
                onChange={(event) => {
                  const warehouse = event.target.value;
                  setCreateForm((current) => ({ ...current, warehouse, stockId: "", materialId: "", lotId: "" }));
                  void loadStocks(warehouse);
                }}
              >
                <option value="">请选择仓库</option>
                {warehouses.map((warehouse) => (
                  <option key={warehouse.code} value={warehouse.code}>
                    {warehouse.name}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="dispense-material">药品物资</label>
              <select
                id="dispense-material"
                className={selectClass}
                value={createForm.stockId}
                disabled={!createForm.warehouse || stocksLoading}
                onChange={(event) => {
                  const stock = stocks.find((item) => item.id === event.target.value);
                  setCreateForm((current) => ({
                    ...current,
                    stockId: stock?.id ?? "",
                    materialId: stock?.material_id ?? "",
                    lotId: stock?.lot_id ?? "",
                  }));
                }}
              >
                <option value="">
                  {stocksLoading ? "加载中…" : createForm.warehouse ? "请选择药品物资" : "请先选择仓库"}
                </option>
                {stocks.map((stock) => (
                  <option key={stock.id} value={stock.id}>
                    {stock.material_name}
                    {stock.batch_no ? `（批次 ${stock.batch_no}` : "（无批次"}
                    {stock.expiry_date ? `，效期 ${stock.expiry_date.slice(0, 10)}` : ""}
                    {`，可用 ${stock.available_quantity}${stock.unit ?? ""}）`}
                  </option>
                ))}
              </select>
            </div>

            {selectedStock && selectedStock.lot_id && (
              <div className="rounded-md border border-border p-3 text-xs text-fg-muted">
                批次管控物资：将按批次 {selectedStock.batch_no || selectedStock.lot_id} 出库，效期{" "}
                {selectedStock.expiry_date ? selectedStock.expiry_date.slice(0, 10) : "—"}
              </div>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="发药数量"
                type="number"
                min={1}
                max={createAvailableQuantity || undefined}
                value={createForm.quantity}
                onChange={(event) => setCreateForm((current) => ({ ...current, quantity: event.target.value }))}
                placeholder="1"
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="dispense-operator">操作人</label>
                <select
                  id="dispense-operator"
                  className={selectClass}
                  value={createForm.operator}
                  onChange={(event) => setCreateForm((current) => ({ ...current, operator: event.target.value }))}
                >
                  <option value="">请选择操作人</option>
                  {subjects.map((subject) => (
                    <option key={subject.id} value={subject.display_name}>
                      {subject.display_name}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {createError && <p className="text-sm text-danger">{createError}</p>}

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setCreateTarget(null)}>取消</Button>
              <Button loading={createSaving} onClick={() => void handleCreateDispense()}>
                创建发药单
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 状态流转弹窗 ───────────────────────────────────────────── */}
      <Modal open={actionTarget !== null} onClose={() => setActionTarget(null)} title={actionTitle}>
        {actionTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">
                {actionTarget.dispense_no} · {encounterName.get(actionTarget.encounter_id ?? "") ?? actionTarget.patient_id}
              </div>
              <div className="mt-1 text-xs text-fg-dimmed">
                {actionTarget.metadata?.drug_name ? String(actionTarget.metadata.drug_name) : "—"} · 仓库{" "}
                {actionTarget.warehouse || "—"}
              </div>
            </div>

            {actionKind === "confirm" && (
              <p className="text-sm text-warning">
                发药确认将同时扣减仓库库存并回写库存操作明细，操作不可撤销。
              </p>
            )}
            {actionKind === "cancel" && (
              <p className="text-sm text-warning">取消后发药单转为已取消，该医嘱可重新接方。</p>
            )}

            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="action-operator">操作人</label>
              <select
                id="action-operator"
                className={selectClass}
                value={actionOperator}
                onChange={(event) => setActionOperator(event.target.value)}
              >
                <option value="">请选择操作人</option>
                {subjects.map((subject) => (
                  <option key={subject.id} value={subject.display_name}>
                    {subject.display_name}
                  </option>
                ))}
              </select>
            </div>

            <Input
              label="备注（可选）"
              value={actionRemark}
              onChange={(event) => setActionRemark(event.target.value)}
              placeholder="例如：药名与库存规格已核对"
            />

            {actionError && <p className="text-sm text-danger">{actionError}</p>}

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setActionTarget(null)}>取消</Button>
              <Button
                variant={actionKind === "cancel" ? "danger" : "primary"}
                loading={actionSaving}
                onClick={() => void handleAction()}
              >
                {actionTitle}
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 详情弹窗 ───────────────────────────────────────────────── */}
      <Modal open={detailOpen} onClose={() => setDetailOpen(false)} title="发药单详情">
        {detailLoading && <div className="py-12 text-center text-sm text-fg-dimmed">正在加载发药单详情…</div>}
        {!detailLoading && detailError && <p className="py-10 text-center text-sm text-danger">{detailError}</p>}
        {!detailLoading && !detailError && detail && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="flex items-center justify-between">
                <span className="font-medium text-fg-emphasis">{detail.dispense_no}</span>
                <div className="flex items-center gap-2">
                  {detail.status === "DISPENSED" && detail.items.length > 0 && <Button size="sm" onClick={() => openReturn(detail)}>创建退药</Button>}
                  {statusBadge(detail.status)}
                </div>
              </div>
              <div className="text-fg-muted">
                {encounterName.get(detail.encounter_id ?? "") ?? detail.patient_id} · 仓库 {detail.warehouse || "—"}
              </div>
              <div className="text-xs text-fg-dimmed">
                创建 {formatDateTime(detail.created_at)}
                {detail.dispensed_at ? ` · 发药 ${formatDateTime(detail.dispensed_at)}` : ""}
                {detail.reviewer ? ` · 审方 ${detail.reviewer}` : ""}
                {detail.pharmacist ? ` · 调配 ${detail.pharmacist}` : ""}
              </div>
            </div>

            {detail.metadata?.order_content != null && (
              <div className="rounded-md border border-border p-3 text-sm">
                <div className="text-xs text-fg-dimmed">医嘱</div>
                <div className="mt-1 text-fg">{String(detail.metadata.order_content)}</div>
                {detail.metadata.doctor ? (
                  <div className="mt-1 text-xs text-fg-dimmed">医生：{String(detail.metadata.doctor)}</div>
                ) : null}
              </div>
            )}

            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">药品物资</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批次</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">数量</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">单位成本</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">总成本</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.items.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="py-8 text-center text-sm text-fg-dimmed">暂无明细</td>
                    </tr>
                  ) : (
                    detail.items.map((item) => (
                      <tr key={item.id} className="border-b border-border/50">
                        <td className="py-2 px-3 text-fg">{item.material_id || "—"}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.lot_id || "—"}</td>
                        <td className="py-2 px-3 text-fg">
                          {item.dispensed_quantity ?? "—"} {materialUnit.get(item.material_id ?? "") ?? ""}
                          {item.prescribed_quantity != null && `（医嘱 ${item.prescribed_quantity}）`}
                        </td>
                        <td className="py-2 px-3 text-fg-muted">{item.unit_cost ?? "—"}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.total_cost ?? "—"}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            {detail.items[0]?.stock_operation_detail_id && (
              <div className="text-xs text-fg-dimmed">
                库存操作明细 ID：{detail.items[0].stock_operation_detail_id}
              </div>
            )}

            <div className="flex justify-end pt-2">
              <Button variant="secondary" onClick={() => setDetailOpen(false)}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
