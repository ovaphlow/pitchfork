import { useState, useEffect } from "react";
import { getTrainingSummary, type TrainingSummary } from "@pitchfork/shared";
import { Card, LoadingSpinner } from "@pitchfork/ui";

export default function DashboardPage() {
  const [summary, setSummary] = useState<TrainingSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getTrainingSummary()
      .then(setSummary)
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  const stats = [
    { label: "课程总数", value: summary?.total_courses ?? 0, icon: "📚" },
    { label: "培训任务", value: summary?.total_assignments ?? 0, icon: "📋" },
    { label: "完成率", value: summary?.completion_rate ? `${Math.round(summary.completion_rate * 100)}%` : "0%", icon: "✅" },
    { label: "平均分", value: summary?.avg_score ?? 0, icon: "📊" },
    { label: "在训人数", value: summary?.active_training ?? 0, icon: "👷" },
    { label: "员工总数", value: summary?.total_employees ?? 0, icon: "👥" },
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">培训概览</h2>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-8">
        {stats.map((stat) => (
          <Card key={stat.label}>
            <div className="flex items-center gap-4">
              <span className="text-3xl">{stat.icon}</span>
              <div>
                <p className="text-2xl font-bold text-fg-emphasis">{stat.value}</p>
                <p className="text-sm text-fg-muted">{stat.label}</p>
              </div>
            </div>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card title="近期动态">
          <div className="flex flex-col gap-3">
            <div className="flex items-center justify-between py-2 border-b border-border/50">
              <span className="text-sm text-fg">新课程已发布</span>
              <span className="text-xs text-fg-dimmed">2小时前</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-border/50">
              <span className="text-sm text-fg">员工技能评估完成</span>
              <span className="text-xs text-fg-dimmed">昨天</span>
            </div>
            <div className="flex items-center justify-between py-2">
              <span className="text-sm text-fg">培训任务分配</span>
              <span className="text-xs text-fg-dimmed">3天前</span>
            </div>
          </div>
        </Card>

        <Card title="快速操作">
          <div className="grid grid-cols-2 gap-3">
            <a href="/knowledge/editor" className="flex flex-col items-center gap-2 p-4 rounded-md bg-surface-alt hover:bg-surface-alt/80 transition-colors no-underline cursor-pointer">
              <span className="text-2xl">📝</span>
              <span className="text-xs text-fg-muted">新建知识</span>
            </a>
            <a href="/courses/editor" className="flex flex-col items-center gap-2 p-4 rounded-md bg-surface-alt hover:bg-surface-alt/80 transition-colors no-underline cursor-pointer">
              <span className="text-2xl">🎓</span>
              <span className="text-xs text-fg-muted">创建课程</span>
            </a>
            <a href="/training-tasks" className="flex flex-col items-center gap-2 p-4 rounded-md bg-surface-alt hover:bg-surface-alt/80 transition-colors no-underline cursor-pointer">
              <span className="text-2xl">📋</span>
              <span className="text-xs text-fg-muted">分配任务</span>
            </a>
            <a href="/analytics" className="flex flex-col items-center gap-2 p-4 rounded-md bg-surface-alt hover:bg-surface-alt/80 transition-colors no-underline cursor-pointer">
              <span className="text-2xl">📊</span>
              <span className="text-xs text-fg-muted">查看报表</span>
            </a>
          </div>
        </Card>
      </div>
    </div>
  );
}
