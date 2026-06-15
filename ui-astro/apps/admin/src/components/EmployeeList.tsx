import { useState, useEffect } from "react";
import { listPositions, listSkills, listEmployeeSkills, type Position, type Skill, type EmployeeSkill } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column, Input } from "@pitchfork/ui";

// Simulated employee list — in real app this comes from the API
interface Employee {
  id: string;
  name: string;
  department: string;
  position: string;
  status: string;
}

export default function EmployeeList() {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // In production, call listUsers() or a dedicated employee API
    // For now, use demo data
    import("@pitchfork/shared").then(({ listUsers }) => {
      listUsers({ limit: 100 })
        .then((users: any) => {
          setEmployees(
            (users as any[]).map((u: any) => ({
              id: u.id,
              name: u.username || u.email,
              department: "—",
              position: "—",
              status: u.status || "active",
            }))
          );
        })
        .catch(() => setEmployees([]))
        .finally(() => setLoading(false));
    });
  }, []);

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    active: "success",
    inactive: "danger",
    suspended: "warning",
  };

  const columns: Column<Employee>[] = [
    { key: "name", header: "姓名", sortable: true },
    { key: "department", header: "部门" },
    { key: "position", header: "岗位" },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status] || "default"}>
        {row.status === "active" ? "在职" : row.status === "inactive" ? "离职" : row.status}
      </Badge>
    )},
    { key: "actions", header: "操作", render: (row) => (
      <Button variant="ghost" size="sm">查看技能</Button>
    )},
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">员工管理</h2>
      <Card>
        <Table columns={columns} data={employees} loading={loading} />
      </Card>
    </div>
  );
}
