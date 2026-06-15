import { useState, useEffect } from "react";
import { listExamPapers, deleteExamPaper, generateExamPaper, type ExamPaper } from "@pitchfork/shared";
import { Button, Badge, Card, Table, type Column } from "@pitchfork/ui";

export default function ExamList() {
  const [papers, setPapers] = useState<ExamPaper[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    listExamPapers({ limit: 100 })
      .then(setPapers)
      .catch(() => setPapers([]))
      .finally(() => setLoading(false));
  }, []);

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此试卷？")) return;
    try {
      await deleteExamPaper(id);
      setPapers((prev) => prev.filter((p) => p.id !== id));
    } catch {}
  };

  const handleGenerate = async (id: string) => {
    try {
      await generateExamPaper(id);
      alert("试卷生成成功");
    } catch (err) {
      alert(err instanceof Error ? err.message : "生成失败");
    }
  };

  const statusVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    draft: "default",
    published: "success",
    archived: "warning",
  };

  const columns: Column<ExamPaper>[] = [
    { key: "title", header: "试卷名称", sortable: true, render: (row) => (
      <span className="text-sm text-fg font-medium">{row.title}</span>
    )},
    { key: "question_count", header: "题量" },
    { key: "total_score", header: "总分" },
    { key: "pass_score", header: "及格分" },
    { key: "duration", header: "时长(分钟)" },
    { key: "status", header: "状态", render: (row) => (
      <Badge variant={statusVariant[row.status ?? ""] || "default"}>
        {row.status === "published" ? "已发布" : row.status === "draft" ? "草稿" : row.status || "—"}
      </Badge>
    )},
    { key: "actions", header: "操作", render: (row) => (
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={() => handleGenerate(row.id)}>生成</Button>
        <Button variant="ghost" size="sm" onClick={() => handleDelete(row.id)}>删除</Button>
      </div>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">试卷管理</h2>
        <a href="/questions" className="no-underline">
          <Button variant="secondary">去题库</Button>
        </a>
      </div>
      <Card>
        <Table columns={columns} data={papers} loading={loading} />
      </Card>
    </div>
  );
}
