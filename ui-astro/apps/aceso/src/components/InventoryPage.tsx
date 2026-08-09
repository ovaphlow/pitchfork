import { useCallback, useEffect, useMemo, useState } from "react";
import {
  confirmInventoryInbound,
  listInventoryLots,
  listInventoryMaterials,
  listInventoryStocks,
  listWarehouseOptions,
  type InventoryInboundItem,
  type InventoryLot,
  type InventoryMaterial,
  type InventoryStockAvailability,
  type WarehouseOption,
} from "@pitchfork/shared/aceso";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 整包 + 余数展示：按物资包装规格换算基础数量（库存账本仍为基础单位） */
function packageBreakdown(material: InventoryMaterial | undefined, baseQty: string): string | null {
  const quantity = Number(baseQty);
  const packageSize = material?.package_size ? Number(material.package_size) : NaN;
  if (!material?.package_unit || !Number.isFinite(quantity) || !Number.isFinite(packageSize) || packageSize <= 0) return null;
  const scale = material.quantity_scale ?? 0;
  const whole = Math.floor(quantity / packageSize);
  const remainder = Number((quantity - whole * packageSize).toFixed(Math.max(scale, 0)));
  const base = material.base_unit;
  const fmt = (n: number) => Number(n.toFixed(scale)).toString();
  if (whole === 0) return `${fmt(remainder)} ${base}`;
  if (remainder === 0) return `${whole} ${material.package_unit}`;
  return `${whole} ${material.package_unit} + ${fmt(remainder)} ${base}`;
}

/** 整包数 × 每包含量 + 余数 = 基础数量 */
function computeBaseTotal(pkgQty: string, remQty: string, size: string): number {
  return (Number(pkgQty) || 0) * (Number(size) || 0) + (Number(remQty) || 0);
}

/** 入库明细行：基础数量 + 每基础单位成本；包装物资用 pkgQty/remQty 录入 */
interface InboundRow {
  key: number;
  materialId: string;
  quantity: string;
  unitCost: string;
  lotId: string;
  pkgQty: string;
  remQty: string;
}

export default function InventoryPage() {
  const [materials, setMaterials] = useState<InventoryMaterial[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [warehouseFilter, setWarehouseFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  const [inboundOpen, setInboundOpen] = useState(false);
  const [inboundWarehouse, setInboundWarehouse] = useState("");
  const [inboundRows, setInboundRows] = useState<InboundRow[]>([freshRow(0)]);
  const [inboundError, setInboundError] = useState("");
  const [inboundSaving, setInboundSaving] = useState(false);
  const [inboundNote, setInboundNote] = useState("");
  const [lotsByMaterial, setLotsByMaterial] = useState<Record<string, InventoryLot[]>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const [materialPage, stockPage, warehouseList] = await Promise.all([
        listInventoryMaterials({ limit: 200 }),
        listInventoryStocks({ warehouse: warehouseFilter || undefined, limit: 200 }),
        listWarehouseOptions(),
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

  const materialByCode = useMemo(
    () => new Map(materials.map((m) => [m.code, m])),
    [materials],
  );

  // ——— 入库 ———
  function freshRow(key: number): InboundRow {
    return { key, materialId: "", quantity: "", unitCost: "", lotId: "", pkgQty: "", remQty: "" };
  }

  function updateRow(key: number, patch: Partial<InboundRow>) {
    setInboundRows((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  }

  async function fetchMaterialLots(materialId: string) {
    if (lotsByMaterial[materialId]) return;
    try {
      const page = await listInventoryLots({ material_id: materialId, limit: 200 });
      setLotsByMaterial((current) => ({ ...current, [materialId]: page.records }));
    } catch {
      setLotsByMaterial((current) => ({ ...current, [materialId]: [] }));
    }
  }

  function openInbound() {
    setInboundWarehouse(warehouseFilter || warehouses[0]?.code || "");
    setInboundRows([freshRow(Date.now())]);
    setInboundError("");
    setInboundNote("");
    setInboundOpen(true);
  }

  function validateInbound(): InventoryInboundItem[] | null {
    const items: InventoryInboundItem[] = [];
    for (const row of inboundRows) {
      const material = materials.find((m) => m.id === row.materialId);
      if (!row.materialId) {
        setInboundError("请为每一行选择物资");
        return null;
      }
      if (material?.enable_batch_control === true && !row.lotId.trim()) {
        setInboundError("批控物资必须选择批次");
        return null;
      }
      const packaged = material?.package_unit && material.package_size ? material : undefined;
      let quantity: string;
      if (packaged) {
        const size = Number(packaged.package_size);
        const p = Number(row.pkgQty) || 0;
        const r = Number(row.remQty) || 0;
        if (p < 0 || r < 0) {
          setInboundError("整包数与余数不能为负数");
          return null;
        }
        quantity = String(p * size + r);
      } else {
        quantity = row.quantity.trim();
      }
      if (!Number.isFinite(Number(quantity)) || Number(quantity) <= 0) {
        setInboundError("基础数量必须为正数");
        return null;
      }
      const unitCost = row.unitCost.trim();
      if (!Number.isFinite(Number(unitCost)) || Number(unitCost) < 0) {
        setInboundError("单位成本必须 ≥ 0");
        return null;
      }
      items.push({
        material_id: row.materialId,
        quantity,
        unit_cost: unitCost,
        ...(material?.enable_batch_control === true && row.lotId.trim() ? { lot_id: row.lotId.trim() } : {}),
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
      className: "min-w-[200px] text-right font-mono text-xs",
      render: (row) => {
        const pkg = packageBreakdown(materialByCode.get(row.material_code), row.quantity);
        return (
          <div className="flex flex-col gap-0.5 tabular-nums">
            <span>
              {row.quantity} / {row.locked_quantity} /{" "}
              <b className="text-fg-emphasis">{row.available_quantity}</b> {row.unit}
            </span>
            {pkg && <span className="text-xs text-fg-dimmed">整包：{pkg}</span>}
          </div>
        );
      },
    },
    {
      key: "unit_cost",
      header: "单位成本",
      className: "min-w-[110px] text-right font-mono text-xs tabular-nums",
      render: (row) => row.unit_cost,
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">库存计量</h2>
          <p className="mt-1 text-sm text-fg-muted">查看各仓库库存结存、锁定与可用数量；手工入库按物资基础单位记账</p>
        </div>
        <Button variant="secondary" onClick={openInbound}>手工入库</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

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
              <option key={warehouse.code} value={warehouse.code}>{warehouse.name}</option>
            ))}
          </select>
        }
      >
        <div className="overflow-x-auto">
          <Table columns={stockColumns} data={stocks} loading={loading} emptyMessage="暂无可用库存" />
        </div>
      </Card>

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
                <option key={warehouse.code} value={warehouse.code}>{warehouse.name}</option>
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
                    onChange={(event) => {
                      updateRow(row.key, { materialId: event.target.value, lotId: "", pkgQty: "", remQty: "" });
                      if (event.target.value) void fetchMaterialLots(event.target.value);
                    }}
                    className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  >
                    <option value="">选择物资</option>
                    {activeMaterials.map((material) => (
                      <option key={material.id} value={material.id}>
                        {material.code} · {material.name}（{material.base_unit}）{material.enable_batch_control === true ? " · 批控" : ""}{material.package_unit ? ` · 1${material.package_unit}=${material.package_size}${material.base_unit}` : ""}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {material?.package_unit && material.package_size ? (
                    <>
                      <Input
                        label={`整包数（${material.package_unit}）`}
                        value={row.pkgQty}
                        onChange={(event) => updateRow(row.key, { pkgQty: event.target.value })}
                        placeholder="例如 2"
                      />
                      <Input
                        label={`余数（${material.base_unit}）`}
                        value={row.remQty}
                        onChange={(event) => updateRow(row.key, { remQty: event.target.value })}
                        placeholder="例如 5"
                      />
                      <Input
                        label={`单位成本（每${material.base_unit}）`}
                        value={row.unitCost}
                        onChange={(event) => updateRow(row.key, { unitCost: event.target.value })}
                        placeholder="例如 1.5"
                      />
                      <div className="flex items-end pb-1">
                        <span className="text-sm text-fg-muted">
                          合计基础数量：<b className="font-mono text-fg-emphasis">{computeBaseTotal(row.pkgQty, row.remQty, material.package_size)}</b> {material.base_unit}
                        </span>
                      </div>
                    </>
                  ) : (
                    <>
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
                    </>
                  )}
                </div>
                {material?.enable_batch_control === true && (
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor={`inbound-lot-${row.key}`}>批次（批控物资必选）</label>
                    <select
                      id={`inbound-lot-${row.key}`}
                      value={row.lotId}
                      onChange={(event) => updateRow(row.key, { lotId: event.target.value })}
                      className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                    >
                      <option value="">选择批次</option>
                      {(lotsByMaterial[material.id] ?? []).map((lot) => (
                        <option key={lot.id} value={lot.id}>
                          {lot.batch_no}{lot.expiry_date ? `（效期 ${lot.expiry_date}）` : ""}
                        </option>
                      ))}
                    </select>
                    {(lotsByMaterial[material.id] ?? []).length === 0 && (
                      <p className="text-xs text-fg-dimmed">该物资暂无可用批次，需先创建批次才能入库</p>
                    )}
                  </div>
                )}
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
