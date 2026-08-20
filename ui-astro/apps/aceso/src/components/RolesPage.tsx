import { useCallback, useEffect, useState } from "react";
import {
  createRole,
  deleteRole,
  listRoles,
  updateRole,
  type NexusRole,
  type NexusRoleInput,
} from "@pitchfork/shared/aceso";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

interface RoleForm {
  roleCode: string;
  displayName: string;
  description: string;
  permissionsText: string;
}

const roleFormDefaults: RoleForm = {
  roleCode: "",
  displayName: "",
  description: "",
  permissionsText: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function permissionsFromText(text: string): string[] {
  return Array.from(new Set(
    text
      .split(/[,，]/)
      .map((item) => item.trim())
      .filter((item) => item.length > 0),
  ));
}

export default function RolesPage() {
  const [roles, setRoles] = useState<NexusRole[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<NexusRole | null>(null);
  const [form, setForm] = useState<RoleForm>(roleFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<NexusRole | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      setRoles(await listRoles());
    } catch (error) {
      setPageError(errorMessage(error, "无法加载角色列表"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function openCreate() {
    setEditTarget(null);
    setForm(roleFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function openEdit(role: NexusRole) {
    setEditTarget(role);
    setForm({
      roleCode: role.role_code,
      displayName: role.display_name,
      description: role.description,
      permissionsText: role.permission_codes.join(", "),
    });
    setFormError("");
    setEditorOpen(true);
  }

  function roleInput(): NexusRoleInput | null {
    const roleCode = form.roleCode.trim();
    const displayName = form.displayName.trim();
    if (!roleCode || !displayName) {
      setFormError("角色编码和显示名称不能为空");
      return null;
    }
    if (!/^[a-z0-9.]+$/.test(roleCode)) {
      setFormError("角色编码仅允许小写字母、数字和点");
      return null;
    }
    const permissionCodes = permissionsFromText(form.permissionsText);
    return {
      role_code: roleCode,
      display_name: displayName,
      description: form.description.trim(),
      permission_codes: permissionCodes,
    };
  }

  async function handleSave() {
    const input = roleInput();
    if (!input) return;

    setSaving(true);
    setFormError("");
    try {
      if (editTarget) {
        await updateRole(editTarget.id, input);
      } else {
        await createRole(input);
      }
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, "无法保存角色"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await deleteRole(deleteTarget.id);
      setDeleteTarget(null);
      await load();
    } catch (error) {
      setDeleteError(errorMessage(error, "无法删除角色"));
    } finally {
      setDeleting(false);
    }
  }

  const columns: Column<NexusRole>[] = [
    {
      key: "role_code",
      header: "角色编码",
      className: "min-w-[150px] font-mono text-xs",
      render: (row) => <Badge variant="info">{row.role_code}</Badge>,
    },
    {
      key: "display_name",
      header: "显示名称",
      className: "min-w-[140px]",
      render: (row) => row.display_name,
    },
    {
      key: "description",
      header: "描述",
      className: "min-w-[200px]",
      render: (row) => row.description || "-",
    },
    {
      key: "permission_codes",
      header: "权限码",
      className: "min-w-[220px]",
      render: (row) =>
        row.permission_codes.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {row.permission_codes.map((code) => (
              <span
                key={code}
                className="inline-flex items-center rounded border border-border bg-surface-alt px-2 py-0.5 font-mono text-xs text-fg-muted"
              >
                {code}
              </span>
            ))}
          </div>
        ) : (
          "-"
        ),
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
          <h2 className="text-lg font-semibold text-fg-emphasis">角色管理</h2>
          <p className="mt-1 text-sm text-fg-muted">管理共享角色目录与每个角色的权限码集合，供各产品消费</p>
        </div>
        <Button onClick={openCreate}>添加角色</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="角色列表" actions={<span className="text-sm text-fg-dimmed">共 {roles.length} 个</span>}>
        <Table columns={columns} data={roles} loading={loading} emptyMessage="暂无角色" />
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑角色" : "添加角色"}>
        <div className="space-y-4">
          {formError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}
          <Input
            label="角色编码"
            value={form.roleCode}
            onChange={(event) => setForm((current) => ({ ...current, roleCode: event.target.value }))}
            placeholder="例如 nursing.staff"
            disabled={editTarget !== null}
          />
          {editTarget && <p className="text-xs text-fg-dimmed">角色编码创建后不可修改。</p>}
          <Input
            label="显示名称"
            value={form.displayName}
            onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))}
            placeholder="请输入显示名称"
          />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="role-description">描述</label>
            <textarea
              id="role-description"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="请输入角色描述"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="role-permissions">权限码</label>
            <textarea
              id="role-permissions"
              value={form.permissionsText}
              onChange={(event) => setForm((current) => ({ ...current, permissionsText: event.target.value }))}
              rows={3}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              placeholder="逗号分隔，例如 nursing:execute, nursing:record"
            />
            <p className="text-xs text-fg-dimmed">以逗号分隔多个权限码，重复项会自动去重。</p>
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button onClick={() => void handleSave()} loading={saving}>保存</Button>
          </div>
        </div>
      </Modal>

      <Modal open={deleteTarget !== null} onClose={() => !deleting && setDeleteTarget(null)} title="确认删除角色">
        <div className="space-y-5">
          {deleteError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{deleteError}</div>}
          <p className="text-sm text-fg-muted">
            将删除角色「{deleteTarget?.display_name}（{deleteTarget?.role_code}）」，此操作不可恢复。
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setDeleteTarget(null)} disabled={deleting}>取消</Button>
            <Button variant="danger" onClick={() => void handleDelete()} loading={deleting}>确认删除</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}