import { useCallback, useEffect, useState } from "react";
import { Button } from "@pitchfork/ui";
import {
  getAbnormalVitalSignSummary,
  getFollowupPlanStats,
  getPaymentSummary,
  listActiveElderlyAdmissions,
  listNursingExecutionStatistics,
  listPatients,
} from "@pitchfork/shared/aceso";
import { ENTITY_LABELS } from "../lib/labels";
import { formatTime, todayLocal } from "../lib/datetime";
import {
  formatCount,
  formatYuan,
  nursingAttentionCount,
  nursingOverdueCount,
} from "./dashboardStats";

type Loadable<T> =
  | { status: "loading" }
  | { status: "ready"; data: T }
  | { status: "error"; message: string };

interface NursingHomeSummary {
  attention: number;
  inProgress: number;
  overdue: number;
}

interface DashboardStats {
  records: Loadable<number>;
  admissions: Loadable<number>;
  nursing: Loadable<NursingHomeSummary>;
  followup: Loadable<{
    todayPending: number;
    overdue: number;
    monthCompleted: number;
  }>;
  abnormal: Loadable<{ pending: number; today: number }>;
  billing: Loadable<{ due: number; paid: number; arrears: number }>;
}

interface CardMetric {
  status: Loadable<unknown>["status"];
  value: string;
  detail: string;
  error?: string;
}

interface DashboardCardData {
  key: string;
  title: string;
  href: string;
  icon: IconName;
  tone: Tone;
  metric: CardMetric;
}

type IconName =
  | "records"
  | "admissions"
  | "nursing"
  | "followup"
  | "abnormal"
  | "billing";

type Tone = "accent" | "success" | "info" | "warning" | "danger";

const TONE_STYLES: Record<Tone, { icon: string; detail: string }> = {
  accent: {
    icon: "bg-accent/10 text-accent",
    detail: "text-accent",
  },
  success: {
    icon: "bg-success-bg text-success",
    detail: "text-success",
  },
  info: {
    icon: "bg-info-bg text-info",
    detail: "text-info",
  },
  warning: {
    icon: "bg-warning-bg text-warning",
    detail: "text-warning",
  },
  danger: {
    icon: "bg-danger-bg text-danger",
    detail: "text-danger",
  },
};

const INITIAL_STATS: DashboardStats = {
  records: { status: "loading" },
  admissions: { status: "loading" },
  nursing: { status: "loading" },
  followup: { status: "loading" },
  abnormal: { status: "loading" },
  billing: { status: "loading" },
};

async function settle<T>(
  promise: Promise<T>,
  fallback: string,
): Promise<Loadable<T>> {
  try {
    return { status: "ready", data: await promise };
  } catch (error) {
    return {
      status: "error",
      message: error instanceof Error && error.message ? error.message : fallback,
    };
  }
}

function mapLoadable<T, U>(
  state: Loadable<T>,
  transform: (data: T) => U,
): Loadable<U> {
  return state.status === "ready"
    ? { status: "ready", data: transform(state.data) }
    : state;
}

function metricFrom<T>(
  state: Loadable<T>,
  render: (data: T) => { value: string; detail: string },
): CardMetric {
  if (state.status === "ready") {
    return { status: state.status, ...render(state.data) };
  }
  return {
    status: state.status,
    value: "—",
    detail: state.status === "loading" ? "正在读取" : "暂时无法读取",
    error: state.status === "error" ? state.message : undefined,
  };
}

function DashboardIcon({ name }: { name: IconName }) {
  const common = {
    className: "h-5 w-5",
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
  };

  switch (name) {
    case "records":
      return (
        <svg {...common}>
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
          <line x1="16" y1="13" x2="8" y2="13" />
          <line x1="16" y1="17" x2="8" y2="17" />
        </svg>
      );
    case "admissions":
      return (
        <svg {...common}>
          <path d="M3 10.5 12 3l9 7.5" />
          <path d="M5 9.5V21h14V9.5" />
          <path d="M9 21v-6h6v6" />
        </svg>
      );
    case "nursing":
      return (
        <svg {...common}>
          <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.29 1.51 4.04 3 5.5l7 7Z" />
          <path d="M8 11h8" />
          <path d="M12 7v8" />
        </svg>
      );
    case "followup":
      return (
        <svg {...common}>
          <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.79 19.79 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.69 2.8a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.9.33 1.84.56 2.8.69A2 2 0 0 1 22 16.92z" />
        </svg>
      );
    case "abnormal":
      return (
        <svg {...common}>
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
      );
    case "billing":
      return (
        <svg {...common}>
          <rect x="2" y="5" width="20" height="14" rx="2" />
          <line x1="2" y1="10" x2="22" y2="10" />
          <line x1="6" y1="15" x2="10" y2="15" />
        </svg>
      );
  }
}

function StatCard({ card }: { card: DashboardCardData }) {
  const tone = TONE_STYLES[card.tone];
  return (
    <a
      href={card.href}
      title={card.metric.error}
      className="group relative overflow-hidden rounded-xl border border-border bg-surface p-5 transition-colors hover:border-accent/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
      aria-label={`${card.title}，${card.metric.value}，${card.metric.detail}`}
    >
      <div className="flex items-start justify-between gap-4">
        <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${tone.icon}`}>
          <DashboardIcon name={card.icon} />
        </div>
        <svg
          className="mt-1 h-4 w-4 text-fg-dimmed transition-transform group-hover:translate-x-0.5 group-hover:text-accent"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </div>

      <p className="mt-5 text-sm font-medium text-fg-muted">{card.title}</p>
      {card.metric.status === "loading" ? (
        <div
          className="mt-2 h-9 w-24 animate-pulse rounded bg-surface-alt"
          aria-label="正在读取"
        />
      ) : (
        <p
          className={`mt-1 text-3xl font-semibold tracking-tight ${
            card.metric.status === "error"
              ? "text-fg-dimmed"
              : "text-fg-emphasis"
          }`}
        >
          {card.metric.value}
        </p>
      )}
      <p
        className={`mt-2 min-h-5 text-xs ${
          card.metric.status === "ready"
            ? tone.detail
            : "text-fg-dimmed"
        }`}
      >
        {card.metric.detail}
      </p>
    </a>
  );
}

/**
 * 养老控制台（056：域切换器已移除）。
 *
 * 056 之前这里有一个「养老 / 医疗 / 儿保」切换器，改动的是
 * `localStorage["aceso-domain"]` 并广播事件让菜单与文案跟随。产品收窄为养老后
 * 不再存在第二、第三个域，切换器随之删除；文案统一取 `ENTITY_LABELS`。
 */
export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats>(INITIAL_STATS);
  const [refreshing, setRefreshing] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);

  const loadStats = useCallback(async () => {
    setRefreshing(true);
    const today = todayLocal();

    const [records, admissions, nursing, followup, abnormal, billing] =
      await Promise.all([
        settle(listPatients({ limit: 1 }), "无法读取长者档案统计"),
        settle(
          listActiveElderlyAdmissions({ limit: 1 }),
          "无法读取在住长者统计",
        ),
        settle(
          listNursingExecutionStatistics({
            date_from: today,
            date_to: today,
            limit: 1,
          }),
          "无法读取今日护理统计",
        ),
        settle(getFollowupPlanStats(), "无法读取随访统计"),
        settle(getAbnormalVitalSignSummary(), "无法读取异常告警统计"),
        settle(getPaymentSummary(), "无法读取收费统计"),
      ]);

    setStats({
      records: mapLoadable(records, (data) => data.meta.total),
      admissions: mapLoadable(admissions, (data) => data.meta.total),
      nursing: mapLoadable(nursing, (data) => ({
        attention: nursingAttentionCount(
          data.meta.pending_total,
          data.meta.in_progress_total,
        ),
        inProgress: data.meta.in_progress_total,
        overdue: nursingOverdueCount(data.meta.overdue_total, undefined),
      })),
      followup: mapLoadable(followup, (data) => ({
        todayPending: data.today_pending,
        overdue: data.overdue,
        monthCompleted: data.month_completed,
      })),
      abnormal: mapLoadable(abnormal, (data) => ({
        pending: data.pending_total,
        today: data.today_total,
      })),
      billing: mapLoadable(billing, (data) => ({
        due: data.due_amount,
        paid: data.paid_amount,
        arrears: data.arrears_amount,
      })),
    });
    setUpdatedAt(new Date().toISOString());
    setRefreshing(false);
  }, []);

  useEffect(() => {
    void loadStats();
  }, [loadStats]);

  const cards: DashboardCardData[] = [
    {
      key: "records",
      title: ENTITY_LABELS.archive,
      href: "/eldercare/elders",
      icon: "records",
      tone: "accent",
      metric: metricFrom(stats.records, (total) => ({
        value: formatCount(total),
        detail: "全部历史与在册档案",
      })),
    },
    {
      key: "admissions",
      title: "当前在住",
      href: "/eldercare/admission",
      icon: "admissions",
      tone: "success",
      metric: metricFrom(stats.admissions, (total) => ({
        value: formatCount(total),
        detail: "有效养老入住",
      })),
    },
    {
      key: "nursing",
      title: "今日护理待处理",
      href: "/eldercare/inpatient",
      icon: "nursing",
      tone: "info",
      metric: metricFrom(stats.nursing, (data) => ({
        value: formatCount(data.attention),
        detail: `执行中 ${formatCount(data.inProgress)} · 逾期 ${formatCount(data.overdue)}`,
      })),
    },
    {
      key: "followup",
      title: "今日待随访",
      href: "/eldercare/followup",
      icon: "followup",
      tone: "warning",
      metric: metricFrom(stats.followup, (data) => ({
        value: formatCount(data.todayPending),
        detail: `已逾期 ${formatCount(data.overdue)} · 本月完成 ${formatCount(data.monthCompleted)}`,
      })),
    },
    {
      key: "abnormal",
      title: "待复核异常",
      href: "/eldercare/abnormal-alerts",
      icon: "abnormal",
      tone: "danger",
      metric: metricFrom(stats.abnormal, (data) => ({
        value: formatCount(data.pending),
        detail: `今日新增 ${formatCount(data.today)}`,
      })),
    },
    {
      key: "billing",
      title: "当前未收欠费",
      href: "/eldercare/billing",
      icon: "billing",
      tone: "success",
      metric: metricFrom(stats.billing, (data) => ({
        value: formatYuan(data.arrears),
        detail: `应缴 ${formatYuan(data.due)} · 已收 ${formatYuan(data.paid)}`,
      })),
    },
  ];

  const hasError = cards.some((card) => card.metric.status === "error");

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-fg-emphasis">运营总览</h1>
          <p className="mt-1 text-sm text-fg-muted">
            {todayLocal()} · 长者照护与运营关键数据
          </p>
        </div>
        <div className="flex items-center gap-3">
          <p className="text-xs text-fg-dimmed" aria-live="polite">
            {updatedAt ? `更新于 ${formatTime(updatedAt)}` : "正在加载实时数据"}
          </p>
          <Button
            type="button"
            size="sm"
            variant="secondary"
            loading={refreshing}
            onClick={() => void loadStats()}
          >
            刷新
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {cards.map((card) => (
          <StatCard key={card.key} card={card} />
        ))}
      </div>

      {hasError && (
        <p className="rounded-lg border border-warning/30 bg-warning-bg px-4 py-3 text-sm text-warning">
          部分统计暂时无法读取，其他数据仍可正常使用。请点击对应卡片进入业务页，或稍后刷新重试。
        </p>
      )}
    </div>
  );
}
