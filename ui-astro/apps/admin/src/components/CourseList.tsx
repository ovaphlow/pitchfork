import { useState, useEffect } from "react";
import { listCourses, deleteCourse, type Course } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column } from "@pitchfork/ui";

export default function CourseList() {
  const [courses, setCourses] = useState<Course[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    listCourses({ limit: 100 })
      .then(setCourses)
      .catch(() => setCourses([]))
      .finally(() => setLoading(false));
  }, []);

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此课程？")) return;
    try {
      await deleteCourse(id);
      setCourses((prev) => prev.filter((c) => c.id !== id));
    } catch {}
  };

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    draft: "default",
    published: "success",
    archived: "warning",
  };

  const columns: Column<Course>[] = [
    { key: "title", header: "课程名称", sortable: true, render: (row) => (
      <a href={`/courses/editor?id=${row.id}`} className="text-accent hover:underline no-underline">{row.title}</a>
    )},
    { key: "type", header: "课程类型", render: (row) => row.type ? <Badge>{row.type}</Badge> : "—" },
    { key: "category", header: "分类", render: (row) => row.category ? <Badge>{row.category}</Badge> : "—" },
    { key: "difficulty", header: "难度", render: (row) => {
      const map: Record<string, string> = { beginner: "初级", intermediate: "中级", advanced: "高级" };
      return row.difficulty ? <Badge>{map[row.difficulty] || row.difficulty}</Badge> : "—";
    } },
    { key: "duration", header: "时长(分钟)", render: (row) => row.duration != null ? `${row.duration}` : "—" },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status ?? ""] || "default"}>
        {row.status === "published" ? "已发布" : row.status === "draft" ? "草稿" : row.status || "—"}
      </Badge>
    )},
    { key: "created_at", header: "创建时间", render: (row) => {
      const d = row.created_at ? new Date(row.created_at) : null;
      return d ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}` : "—";
    } },
    { key: "actions", header: "操作", render: (row) => (
      <div className="flex gap-2">
        <a href={`/courses/editor?id=${row.id}`} className="no-underline">
          <Button variant="ghost" size="sm" className="text-orange-500">编辑</Button>
        </a>
        <Button variant="ghost" size="sm" className="text-red-500" onClick={() => handleDelete(row.id)}>删除</Button>
      </div>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">课程管理</h2>
        <a href="/courses/editor" className="no-underline">
          <Button>创建课程</Button>
        </a>
      </div>
      <Card>
        <Table columns={columns} data={courses} loading={loading} />
      </Card>
    </div>
  );
}
