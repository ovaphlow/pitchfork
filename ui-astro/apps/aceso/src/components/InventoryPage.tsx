import { useCallback, useEffect, useMemo, useState } from "react";
import {
  confirmInventoryInbound,
  createInventoryMaterial,
  listInventoryMaterials,
  listInventoryStocks,
  listInventoryWarehouses,
  type InventoryInboundItem,
  type InventoryMaterial,
  type InventoryStockAvailability,
} from "@pitchfork/shared/aceso";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 新建物资：一次性提交 base_unit 与 quantity_scale */
interface MaterialForm {
  code: string;
  name: string;
  category: string;
  baseUnit: string;
  quantityScale: string;
}

const materialFormDefaults: MaterialForm = {
  code: "",
  name: "",
  category: "",
  baseUnit: "",
  quantityScale: "0",
};

/** 入库明细行：基础数量 + 每基础单位成本 */
interface InboundRow {
  key: number;
  materialId: string;
  quantity: string;
  unitCost: string;
  lotId: string;
}

export default function InventoryPage() {
  const [materials, setMaterials] = useState<InventoryMaterial[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [warehouses, setWarehouses] = useState<string[]>([]);
  const [warehouseFilter, setWarehouseFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<MaterialForm>(materialFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  const [inboundOpen, setInboundOpen] = useState(false);
  const [inboundWarehouse, setInboundWarehouse] = useState("");
  const [inboundRows, setInboundRows] = useState<InboundRow[]>([freshRow(0)]);
  const [inboundError, setInboundError] = useState("");
  const [inboundSaving, setInboundSaving] = useState(false);
  const [inboundNote, setInboundNote] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const [materialPage, stockPage, warehouseList] = await Promise.all([
        listInventoryMaterials({ limit: 200 }),
        listInventoryStocks({ warehouse: warehouseFilter || undefined, limit: 200 }),
        listInventoryWarehouses(),
      ]);
      setMaterials(materialPage.records);
      setStocks(stockPage.records);
      setWarehouses(warehouseList);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载库存数据"));
    } finally {
      setLoading(false);
    }
  }, [warehouseFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const activeMaterials = useMemo(
    () => materials.filter((m) => m.status === "ACTIVE"),
    [materials],
  );

  async function handleCreateMaterial() {
    const f = createForm;
    if (!f.code.trim() || !f.name.trim() || !f.category.trim() || !f.baseUnit.trim()) {
      setCreateError("物资编码、名称、类别与基础单位不能为空");
      return;
    }
    const scale = Number(f.quantityScale);
    if (!Number.isInteger(scale) || scale < 0 || scale > 6) {
      setCreateError("数量精度必须为 0–6 的整数");
      return;
    }

    setCreating(true);
    setCreateError("");
    try {
      await createInventoryMaterial({
        code: f.code.trim(),
        name: f.name.trim(),
        category: f.category.trim(),
        base_unit: f.baseUnit.trim(),
        quantity_scale: scale,
        status: "ACTIVE",
      });
      setCreateOpen(false);
      setCreateForm(materialFormDefaults);
      await load();
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建物资"));
    } finally {
      setCreating(false);
    }
  }

  // ——— 入库 ———
  function freshRow(key: number): InboundRow {
    return { key, materialId: "", quantity: "", unitCost: "", lotId: "" };
  }

  function updateRow(key: number, patch: Partial<InboundRow>) {
    setInboundRows((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  }

  function openInbound() {
    setInboundWarehouse(warehouseFilter || warehouses[0] || "");
    setInboundRows([freshRow(Date.now())]);
    setInboundError("");
    setInboundNote("");
    setInboundOpen(true);
  }

  function validateInbound(): InventoryInboundItem[] | null {
    const items: InventoryInboundItem[] = [];
    for (const row of inboundRows) {
      if (!row.materialId) {
        setInboundError("请为每一行选择物资");
        return null;
      }
      const quantity = Number(row.quantity);
      if (!Number.isFinite(quantity) || quantity <= 0) {
        setInboundError("基础数量必须为正数");
        return null;
      }
      const unitCost = Number(row.unitCost);
      if (!Number.isFinite(unitCost) || unitCost < 0) {
        setInboundError("单位成本必须 ≥ 0");
        return null;
      }
      items.push({
        material_id: row.materialId,
        quantity,
        unit_cost: unitCost,
        ...(row.lotId.trim() ? { lot_id: row.lotId.trim() } : {}),
      });
    }
    return items;
  }

  async function handleInbound() {
    if (!inboundWarehouse) {
      setInboundError("仓库不能为空");
      return;
    }
    const items = validateInbound();
    if (!items) return;
    setInboundSaving(true);
    setInboundError("");
    try {
      await confirmInventoryInbound({
        warehouse: inboundWarehouse,
        items,
        ...(inboundNote.trim() ? { note: inboundNote.trim() } : {}),
      });
      setInboundOpen(false);
      await load();
    } catch (error) {
      setInboundError(errorMessage(error, "无法确认入库"));
    } finally {
      setInboundSaving(false);
    }
  }

  // ——— 表格 ———
  const materialColumns: Column<InventoryMaterial>[] = [
    { key: "code", header: "编码", className: "min-w-[120px] font-mono text-xs" },
    { key: "name", header: "名称", className: "min-w-[180px]", render: (row) => <span className="font-medium text-fg-emphasis">{row.name}</span> },
    { key: "category", header: "类别", className: "min-w-[120px]" },
    { key: "base_unit", header: "基础单位", className: "min-w-[120px] font-mono text-xs" },
    { key: "quantity_scale", header: "精度（小数位）", className: "min-w-[100px] text-right font-mono text-xs", render: (row) => String(row.quantity_scale ?? 0) },
    { key: "status", header: "状态", className: "min-w-[100px]" },
  ];

  const stockColumns: Column<InventoryStockAvailability>[] = [
    { key: "warehouse", header: "仓库", className: "min-w-[100px]" },
    {
      key: "material",
      header: "物资",
      className: "min-w-[200px]",
      render: (row) => (
        <div className="flex flex-col gap-0.5">
          <span className="font-mono text-xs text-fg-dimmed">{row.material_code}</span>
          <span>{row.material_name}</span>
        </div>
      ),
    },
    {
      key: "batch",
      header: "批次",
      className: "min-w-[120px]",
      render: (row) => (
        <div className="flex flex-col gap-0.5">
          <span className="font-mono text-xs">{row.batch_no ?? "-"}</span>
          {row.expiry_date && <span className="text-xs text-fg-dimmed">效期 {row.expiry_date}</span>}
        </div>
      ),
    },
    {
      key: "balance",
      header: "结存 / 锁定 / 可用",
      className: "min-w-[180px] text-right font-mono text-xs",
      render: (row) => (
        <div className="flex flex-col gap-0.5 tabular-nums">
          <span>
            {row.quantity.toFixed(6)} / {row.locked_quantity.toFixed(6)} /{" "}
            <b className="text-fg-emphasis">{row.available_quantity.toFixed(6)}</b> {row.unit}
          </span>
        </div>
      ),
    },
    {
      key: "unit_cost",
      header: "单位成本",
      className: "min-w-[110px] text-right font-mono text-xs tabular-nums",
      render: (row) => row.unit_cost.toFixed(8),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">库存计量</h2>
          <p className="mt-1 text-sm text-fg-muted">每个物资只有一个基础单位和数量精度；所有库存与单据均以基础数量记账</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={openInbound}>手工入库</Button>
          <Button onClick={() => { setCreateForm(materialFormDefaults); setCreateError(""); setCreateOpen(true); }}>新建物资</Button>
        </div>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="物资与基础单位" actions={<span className="text-sm text-fg-dimmed">共 {materials.length} 个</span>}>
        <div className="overflow-x-auto">
          <Table columns={materialColumns} data={materials} loading={loading} emptyMessage="暂无物资" />
        </div>
      </Card>

      <Card
        title="库存查看"
        actions={
          <select
            value={warehouseFilter}
            onChange={(event) => setWarehouseFilter(event.target.value)}
            className="h-9 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            aria-label="按仓库筛选"
          >
            <option value="">全部仓库</option>
            {warehouses.map((warehouse) => (
              <option key={warehouse} value={warehouse}>{warehouse}</option>
            ))}
          </select>
        }
      >
        <div className="overflow-x-auto">
          <Table columns={stockColumns} data={stocks} loading={loading} emptyMessage="暂无可用库存" />
        </div>
      </Card>

      {/* ——— 新建物资 Modal ——— */}
      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="新建物资">
        <div className="space-y-4">
          {createError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="物资编码" value={createForm.code} onChange={(event) => setCreateForm((current) => ({ ...current, code: event.target.value }))} placeholder="例如 M-001" />
            <Input label="物资名称" value={createForm.name} onChange={(event) => setCreateForm((current) => ({ ...current, name: event.target.value }))} placeholder="例如 纱布敷料" />
            <Input label="类别" value={createForm.category} onChange={(event) => setCreateForm((current) => ({ ...current, category: event.target.value }))} placeholder="例如 耗材" />
            <Input label="基础单位" value={createForm.baseUnit} onChange={(event) => setCreateForm((current) => ({ ...current, baseUnit: event.target.value }))} placeholder="例如 片、支、包、mL" />
            <Input label="数量精度（小数位 0–6）" value={createForm.quantityScale} onChange={(event) => setCreateForm((current) => ({ ...current, quantityScale: event.target.value }))} placeholder="0" />
          </div>
          <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
            基础单位与数量精度在存在库存事实后不可修改；系统不接受包装单位、换算率或拆零输入。
          </p>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button onClick={() => void handleCreateMaterial()} loading={creating}>创建</Button>
          </div>
        </div>
      </Modal>

      {/* ——— 手工入库 Modal ——— */}
      <Modal open={inboundOpen} onClose={() => !inboundSaving && setInboundOpen(false)} title="手工入库">
        <div className="space-y-4">
          {inboundError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{inboundError}</div>}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="inbound-warehouse">仓库</label>
            <select
              id="inbound-warehouse"
              value={inboundWarehouse}
              onChange={(event) => setInboundWarehouse(event.target.value)}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {warehouses.map((warehouse) => (
                <option key={warehouse} value={warehouse}>{warehouse}</option>
              ))}
            </select>
          </div>

          {inboundRows.map((row) => {
            const material = materials.find((m) => m.id === row.materialId);
            return (
              <div key={row.key} className="space-y-3 rounded-lg border border-border bg-surface-alt p-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-fg-dimmed">入库明细 {inboundRows.indexOf(row) + 1}</span>
                  {inboundRows.length > 1 && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setInboundRows((rows) => rows.filter((r) => r.key !== row.key))}
                      disabled={inboundSaving}
                    >
                      移除
                    </Button>
                  )}
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor={`inbound-material-${row.key}`}>物资</label>
                  <select
                    id={`inbound-material-${row.key}`}
                    value={row.materialId}
                    onChange={(event) => updateRow(row.key, { materialId: event.target.value })}
                    className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  >
                    <option value="">选择物资</option>
                    {activeMaterials.map((material) => (
                      <option key={material.id} value={material.id}>
                        {material.code} · {material.name}（{material.base_unit}）
                      </option>
                    ))}
                  </select>
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <Input
                    label={`基础数量（${material?.base_unit ?? "单位"}）`}
                    value={row.quantity}
                    onChange={(event) => updateRow(row.key, { quantity: event.target.value })}
                    placeholder="例如 48"
                  />
                  <Input
                    label={`单位成本（每${material?.base_unit ?? "基础单位"}）`}
                    value={row.unitCost}
                    onChange={(event) => updateRow(row.key, { unitCost: event.target.value })}
                    placeholder="例如 1.5"
                  />
                </div>
                <Input
                  label="批次号（批控物资必填）"
                  value={row.lotId}
                  onChange={(event) => updateRow(row.key, { lotId: event.target.value })}
                  placeholder="例如 LOT-20260807"
                />
              </div>
            );
          })}

          <Button variant="secondary" size="sm" onClick={() => setInboundRows((rows) => [...rows, freshRow(Date.now())])} disabled={inboundSaving}>
            添加明细行
          </Button>
          <Input
            label="备注"
            value={inboundNote}
            onChange={(event) => setInboundNote(event.target.value)}
            placeholder="可选"
          />
          <p className="text-xs text-fg-dimmed">
            数量按物资基础单位提交，超精度、包装字段或换算字段会被服务端以 400 拒绝；提交期间请勿重复点击。
          </p>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setInboundOpen(false)} disabled={inboundSaving}>取消</Button>
            <Button onClick={() => void handleInbound()} loading={inboundSaving}>确认入库</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
