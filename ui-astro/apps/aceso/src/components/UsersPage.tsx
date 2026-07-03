import { useState, useEffect, useCallback } from "react";
import { listUsers, createUser, updateUser, updateUserStatus, type User } from "@pitchfork/shared";
import { Table, type Column, Badge, Card } from "@pitchfork/ui";

const statusMap: Record<string, { label: string; variant: "success" | "danger" | "default" | "warning" | "info" }> = {
  ACTIVE: { label: "启用", variant: "success" },
  INACTIVE: { label: "禁用", variant: "danger" },
  PENDING: { label: "待审核", variant: "warning" },
};

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [modalOpen, setModalOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<User | null>(null);
  const [form, setForm] = useState({ email: "", password: "", username: "", phone: "" });
  const [saving, setSaving] = useState(false);

  const fetch = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listUsers({ search }) as User[];
      setUsers(res ?? []);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [search]);

  useEffect(() => { fetch(); }, [fetch]);

  async function toggleStatus(user: User) {
    const next = user.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    await updateUserStatus(user.id, next);
    fetch();
  }

  function openAdd() {
    setEditTarget(null);
    setForm({ email: "", password: "", username: "", phone: "" });
    setModalOpen(true);
  }

  function openEdit(user: User) {
    setEditTarget(user);
    setForm({ email: user.email, password: "", username: user.username, phone: user.phone });
    setModalOpen(true);
  }

  async function handleSave() {
    if (!form.email.trim()) return;
    setSaving(true);
    try {
      if (editTarget) {
        await updateUser(editTarget.id, {
          email: form.email,
          username: form.username,
          phone: form.phone,
        });
      } else {
        await createUser({
          email: form.email,
          password: form.password || "123456",
          username: form.username,
          phone: form.phone,
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

  const columns: Column<User>[] = [
    { key: "username", header: "姓名", className: "min-w-[120px]" },
    { key: "email", header: "邮箱", className: "min-w-[200px]" },
    { key: "phone", header: "手机号", className: "min-w-[140px]" },
    {
      key: "status",
      header: "状态",
      className: "w-[100px]",
      render: (row) => {
        const s = statusMap[row.status] ?? { label: row.status, variant: "default" as const };
        return <Badge variant={s.variant}>{s.label}</Badge>;
      },
    },
    {
      key: "created_at",
      header: "创建时间",
      className: "min-w-[160px]",
      render: (row) => new Date(row.created_at).toLocaleString("zh-CN"),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[160px]",
      render: (row) => (
        <div className="flex items-center gap-3">
          <button
            onClick={() => openEdit(row)}
            className="text-xs text-fg-muted hover:text-fg cursor-pointer bg-transparent border-none"
          >
            编辑
          </button>
          <button
            onClick={() => toggleStatus(row)}
            className="text-xs text-fg-muted hover:text-fg cursor-pointer bg-transparent border-none"
          >
            {row.status === "ACTIVE" ? "禁用" : "启用"}
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">用户管理</h2>
          <p className="text-sm text-fg-muted mt-1">管理系统用户账号与登录权限</p>
        </div>
        <button
          onClick={openAdd}
          className="h-9 px-4 rounded-md bg-accent text-white text-sm font-medium hover:brightness-110 cursor-pointer border-none transition-all"
        >
          添加用户
        </button>
      </div>

      <Card>
        <div className="flex items-center gap-3 mb-4">
          <input
            placeholder="搜索姓名 / 邮箱..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed w-64 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
          />
          <span className="text-xs text-fg-dimmed ml-auto">
            共 {users.length} 条
          </span>
        </div>

        <Table
          columns={columns}
          data={users}
          loading={loading}
          emptyMessage="暂无用户"
        />
      </Card>

      {modalOpen && (
        <div style={{ position: "fixed", inset: 0, zIndex: 50, display: "flex", alignItems: "center", justifyContent: "center", background: "rgba(0,0,0,0.6)", padding: "1rem" }} onClick={() => setModalOpen(false)}>
          <div style={{ background: "var(--color-surface-overlay)", border: "1px solid var(--color-border)", borderRadius: "0.5rem", width: "100%", maxWidth: "32rem", maxHeight: "85vh", overflowY: "auto", boxShadow: "var(--shadow-overlay)" }} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "1.25rem 2rem", borderBottom: "1px solid var(--color-border)" }}>
              <h3 style={{ fontSize: "1rem", fontWeight: 600, color: "var(--color-fg-emphasis)" }}>{editTarget ? "编辑用户" : "添加用户"}</h3>
              <button onClick={() => setModalOpen(false)} style={{ width: "2rem", height: "2rem", display: "flex", alignItems: "center", justifyContent: "center", background: "transparent", border: "none", borderRadius: "0.375rem", color: "var(--color-fg-muted)", cursor: "pointer" }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div style={{ padding: "2rem" }}>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">邮箱 *</label>
                  <input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">姓名</label>
                  <input value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-fg-muted mb-1">手机号</label>
                  <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                </div>
                {!editTarget && (
                  <div>
                    <label className="block text-sm font-medium text-fg-muted mb-1">密码</label>
                    <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="留空默认 123456" className="w-full h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent" />
                  </div>
                )}
                <div className="flex justify-end gap-3 pt-2">
                  <button onClick={() => setModalOpen(false)} className="h-9 px-4 rounded-md bg-surface border border-border text-sm text-fg-muted hover:text-fg cursor-pointer border-none">取消</button>
                  <button onClick={handleSave} disabled={saving || !form.email.trim()} className="h-9 px-4 rounded-md bg-accent text-white text-sm font-medium hover:brightness-110 disabled:opacity-50 cursor-pointer border-none">{saving ? "保存中..." : "保存"}</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
