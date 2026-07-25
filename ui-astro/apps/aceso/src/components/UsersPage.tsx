import { useCallback, useEffect, useState } from "react";
import {
  createIdentitySubject,
  disableIdentitySubject,
  listIdentitySubjects,
  setIdentityTemporaryPassword,
  type IdentitySubject,
} from "@pitchfork/shared/aceso";
import { Badge, Button, Card, Input, Modal, Table, type Column } from "@pitchfork/ui";

const PAGE_SIZE = 20;

const subjectFormDefaults = {
  displayName: "",
  identifier: "",
  password: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? value : date.toLocaleString("zh-CN");
}

export default function UsersPage() {
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
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

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listIdentitySubjects(targetPage, PAGE_SIZE);
      setSubjects(response.records);
      setTotal(response.meta.total);
      setPage(targetPage);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载用户列表"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  function openCreate() {
    setCreateForm(subjectFormDefaults);
    setCreateError("");
    setCreateOpen(true);
  }

  async function handleCreate() {
    if (!createForm.displayName.trim() || !createForm.identifier.trim() || !createForm.password) {
      setCreateError("姓名、账号和初始密码均不能为空");
      return;
    }

    setCreating(true);
    setCreateError("");
    try {
      await createIdentitySubject({
        display_name: createForm.displayName.trim(),
        identifier: createForm.identifier.trim(),
        password: createForm.password,
      });
      setCreateOpen(false);
      await load(1);
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建用户"));
    } finally {
      setCreating(false);
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
      key: "roles",
      header: "角色",
      className: "min-w-[180px]",
      render: (row) => row.roles.length > 0 ? (
        <div className="flex flex-wrap gap-1.5">
          {row.roles.map((role) => <Badge key={role} variant="info">{role}</Badge>)}
        </div>
      ) : "-",
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
      render: (row) => formatDate(row.created_at),
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[190px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" onClick={() => openTemporaryPassword(row)}>
            临时密码
          </Button>
          {row.status === "启用" && (
            <Button variant="ghost" size="sm" onClick={() => openDisable(row)}>
              禁用
            </Button>
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
          <p className="mt-1 text-sm text-fg-muted">管理 IDP 账号、登录状态和临时密码</p>
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
          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button onClick={() => void handleCreate()} loading={creating}>创建用户</Button>
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

      <Modal open={temporaryPasswordTarget !== null} onClose={() => !settingTemporaryPassword && setTemporaryPasswordTarget(null)} title="设置临时密码">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">用户下次登录时需要完成密码修改。</p>
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
            <Button onClick={() => void handleTemporaryPassword()} loading={settingTemporaryPassword}>保存</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
