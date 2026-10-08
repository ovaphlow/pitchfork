import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  createChildGuidanceRecord,
  createChildHealthNotification,
  listChildGuidanceRecords,
  listChildGuidanceTemplates,
  listPatients,
  type ChildGuidanceRecord,
  type ChildGuidanceTemplate,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDateTime, nowLocalInput, toOffsetDateTime } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import { ageBandOf, findTemplate, groupTemplatesByTopic, topicIcon } from "../lib/guidance";
import { ageMonthsBetween } from "../lib/growth";
import { useDomain } from "../lib/useDomain";

/**
 * 日常健康指导页（052 P5）。
 *
 * 按当前月龄推荐模板（模板常量在后端，`GET /child-guidance-templates?age_months=`），
 * 护士可编辑内容后保存开展记录；可选一键转家长通报（复用 P4 通报台账）。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function ChildGuidancePage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [templates, setTemplates] = useState<ChildGuidanceTemplate[]>([]);
  const [records, setRecords] = useState<ChildGuidanceRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [selectedCode, setSelectedCode] = useState("");
  const [content, setContent] = useState("");
  const [channel, setChannel] = useState("站内");
  const [deliveredAt, setDeliveredAt] = useState(nowLocalInput());
  const [alsoNotify, setAlsoNotify] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [savedHint, setSavedHint] = useState("");

  const selected = useMemo(() => children.find((child) => child.id === patientId) ?? null, [children, patientId]);
  const ageMonths = selected ? ageMonthsBetween(selected.birth_date, nowLocalInput().slice(0, 10)) : null;

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

  useEffect(() => {
    if (ageMonths === null) {
      setTemplates([]);
      return;
    }
    let active = true;
    listChildGuidanceTemplates(ageMonths)
      .then((response) => {
        if (!active) return;
        setTemplates(response.records);
        const first = response.records[0];
        if (first) {
          setSelectedCode(first.template_code);
          setContent(first.content);
        }
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, "无法加载指导模板"));
      });
    return () => {
      active = false;
    };
  }, [ageMonths]);

  const load = useCallback(async () => {
    if (!patientId) {
      setRecords([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const response = await listChildGuidanceRecords({
        patient_id: patientId,
        limit: PAGE_SIZE,
        offset: (page - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载指导记录"));
    } finally {
      setLoading(false);
    }
  }, [patientId, page]);

  useEffect(() => {
    void load();
  }, [load]);

  function pickTemplate(template: ChildGuidanceTemplate) {
    setSelectedCode(template.template_code);
    setContent(template.content);
    setSavedHint("");
  }

  async function submit() {
    if (!patientId || !selectedCode) return;
    setSaving(true);
    setFormError("");
    setSavedHint("");
    try {
      const template = findTemplate(templates, selectedCode);
      const created = await createChildGuidanceRecord({
        patient_id: patientId,
        template_code: selectedCode,
        age_band: template?.age_band,
        topic: template?.topic,
        content,
        delivered_at: toOffsetDateTime(deliveredAt),
        channel,
      });
      if (alsoNotify) {
        const notification = await createChildHealthNotification({
          patient_id: patientId,
          alert_type: "健康指导",
          alert_ref: null,
          channel,
          content: `【健康指导】${selected?.name ?? ""}：${created.content}`,
        });
        setSavedHint(
          notification.status === "已发送"
            ? "已保存指导记录，并已生成家长通报（站内）"
            : `已保存指导记录；家长通报状态：${notification.status}`,
        );
      } else {
        setSavedHint("已保存指导记录");
      }
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "保存指导记录失败"));
    } finally {
      setSaving(false);
    }
  }

  const columns: Column<ChildGuidanceRecord>[] = [
    { key: "delivered_at", header: "开展时间", render: (row) => formatDateTime(row.delivered_at) },
    { key: "topic", header: "主题", render: (row) => `${topicIcon(row.topic)} ${row.topic ?? "-"}` },
    { key: "age_band", header: "月龄段", render: (row) => row.age_band ?? "-" },
    { key: "template_code", header: "模板", render: (row) => row.template_code },
    { key: "channel", header: "渠道", render: (row) => row.channel },
    { key: "content", header: "内容", render: (row) => <span className="text-xs text-fg-muted">{row.content}</span> },
  ];

  const groups = groupTemplatesByTopic(templates);
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <Card title={`日常健康指导（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="guidance-patient">
              选择{entity.person}
            </label>
            <select
              id="guidance-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setPage(1);
                setSavedHint("");
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
          {selected && ageMonths !== null && (
            <span className="text-sm text-fg-muted">
              当前 {formatAge(selected.birth_date)}（{ageMonths} 月，月龄段 {ageBandOf(ageMonths)}）
            </span>
          )}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card title="推荐指导模板">
        {groups.length === 0 ? (
          <EmptyState icon="📋" title="暂无推荐模板" description="选择儿童后按当前月龄推荐" />
        ) : (
          <div className="flex flex-col gap-4">
            {groups.map((group) => (
              <div key={group.topic} className="flex flex-col gap-2">
                <span className="text-sm font-semibold text-fg-emphasis">
                  {topicIcon(group.topic)} {group.topic}
                </span>
                <div className="flex flex-wrap gap-2">
                  {group.templates.map((template) => (
                    <Button
                      key={template.template_code}
                      variant={selectedCode === template.template_code ? "primary" : "ghost"}
                      onClick={() => pickTemplate(template)}
                    >
                      {template.title}
                    </Button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card title="开展指导">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="开展时间"
              type="datetime-local"
              value={deliveredAt}
              max={nowLocalInput()}
              onChange={(event) => setDeliveredAt(event.target.value)}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="guidance-channel">
                渠道
              </label>
              <select
                id="guidance-channel"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={channel}
                onChange={(event) => setChannel(event.target.value)}
              >
                <option value="站内">站内</option>
                <option value="短信">短信（未接入）</option>
                <option value="微信">微信（未接入）</option>
              </select>
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="guidance-content">
              指导内容（可编辑）
            </label>
            <textarea
              id="guidance-content"
              className="min-h-[160px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={content}
              onChange={(event) => setContent(event.target.value)}
            />
          </div>
          <label className="flex items-center gap-2 text-sm text-fg-muted">
            <input type="checkbox" checked={alsoNotify} onChange={(event) => setAlsoNotify(event.target.checked)} />
            同时生成家长通报（复用通报台账；渠道未接入时记为待接入）
          </label>
          {formError && <p className="text-sm text-danger">{formError}</p>}
          {savedHint && <p className="text-sm text-success">{savedHint}</p>}
          <div className="flex justify-end">
            <Button onClick={submit} loading={saving} disabled={!patientId || !content.trim()}>
              保存开展记录
            </Button>
          </div>
        </div>
      </Card>

      <Card
        title={`开展记录（${total}）`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {page} / {totalPages}
            </span>
            <Button variant="ghost" onClick={() => setPage((value) => Math.min(totalPages, value + 1))} disabled={page >= totalPages}>
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
          <EmptyState icon="🗂️" title="暂无指导记录" />
        ) : (
          <Table columns={columns} data={records} />
        )}
      </Card>
    </div>
  );
}
