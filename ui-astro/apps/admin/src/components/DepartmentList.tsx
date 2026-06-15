import { useState, useEffect } from "react";
import { listDepartments, createDepartment, updateDepartment, deleteDepartment } from "@pitchfork/shared";
import { Button, Input, Card, Badge } from "@pitchfork/ui";

interface Department {
  id: string;
  name: string;
  code: string;
  parent_code?: string;
  payload: { name: string; description?: string };
  created_at?: string;
  updated_at?: string;
}

export default function DepartmentList() {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Department | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formName, setFormName] = useState("");
  const [formCode, setFormCode] = useState("");
  const [formParentCode, setFormParentCode] = useState("");
  const [formDescription, setFormDescription] = useState("");
  const [message, setMessage] = useState("");

  const fetchData = () => {
    setLoading(true);
    listDepartments()
      .then((data) => setDepartments(data as Department[]))
      .catch(() => setDepartments([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const resetForm = () => {
    setEditing(null);
    setShowForm(false);
    setFormName("");
    setFormCode("");
    setFormParentCode("");
    setFormDescription("");
    setMessage("");
  };

  const openEdit = (dept: Department) => {
    setEditing(dept);
    setShowForm(true);
    setFormName(dept.payload?.name ?? dept.name);
    setFormCode(dept.code);
    setFormParentCode(dept.parent_code ?? "");
    setFormDescription(dept.payload?.description ?? "");
  };

  const handleSave = async () => {
    if (!formName.trim() || !formCode.trim()) {
      setMessage("名称和编码不能为空");
      return;
    }
    setMessage("");
    try {
      if (editing) {
        await updateDepartment(editing.id, {
          name: formName,
          code: formCode,
          parent_code: formParentCode || undefined,
          description: formDescription,
        });
      } else {
        await createDepartment({
          name: formName,
          code: formCode,
          parent_code: formParentCode || undefined,
          description: formDescription,
        });
      }
      resetForm();
      fetchData();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "保存失败");
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm("确定删除此部门？")) return;
    try {
      await deleteDepartment(id);
      fetchData();
    } catch {}
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-lg font-semibold text-fg-emphasis">部门管理</h2>
        <Button onClick={() => { resetForm(); setShowForm(true); }}>
          新增部门
        </Button>
      </div>

      {message && (
        <div className={`p-3 rounded-lg text-sm mb-4 ${message.includes("成功") ? "bg-success-bg text-success" : "bg-danger-bg text-danger"}`}>
          {message}
        </div>
      )}

      {showForm && (
        <Card className="mb-6">
          <h3 className="text-sm font-semibold text-fg-emphasis mb-4">
            {editing ? "编辑部门" : "新增部门"}
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input label="部门名称" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="例如: 生产部" />
            <Input label="部门编码" value={formCode} onChange={(e) => setFormCode(e.target.value)} placeholder="例如: dept-prod" disabled={!!editing} />
            <Input label="上级编码" value={formParentCode} onChange={(e) => setFormParentCode(e.target.value)} placeholder="留空为顶级部门" />
            <Input label="描述" value={formDescription} onChange={(e) => setFormDescription(e.target.value)} placeholder="部门描述" />
          </div>
          <div className="flex gap-3 mt-4">
            <Button onClick={handleSave}>{editing ? "更新" : "创建"}</Button>
            <Button variant="secondary" onClick={resetForm}>取消</Button>
          </div>
        </Card>
      )}

      {departments.length === 0 ? (
        <Card>
          <div className="py-12 text-center text-sm text-fg-dimmed">暂无部门数据</div>
        </Card>
      ) : (
        <div className="space-y-2">
          {departments.map((dept) => (
            <Card key={dept.id}>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-full bg-accent/20 text-accent flex items-center justify-center text-sm font-medium">
                    {dept.payload?.name?.charAt(0) || dept.name?.charAt(0) || "?"}
                  </div>
                  <div>
                    <div className="text-sm font-medium text-fg-emphasis">{dept.payload?.name || dept.name}</div>
                    <div className="text-xs text-fg-dimmed mt-0.5">
                      编码: {dept.code}
                      {dept.parent_code ? ` / 上级: ${dept.parent_code}` : " / 顶级部门"}
                    </div>
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" size="sm" className="text-orange-500" onClick={() => openEdit(dept)}>编辑</Button>
                  <Button variant="ghost" size="sm" className="text-red-500" onClick={() => handleDelete(dept.id)}>删除</Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
