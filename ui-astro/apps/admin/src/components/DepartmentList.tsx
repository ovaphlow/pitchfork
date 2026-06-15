import { useState, useEffect, useMemo } from "react";
import { listDepartments, createDepartment, updateDepartment, deleteDepartment } from "@pitchfork/shared";
import { Button, Input, Card } from "@pitchfork/ui";

interface Department {
  id: string;
  name: string;
  code: string;
  parent_code?: string;
  payload: { name: string; description?: string };
  created_at?: string;
  updated_at?: string;
}

interface TreeNode extends Department {
  children: TreeNode[];
  depth: number;
}

// ─── Recursive tree node ────────────────────────────────────────

function TreeNodeRow({
  node,
  allDepts,
  onEdit,
  onDelete,
  onAddChild,
  expanded,
  onToggle,
  selectedParentCode,
  onSelectParent,
  depth,
}: {
  node: TreeNode;
  allDepts: Department[];
  onEdit: (d: Department) => void;
  onDelete: (id: string) => void;
  onAddChild: (parentCode: string) => void;
  expanded: Record<string, boolean>;
  onToggle: (code: string) => void;
  selectedParentCode: string;
  onSelectParent: (code: string) => void;
  depth: number;
}) {
  const hasChildren = node.children.length > 0;
  const isExpanded = expanded[node.code] ?? hasChildren;

  return (
    <>
      <div
        className="flex items-center gap-2 group"
        style={{ paddingLeft: `${depth * 24 + 8}px` }}
      >
        {/* Expand/Collapse */}
        <button
          onClick={() => onToggle(node.code)}
          className="w-5 h-5 shrink-0 flex items-center justify-center text-fg-dimmed hover:text-fg cursor-pointer border-none bg-transparent"
        >
          {hasChildren ? (
            <svg className={`w-4 h-4 transition-transform ${isExpanded ? "rotate-90" : ""}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="9 18 15 12 9 6" />
            </svg>
          ) : (
            <span className="w-4" />
          )}
        </button>

        {/* Radio dot for parent selector */}
        <input
          type="radio"
          name="parentCode"
          checked={selectedParentCode === node.code}
          onChange={() => onSelectParent(node.code)}
          className="shrink-0 accent-accent cursor-pointer"
        />

        {/* Icon */}
        <div className="w-6 h-6 rounded-full bg-accent/20 text-accent flex items-center justify-center text-xs font-medium shrink-0">
          {node.payload?.name?.charAt(0) || node.name?.charAt(0) || "?"}
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <span className="text-sm font-medium text-fg-emphasis">{node.payload?.name || node.name}</span>
          <span className="text-xs text-fg-dimmed ml-2">({node.code})</span>
          {node.payload?.description && (
            <span className="text-xs text-fg-dimmed ml-2 hidden sm:inline">{node.payload.description}</span>
          )}
        </div>

        {/* Actions */}
        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <Button variant="ghost" size="sm" onClick={() => onAddChild(node.code)} className="text-xs">+ 子部门</Button>
          <Button variant="ghost" size="sm" className="text-orange-500" onClick={() => onEdit(node)}>编辑</Button>
          <Button variant="ghost" size="sm" className="text-red-500" onClick={() => onDelete(node.id)}>删除</Button>
        </div>
      </div>

      {/* Children */}
      {hasChildren && isExpanded && (
        <div>
          {node.children.map((child) => (
            <TreeNodeRow
              key={child.id}
              node={child}
              allDepts={allDepts}
              onEdit={onEdit}
              onDelete={onDelete}
              onAddChild={onAddChild}
              expanded={expanded}
              onToggle={onToggle}
              selectedParentCode={selectedParentCode}
              onSelectParent={onSelectParent}
              depth={depth + 1}
            />
          ))}
        </div>
      )}
    </>
  );
}

// ─── Main component ─────────────────────────────────────────────

export default function DepartmentList() {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Department | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState("");
  const [formCode, setFormCode] = useState("");
  const [formParentCode, setFormParentCode] = useState("");
  const [formDescription, setFormDescription] = useState("");
  const [message, setMessage] = useState("");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const fetchData = () => {
    setLoading(true);
    listDepartments()
      .then((data) => setDepartments(data as Department[]))
      .catch(() => setDepartments([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  // Build tree
  const tree = useMemo(() => {
    const map = new Map<string, TreeNode>();
    const roots: TreeNode[] = [];

    // Create nodes
    for (const d of departments) {
      map.set(d.code, { ...d, children: [], depth: 0 });
    }

    // Link children
    for (const d of departments) {
      const node = map.get(d.code)!;
      if (d.parent_code && map.has(d.parent_code)) {
        map.get(d.parent_code)!.children.push(node);
      } else {
        roots.push(node);
      }
    }

    // Compute depth recursively
    const setDepth = (nodes: TreeNode[], d: number) => {
      for (const n of nodes) {
        n.depth = d;
        setDepth(n.children, d + 1);
      }
    };
    setDepth(roots, 0);

    return roots;
  }, [departments]);

  // Auto-expand all on first load
  useEffect(() => {
    if (departments.length > 0 && Object.keys(expanded).length === 0) {
      const all: Record<string, boolean> = {};
      for (const d of departments) {
        all[d.code] = true;
      }
      setExpanded(all);
    }
  }, [departments]);

  const toggleExpand = (code: string) => {
    setExpanded((prev) => ({ ...prev, [code]: !prev[code] }));
  };

  const resetForm = () => {
    setEditing(null);
    setShowForm(false);
    setFormName("");
    setFormCode("");
    setFormParentCode("");
    setFormDescription("");
    setMessage("");
  };

  const openEdit = (dept: Department) => {
    setEditing(dept);
    setShowForm(true);
    setFormName(dept.payload?.name ?? dept.name);
    setFormCode(dept.code);
    setFormParentCode(dept.parent_code ?? "");
    setFormDescription(dept.payload?.description ?? "");
  };

  const openAddChild = (parentCode: string) => {
    resetForm();
    setFormParentCode(parentCode);
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!formName.trim() || !formCode.trim()) {
      setMessage("名称和编码不能为空");
      return;
    }
    setMessage("");
    try {
      if (editing) {
        await updateDepartment(editing.id, {
          name: formName,
          code: formCode,
          parent_code: formParentCode || undefined,
          description: formDescription,
        });
      } else {
        await createDepartment({
          name: formName,
          code: formCode,
          parent_code: formParentCode || undefined,
          description: formDescription,
        });
      }
      resetForm();
      fetchData();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此部门？下级部门将变为顶级部门")) return;
    try {
      await deleteDepartment(id);
      fetchData();
    } catch {}
  };

  // Get all codes except the currently edited one (prevent self-reference)
  const availableParents = useMemo(() => {
    return departments.filter((d) => d.code !== formCode);
  }, [departments, formCode]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">部门管理</h2>
        <Button onClick={() => { resetForm(); setShowForm(true); }}>
          新增顶级部门
        </Button>
      </div>

      {message && (
        <div className={`p-3 rounded-lg text-sm mb-4 ${message.includes("成功") ? "bg-success-bg text-success" : "bg-danger-bg text-danger"}`}>
          {message}
        </div>
      )}

      {/* Form */}
      {showForm && (
        <Card className="mb-6">
          <h3 className="text-sm font-semibold text-fg-emphasis mb-4">
            {editing ? "编辑部门" : "新增部门"}
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input label="部门名称" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="例如: 生产部" />
            <Input label="部门编码" value={formCode} onChange={(e) => setFormCode(e.target.value)} placeholder="例如: dept-prod" disabled={!!editing} />
            <Input label="描述" value={formDescription} onChange={(e) => setFormDescription(e.target.value)} placeholder="部门描述" />
          </div>

          {/* Parent selector */}
          <div className="mt-4">
            <label className="text-sm font-medium text-fg-muted block mb-1.5">上级部门（留空为顶级部门）</label>
            <div className="max-h-48 overflow-y-auto rounded-md bg-surface border border-border p-1">
              <label className="flex items-center gap-2 px-3 py-2 rounded hover:bg-surface-alt cursor-pointer text-sm text-fg">
                <input
                  type="radio"
                  name="parentCode"
                  checked={formParentCode === ""}
                  onChange={() => setFormParentCode("")}
                  className="accent-accent"
                />
                （顶级部门 — 无上级）
              </label>
              {tree.length > 0 && (
                <div className="border-t border-border/50 pt-1">
                  {tree.map((root) => (
                    <ParentPickerTree
                      key={root.id}
                      node={root}
                      selectedCode={formParentCode}
                      onSelect={setFormParentCode}
                      excludeCode={formCode}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="flex gap-3 mt-4">
            <Button onClick={handleSave}>{editing ? "更新" : "创建"}</Button>
            <Button variant="secondary" onClick={resetForm}>取消</Button>
          </div>
        </Card>
      )}

      {/* Tree */}
      {tree.length === 0 ? (
        <Card>
          <div className="py-12 text-center text-sm text-fg-dimmed">暂无部门，请先创建顶级部门</div>
        </Card>
      ) : (
        <Card>
          <div className="divide-y divide-border/30">
            {tree.map((root) => (
              <TreeNodeRow
                key={root.id}
                node={root}
                allDepts={departments}
                onEdit={openEdit}
                onDelete={handleDelete}
                onAddChild={openAddChild}
                expanded={expanded}
                onToggle={toggleExpand}
                selectedParentCode={formParentCode}
                onSelectParent={setFormParentCode}
                depth={0}
              />
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

// ─── Recursive parent picker for the form ───────────────────────

function ParentPickerTree({
  node,
  selectedCode,
  onSelect,
  excludeCode,
}: {
  node: TreeNode;
  selectedCode: string;
  onSelect: (code: string) => void;
  excludeCode: string;
}) {
  const disabled = node.code === excludeCode;
  return (
    <div>
      <label
        className={`flex items-center gap-2 px-3 py-1.5 rounded hover:bg-surface-alt cursor-pointer text-sm ${
          disabled ? "opacity-40 pointer-events-none" : ""
        } text-fg`}
        style={{ paddingLeft: `${(node.depth + 1) * 16 + 8}px` }}
      >
        <input
          type="radio"
          name="parentCode"
          checked={selectedCode === node.code}
          onChange={() => onSelect(node.code)}
          disabled={disabled}
          className="accent-accent"
        />
        {node.payload?.name || node.name}
        <span className="text-xs text-fg-dimmed">({node.code})</span>
        {disabled && <span className="text-xs text-fg-dimmed">— 不能选择自身</span>}
      </label>
      {node.children.map((child) => (
        <ParentPickerTree
          key={child.id}
          node={child}
          selectedCode={selectedCode}
          onSelect={onSelect}
          excludeCode={excludeCode}
        />
      ))}
    </div>
  );
}
