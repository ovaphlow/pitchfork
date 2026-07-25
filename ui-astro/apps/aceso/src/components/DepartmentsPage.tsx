import { useCallback, useEffect, useMemo, useState } from "react";
import {
  createDepartment,
  deleteDepartment,
  listDepartments,
  updateDepartment,
  type Department,
  type DepartmentInput,
} from "@pitchfork/shared/aceso";
import { Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

const indentClasses = ["pl-0", "pl-5", "pl-10", "pl-15", "pl-20", "pl-24"] as const;

interface DepartmentRow extends Department {
  depth: number;
}

interface DepartmentForm {
  name: string;
  code: string;
  parentCode: string;
  description: string;
}

const departmentFormDefaults: DepartmentForm = {
  name: "",
  code: "",
  parentCode: "",
  description: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function flattenDepartments(departments: Department[]): DepartmentRow[] {
  const byCode = new Map(departments.map((department) => [department.code, department]));
  const childrenByParent = new Map<string, Department[]>();
  for (const department of departments) {
    const children = childrenByParent.get(department.parent_code) ?? [];
    children.push(department);
    childrenByParent.set(department.parent_code, children);
  }

  const rows: DepartmentRow[] = [];
  const seen = new Set<string>();
  const visit = (department: Department, depth: number, ancestors: Set<string>) => {
    if (seen.has(department.id) || ancestors.has(department.code)) return;
    seen.add(department.id);
    rows.push({ ...department, depth });
    const nextAncestors = new Set(ancestors);
    nextAncestors.add(department.code);
    for (const child of childrenByParent.get(department.code) ?? []) {
      visit(child, depth + 1, nextAncestors);
    }
  };

  for (const department of departments) {
    if (!department.parent_code || !byCode.has(department.parent_code)) visit(department, 0, new Set());
  }
  for (const department of departments) {
    visit(department, 0, new Set());
  }
  return rows;
}

function descendantCodes(departments: Department[], code: string): Set<string> {
  const descendants = new Set<string>();
  const queue = [code];
  while (queue.length > 0) {
    const parentCode = queue.shift();
    for (const department of departments) {
      if (department.parent_code === parentCode && !descendants.has(department.code)) {
        descendants.add(department.code);
        queue.push(department.code);
      }
    }
  }
  return descendants;
}

export default function DepartmentsPage() {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Department | null>(null);
  const [form, setForm] = useState<DepartmentForm>(departmentFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Department | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      setDepartments(await listDepartments());
    } catch (error) {
      setPageError(errorMessage(error, "无法加载部门列表"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => flattenDepartments(departments), [departments]);
  const unavailableParentCodes = useMemo(() => {
    if (!editTarget) return new Set<string>();
    return new Set([editTarget.code, ...descendantCodes(departments, editTarget.code)]);
  }, [departments, editTarget]);
  const parentChoices = rows.filter((department) => !unavailableParentCodes.has(department.code));

  function openCreate() {
    setEditTarget(null);
    setForm(departmentFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function openEdit(department: Department) {
    setEditTarget(department);
    setForm({
      name: department.payload.name,
      code: department.code,
      parentCode: department.parent_code,
      description: department.payload.description ?? "",
    });
    setFormError("");
    setEditorOpen(true);
  }

  function departmentInput(): DepartmentInput | null {
    const code = form.code.trim();
    const parentCode = form.parentCode;
    const parent = departments.find((department) => department.code === parentCode);

    if (!form.name.trim() || !code) {
      setFormError("部门名称和编码不能为空");
      return null;
    }
    if (parentCode && !parent) {
      setFormError("请选择有效的上级部门");
      return null;
    }
    if (editTarget && unavailableParentCodes.has(parentCode)) {
      setFormError("不能将部门移动到自身或其下级部门");
      return null;
    }

    return {
      code,
      parent_code: parentCode,
      root_code: parent ? parent.root_code || parent.code : "",
      name: form.name,
      description: form.description,
    };
  }

  async function handleSave() {
    const input = departmentInput();
    if (!input) return;

    setSaving(true);
    setFormError("");
    try {
      if (editTarget) {
        await updateDepartment(editTarget.id, input);
      } else {
        await createDepartment(input);
      }
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, "无法保存部门"));
    } finally {
      setSaving(false);
    }
  }

  function requestDelete(department: Department) {
    if (departments.some((candidate) => candidate.parent_code === department.code)) {
      setPageError("请先删除或迁移该部门的下级部门");
      return;
    }
    setDeleteError("");
    setDeleteTarget(department);
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await deleteDepartment(deleteTarget.id);
      setDeleteTarget(null);
      await load();
    } catch (error) {
      setDeleteError(errorMessage(error, "无法删除部门"));
    } finally {
      setDeleting(false);
    }
  }

  const columns: Column<DepartmentRow>[] = [
    {
      key: "name",
      header: "部门",
      className: "min-w-[240px]",
      render: (row) => (
        <div className={`flex items-center gap-2 ${indentClasses[Math.min(row.depth, indentClasses.length - 1)]}`}>
          {row.depth > 0 && <span className="text-xs text-fg-dimmed">└</span>}
          <span>{row.payload.name}</span>
        </div>
      ),
    },
    { key: "code", header: "编码", className: "min-w-[150px] font-mono text-xs" },
    {
      key: "parent_code",
      header: "上级部门",
      className: "min-w-[150px]",
      render: (row) => row.parent_code || "根部门",
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
          <Button variant="ghost" size="sm" onClick={() => requestDelete(row)}>删除</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">部门管理</h2>
          <p className="mt-1 text-sm text-fg-muted">管理 Nexus Settings 中的部门层级</p>
        </div>
        <Button onClick={openCreate}>添加部门</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="部门列表" actions={<span className="text-sm text-fg-dimmed">共 {departments.length} 个</span>}>
        <Table columns={columns} data={rows} loading={loading} emptyMessage="暂无部门" />
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑部门" : "添加部门"}>
        <div className="space-y-4">
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}
          <Input
            label="部门名称"
            value={form.name}
            onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
            placeholder="请输入部门名称"
          />
          <Input
            label="部门编码"
            value={form.code}
            onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
            placeholder="例如 nursing"
            disabled={editTarget !== null}
          />
          {editTarget && <p className="text-xs text-fg-dimmed">部门编码创建后不可修改，以保障下级部门关系。</p>}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="department-parent">上级部门</label>
            <select
              id="department-parent"
              value={form.parentCode}
              onChange={(event) => setForm((current) => ({ ...current, parentCode: event.target.value }))}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">根部门</option>
              {parentChoices.map((department) => (
                <option key={department.id} value={department.code}>
                  {"  ".repeat(Math.min(department.depth, 3))}{department.payload.name} ({department.code})
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="department-description">描述</label>
            <textarea
              id="department-description"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="请输入部门描述"
            />
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button onClick={() => void handleSave()} loading={saving}>保存</Button>
          </div>
        </div>
      </Modal>

      <Modal open={deleteTarget !== null} onClose={() => !deleting && setDeleteTarget(null)} title="确认删除部门">
        <div className="space-y-5">
          {deleteError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{deleteError}</div>}
          <p className="text-sm text-fg-muted">将物理删除部门「{deleteTarget?.payload.name}」，此操作不可恢复。</p>
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setDeleteTarget(null)} disabled={deleting}>取消</Button>
            <Button variant="danger" onClick={() => void handleDelete()} loading={deleting}>确认删除</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
