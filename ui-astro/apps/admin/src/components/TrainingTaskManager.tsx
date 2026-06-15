import { useState, useEffect } from "react";
import { listCourses, listTrainingAssignments, createTrainingAssignment, deleteTrainingAssignment, type Course, type TrainingAssignment } from "@pitchfork/shared";
import { Button, Badge, Card, Table, Modal, type Column } from "@pitchfork/ui";

export default function TrainingTaskManager() {
  const [assignments, setAssignments] = useState<TrainingAssignment[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [selectedCourseId, setSelectedCourseId] = useState("");

  useEffect(() => {
    Promise.all([
      listTrainingAssignments({ limit: 100 }),
      listCourses({ limit: 100 }),
    ])
      .then(([assignments, courses]) => {
        setAssignments(assignments);
        setCourses(courses);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleCreate = async () => {
    if (!selectedCourseId) return;
    try {
      await createTrainingAssignment({ course_id: selectedCourseId, employee_ids: [] });
      setShowModal(false);
      setSelectedCourseId("");
      // Refresh
      const data = await listTrainingAssignments({ limit: 100 });
      setAssignments(data);
    } catch {}
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此任务？")) return;
    try {
      await deleteTrainingAssignment(id);
      setAssignments((prev) => prev.filter((a) => a.id !== id));
    } catch {}
  };

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    pending: "default",
    in_progress: "info",
    completed: "success",
    overdue: "danger",
  };
  const statusLabel: Record<string, string> = {
    pending: "待开始",
    in_progress: "进行中",
    completed: "已完成",
    overdue: "已逾期",
  };

  const columns: Column<TrainingAssignment>[] = [
    { key: "course_title", header: "课程", sortable: true, render: (row) => (
      <span className="text-sm font-medium text-fg">{row.course_title || "—"}</span>
    )},
    { key: "employee_name", header: "员工", render: (row) => row.employee_name || "—" },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status ?? ""] || "default"}>
        {statusLabel[row.status ?? ""] || row.status || "—"}
      </Badge>
    )},
    { key: "progress", header: "进度", render: (row) => row.progress !== undefined ? `${Math.round(row.progress * 100)}%` : "—" },
    { key: "deadline", header: "截止日期" },
    { key: "assigned_at", header: "分配时间" },
    { key: "actions", header: "操作", render: (row) => (
      <Button variant="ghost" size="sm" onClick={() => handleDelete(row.id)}>删除</Button>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">培训任务管理</h2>
        <Button onClick={() => { setSelectedCourseId(""); setShowModal(true); }}>
          分配任务
        </Button>
      </div>
      <Card>
        <Table columns={columns} data={assignments} loading={loading} />
      </Card>

      <Modal open={showModal} onClose={() => setShowModal(false)} title="分配培训任务">
        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">选择课程</label>
            <select
              value={selectedCourseId}
              onChange={(e) => setSelectedCourseId(e.target.value)}
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
            >
              <option value="">请选择</option>
              {courses.map((c) => (
                <option key={c.id} value={c.id}>{c.title}</option>
              ))}
            </select>
          </div>
          <div className="flex gap-3">
            <Button onClick={handleCreate}>创建任务</Button>
            <Button variant="secondary" onClick={() => setShowModal(false)}>取消</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
