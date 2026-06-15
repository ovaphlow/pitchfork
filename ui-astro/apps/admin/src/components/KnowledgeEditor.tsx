import { useState, useEffect } from "react";
import { createKnowledgeEntry, updateKnowledgeEntry, listKnowledgeCategories, type KnowledgeEntry, type KnowledgeCategory } from "@pitchfork/shared";
import { Button, Input, Card, Badge } from "@pitchfork/ui";

function useQueryParam(key: string): string {
  if (typeof window === "undefined") return "";
  const params = new URLSearchParams(window.location.search);
  return params.get(key) ?? "";
}

export default function KnowledgeEditor() {
  const editId = useQueryParam("id");
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    listKnowledgeCategories().then(setCategories).catch(() => {});
  }, []);

  const handleAddTag = () => {
    const t = tagInput.trim();
    if (t && !tags.includes(t)) {
      setTags([...tags, t]);
      setTagInput("");
    }
  };

  const handleSave = async () => {
    if (!title.trim()) return;
    setSaving(true);
    setMessage("");
    try {
      const data = { title, content, category_id: categoryId || undefined, tags };
      if (editId) {
        await updateKnowledgeEntry(editId, data);
        setMessage("更新成功");
      } else {
        await createKnowledgeEntry(data);
        setMessage("创建成功");
        setTitle("");
        setContent("");
        setCategoryId("");
        setTags([]);
      }
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-3xl">
      <h2 className="text-lg font-semibold text-fg-emphasis mb-6">
        {editId ? "编辑知识" : "新建知识"}
      </h2>

      <div className="space-y-5">
        <Input label="标题" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="输入知识标题" />

        <div>
          <label className="text-sm font-medium text-fg-muted block mb-1.5">分类</label>
          <select
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
            className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
          >
            <option value="">无分类</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </div>

        <div>
          <label className="text-sm font-medium text-fg-muted block mb-1.5">标签</label>
          <div className="flex gap-2 mb-2 flex-wrap">
            {tags.map((tag) => (
              <Badge key={tag}>
                {tag}
                <button
                  onClick={() => setTags(tags.filter((t) => t !== tag))}
                  className="ml-1 text-fg-dimmed hover:text-fg cursor-pointer border-none bg-transparent p-0"
                >
                  ×
                </button>
              </Badge>
            ))}
          </div>
          <div className="flex gap-2">
            <input
              value={tagInput}
              onChange={(e) => setTagInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), handleAddTag())}
              placeholder="输入标签后按回车"
              className="flex-1 h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            />
            <Button variant="secondary" onClick={handleAddTag}>添加</Button>
          </div>
        </div>

        <div>
          <label className="text-sm font-medium text-fg-muted block mb-1.5">内容</label>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={15}
            placeholder="支持 Markdown 或 HTML 格式..."
            className="w-full px-3 py-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent resize-y font-mono"
          />
        </div>

        {message && (
          <div className={`p-3 rounded-lg text-sm ${message.includes("成功") ? "bg-success-bg text-success" : "bg-danger-bg text-danger"}`}>
            {message}
          </div>
        )}

        <div className="flex gap-3">
          <Button onClick={handleSave} loading={saving}>
            {editId ? "更新" : "创建"}
          </Button>
          <a href="/knowledge" className="no-underline">
            <Button variant="secondary">取消</Button>
          </a>
        </div>
      </div>
    </div>
  );
}
