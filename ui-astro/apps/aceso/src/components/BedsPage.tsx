import { useCallback, useEffect, useState } from "react";
import {
  ApiRequestError,
  createBed,
  deleteBed,
  listBeds,
  updateBed,
  updateBedStatus,
  type Bed,
  type BedInput,
  type BedStatus,
} from "@pitchfork/shared/aceso";
import { DOMAIN_ENTITY } from "../lib/domain";
import { formatDateTime } from "../lib/datetime";
import { useDomain } from "../lib/useDomain";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";

const PAGE_SIZE = 50;
/** department/ward 上限：与服务端 BedService.MAX_IDENTITY_LENGTH 一致 */
const IDENTITY_MAX = 50;
/** remark 上限：与服务端 BedService.MAX_REMARK_LENGTH 一致（label 服务端不限长） */
const REMARK_MAX = 500;

const BED_STATUSES: BedStatus[] = ["启用", "停用"];

const selectClass =
  "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 服务端错误码：409（业务键重复 / 被在住占用）、404（不存在）需要映射为中文业务文案 */
function errorStatus(error: unknown): number | null {
  return error instanceof ApiRequestError ? error.status : null;
}

interface BedForm {
  department: string;
  ward: string;
  label: string;
  remark: string;
}

interface ValidatedBedForm {
  department: string;
  ward: string;
  label: string | null;
  remark: string | null;
}

const emptyForm: BedForm = { department: "", ward: "", label: "", remark: "" };

/**
 * 前端校验与服务端字段白名单保持一致：
 * `department`/`ward` trim 后非空且 ≤50 字、`label` 可选、`remark` ≤500 字。
 */
function validateBedForm(
  form: BedForm,
): { ok: true; value: ValidatedBedForm } | { ok: false; error: string } {
  const department = form.department.trim();
  if (!department) return { ok: false, error: "照护单元/病区不能为空" };
  if (department.length > IDENTITY_MAX) {
    return { ok: false, error: `照护单元/病区不能超过 ${IDENTITY_MAX} 个字符` };
  }
  const ward = form.ward.trim();
  if (!ward) return { ok: false, error: "房间床位不能为空" };
  if (ward.length > IDENTITY_MAX) {
    return { ok: false, error: `房间床位不能超过 ${IDENTITY_MAX} 个字符` };
  }
  const remark = form.remark.trim();
  if (remark.length > REMARK_MAX) {
    return { ok: false, error: `备注不能超过 ${REMARK_MAX} 个字符` };
  }
  const label = form.label.trim();
  return {
    ok: true,
    value: { department, ward, label: label || null, remark: remark || null },
  };
}

/** PUT 全量替换 department/ward/label/remark；状态不走这里（PATCH /:id/status） */
function toBedInput(value: ValidatedBedForm): BedInput {
  return {
    department: value.department,
    ward: value.ward,
    ...(value.label ? { label: value.label } : {}),
    ...(value.remark ? { remark: value.remark } : {}),
  };
}

/** 业务键展示：照护单元/病区 + 房间床位 */
function bedIdentity(row: Bed): string {
  return `${row.department} / ${row.ward}`;
}

export default function BedsPage() {
  // 床位页三个域都可见：占用者称呼必须随域切换（居民 / 长者 / 儿童）
  const { person } = DOMAIN_ENTITY[useDomain()];
  const [items, setItems] = useState<Bed[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  // 筛选：输入即时反馈，300ms 防抖后才落到查询条件（服务端为 trim 后精确匹配）
  const [departmentInput, setDepartmentInput] = useState("");
  const [wardInput, setWardInput] = useState("");
  const [departmentFilter, setDepartmentFilter] = useState("");
  const [wardFilter, setWardFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState<"" | BedStatus>("");

  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  /** 被编辑床位当前的状态：停用项不占用业务键，因此不参与「该床位已存在」的前端预判 */
  const [editingStatus, setEditingStatus] = useState<BedStatus | null>(null);
  const [form, setForm] = useState<BedForm>(emptyForm);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  /** 已启用床位：用于编辑时对同一业务键的即时提示（服务端 409 之外的前端一层） */
  const [enabledBeds, setEnabledBeds] = useState<Bed[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const page = await listBeds({
        ...(departmentFilter ? { department: departmentFilter } : {}),
        ...(wardFilter ? { ward: wardFilter } : {}),
        ...(statusFilter ? { status: statusFilter } : {}),
        limit: PAGE_SIZE,
        offset,
      });
      setItems(page.records);
      setTotal(page.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载床位主数据"));
    } finally {
      setLoading(false);
    }
  }, [departmentFilter, wardFilter, statusFilter, offset]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDepartmentFilter(departmentInput.trim());
      setWardFilter(wardInput.trim());
      setOffset(0);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [departmentInput, wardInput]);

  useEffect(() => {
    if (!formOpen) return;
    let alive = true;
    void listBeds({ status: "启用", limit: 200 })
      .then((page) => {
        if (alive) setEnabledBeds(page.records);
      })
      .catch(() => {
        if (alive) setEnabledBeds([]);
      });
    return () => {
      alive = false;
    };
  }, [formOpen]);

  function openCreate() {
    setEditingId(null);
    setEditingStatus(null);
    setForm(emptyForm);
    setFormError("");
    setFormOpen(true);
  }

  function openEdit(row: Bed) {
    setEditingId(row.id);
    setEditingStatus(row.status);
    setForm({
      department: row.department,
      ward: row.ward,
      label: row.label ?? "",
      remark: row.remark ?? "",
    });
    setFormError("");
    setFormOpen(true);
  }

  function closeForm() {
    setFormOpen(false);
    setEditingId(null);
    setEditingStatus(null);
    setForm(emptyForm);
    setFormError("");
  }

  async function handleSubmit() {
    const validated = validateBedForm(form);
    if (!validated.ok) {
      setFormError(validated.error);
      return;
    }
    setSaving(true);
    setFormError("");
    try {
      if (editingId) {
        await updateBed(editingId, toBedInput(validated.value));
      } else {
        await createBed(toBedInput(validated.value));
      }
      closeForm();
      await load();
    } catch (error) {
      const status = errorStatus(error);
      if (status === 409) {
        setFormError("该床位已存在");
      } else if (status === 404) {
        setFormError("该床位不存在或已被删除，请刷新列表后重试");
      } else {
        setFormError(errorMessage(error, editingId ? "保存失败" : "新增失败"));
      }
    } finally {
      setSaving(false);
    }
  }

  async function toggleStatus(row: Bed) {
    const next: BedStatus = row.status === "启用" ? "停用" : "启用";
    setPageError("");
    try {
      await updateBedStatus(row.id, next);
      await load();
    } catch (error) {
      const status = errorStatus(error);
      if (status === 409) {
        setPageError("该床位已存在");
      } else if (status === 404) {
        setPageError("该床位不存在或已被删除，请刷新列表后重试");
      } else {
        setPageError(errorMessage(error, `${next}失败`));
      }
    }
  }

  async function handleDelete(row: Bed) {
    const confirmed = window.confirm(
      `确认删除床位「${bedIdentity(row)}」？\n\n` +
        "删除后该床位不再作为办理入住时的候选；历史入住记录里的自由文本不受影响，删除不可恢复。\n" +
        `若该床位当前有在住${person}，系统会拒绝删除，可改为停用。`,
    );
    if (!confirmed) return;
    setPageError("");
    try {
      await deleteBed(row.id);
      if (items.length === 1 && offset > 0) setOffset(Math.max(0, offset - PAGE_SIZE));
      else await load();
    } catch (error) {
      const status = errorStatus(error);
      if (status === 409) {
        setPageError(`该床位有在住${person}，不能删除`);
      } else if (status === 404) {
        setPageError("该床位不存在或已被删除，请刷新列表后重试");
      } else {
        setPageError(errorMessage(error, "删除失败"));
      }
    }
  }

  const formDepartment = form.department.trim();
  const formWard = form.ward.trim();
  /**
   * 启用态业务键唯一：新建（服务端固定置为启用）与被编辑项当前为启用时才可能 409；
   * 停用项改键不占用业务键，故不做提示，避免与服务端行为不一致的假阳性。
   */
  const duplicateBed =
    formOpen && formDepartment && formWard && (editingId === null || editingStatus === "启用")
      ? enabledBeds.find(
          (row) =>
            row.id !== editingId &&
            row.department.trim() === formDepartment &&
            row.ward.trim() === formWard,
        ) ?? null
      : null;

  // ——— 表格 ———
  const columns: Column<Bed>[] = [
    {
      key: "department",
      header: "照护单元/病区",
      className: "min-w-[160px]",
      render: (row) => <span className="text-fg">{row.department}</span>,
    },
    {
      key: "ward",
      header: "房间床位",
      className: "min-w-[140px]",
      render: (row) => <span className="font-medium text-fg-emphasis">{row.ward}</span>,
    },
    {
      key: "label",
      header: "展示名称",
      className: "min-w-[140px]",
      render: (row) => row.label || "—",
    },
    {
      key: "status",
      header: "状态",
      className: "min-w-[90px]",
      render: (row) =>
        row.status === "启用" ? (
          <Badge variant="success">启用</Badge>
        ) : (
          <Badge variant="default">停用</Badge>
        ),
    },
    {
      key: "remark",
      header: "备注",
      className: "min-w-[160px]",
      render: (row) => row.remark || "—",
    },
    {
      key: "updated_at",
      header: "更新时间",
      className: "min-w-[150px] font-mono text-xs",
      render: (row) => formatDateTime(row.updated_at, "—"),
    },
    {
      key: "actions",
      header: "操作",
      className: "min-w-[190px]",
      render: (row) => (
        <div className="flex gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => openEdit(row)}>
            修改
          </Button>
          <Button variant="ghost" size="sm" onClick={() => void toggleStatus(row)}>
            {row.status === "启用" ? "停用" : "启用"}
          </Button>
          <Button variant="ghost" size="sm" className="text-danger" onClick={() => void handleDelete(row)}>
            删除
          </Button>
        </div>
      ),
    },
  ];

  const hasFilter = Boolean(departmentFilter || wardFilter || statusFilter);
  const emptyDictionary = !loading && items.length === 0 && !hasFilter;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">床位管理</h2>
          <p className="mt-1 text-sm text-fg-muted">
            维护床位的 (照护单元/病区, 房间床位) 主数据，作为办理入住时的候选来源；启用态下同一组合只能有一条。
          </p>
        </div>
        <Button onClick={openCreate}>新增床位</Button>
      </div>

      {pageError && (
        <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
          {pageError}
        </div>
      )}

      <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
        床位主数据只是候选来源，不强制校验：办理入住时仍可直接填写历史自由文本，入住时的同一床位区间冲突校验继续生效。
        删除仅收回候选；被在住{person}占用的床位会拒绝删除，可改为停用。
      </p>

      <Card
        title="床位主数据"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <input
              className={selectClass}
              aria-label="按照护单元/病区筛选"
              placeholder="照护单元/病区（精确）"
              value={departmentInput}
              onChange={(event) => setDepartmentInput(event.target.value)}
            />
            <input
              className={selectClass}
              aria-label="按房间床位筛选"
              placeholder="房间床位（精确）"
              value={wardInput}
              onChange={(event) => setWardInput(event.target.value)}
            />
            <select
              className={selectClass}
              aria-label="按状态过滤"
              value={statusFilter}
              onChange={(event) => {
                setStatusFilter(event.target.value as "" | BedStatus);
                setOffset(0);
              }}
            >
              <option value="">全部状态</option>
              {BED_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
            <span className="text-sm text-fg-dimmed">共 {total} 条</span>
          </div>
        }
        bodyClassName={emptyDictionary ? "p-0" : undefined}
      >
        {emptyDictionary ? (
          <EmptyState
            icon="🛏️"
            title="床位主数据为空"
            action={
              <div className="flex flex-col items-center gap-4">
                <div className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-left text-xs text-fg-muted">
                  <p className="mb-2 font-medium text-fg">录入后办理入住可以下拉选择，避免手写「001 / 01」被当成两张床。</p>
                  <p>不录入也不影响入住：两个字段仍可直接填写自由文本。</p>
                </div>
                <Button onClick={openCreate}>新增床位</Button>
              </div>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <Table columns={columns} data={items} loading={loading} emptyMessage="暂无匹配的床位" />
          </div>
        )}
        {!emptyDictionary && total > PAGE_SIZE && (
          <div className="flex items-center justify-between px-5 py-3 border-t border-border">
            <span className="text-xs text-fg-dimmed">
              共 {total} 条 · 第 {Math.floor(offset / PAGE_SIZE) + 1} 页
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={offset === 0}
                onClick={() => setOffset((value) => Math.max(0, value - PAGE_SIZE))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={offset + PAGE_SIZE >= total}
                onClick={() => setOffset((value) => value + PAGE_SIZE)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* ——— 新增 / 编辑床位（同一弹窗复用） ——— */}
      <Modal
        open={formOpen}
        onClose={() => !saving && closeForm()}
        title={editingId ? "编辑床位" : "新增床位"}
        width="36rem"
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="照护单元/病区"
              value={form.department}
              maxLength={IDENTITY_MAX}
              onChange={(event) =>
                setForm((current) => ({ ...current, department: event.target.value }))
              }
              placeholder="例如 一病区 / 颐养一区"
            />
            <Input
              label="房间床位"
              value={form.ward}
              maxLength={IDENTITY_MAX}
              onChange={(event) => setForm((current) => ({ ...current, ward: event.target.value }))}
              placeholder="例如 101-1 / 01"
            />
            <Input
              label="展示名称（可选）"
              value={form.label}
              onChange={(event) => setForm((current) => ({ ...current, label: event.target.value }))}
              placeholder="例如 一号楼 101 房 1 床"
            />
            <Input
              label={`备注（可选，≤${REMARK_MAX} 字符）`}
              value={form.remark}
              maxLength={REMARK_MAX}
              onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))}
              placeholder="例如 靠近护士站"
            />

            {duplicateBed && (
              <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-xs text-danger sm:col-span-2">
                该床位已存在（{bedIdentity(duplicateBed)}）；同一照护单元与房间床位在启用态下只能保留一条。请修改组合，或先停用已有床位。
              </div>
            )}
          </div>

          <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
            保存会全量替换照护单元/病区、房间床位、展示名称与备注，不会改变启用状态；启用或停用请在列表操作列点击「启用 / 停用」。
            前端与服务端按 trim 后精确比较，`001` 与 `01` 视为不同床位；停用项不占用业务键，但同一组合不能同时有两条启用项。
          </p>

          <div className="flex justify-end gap-3 pt-2">
            <Button variant="ghost" onClick={closeForm} disabled={saving}>
              取消
            </Button>
            <Button onClick={() => void handleSubmit()} loading={saving}>
              {editingId ? "保存" : "创建"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
