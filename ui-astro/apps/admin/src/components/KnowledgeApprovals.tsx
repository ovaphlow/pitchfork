import { useState, useEffect } from "react";
import { listKnowledgeEntries, listKnowledgeVersions, approveKnowledgeVersion, rejectKnowledgeVersion, type KnowledgeEntry, type KnowledgeVersion } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column, LoadingSpinner } from "@pitchfork/ui";

export default function KnowledgeApprovals() {
  const [versions, setVersions] = useState<(KnowledgeVersion & { entry_title?: string })[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    listKnowledgeEntries({ limit: 100 })
      .then(async (entries) => {
        const allVersions: (KnowledgeVersion & { entry_title?: string })[] = [];
        for (const entry of entries) {
          const vs = await listKnowledgeVersions(entry.id).catch(() => []);
          allVersions.push(...vs.map((v) => ({ ...v, entry_title: entry.title })));
        }
        return allVersions;
      })
      .then(setVersions)
      .catch(() => setVersions([]))
      .finally(() => setLoading(false));
  }, []);

  const handleApprove = async (entryId: string, versionId: string) => {
    try {
      await approveKnowledgeVersion(entryId, versionId);
      setVersions((prev) => prev.map((v) => v.id === versionId ? { ...v, status: "approved" } : v));
    } catch {}
  };

  const handleReject = async (entryId: string, versionId: string) => {
    try {
      await rejectKnowledgeVersion(entryId, versionId);
      setVersions((prev) => prev.map((v) => v.id === versionId ? { ...v, status: "rejected" } : v));
    } catch {}
  };

  const pending = versions.filter((v) => v.status === "pending");

  const columns: Column<KnowledgeVersion & { entry_title?: string }>[] = [
    { key: "entry_title", header: "知识条目", render: (row) => (
      <span className="text-sm text-fg">{row.entry_title || "—"}</span>
    )},
    { key: "version", header: "版本", render: (row) => <Badge>v{row.version}</Badge> },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={row.status === "approved" ? "success" : row.status === "rejected" ? "danger" : "warning"}>
        {row.status === "approved" ? "已通过" : row.status === "rejected" ? "已拒绝" : "待审核"}
      </Badge>
    )},
    { key: "created_at", header: "提交时间" },
    { key: "actions", header: "操作", render: (row) => row.status === "pending" ? (
      <div className="flex gap-2">
        <Button size="sm" onClick={() => handleApprove(row.entry_id, row.id)}>通过</Button>
        <Button size="sm" variant="danger" onClick={() => handleReject(row.entry_id, row.id)}>拒绝</Button>
      </div>
    ) : <span className="text-xs text-fg-dimmed">已处理</span> },
  ];

  return (
    <div>
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">
        知识审批 {pending.length > 0 && <span className="text-fg-dimmed text-sm font-normal">({pending.length} 待处理)</span>}
      </h2>
      <Card>
        <Table columns={columns} data={versions} loading={loading} />
      </Card>
    </div>
  );
}
