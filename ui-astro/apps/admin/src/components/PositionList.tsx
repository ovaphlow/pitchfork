import { useState, useEffect } from "react";
import { listPositions, getPositionTree, createPosition, updatePosition, deletePosition, type Position } from "@pitchfork/shared";
import { Button, Badge, Card, Input, Modal } from "@pitchfork/ui";

export default function PositionList() {
  const [positions, setPositions] = useState<Position[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editPos, setEditPos] = useState<Position | null>(null);
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [description, setDescription] = useState("");

  useEffect(() => {
    getPositionTree()
      .then(setPositions)
      .catch(() => listPositions({ limit: 100 }).then(setPositions).catch(() => setPositions([])))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    if (!name.trim()) return;
    try {
      const data = { name, code, description };
      if (editPos) {
        await updatePosition(editPos.id, data);
        setPositions((prev) => prev.map((p) => p.id === editPos.id ? { ...p, ...data } : p));
      } else {
        const created = await createPosition(data);
        setPositions([...positions, created]);
      }
      setShowModal(false);
      setEditPos(null);
      setName("");
      setCode("");
      setDescription("");
    } catch {}
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此岗位？")) return;
    try {
      await deletePosition(id);
      setPositions((prev) => prev.filter((p) => p.id !== id));
    } catch {}
  };

  const renderTree = (items: Position[], depth = 0) => {
    return items.map((item) => (
      <div key={item.id}>
        <div className="flex items-center justify-between py-3 px-4 rounded-md hover:bg-surface-alt transition-colors" style={{ paddingLeft: `${16 + depth * 24}px` }}>
          <div>
            <span className="text-sm font-medium text-fg">{item.name}</span>
            {item.code && <span className="text-xs text-fg-dimmed ml-2">({item.code})</span>}
            {item.description && <p className="text-xs text-fg-muted mt-0.5">{item.description}</p>}
          </div>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => { setEditPos(item); setName(item.name); setCode(item.code ?? ""); setDescription(item.description ?? ""); setShowModal(true); }}>编辑</Button>
            <Button variant="ghost" size="sm" onClick={() => handleDelete(item.id)}>删除</Button>
          </div>
        </div>
        {item.children && renderTree(item.children, depth + 1)}
      </div>
    ));
  };

  if (loading) {
    return <div className="flex justify-center py-20"><span className="text-fg-dimmed">加载中...</span></div>;
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">岗位定义</h2>
        <Button onClick={() => { setEditPos(null); setName(""); setCode(""); setDescription(""); setShowModal(true); }}>
          新建岗位
        </Button>
      </div>
      <Card>
        {positions.length === 0 ? (
          <div className="text-center py-12 text-sm text-fg-dimmed">暂无岗位数据</div>
        ) : (
          renderTree(positions)
        )}
      </Card>

      <Modal open={showModal} onClose={() => setShowModal(false)} title={editPos ? "编辑岗位" : "新建岗位"}>
        <div className="space-y-4">
          <Input label="岗位名称" value={name} onChange={(e) => setName(e.target.value)} />
          <Input label="编码" value={code} onChange={(e) => setCode(e.target.value)} />
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
