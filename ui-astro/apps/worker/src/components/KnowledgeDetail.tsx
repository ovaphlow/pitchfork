import { useState, useEffect } from "react";
import { listKnowledgeEntries, listKnowledgeVersions, listKnowledgeFeedbacks, type KnowledgeEntry, type KnowledgeVersion, type KnowledgeFeedback } from "@pitchfork/shared";
import { Badge, LoadingSpinner } from "@pitchfork/ui";

// Simple hook to get path params since we're in Astro
function usePathId() {
  if (typeof window === "undefined") return "";
  const m = window.location.pathname.match(/\/knowledge\/([^/]+)/);
  return m ? m[1] : "";
}

export default function KnowledgeDetail() {
  const id = usePathId();
  const [entry, setEntry] = useState<KnowledgeEntry | null>(null);
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [feedbacks, setFeedbacks] = useState<KnowledgeFeedback[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<"content" | "versions" | "feedback">("content");

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    Promise.all([
      listKnowledgeEntries({ limit: 1 }).then((entries) => entries.find((e) => e.id === id)),
      listKnowledgeVersions(id).catch(() => [] as KnowledgeVersion[]),
      listKnowledgeFeedbacks(id).catch(() => [] as KnowledgeFeedback[]),
    ])
      .then(([entry, versions, feedbacks]) => {
        setEntry(entry ?? null);
        setVersions(versions);
        setFeedbacks(feedbacks);
      })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  if (!entry) {
    return (
      <div className="flex flex-col items-center py-20 text-center">
        <span className="text-4xl mb-3">📄</span>
        <p className="text-sm text-fg-dimmed">知识条目未找到</p>
      </div>
    );
  }

  const tabs = [
    { key: "content" as const, label: "内容" },
    { key: "versions" as const, label: `版本 (${versions.length})` },
    { key: "feedback" as const, label: `反馈 (${feedbacks.length})` },
  ];

  return (
    <div>
      {/* Header */}
      <div className="mb-4">
        <h1 className="text-lg font-semibold text-fg-emphasis mb-2">{entry.title}</h1>
        <div className="flex items-center gap-2 flex-wrap">
          {entry.category_name && <Badge>{entry.category_name}</Badge>}
          {entry.tags?.map((tag) => (
            <span key={tag} className="text-xs text-fg-dimmed">#{tag}</span>
          ))}
          <span className="text-xs text-fg-dimmed ml-auto">v{entry.version ?? 1}</span>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border mb-4">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex-1 h-10 text-sm font-medium transition-all cursor-pointer border-none ${
              activeTab === tab.key
                ? "text-accent border-b-2 border-accent bg-transparent"
                : "text-fg-muted hover:text-fg bg-transparent"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content Tab */}
      {activeTab === "content" && (
        <div className="prose prose-invert prose-sm max-w-none">
          <div
            className="text-sm text-fg leading-relaxed whitespace-pre-wrap"
            dangerouslySetInnerHTML={{ __html: entry.content || "" }}
          />
        </div>
      )}

      {/* Versions Tab */}
      {activeTab === "versions" && (
        <div className="flex flex-col gap-2">
          {versions.length === 0 ? (
            <p className="text-sm text-fg-dimmed text-center py-8">暂无版本记录</p>
          ) : (
            versions.map((v) => (
              <div key={v.id} className="flex items-center justify-between p-3 rounded-lg border border-border bg-surface">
                <div>
                  <span className="text-sm font-medium text-fg">v{v.version}</span>
                  <span className="text-xs text-fg-dimmed ml-2">{v.created_at}</span>
                </div>
                <Badge variant={v.status === "approved" ? "success" : v.status === "rejected" ? "danger" : "default"}>
                  {v.status === "approved" ? "已通过" : v.status === "rejected" ? "已拒绝" : "待审核"}
                </Badge>
              </div>
            ))
          )}
        </div>
      )}

      {/* Feedback Tab */}
      {activeTab === "feedback" && (
        <div className="flex flex-col gap-3">
          {feedbacks.length === 0 ? (
            <p className="text-sm text-fg-dimmed text-center py-8">暂无反馈</p>
          ) : (
            feedbacks.map((fb) => (
              <div key={fb.id} className="p-3 rounded-lg border border-border bg-surface">
                <p className="text-sm text-fg mb-2">{fb.content}</p>
                <div className="flex items-center justify-between text-xs text-fg-dimmed">
                  <span>{fb.created_by || "匿名"}</span>
                  <span>{fb.created_at}</span>
                </div>
                {fb.reply && (
                  <div className="mt-2 pt-2 border-t border-border">
                    <p className="text-xs text-accent mb-1">回复:</p>
                    <p className="text-sm text-fg-muted">{fb.reply}</p>
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
