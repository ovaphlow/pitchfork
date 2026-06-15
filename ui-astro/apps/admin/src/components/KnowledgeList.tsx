import { useState, useEffect } from "react";
import { listKnowledgeEntries, deleteKnowledgeEntry, type KnowledgeEntry } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column, LoadingSpinner } from "@pitchfork/ui";

export default function KnowledgeList() {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchData = () => {
    setLoading(true);
    listKnowledgeEntries({ limit: 100 })
      .then(setEntries)
      .catch(() => setEntries([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此知识条目？")) return;
    try {
      await deleteKnowledgeEntry(id);
      setEntries((prev) => prev.filter((e) => e.id !== id));
    } catch {}
  };

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    draft: "default",
    published: "success",
    archived: "warning",
  };
  const statusLabel: Record<string, string> = {
    draft: "草稿",
    published: "已发布",
    archived: "已归档",
  };

  const columns: Column<KnowledgeEntry>[] = [
    { key: "title", header: "标题", sortable: true, render: (row) => (
      <a href={`/knowledge/editor?id=${row.id}`} className="text-accent hover:underline no-underline">
        {row.title}
      </a>
    )},
    { key: "type", header: "类型", render: (row) => row.type ? <Badge>{row.type}</Badge> : "—" },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status ?? ""] || "default"}>
        {statusLabel[row.status ?? ""] || row.status}
      </Badge>
    )},
    { key: "tags", header: "标签", render: (row) => row.tags?.length ? (
      <div className="flex gap-1 flex-wrap">{row.tags.map((t) => <Badge key={t}>{t}</Badge>)}</div>
    ) : "—" },
    { key: "updated_at", header: "更新时间", render: (row) => {
      const d = row.updated_at ? new Date(row.updated_at) : null;
      return d ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}` : "—";
    } },
    { key: "actions", header: "操作", render: (row) => (
      <div className="flex gap-2">
        <a href={`/knowledge/editor?id=${row.id}`} className="no-underline">
          <Button variant="ghost" size="sm">编辑</Button>
        </a>
        <Button variant="ghost" size="sm" onClick={() => handleDelete(row.id)}>删除</Button>
      </div>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">知识库</h2>
        <a href="/knowledge/editor" className="no-underline">
          <Button>新建知识</Button>
        </a>
      </div>
      <Card>
        <Table columns={columns} data={entries} loading={loading} />
      </Card>
    </div>
  );
}
