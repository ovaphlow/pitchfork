import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  CHILD_CHECKUP_CONCLUSIONS,
  CHILD_CHECKUP_TYPES,
  createChildHealthCheckup,
  listChildHealthCheckups,
  listPatients,
  updateChildHealthCheckup,
  type ChildHealthCheckup,
  type ChildHealthCheckupItem,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import { useDomain } from "../lib/useDomain";

/**
 * 儿童体检页（052 P1）。
 *
 * 按儿童、按月龄的保健体检记录：列表 + 新建 + 修正结论/指导/下次体检日期。
 * 年度批次体检（`/dashboard/checkup`）保持独立，两者不互相替代（052 D1）。
 * 所有请求经 `@pitchfork/shared/aceso`（token 注入 / JSON / 401 处理复用共享客户端）。
 */

const PAGE_SIZE = 20;

interface CheckupForm {
  checkup_date: string;
  checkup_type: string;
  itemsText: string;
  conclusion: string;
  guidance: string;
  next_checkup_date: string;
  remark: string;
}

const defaultForm: CheckupForm = {
  checkup_date: todayLocal(),
  checkup_type: "常规",
  itemsText: "",
  conclusion: "",
  guidance: "",
  next_checkup_date: "",
  remark: "",
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 检查项文本 → 结构化数组：每行 `项目|结果|结论`（后两段可省）。 */
function parseItems(text: string): ChildHealthCheckupItem[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const [item = "", result = "", conclusion = ""] = line.split("|").map((part) => part.trim());
      const entry: ChildHealthCheckupItem = { item };
      if (result) entry.result = result;
      if (conclusion) entry.conclusion = conclusion;
      return entry;
    });
}

/** 结构化数组 → 文本（回填编辑表单）。 */
function itemsToText(items: ChildHealthCheckupItem[] | null): string {
  return (items ?? [])
    .map((entry) => [entry.item ?? "", entry.result ?? "", entry.conclusion ?? ""].join("|").replace(/\|+$/, ""))
    .join("\n");
}

function conclusionBadge(conclusion: string | null) {
  if (conclusion === "正常") return <Badge variant="success">正常</Badge>;
  if (conclusion === "异常") return <Badge variant="warning">异常</Badge>;
  if (conclusion === "需转诊") return <Badge variant="danger">需转诊</Badge>;
  return <Badge>{conclusion ?? "未结论"}</Badge>;
}

export default function ChildCheckupPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [records, setRecords] = useState<ChildHealthCheckup[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<CheckupForm>(defaultForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

  const [editing, setEditing] = useState<ChildHealthCheckup | null>(null);
  const [editForm, setEditForm] = useState<CheckupForm>(defaultForm);

  const selected = useMemo(() => children.find((child) => child.id === patientId) ?? null, [children, patientId]);

  useEffect(() => {
    let active = true;
    listPatients({ person_type: "儿童", status: "ACTIVE", limit: 200 })
      .then((response) => {
        if (!active) return;
        setChildren(response.records);
        const fromUrl = typeof window === "undefined" ? null : new URLSearchParams(window.location.search).get("patient");
        const initial = fromUrl && response.records.some((child) => child.id === fromUrl) ? fromUrl : response.records[0]?.id ?? "";
        setPatientId(initial);
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, "无法加载儿童列表"));
      });
    return () => {
      active = false;
    };
  }, []);

  const load = useCallback(async () => {
    if (!patientId) {
      setRecords([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const response = await listChildHealthCheckups({
        patient_id: patientId,
        limit: PAGE_SIZE,
        offset: (page - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载体检记录"));
    } finally {
      setLoading(false);
    }
  }, [patientId, page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitCreate() {
    if (!patientId) return;
    setSaving(true);
    setFormError("");
    try {
      await createChildHealthCheckup({
        patient_id: patientId,
        checkup_date: form.checkup_date,
        checkup_type: form.checkup_type,
        items: parseItems(form.itemsText),
        conclusion: form.conclusion || null,
        guidance: form.guidance || null,
        next_checkup_date: form.next_checkup_date || null,
        remark: form.remark || null,
      });
      setCreateOpen(false);
      setForm(defaultForm);
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "创建体检记录失败"));
    } finally {
      setSaving(false);
    }
  }

  async function submitEdit() {
    if (!editing) return;
    setSaving(true);
    setFormError("");
    try {
      await updateChildHealthCheckup(editing.id, {
        checkup_date: editForm.checkup_date,
        checkup_type: editForm.checkup_type,
        items: parseItems(editForm.itemsText),
        conclusion: editForm.conclusion || null,
        guidance: editForm.guidance || null,
        next_checkup_date: editForm.next_checkup_date || null,
        remark: editForm.remark || null,
      });
      setEditing(null);
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "修正体检记录失败"));
    } finally {
      setSaving(false);
    }
  }

  const columns: Column<ChildHealthCheckup>[] = [
    { key: "checkup_date", header: "体检日期", render: (row) => formatDate(row.checkup_date) },
    { key: "checkup_type", header: "类型", render: (row) => row.checkup_type },
    { key: "age_months", header: "月龄", render: (row) => (row.age_months === null ? "-" : `${row.age_months} 月`) },
    {
      key: "items",
      header: "检查项",
      render: (row) => (row.items && row.items.length > 0 ? `${row.items.length} 项` : "-"),
    },
    { key: "conclusion", header: "结论", render: (row) => conclusionBadge(row.conclusion) },
    { key: "next_checkup_date", header: "下次体检", render: (row) => formatDate(row.next_checkup_date) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          variant="ghost"
          onClick={() => {
            setEditing(row);
            setFormError("");
            setEditForm({
              checkup_date: row.checkup_date,
              checkup_type: row.checkup_type,
              itemsText: itemsToText(row.items),
              conclusion: row.conclusion ?? "",
              guidance: row.guidance ?? "",
              next_checkup_date: row.next_checkup_date ?? "",
              remark: row.remark ?? "",
            });
          }}
        >
          修正
        </Button>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <Card title={`儿童体检（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="child-checkup-patient">
              选择{entity.person}
            </label>
            <select
              id="child-checkup-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setPage(1);
              }}
            >
              <option value="">请选择</option>
              {children.map((child) => (
                <option key={child.id} value={child.id}>
                  {child.name}（{formatAge(child.birth_date)}）
                </option>
              ))}
            </select>
          </div>
          {selected && (
            <span className="text-sm text-fg-muted">
              出生日期 {formatDate(selected.birth_date)} · 当前月龄 {formatAge(selected.birth_date)}
            </span>
          )}
          <div className="ml-auto">
            <Button onClick={() => { setForm(defaultForm); setFormError(""); setCreateOpen(true); }} disabled={!patientId}>
              新建体检记录
            </Button>
          </div>
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card
        title={`体检记录（${total}）`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {page} / {totalPages}
            </span>
            <Button
              variant="ghost"
              onClick={() => setPage((value) => Math.min(totalPages, value + 1))}
              disabled={page >= totalPages}
            >
              下一页
            </Button>
          </div>
        }
      >
        {loading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : records.length === 0 ? (
          <EmptyState icon="🩻" title="暂无体检记录" description="选择儿童后新建第一条保健体检记录" />
        ) : (
          <Table columns={columns} data={records} />
        )}
      </Card>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="新建儿保体检记录" width="720px">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="体检日期"
              type="date"
              value={form.checkup_date}
              max={todayLocal()}
              onChange={(event) => setForm({ ...form, checkup_date: event.target.value })}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="checkup-type">
                体检类型
              </label>
              <select
                id="checkup-type"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={form.checkup_type}
                onChange={(event) => setForm({ ...form, checkup_type: event.target.value })}
              >
                {CHILD_CHECKUP_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="checkup-conclusion">
                结论
              </label>
              <select
                id="checkup-conclusion"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={form.conclusion}
                onChange={(event) => setForm({ ...form, conclusion: event.target.value })}
              >
                <option value="">未结论</option>
                {CHILD_CHECKUP_CONCLUSIONS.map((conclusion) => (
                  <option key={conclusion} value={conclusion}>
                    {conclusion}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="建议下次体检日期"
              type="date"
              value={form.next_checkup_date}
              onChange={(event) => setForm({ ...form, next_checkup_date: event.target.value })}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="checkup-items">
              检查项（每行一项，格式：项目|结果|结论）
            </label>
            <textarea
              id="checkup-items"
              className="min-h-[96px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={form.itemsText}
              placeholder={"体重|8.6kg|正常\n身长|72cm|正常\n血红蛋白|105g/L|偏低"}
              onChange={(event) => setForm({ ...form, itemsText: event.target.value })}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="checkup-guidance">
              本次指导
            </label>
            <textarea
              id="checkup-guidance"
              className="min-h-[72px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={form.guidance}
              onChange={(event) => setForm({ ...form, guidance: event.target.value })}
            />
          </div>
          <Input
            label="备注"
            value={form.remark}
            onChange={(event) => setForm({ ...form, remark: event.target.value })}
          />
          {formError && <p className="text-sm text-danger">{formError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submitCreate} loading={saving}>
              保存
            </Button>
          </div>
        </div>
      </Modal>

      <Modal open={editing !== null} onClose={() => setEditing(null)} title="修正体检记录" width="720px">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="体检日期"
              type="date"
              value={editForm.checkup_date}
              max={todayLocal()}
              onChange={(event) => setEditForm({ ...editForm, checkup_date: event.target.value })}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="edit-checkup-type">
                体检类型
              </label>
              <select
                id="edit-checkup-type"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={editForm.checkup_type}
                onChange={(event) => setEditForm({ ...editForm, checkup_type: event.target.value })}
              >
                {CHILD_CHECKUP_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="edit-checkup-conclusion">
                结论
              </label>
              <select
                id="edit-checkup-conclusion"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={editForm.conclusion}
                onChange={(event) => setEditForm({ ...editForm, conclusion: event.target.value })}
              >
                <option value="">未结论</option>
                {CHILD_CHECKUP_CONCLUSIONS.map((conclusion) => (
                  <option key={conclusion} value={conclusion}>
                    {conclusion}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="建议下次体检日期"
              type="date"
              value={editForm.next_checkup_date}
              onChange={(event) => setEditForm({ ...editForm, next_checkup_date: event.target.value })}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="edit-checkup-items">
              检查项（每行一项，格式：项目|结果|结论）
            </label>
            <textarea
              id="edit-checkup-items"
              className="min-h-[96px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={editForm.itemsText}
              onChange={(event) => setEditForm({ ...editForm, itemsText: event.target.value })}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="edit-checkup-guidance">
              本次指导
            </label>
            <textarea
              id="edit-checkup-guidance"
              className="min-h-[72px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={editForm.guidance}
              onChange={(event) => setEditForm({ ...editForm, guidance: event.target.value })}
            />
          </div>
          <Input
            label="备注"
            value={editForm.remark}
            onChange={(event) => setEditForm({ ...editForm, remark: event.target.value })}
          />
          {formError && <p className="text-sm text-danger">{formError}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setEditing(null)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submitEdit} loading={saving}>
              保存修正
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
