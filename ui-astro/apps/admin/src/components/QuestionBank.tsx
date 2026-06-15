import { useState, useEffect } from "react";
import { listQuestions, createQuestion, updateQuestion, deleteQuestion, type Question } from "@pitchfork/shared";
import { Button, Badge, Card, Table, Input, Modal, type Column } from "@pitchfork/ui";

export default function QuestionBank() {
  const [questions, setQuestions] = useState<Question[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editQuestion, setEditQuestion] = useState<Question | null>(null);
  const [content, setContent] = useState("");
  const [type, setType] = useState("single");
  const [difficulty, setDifficulty] = useState("medium");
  const [category, setCategory] = useState("");

  useEffect(() => {
    listQuestions({ limit: 100 })
      .then(setQuestions)
      .catch(() => setQuestions([]))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    if (!content.trim()) return;
    try {
      const data = { content, type, difficulty, category: category || undefined };
      if (editQuestion) {
        await updateQuestion(editQuestion.id, data);
        setQuestions((prev) => prev.map((q) => q.id === editQuestion.id ? { ...q, ...data } : q));
      } else {
        const created = await createQuestion(data);
        setQuestions([...questions, created]);
      }
      setShowModal(false);
      setEditQuestion(null);
      setContent("");
      setType("single");
      setDifficulty("medium");
      setCategory("");
    } catch {}
  };

  const handleEdit = (q: Question) => {
    setEditQuestion(q);
    setContent(q.content);
    setType(q.type ?? "single");
    setDifficulty(q.difficulty ?? "medium");
    setCategory(q.category ?? "");
    setShowModal(true);
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此题？")) return;
    try {
      await deleteQuestion(id);
      setQuestions((prev) => prev.filter((q) => q.id !== id));
    } catch {}
  };

  const difficultyLabel: Record<string, string> = { easy: "简单", medium: "中等", hard: "困难" };
  const difficultyVariant: Record<string, "default" | "success" | "warning" | "danger" | "info"> = {
    easy: "success", medium: "warning", hard: "danger",
  };

  const columns: Column<Question>[] = [
    { key: "content", header: "题目", render: (row) => (
      <span className="text-sm text-fg line-clamp-2">{row.content}</span>
    )},
    { key: "type", header: "类型", render: (row) => (
      <Badge>{row.type === "single" ? "单选" : row.type === "multi" ? "多选" : row.type || "—"}</Badge>
    )},
    { key: "difficulty", header: "难度", render: (row) => (
      <Badge variant={difficultyVariant[row.difficulty ?? ""] || "default"}>
        {difficultyLabel[row.difficulty ?? ""] || row.difficulty || "—"}
      </Badge>
    )},
    { key: "category", header: "分类" },
    { key: "actions", header: "操作", render: (row) => (
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={() => handleEdit(row)}>编辑</Button>
        <Button variant="ghost" size="sm" onClick={() => handleDelete(row.id)}>删除</Button>
      </div>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">题库</h2>
        <Button onClick={() => { setEditQuestion(null); setContent(""); setType("single"); setDifficulty("medium"); setCategory(""); setShowModal(true); }}>
          新建题目
        </Button>
      </div>
      <Card>
        <Table columns={columns} data={questions} loading={loading} />
      </Card>

      <Modal open={showModal} onClose={() => setShowModal(false)} title={editQuestion ? "编辑题目" : "新建题目"}>
        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">题目内容</label>
            <textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={4}
              placeholder="输入题目..."
              className="w-full px-3 py-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent resize-y"
            />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="text-sm font-medium text-fg-muted block mb-1.5">类型</label>
              <select value={type} onChange={(e) => setType(e.target.value)}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full">
                <option value="single">单选</option>
                <option value="multi">多选</option>
                <option value="judge">判断</option>
                <option value="fill">填空</option>
              </select>
            </div>
            <div>
              <label className="text-sm font-medium text-fg-muted block mb-1.5">难度</label>
              <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full">
                <option value="easy">简单</option>
                <option value="medium">中等</option>
                <option value="hard">困难</option>
              </select>
            </div>
            <Input label="分类" value={category} onChange={(e) => setCategory(e.target.value)} />
          </div>
          <div className="flex gap-3 pt-2">
            <Button onClick={handleSave}>保存</Button>
            <Button variant="secondary" onClick={() => setShowModal(false)}>取消</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
