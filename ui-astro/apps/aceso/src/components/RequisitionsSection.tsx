import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  approvePharmacyRequisition,
  cancelPharmacyRequisition,
  createPharmacyRequisition,
  dispensePharmacyRequisition,
  getPharmacyRequisition,
  listInventoryStocks,
  listInventoryWarehouses,
  listPharmacyRequisitions,
  type InventoryStockAvailability,
  type PharmacyRequisition,
} from "@pitchfork/shared/aceso";

const REQUISITION_STATUS: Record<string, { label: string; variant: "default" | "success" | "warning" | "danger" | "info" }> = {
  DRAFT: { label: "待审核", variant: "warning" },
  APPROVED: { label: "待调拨", variant: "info" },
  DISPENSED: { label: "已完成", variant: "success" },
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
  const meta = REQUISITION_STATUS[status] ?? { label: status, variant: "default" as const };
  return <Badge variant={meta.variant}>{meta.label}</Badge>;
}

interface CreateRow {
  materialId: string;
  quantity: string;
}

interface CreateForm {
  warehouse: string;
  destinationWarehouse: string;
  department: string;
  rows: CreateRow[];
}

interface ApproveRow {
  itemId: string;
  approvedQuantity: string;
  lotId: string;
}

/** 013 护理站申领工作区：新建（护理站）→ 待审核（药房）→ 待调拨（药房）→ 历史 */
export default function RequisitionsSection() {
  const [requisitions, setRequisitions] = useState<PharmacyRequisition[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [offset, setOffset] = useState(0);
  const PAGE = 50;

  const [warehouses, setWarehouses] = useState<string[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [stocksLoading, setStocksLoading] = useState(false);

  // ── 新建申领 ────────────────────────────────────────────────────────
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateForm>({ warehouse: "", destinationWarehouse: "", department: "", rows: [] });
  const [createError, setCreateError] = useState("");
  const [createSaving, setCreateSaving] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState("");

  // ── 审批 ───────────────────────────────────────────────────────────
  const [approveTarget, setApproveTarget] = useState<PharmacyRequisition | null>(null);
  const [approveRows, setApproveRows] = useState<ApproveRow[]>([]);
  const [approveError, setApproveError] = useState("");
  const [approveSaving, setApproveSaving] = useState(false);

  // ── 确认调拨 ───────────────────────────────────────────────────────
  const [dispenseTarget, setDispenseTarget] = useState<PharmacyRequisition | null>(null);
  const [dispenseError, setDispenseError] = useState("");
  const [dispenseSaving, setDispenseSaving] = useState(false);

  // ── 取消 ───────────────────────────────────────────────────────────
  const [cancelTarget, setCancelTarget] = useState<PharmacyRequisition | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelError, setCancelError] = useState("");
  const [cancelSaving, setCancelSaving] = useState(false);

  // ── 详情 ───────────────────────────────────────────────────────────
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<PharmacyRequisition | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");

  const materialName = useMemo(() => {
    const map = new Map<string, string>();
    for (const stock of stocks) map.set(stock.material_id, stock.material_name);
    return map;
  }, [stocks]);

  const materialUnit = useMemo(() => {
    const map = new Map<string, string>();
    for (const stock of stocks) map.set(stock.material_id, stock.unit);
    return map;
  }, [stocks]);

  const loadRequisitions = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await listPharmacyRequisitions({
        status: statusFilter || undefined,
        limit: PAGE,
        offset,
      });
      setRequisitions(response.records);
      setTotal(response.meta.total);
    } catch (loadError) {
      setRequisitions([]);
      setError(errorMessage(loadError, "无法加载护理站申领"));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, offset]);

  const loadWarehouses = useCallback(async () => {
    try {
      setWarehouses(await listInventoryWarehouses());
    } catch {
      // 仓库下拉失败不阻塞页面
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

  useEffect(() => {
    void loadRequisitions();
  }, [loadRequisitions]);

  // ── 新建 ───────────────────────────────────────────────────────────

  const openCreate = () => {
    setCreateForm({ warehouse: "", destinationWarehouse: "", department: "", rows: [{ materialId: "", quantity: "1" }] });
    setCreateError("");
    setIdempotencyKey(crypto.randomUUID());
    setCreateOpen(true);
    void loadWarehouses();
  };

  /** 创建用物资选项：按物资聚合该仓库可用量（不选批次，审批时再定批次） */
  const materialOptions = useMemo(() => {
    const byMaterial = new Map<string, InventoryStockAvailability>();
    for (const stock of stocks) {
      const existing = byMaterial.get(stock.material_id);
      if (!existing) {
        byMaterial.set(stock.material_id, { ...stock, available_quantity: 0 });
      }
      byMaterial.get(stock.material_id)!.available_quantity += stock.available_quantity;
    }
    return [...byMaterial.values()];
  }, [stocks]);

  const handleCreate = async () => {
    if (!createForm.warehouse.trim()) return setCreateError("请选择药房源仓库");
    if (!createForm.destinationWarehouse.trim()) return setCreateError("请选择目标护理站仓库");
    if (createForm.warehouse === createForm.destinationWarehouse) return setCreateError("目标仓库不能与源仓库相同");
    if (!createForm.department.trim()) return setCreateError("请填写申领科室");
    if (createForm.rows.length === 0) return setCreateError("请至少添加一项申领物资");
    for (const row of createForm.rows) {
      if (!row.materialId) return setCreateError("请为每行选择物资");
      const quantity = Number(row.quantity);
      if (!quantity || quantity <= 0) return setCreateError("申领数量必须大于 0");
    }
    setCreateSaving(true);
    setCreateError("");
    try {
      await createPharmacyRequisition(
        {
          warehouse: createForm.warehouse.trim(),
          destination_warehouse: createForm.destinationWarehouse.trim(),
          department: createForm.department.trim(),
          items: createForm.rows.map((row) => ({ material_id: row.materialId, requested_quantity: Number(row.quantity) })),
        },
        idempotencyKey,
      );
      setCreateOpen(false);
      setIdempotencyKey("");
      void loadRequisitions();
    } catch (createErr) {
      setCreateError(errorMessage(createErr, "创建申领单失败"));
    } finally {
      setCreateSaving(false);
    }
  };

  // ── 审批 ───────────────────────────────────────────────────────────

  const openApprove = (requisition: PharmacyRequisition) => {
    setApproveTarget(requisition);
    setApproveError("");
    setApproveRows(
      (requisition.items ?? []).map((item) => ({
        itemId: item.id,
        approvedQuantity: String(item.requested_quantity ?? 1),
        lotId: "",
      })),
    );
    if (requisition.warehouse) void loadStocks(requisition.warehouse);
  };

  const approveLotOptions = useMemo(() => {
    if (!approveTarget) return new Map<string, InventoryStockAvailability[]>();
    const byMaterial = new Map<string, InventoryStockAvailability[]>();
    for (const stock of stocks) {
      if (stock.available_quantity <= 0) continue;
      const list = byMaterial.get(stock.material_id) ?? [];
      list.push(stock);
      byMaterial.set(stock.material_id, list);
    }
    return byMaterial;
  }, [stocks, approveTarget]);

  const handleApprove = async () => {
    if (!approveTarget) return;
    const items: Array<{ id: string; approved_quantity: number; lot_id: string | null }> = [];
    for (const row of approveRows) {
      const quantity = Number(row.approvedQuantity);
      if (Number.isNaN(quantity) || quantity < 0) {
        return setApproveError("批准数量必须为不小于 0 的数字");
      }
      items.push({ id: row.itemId, approved_quantity: quantity, lot_id: row.lotId || null });
    }
    setApproveSaving(true);
    setApproveError("");
    try {
      await approvePharmacyRequisition(approveTarget.id, items);
      setApproveTarget(null);
      void loadRequisitions();
    } catch (approveErr) {
      setApproveError(errorMessage(approveErr, "审批失败"));
    } finally {
      setApproveSaving(false);
    }
  };

  // ── 确认调拨 ───────────────────────────────────────────────────────

  const handleDispense = async () => {
    if (!dispenseTarget) return;
    setDispenseSaving(true);
    setDispenseError("");
    try {
      await dispensePharmacyRequisition(dispenseTarget.id);
      setDispenseTarget(null);
      void loadRequisitions();
    } catch (dispenseErr) {
      setDispenseError(errorMessage(dispenseErr, "确认调拨失败"));
    } finally {
      setDispenseSaving(false);
    }
  };

  // ── 取消 ───────────────────────────────────────────────────────────

  const handleCancel = async () => {
    if (!cancelTarget) return;
    if (!cancelReason.trim()) return setCancelError("请填写取消原因");
    setCancelSaving(true);
    setCancelError("");
    try {
      await cancelPharmacyRequisition(cancelTarget.id, cancelReason.trim());
      setCancelTarget(null);
      setCancelReason("");
      void loadRequisitions();
    } catch (cancelErr) {
      setCancelError(errorMessage(cancelErr, "取消失败"));
    } finally {
      setCancelSaving(false);
    }
  };

  // ── 详情 ───────────────────────────────────────────────────────────

  const openDetail = async (requisition: PharmacyRequisition) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetailError("");
    setDetail(requisition);
    try {
      setDetail(await getPharmacyRequisition(requisition.id));
    } catch (detailErr) {
      setDetailError(errorMessage(detailErr, "无法加载申领单详情"));
    } finally {
      setDetailLoading(false);
    }
  };

  // ── 列 ─────────────────────────────────────────────────────────────

  const columns: Column<PharmacyRequisition>[] = [
    {
      key: "requisition_no",
      header: "申领单号",
      render: (row) => (
        <div>
          <div className="font-medium text-fg-emphasis">{row.requisition_no}</div>
          <div className="text-xs text-fg-dimmed">{formatDateTime(row.created_at)}</div>
        </div>
      ),
    },
    {
      key: "warehouses",
      header: "仓库",
      render: (row) => (
        <div className="text-sm text-fg-muted">
          <div>{row.warehouse || "—"}</div>
          <div className="text-xs text-fg-dimmed">→ {row.destination_warehouse || "—"}</div>
        </div>
      ),
    },
    { key: "department", header: "科室", render: (row) => <span className="text-fg-muted">{row.department || "—"}</span> },
    {
      key: "items",
      header: "物资摘要",
      render: (row) => {
        const items = row.items ?? [];
        if (items.length === 0) return <span className="text-fg-dimmed">—</span>;
        return (
          <div className="text-sm text-fg-muted">
            <div>{items.length} 项</div>
            <div className="text-xs text-fg-dimmed">
              {items.map((item) => `${materialName.get(item.material_id) ?? item.material_id.slice(0, 8)}×${item.requested_quantity ?? "—"}`).join("、")}
            </div>
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
              <Button size="sm" onClick={() => openApprove(row)}>审批</Button>
              <Button size="sm" variant="danger" onClick={() => { setCancelTarget(row); setCancelReason(""); setCancelError(""); }}>取消</Button>
            </>
          )}
          {row.status === "APPROVED" && (
            <>
              <Button size="sm" variant="primary" onClick={() => { setDispenseTarget(row); setDispenseError(""); }}>确认调拨</Button>
              <Button size="sm" variant="danger" onClick={() => { setCancelTarget(row); setCancelReason(""); setCancelError(""); }}>取消</Button>
            </>
          )}
          <Button size="sm" variant="ghost" onClick={() => void openDetail(row)}>详情</Button>
        </div>
      ),
    },
  ];

  return (
    <Card
      className="min-w-0 overflow-hidden"
      title="护理站申领"
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
            {Object.entries(REQUISITION_STATUS).map(([key, meta]) => (
              <option key={key} value={key}>{meta.label}</option>
            ))}
          </select>
          <Button size="sm" onClick={openCreate}>新建申领</Button>
        </div>
      }
    >
      {error ? (
        <p className="py-10 text-center text-sm text-danger">{error}</p>
      ) : requisitions.length === 0 && !loading ? (
        <EmptyState icon="📦" title="暂无护理站申领" description="护理站新建申领后，药房在此审批并确认调拨。" />
      ) : (
        <Table
          className="min-w-[900px]"
          columns={columns}
          data={requisitions}
          loading={loading}
          emptyMessage="暂无匹配的申领单"
        />
      )}
      {total > PAGE && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-border">
          <span className="text-xs text-fg-dimmed">共 {total} 条 · 第 {Math.floor(offset / PAGE) + 1} 页</span>
          <div className="flex gap-2">
            <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((value) => Math.max(0, value - PAGE))}>上一页</Button>
            <Button size="sm" variant="secondary" disabled={offset + PAGE >= total} onClick={() => setOffset((value) => value + PAGE)}>下一页</Button>
          </div>
        </div>
      )}

      {/* ── 新建申领 ───────────────────────────────────────────────── */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="新建护理站申领">
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="req-warehouse">药房源仓库</label>
              <select
                id="req-warehouse"
                className={selectClass}
                value={createForm.warehouse}
                onChange={(event) => {
                  const warehouse = event.target.value;
                  setCreateForm((current) => ({ ...current, warehouse, rows: current.rows.map((row) => ({ ...row, materialId: "" })) }));
                  void loadStocks(warehouse);
                }}
              >
                <option value="">请选择仓库</option>
                {warehouses.map((warehouse) => <option key={warehouse} value={warehouse}>{warehouse}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="req-destination">目标护理站仓库</label>
              <select
                id="req-destination"
                className={selectClass}
                value={createForm.destinationWarehouse}
                onChange={(event) => setCreateForm((current) => ({ ...current, destinationWarehouse: event.target.value }))}
              >
                <option value="">请选择仓库</option>
                {warehouses.map((warehouse) => <option key={warehouse} value={warehouse}>{warehouse}</option>)}
              </select>
            </div>
          </div>
          <Input
            label="申领科室"
            value={createForm.department}
            onChange={(event) => setCreateForm((current) => ({ ...current, department: event.target.value }))}
            placeholder="例如：一护理站"
          />

          <div className="space-y-2">
            <div className="text-sm font-medium text-fg-muted">申领物资</div>
            {createForm.rows.map((row, index) => (
              <div key={index} className="grid gap-2 sm:grid-cols-[1fr_120px_auto] items-center">
                <select
                  className={selectClass}
                  value={row.materialId}
                  disabled={!createForm.warehouse || stocksLoading}
                  onChange={(event) =>
                    setCreateForm((current) => ({
                      ...current,
                      rows: current.rows.map((candidate, i) => (i === index ? { ...candidate, materialId: event.target.value } : candidate)),
                    }))
                  }
                >
                  <option value="">
                    {stocksLoading ? "加载中…" : createForm.warehouse ? "请选择物资" : "请先选择仓库"}
                  </option>
                  {materialOptions.map((stock) => (
                    <option key={stock.material_id} value={stock.material_id}>
                      {stock.material_name}（可用 {stock.available_quantity}{stock.unit ?? ""}）
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
                  onClick={() =>
                    setCreateForm((current) => ({ ...current, rows: current.rows.filter((_, i) => i !== index) }))
                  }
                >
                  删除
                </Button>
              </div>
            ))}
            <Button
              size="sm"
              variant="secondary"
              onClick={() => setCreateForm((current) => ({ ...current, rows: [...current.rows, { materialId: "", quantity: "1" }] }))}
            >
              + 添加物资
            </Button>
          </div>

          {createError && <p className="text-sm text-danger">{createError}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>取消</Button>
            <Button loading={createSaving} onClick={() => void handleCreate()}>提交申领</Button>
          </div>
        </div>
      </Modal>

      {/* ── 审批弹窗 ───────────────────────────────────────────────── */}
      <Modal open={approveTarget !== null} onClose={() => setApproveTarget(null)} title="审批申领（预留库存）">
        {approveTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="font-medium text-fg-emphasis">{approveTarget.requisition_no} · {approveTarget.department || "—"}</div>
              <div className="text-xs text-fg-dimmed">
                {approveTarget.warehouse} → {approveTarget.destination_warehouse}
              </div>
              <div className="text-xs text-warning">审批通过后将立即在药房源仓库锁定等额库存（locked_quantity），批次物资必须选择批次。</div>
            </div>

            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">物资</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">申领</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批准数量</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批次</th>
                  </tr>
                </thead>
                <tbody>
                  {approveTarget.items?.map((item, index) => {
                    const row = approveRows[index];
                    const lotOptions = approveLotOptions.get(item.material_id) ?? [];
                    return (
                      <tr key={item.id} className="border-b border-border/50">
                        <td className="py-2 px-3 text-fg">{materialName.get(item.material_id) ?? item.material_id}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.requested_quantity ?? "—"} {materialUnit.get(item.material_id) ?? ""}</td>
                        <td className="py-2 px-3">
                          <Input
                            type="number"
                            min={0}
                            className="w-24"
                            value={row?.approvedQuantity ?? ""}
                            onChange={(event) =>
                              setApproveRows((current) => current.map((candidate, i) => (i === index ? { ...candidate, approvedQuantity: event.target.value } : candidate)))
                            }
                          />
                        </td>
                        <td className="py-2 px-3">
                          <select
                            className={selectClass}
                            value={row?.lotId ?? ""}
                            onChange={(event) =>
                              setApproveRows((current) => current.map((candidate, i) => (i === index ? { ...candidate, lotId: event.target.value } : candidate)))
                            }
                          >
                            <option value="">无批次</option>
                            {lotOptions.map((stock) => (
                              <option key={stock.id} value={stock.lot_id ?? ""}>
                                {stock.batch_no || stock.lot_id || "无批次"}
                                {stock.expiry_date ? `（效期 ${stock.expiry_date.slice(0, 10)}）` : ""}
                                {`（可用 ${stock.available_quantity}）`}
                              </option>
                            ))}
                          </select>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {approveError && <p className="text-sm text-danger">{approveError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setApproveTarget(null)}>取消</Button>
              <Button loading={approveSaving} onClick={() => void handleApprove()}>确认审批并预留</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 确认调拨弹窗 ───────────────────────────────────────────── */}
      <Modal open={dispenseTarget !== null} onClose={() => setDispenseTarget(null)} title="确认调拨">
        {dispenseTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="font-medium text-fg-emphasis">{dispenseTarget.requisition_no}</div>
              <div className="text-fg-muted">
                {dispenseTarget.warehouse} → {dispenseTarget.destination_warehouse} · {dispenseTarget.department || "—"}
              </div>
              <div className="text-xs text-fg-dimmed">
                已预留：{(dispenseTarget.items ?? []).map((item) => `${materialName.get(item.material_id) ?? item.material_id.slice(0, 8)}×${item.approved_quantity ?? 0}${item.lot_id ? `（批次 ${item.lot_id.slice(0, 8)}）` : ""}`).join("、") || "—"}
              </div>
            </div>
            <p className="text-sm text-warning">确认后将一次性完成药房仓库出库与护理站仓库入库并回写双向库存操作明细，操作不可撤销。</p>
            {dispenseError && <p className="text-sm text-danger">{dispenseError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setDispenseTarget(null)}>取消</Button>
              <Button variant="primary" loading={dispenseSaving} onClick={() => void handleDispense()}>确认调拨</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 取消弹窗 ───────────────────────────────────────────────── */}
      <Modal open={cancelTarget !== null} onClose={() => setCancelTarget(null)} title="取消申领">
        {cancelTarget && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="font-medium text-fg-emphasis">{cancelTarget.requisition_no}</div>
              <div className="mt-1 text-xs text-fg-dimmed">
                {cancelTarget.status === "APPROVED" ? "已审批单据取消后将释放药房源仓库的库存预留。" : "草稿取消不产生库存变动。"}
              </div>
            </div>
            <Input
              label="取消原因"
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
              placeholder="例如：护理站取消本次补货"
            />
            {cancelError && <p className="text-sm text-danger">{cancelError}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setCancelTarget(null)}>取消</Button>
              <Button variant="danger" loading={cancelSaving} onClick={() => void handleCancel()}>确认取消</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* ── 详情弹窗 ───────────────────────────────────────────────── */}
      <Modal open={detailOpen} onClose={() => setDetailOpen(false)} title="申领单详情">
        {detailLoading && <div className="py-12 text-center text-sm text-fg-dimmed">正在加载申领单详情…</div>}
        {!detailLoading && detailError && <p className="py-10 text-center text-sm text-danger">{detailError}</p>}
        {!detailLoading && !detailError && detail && (
          <div className="space-y-4">
            <div className="rounded-md border border-border p-3 text-sm space-y-1">
              <div className="flex items-center justify-between">
                <span className="font-medium text-fg-emphasis">{detail.requisition_no}</span>
                {statusBadge(detail.status)}
              </div>
              <div className="text-fg-muted">{detail.warehouse} → {detail.destination_warehouse || "—"} · {detail.department || "—"}</div>
              <div className="text-xs text-fg-dimmed">
                创建 {formatDateTime(detail.created_at)}
                {detail.approved_at ? ` · 审批 ${formatDateTime(detail.approved_at)}` : ""}
                {detail.dispensed_at ? ` · 调拨 ${formatDateTime(detail.dispensed_at)}` : ""}
                {detail.cancelled_at ? ` · 取消 ${formatDateTime(detail.cancelled_at)}` : ""}
              </div>
              {detail.cancel_reason && <div className="text-xs text-danger">取消原因：{detail.cancel_reason}</div>}
            </div>

            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">物资</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批次</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">申领</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">批准</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">实发</th>
                    <th className="text-left text-xs font-semibold text-fg-dimmed uppercase tracking-wider py-2 px-3">出/入明细 ID</th>
                  </tr>
                </thead>
                <tbody>
                  {(detail.items ?? []).length === 0 ? (
                    <tr><td colSpan={6} className="py-8 text-center text-sm text-fg-dimmed">暂无明细</td></tr>
                  ) : (
                    (detail.items ?? []).map((item) => (
                      <tr key={item.id} className="border-b border-border/50">
                        <td className="py-2 px-3 text-fg">{materialName.get(item.material_id) ?? item.material_id}</td>
                        <td className="py-2 px-3 text-fg-muted">{item.lot_id || "—"}</td>
                        <td className="py-2 px-3 text-fg">{item.requested_quantity ?? "—"}</td>
                        <td className="py-2 px-3 text-fg">{item.approved_quantity ?? "—"}</td>
                        <td className="py-2 px-3 text-fg">{item.dispensed_quantity ?? "—"}</td>
                        <td className="py-2 px-3 text-xs text-fg-dimmed">
                          {item.outbound_stock_operation_detail_id ? (
                            <div>
                              <div title={item.outbound_stock_operation_detail_id}>出 {item.outbound_stock_operation_detail_id.slice(0, 10)}…</div>
                              <div title={item.inbound_stock_operation_detail_id ?? ""}>入 {item.inbound_stock_operation_detail_id?.slice(0, 10) ?? "—"}</div>
                            </div>
                          ) : (
                            "—"
                          )}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="flex justify-end pt-2">
              <Button variant="secondary" onClick={() => setDetailOpen(false)}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>
    </Card>
  );
}
