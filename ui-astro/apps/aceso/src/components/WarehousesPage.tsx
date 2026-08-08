import { useCallback, useEffect, useState } from "react";
import {
  createWarehouse,
  deleteWarehouse,
  listWarehouses,
  updateWarehouse,
  type Warehouse,
  type WarehouseInput,
} from "@pitchfork/shared/aceso";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

interface WarehouseForm {
  name: string;
  code: string;
  description: string;
}

const warehouseFormDefaults: WarehouseForm = {
  name: "",
  code: "",
  description: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export default function WarehousesPage() {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Warehouse | null>(null);
  const [form, setForm] = useState<WarehouseForm>(warehouseFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Warehouse | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      setWarehouses(await listWarehouses());
    } catch (error) {
      setPageError(errorMessage(error, "无法加载仓库列表"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function openCreate() {
    setEditTarget(null);
    setForm(warehouseFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function openEdit(warehouse: Warehouse) {
    setEditTarget(warehouse);
    setForm({
      name: warehouse.payload.name,
      code: warehouse.code,
      description: warehouse.payload.description ?? "",
    });
    setFormError("");
    setEditorOpen(true);
  }

  function warehouseInput(): WarehouseInput | null {
    const code = form.code.trim();
    if (!form.name.trim() || !code) {
      setFormError("仓库名称和编码不能为空");
      return null;
    }
    return { code, name: form.name, description: form.description };
  }

  async function handleSave() {
    const input = warehouseInput();
    if (!input) return;

    setSaving(true);
    setFormError("");
    try {
      if (editTarget) {
        await updateWarehouse(editTarget.id, input);
      } else {
        await createWarehouse(input);
      }
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, "无法保存仓库"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await deleteWarehouse(deleteTarget.id);
      setDeleteTarget(null);
      await load();
    } catch (error) {
      setDeleteError(errorMessage(error, "无法删除仓库"));
    } finally {
      setDeleting(false);
    }
  }

  const columns: Column<Warehouse>[] = [
    { key: "code", header: "编码", className: "min-w-[150px] font-mono text-xs" },
    {
      key: "name",
      header: "仓库名称",
      className: "min-w-[240px]",
      render: (row) => row.payload.name,
    },
    {
      key: "description",
      header: "描述",
      className: "min-w-[200px]",
      render: (row) => row.payload.description || "-",
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[140px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" onClick={() => openEdit(row)}>编辑</Button>
          <Button variant="ghost" size="sm" onClick={() => setDeleteTarget(row)}>删除</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">仓库管理</h2>
          <p className="mt-1 text-sm text-fg-muted">管理 Nexus Settings 中的仓库，供药房发药、库存等业务选择</p>
        </div>
        <Button onClick={openCreate}>添加仓库</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="仓库列表" actions={<span className="text-sm text-fg-dimmed">共 {warehouses.length} 个</span>}>
        <Table columns={columns} data={warehouses} loading={loading} emptyMessage="暂无仓库" />
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑仓库" : "添加仓库"}>
        <div className="space-y-4">
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}
          <Input
            label="仓库名称"
            value={form.name}
            onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
            placeholder="请输入仓库名称"
          />
          <Input
            label="仓库编码"
            value={form.code}
            onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
            placeholder="例如 main"
            disabled={editTarget !== null}
          />
          {editTarget && <p className="text-xs text-fg-dimmed">仓库编码创建后不可修改，以保障与库存数据一致。</p>}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="warehouse-description">描述</label>
            <textarea
              id="warehouse-description"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="请输入仓库描述"
            />
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button onClick={() => void handleSave()} loading={saving}>保存</Button>
          </div>
        </div>
      </Modal>

      <Modal open={deleteTarget !== null} onClose={() => !deleting && setDeleteTarget(null)} title="确认删除仓库">
        <div className="space-y-5">
          {deleteError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{deleteError}</div>}
          <p className="text-sm text-fg-muted">将物理删除仓库「{deleteTarget?.payload.name}」，此操作不可恢复。</p>
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setDeleteTarget(null)} disabled={deleting}>取消</Button>
            <Button variant="danger" onClick={() => void handleDelete()} loading={deleting}>确认删除</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
