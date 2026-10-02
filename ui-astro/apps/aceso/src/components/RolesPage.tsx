import { useCallback, useEffect, useState } from "react";
import {
  createRole,
  deleteRole,
  listDeclaredPermissions,
  listRoles,
  updateRole,
  type NexusRole,
  type NexusRoleInput,
  type PermissionCatalogEntry,
} from "@pitchfork/shared/aceso";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

interface RoleForm {
  roleCode: string;
  displayName: string;
  description: string;
  permissionCodes: string[];
}

const roleFormDefaults: RoleForm = {
  roleCode: "",
  displayName: "",
  description: "",
  permissionCodes: [],
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 勾选/取消一个权限码，返回新数组。 */
function toggleCode(codes: string[], code: string): string[] {
  return codes.includes(code) ? codes.filter((item) => item !== code) : [...codes, code];
}

export default function RolesPage() {
  const [roles, setRoles] = useState<NexusRole[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  // 产品权限目录（含未接线的码）：角色页只能从这里勾选权限码。
  // null 表示自省端点拿不到——此时不提供勾选，避免又写出自由文本垃圾。
  const [permissionCatalog, setPermissionCatalog] = useState<PermissionCatalogEntry[] | null>(null);
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

  useEffect(() => {
    void (async () => {
      try {
        const declared = await listDeclaredPermissions();
        setPermissionCatalog(declared.catalog);
      } catch {
        setPermissionCatalog(null);
      }
    })();
  }, []);

  const wiredPermissionCodes = permissionCatalog
    ? new Set(permissionCatalog.filter((entry) => entry.wired).map((entry) => entry.code))
    : null;

  // 目录外（历史遗留或别的产品）的权限码：原样保留并在弹窗里列出，保存时不静默丢弃。
  const catalogForeignCodes = permissionCatalog
    ? form.permissionCodes.filter((code) => !permissionCatalog.some((entry) => entry.code === code))
    : [];

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
      permissionCodes: [...role.permission_codes],
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
    return {
      role_code: roleCode,
      display_name: displayName,
      description: form.description.trim(),
      permission_codes: form.permissionCodes,
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
            {row.permission_codes.map((code) => {
              const wired = wiredPermissionCodes?.has(code) ?? false;
              return (
                <span
                  key={code}
                  title={wired ? "已被接口判定" : "当前没有任何接口在判定这个权限码"}
                  className={
                    wired
                      ? "inline-flex items-center rounded border border-border bg-surface-alt px-2 py-0.5 font-mono text-xs text-fg-muted"
                      : "inline-flex items-center rounded border border-dashed border-border px-2 py-0.5 font-mono text-xs text-fg-dimmed"
                  }
                >
                  {code}
                  {!wired && <span className="ml-1.5">未接线</span>}
                </span>
              );
            })}
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

  // 已接线但没有任何角色承载的权限码：接线了却没人能通过，必须显式提示，
  // 否则相关操作会静默变成全员 403。拿不到自省清单时不提示（避免误报）。
  const permissionCodesWithoutRole = wiredPermissionCodes
    ? [...wiredPermissionCodes].filter(
        (code) => !roles.some((role) => role.permission_codes.includes(code)),
      )
    : [];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">角色管理</h2>
          <p className="mt-1 text-sm text-fg-muted">
            管理共享角色目录与每个角色的权限码集合；平台管理员由身份服务单独维护，不在本页
          </p>
        </div>
        <Button onClick={openCreate}>添加角色</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      {permissionCodesWithoutRole.length > 0 && (
        <div className="rounded-lg border border-warning/30 bg-warning-bg px-4 py-3 text-sm text-warning">
          已有接口在判定这些权限码，但当前没有任何角色带有它们，相关操作会直接返回「权限不足」：
          <span className="ml-1 font-mono">{permissionCodesWithoutRole.join("、")}</span>。请创建带这些权限码的角色，再到「用户管理」把它分配给对应账号。
        </div>
      )}

      <Card title="产品角色" actions={<span className="text-sm text-fg-dimmed">共 {roles.length} 个</span>}>
        <p className="mb-4 text-sm text-fg-muted">
          存放在共享角色目录（Nexus），可在「用户管理」中分配给用户；权限码用于接口准入，标为「未接线」的权限码目前没有任何接口在判定它。
        </p>
        <Table columns={columns} data={roles} loading={loading} emptyMessage="暂无产品角色" />
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
          <div className="space-y-2">
            <span className="block text-sm font-medium text-fg">权限码</span>
            {permissionCatalog === null ? (
              <div className="rounded-lg border border-border bg-surface-alt px-3 py-2 text-sm text-fg-muted">
                无法读取权限码目录，暂不能编辑权限码；已保存的权限码会原样保留。
              </div>
            ) : (
              <>
                <div className="max-h-56 space-y-1.5 overflow-y-auto rounded-lg border border-border px-3 py-2">
                  {permissionCatalog.map((entry) => (
                    <label key={entry.code} className="flex cursor-pointer items-start gap-2.5 text-sm text-fg">
                      <input
                        type="checkbox"
                        className="mt-0.5 h-4 w-4 rounded border-border bg-surface accent-accent"
                        checked={form.permissionCodes.includes(entry.code)}
                        onChange={() =>
                          setForm((current) => ({
                            ...current,
                            permissionCodes: toggleCode(current.permissionCodes, entry.code),
                          }))
                        }
                      />
                      <span>
                        <span className="font-mono text-xs">{entry.code}</span>
                        <span className="ml-2 text-xs text-fg-muted">{entry.description}</span>
                        {!entry.wired && <span className="ml-1.5 text-xs text-fg-dimmed">未接线</span>}
                      </span>
                    </label>
                  ))}
                  {catalogForeignCodes.length > 0 && (
                    <div className="border-t border-border pt-1.5 text-xs text-fg-dimmed">
                      目录外的既有权限码（保留，不会因为保存被清掉）：
                      <span className="ml-1 font-mono">{catalogForeignCodes.join("、")}</span>
                    </div>
                  )}
                </div>
                <p className="text-xs text-fg-dimmed">
                  只能从产品权限目录里勾选；「未接线」表示目前还没有接口在判定它。格式与合法性由服务端兜底校验。
                </p>
              </>
            )}
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
