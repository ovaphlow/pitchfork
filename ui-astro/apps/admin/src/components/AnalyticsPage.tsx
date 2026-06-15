import { useState, useEffect } from "react";
import { getTrainingSummary, getSkillHeatmap, getQualityCorrelation, type TrainingSummary, type SkillHeatmap, type QualityCorrelation } from "@pitchfork/shared";
import { Card, LoadingSpinner } from "@pitchfork/ui";

export default function AnalyticsPage() {
  const [summary, setSummary] = useState<TrainingSummary | null>(null);
  const [heatmap, setHeatmap] = useState<SkillHeatmap[]>([]);
  const [correlation, setCorrelation] = useState<QualityCorrelation[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      getTrainingSummary(),
      getSkillHeatmap().catch(() => [] as SkillHeatmap[]),
      getQualityCorrelation().catch(() => [] as QualityCorrelation[]),
    ])
      .then(([summary, heatmap, correlation]) => {
        setSummary(summary);
        setHeatmap(heatmap);
        setCorrelation(correlation);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div>
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">数据分析</h2>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-8">
        <Card>
          <div className="flex items-center gap-4">
            <span className="text-3xl">📚</span>
            <div>
              <p className="text-2xl font-bold text-fg-emphasis">{summary?.total_courses ?? 0}</p>
              <p className="text-sm text-fg-muted">课程总数</p>
            </div>
          </div>
        </Card>
        <Card>
          <div className="flex items-center gap-4">
            <span className="text-3xl">✅</span>
            <div>
              <p className="text-2xl font-bold text-fg-emphasis">
                {summary?.completion_rate ? `${Math.round(summary.completion_rate * 100)}%` : "0%"}
              </p>
              <p className="text-sm text-fg-muted">完成率</p>
            </div>
          </div>
        </Card>
        <Card>
          <div className="flex items-center gap-4">
            <span className="text-3xl">📊</span>
            <div>
              <p className="text-2xl font-bold text-fg-emphasis">{summary?.avg_score ?? 0}</p>
              <p className="text-sm text-fg-muted">平均分</p>
            </div>
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Skill Heatmap */}
        <Card title="技能热力图">
          {heatmap.length === 0 ? (
            <div className="text-center py-8 text-sm text-fg-dimmed">暂无数据</div>
          ) : (
            <div className="space-y-3">
              {heatmap.slice(0, 10).map((h, i) => (
                <div key={i}>
                  <div className="flex items-center justify-between text-xs text-fg-muted mb-1">
                    <span>{h.skill_name || "—"}</span>
                    <span>{h.department_name || "—"}</span>
                  </div>
                  <div className="h-2 rounded-full bg-surface-alt overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all"
                      style={{
                        width: `${((h.level ?? 0) / 5) * 100}%`,
                        background: `oklch(${60 + (h.level ?? 0) * 8}% ${0.1 + (h.level ?? 0) * 0.02} 235)`,
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        {/* Quality Correlation */}
        <Card title="技能与质量关联">
          {correlation.length === 0 ? (
            <div className="text-center py-8 text-sm text-fg-dimmed">暂无数据</div>
          ) : (
            <div className="space-y-3">
              {correlation.map((c, i) => (
                <div key={i} className="flex items-center justify-between py-2 border-b border-border/50 last:border-b-0">
                  <span className="text-sm text-fg">{c.skill_name || "—"}</span>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-fg-muted">质量分: {c.quality_score ?? "—"}</span>
                    <span className={`text-xs font-medium ${(c.correlation ?? 0) > 0 ? "text-success" : "text-danger"}`}>
                      {(c.correlation ?? 0) > 0 ? "+" : ""}{c.correlation?.toFixed(2)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
