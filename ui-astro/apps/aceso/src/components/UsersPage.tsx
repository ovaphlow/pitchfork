import { useCallback, useEffect, useMemo, useState } from "react";
import {
  createIdentitySubject,
  disableIdentitySubject,
  getSubjectPermissions,
  listDepartments,
  listIdentitySubjects,
  listRoles,
  listSubjectDepartments,
  listSubjectRoles,
  replaceSubjectDepartment,
  replaceSubjectRoles,
  setIdentityTemporaryPassword,
  type Department,
  type IdentitySubject,
  type NexusRole,
} from "@pitchfork/shared/aceso";
import { formatDateTime } from "../lib/datetime";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

const PAGE_SIZE = 20;
const ROLE_CATALOG_PAGE_SIZE = 100;
const checkboxClass = "h-4 w-4 rounded border-border bg-surface accent-accent";

const subjectFormDefaults = {
  displayName: "",
  identifier: "",
  password: "",
  roleCodes: [] as string[],
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 勾选/取消一个角色码，返回新的角色码数组。 */
function toggleCode(codes: string[], roleCode: string): string[] {
  return codes.includes(roleCode)
    ? codes.filter((code) => code !== roleCode)
    : [...codes, roleCode];
}

interface RoleChecklistProps {
  roles: NexusRole[];
  selected: string[];
  loading: boolean;
  catalogError: string;
  onToggle: (roleCode: string) => void;
}

/** 角色多选列表：加载中、目录为空、加载失败都给出可行动提示。 */
function RoleChecklist({ roles, selected, loading, catalogError, onToggle }: RoleChecklistProps) {
  if (loading) {
    return <p className="text-sm text-fg-muted">正在加载角色目录…</p>;
  }
  if (catalogError) {
    return (
      <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{catalogError}</div>
    );
  }
  if (roles.length === 0) {
    return <p className="text-sm text-fg-muted">角色目录为空，请先到「角色」页创建角色后再分配。</p>;
  }
  return (
    <div className="max-h-56 space-y-2 overflow-y-auto rounded-lg border border-border px-3 py-2">
      {roles.map((role) => (
        <label key={role.id} className="flex cursor-pointer items-center gap-2.5 text-sm text-fg">
          <input
            type="checkbox"
            className={checkboxClass}
            checked={selected.includes(role.role_code)}
            onChange={() => onToggle(role.role_code)}
          />
          <span className="font-medium">{role.display_name}</span>
          <span className="text-xs text-fg-dimmed">{role.role_code}</span>
        </label>
      ))}
    </div>
  );
}

export default function UsersPage() {
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [assignments, setAssignments] = useState<Record<string, string[]>>({});
  const [roleCatalog, setRoleCatalog] = useState<NexusRole[]>([]);
  const [roleCatalogLoading, setRoleCatalogLoading] = useState(true);
  const [roleCatalogError, setRoleCatalogError] = useState("");
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentCatalogError, setDepartmentCatalogError] = useState("");
  const [departmentAssignments, setDepartmentAssignments] = useState<Record<string, string>>({});
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState(subjectFormDefaults);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);
  const [disableTarget, setDisableTarget] = useState<IdentitySubject | null>(null);
  const [disableError, setDisableError] = useState("");
  const [disabling, setDisabling] = useState(false);
  const [temporaryPasswordTarget, setTemporaryPasswordTarget] = useState<IdentitySubject | null>(null);
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [temporaryPasswordError, setTemporaryPasswordError] = useState("");
  const [settingTemporaryPassword, setSettingTemporaryPassword] = useState(false);
  const [roleEditorTarget, setRoleEditorTarget] = useState<IdentitySubject | null>(null);
  const [editRoleCodes, setEditRoleCodes] = useState<string[]>([]);
  const [roleEditorError, setRoleEditorError] = useState("");
  const [editorPermissionCodes, setEditorPermissionCodes] = useState<string[]>([]);
  const [editorPermissionsError, setEditorPermissionsError] = useState("");
  const [savingRoles, setSavingRoles] = useState(false);
  const [departmentTarget, setDepartmentTarget] = useState<IdentitySubject | null>(null);
  const [editDepartmentId, setEditDepartmentId] = useState("");
  const [departmentError, setDepartmentError] = useState("");
  const [savingDepartment, setSavingDepartment] = useState(false);

  const loadRoleCatalog = useCallback(async () => {
    setRoleCatalogLoading(true);
    setRoleCatalogError("");
    try {
      setRoleCatalog(await listRoles({ page_size: ROLE_CATALOG_PAGE_SIZE }));
    } catch (error) {
      setRoleCatalogError(errorMessage(error, "无法加载角色目录"));
    } finally {
      setRoleCatalogLoading(false);
    }
  }, []);

  const loadDepartmentCatalog = useCallback(async () => {
    setDepartmentCatalogError("");
    try {
      setDepartments((await listDepartments()).records);
    } catch (error) {
      setDepartments([]);
      setDepartmentCatalogError(errorMessage(error, "无法加载部门目录"));
    }
  }, []);

  const loadAssignments = useCallback(async (subjectIds: string[]) => {
    if (subjectIds.length === 0) {
      setAssignments({});
      return;
    }
    const rows = await listSubjectRoles(subjectIds);
    const grouped: Record<string, string[]> = {};
    for (const row of rows) {
      (grouped[row.subject_id] ??= []).push(row.role_code);
    }
    setAssignments(grouped);
  }, []);

  // 部门归属单独取：Nexus 尚未重启（没有 /subject-departments）时，
  // 只影响部门列，不把角色列一起打成错误。
  const loadDepartmentAssignments = useCallback(async (subjectIds: string[]) => {
    if (subjectIds.length === 0) {
      setDepartmentAssignments({});
      return;
    }
    try {
      const rows = await listSubjectDepartments(subjectIds);
      const grouped: Record<string, string> = {};
      for (const row of rows.records) {
        grouped[row.subject_id] = row.department_id;
      }
      setDepartmentAssignments(grouped);
    } catch (error) {
      setDepartmentAssignments({});
      setDepartmentCatalogError(errorMessage(error, "无法加载用户部门归属"));
    }
  }, []);

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listIdentitySubjects(targetPage, PAGE_SIZE);
      setSubjects(response.records);
      setTotal(response.meta.total);
      setPage(targetPage);
      await loadDepartmentAssignments(response.records.map((subject) => subject.id));
      try {
        await loadAssignments(response.records.map((subject) => subject.id));
      } catch (error) {
        setAssignments({});
        setPageError(errorMessage(error, "无法加载用户角色分配"));
      }
    } catch (error) {
      setPageError(errorMessage(error, "无法加载用户列表"));
    } finally {
      setLoading(false);
    }
  }, [loadAssignments, loadDepartmentAssignments]);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  useEffect(() => {
    void loadRoleCatalog();
  }, [loadRoleCatalog]);

  useEffect(() => {
    void loadDepartmentCatalog();
  }, [loadDepartmentCatalog]);

  const roleLabels = useMemo(() => {
    const labels: Record<string, string> = {};
    for (const role of roleCatalog) {
      labels[role.role_code] = role.display_name;
    }
    return labels;
  }, [roleCatalog]);

  const departmentLabels = useMemo(() => {
    const labels: Record<string, string> = {};
    for (const department of departments) {
      labels[department.id] = department.name;
    }
    return labels;
  }, [departments]);

  function openCreate() {
    setCreateForm(subjectFormDefaults);
    setCreateError("");
    setCreateOpen(true);
  }

  function openRoleEditor(subject: IdentitySubject) {
    setRoleEditorTarget(subject);
    setEditRoleCodes(assignments[subject.id] ?? []);
    setRoleEditorError("");
    setEditorPermissionCodes([]);
    void loadEffectivePermissions(subject.id);
  }

  // 保存前后都刷新一次：让"配了角色后实际生效哪些权限码"当场可见。
  async function loadEffectivePermissions(subjectId: string) {
    setEditorPermissionsError("");
    try {
      const permissions = await getSubjectPermissions(subjectId);
      setEditorPermissionCodes(permissions.permission_codes);
    } catch (error) {
      setEditorPermissionCodes([]);
      setEditorPermissionsError(errorMessage(error, "无法读取生效权限"));
    }
  }

  async function handleCreate() {
    if (!createForm.displayName.trim() || !createForm.identifier.trim() || !createForm.password) {
      setCreateError("姓名、账号和初始密码均不能为空");
      return;
    }

    setCreating(true);
    setCreateError("");
    try {
      const subject = await createIdentitySubject({
        display_name: createForm.displayName.trim(),
        identifier: createForm.identifier.trim(),
        password: createForm.password,
      });
      let roleWarning = "";
      if (createForm.roleCodes.length > 0) {
        try {
          await replaceSubjectRoles(subject.id, createForm.roleCodes);
        } catch (error) {
          roleWarning = errorMessage(error, "未知错误");
        }
      }
      setCreateOpen(false);
      await load(1);
      if (roleWarning) {
        setPageError(`用户「${subject.display_name}」已创建，但角色未保存：${roleWarning}；可在列表行内重新分配`);
      }
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建用户"));
    } finally {
      setCreating(false);
    }
  }

  async function handleSaveRoles() {
    if (!roleEditorTarget) return;

    setSavingRoles(true);
    setRoleEditorError("");
    try {
      const rows = await replaceSubjectRoles(roleEditorTarget.id, editRoleCodes);
      const saved = rows.map((row) => row.role_code);
      setAssignments((current) => ({ ...current, [roleEditorTarget.id]: saved }));
      setRoleEditorTarget(null);
    } catch (error) {
      setRoleEditorError(errorMessage(error, "无法保存角色"));
    } finally {
      setSavingRoles(false);
    }
  }

  function openDepartmentEditor(subject: IdentitySubject) {
    setDepartmentTarget(subject);
    setEditDepartmentId(departmentAssignments[subject.id] ?? "");
    setDepartmentError("");
  }

  async function handleSaveDepartment() {
    if (!departmentTarget) return;

    setSavingDepartment(true);
    setDepartmentError("");
    try {
      await replaceSubjectDepartment(departmentTarget.id, editDepartmentId || null);
      setDepartmentAssignments((current) => {
        const next = { ...current };
        if (editDepartmentId) {
          next[departmentTarget.id] = editDepartmentId;
        } else {
          delete next[departmentTarget.id];
        }
        return next;
      });
      setDepartmentTarget(null);
      await loadDepartmentCatalog();
    } catch (error) {
      setDepartmentError(errorMessage(error, "无法保存部门"));
    } finally {
      setSavingDepartment(false);
    }
  }

  async function handleDisable() {
    if (!disableTarget) return;

    setDisabling(true);
    setDisableError("");
    try {
      await disableIdentitySubject(disableTarget.id);
      setDisableTarget(null);
      await load(page);
    } catch (error) {
      setDisableError(errorMessage(error, "无法禁用用户"));
    } finally {
      setDisabling(false);
    }
  }

  function openTemporaryPassword(subject: IdentitySubject) {
    setTemporaryPasswordTarget(subject);
    setTemporaryPassword("");
    setTemporaryPasswordError("");
  }

  function openDisable(subject: IdentitySubject) {
    setDisableTarget(subject);
    setDisableError("");
  }

  async function handleTemporaryPassword() {
    if (!temporaryPasswordTarget) return;
    if (!temporaryPassword) {
      setTemporaryPasswordError("临时密码不能为空");
      return;
    }

    setSettingTemporaryPassword(true);
    setTemporaryPasswordError("");
    try {
      await setIdentityTemporaryPassword(temporaryPasswordTarget.id, temporaryPassword);
      setTemporaryPasswordTarget(null);
      await load(page);
    } catch (error) {
      setTemporaryPasswordError(errorMessage(error, "无法设置临时密码"));
    } finally {
      setSettingTemporaryPassword(false);
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns: Column<IdentitySubject>[] = [
    {
      key: "display_name",
      header: "姓名",
      className: "min-w-[140px]",
      render: (row) => row.display_name || "-",
    },
    { key: "identifier", header: "账号", className: "min-w-[180px]" },
    {
      key: "department",
      header: "部门",
      className: "min-w-[160px]",
      render: (row) => {
        const departmentId = departmentAssignments[row.id];
        return (
          <div className="flex flex-wrap items-center gap-1.5">
            <span>{departmentId ? departmentLabels[departmentId] ?? "未知部门" : "-"}</span>
            <Button variant="ghost" size="sm" onClick={() => openDepartmentEditor(row)}>
              设置部门
            </Button>
          </div>
        );
      },
    },
    {
      key: "roles",
      header: "角色",
      className: "min-w-[240px]",
      render: (row) => {
        const productRoles = assignments[row.id] ?? [];
        return (
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-1.5">
              {productRoles.length > 0 ? (
                productRoles.map((roleCode) => (
                  <Badge key={roleCode} variant="info">{roleLabels[roleCode] ?? roleCode}</Badge>
                ))
              ) : (
                <span className="text-xs text-fg-dimmed">未分配</span>
              )}
              <Button variant="ghost" size="sm" onClick={() => openRoleEditor(row)}>
                编辑角色
              </Button>
            </div>
            {row.platform_admin && (
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-xs text-fg-dimmed">平台管理员（身份服务，只读）</span>
                <Badge variant="default">平台管理员</Badge>
              </div>
            )}
          </div>
        );
      },
    },
    {
      key: "status",
      header: "状态",
      className: "w-[100px]",
      render: (row) => <Badge variant={row.status === "启用" ? "success" : "danger"}>{row.status}</Badge>,
    },
    {
      key: "created_at",
      header: "创建时间",
      className: "min-w-[180px]",
      render: (row) => formatDateTime(row.created_at),
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[190px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          {row.status === "启用" && (
            <>
              <Button variant="ghost" size="sm" onClick={() => openTemporaryPassword(row)}>
                重置临时密码
              </Button>
              <Button variant="ghost" size="sm" onClick={() => openDisable(row)}>
                禁用
              </Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">用户管理</h2>
          <p className="mt-1 text-sm text-fg-muted">管理员核验身份后，可将账号重置为仅能改密的临时密码，并分配产品角色</p>
        </div>
        <Button onClick={openCreate}>添加用户</Button>
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      <Card title="用户列表" actions={<span className="text-sm text-fg-dimmed">共 {total} 条</span>}>
        <Table columns={columns} data={subjects} loading={loading} emptyMessage="暂无用户" />
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
          <span className="text-sm text-fg-muted">第 {page} / {pageCount} 页</span>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={page <= 1 || loading} onClick={() => void load(page - 1)}>
              上一页
            </Button>
            <Button variant="secondary" size="sm" disabled={page >= pageCount || loading} onClick={() => void load(page + 1)}>
              下一页
            </Button>
          </div>
        </div>
      </Card>

      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="添加用户">
        <div className="space-y-4">
          {createError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}
          <Input
            label="姓名"
            value={createForm.displayName}
            onChange={(event) => setCreateForm((form) => ({ ...form, displayName: event.target.value }))}
            placeholder="请输入姓名"
            autoComplete="name"
          />
          <Input
            label="账号"
            value={createForm.identifier}
            onChange={(event) => setCreateForm((form) => ({ ...form, identifier: event.target.value }))}
            placeholder="请输入登录账号"
            autoComplete="username"
          />
          <Input
            label="初始密码"
            type="password"
            value={createForm.password}
            onChange={(event) => setCreateForm((form) => ({ ...form, password: event.target.value }))}
            placeholder="请输入初始密码"
            autoComplete="new-password"
          />
          <div className="space-y-2">
            <span className="block text-sm font-medium text-fg">角色（可选）</span>
            <RoleChecklist
              roles={roleCatalog}
              selected={createForm.roleCodes}
              loading={roleCatalogLoading}
              catalogError={roleCatalogError}
              onToggle={(roleCode) => setCreateForm((form) => ({ ...form, roleCodes: toggleCode(form.roleCodes, roleCode) }))}
            />
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button onClick={() => void handleCreate()} loading={creating}>创建用户</Button>
          </div>
        </div>
      </Modal>

      <Modal open={roleEditorTarget !== null} onClose={() => !savingRoles && setRoleEditorTarget(null)} title="编辑用户角色">
        <div className="space-y-4">
          {roleEditorError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{roleEditorError}</div>}
          <p className="text-sm text-fg-muted">
            为 {roleEditorTarget?.display_name || roleEditorTarget?.identifier} 分配产品角色，保存后立即生效。
          </p>
          <RoleChecklist
            roles={roleCatalog}
            selected={editRoleCodes}
            loading={roleCatalogLoading}
            catalogError={roleCatalogError}
            onToggle={(roleCode) => setEditRoleCodes((codes) => toggleCode(codes, roleCode))}
          />
          <div className="space-y-2">
            <span className="block text-sm font-medium text-fg">当前生效的权限码</span>
            {editorPermissionsError ? (
              <p className="text-xs text-fg-dimmed">{editorPermissionsError}</p>
            ) : editorPermissionCodes.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {editorPermissionCodes.map((code) => (
                  <span
                    key={code}
                    className="inline-flex items-center rounded border border-border bg-surface-alt px-2 py-0.5 font-mono text-xs text-fg-muted"
                  >
                    {code}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-xs text-fg-dimmed">当前没有任何角色带来权限码</p>
            )}
            <p className="text-xs text-fg-dimmed">以已保存的角色为准；本次勾选保存后生效（接口侧最多有 1 分钟缓存）</p>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
            <span className="text-xs text-fg-dimmed">已选 {editRoleCodes.length} 个角色，全部取消即清空该用户角色</span>
            <div className="flex gap-3">
              <Button variant="ghost" onClick={() => setRoleEditorTarget(null)} disabled={savingRoles}>取消</Button>
              <Button onClick={() => void handleSaveRoles()} loading={savingRoles}>保存角色</Button>
            </div>
          </div>
        </div>
      </Modal>

      <Modal open={departmentTarget !== null} onClose={() => !savingDepartment && setDepartmentTarget(null)} title="设置用户部门">
        <div className="space-y-4">
          {departmentError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{departmentError}</div>}
          <p className="text-sm text-fg-muted">
            为 {departmentTarget?.display_name || departmentTarget?.identifier} 指定所属部门（一人一部门，选择「不设置」即清空）。
          </p>
          {departmentCatalogError ? (
            <p className="text-xs text-fg-dimmed">{departmentCatalogError}</p>
          ) : departments.length === 0 ? (
            <p className="text-xs text-fg-dimmed">部门目录为空，请先到「部门」页创建部门。</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="user-department">部门</label>
              <select
                id="user-department"
                value={editDepartmentId}
                onChange={(event) => setEditDepartmentId(event.target.value)}
                className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">（不设置）</option>
                {departments.map((department) => (
                  <option key={department.id} value={department.id}>
                    {department.name}（{department.code}）
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setDepartmentTarget(null)} disabled={savingDepartment}>取消</Button>
            <Button onClick={() => void handleSaveDepartment()} loading={savingDepartment}>保存部门</Button>
          </div>
        </div>
      </Modal>

      <Modal open={disableTarget !== null} onClose={() => !disabling && setDisableTarget(null)} title="确认禁用用户">
        <div className="space-y-5">
          {disableError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{disableError}</div>}
          <p className="text-sm text-fg-muted">
            禁用后，{disableTarget?.display_name || disableTarget?.identifier} 将无法继续登录。
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setDisableTarget(null)} disabled={disabling}>取消</Button>
            <Button variant="danger" onClick={() => void handleDisable()} loading={disabling}>确认禁用</Button>
          </div>
        </div>
      </Modal>

      <Modal open={temporaryPasswordTarget !== null} onClose={() => !settingTemporaryPassword && setTemporaryPasswordTarget(null)} title="重置为临时密码">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">请先完成身份核验。保存后会撤销该用户的所有会话，且其下次登录只能设置新密码。</p>
          <Input
            label="临时密码"
            type="password"
            value={temporaryPassword}
            onChange={(event) => setTemporaryPassword(event.target.value)}
            error={temporaryPasswordError}
            placeholder="请输入临时密码"
            autoComplete="new-password"
          />
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setTemporaryPasswordTarget(null)} disabled={settingTemporaryPassword}>取消</Button>
            <Button onClick={() => void handleTemporaryPassword()} loading={settingTemporaryPassword}>确认重置</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
