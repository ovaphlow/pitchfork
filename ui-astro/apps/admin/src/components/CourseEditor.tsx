import { useState, useEffect } from "react";
import { listCourses, createCourse, updateCourse, listChapters, createChapter, updateChapter, deleteChapter, type Course, type Chapter } from "@pitchfork/shared";
import { Button, Input, Card, Badge } from "@pitchfork/ui";

export default function CourseEditor() {
  const [editId, setEditId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [courseType, setCourseType] = useState("线上");
  const [description, setDescription] = useState("");
  const [difficulty, setDifficulty] = useState("beginner");
  const [category, setCategory] = useState("");
  const [duration, setDuration] = useState<number | undefined>(undefined);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("id");
    setEditId(id);
  }, []);

  useEffect(() => {
    if (!editId) return;
    listCourses({ limit: 1 })
      .then((courses) => {
        const c = courses.find((c) => c.id === editId);
        if (c) {
          setTitle(c.title);
          setCourseType(c.type || "线上");
          setDescription(c.description || "");
          setDifficulty(c.difficulty || "beginner");
          setCategory(c.category || "");
          setDuration(c.duration ?? undefined);
        }
      })
      .catch(() => {});
    listChapters(editId)
      .then(setChapters)
      .catch(() => setChapters([]));
  }, [editId]);

  const handleSave = async () => {
    if (!title.trim()) return;
    setSaving(true);
    setMessage("");
    try {
      const data = {
        title,
        type: courseType,
        metadata: {
          description,
          difficulty,
          category,
          ...(duration != null ? { duration } : {}),
        },
      };
      if (editId) {
        await updateCourse(editId, data);
        setMessage("更新成功");
      } else {
        const course = await createCourse(data);
        window.location.href = `/courses/editor?id=${course.id}`;
      }
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const [newChapterTitle, setNewChapterTitle] = useState("");

  const handleAddChapter = async () => {
    if (!editId || !newChapterTitle.trim()) return;
    try {
      const ch = await createChapter(editId, {
        title: newChapterTitle.trim(),
        sort_order: chapters.length + 1,
      });
      setChapters([...chapters, ch]);
      setNewChapterTitle("");
    } catch {}
  };

  const handleDeleteChapter = async (id: string) => {
    try {
      await deleteChapter(id);
      setChapters(chapters.filter((c) => c.id !== id));
    } catch {}
  };

  return (
    <div className="max-w-4xl">
      <div className="flex items-center gap-4 mb-6">
        <a href="/courses" className="no-underline">
          <Button variant="ghost" size="sm">← 返回</Button>
        </a>
        <h2 className="text-lg font-semibold text-fg-emphasis">
          {editId ? "编辑课程" : "创建课程"}
        </h2>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="space-y-5">
          <Input label="课程名称" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="输入课程名称" />
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">描述</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              placeholder="课程描述"
              className="w-full px-3 py-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent resize-y"
            />
          </div>
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">难度</label>
            <select
              value={difficulty}
              onChange={(e) => setDifficulty(e.target.value)}
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
            >
              <option value="beginner">初级</option>
              <option value="intermediate">中级</option>
              <option value="advanced">高级</option>
            </select>
          </div>
          <Input label="分类" value={category} onChange={(e) => setCategory(e.target.value)} placeholder="例如: 安全、操作、质量" />
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">课程类型</label>
            <select
              value={courseType}
              onChange={(e) => setCourseType(e.target.value)}
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
            >
              <option value="线上">线上</option>
              <option value="线下实操">线下实操</option>
            </select>
          </div>
          <Input
            label="时长(分钟)"
            type="number"
            value={duration ?? ""}
            onChange={(e) => setDuration(e.target.value ? Number(e.target.value) : undefined)}
            placeholder="例如: 90"
          />

          {message && (
            <div className={`p-3 rounded-lg text-sm ${message.includes("成功") ? "bg-success-bg text-success" : "bg-danger-bg text-danger"}`}>
              {message}
            </div>
          )}

          <Button onClick={handleSave} loading={saving}>
            {editId ? "更新课程" : "创建课程"}
          </Button>
        </div>

        {/* Chapters */}
        {editId && (
          <Card title={`章节 (${chapters.length})`}>
            <div className="flex flex-col gap-3">
              {chapters.map((ch, idx) => (
                <div key={ch.id} className="flex items-center justify-between p-3 rounded-md bg-surface-alt">
                  <div className="flex items-center gap-3">
                    <span className="w-6 h-6 rounded-full bg-accent/20 text-accent flex items-center justify-center text-xs font-medium">
                      {idx + 1}
                    </span>
                    <span className="text-sm text-fg">{ch.title}</span>
                  </div>
                  <Button variant="ghost" size="sm" onClick={() => handleDeleteChapter(ch.id)}>删除</Button>
                </div>
              ))}
            </div>
            <div className="flex gap-2 mt-4">
              <input
                value={newChapterTitle}
                onChange={(e) => setNewChapterTitle(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), handleAddChapter())}
                placeholder="新章节标题"
                className="flex-1 h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              />
              <Button variant="secondary" onClick={handleAddChapter}>添加</Button>
            </div>
          </Card>
        )}
      </div>
    </div>
  );
}
