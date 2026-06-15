import { useState, useEffect, useMemo } from "react";
import { listUsers, listRoles, listDepartments, assignRole, unassignRole, getUserAssignments, updateUser } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column, Input } from "@pitchfork/ui";

interface UserWithInfo {
  id: string;
  email: string;
  username: string;
  phone: string;
  user_type: string;
  status: string;
  department_code?: string;
  department_name?: string;
  role_names?: string[];
}

export default function EmployeeList() {
  const [users, setUsers] = useState<UserWithInfo[]>([]);
  const [allRoles, setAllRoles] = useState<{ id: string; name: string; code: string }[]>([]);
  const [allDepts, setAllDepts] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);

  // Assign modal state
  const [modalUser, setModalUser] = useState<UserWithInfo | null>(null);
  const [userRoles, setUserRoles] = useState<string[]>([]);
  const [userDept, setUserDept] = useState("");
  const [saving, setSaving] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [userData, roleData, deptData] = await Promise.all([
        listUsers({ limit: 100 }),
        listRoles(),
        listDepartments() as Promise<any[]>,
      ]);

      const deptMap = new Map(deptData.map((d: any) => [d.code, d.payload?.name || d.name]));
      const roleMap = new Map(roleData.map((r) => [r.id, r.name]));

      // Fetch role assignments for each user
      const usersWithRoles: UserWithInfo[] = [];
      for (const u of userData as any[]) {
        let roleNames: string[] = [];
        try {
          const assignments = await getUserAssignments(u.id);
          roleNames = assignments.map((a) => roleMap.get(a.role_id) || a.role_name);
        } catch {}

        usersWithRoles.push({
          id: u.id,
          email: u.email,
          username: u.username || u.email,
          phone: u.phone || "",
          user_type: u.user_type || "regular",
          status: u.status || "active",
          department_code: u.department_code || "",
          department_name: deptMap.get(u.department_code) || "—",
          role_names: roleNames,
        });
      }

      setUsers(usersWithRoles);
      setAllRoles(roleData);
      setAllDepts(deptData.map((d: any) => ({ id: d.id, code: d.code, name: d.payload?.name || d.name })));
    } catch {}
    setLoading(false);
  };

  useEffect(() => { fetchData(); }, []);

  const openAssign = async (user: UserWithInfo) => {
    setModalUser(user);
    setUserDept(user.department_code || "");
    try {
      const assignments = await getUserAssignments(user.id);
      setUserRoles(assignments.map((a) => a.role_id));
    } catch {
      setUserRoles([]);
    }
  };

  const handleSaveRoles = async () => {
    if (!modalUser) return;
    setSaving(true);
    try {
      // Get current assignments
      const current = await getUserAssignments(modalUser.id);
      const currentRoleIds = current.map((a) => a.role_id);

      // Remove roles that were unchecked
      for (const rid of currentRoleIds) {
        if (!userRoles.includes(rid)) {
          await unassignRole(modalUser.id, rid).catch(() => {});
        }
      }

      // Add new roles
      for (const rid of userRoles) {
        if (!currentRoleIds.includes(rid)) {
          await assignRole(modalUser.id, rid);
        }
      }

      // Save department
      if (userDept !== (modalUser.department_code || "")) {
        await updateUser(modalUser.id, { department_code: userDept || undefined });
      }

      setModalUser(null);
      fetchData();
    } catch {}
    setSaving(false);
  };

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    active: "success",
    pending: "warning",
    inactive: "danger",
  };

  const columns: Column<UserWithInfo>[] = [
    { key: "username", header: "姓名", sortable: true },
    { key: "email", header: "邮箱" },
    { key: "department_name", header: "部门" },
    { key: "role_names", header: "角色", render: (row) => (
      <div className="flex gap-1 flex-wrap">
        {row.role_names?.length ? row.role_names.map((r) => <Badge key={r}>{r}</Badge>) : <span className="text-fg-dimmed">—</span>}
      </div>
    )},
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status] || "default"}>
        {row.status === "active" ? "在职" : row.status === "pending" ? "待激活" : row.status || row.status}
      </Badge>
    )},
    { key: "actions", header: "操作", render: (row) => (
      <Button variant="ghost" size="sm" className="text-accent" onClick={() => openAssign(row)}>设置</Button>
    )},
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">员工管理</h2>
      <Card>
        <Table columns={columns} data={users} loading={loading} />
      </Card>

      {/* Assign Modal */}
      {modalUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setModalUser(null)}>
          <div className="bg-surface rounded-xl border border-border p-6 w-full max-w-lg max-h-[80vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
            <h3 className="text-base font-semibold text-fg-emphasis mb-1">员工设置</h3>
            <p className="text-sm text-fg-dimmed mb-5">{modalUser.username} ({modalUser.email})</p>

            {/* Department */}
            <div className="mb-5">
              <label className="text-sm font-medium text-fg-muted block mb-2">部门</label>
              <select
                value={userDept}
                onChange={(e) => setUserDept(e.target.value)}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
              >
                <option value="">无部门</option>
                {allDepts.map((d) => (
                  <option key={d.code} value={d.code}>{d.name}</option>
                ))}
              </select>
            </div>

            {/* Roles */}
            <div className="mb-5">
              <label className="text-sm font-medium text-fg-muted block mb-2">角色</label>
              <div className="flex flex-col gap-2 max-h-48 overflow-y-auto border border-border rounded-md p-2">
                {allRoles.map((role) => (
                  <label key={role.id} className="flex items-center gap-3 px-3 py-2 rounded hover:bg-surface-alt cursor-pointer text-sm text-fg">
                    <input
                      type="checkbox"
                      checked={userRoles.includes(role.id)}
                      onChange={(e) => {
                        if (e.target.checked) setUserRoles([...userRoles, role.id]);
                        else setUserRoles(userRoles.filter((r) => r !== role.id));
                      }}
                      className="accent-accent rounded"
                    />
                    {role.name}
                    <span className="text-xs text-fg-dimmed">({role.code})</span>
                  </label>
                ))}
                {allRoles.length === 0 && <span className="text-sm text-fg-dimmed px-3 py-2">暂无角色，请先在角色管理创建</span>}
              </div>
            </div>

            <div className="flex gap-3 justify-end">
              <Button variant="secondary" onClick={() => setModalUser(null)}>取消</Button>
              <Button onClick={handleSaveRoles} loading={saving}>保存</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
