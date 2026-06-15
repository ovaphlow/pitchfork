import { useState, useEffect } from "react";
import { getKnowledgeEntry, createKnowledgeEntry, updateKnowledgeEntry, createKnowledgeVersion, listKnowledgeCategorySettings, listKnowledgeTagSettings, type KnowledgeEntry } from "@pitchfork/shared";
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
  const [knowledgeType, setKnowledgeType] = useState("SOP");
  const [categoryCode, setCategoryCode] = useState("");
  const [availableCategories, setAvailableCategories] = useState<{ code: string; name: string }[]>([]);
  const [availableTags, setAvailableTags] = useState<string[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState("");
  const [version, setVersion] = useState<number | undefined>(undefined);
  const [changeNote, setChangeNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    listKnowledgeCategorySettings().then(setAvailableCategories).catch(() => {});
    listKnowledgeTagSettings().then(setAvailableTags).catch(() => {});
    if (editId) {
      getKnowledgeEntry(editId)
        .then((entry) => {
          setTitle(entry.title);
          setContent(entry.content ?? "");
          setKnowledgeType(entry.type ?? "SOP");
          setCategoryCode(entry.category_ids?.[0] ?? "");
          setTags(entry.tags ?? []);
          setVersion(entry.version_number ?? 1);
          setChangeNote(entry.change_note ?? "");
        })
        .catch(() => {});
    }
  }, [editId]);

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
      const data = { title, type: knowledgeType, category_ids: categoryCode ? [categoryCode] : [], tags };
      if (editId) {
        await updateKnowledgeEntry(editId, data);
        if (content.trim()) {
          await createKnowledgeVersion(editId, { content, change_note: changeNote || undefined });
        }
        setMessage("更新成功");
        setChangeNote("");
      } else {
        const entry = await createKnowledgeEntry({ ...data, content });
        // 创建初始版本
        if (entry.id && content.trim()) {
          await createKnowledgeVersion(entry.id, { content, change_note: "初始版本" });
        }
        setMessage("创建成功");
        setTitle("");
        setContent("");
        setCategoryCode("");
        setTags([]);
        setVersion(undefined);
      }
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-3xl">
      <div className="flex items-center gap-4 mb-6">
        <a href="/knowledge" className="no-underline">
          <Button variant="ghost" size="sm">← 返回</Button>
        </a>
        <h2 className="text-lg font-semibold text-fg-emphasis">
          {editId ? "编辑知识" : "新建知识"}
        </h2>
      </div>

      <div className="space-y-5">
        <Input label="标题" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="输入知识标题" />

        <div>
          <label className="text-sm font-medium text-fg-muted block mb-1.5">知识类型</label>
          <select
            value={knowledgeType}
            onChange={(e) => setKnowledgeType(e.target.value)}
            className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
          >
            <option value="SOP">标准作业程序</option>
            <option value="OPL">单点课程</option>
            <option value="故障案例">故障案例</option>
            <option value="安全须知">安全须知</option>
            <option value="工艺参数表">工艺参数表</option>
            <option value="设备手册">设备手册</option>
          </select>
        </div>

        <div>
          <label className="text-sm font-medium text-fg-muted block mb-1.5">分类</label>
          <select
            value={categoryCode}
            onChange={(e) => setCategoryCode(e.target.value)}
            className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent w-full"
          >
            <option value="">无分类</option>
            {availableCategories.map((c) => (
              <option key={c.code} value={c.code}>{c.name}</option>
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
          {availableTags.length > 0 && (
            <div className="flex gap-1.5 flex-wrap mt-2">
              {availableTags.filter((t) => !tags.includes(t)).map((t) => (
                <button
                  key={t}
                  onClick={() => { setTags([...tags, t]); }}
                  className="text-xs px-2 py-0.5 rounded-full bg-surface-alt text-fg-muted hover:bg-accent/20 hover:text-accent cursor-pointer border-none transition-colors"
                >
                  + {t}
                </button>
              ))}
            </div>
          )}
        </div>

        {version != null && (
          <div className="flex items-center gap-3 text-sm text-fg-muted p-3 rounded-md bg-surface-alt">
            <span>当前版本：<strong className="text-fg">v{version}</strong></span>
          </div>
        )}

        {editId && (
          <Input
            label="更新说明"
            value={changeNote}
            onChange={(e) => setChangeNote(e.target.value)}
            placeholder="简要描述本次变更..."
          />
        )}

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
