import { useCallback, useEffect, useState } from "react";
import {
  createInventoryMaterial,
  deleteInventoryMaterial,
  listInventoryMaterials,
  updateInventoryMaterial,
  type InventoryMaterial,
} from "@pitchfork/shared/aceso";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 包装规格解析：同空或同填，每包含量 > 0 */
function parsePackageSpec(
  unit: string,
  size: string,
): { ok: true; unit: string | null; size?: string } | { ok: false; error: string } {
  const u = unit.trim();
  const s = size.trim();
  if (!u && !s) return { ok: true, unit: null };
  if (!u || !s) return { ok: false, error: "包装单位与每包含量需同时填写或同时留空" };
  const n = Number(s);
  if (!Number.isFinite(n) || n <= 0) return { ok: false, error: "每包含量必须为正数" };
  return { ok: true, unit: u, size: s };
}

/** 新建物资：一次性提交 base_unit 与 quantity_scale；包装规格可选 */
interface MaterialForm {
  code: string;
  name: string;
  category: string;
  spec: string;
  manufacturer: string;
  baseUnit: string;
  quantityScale: string;
  packageUnit: string;
  packageSize: string;
}

const materialFormDefaults: MaterialForm = {
  code: "",
  name: "",
  category: "",
  spec: "",
  manufacturer: "",
  baseUnit: "",
  quantityScale: "0",
  packageUnit: "",
  packageSize: "",
};

/** 预置常见物资类别（业务枚举：遵循既有中文值） */
const MATERIAL_CATEGORIES = ["耗材", "药品", "器械", "敷料", "试剂", "办公用品", "其他"];

/** 编辑物资：code 不可改，base_unit/quantity_scale 存在库存后由服务端拒绝 */
interface EditMaterialForm {
  id: string;
  name: string;
  category: string;
  spec: string;
  manufacturer: string;
  baseUnit: string;
  quantityScale: string;
  packageUnit: string;
  packageSize: string;
  metadata: Record<string, unknown> | null;
}

function editFormFromMaterial(material: InventoryMaterial): EditMaterialForm {
  return {
    id: material.id,
    name: material.name,
    category: material.category,
    spec: material.spec ?? "",
    manufacturer: typeof material.metadata?.manufacturer === "string" ? material.metadata.manufacturer : "",
    baseUnit: material.base_unit,
    quantityScale: String(material.quantity_scale ?? 0),
    packageUnit: material.package_unit ?? "",
    packageSize: material.package_size != null ? String(material.package_size) : "",
    metadata: material.metadata,
  };
}

export default function MaterialsPage() {
  const [materials, setMaterials] = useState<InventoryMaterial[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<MaterialForm>(materialFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  const [editOpen, setEditOpen] = useState(false);
  const [editForm, setEditForm] = useState<EditMaterialForm | null>(null);
  const [editError, setEditError] = useState("");
  const [editing, setEditing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const materialPage = await listInventoryMaterials({ limit: 200 });
      setMaterials(materialPage.records);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载物资列表"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

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
    const packageSpec = parsePackageSpec(f.packageUnit, f.packageSize);
    if (!packageSpec.ok) {
      setCreateError(packageSpec.error);
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
        spec: f.spec.trim() || undefined,
        ...(packageSpec.unit ? { package_unit: packageSpec.unit, package_size: packageSpec.size } : {}),
        ...(f.manufacturer.trim() ? { metadata: { manufacturer: f.manufacturer.trim() } } : {}),
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

  // ——— 修改 / 禁用 / 删除 ———
  function openEdit(material: InventoryMaterial) {
    setEditForm(editFormFromMaterial(material));
    setEditError("");
    setEditOpen(true);
  }

  async function handleEditMaterial() {
    if (!editForm) return;
    if (!editForm.name.trim() || !editForm.category.trim() || !editForm.baseUnit.trim()) {
      setEditError("名称、类别与基础单位不能为空");
      return;
    }
    const scale = Number(editForm.quantityScale);
    if (!Number.isInteger(scale) || scale < 0 || scale > 6) {
      setEditError("数量精度必须为 0–6 的整数");
      return;
    }
    const packageSpec = parsePackageSpec(editForm.packageUnit, editForm.packageSize);
    if (!packageSpec.ok) {
      setEditError(packageSpec.error);
      return;
    }
    const manufacturer = editForm.manufacturer.trim();
    const metadata = { ...(editForm.metadata ?? {}) };
    if (manufacturer) metadata.manufacturer = manufacturer;
    else delete metadata.manufacturer;
    setEditing(true);
    setEditError("");
    try {
      await updateInventoryMaterial(editForm.id, {
        name: editForm.name.trim(),
        category: editForm.category.trim(),
        spec: editForm.spec.trim() || null,
        base_unit: editForm.baseUnit.trim(),
        quantity_scale: scale,
        package_unit: packageSpec.unit,
        package_size: packageSpec.size ?? null,
        metadata,
      });
      setEditOpen(false);
      setEditForm(null);
      await load();
    } catch (error) {
      setEditError(errorMessage(error, "无法更新物资"));
    } finally {
      setEditing(false);
    }
  }

  async function toggleMaterialStatus(material: InventoryMaterial, status: "ACTIVE" | "INACTIVE") {
    setPageError("");
    try {
      await updateInventoryMaterial(material.id, { status });
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法更新物资状态"));
    }
  }

  async function handleDeleteMaterial(material: InventoryMaterial) {
    if (!window.confirm(`确认删除物资「${material.name}」（${material.code}）？删除后不可恢复。`)) return;
    setPageError("");
    try {
      await deleteInventoryMaterial(material.id);
      await load();
    } catch (error) {
      const msg = errorMessage(error, "无法删除物资");
      setPageError(/foreign|constraint/i.test(msg) ? "该物资已有库存或单据记录，无法删除；可改为禁用" : msg);
    }
  }

  // ——— 表格 ———
  const materialColumns: Column<InventoryMaterial>[] = [
    { key: "code", header: "编码", className: "min-w-[120px] font-mono text-xs" },
    { key: "name", header: "名称", className: "min-w-[180px]", render: (row) => <span className="font-medium text-fg-emphasis">{row.name}</span> },
    { key: "category", header: "类别", className: "min-w-[120px]" },
    { key: "spec", header: "规格", className: "min-w-[120px]", render: (row) => row.spec || "-" },
    { key: "manufacturer", header: "生产厂家", className: "min-w-[160px]", render: (row) => (typeof row.metadata?.manufacturer === "string" ? row.metadata.manufacturer : "-") },
    { key: "base_unit", header: "基础单位", className: "min-w-[120px] font-mono text-xs" },
    { key: "quantity_scale", header: "精度（小数位）", className: "min-w-[100px] text-right font-mono text-xs", render: (row) => String(row.quantity_scale ?? 0) },
    { key: "status", header: "状态", className: "min-w-[100px]" },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[190px]",
      render: (row) => (
        <div className="flex gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => openEdit(row)}>修改</Button>
          {row.status === "ACTIVE" ? (
            <Button variant="ghost" size="sm" onClick={() => void toggleMaterialStatus(row, "INACTIVE")}>禁用</Button>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => void toggleMaterialStatus(row, "ACTIVE")}>启用</Button>
          )}
          <Button variant="ghost" size="sm" className="text-danger" onClick={() => void handleDeleteMaterial(row)}>删除</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">物资管理</h2>
          <p className="mt-1 text-sm text-fg-muted">维护物资编码、名称、类别、基础单位与数量精度；基础单位与精度存在库存事实后不可修改</p>
        </div>
        <Button onClick={() => { setCreateForm(materialFormDefaults); setCreateError(""); setCreateOpen(true); }}>新建物资</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="物资与基础单位" actions={<span className="text-sm text-fg-dimmed">共 {materials.length} 个</span>}>
        <div className="overflow-x-auto">
          <Table columns={materialColumns} data={materials} loading={loading} emptyMessage="暂无物资" />
        </div>
      </Card>

      {/* ——— 新建物资 Modal ——— */}
      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="新建物资">
        <div className="space-y-4">
          {createError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="物资编码" value={createForm.code} onChange={(event) => setCreateForm((current) => ({ ...current, code: event.target.value }))} placeholder="例如 M-001" />
            <Input label="物资名称" value={createForm.name} onChange={(event) => setCreateForm((current) => ({ ...current, name: event.target.value }))} placeholder="例如 纱布敷料" />
            <Input label="规格" value={createForm.spec} onChange={(event) => setCreateForm((current) => ({ ...current, spec: event.target.value }))} placeholder="例如 10cm×10cm（可选）" />
            <Input label="生产厂家" value={createForm.manufacturer} onChange={(event) => setCreateForm((current) => ({ ...current, manufacturer: event.target.value }))} placeholder="例如 某某医疗（可选）" />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="material-category">类别</label>
              <select
                id="material-category"
                value={createForm.category}
                onChange={(event) => setCreateForm((current) => ({ ...current, category: event.target.value }))}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">选择类别</option>
                {MATERIAL_CATEGORIES.map((category) => (
                  <option key={category} value={category}>{category}</option>
                ))}
              </select>
            </div>
            <Input label="基础单位" value={createForm.baseUnit} onChange={(event) => setCreateForm((current) => ({ ...current, baseUnit: event.target.value }))} placeholder="例如 片、支、包、mL" />
            <Input label="数量精度（小数位 0–6）" value={createForm.quantityScale} onChange={(event) => setCreateForm((current) => ({ ...current, quantityScale: event.target.value }))} placeholder="0" />
            <Input label="包装单位（可选）" value={createForm.packageUnit} onChange={(event) => setCreateForm((current) => ({ ...current, packageUnit: event.target.value }))} placeholder="例如 盒、包、箱" />
            <Input label="每包含基础单位数（可选）" value={createForm.packageSize} onChange={(event) => setCreateForm((current) => ({ ...current, packageSize: event.target.value }))} placeholder="例如 24（1 盒 = 24 粒）" />
          </div>
          <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
            基础单位与数量精度在存在库存事实后不可修改；包装规格仅用于整包/拆零展示与换算，库存仍以基础单位记账，可随时调整。
          </p>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button onClick={() => void handleCreateMaterial()} loading={creating}>创建</Button>
          </div>
        </div>
      </Modal>

      {/* ——— 编辑物资 Modal ——— */}
      <Modal open={editOpen} onClose={() => !editing && setEditOpen(false)} title="编辑物资">
        {editForm && (
          <div className="space-y-4">
            {editError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{editError}</div>}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Input label="物资名称" value={editForm.name} onChange={(event) => setEditForm((current) => current && ({ ...current, name: event.target.value }))} placeholder="例如 纱布敷料" />
              <Input label="规格" value={editForm.spec} onChange={(event) => setEditForm((current) => current && ({ ...current, spec: event.target.value }))} placeholder="例如 10cm×10cm（可选）" />
              <Input label="生产厂家" value={editForm.manufacturer} onChange={(event) => setEditForm((current) => current && ({ ...current, manufacturer: event.target.value }))} placeholder="例如 某某医疗（可选）" />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="edit-material-category">类别</label>
                <select
                  id="edit-material-category"
                  value={editForm.category}
                  onChange={(event) => setEditForm((current) => current && ({ ...current, category: event.target.value }))}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  {!MATERIAL_CATEGORIES.includes(editForm.category) && (
                    <option value={editForm.category}>{editForm.category}（既有）</option>
                  )}
                  {MATERIAL_CATEGORIES.map((category) => (
                    <option key={category} value={category}>{category}</option>
                  ))}
                </select>
              </div>
              <Input label="基础单位" value={editForm.baseUnit} onChange={(event) => setEditForm((current) => current && ({ ...current, baseUnit: event.target.value }))} placeholder="例如 片、支、包、mL" />
              <Input label="数量精度（小数位 0–6）" value={editForm.quantityScale} onChange={(event) => setEditForm((current) => current && ({ ...current, quantityScale: event.target.value }))} placeholder="0" />
              <Input label="包装单位（可选）" value={editForm.packageUnit} onChange={(event) => setEditForm((current) => current && ({ ...current, packageUnit: event.target.value }))} placeholder="例如 盒、包、箱" />
              <Input label="每包含基础单位数（可选）" value={editForm.packageSize} onChange={(event) => setEditForm((current) => current && ({ ...current, packageSize: event.target.value }))} placeholder="例如 24（1 盒 = 24 粒）" />
            </div>
            <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
              物资编码不可修改；基础单位与数量精度在存在库存事实后不可修改（服务端会以 409 拒绝），包装规格可随时调整。
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <Button variant="ghost" onClick={() => setEditOpen(false)} disabled={editing}>取消</Button>
              <Button onClick={() => void handleEditMaterial()} loading={editing}>保存</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
