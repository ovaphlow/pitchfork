import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  approvePharmacyPurchaseOrder,
  cancelPharmacyPurchaseOrder,
  closePharmacyPurchaseOrder,
  createPharmacyPurchaseOrder,
  getPharmacyPurchaseOrder,
  getPharmacyPurchaseReceipt,
  listInventoryStocks,
  listInventoryWarehouses,
  listPharmacyPurchaseOrders,
  receivePharmacyPurchaseOrder,
  updatePharmacyPurchaseOrder,
  type InventoryStockAvailability,
  type PharmacyPurchaseOrder,
  type PharmacyPurchaseReceipt,
} from "@pitchfork/shared/aceso";

/**
 * 014 药房采购：采购订单（创建 → 审核 → 供应商收货 → 收讫/关闭/取消）
 * 与收货凭证。创建与收货要求 `Idempotency-Key`；批次、效期、成本由收货
 * 弹窗录入，物资是否批次管控由服务端权威校验。
 */

const ORDER_STATUS: Record<string, { label: string; variant: "default" | "success" | "warning" | "danger" | "info" }> = {
  DRAFT: { label: "草稿", variant: "default" },
  APPROVED: { label: "已审核", variant: "info" },
  PARTIALLY_RECEIVED: { label: "部分收货", variant: "warning" },
  RECEIVED: { label: "已收讫", variant: "success" },
  CLOSED: { label: "已关闭", variant: "default" },
  CANCELLED: { label: "已取消", variant: "danger" },
};

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function statusBadge(status: string | null | undefined): React.ReactNode {
  if (!status) return <span className="text-fg-dimmed">—</span>;
  const meta = ORDER_STATUS[status] ?? { label: status, variant: "default" as const };
  return <Badge variant={meta.variant}>{meta.label}</Badge>;
}

interface PurchaseRow {
  materialId: string;
  quantity: string;
}

interface ReceiptRow {
  orderItemId: string;
  quantity: string;
  batchNo: string;
  productionDate: string;
  expiryDate: string;
  manufacturer: string;
  unitCost: string;
}

export default function PurchaseOrdersSection() {
  // ── 列表 ────────────────────────────────────────────────────────────
  const [orders, setOrders] = useState<PharmacyPurchaseOrder[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [offset, setOffset] = useState(0);
  const PAGE = 50;

  // ── 弹窗共用 ────────────────────────────────────────────────────────
  const [warehouses, setWarehouses] = useState<string[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [stocksLoading, setStocksLoading] = useState(false);

  // ── 新建/编辑 ───────────────────────────────────────────────────────
  const [editTarget, setEditTarget] = useState<PharmacyPurchaseOrder | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState({ warehouse: "", supplierName: "", rows: [{ materialId: "", quantity: "1" }] as PurchaseRow[] });
  const [createError, setCreateError] = useState("");
  const [createSaving, setCreateSaving] = useState(false);
  const [createIdempotencyKey, setCreateIdempotencyKey] = useState("");

  // ── 状态流转（审核/取消/关闭） ──────────────────────────────────────
  const [actionTarget, setActionTarget] = useState<PharmacyPurchaseOrder | null>(null);
  const [actionKind, setActionKind] = useState<"" | "approve" | "cancel" | "close">("");
  const [actionReason, setActionReason] = useState("");
  const [actionError, setActionError] = useState("");
  const [actionSaving, setActionSaving] = useState(false);

  // ── 收货 ────────────────────────────────────────────────────────────
  const [receiveTarget, setReceiveTarget] = useState<PharmacyPurchaseOrder | null>(null);
  const [receiveRows, setReceiveRows] = useState<ReceiptRow[]>([]);
  const [receiveError, setReceiveError] = useState("");
  const [receiveSaving, setReceiveSaving] = useState(false);
  const [receiveIdempotencyKey, setReceiveIdempotencyKey] = useState("");

  // ── 详情 ────────────────────────────────────────────────────────────
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<PharmacyPurchaseOrder | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [receiptDetail, setReceiptDetail] = useState<PharmacyPurchaseReceipt | null>(null);
  const [receiptDetailOpen, setReceiptDetailOpen] = useState(false);
  const [receiptDetailLoading, setReceiptDetailLoading] = useState(false);

  const loadOrders = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await listPharmacyPurchaseOrders({
        status: statusFilter || undefined,
        limit: PAGE,
        offset,
      });
      setOrders(response.records);
      setTotal(response.meta.total);
    } catch (loadError) {
      setOrders([]);
      setError(errorMessage(loadError, "无法加载采购订单"));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, offset]);

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  const loadWarehouses = useCallback(async () => {
    try {
      setWarehouses(await listInventoryWarehouses());
    } catch {
      setWarehouses([]);
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
      setStocks(response.records);
    } catch {
      setStocks([]);
    } finally {
      setStocksLoading(false);
    }
  }, []);

  /** 按物资聚合该仓库现有库存行，作为采购物资选项 */
  const materialOptions = useMemo(() => {
    const byMaterial = new Map<string, InventoryStockAvailability>();
    for (const stock of stocks) {
      if (!byMaterial.has(stock.material_id)) byMaterial.set(stock.material_id, stock);
    }
    return [...byMaterial.values()];
  }, [stocks]);

  // ── 新建 ────────────────────────────────────────────────────────────

  const openCreate = () => {
    setEditTarget(null);
    setCreateForm({ warehouse: "", supplierName: "", rows: [{ materialId: "", quantity: "1" }] });
    setCreateError("");
    setCreateIdempotencyKey(crypto.randomUUID());
    setCreateOpen(true);
    void loadWarehouses();
  };

  const openEdit = (order: PharmacyPurchaseOrder) => {
    setEditTarget(order);
    setCreateForm({
      warehouse: order.warehouse,
      supplierName: order.supplier_name,
      rows: order.items.map((item) => ({ materialId: item.material_id, quantity: String(item.ordered_quantity) })),
    });
    setCreateError("");
    setCreateOpen(true);
    void loadWarehouses();
    void loadStocks(order.warehouse);
  };

  const handleSave = async () => {
    if (!createForm.warehouse.trim()) return setCreateError("请选择仓库");
    if (!createForm.supplierName.trim()) return setCreateError("请填写供应商名称");
    if (createForm.rows.length === 0) return setCreateError("请至少添加一项物资");
    for (const row of createForm.rows) {
      if (!row.materialId) return setCreateError("请为每行选择物资");
      const quantity = Number(row.quantity);
      if (!quantity || quantity <= 0) return setCreateError("订购数量必须大于 0");
    }
    const input = {
      warehouse: createForm.warehouse.trim(),
      supplier_name: createForm.supplierName.trim(),
      items: createForm.rows.map((row) => ({ material_id: row.materialId, ordered_quantity: Number(row.quantity) })),
    };
    setCreateSaving(true);
    setCreateError("");
    try {
      if (editTarget) {
        await updatePharmacyPurchaseOrder(editTarget.id, input);
      } else {
        await createPharmacyPurchaseOrder(input, createIdempotencyKey);
      }
      setCreateOpen(false);
      setCreateIdempotencyKey("");
      void loadOrders();
    } catch (saveError) {
      setCreateError(errorMessage(saveError, editTarget ? "保存失败" : "创建失败"));
    } finally {
      setCreateSaving(false);
    }
  };

  // ── 审核 / 取消 / 关闭 ──────────────────────────────────────────────

  const openAction = (order: PharmacyPurchaseOrder, kind: "approve" | "cancel" | "close") => {
    setActionTarget(order);
    setActionKind(kind);
    setActionReason("");
    setActionError("");
  };

  const handleAction = async () => {
    if (!actionTarget || !actionKind) return;
    if ((actionKind === "cancel" || actionKind === "close") && !actionReason.trim()) {
      return setActionError("请填写原因");
    }
    setActionSaving(true);
    setActionError("");
    try {
      if (actionKind === "approve") await approvePharmacyPurchaseOrder(actionTarget.id);
      if (actionKind === "cancel") await cancelPharmacyPurchaseOrder(actionTarget.id, actionReason.trim());
      if (actionKind === "close") await closePharmacyPurchaseOrder(actionTarget.id, actionReason.trim());
      setActionTarget(null);
      setActionKind("");
      void loadOrders();
    } catch (actionErr) {
      setActionError(errorMessage(actionErr, "操作失败"));
    } finally {
      setActionSaving(false);
    }
  };

  // ── 收货 ────────────────────────────────────────────────────────────

  const openReceive = (order: PharmacyPurchaseOrder) => {
    setReceiveTarget(order);
    setReceiveError("");
    setReceiveIdempotencyKey(crypto.randomUUID());
    setReceiveRows(
      (order.items ?? []).map((item) => ({
        orderItemId: item.id,
        quantity: String(Math.max(1, item.remaining_quantity)),
        batchNo: "",
        productionDate: "",
        expiryDate: "",
        manufacturer: "",
        unitCost: "",
      })),
    );
  };

  const handleReceive = async () => {
    if (!receiveTarget) return;
    if (receiveRows.length === 0) return setReceiveError("请至少添加一项收货明细");
    for (const row of receiveRows) {
      const quantity = Number(row.quantity);
      if (!quantity || quantity <= 0) return setReceiveError("收货数量必须大于 0");
      const cost = Number(row.unitCost);
      if (!cost || cost < 0) return setReceiveError("实际成本必须 ≥ 0");
      const item = receiveTarget.items.find((candidate) => candidate.id === row.orderItemId);
      if (item && quantity > item.remaining_quantity) {
        return setReceiveError(`收货数量不能超过剩余 ${item.remaining_quantity}（${item.material_id}）`);
      }
    }
    setReceiveSaving(true);
    setReceiveError("");
    try {
      await receivePharmacyPurchaseOrder(
        receiveTarget.id,
        {
          items: receiveRows.map((row) => ({
            purchase_order_item_id: row.orderItemId,
            received_quantity: Number(row.quantity),
            unit_cost: Number(row.unitCost),
            ...(row.batchNo.trim() ? { batch_no: row.batchNo.trim() } : {}),
            ...(row.productionDate.trim() ? { production_date: row.productionDate.trim() } : {}),
            ...(row.expiryDate.trim() ? { expiry_date: row.expiryDate.trim() } : {}),
            ...(row.manufacturer.trim() ? { manufacturer: row.manufacturer.trim() } : {}),
          })),
        },
        receiveIdempotencyKey,
      );
      setReceiveTarget(null);
      setReceiveIdempotencyKey("");
      void loadOrders();
    } catch (receiveErr) {
      setReceiveError(errorMessage(receiveErr, "收货失败"));
    } finally {
      setReceiveSaving(false);
    }
  };

  // ── 详情 ────────────────────────────────────────────────────────────

  const openDetail = async (order: PharmacyPurchaseOrder) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetailError("");
    setDetail(order);
    try {
      setDetail(await getPharmacyPurchaseOrder(order.id));
    } catch (detailErr) {
      setDetailError(errorMessage(detailErr, "无法加载订单详情"));
    } finally {
      setDetailLoading(false);
    }
  };

  const openReceiptDetail = async (receiptId: string) => {
    setReceiptDetailOpen(true);
    setReceiptDetailLoading(true);
    setReceiptDetail(null);
    try {
      setReceiptDetail(await getPharmacyPurchaseReceipt(receiptId));
    } catch (receiptErr) {
      setReceiptDetail(null);
      // 复用详情弹窗的错误展示
      setDetailError(errorMessage(receiptErr, "无法加载收货凭证"));
    } finally {
      setReceiptDetailLoading(false);
    }
  };

  // ── 列表列 ──────────────────────────────────────────────────────────

  const columns: Column<PharmacyPurchaseOrder>[] = [
    {
      key: "order_no",
      header: "采购订单",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.purchase_order_no}</div>
          <div className="text-xs text-fg-dimmed">{formatDateTime(row.created_at)}</div>
        </div>
      ),
    },
    {
      key: "supplier",
      header: "供应商",
      render: (row) => (
        <div>
          <div className="text-fg">{row.supplier_name}</div>
          <div className="text-xs text-fg-dimmed">{row.warehouse}</div>
        </div>
      ),
    },
    {
      key: "progress",
      header: "进度",
      render: (row) => {
        const ordered = row.items.reduce((sum, item) => sum + item.ordered_quantity, 0);
        const received = row.items.reduce((sum, item) => sum + item.received_quantity, 0);
        return (
          <div className="text-sm text-fg-muted">
            {received} / {ordered}
          </div>
        );
      },
    },
    { key: "status", header: "状态", render: (row) => statusBadge(row.status) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex flex-wrap gap-2">
          {row.status === "DRAFT" && (
            <>
              <Button size="sm" variant="primary" onClick={() => openAction(row, "approve")}>审核</Button>
              <Button size="sm" onClick={() => openEdit(row)}>编辑</Button>
              <Button size="sm" variant="danger" onClick={() => openAction(row, "cancel")}>取消</Button>
            </>
          )}
          {row.status === "APPROVED" && (
            <>
              <Button size="sm" variant="primary" onClick={() => openReceive(row)}>供应商收货</Button>
              <Button size="sm" onClick={() => openAction(row, "close")}>关闭余量</Button>
              <Button size="sm" variant="danger" onClick={() => openAction(row, "cancel")}>取消</Button>
            </>
          )}
          {row.status === "PARTIALLY_RECEIVED" && (
            <>
              <Button size="sm" variant="primary" onClick={() => openReceive(row)}>继续收货</Button>
              <Button size="sm" onClick={() => openAction(row, "close")}>关闭余量</Button>
            </>
          )}
          <Button size="sm" variant="ghost" onClick={() => void openDetail(row)}>详情</Button>
        </div>
      ),
    },
  ];

  const actionTitle = actionKind === "approve" ? "审核采购订单" : actionKind === "cancel" ? "取消采购订单" : "关闭采购订单";

  return (
    <div className="space-y-4">
      <Card
        className="min-w-0 overflow-hidden"
        title="采购订单"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={selectClass}
              value={statusFilter}
              onChange={(event) => {
                setStatusFilter(event.target.value);
                setOffset(0);
              }}
            >
              <option value="">全部状态</option>
              {Object.entries(ORDER_STATUS).map(([key, meta]) => (
                <option key={key} value={key}>
                  {meta.label}
                </option>
              ))}
            </select>
            <Button size="sm" variant="primary" onClick={openCreate}>新建采购订单</Button>
          </div>
        }
      >
        {error ? (
          <p className="py-10 text-center text-sm text-danger">{error}</p>
        ) : orders.length === 0 && !loading ? (
          <EmptyState icon="🛒" title="暂无采购订单" description="新建采购订单并审核后，供应商到货时在此收货。" />
        ) : (
          <Table
            className="min-w-[900px]"
            columns={columns}
            data={orders}
            loading={loading}
            emptyMessage="暂无匹配的采购订单"
          />
        )}
        {total > PAGE && (
          <div className="flex items-center justify-between px-5 py-3 border-t border-border">
            <span className="text-xs text-fg-dimmed">
              共 {total} 条 · 第 {Math.floor(offset / PAGE) + 1} 页
            </span>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((value) => Math.max(0, value - PAGE))}>
                上一页
              </Button>
              <Button size="sm" variant="secondary" disabled={offset + PAGE >= total} onClick={() => setOffset((value) => value + PAGE)}>
                下一页
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* ── 新建/编辑弹窗 ─────────────────────────────────────────── */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={editTarget ? "编辑采购订单" : "新建采购订单"}>
        <div className="space-y-4">
          {editTarget && (
            <div className="rounded-md border border-border p-3 text-xs text-fg-muted">
              {editTarget.purchase_order_no} · 仅草稿可编辑，保存将完整替换订购明细。
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="po-warehouse">仓库</label>
              <select
                id="po-warehouse"
                className={selectClass}
                value={createForm.warehouse}
                onChange={(event) => {
                  const warehouse = event.target.value;
                  setCreateForm((current) => ({ ...current, warehouse }));
                  void loadStocks(warehouse);
                }}
              >
                <option value="">请选择仓库</option>
                {warehouses.map((warehouse) => (
                  <option key={warehouse} value={warehouse}>
                    {warehouse}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="供应商名称"
              value={createForm.supplierName}
              onChange={(event) => setCreateForm((current) => ({ ...current, supplierName: event.target.value }))}
              placeholder="例如：华康医药配送"
            />
          </div>

          <div className="space-y-2">
            <div className="text-sm font-medium text-fg-muted">订购明细</div>
            {createForm.rows.map((row, index) => (
              <div key={index} className="grid gap-2 sm:grid-cols-[1fr_110px_auto]">
                <select
                  className={selectClass}
                  value={row.materialId}
                  onChange={(event) =>
                    setCreateForm((current) => ({
                      ...current,
                      rows: current.rows.map((candidate, i) => (i === index ? { ...candidate, materialId: event.target.value } : candidate)),
                    }))
                  }
                >
                  <option value="">请选择物资</option>
                  {materialOptions.map((stock) => (
                    <option key={stock.material_id} value={stock.material_id}>
                      {stock.material_name}（{stock.material_id}）
                    </option>
                  ))}
                </select>
                <Input
                  type="number"
                  min={1}
                  value={row.quantity}
                  onChange={(event) =>
                    setCreateForm((current) => ({
                      ...current,
                      rows: current.rows.map((candidate, i) => (i === index ? { ...candidate, quantity: event.target.value } : candidate)),
                    }))
                  }
                  placeholder="数量"
                />
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={createForm.rows.length <= 1}
                  onClick={() => setCreateForm((current) => ({ ...current, rows: current.rows.filter((_, i) => i !== index) }))}
                >
                  删除
                </Button>
              </div>
            ))}
            <Button
              size="sm"
              variant="secondary"
              disabled={!createForm.warehouse || stocksLoading}
              onClick={() =>
                setCreateForm((current) => ({ ...current, rows: [...current.rows, { materialId: "", quantity: "1" }] }))
              }
            >
              + 添加物资
            </Button>
          </div>

          {createError && <p className="text-sm text-danger">{createError}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>取消</Button>
            <Button loading={createSaving} onClick={() => void handleSave()}>
              {editTarget ? "保存修改" : "创建采购订单"}
            </Button>
          </div>
        </div>
      </Modal>

      {/* ── 审核/取消/关闭弹窗 ─────────────────────────────────────── */}
      <Modal open={actionTarget !== null} onClose={() => setActionTarget(null)} title={actionTitle}>
        {actionTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">{actionTarget.purchase_order_no}</div>
              <div className="mt-1 text-fg-muted">{actionTarget.supplier_name} · {actionTarget.warehouse}</div>
              {actionKind === "approve" && <div className="mt-1 text-xs text-fg-dimmed">审核通过后开始收货；审核后明细冻结。</div>}
              {actionKind === "cancel" && <div className="mt-1 text-xs text-fg-dimmed">仅草稿或零收货订单可取消；已收货订单请改为关闭余量。</div>}
              {actionKind === "close" && <div className="mt-1 text-xs text-fg-dimmed">关闭后剩余数量不再收货。</div>}
            </div>
            {(actionKind === "cancel" || actionKind === "close") && (
              <Input label="原因" value={actionReason} onChange={(event) => setActionReason(event.target.value)} placeholder="必填" />
            )}
            {actionError && <p className="text-sm text-danger">{actionError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setActionTarget(null)}>取消</Button>
              <Button
                variant={actionKind === "cancel" ? "danger" : "primary"}
                loading={actionSaving}
                onClick={() => void handleAction()}
              >
                {actionKind === "approve" ? "确认审核" : actionKind === "cancel" ? "确认取消" : "确认关闭"}
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 收货弹窗 ───────────────────────────────────────────────── */}
      <Modal open={receiveTarget !== null} onClose={() => setReceiveTarget(null)} title="供应商收货">
        {receiveTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">{receiveTarget.purchase_order_no}</div>
              <div className="mt-1 text-xs text-fg-dimmed">
                {receiveTarget.supplier_name} · {receiveTarget.warehouse} · 收货将按批次/数量/实际成本入库并生成库存操作
              </div>
            </div>
            <div className="space-y-2">
              {receiveRows.map((row, index) => {
                const item = receiveTarget.items.find((candidate) => candidate.id === row.orderItemId);
                return (
                  <div key={row.orderItemId} className="rounded-md border border-border p-3 space-y-2">
                    <div className="flex items-center justify-between text-sm">
                      <span className="font-medium text-fg-emphasis">{item?.material_id ?? row.orderItemId}</span>
                      <span className="text-xs text-fg-dimmed">已收 {item?.received_quantity ?? "—"} / 订购 {item?.ordered_quantity ?? "—"}</span>
                    </div>
                    <div className="grid gap-2 sm:grid-cols-2">
                      <Input
                        label="收货数量"
                        type="number"
                        min={1}
                        value={row.quantity}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, quantity: event.target.value } : candidate)),
                          )
                        }
                      />
                      <Input
                        label="实际成本（单价）"
                        type="number"
                        min={0}
                        value={row.unitCost}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, unitCost: event.target.value } : candidate)),
                          )
                        }
                        placeholder="必填"
                      />
                      <Input
                        label="批号（批次物资必填）"
                        value={row.batchNo}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, batchNo: event.target.value } : candidate)),
                          )
                        }
                      />
                      <Input
                        label="生产企业（可选）"
                        value={row.manufacturer}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, manufacturer: event.target.value } : candidate)),
                          )
                        }
                      />
                      <Input
                        label="生产日期（可选）"
                        type="date"
                        value={row.productionDate}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, productionDate: event.target.value } : candidate)),
                          )
                        }
                      />
                      <Input
                        label="有效期（批次物资必填）"
                        type="date"
                        value={row.expiryDate}
                        onChange={(event) =>
                          setReceiveRows((current) =>
                            current.map((candidate, i) => (i === index ? { ...candidate, expiryDate: event.target.value } : candidate)),
                          )
                        }
                      />
                    </div>
                  </div>
                );
              })}
            </div>
            {receiveError && <p className="text-sm text-danger">{receiveError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setReceiveTarget(null)}>取消</Button>
              <Button loading={receiveSaving} onClick={() => void handleReceive()}>
                确认收货入库
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 订单详情弹窗 ───────────────────────────────────────────── */}
      <Modal open={detailOpen} onClose={() => setDetailOpen(false)} title="采购订单详情">
        {detailLoading && <div className="py-12 text-center text-sm text-fg-dimmed">正在加载订单详情…</div>}
        {!detailLoading && detailError && <p className="py-10 text-center text-sm text-danger">{detailError}</p>}
        {!detailLoading && !detailError && detail && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="flex items-center justify-between">
                <span className="font-medium text-fg-emphasis">{detail.purchase_order_no}</span>
                {statusBadge(detail.status)}
              </div>
              <div className="text-fg-muted">
                {detail.supplier_name} · {detail.warehouse}
              </div>
              <div className="text-xs text-fg-dimmed">
                创建 {formatDateTime(detail.created_at)}
                {detail.approved_at ? ` · 审核 ${formatDateTime(detail.approved_at)}` : ""}
                {detail.cancelled_at ? ` · 取消 ${formatDateTime(detail.cancelled_at)}（${detail.cancel_reason ?? ""}）` : ""}
                {detail.closed_at ? ` · 关闭 ${formatDateTime(detail.closed_at)}（${detail.close_reason ?? ""}）` : ""}
              </div>
            </div>

            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">物资</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">订购</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">已收</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">剩余</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.items.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-sm text-fg-dimmed">暂无明细</td>
                    </tr>
                  ) : (
                    detail.items.map((item) => (
                      <tr key={item.id} className="border-b border-border/50">
                        <td className="py-2 px-3 text-fg">{item.material_id}</td>
                        <td className="py-2 px-3 text-fg">{item.ordered_quantity}</td>
                        <td className="py-2 px-3 text-fg">{item.received_quantity}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.remaining_quantity}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            {(detail.receipts ?? []).length > 0 && (
              <div className="rounded-md border border-border">
                <div className="px-3 py-2 text-xs font-semibold text-fg-dimmed uppercase tracking-wider">收货凭证</div>
                {(detail.receipts ?? []).map((receipt) => (
                  <button
                    key={receipt.id}
                    className="flex w-full items-center justify-between border-t border-border/50 px-3 py-2 text-sm hover:bg-surface-muted"
                    onClick={() => {
                      void openReceiptDetail(receipt.id);
                    }}
                  >
                    <span className="font-medium text-fg-emphasis">{receipt.receipt_no}</span>
                    <span className="text-xs text-fg-dimmed">
                      {formatDateTime(receipt.received_at)} · 库存操作 {receipt.stock_operation_id}
                    </span>
                  </button>
                ))}
              </div>
            )}

            <div className="flex justify-end pt-2">
              <Button variant="secondary" onClick={() => setDetailOpen(false)}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 收货凭证详情弹窗 ───────────────────────────────────────── */}
      <Modal open={receiptDetailOpen} onClose={() => setReceiptDetailOpen(false)} title="收货凭证">
        {receiptDetailLoading && <div className="py-12 text-center text-sm text-fg-dimmed">正在加载收货凭证…</div>}
        {!receiptDetailLoading && !receiptDetail && <p className="py-10 text-center text-sm text-danger">{detailError}</p>}
        {!receiptDetailLoading && receiptDetail && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="flex items-center justify-between">
                <span className="font-medium text-fg-emphasis">{receiptDetail.receipt_no}</span>
                <Badge variant="success">已入库</Badge>
              </div>
              <div className="text-fg-muted">
                {receiptDetail.supplier_name} · {receiptDetail.warehouse} · 收货人 {receiptDetail.received_by}
              </div>
              <div className="text-xs text-fg-dimmed">
                收货 {formatDateTime(receiptDetail.received_at)} · 库存操作 {receiptDetail.stock_operation_id}
              </div>
            </div>
            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">物资</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批次</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">数量</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">单位成本</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">总成本</th>
                  </tr>
                </thead>
                <tbody>
                  {receiptDetail.items.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="py-8 text-center text-sm text-fg-dimmed">暂无明细</td>
                    </tr>
                  ) : (
                    receiptDetail.items.map((item) => (
                      <tr key={item.id} className="border-b border-border/50">
                        <td className="py-2 px-3 text-fg">{item.material_id}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.lot_id || "无批次"}</td>
                        <td className="py-2 px-3 text-fg">{item.received_quantity}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.unit_cost}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.total_cost}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <div className="flex justify-end pt-2">
              <Button variant="secondary" onClick={() => setReceiptDetailOpen(false)}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
