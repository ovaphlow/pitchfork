import React, { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  addRosterItem,
  copyWeeklyMenu,
  createDietProfile,
  createDish,
  createWeeklyMenu,
  deleteDietProfile,
  generateRoster,
  getMealStatistics,
  getRoster,
  getWeeklyMenu,
  listActiveElderlyAdmissions,
  listDietProfiles,
  listDishes,
  listPatients,
  listRosters,
  listWeeklyMenus,
  registerMealExecution,
  removeRosterItem,
  replaceWeeklyMenuItems,
  updateDietProfile,
  updateDietProfileStatus,
  updateDish,
  updateDishStatus,
  updateWeeklyMenuStatus,
  type DietProfile,
  type Dish,
  type MealStatistics,
  type Roster,
  type RosterItem,
  type WeeklyMenu,
} from "@pitchfork/shared/aceso";

// ─── 业务常量（与服务端枚举一致，一律中文值） ────────────────────────

const MEAL_TYPES = ["普食", "软食", "碎食", "流食", "糖尿病餐"];
const PORTION_PREFERENCES = ["标准", "大半份", "小半份"];
const DISH_CATEGORIES = ["荤菜", "素菜", "汤品", "主食", "加餐"];
const MEAL_TIMES = ["早餐", "午餐", "晚餐", "加餐"];
const DIET_TAGS = ["低盐", "低糖", "无糖", "清真", "高蛋白", "少油", "无辣"];
const MEAL_STATUSES = ["正常", "部分", "未就餐", "拒食"];
const WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const PAGE_SIZE = 50;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "-";
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function toISODate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function today(): string {
  return toISODate(new Date());
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00`);
  d.setDate(d.getDate() + days);
  return toISODate(d);
}

/** 计算某日期所在周的周一（与服务端 weekStartOf 一致） */
function weekStartOf(date: string): string {
  const d = new Date(`${date}T00:00:00`);
  const day = (d.getDay() + 6) % 7; // 周一=0
  d.setDate(d.getDate() - day);
  return toISODate(d);
}

function statusBadge(status: string): React.ReactNode {
  if (status === "启用" || status === "ACTIVE") return <Badge variant="success">启用</Badge>;
  if (status === "停用") return <Badge variant="default">停用</Badge>;
  return <Badge variant="default">{status}</Badge>;
}

function mealStatusBadge(status: string | null | undefined): React.ReactNode {
  if (!status) return <Badge variant="default">未登记</Badge>;
  if (status === "正常") return <Badge variant="success">正常</Badge>;
  if (status === "部分") return <Badge variant="warning">部分</Badge>;
  if (status === "拒食") return <Badge variant="danger">拒食</Badge>;
  return <Badge variant="default">{status}</Badge>;
}

type TabKey = "profiles" | "dishes" | "menus" | "rosters" | "executions" | "statistics";

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: "profiles", label: "饮食档案" },
  { key: "dishes", label: "菜品库" },
  { key: "menus", label: "周菜谱" },
  { key: "rosters", label: "配餐名单" },
  { key: "executions", label: "就餐登记" },
  { key: "statistics", label: "就餐统计" },
];

export default function DiningPage() {
  const [tab, setTab] = useState<TabKey>("profiles");

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">膳食营养</h2>
        <p className="mt-1 text-sm text-fg-muted">长者餐食管理：饮食档案 → 菜品菜谱 → 配餐 → 就餐执行 → 统计</p>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-border pb-3">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            onClick={() => setTab(item.key)}
            className={`rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              tab === item.key
                ? "bg-accent/10 text-accent"
                : "text-fg-muted hover:bg-surface-alt hover:text-fg"
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {tab === "profiles" && <DietProfilesTab />}
      {tab === "dishes" && <DishesTab />}
      {tab === "menus" && <WeeklyMenusTab />}
      {tab === "rosters" && <RostersTab />}
      {tab === "executions" && <ExecutionsTab />}
      {tab === "statistics" && <StatisticsTab />}
    </div>
  );
}

// ========================================================================
//  长者饮食档案（FR-1）
// ========================================================================

interface AdmissionOption {
  encounter_id: string;
  patient_id: string;
  patient_name: string;
  encounter_no: string;
}

function DietProfilesTab() {
  const [records, setRecords] = useState<DietProfile[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [offset, setOffset] = useState(0);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<DietProfile | null>(null);
  const [admissions, setAdmissions] = useState<AdmissionOption[]>([]);
  const [form, setForm] = useState({
    encounter_id: "",
    meal_type: "普食",
    allergies: "",
    portion_preference: "标准",
    remark: "",
  });
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listDietProfiles({
        status: statusFilter || undefined,
        limit: PAGE_SIZE,
        offset,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载饮食档案"));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, offset]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadAdmissions = useCallback(async () => {
    try {
      const [encounters, patientList] = await Promise.all([
        listActiveElderlyAdmissions({ limit: 100 }),
        listPatients({ status: "ACTIVE", limit: 200 }),
      ]);
      const patientById = new Map(patientList.records.map((p) => [p.id, p.name]));
      const existing = new Set(records.map((r) => r.patient_id));
      const options: AdmissionOption[] = encounters.records
        .filter((enc) => !existing.has(enc.patient_id))
        .map((enc) => ({
          encounter_id: enc.id,
          patient_id: enc.patient_id,
          patient_name: patientById.get(enc.patient_id) ?? enc.patient_id,
          encounter_no: enc.encounter_no,
        }));
      setAdmissions(options);
    } catch {
      setAdmissions([]);
    }
  }, [records]);

  function openCreate() {
    setEditTarget(null);
    setForm({ encounter_id: "", meal_type: "普食", allergies: "", portion_preference: "标准", remark: "" });
    setFormError("");
    void loadAdmissions();
    setEditorOpen(true);
  }

  function openEdit(profile: DietProfile) {
    setEditTarget(profile);
    setForm({
      encounter_id: profile.encounter_id,
      meal_type: profile.meal_type,
      allergies: (profile.allergies ?? []).join("，"),
      portion_preference: profile.portion_preference ?? "标准",
      remark: profile.remark ?? "",
    });
    setFormError("");
    setEditorOpen(true);
  }

  async function handleSave() {
    if (editTarget) {
      const allergies = form.allergies.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
      setSaving(true);
      setFormError("");
      try {
        await updateDietProfile(editTarget.id, {
          meal_type: form.meal_type,
          allergies,
          portion_preference: form.portion_preference,
          remark: form.remark.trim() || undefined,
        });
        setEditorOpen(false);
        await load();
      } catch (error) {
        setFormError(errorMessage(error, "无法更新饮食档案"));
      } finally {
        setSaving(false);
      }
      return;
    }

    const option = admissions.find((a) => a.encounter_id === form.encounter_id);
    if (!option) {
      setFormError("请选择在院长者");
      return;
    }
    const allergies = form.allergies.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
    setSaving(true);
    setFormError("");
    try {
      await createDietProfile({
        patient_id: option.patient_id,
        encounter_id: option.encounter_id,
        meal_type: form.meal_type,
        allergies,
        portion_preference: form.portion_preference,
        remark: form.remark.trim() || undefined,
      });
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, "无法保存饮食档案"));
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleStatus(profile: DietProfile) {
    try {
      await updateDietProfileStatus(profile.id, profile.status === "启用" ? "停用" : "启用");
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法更新档案状态"));
    }
  }

  async function handleDelete(profile: DietProfile) {
    if (!window.confirm(`确认删除「${profile.patient_name ?? profile.patient_id}」的饮食档案？`)) return;
    try {
      await deleteDietProfile(profile.id);
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法删除饮食档案"));
    }
  }

  const columns: Column<DietProfile>[] = [
    { key: "patient_name", header: "长者", className: "min-w-[130px]", render: (row) => row.patient_name ?? row.patient_id },
    { key: "meal_type", header: "餐食类型", className: "min-w-[110px]", render: (row) => <Badge variant="info">{row.meal_type}</Badge> },
    {
      key: "allergies",
      header: "忌口/过敏",
      className: "min-w-[150px]",
      render: (row) =>
        row.allergies.length > 0 ? row.allergies.map((a) => <Badge key={a} variant="danger" className="mr-1">{a}</Badge>) : <span className="text-fg-dimmed">无</span>,
    },
    { key: "portion_preference", header: "份量", className: "w-[90px]", render: (row) => row.portion_preference ?? "-" },
    {
      key: "status",
      header: "状态",
      className: "w-[110px]",
      render: (row) => (
        <div className="flex items-center gap-1.5">
          {statusBadge(row.status)}
          {row.encounter_status !== "ACTIVE" && <Badge variant="default">已离院</Badge>}
        </div>
      ),
    },
    { key: "remark", header: "备注", className: "min-w-[140px]", render: (row) => row.remark ?? "-" },
    {
      key: "actions",
      header: "操作",
      className: "w-[190px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => openEdit(row)}>编辑</Button>
          <Button variant="link" size="sm" onClick={() => void handleToggleStatus(row)}>
            {row.status === "启用" ? "停用" : "启用"}
          </Button>
          <Button variant="link" size="sm" onClick={() => void handleDelete(row)}>删除</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card
        title="长者饮食档案"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value); setOffset(0); }}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部状态</option>
              <option value="启用">启用</option>
              <option value="停用">停用</option>
            </select>
            <Button variant="primary" onClick={openCreate}>新建档案</Button>
          </div>
        }
      >
        {pageError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{pageError}</div>}
        <Table
          columns={columns}
          data={records}
          loading={loading}
          emptyMessage="暂无饮食档案，点击右上角为在院长者建档"
        />
        {total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
            <span className="text-xs text-fg-dimmed">共 {total} 条</span>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((v) => Math.max(0, v - PAGE_SIZE))}>上一页</Button>
              <Button size="sm" variant="secondary" disabled={offset + PAGE_SIZE >= total} onClick={() => setOffset((v) => v + PAGE_SIZE)}>下一页</Button>
            </div>
          </div>
        )}
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑饮食档案" : "新建饮食档案"}>
        <form
          className="space-y-5"
          onSubmit={(event) => { event.preventDefault(); void handleSave(); }}
        >
          {formError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}

          <div>
            <h4 className="text-sm font-semibold text-fg-emphasis">基本信息</h4>
            <div className="mt-3 space-y-4">
              {!editTarget && (
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="profile-elder">在院长者</label>
                  <select
                    id="profile-elder"
                    value={form.encounter_id}
                    onChange={(event) => setForm((c) => ({ ...c, encounter_id: event.target.value }))}
                    className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  >
                    <option value="">请选择（入住自动生效）</option>
                    {admissions.map((a) => (
                      <option key={a.encounter_id} value={a.encounter_id}>
                        {a.patient_name}（{a.encounter_no}）
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="profile-meal-type">餐食类型</label>
                <select
                  id="profile-meal-type"
                  value={form.meal_type}
                  onChange={(event) => setForm((c) => ({ ...c, meal_type: event.target.value }))}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  {MEAL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="profile-portion">份量偏好</label>
                <select
                  id="profile-portion"
                  value={form.portion_preference}
                  onChange={(event) => setForm((c) => ({ ...c, portion_preference: event.target.value }))}
                  className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  {PORTION_PREFERENCES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <Input
                label="忌口/过敏"
                value={form.allergies}
                onChange={(event) => setForm((c) => ({ ...c, allergies: event.target.value }))}
                placeholder="多个忌口用逗号分隔，如：海鲜，香菜"
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="profile-remark">备注</label>
                <textarea
                  id="profile-remark"
                  value={form.remark}
                  onChange={(event) => setForm((c) => ({ ...c, remark: event.target.value }))}
                  rows={2}
                  className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  placeholder="如：避免辛辣、进食需陪同等"
                />
              </div>
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving}>{editTarget ? "更新档案" : "保存档案"}</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}

// ========================================================================
//  菜品库（FR-2）
// ========================================================================

function DishesTab() {
  const [records, setRecords] = useState<Dish[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [offset, setOffset] = useState(0);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Dish | null>(null);
  const [form, setForm] = useState({ name: "", category: "荤菜", meal_times: ["午餐"] as string[], diet_tags: [] as string[], remark: "" });
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listDishes({
        category: categoryFilter || undefined,
        status: statusFilter || undefined,
        keyword: search || undefined,
        limit: PAGE_SIZE,
        offset,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载菜品库"));
    } finally {
      setLoading(false);
    }
  }, [categoryFilter, statusFilter, search, offset]);

  useEffect(() => { void load(); }, [load]);

  function openCreate() {
    setEditTarget(null);
    setForm({ name: "", category: "荤菜", meal_times: ["午餐"], diet_tags: [], remark: "" });
    setFormError("");
    setEditorOpen(true);
  }

  function openEdit(dish: Dish) {
    setEditTarget(dish);
    setForm({
      name: dish.name,
      category: dish.category,
      meal_times: dish.meal_times,
      diet_tags: dish.diet_tags,
      remark: dish.remark ?? "",
    });
    setFormError("");
    setEditorOpen(true);
  }

  function toggleArray(value: string, array: string[], set: (next: string[]) => void) {
    set(array.includes(value) ? array.filter((v) => v !== value) : [...array, value]);
  }

  async function handleSave() {
    const name = form.name.trim();
    if (!name) { setFormError("菜品名称不能为空"); return; }
    setSaving(true);
    setFormError("");
    try {
      if (editTarget) {
        await updateDish(editTarget.id, {
          name,
          category: form.category,
          meal_times: form.meal_times,
          diet_tags: form.diet_tags,
          remark: form.remark.trim() || undefined,
        });
      } else {
        await createDish({
          name,
          category: form.category,
          meal_times: form.meal_times,
          diet_tags: form.diet_tags,
          remark: form.remark.trim() || undefined,
        });
      }
      setEditorOpen(false);
      await load();
    } catch (error) {
      setFormError(errorMessage(error, editTarget ? "无法更新菜品" : "无法保存菜品"));
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleStatus(dish: Dish) {
    try {
      await updateDishStatus(dish.id, dish.status === "启用" ? "停用" : "启用");
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法更新菜品状态"));
    }
  }

  const columns: Column<Dish>[] = [
    { key: "name", header: "菜品名称", className: "min-w-[140px]" },
    { key: "category", header: "分类", className: "w-[90px]", render: (row) => <Badge>{row.category}</Badge> },
    { key: "meal_times", header: "适用餐次", className: "min-w-[160px]", render: (row) => row.meal_times.map((t) => <Badge key={t} variant="info" className="mr-1">{t}</Badge>) },
    { key: "diet_tags", header: "饮食标签", className: "min-w-[140px]", render: (row) => row.diet_tags.length > 0 ? row.diet_tags.map((t) => <Badge key={t} className="mr-1">{t}</Badge>) : <span className="text-fg-dimmed">无</span> },
    { key: "status", header: "状态", className: "w-[90px]", render: (row) => statusBadge(row.status) },
    { key: "remark", header: "备注", className: "min-w-[120px]", render: (row) => row.remark ?? "-" },
    {
      key: "actions",
      header: "操作",
      className: "w-[130px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => openEdit(row)}>编辑</Button>
          <Button variant="link" size="sm" onClick={() => void handleToggleStatus(row)}>
            {row.status === "启用" ? "停用" : "启用"}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card
        title="菜品库"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={categoryFilter}
              onChange={(event) => { setCategoryFilter(event.target.value); setOffset(0); }}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部分类</option>
              {DISH_CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value); setOffset(0); }}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部状态</option>
              <option value="启用">启用</option>
              <option value="停用">停用</option>
            </select>
            <Input
              placeholder="搜索菜名"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") { setSearch(searchInput.trim()); setOffset(0); } }}
              className="w-44"
            />
            <Button variant="secondary" onClick={() => { setSearch(searchInput.trim()); setOffset(0); }}>查询</Button>
            <Button variant="primary" onClick={openCreate}>新增菜品</Button>
          </div>
        }
      >
        {pageError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{pageError}</div>}
        <Table columns={columns} data={records} loading={loading} emptyMessage="暂无菜品，点击右上角新增" />
        {total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
            <span className="text-xs text-fg-dimmed">共 {total} 条</span>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((v) => Math.max(0, v - PAGE_SIZE))}>上一页</Button>
              <Button size="sm" variant="secondary" disabled={offset + PAGE_SIZE >= total} onClick={() => setOffset((v) => v + PAGE_SIZE)}>下一页</Button>
            </div>
          </div>
        )}
      </Card>

      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title={editTarget ? "编辑菜品" : "新增菜品"} width="36rem">
        <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); void handleSave(); }}>
          {formError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{formError}</div>}
          <Input label="菜品名称" value={form.name} onChange={(event) => setForm((c) => ({ ...c, name: event.target.value }))} placeholder="请输入菜品名称" required />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="dish-category">分类</label>
            <select
              id="dish-category"
              value={form.category}
              onChange={(event) => setForm((c) => ({ ...c, category: event.target.value }))}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {DISH_CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <span className="text-sm font-medium text-fg-muted">适用餐次</span>
            <div className="mt-2 flex flex-wrap gap-2">
              {MEAL_TIMES.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => toggleArray(t, form.meal_times, (next) => setForm((c) => ({ ...c, meal_times: next })))}
                  className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                    form.meal_times.includes(t)
                      ? "border-accent bg-accent/10 text-accent"
                      : "border-border text-fg-muted hover:bg-surface-alt"
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
          <div>
            <span className="text-sm font-medium text-fg-muted">饮食标签</span>
            <div className="mt-2 flex flex-wrap gap-2">
              {DIET_TAGS.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => toggleArray(t, form.diet_tags, (next) => setForm((c) => ({ ...c, diet_tags: next })))}
                  className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                    form.diet_tags.includes(t)
                      ? "border-accent bg-accent/10 text-accent"
                      : "border-border text-fg-muted hover:bg-surface-alt"
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="dish-remark">备注</label>
            <textarea
              id="dish-remark"
              value={form.remark}
              onChange={(event) => setForm((c) => ({ ...c, remark: event.target.value }))}
              rows={2}
              className="resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            />
          </div>
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving}>{editTarget ? "更新菜品" : "保存菜品"}</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}

// ========================================================================
//  周菜谱（FR-3）
// ========================================================================

function WeeklyMenusTab() {
  const [records, setRecords] = useState<WeeklyMenu[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [offset, setOffset] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [createWeek, setCreateWeek] = useState(today());
  const [createName, setCreateName] = useState("");
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  const [editor, setEditor] = useState<{ menu: WeeklyMenu; items: Array<{ day_of_week: number; meal_time: string; dish_id: string }>; dishes: Dish[] } | null>(null);
  const [editError, setEditError] = useState("");
  const [saving, setSaving] = useState(false);

  const [copyMenu, setCopyMenu] = useState<WeeklyMenu | null>(null);
  const [copyWeek, setCopyWeek] = useState("");
  const [copyError, setCopyError] = useState("");
  const [copying, setCopying] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listWeeklyMenus({ status: statusFilter || undefined, limit: PAGE_SIZE, offset });
      setRecords(response.records);
      setTotal(response.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载周菜谱"));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, offset]);

  useEffect(() => { void load(); }, [load]);

  async function handleCreate() {
    if (!createWeek) { setCreateError("请选择周起始日期"); return; }
    setCreating(true);
    setCreateError("");
    try {
      await createWeeklyMenu({ week_start: createWeek, name: createName.trim() || undefined });
      setCreateOpen(false);
      setCreateName("");
      await load();
    } catch (error) {
      setCreateError(errorMessage(error, "无法创建周菜谱"));
    } finally {
      setCreating(false);
    }
  }

  async function openEditor(menu: WeeklyMenu) {
    setEditError("");
    try {
      const [detail, dishes] = await Promise.all([
        getWeeklyMenu(menu.id),
        listDishes({ status: "启用", limit: 200 }),
      ]);
      setEditor({
        menu: detail,
        items: (detail.items ?? []).map((item) => ({ day_of_week: item.day_of_week, meal_time: item.meal_time, dish_id: item.dish_id })),
        dishes: dishes.records,
      });
    } catch (error) {
      setPageError(errorMessage(error, "无法加载菜谱明细"));
    }
  }

  async function handleSaveItems() {
    if (!editor) return;
    if (editor.items.length === 0) { setEditError("请至少添加一个菜品"); return; }
    setSaving(true);
    setEditError("");
    try {
      await replaceWeeklyMenuItems(editor.menu.id, editor.items.map((item, index) => ({
        day_of_week: item.day_of_week,
        meal_time: item.meal_time,
        dish_id: item.dish_id,
        sort_order: index,
      })));
      setEditor(null);
      await load();
    } catch (error) {
      setEditError(errorMessage(error, "无法保存菜谱明细"));
    } finally {
      setSaving(false);
    }
  }

  function addCellItem(day: number, meal: string) {
    setEditor((current) => {
      if (!current) return current;
      const dish = current.dishes[0];
      if (!dish) return current;
      return { ...current, items: [...current.items, { day_of_week: day, meal_time: meal, dish_id: dish.id }] };
    });
  }

  function removeCellItem(day: number, meal: string, index: number) {
    setEditor((current) => {
      if (!current) return current;
      const filtered = current.items.filter((_, i) => !(i === index && current.items[i].day_of_week === day && current.items[i].meal_time === meal));
      return { ...current, items: filtered };
    });
  }

  async function handleToggleStatus(menu: WeeklyMenu) {
    try {
      await updateWeeklyMenuStatus(menu.id, menu.status === "启用" ? "停用" : "启用");
      await load();
    } catch (error) {
      setPageError(errorMessage(error, "无法更新菜谱状态"));
    }
  }

  async function handleCopy() {
    if (!copyMenu || !copyWeek) return;
    setCopying(true);
    setCopyError("");
    try {
      await copyWeeklyMenu(copyMenu.id, copyWeek);
      setCopyMenu(null);
      await load();
    } catch (error) {
      setCopyError(errorMessage(error, "无法复制菜谱"));
    } finally {
      setCopying(false);
    }
  }

  const columns: Column<WeeklyMenu>[] = [
    { key: "week_start", header: "起始日期（周一）", className: "min-w-[130px]", render: (row) => formatDate(row.week_start) },
    { key: "name", header: "菜谱名称", className: "min-w-[140px]", render: (row) => row.name ?? "-" },
    { key: "item_count", header: "菜品项", className: "w-[90px]", render: (row) => (row.items?.length ?? "-") },
    { key: "status", header: "状态", className: "w-[90px]", render: (row) => statusBadge(row.status) },
    {
      key: "actions",
      header: "操作",
      className: "w-[230px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => void openEditor(row)}>编排明细</Button>
          <Button variant="link" size="sm" onClick={() => { setCopyMenu(row); setCopyWeek(weekStartOf(addDays(row.week_start, 7))); setCopyError(""); }}>复制</Button>
          <Button variant="link" size="sm" onClick={() => void handleToggleStatus(row)}>
            {row.status === "启用" ? "停用" : "启用"}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card
        title="周菜谱"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value); setOffset(0); }}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部状态</option>
              <option value="启用">启用</option>
              <option value="停用">停用</option>
            </select>
            <Button variant="primary" onClick={() => { setCreateWeek(today()); setCreateName(""); setCreateError(""); setCreateOpen(true); }}>新建周菜谱</Button>
          </div>
        }
      >
        {pageError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{pageError}</div>}
        <Table columns={columns} data={records} loading={loading} emptyMessage="暂无周菜谱，点击右上角新建" />
        {total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
            <span className="text-xs text-fg-dimmed">共 {total} 条</span>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((v) => Math.max(0, v - PAGE_SIZE))}>上一页</Button>
              <Button size="sm" variant="secondary" disabled={offset + PAGE_SIZE >= total} onClick={() => setOffset((v) => v + PAGE_SIZE)}>下一页</Button>
            </div>
          </div>
        )}
      </Card>

      <Modal open={createOpen} onClose={() => !creating && setCreateOpen(false)} title="新建周菜谱" width="30rem">
        <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); void handleCreate(); }}>
          {createError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{createError}</div>}
          <Input
            label="周起始日期（任意日期将归入所在周）"
            type="date"
            value={createWeek}
            onChange={(event) => setCreateWeek(event.target.value)}
            required
          />
          {createWeek && <p className="-mt-2 text-xs text-fg-dimmed">生效周：{weekStartOf(createWeek)} 起</p>}
          <Input label="菜谱名称" value={createName} onChange={(event) => setCreateName(event.target.value)} placeholder="如：第32周常规菜谱" />
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setCreateOpen(false)} disabled={creating}>取消</Button>
            <Button type="submit" loading={creating}>创建</Button>
          </div>
        </form>
      </Modal>

      <Modal open={editor !== null} onClose={() => !saving && setEditor(null)} title={`编排菜谱（${editor?.menu.name ?? ""}，${editor ? formatDate(editor.menu.week_start) : ""} 起）`} width="64rem">
        {editor && (
          <div className="space-y-5">
            {editError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{editError}</div>}
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed w-[70px]">星期</th>
                    {MEAL_TIMES.map((meal) => (
                      <th key={meal} className="py-2 px-2 text-left text-xs font-semibold text-fg-dimmed">{meal}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {WEEKDAYS.map((dayName, dayIndex) => (
                    <tr key={dayName} className="border-b border-border/50 align-top">
                      <td className="py-2 pr-3 text-fg">{dayName}</td>
                      {MEAL_TIMES.map((meal) => {
                        const cellItems = editor.items
                          .map((item, index) => ({ item, index }))
                          .filter(({ item }) => item.day_of_week === dayIndex + 1 && item.meal_time === meal);
                        return (
                          <td key={meal} className="py-2 px-1">
                            <div className="space-y-1.5">
                              {cellItems.map(({ item, index }) => (
                                <div key={`${index}-${item.dish_id}`} className="flex items-center gap-1">
                                  <select
                                    value={item.dish_id}
                                    onChange={(event) => {
                                      setEditor((current) => {
                                        if (!current) return current;
                                        const items = [...current.items];
                                        items[index] = { ...items[index], dish_id: event.target.value };
                                        return { ...current, items };
                                      });
                                    }}
                                    className="h-8 w-full min-w-[120px] rounded-md border border-border bg-surface px-2 text-xs text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                                  >
                                    {editor.dishes.map((dish) => (
                                      <option key={dish.id} value={dish.id}>{dish.category} · {dish.name}</option>
                                    ))}
                                  </select>
                                  <button
                                    type="button"
                                    onClick={() => removeCellItem(dayIndex + 1, meal, index)}
                                    className="shrink-0 text-fg-dimmed hover:text-danger"
                                    title="移除"
                                  >
                                    ✕
                                  </button>
                                </div>
                              ))}
                              <Button variant="ghost" size="sm" onClick={() => addCellItem(dayIndex + 1, meal)}>＋ 添加</Button>
                            </div>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="ghost" onClick={() => setEditor(null)} disabled={saving}>取消</Button>
              <Button type="button" loading={saving} onClick={() => void handleSaveItems()}>保存菜谱</Button>
            </div>
          </div>
        )}
      </Modal>

      <Modal open={copyMenu !== null} onClose={() => !copying && setCopyMenu(null)} title="复制菜谱到新周" width="30rem">
        <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); void handleCopy(); }}>
          {copyError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{copyError}</div>}
          <p className="text-sm text-fg-muted">
            将「{copyMenu?.name ?? "当前菜谱"}」（{copyMenu ? formatDate(copyMenu.week_start) : ""} 起）复制为整周模板。
          </p>
          <Input label="目标周起始日期" type="date" value={copyWeek} onChange={(event) => setCopyWeek(event.target.value)} required />
          {copyWeek && <p className="-mt-2 text-xs text-fg-dimmed">目标生效周：{weekStartOf(copyWeek)} 起</p>}
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setCopyMenu(null)} disabled={copying}>取消</Button>
            <Button type="submit" loading={copying}>复制</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}

// ========================================================================
//  配餐名单（FR-4）
// ========================================================================

function RostersTab() {
  const [records, setRecords] = useState<Roster[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  const [genDate, setGenDate] = useState(today());
  const [genMeal, setGenMeal] = useState("午餐");
  const [genError, setGenError] = useState("");
  const [generating, setGenerating] = useState(false);
  const [genResult, setGenResult] = useState("");

  const [detail, setDetail] = useState<Roster | null>(null);
  const [detailError, setDetailError] = useState("");
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [adjustType, setAdjustType] = useState<"外出" | "请假" | "临时加餐">("临时加餐");
  const [adjustPatient, setAdjustPatient] = useState("");
  const [adjustRemark, setAdjustRemark] = useState("");
  const [adjustCandidates, setAdjustCandidates] = useState<Array<{ patient_id: string; name: string; encounter_no: string }>>([]);
  const [adjustError, setAdjustError] = useState("");
  const [adjusting, setAdjusting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listRosters({ limit: PAGE_SIZE, offset: 0 });
      setRecords(response.records);
      setTotal(response.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载配餐名单"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function handleGenerate() {
    if (!genDate) { setGenError("请选择日期"); return; }
    setGenerating(true);
    setGenError("");
    setGenResult("");
    try {
      const result = await generateRoster({ date: genDate, meal_time: genMeal });
      setGenResult(`生成完成：新增 ${result.created} 人，刷新 ${result.updated} 人，跳过 ${result.skipped} 人，共 ${result.total} 人`);
      await load();
      setDetail(result.roster);
    } catch (error) {
      setGenError(errorMessage(error, "无法生成配餐名单"));
    } finally {
      setGenerating(false);
    }
  }

  async function openDetail(roster: Roster) {
    setDetailError("");
    try {
      const detail = await getRoster(roster.id);
      setDetail(detail);
    } catch (error) {
      setDetailError(errorMessage(error, "无法加载名单详情"));
    }
  }

  async function openAdjust(item: RosterItem | null, type: "外出" | "请假" | "临时加餐") {
    setAdjustType(type);
    setAdjustRemark("");
    setAdjustError("");
    setAdjustCandidates([]);
    if (type === "临时加餐") {
      try {
        const [encounters, patients] = await Promise.all([
          listActiveElderlyAdmissions({ limit: 100 }),
          listPatients({ status: "ACTIVE", limit: 200 }),
        ]);
        const patientById = new Map(patients.records.map((p) => [p.id, p.name]));
        const onRoster = new Set((detail?.items ?? []).map((i) => i.patient_id));
        setAdjustCandidates(
          encounters.records
            .filter((enc) => !onRoster.has(enc.patient_id))
            .map((enc) => ({ patient_id: enc.patient_id, name: patientById.get(enc.patient_id) ?? enc.patient_id, encounter_no: enc.encounter_no })),
        );
      } catch {
        setAdjustCandidates([]);
      }
    } else {
      setAdjustPatient(item?.patient_id ?? "");
    }
    setAdjustOpen(true);
  }

  async function handleAdjust() {
    if (!detail) return;
    if (adjustType === "临时加餐" && !adjustPatient) { setAdjustError("请选择长者"); return; }
    setAdjusting(true);
    setAdjustError("");
    try {
      await addRosterItem(detail.id, {
        patient_id: adjustPatient,
        adjust_type: adjustType,
        remark: adjustRemark.trim() || undefined,
      });
      setAdjustOpen(false);
      setDetail(await getRoster(detail.id));
    } catch (error) {
      setAdjustError(errorMessage(error, "调整失败"));
    } finally {
      setAdjusting(false);
    }
  }

  async function handleRemoveItem(item: RosterItem) {
    if (!detail) return;
    if (!window.confirm(`确认从名单中移除「${item.patient_name}」？已登记就餐的条目不可删除。`)) return;
    try {
      await removeRosterItem(detail.id, item.id);
      setDetail(await getRoster(detail.id));
    } catch (error) {
      setDetailError(errorMessage(error, "无法移除条目"));
    }
  }

  const rosterColumns: Column<Roster>[] = [
    { key: "menu_date", header: "日期", className: "min-w-[110px]", render: (row) => formatDate(row.menu_date) },
    { key: "meal_time", header: "餐次", className: "w-[90px]", render: (row) => <Badge variant="info">{row.meal_time}</Badge> },
    { key: "item_count", header: "名单人数", className: "w-[100px]", render: (row) => (row.items?.length ?? "-") },
    { key: "generated_by", header: "生成人", className: "w-[110px]", render: (row) => row.generated_by ?? "-" },
    { key: "generated_at", header: "生成时间", className: "min-w-[140px]", render: (row) => formatDateTime(row.generated_at) },
    { key: "actions", header: "操作", className: "w-[90px]", render: (row) => <Button variant="link" size="sm" onClick={() => void openDetail(row)}>查看名单</Button> },
  ];

  const itemColumns: Column<RosterItem>[] = [
    { key: "patient_name", header: "长者", className: "min-w-[110px]" },
    { key: "meal_type", header: "餐食类型", className: "w-[110px]", render: (row) => <Badge variant="info">{row.meal_type}</Badge> },
    {
      key: "allergies",
      header: "忌口/过敏",
      className: "min-w-[130px]",
      render: (row) =>
        row.allergies.length > 0 ? row.allergies.map((a) => <Badge key={a} variant="danger" className="mr-1">{a}</Badge>) : <span className="text-fg-dimmed">无</span>,
    },
    {
      key: "adjust_type",
      header: "调整",
      className: "w-[100px]",
      render: (row) =>
        row.adjust_type ? <Badge variant="warning">{row.adjust_type}</Badge> : (row.source === "手工" ? <Badge>手工</Badge> : <span className="text-fg-dimmed">自动</span>),
    },
    { key: "execution", header: "就餐状态", className: "w-[100px]", render: (row) => mealStatusBadge(row.execution?.status) },
    { key: "remark", header: "备注", className: "min-w-[120px]", render: (row) => row.remark ?? "-" },
    {
      key: "actions",
      header: "操作",
      className: "w-[220px]",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => void openAdjust(row, "外出")}>外出</Button>
          <Button variant="link" size="sm" onClick={() => void openAdjust(row, "请假")}>请假</Button>
          {row.source === "手工" && (
            <Button variant="link" size="sm" onClick={() => void handleRemoveItem(row)}>移除</Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card title="生成配餐名单">
        <div className="flex flex-wrap items-end gap-3">
          <Input label="日期" type="date" value={genDate} onChange={(event) => setGenDate(event.target.value)} className="w-44" />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="roster-meal">餐次</label>
            <select
              id="roster-meal"
              value={genMeal}
              onChange={(event) => setGenMeal(event.target.value)}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {MEAL_TIMES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <Button loading={generating} onClick={() => void handleGenerate()}>生成/刷新名单</Button>
          {genError && <span className="text-sm text-danger">{genError}</span>}
          {genResult && <span className="text-sm text-success">{genResult}</span>}
        </div>
        <p className="mt-3 text-xs text-fg-dimmed">
          按「日期 + 餐次」依据启用饮食档案自动生成当日就餐长者名单；重复生成幂等，手工调整不受影响。
        </p>
      </Card>

      <Card title="配餐名单列表" actions={<span className="text-sm text-fg-dimmed">共 {total} 条</span>}>
        {pageError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{pageError}</div>}
        <Table columns={rosterColumns} data={records} loading={loading} emptyMessage="暂无配餐名单，请先在上方生成" />
      </Card>

      <Modal open={detail !== null} onClose={() => setDetail(null)} title={detail ? `${formatDate(detail.menu_date)} ${detail.meal_time} · 配餐名单（${detail.items?.length ?? 0} 人）` : ""} width="60rem">
        {detail && (
          <div className="space-y-4">
            {detailError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{detailError}</div>}
            <div className="flex flex-wrap gap-2">
              <Button size="sm" onClick={() => void openAdjust(null, "临时加餐")}>＋ 临时加餐</Button>
              <span className="text-xs text-fg-dimmed self-center">外出/请假标记本餐不就餐；手工条目可移除，已登记就餐的条目不可移除</span>
            </div>
            <Table columns={itemColumns} data={detail.items ?? []} emptyMessage="名单为空" />
          </div>
        )}
      </Modal>

      <Modal open={adjustOpen} onClose={() => !adjusting && setAdjustOpen(false)} title={adjustType === "临时加餐" ? "临时加餐" : `标记「${adjustType}」`} width="30rem">
        <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); void handleAdjust(); }}>
          {adjustError && <div className="rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{adjustError}</div>}
          {adjustType === "临时加餐" ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="adjust-patient">长者（在院长者）</label>
              <select
                id="adjust-patient"
                value={adjustPatient}
                onChange={(event) => setAdjustPatient(event.target.value)}
                className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <option value="">请选择</option>
                {adjustCandidates.map((c) => (
                  <option key={c.patient_id} value={c.patient_id}>{c.name}（{c.encounter_no}）</option>
                ))}
              </select>
            </div>
          ) : (
            <p className="text-sm text-fg-muted">将在名单中标记该长者本餐「{adjustType}」，不计入应就餐人次统计。</p>
          )}
          <Input label="备注" value={adjustRemark} onChange={(event) => setAdjustRemark(event.target.value)} placeholder="可选，如：外出就医、家属接走" />
          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setAdjustOpen(false)} disabled={adjusting}>取消</Button>
            <Button type="submit" loading={adjusting}>确认</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}

// ========================================================================
//  就餐执行登记（FR-5）
// ========================================================================

function ExecutionsTab() {
  const [date, setDate] = useState(today());
  const [meal, setMeal] = useState("午餐");
  const [roster, setRoster] = useState<Roster | null>(null);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");
  const [drafts, setDrafts] = useState<Record<string, { status: string; remark: string }>>({});
  const [savingId, setSavingId] = useState<string | null>(null);
  const [saveError, setSaveError] = useState("");

  const loadRoster = useCallback(async () => {
    setLoading(true);
    setPageError("");
    setRoster(null);
    setSaveError("");
    try {
      const list = await listRosters({ date, meal_time: meal, limit: 1 });
      if (list.records.length === 0) {
        setLoading(false);
        return;
      }
      const detail = await getRoster(list.records[0].id);
      setRoster(detail);
      const next: Record<string, { status: string; remark: string }> = {};
      for (const item of detail.items ?? []) {
        next[item.id] = { status: item.execution?.status ?? "正常", remark: item.execution?.remark ?? "" };
      }
      setDrafts(next);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载名单"));
    } finally {
      setLoading(false);
    }
  }, [date, meal]);

  useEffect(() => { void loadRoster(); }, [loadRoster]);

  async function handleSave(item: RosterItem) {
    const draft = drafts[item.id];
    if (!draft) return;
    setSavingId(item.id);
    setSaveError("");
    try {
      const result = await registerMealExecution({
        roster_item_id: item.id,
        status: draft.status,
        remark: draft.remark.trim() || undefined,
      });
      setRoster((current) => {
        if (!current) return current;
        return {
          ...current,
          items: (current.items ?? []).map((i) =>
            i.id === item.id ? { ...i, execution: { ...i.execution, ...result } } : i,
          ),
        };
      });
    } catch (error) {
      setSaveError(errorMessage(error, "登记失败"));
    } finally {
      setSavingId(null);
    }
  }

  const columns: Column<RosterItem>[] = [
    { key: "patient_name", header: "长者", className: "min-w-[110px]" },
    { key: "meal_type", header: "餐食类型", className: "w-[110px]", render: (row) => <Badge variant="info">{row.meal_type}</Badge> },
    {
      key: "allergies",
      header: "忌口/过敏",
      className: "min-w-[120px]",
      render: (row) =>
        row.allergies.length > 0 ? row.allergies.map((a) => <Badge key={a} variant="danger" className="mr-1">{a}</Badge>) : <span className="text-fg-dimmed">无</span>,
    },
    {
      key: "adjust",
      header: "调整",
      className: "w-[90px]",
      render: (row) => (row.adjust_type ? <Badge variant="warning">{row.adjust_type}</Badge> : <span className="text-fg-dimmed">-</span>),
    },
    {
      key: "status_edit",
      header: "就餐状态",
      className: "min-w-[130px]",
      render: (row) => (
        <select
          value={drafts[row.id]?.status ?? "正常"}
          onChange={(event) => setDrafts((c) => ({ ...c, [row.id]: { ...c[row.id], status: event.target.value } }))}
          className="h-9 rounded-md border border-border bg-surface px-2 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          {MEAL_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      ),
    },
    {
      key: "remark_edit",
      header: "备注",
      className: "min-w-[150px]",
      render: (row) => (
        <Input
          value={drafts[row.id]?.remark ?? ""}
          onChange={(event) => setDrafts((c) => ({ ...c, [row.id]: { ...c[row.id], remark: event.target.value } }))}
          placeholder="可选"
          className="h-9"
        />
      ),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[110px]",
      render: (row) => (
        <Button size="sm" loading={savingId === row.id} disabled={savingId !== null} onClick={() => void handleSave(row)}>
          登记/更新
        </Button>
      ),
    },
    {
      key: "recorded",
      header: "登记人/时间",
      className: "min-w-[150px]",
      render: (row) =>
        row.execution ? (
          <span className="text-xs text-fg-muted">{row.execution.recorded_by} · {formatDateTime(row.execution.recorded_at)}</span>
        ) : (
          <span className="text-fg-dimmed">未登记</span>
        ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card title="就餐执行登记">
        <div className="flex flex-wrap items-end gap-3">
          <Input label="日期" type="date" value={date} onChange={(event) => setDate(event.target.value)} className="w-44" />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="exec-meal">餐次</label>
            <select
              id="exec-meal"
              value={meal}
              onChange={(event) => setMeal(event.target.value)}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              {MEAL_TIMES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <Button variant="secondary" loading={loading} onClick={() => void loadRoster()}>加载名单</Button>
          {saveError && <span className="text-sm text-danger">{saveError}</span>}
        </div>
        <p className="mt-3 text-xs text-fg-dimmed">同一长者同一餐次重复登记为幂等更新，登记人与时间以最后一次为准。</p>
      </Card>

      <Card title={`${formatDate(date)} ${meal} · 就餐登记`}>
        {pageError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{pageError}</div>}
        {!loading && !roster ? (
          <EmptyState icon="🍱" title="该餐次暂无名单" description="请先在「配餐名单」页生成当日名单，再回来登记就餐情况。" />
        ) : (
          <Table columns={columns} data={roster?.items ?? []} loading={loading} emptyMessage="名单为空" />
        )}
      </Card>
    </div>
  );
}

// ========================================================================
//  就餐统计（FR-6）
// ========================================================================

function StatisticsTab() {
  const [dateFrom, setDateFrom] = useState(today());
  const [dateTo, setDateTo] = useState(today());
  const [meal, setMeal] = useState("");
  const [stats, setStats] = useState<MealStatistics | null>(null);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState("");

  async function handleQuery() {
    if (!dateFrom || !dateTo) { setPageError("请选择日期范围"); return; }
    setLoading(true);
    setPageError("");
    try {
      setStats(await getMealStatistics({ date_from: dateFrom, date_to: dateTo, meal_time: meal || undefined }));
    } catch (error) {
      setPageError(errorMessage(error, "无法获取就餐统计"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void handleQuery(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const summaryCards = stats
    ? [
        { label: "应就餐人次", value: stats.summary.expected_total, hint: `外出/请假 ${stats.summary.not_expected_total} 人不计入` },
        { label: "已登记人次", value: stats.summary.recorded_total, hint: `未登记 ${stats.summary.unrecorded_total} 人次` },
        { label: "实际就餐", value: stats.summary.eaten_total, hint: "正常 + 部分" },
        { label: "就餐率", value: stats.summary.dining_rate === null ? "-" : `${stats.summary.dining_rate}%`, hint: "实际就餐 / 应就餐" },
      ]
    : [];

  const byStatus = stats ? Object.entries(stats.by_status) : [];
  const byStatusLabel: Record<string, string> = { 正常: "正常", 部分: "部分", 未就餐: "未就餐", 拒食: "拒食", 未登记: "未登记" };

  return (
    <div className="space-y-4">
      <Card title="就餐统计查询">
        <div className="flex flex-wrap items-end gap-3">
          <Input label="开始日期" type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} className="w-44" />
          <Input label="结束日期" type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} className="w-44" />
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="stats-meal">餐次</label>
            <select
              id="stats-meal"
              value={meal}
              onChange={(event) => setMeal(event.target.value)}
              className="h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              <option value="">全部餐次</option>
              {MEAL_TIMES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <Button loading={loading} onClick={() => void handleQuery()}>查询统计</Button>
        </div>
      </Card>

      {pageError && <div className="rounded border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      {stats && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {summaryCards.map((card) => (
              <Card key={card.label} bodyClassName="p-5">
                <div className="text-sm text-fg-muted">{card.label}</div>
                <div className="mt-1 text-2xl font-semibold text-fg-emphasis">{card.value}</div>
                <div className="mt-1 text-xs text-fg-dimmed">{card.hint}</div>
              </Card>
            ))}
          </div>

          <Card title="状态分布" bodyClassName="p-5">
            <div className="flex flex-wrap gap-2">
              {byStatus.map(([key, count]) => (
                <span key={key} className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface-alt px-3 py-2 text-sm">
                  <Badge variant={key === "拒食" ? "danger" : key === "未就餐" || key === "未登记" ? "warning" : key === "正常" ? "success" : "info"}>
                    {byStatusLabel[key] ?? key}
                  </Badge>
                  <span className="font-semibold text-fg-emphasis">{count}</span>
                  <span className="text-xs text-fg-dimmed">人次</span>
                </span>
              ))}
            </div>
          </Card>

          <Card title="餐次汇总" bodyClassName="p-5">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">餐次</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">应就餐</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">已登记</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">实际就餐</th>
                  <th className="py-2 text-left text-xs font-semibold text-fg-dimmed">就餐率</th>
                </tr>
              </thead>
              <tbody>
                {stats.by_meal.length === 0 && (
                  <tr><td colSpan={5} className="py-8 text-center text-sm text-fg-dimmed">该日期范围内无就餐记录</td></tr>
                )}
                {stats.by_meal.map((row) => (
                  <tr key={row.meal_time} className="border-b border-border/50">
                    <td className="py-2.5 pr-3">{row.meal_time}</td>
                    <td className="py-2.5 pr-3">{row.expected_total}</td>
                    <td className="py-2.5 pr-3">{row.recorded_total}</td>
                    <td className="py-2.5 pr-3">{row.eaten_total}</td>
                    <td className="py-2.5">{row.dining_rate === null ? "-" : `${row.dining_rate}%`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <Card title="按日汇总" bodyClassName="p-5">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">日期</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">应就餐</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">已登记</th>
                  <th className="py-2 pr-3 text-left text-xs font-semibold text-fg-dimmed">实际就餐</th>
                  <th className="py-2 text-left text-xs font-semibold text-fg-dimmed">就餐率</th>
                </tr>
              </thead>
              <tbody>
                {stats.by_date.length === 0 && (
                  <tr><td colSpan={5} className="py-8 text-center text-sm text-fg-dimmed">该日期范围内无就餐记录</td></tr>
                )}
                {stats.by_date.map((row) => (
                  <tr key={row.menu_date} className="border-b border-border/50">
                    <td className="py-2.5 pr-3">{formatDate(row.menu_date)}</td>
                    <td className="py-2.5 pr-3">{row.expected_total}</td>
                    <td className="py-2.5 pr-3">{row.recorded_total}</td>
                    <td className="py-2.5 pr-3">{row.eaten_total}</td>
                    <td className="py-2.5">{row.dining_rate === null ? "-" : `${row.dining_rate}%`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </div>
  );
}
