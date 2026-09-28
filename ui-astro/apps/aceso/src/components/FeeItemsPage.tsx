import { useCallback, useEffect, useState } from "react";
import {
  createFeeItem,
  deleteFeeItem,
  listFeeItems,
  updateFeeItem,
  updateFeeItemStatus,
  type FeeItem,
  type FeeItemCategory,
  type FeeItemInput,
  type FeeItemStatus,
} from "@pitchfork/shared/aceso";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";

const PAGE_SIZE = 50;
const NAME_MAX = 100;
const REMARK_MAX = 500;
/** NUMERIC(12,2) 上限：与服务端 FeeItemService.maxUnitPrice 一致 */
const UNIT_PRICE_MAX = 9999999999.99;

/** 分类固定 6 值（业务枚举，不引入英文 code） */
const FEE_ITEM_CATEGORIES: FeeItemCategory[] = [
  "床位费",
  "护理费",
  "伙食费",
  "个性化服务费",
  "押金",
  "其他",
];

/** 参与按月自动计费的分类；其余仅供账单手工加项 */
const AUTO_BILLING_CATEGORIES: FeeItemCategory[] = ["床位费", "护理费", "伙食费"];

/** 护理评估的结果等级（与照护评估界面同值）；护理费名称必须逐字一致 */
const NURSING_RESULT_LEVELS = ["低风险", "中风险", "高风险", "无需干预"];

const selectClass =
  "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatUnitPrice(value: number | string | null | undefined): string {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed.toFixed(2) : "—";
}

function isAutoBillingCategory(category: FeeItemCategory): boolean {
  return AUTO_BILLING_CATEGORIES.includes(category);
}

/** 护理费名称必须取自护理评估结果等级，避免生成账单时匹配不到 */
function isNursingResultLevel(name: string): boolean {
  return NURSING_RESULT_LEVELS.includes(name);
}

interface FeeItemForm {
  category: "" | FeeItemCategory;
  name: string;
  unitPrice: string;
  remark: string;
}

const emptyForm: FeeItemForm = { category: "", name: "", unitPrice: "", remark: "" };

interface ValidatedForm {
  category: FeeItemCategory;
  name: string;
  unit_price: number;
  remark: string | null;
}

/** 单价：正数、至多两位小数、不超过 9999999999.99 */
function parseUnitPrice(raw: string): { ok: true; value: number } | { ok: false; error: string } {
  const text = raw.trim();
  if (!text) return { ok: false, error: "单价不能为空" };
  if (!/^\d+(\.\d+)?$/.test(text)) {
    return { ok: false, error: "单价必须为数字（不支持负号、千分位或科学计数法）" };
  }
  if (!/^\d+(\.\d{1,2})?$/.test(text)) {
    return { ok: false, error: "单价最多保留两位小数" };
  }
  const value = Number(text);
  if (!Number.isFinite(value) || value <= 0) return { ok: false, error: "单价必须为正数" };
  if (value > UNIT_PRICE_MAX) {
    return { ok: false, error: `单价不能超过 ${UNIT_PRICE_MAX.toFixed(2)}` };
  }
  return { ok: true, value };
}

/** 前端校验与服务端字段白名单保持一致：分类 6 值、名称 ≤100、单价 >0 且 ≤2 位小数、备注 ≤500 */
function validateFeeItemForm(
  form: FeeItemForm,
): { ok: true; value: ValidatedForm } | { ok: false; error: string } {
  if (!(FEE_ITEM_CATEGORIES as string[]).includes(form.category)) {
    return { ok: false, error: "请选择费用分类" };
  }
  const name = form.name.trim();
  if (!name) return { ok: false, error: "名称不能为空" };
  if (name.length > NAME_MAX) return { ok: false, error: `名称不能超过 ${NAME_MAX} 个字符` };
  const price = parseUnitPrice(form.unitPrice);
  if (!price.ok) return { ok: false, error: price.error };
  const remark = form.remark.trim();
  if (remark.length > REMARK_MAX) {
    return { ok: false, error: `备注不能超过 ${REMARK_MAX} 个字符` };
  }
  return {
    ok: true,
    value: {
      category: form.category as FeeItemCategory,
      name,
      unit_price: price.value,
      remark: remark || null,
    },
  };
}

/** PUT 全量替换分类/名称/单价/备注；状态不走这里（PATCH /:id/status） */
function toFeeItemInput(value: ValidatedForm): FeeItemInput {
  return {
    category: value.category,
    name: value.name,
    unit_price: value.unit_price,
    ...(value.remark ? { remark: value.remark } : {}),
  };
}

export default function FeeItemsPage() {
  const [items, setItems] = useState<FeeItem[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");

  const [categoryFilter, setCategoryFilter] = useState<"" | FeeItemCategory>("");
  const [statusFilter, setStatusFilter] = useState<"" | FeeItemStatus>("");

  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FeeItemForm>(emptyForm);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  /** 已启用的护理费名称：用于同名重复的即时提示（服务端 400 之外的前端一层） */
  const [nursingEnabled, setNursingEnabled] = useState<FeeItem[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const page = await listFeeItems({
        ...(categoryFilter ? { category: categoryFilter } : {}),
        ...(statusFilter ? { status: statusFilter } : {}),
        limit: PAGE_SIZE,
        offset,
      });
      setItems(page.records);
      setTotal(page.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载费用项目"));
    } finally {
      setLoading(false);
    }
  }, [categoryFilter, statusFilter, offset]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!formOpen || form.category !== "护理费") return;
    let alive = true;
    void listFeeItems({ category: "护理费", status: "启用", limit: 200 })
      .then((page) => {
        if (alive) setNursingEnabled(page.records);
      })
      .catch(() => {
        if (alive) setNursingEnabled([]);
      });
    return () => {
      alive = false;
    };
  }, [formOpen, form.category]);

  function openCreate() {
    setEditingId(null);
    setForm(emptyForm);
    setFormError("");
    setFormOpen(true);
  }

  function openEdit(item: FeeItem) {
    setEditingId(item.id);
    setForm({
      category: item.category,
      name: item.name,
      unitPrice: formatUnitPrice(item.unit_price),
      remark: item.remark ?? "",
    });
    setFormError("");
    setFormOpen(true);
  }

  function closeForm() {
    setFormOpen(false);
    setEditingId(null);
    setForm(emptyForm);
    setFormError("");
  }

  async function handleSubmit() {
    const validated = validateFeeItemForm(form);
    if (!validated.ok) {
      setFormError(validated.error);
      return;
    }
    setSaving(true);
    setFormError("");
    try {
      if (editingId) {
        await updateFeeItem(editingId, toFeeItemInput(validated.value));
      } else {
        await createFeeItem(toFeeItemInput(validated.value));
      }
      closeForm();
      await load();
    } catch (error) {
      setFormError(errorMessage(error, editingId ? "保存失败" : "新增失败"));
    } finally {
      setSaving(false);
    }
  }

  async function toggleStatus(item: FeeItem) {
    const next: FeeItemStatus = item.status === "启用" ? "停用" : "启用";
    setPageError("");
    try {
      await updateFeeItemStatus(item.id, next);
      await load();
    } catch (error) {
      setPageError(errorMessage(error, `${next}失败`));
    }
  }

  async function handleDelete(item: FeeItem) {
    const confirmed = window.confirm(
      `确认删除费用项目「${item.name}」（${item.category}）？\n\n` +
        "已生成账单的明细是快照，删除字典项不影响历史账单；但删除后新账单无法再按该分类自动计费，删除不可恢复。",
    );
    if (!confirmed) return;
    setPageError("");
    try {
      await deleteFeeItem(item.id);
      if (items.length === 1 && offset > 0) setOffset(Math.max(0, offset - PAGE_SIZE));
      else await load();
    } catch (error) {
      const msg = errorMessage(error, "删除失败");
      setPageError(/foreign|constraint/i.test(msg) ? "该费用项目已被引用，无法删除；可改为停用" : msg);
    }
  }

  const duplicateNursingName =
    form.category === "护理费" && form.name.trim()
      ? nursingEnabled.find((row) => row.name === form.name.trim() && row.id !== editingId) ?? null
      : null;

  // ——— 表格 ———
  const columns: Column<FeeItem>[] = [
    {
      key: "category",
      header: "分类",
      className: "min-w-[130px]",
      render: (row) => (
        <span className="text-fg">
          {row.category}
          {!isAutoBillingCategory(row.category) && (
            <span className="ml-1 text-xs text-fg-dimmed">（手工）</span>
          )}
        </span>
      ),
    },
    {
      key: "name",
      header: "名称",
      className: "min-w-[180px]",
      render: (row) => (
        <span className="font-medium text-fg-emphasis">
          {row.name}
          {row.category === "护理费" && !isNursingResultLevel(row.name) && (
            <span className="ml-2 text-xs font-normal text-warning">非评估等级名称</span>
          )}
        </span>
      ),
    },
    {
      key: "unit_price",
      header: "单价（元）",
      className: "min-w-[120px] text-right font-mono text-xs",
      render: (row) => formatUnitPrice(row.unit_price),
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
      render: (row) => formatDateTime(row.updated_at),
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
          {row.status === "启用" ? (
            <Button variant="ghost" size="sm" onClick={() => void toggleStatus(row)}>
              停用
            </Button>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => void toggleStatus(row)}>
              启用
            </Button>
          )}
          <Button variant="ghost" size="sm" className="text-danger" onClick={() => void handleDelete(row)}>
            删除
          </Button>
        </div>
      ),
    },
  ];

  const emptyDictionary = !loading && items.length === 0 && !categoryFilter && !statusFilter;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">费用项目</h2>
          <p className="mt-1 text-sm text-fg-muted">
            维护养老收费的收费项目字典：分类 / 名称 / 单价（元）/ 备注。床位费 / 护理费 / 伙食费
            参与按月自动计费；个性化服务费 / 押金 / 其他 仅用于账单手工加项，不会自动抵扣或自动计费。
          </p>
        </div>
        <Button onClick={openCreate}>新增费用项目</Button>
      </div>

      {pageError && (
        <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
          {pageError}
        </div>
      )}

      <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
        启用 / 停用只影响之后生成的账单：已生成账单的明细是快照（名称、单价已定格），改价、停用或删除字典项都不影响历史账单。
        护理费按名称精确匹配护理评估的「结果等级」，每个等级只能保留一条启用项。
      </p>

      <Card
        title="费用项目字典"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={selectClass}
              aria-label="按分类过滤"
              value={categoryFilter}
              onChange={(event) => {
                setCategoryFilter(event.target.value as "" | FeeItemCategory);
                setOffset(0);
              }}
            >
              <option value="">全部分类</option>
              {FEE_ITEM_CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {category}
                </option>
              ))}
            </select>
            <select
              className={selectClass}
              aria-label="按状态过滤"
              value={statusFilter}
              onChange={(event) => {
                setStatusFilter(event.target.value as "" | FeeItemStatus);
                setOffset(0);
              }}
            >
              <option value="">全部状态</option>
              <option value="启用">启用</option>
              <option value="停用">停用</option>
            </select>
            <span className="text-sm text-fg-dimmed">共 {total} 项</span>
          </div>
        }
        bodyClassName={emptyDictionary ? "p-0" : undefined}
      >
        {emptyDictionary ? (
          <EmptyState
            icon="🧾"
            title="费用项目字典为空"
            action={
              <div className="flex flex-col items-center gap-4">
                <div className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-left text-xs text-fg-muted">
                  <p className="mb-2 font-medium text-fg">按月自动计费至少需要以下启用项：</p>
                  <ul className="list-disc space-y-1 pl-4">
                    <li>床位费 × 1（按在院天数计费）</li>
                    <li>
                      护理费 × 每个评估等级 × 1（低风险 / 中风险 / 高风险 / 无需干预，按等级分段天数计费）
                    </li>
                    <li>伙食费 × 1（按账期内折合餐次计费）</li>
                  </ul>
                  <p className="mt-2">
                    个性化服务费 / 押金 / 其他 只用于账单手工加项，不参与自动计费；押金请在押金管理中登记。
                  </p>
                </div>
                <Button onClick={openCreate}>新增费用项目</Button>
              </div>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <Table columns={columns} data={items} loading={loading} emptyMessage="暂无匹配的费用项目" />
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

      {/* ——— 新增 / 编辑费用项目（同一弹窗复用） ——— */}
      <Modal
        open={formOpen}
        onClose={() => !saving && closeForm()}
        title={editingId ? "编辑费用项目" : "新增费用项目"}
        width="36rem"
      >
        <div className="space-y-4">
          {formError && (
            <div className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="fee-item-category">
                分类
              </label>
              <select
                id="fee-item-category"
                className={selectClass}
                value={form.category}
                onChange={(event) =>
                  setForm((current) => ({
                    ...current,
                    category: event.target.value as "" | FeeItemCategory,
                  }))
                }
              >
                <option value="">选择分类</option>
                {FEE_ITEM_CATEGORIES.map((category) => (
                  <option key={category} value={category}>
                    {category}
                    {isAutoBillingCategory(category) ? "（参与自动计费）" : "（仅手工加项）"}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="名称"
              value={form.name}
              onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
              placeholder={form.category === "护理费" ? "例如 中风险（须与评估结果等级一致）" : "例如 床位费-标准间"}
            />

            {form.category === "护理费" && (
              <div className="rounded-lg border border-warning/30 bg-warning-bg px-4 py-3 text-xs text-warning sm:col-span-2">
                <p>
                  护理费名称必须与护理评估的「结果等级」完全一致，且每个等级只能有一个启用项；否则生成账单会报错或计费错误。
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span>快捷填入评估结果等级：</span>
                  {NURSING_RESULT_LEVELS.map((level) => (
                    <Button
                      key={level}
                      type="button"
                      size="sm"
                      variant="secondary"
                      onClick={() => setForm((current) => ({ ...current, name: level }))}
                    >
                      {level}
                    </Button>
                  ))}
                </div>
              </div>
            )}

            {duplicateNursingName && (
              <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-xs text-danger sm:col-span-2">
                「{duplicateNursingName.name}」已存在一条启用项；同名多条启用项会让生成账单报「多个启用项」。
                请先停用其中一条，或改用其它评估结果等级名称。
              </div>
            )}

            <Input
              label="单价（元）"
              value={form.unitPrice}
              inputMode="decimal"
              onChange={(event) =>
                setForm((current) => ({ ...current, unitPrice: event.target.value }))
              }
              placeholder="例如 120.00"
            />
            <Input
              label={`备注（可选，≤${REMARK_MAX} 字符）`}
              value={form.remark}
              onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))}
              placeholder="例如 含三餐，按月结算"
            />
          </div>

          <p className="rounded-lg border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
            保存会全量替换分类 / 名称 / 单价 / 备注，不会改变启用状态；启用或停用请在列表操作列点击「启用 / 停用」。
            只有 床位费 / 护理费 / 伙食费 参与按月自动计费，其余分类仅作为账单手工加项的来源。
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
