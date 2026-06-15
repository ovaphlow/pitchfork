import { useState, useEffect } from "react";
import { listTrainingAssignments, type TrainingAssignment } from "@pitchfork/shared";
import { Badge, LoadingSpinner, EmptyState } from "@pitchfork/ui";

export default function TrainingTaskList() {
  const [assignments, setAssignments] = useState<TrainingAssignment[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // In a real app, get the employee ID from auth context
    listTrainingAssignments({ limit: 50 })
      .then(setAssignments)
      .catch(() => setAssignments([]))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  if (assignments.length === 0) {
    return <EmptyState icon="📚" title="暂无培训任务" description="还没有分配给你的培训课程" />;
  }

  const statusLabel: Record<string, string> = {
    pending: "待开始",
    in_progress: "进行中",
    completed: "已完成",
    overdue: "已逾期",
  };
  const statusVariant: Record<string, "default" | "warning" | "success" | "danger"> = {
    pending: "default",
    in_progress: "warning",
    completed: "success",
    overdue: "danger",
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-sm font-semibold text-fg-emphasis">
          我的培训任务 ({assignments.length})
        </h2>
      </div>
      {assignments.map((a) => (
        <a
          key={a.id}
          href={`/training/${a.course_id}`}
          className="block no-underline rounded-lg border border-border bg-surface p-4 hover:bg-surface-alt transition-colors"
        >
          <div className="flex items-start justify-between mb-2">
            <h3 className="text-sm font-semibold text-fg-emphasis flex-1 mr-2">
              {a.course_title || "未命名课程"}
            </h3>
            <Badge variant={statusVariant[a.status ?? "pending"] || "default"}>
              {statusLabel[a.status ?? "pending"] || a.status}
            </Badge>
          </div>
          <div className="flex items-center gap-4 text-xs text-fg-dimmed">
            {a.deadline && <span>截止: {a.deadline}</span>}
            {a.progress !== undefined && (
              <span>进度: {Math.round(a.progress * 100)}%</span>
            )}
          </div>
          {/* Progress bar */}
          {a.progress !== undefined && a.progress < 1 && (
            <div className="mt-3 h-1.5 rounded-full bg-surface-alt overflow-hidden">
              <div
                className="h-full rounded-full bg-accent transition-all duration-500"
                style={{ width: `${Math.round(a.progress * 100)}%` }}
              />
            </div>
          )}
        </a>
      ))}
    </div>
  );
}
