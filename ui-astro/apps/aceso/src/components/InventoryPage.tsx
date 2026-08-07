import { useCallback, useEffect, useMemo, useState } from "react";
import {
  activateMaterialUnitModel,
  confirmInventoryInbound,
  createInventoryMaterial,
  createMaterialUnitSpec,
  listInventoryMaterials,
  listInventoryStocks,
  listInventoryWarehouses,
  listMaterialUnitSpecs,
  retireMaterialUnitSpec,
  type InventoryInboundItem,
  type InventoryMaterial,
  type InventoryStockAvailability,
  type InventoryUnitModelView,
  type InventoryUnitSpec,
} from "@pitchfork/shared/aceso";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function modelStatusBadge(status: string | null | undefined) {
  switch (status ?? "LEGACY") {
    case "ACTIVE":
      return <Badge variant="success">计量模型</Badge>;
    case "MIGRATION_BLOCKED":
      return <Badge variant="danger">迁移阻断</Badge>;
    default:
      return <Badge variant="default">旧计量</Badge>;
  }
}

function specStatusBadge(spec: InventoryUnitSpec) {
  if (spec.status !== "ACTIVE") return <Badge variant="default">已停用</Badge>;
  if (spec.is_base_unit) return <Badge variant="info">基础单位</Badge>;
  if (spec.is_default) return <Badge variant="success">默认规格</Badge>;
  return <Badge variant="default">活动</Badge>;
}

/** 物资 + 首次单位模型（同一受控表单） */
interface MaterialForm {
  code: string;
  name: string;
  category: string;
  packageUnit: string;
  baseUnit: string;
  baseQuantityScale: string;
  defaultInputUnit: string;
  defaultBaseRatio: string;
}

const materialFormDefaults: MaterialForm = {
  code: "",
  name: "",
  category: "",
  packageUnit: "",
  baseUnit: "",
  baseQuantityScale: "0",
  defaultInputUnit: "",
  defaultBaseRatio: "1",
};

/** 入库明细行 */
interface InboundRow {
  key: number;
  materialId: string;
  unitSpecId: string;
  inputQuantity: string;
  inputUnitCost: string;
  lotId: string;
}

export default function InventoryPage() {
  const [materials, setMaterials] = useState<InventoryMaterial[]>([]);
  const [stocks, setStocks] = useState<InventoryStockAvailability[]>([]);
  const [warehouses, setWarehouses] = useState<string[]>([]);
  const [warehouseFilter, setWarehouseFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  // 物资 → 单位模型视图（规格管理 Modal）
  const [modelMaterial, setModelMaterial] = useState<InventoryMaterial | null>(null);
  const [modelView, setModelView] = useState<InventoryUnitModelView | null>(null);
  const [modelError, setModelError] = useState("");
  const [modelLoading, setModelLoading] = useState(false);

  // 新建物资 + 单位模型
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<MaterialForm>(materialFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  // 新增规格
  const [specFormOpen, setSpecFormOpen] = useState(false);
  const [specInputUnit, setSpecInputUnit] = useState("");
  const [specRatio, setSpecRatio] = useState("1");
  const [specMakeDefault, setSpecMakeDefault] = useState(false);
  const [specError, setSpecError] = useState("");
  const [specSaving, setSpecSaving] = useState(false);

  // 入库
  const [inboundOpen, setInboundOpen] = useState(false);
  const [inboundWarehouse, setInboundWarehouse] = useState("");
  const [inboundRows, setInboundRows] = useState<InboundRow[]>([freshRow(0)]);
  const [inboundSpecsByMaterial, setInboundSpecsByMaterial] = useState<Record<string, InventoryUnitSpec[]>>({});
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

  // ——— 单位模型 / 规格 ———
  const activeMaterials = useMemo(
    () => materials.filter((m) => m.status === "ACTIVE" && m.unit_model_status === "ACTIVE"),
    [materials],
  );

  function openModel(material: InventoryMaterial) {
    setModelMaterial(material);
    setModelView(null);
    setModelError("");
    setModelLoading(true);
    listMaterialUnitSpecs(material.id)
      .then(setModelView)
      .catch((error) => setModelError(errorMessage(error, "无法加载单位模型")))
      .finally(() => setModelLoading(false));
  }

  async function handleCreateMaterial() {
    const f = createForm;
    if (!f.code.trim() || !f.name.trim() || !f.category.trim() || !f.packageUnit.trim()) {
      setCreateError("物资编码、名称、类别与包装单位不能为空");
      return;
    }
    if (!f.baseUnit.trim()) {
      setCreateError("基础单位不能为空");
      return;
    }
    const scale = Number(f.baseQuantityScale);
    if (!Number.isInteger(scale) || scale < 0 || scale > 6) {
      setCreateError("基础精度必须为 0–6 的整数");
      return;
    }
    const ratio = Number(f.defaultBaseRatio);
    if (!Number.isFinite(ratio) || ratio <= 0) {
      setCreateError("换算比率必须为正数");
      return;
    }

    setCreating(true);
    setCreateError("");
    try {
      const created = await createInventoryMaterial({
        code: f.code.trim(),
        name: f.name.trim(),
        category: f.category.trim(),
        package_unit: f.packageUnit.trim(),
        status: "ACTIVE",
      });
      await activateMaterialUnitModel(created.id, {
        base_unit: f.baseUnit.trim(),
        base_quantity_scale: scale,
        default_spec: {
          input_unit: f.defaultInputUnit.trim() || f.baseUnit.trim(),
          base_ratio: ratio,
        },
      });
      setCreateOpen(false);
      setCreateForm(materialFormDefaults);
      await load();
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建物资与单位模型"));
    } finally {
      setCreating(false);
    }
  }

  async function handleCreateSpec() {
    if (!modelMaterial) return;
    if (!specInputUnit.trim()) {
      setSpecError("输入单位不能为空");
      return;
    }
    const ratio = Number(specRatio);
    if (!Number.isFinite(ratio) || ratio <= 0) {
      setSpecError("换算比率必须为正数");
      return;
    }
    setSpecSaving(true);
    setSpecError("");
    try {
      await createMaterialUnitSpec(modelMaterial.id, {
        input_unit: specInputUnit.trim(),
        base_ratio: ratio,
        is_default: specMakeDefault,
      });
      setSpecFormOpen(false);
      setSpecInputUnit("");
      setSpecRatio("1");
      setSpecMakeDefault(false);
      await openModel(modelMaterial);
      await load();
    } catch (error) {
      setSpecError(errorMessage(error, "无法新增规格"));
    } finally {
      setSpecSaving(false);
    }
  }

  async function handleRetireSpec(spec: InventoryUnitSpec) {
    if (!modelMaterial) return;
    setModelError("");
    try {
      await retireMaterialUnitSpec(modelMaterial.id, spec.id);
      await openModel(modelMaterial);
      await load();
    } catch (error) {
      setModelError(errorMessage(error, "无法停用规格"));
    }
  }

  // ——— 入库 ———
  function freshRow(key: number): InboundRow {
    return { key, materialId: "", unitSpecId: "", inputQuantity: "", inputUnitCost: "", lotId: "" };
  }

  async function loadSpecsForRow(row: InboundRow) {
    if (!row.materialId) return;
    try {
      const view = await listMaterialUnitSpecs(row.materialId);
      setInboundSpecsByMaterial((current) => ({ ...current, [row.materialId]: view.unit_specs }));
    } catch {
      setInboundSpecsByMaterial((current) => ({ ...current, [row.materialId]: [] }));
    }
  }

  function updateRow(key: number, patch: Partial<InboundRow>) {
    setInboundRows((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  }

  function openInbound() {
    setInboundWarehouse(warehouseFilter || warehouses[0] || "");
    setInboundRows([freshRow(Date.now())]);
    setInboundSpecsByMaterial({});
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
      if (!row.unitSpecId) {
        setInboundError("请为每一行选择活动包装规格");
        return null;
      }
      const inputQuantity = Number(row.inputQuantity);
      if (!Number.isFinite(inputQuantity) || inputQuantity <= 0) {
        setInboundError("输入数量必须为正数");
        return null;
      }
      const inputUnitCost = Number(row.inputUnitCost);
      if (!Number.isFinite(inputUnitCost) || inputUnitCost < 0) {
        setInboundError("输入单位成本必须 ≥ 0");
        return null;
      }
      items.push({
        material_id: row.materialId,
        unit_spec_id: row.unitSpecId,
        input_quantity: inputQuantity,
        input_unit_cost: inputUnitCost,
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
    { key: "package_unit", header: "包装单位", className: "min-w-[100px]" },
    {
      key: "base_unit",
      header: "基础单位",
      className: "min-w-[120px]",
      render: (row) =>
        row.unit_model_status === "ACTIVE" ? (
          <span className="font-mono text-xs">{row.base_unit ?? "-"}</span>
        ) : (
          <span className="text-fg-dimmed">—</span>
        ),
    },
    {
      key: "base_quantity_scale",
      header: "精度",
      className: "min-w-[80px] text-right font-mono text-xs",
      render: (row) => (row.unit_model_status === "ACTIVE" ? String(row.base_quantity_scale ?? 0) : "—"),
    },
    { key: "unit_model_status", header: "计量状态", className: "min-w-[110px]", render: (row) => modelStatusBadge(row.unit_model_status) },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[110px]",
      render: (row) => (
        <Button variant="ghost" size="sm" onClick={() => openModel(row)}>
          单位模型
        </Button>
      ),
    },
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
      key: "base",
      header: "基础结存/锁定/可用",
      className: "min-w-[180px] text-right font-mono text-xs",
      render: (row) => {
        const unit = row.base_unit ?? "";
        return (
          <div className="flex flex-col gap-0.5 tabular-nums">
            <span>{row.base_quantity ?? "-"} / {row.locked_base_quantity ?? "-"} / <b className="text-fg-emphasis">{row.available_base_quantity ?? "-"}</b> {unit}</span>
          </div>
        );
      },
    },
    {
      key: "default_spec",
      header: "默认包装（只读）",
      className: "min-w-[150px]",
      render: (row) => {
        if (row.unit_model_status !== "ACTIVE") return modelStatusBadge(row.unit_model_status);
        return (
          <span className="font-mono text-xs">
            {row.quantity} {row.default_spec_unit ?? "-"}
          </span>
        );
      },
    },
    {
      key: "unit_cost",
      header: "单位成本",
      className: "min-w-[100px] text-right font-mono text-xs tabular-nums",
      render: (row) => row.unit_cost.toFixed(4),
    },
  ];

  const specColumns: Column<InventoryUnitSpec>[] = [
    { key: "input_unit", header: "输入单位", className: "min-w-[140px] font-medium" },
    {
      key: "base_ratio",
      header: "换算为基础单位",
      className: "min-w-[180px] font-mono text-xs",
      render: (row) => `1 ${row.input_unit} = ${row.base_ratio ?? "-"} ${modelView?.base_unit ?? ""}`,
    },
    { key: "status", header: "状态", className: "min-w-[110px]", render: (row) => specStatusBadge(row) },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[100px]",
      render: (row) =>
        row.status === "ACTIVE" && !row.is_base_unit && !row.is_default ? (
          <Button variant="ghost" size="sm" onClick={() => void handleRetireSpec(row)}>
            停用
          </Button>
        ) : (
          <span className="text-xs text-fg-dimmed">—</span>
        ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">库存计量</h2>
          <p className="mt-1 text-sm text-fg-muted">基础单位、包装规格与库存基础数量（仅接受规格换算，客户端不计算换算率）</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={openInbound}>手工入库</Button>
          <Button onClick={() => { setCreateForm(materialFormDefaults); setCreateError(""); setCreateOpen(true); }}>新建物资</Button>
        </div>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="物资与基础单位" actions={<span className="text-sm text-fg-dimmed">共 {materials.length} 个</span>}>
        <Table columns={materialColumns} data={materials} loading={loading} emptyMessage="暂无物资" />
      </Card>

      <Card
        title="库存查看"
        actions={
          <div className="flex items-center gap-2">
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
          </div>
        }
      >
        <Table columns={stockColumns} data={stocks} loading={loading} emptyMessage="暂无可用库存" />
      </Card>

      {/* ——— 单位模型与包装规格 Modal ——— */}
      <Modal open={modelMaterial !== null} onClose={() => setModelMaterial(null)} title={`单位模型 · ${modelMaterial?.name ?? ""}`}>
        <div className="space-y-4">
          {modelError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{modelError}</div>}
          {modelLoading ? (
            <div className="py-8 text-center text-sm text-fg-dimmed">加载中…</div>
          ) : modelView ? (
            <>
              <div className="flex flex-wrap gap-4 rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm">
                <span className="text-fg-muted">基础单位 <b className="ml-1 font-mono text-fg">{modelView.base_unit ?? "-"}</b></span>
                <span className="text-fg-muted">精度 <b className="ml-1 font-mono text-fg">{modelView.base_quantity_scale ?? 0}</b></span>
                <span className="text-fg-muted">状态 {modelStatusBadge(modelView.unit_model_status)}</span>
                {modelMaterial?.unit_model_status === "ACTIVE" && (
                  <span className="text-xs text-fg-dimmed ml-auto">基础单位与精度创建后不可变更，变更请求由服务端拒绝</span>
                )}
              </div>
              <Table columns={specColumns} data={modelView.unit_specs} loading={false} emptyMessage="暂无规格" />
              {modelView.unit_model_status === "ACTIVE" && (
                <div className="flex justify-end">
                  <Button size="sm" onClick={() => { setSpecFormOpen(true); setSpecError(""); }}>新增规格</Button>
                </div>
              )}
            </>
          ) : (
            <EmptyState title="暂无数据" description={modelError || "无法加载单位模型"} />
          )}
        </div>
      </Modal>

      {/* 新增规格 */}
      <Modal open={specFormOpen} onClose={() => !specSaving && setSpecFormOpen(false)} title="新增包装规格">
        <div className="space-y-4">
          {specError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{specError}</div>}
          <Input
            label="输入单位"
            value={specInputUnit}
            onChange={(event) => setSpecInputUnit(event.target.value)}
            placeholder="例如 盒、袋"
          />
          <Input
            label={`换算为基础单位（1 输入单位 = ? ${modelView?.base_unit ?? ""}）`}
            value={specRatio}
            onChange={(event) => setSpecRatio(event.target.value)}
            placeholder="例如 10"
          />
          <label className="flex items-center gap-2 text-sm text-fg-muted">
            <input
              type="checkbox"
              checked={specMakeDefault}
              onChange={(event) => setSpecMakeDefault(event.target.checked)}
              className="h-4 w-4 rounded border-border accent-accent"
            />
            设为当前默认规格（存在未结算锁定库存时服务端将拒绝）
          </label>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setSpecFormOpen(false)} disabled={specSaving}>取消</Button>
            <Button onClick={() => void handleCreateSpec()} loading={specSaving}>保存规格</Button>
          </div>
        </div>
      </Modal>

      {/* ——— 新建物资 + 单位模型 Modal ——— */}
      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="新建物资与单位模型">
        <div className="space-y-4">
          {createError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="物资编码" value={createForm.code} onChange={(event) => setCreateForm((current) => ({ ...current, code: event.target.value }))} placeholder="例如 M-001" />
            <Input label="物资名称" value={createForm.name} onChange={(event) => setCreateForm((current) => ({ ...current, name: event.target.value }))} placeholder="例如 纱布敷料" />
            <Input label="类别" value={createForm.category} onChange={(event) => setCreateForm((current) => ({ ...current, category: event.target.value }))} placeholder="例如 耗材" />
            <Input label="包装单位" value={createForm.packageUnit} onChange={(event) => setCreateForm((current) => ({ ...current, packageUnit: event.target.value }))} placeholder="例如 包" />
            <Input label="基础单位" value={createForm.baseUnit} onChange={(event) => setCreateForm((current) => ({ ...current, baseUnit: event.target.value }))} placeholder="例如 片" />
            <Input label="基础精度（小数位 0–6）" value={createForm.baseQuantityScale} onChange={(event) => setCreateForm((current) => ({ ...current, baseQuantityScale: event.target.value }))} placeholder="0" />
          </div>
          <div className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
            首个默认规格（缺省 = 基础单位 × 1）
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="默认规格输入单位" value={createForm.defaultInputUnit} onChange={(event) => setCreateForm((current) => ({ ...current, defaultInputUnit: event.target.value }))} placeholder="留空则与基础单位一致" />
            <Input label="默认规格换算比率" value={createForm.defaultBaseRatio} onChange={(event) => setCreateForm((current) => ({ ...current, defaultBaseRatio: event.target.value }))} placeholder="1" />
          </div>
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

          {inboundRows.map((row) => (
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
                    const materialId = event.target.value;
                    updateRow(row.key, { materialId, unitSpecId: "" });
                    if (materialId) void loadSpecsForRow({ ...row, materialId });
                  }}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <option value="">选择物资</option>
                  {activeMaterials.map((material) => (
                    <option key={material.id} value={material.id}>
                      {material.code} · {material.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor={`inbound-spec-${row.key}`}>包装规格</label>
                <select
                  id={`inbound-spec-${row.key}`}
                  value={row.unitSpecId}
                  onChange={(event) => updateRow(row.key, { unitSpecId: event.target.value })}
                  disabled={!row.materialId}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:opacity-50"
                >
                  <option value="">选择活动规格</option>
                  {(inboundSpecsByMaterial[row.materialId] ?? [])
                    .filter((spec) => spec.status === "ACTIVE")
                    .map((spec) => (
                      <option key={spec.id} value={spec.id}>
                        {spec.input_unit}（1 = {spec.base_ratio} {modelView?.base_unit ?? ""}）
                      </option>
                    ))}
                </select>
                {row.materialId && !(inboundSpecsByMaterial[row.materialId] ?? []).some((spec) => spec.status === "ACTIVE") && (
                  <p className="text-xs text-danger">该物资没有活动包装规格，无法入库</p>
                )}
              </div>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Input
                  label="输入数量"
                  value={row.inputQuantity}
                  onChange={(event) => updateRow(row.key, { inputQuantity: event.target.value })}
                  placeholder="例如 10"
                />
                <Input
                  label="输入单位成本"
                  value={row.inputUnitCost}
                  onChange={(event) => updateRow(row.key, { inputUnitCost: event.target.value })}
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
          ))}

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
            换算率与基础数量由服务端按所选规格计算；提交期间请勿重复点击。仅活动计量模型物资可入库。
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
