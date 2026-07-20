import { useState, useEffect, useCallback } from "react";
import { listDepartments, createDepartment, updateDepartment, deleteDepartment } from "@pitchfork/shared/aceso";
import { Table, type Column, Card } from "@pitchfork/ui";

interface Department {
  id: string;
  code: string;
  parent_code: string;
  payload: { name: string; description?: string };
  created_at: string;
  updated_at: string;
}

export default function DepartmentsPage() {
  const [depts, setDepts] = useState<Department[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Department | null>(null);
  const [form, setForm] = useState({ name: "", code: "", parent_code: "", description: "" });
  const [saving, setSaving] = useState(false);

  const fetch = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listDepartments() as Department[];
      setDepts(res ?? []);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  function openAdd() {
    setEditTarget(null);
    setForm({ name: "", code: "", parent_code: "", description: "" });
    setModalOpen(true);
  }

  function openEdit(d: Department) {
    setEditTarget(d);
    setForm({
      name: d.payload.name,
      code: d.code,
      parent_code: d.parent_code,
      description: d.payload.description ?? "",
    });
    setModalOpen(true);
  }

  async function handleSave() {
    if (!form.name.trim() || !form.code.trim()) return;
    setSaving(true);
    try {
      if (editTarget) {
        await updateDepartment(editTarget.id, {
          name: form.name,
          code: form.code,
          parent_code: form.parent_code,
          description: form.description,
        });
      } else {
        await createDepartment({
          name: form.name,
          code: form.code,
          parent_code: form.parent_code,
          description: form.description,
        });
      }
      setModalOpen(false);
      fetch();
    } catch {
      // ignore
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(d: Department) {
    if (!confirm(`确认删除部门「${d.payload.name}」？`)) return;
    await deleteDepartment(d.id);
    fetch();
  }

  function buildTree(list: Department[]): (Department & { depth: number })[] {
    const map = new Map<string, Department & { depth: number }>();
    const result: (Department & { depth: number })[] = [];
    // ponytail: O(n²) on each render, fine for <1000 depts
    function add(node: Department, depth: number) {
      if (map.has(node.id)) return;
      const item = { ...node, depth };
      map.set(node.id, item);
      result.push(item);
      const children = list.filter((d) => d.parent_code === node.code);
      for (const child of children) add(child, depth + 1);
    }
    const roots = list.filter((d) => !d.parent_code);
    for (const root of roots) add(root, 0);
    // append any remaining that weren't reached (broken parent refs)
    for (const d of list) if (!map.has(d.id)) result.push({ ...d, depth: 0 });
    return result;
  }

  const tree = buildTree(depts);

  const columns: Column<Department & { depth: number }>[] = [
    { key: "code", header: "编码", className: "w-[120px]" },
    {
      key: "name",
      header: "名称",
      className: "min-w-[200px]",
      render: (row) => (
        <div style={{ paddingLeft: `${row.depth * 1.5}rem` }} className="flex items-center gap-2">
          {row.depth > 0 && <span className="text-fg-dimmed text-xs">└─</span>}
          <span>{row.payload.name}</span>
          <span className="text-fg-dimmed text-xs">({row.code})</span>
        </div>
      ),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[140px]",
      render: (row) => (
        <div className="flex items-center gap-3">
          <button onClick={() => openEdit(row)} className="text-xs text-fg-muted hover:text-fg cursor-pointer bg-transparent border-none">编辑</button>
          <button onClick={() => handleDelete(row)} className="text-xs text-danger hover:brightness-110 cursor-pointer bg-transparent border-none">删除</button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">部门管理</h2>
          <p className="text-sm text-fg-muted mt-1">管理组织架构与部门信息</p>
        </div>
        <button onClick={openAdd} className="h-9 px-4 rounded-md bg-accent text-white text-sm font-medium hover:brightness-110 cursor-pointer border-none transition-all">
          添加部门
        </button>
      </div>

      <Card>
        <div className="flex items-center gap-3 mb-4">
          <span className="text-xs text-fg-dimmed ml-auto">共 {depts.length} 条</span>
        </div>
        <Table columns={columns} data={tree} loading={loading} emptyMessage="暂无部门" />
      </Card>

      {modalOpen && (
        <div style={{ position: "fixed", inset: 0, zIndex: 50, display: "flex", alignItems: "center", justifyContent: "center", background: "rgba(0,0,0,0.6)", padding: "1rem" }} onClick={() => setModalOpen(false)}>
          <div style={{ background: "var(--color-surface-overlay)", border: "1px solid var(--color-border)", borderRadius: "0.5rem", width: "100%", maxWidth: "32rem", maxHeight: "85vh", overflowY: "auto", boxShadow: "var(--shadow-overlay)" }} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "1.25rem 2rem", borderBottom: "1px solid var(--color-border)" }}>
              <h3 style={{ fontSize: "1rem", fontWeight: 600, color: "var(--color-fg-emphasis)" }}>{editTarget ? "编辑部门" : "添加部门"}</h3>
              <button onClick={() => setModalOpen(false)} style={{ width: "2rem", height: "2rem", display: "flex", alignItems: "center", justifyContent: "center", background: "transparent", border: "none", borderRadius: "0.375rem", color: "var(--color-fg-muted)", cursor: "pointer" }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div style={{ padding: "2rem" }}>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">名称 *</label>
                  <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">编码 *</label>
                  <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">上级编码</label>
                  <input value={form.parent_code} onChange={(e) => setForm({ ...form, parent_code: e.target.value })} placeholder="留空为根节点" className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">描述</label>
                  <textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={3} className="w-full px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent resize-none" />
                </div>
                <div className="flex justify-end gap-3 pt-2">
                  <button onClick={() => setModalOpen(false)} className="h-9 px-4 rounded-md bg-surface border border-border text-sm text-fg-muted hover:text-fg cursor-pointer border-none">取消</button>
                  <button onClick={handleSave} disabled={saving || !form.name.trim() || !form.code.trim()} className="h-9 px-4 rounded-md bg-accent text-white text-sm font-medium hover:brightness-110 disabled:opacity-50 cursor-pointer border-none">{saving ? "保存中..." : "保存"}</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
