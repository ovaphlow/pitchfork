import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  CHILD_NOTIFICATION_CHANNELS,
  createChildHealthNotification,
  listChildHealthAlerts,
  listChildHealthNotifications,
  listPatients,
  type ChildHealthAlert,
  type ChildHealthNotification,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, formatDateTime } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import { useDomain } from "../lib/useDomain";

/**
 * 儿保异常提醒与家长通报页（052 P4）。
 *
 * 异常由后端按儿童口径聚合（体温/心率/呼吸按年龄档、生长按 P3/P97、接种逾期、体检异常）；
 * 本页负责展示、筛选与「通报家长」动作。通报会写本域台账并调用 platformd 共享 message；
 * 渠道未接入或发送失败都会如实标注，**不显示为已送达**（052 D6 / 裁定 4）。
 */

const PAGE_SIZE = 20;

const STATUS_OPTIONS = ["待通报", "已通报"] as const;

type AlertRow = ChildHealthAlert & { row_key: string };

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function severityBadge(severity: string) {
  if (severity === "高") return <Badge variant="danger">高</Badge>;
  if (severity === "中") return <Badge variant="warning">中</Badge>;
  return <Badge>{severity}</Badge>;
}

function notificationBadge(status: string) {
  if (status === "已发送") return <Badge variant="success">已发送</Badge>;
  if (status === "发送失败") return <Badge variant="danger">发送失败</Badge>;
  return <Badge variant="warning">{status}</Badge>;
}

/** 通报内容模板：只带必要字段，不含身份证号（052 D10）。 */
function draftContent(childName: string, alert: ChildHealthAlert): string {
  const detail = alert.detail ? `（${alert.detail}）` : "";
  return `【儿童健康提醒】${childName}：${alert.title}${detail}。请家长及时关注并按建议就诊或复测，如有疑问请联系儿保科。`;
}

export default function ChildHealthAlertsPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [alerts, setAlerts] = useState<ChildHealthAlert[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [notifications, setNotifications] = useState<ChildHealthNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [target, setTarget] = useState<ChildHealthAlert | null>(null);
  const [channel, setChannel] = useState<string>("站内");
  const [content, setContent] = useState("");
  const [guardianName, setGuardianName] = useState("");
  const [guardianPhone, setGuardianPhone] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

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
      setAlerts([]);
      setTotal(0);
      setNotifications([]);
      return;
    }
    setLoading(true);
    try {
      const [alertList, ledger] = await Promise.all([
        listChildHealthAlerts({
          patient_id: patientId,
          status: statusFilter || undefined,
          limit: PAGE_SIZE,
          offset: (page - 1) * PAGE_SIZE,
        }),
        listChildHealthNotifications({ patient_id: patientId, limit: PAGE_SIZE }),
      ]);
      setAlerts(alertList.records);
      setTotal(alertList.meta.total);
      setNotifications(ledger.records);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载异常提醒"));
    } finally {
      setLoading(false);
    }
  }, [patientId, statusFilter, page]);

  useEffect(() => {
    void load();
  }, [load]);

  function openNotify(alert: ChildHealthAlert) {
    setTarget(alert);
    setChannel("站内");
    setContent(draftContent(selected?.name ?? "", alert));
    setGuardianName(alert.guardian_name ?? "");
    setGuardianPhone(alert.guardian_phone ?? "");
    setFormError("");
  }

  async function submitNotify() {
    if (!target || !patientId) return;
    setSaving(true);
    setFormError("");
    try {
      await createChildHealthNotification({
        patient_id: patientId,
        alert_type: target.alert_type,
        alert_ref: target.alert_ref,
        guardian_name: guardianName || null,
        guardian_phone: guardianPhone || null,
        channel,
        content,
      });
      setTarget(null);
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "登记通报失败"));
    } finally {
      setSaving(false);
    }
  }

  const alertColumns: Column<AlertRow>[] = [
    { key: "occurred_at", header: "发生时间", render: (row) => formatDateTime(row.occurred_at) },
    { key: "alert_type", header: "异常类型", render: (row) => row.alert_type },
    { key: "severity", header: "级别", render: (row) => severityBadge(row.severity) },
    {
      key: "title",
      header: "摘要",
      render: (row) => (
        <div className="flex flex-col">
          <span>{row.title}</span>
          {row.detail && <span className="text-xs text-fg-dimmed">{row.detail}</span>}
        </div>
      ),
    },
    {
      key: "status",
      header: "通报状态",
      render: (row) =>
        row.status === "已通报" ? <Badge variant="success">已通报</Badge> : <Badge variant="warning">待通报</Badge>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="ghost" onClick={() => openNotify(row)}>
          通报家长
        </Button>
      ),
    },
  ];

  const notificationColumns: Column<ChildHealthNotification>[] = [
    { key: "created_at", header: "通报时间", render: (row) => formatDateTime(row.created_at) },
    { key: "alert_type", header: "异常类型", render: (row) => row.alert_type },
    { key: "channel", header: "渠道", render: (row) => row.channel },
    { key: "status", header: "状态", render: (row) => notificationBadge(row.status) },
    { key: "message_id", header: "Nexus 消息", render: (row) => row.message_id ?? "-" },
    { key: "content", header: "内容快照", render: (row) => <span className="text-xs text-fg-muted">{row.content}</span> },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <Card title={`异常提醒与家长通报（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="alerts-patient">
              选择{entity.person}
            </label>
            <select
              id="alerts-patient"
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
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="alerts-status">
              通报状态
            </label>
            <select
              id="alerts-status"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={statusFilter}
              onChange={(event) => {
                setStatusFilter(event.target.value);
                setPage(1);
              }}
            >
              <option value="">全部</option>
              {STATUS_OPTIONS.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {selected && (
            <span className="text-sm text-fg-muted">
              出生日期 {formatDate(selected.birth_date)} · 当前 {formatAge(selected.birth_date)}
            </span>
          )}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card
        title={`异常提醒（${total}）`}
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
        ) : alerts.length === 0 ? (
          <EmptyState icon="🔔" title="暂无异常提醒" description="体温/心率/呼吸、生长、接种逾期、体检异常会在这里汇总" />
        ) : (
          <Table
            columns={alertColumns}
            data={alerts.map((row) => ({
              ...row,
              row_key: `${row.alert_type}-${row.alert_ref ?? row.occurred_at}-${row.metric}`,
            }))}
            keyField="row_key"
          />
        )}
      </Card>

      <Card title={`通报台账（${notifications.length}）`}>
        {notifications.length === 0 ? (
          <EmptyState icon="📨" title="暂无通报记录" description="对异常项点「通报家长」后在此留痕" />
        ) : (
          <Table columns={notificationColumns} data={notifications} />
        )}
        <p className="mt-3 text-xs text-fg-dimmed">
          「待接入」表示该渠道尚未接入外发能力，「发送失败」表示 platformd 共享 message 未创建成功——两者都不代表已送达。
        </p>
      </Card>

      <Modal open={target !== null} onClose={() => setTarget(null)} title="通报家长" width="680px">
        {target && (
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="flex items-center gap-2">
                {severityBadge(target.severity)}
                <span className="font-medium text-fg-emphasis">{target.title}</span>
              </div>
              {target.detail && <p className="mt-1 text-fg-muted">{target.detail}</p>}
              <p className="mt-1 text-xs text-fg-dimmed">
                {target.alert_type} · 发生于 {formatDateTime(target.occurred_at)}
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="监护人姓名"
                value={guardianName}
                onChange={(event) => setGuardianName(event.target.value)}
              />
              <Input
                label="监护人电话"
                value={guardianPhone}
                onChange={(event) => setGuardianPhone(event.target.value)}
              />
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="notify-channel">
                  渠道
                </label>
                <select
                  id="notify-channel"
                  className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                  value={channel}
                  onChange={(event) => setChannel(event.target.value)}
                >
                  {CHILD_NOTIFICATION_CHANNELS.map((item) => (
                    <option key={item} value={item}>
                      {item}
                      {item === "站内" ? "（已接入）" : "（未接入，记待接入）"}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="notify-content">
                通报内容
              </label>
              <textarea
                id="notify-content"
                className="min-h-[120px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
                value={content}
                onChange={(event) => setContent(event.target.value)}
              />
            </div>
            <p className="text-xs text-fg-dimmed">内容快照会与通报台账一并保存；只带必要字段，不写身份证号。</p>
            {formError && <p className="text-sm text-danger">{formError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setTarget(null)} disabled={saving}>
                取消
              </Button>
              <Button onClick={submitNotify} loading={saving} disabled={!content.trim()}>
                登记通报
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
