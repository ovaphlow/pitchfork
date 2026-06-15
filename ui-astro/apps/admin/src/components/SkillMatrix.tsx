import { useState, useEffect } from "react";
import { listSkills, createSkill, updateSkill, deleteSkill, type Skill } from "@pitchfork/shared";
import { Button, Badge, Card, Table, Input, Modal, type Column } from "@pitchfork/ui";

export default function SkillMatrix() {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editSkill, setEditSkill] = useState<Skill | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");

  useEffect(() => {
    listSkills({ limit: 100 })
      .then(setSkills)
      .catch(() => setSkills([]))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    if (!name.trim()) return;
    try {
      const data = { name, description, category: category || undefined };
      if (editSkill) {
        await updateSkill(editSkill.id, data);
        setSkills((prev) => prev.map((s) => s.id === editSkill.id ? { ...s, ...data } : s));
      } else {
        const created = await createSkill(data);
        setSkills([...skills, created]);
      }
      setShowModal(false);
      setEditSkill(null);
      setName("");
      setDescription("");
      setCategory("");
    } catch {}
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此技能？")) return;
    try {
      await deleteSkill(id);
      setSkills((prev) => prev.filter((s) => s.id !== id));
    } catch {}
  };

  const columns: Column<Skill>[] = [
    { key: "name", header: "技能名称", sortable: true, render: (row) => (
      <span className="text-sm font-medium text-fg">{row.name}</span>
    )},
    { key: "category", header: "分类", render: (row) => row.category ? <Badge>{row.category}</Badge> : "—" },
    { key: "description", header: "描述", render: (row) => (
      <span className="text-sm text-fg-muted line-clamp-1">{row.description || "—"}</span>
    )},
    { key: "created_at", header: "创建时间" },
    { key: "actions", header: "操作", render: (row) => (
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={() => { setEditSkill(row); setName(row.name); setDescription(row.description ?? ""); setCategory(row.category ?? ""); setShowModal(true); }}>编辑</Button>
        <Button variant="ghost" size="sm" onClick={() => handleDelete(row.id)}>删除</Button>
      </div>
    )},
  ];

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">技能矩阵</h2>
        <Button onClick={() => { setEditSkill(null); setName(""); setDescription(""); setCategory(""); setShowModal(true); }}>
          新建技能
        </Button>
      </div>
      <Card>
        <Table columns={columns} data={skills} loading={loading} />
      </Card>

      <Modal open={showModal} onClose={() => setShowModal(false)} title={editSkill ? "编辑技能" : "新建技能"}>
        <div className="space-y-4">
          <Input label="技能名称" value={name} onChange={(e) => setName(e.target.value)} />
          <Input label="分类" value={category} onChange={(e) => setCategory(e.target.value)} placeholder="例如: 操作、质量、安全" />
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1.5">描述</label>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3}
              className="w-full px-3 py-3 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent resize-y"
            />
          </div>
          <div className="flex gap-3">
            <Button onClick={handleSave}>保存</Button>
            <Button variant="secondary" onClick={() => setShowModal(false)}>取消</Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
