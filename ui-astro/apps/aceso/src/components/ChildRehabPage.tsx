import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  acknowledgeChildRehabHandout,
  createChildRehabHandout,
  createChildRehabMilestone,
  createChildRehabPeriod,
  createChildRehabPlan,
  createChildRehabSession,
  getChildRehabMilestoneStatus,
  listChildRehabHandoutTemplates,
  listChildRehabHandouts,
  listChildRehabMilestones,
  listChildRehabPeriods,
  listChildRehabPlans,
  listChildRehabSessions,
  listPatients,
  updateChildRehabPeriodStatus,
  type ChildRehabHandout,
  type ChildRehabHandoutTemplate,
  type ChildRehabMilestoneRecord,
  type ChildRehabMilestoneStatus,
  type ChildRehabPeriod,
  type ChildRehabPlan,
  type ChildRehabPlanItemInput,
  type ChildRehabProgress,
  type ChildRehabSession,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, formatDateTime, nowLocalInput, toOffsetDateTime, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY, currentEntityLabels } from "../lib/domain";
import { useDomain } from "../lib/useDomain";
import {
  HANDOUT_CHANNEL_SUGGESTIONS,
  addPlanItem,
  allowedPeriodTargets,
  canAcknowledgeHandout,
  emptyPlanItem,
  formatPercent,
  handoutStatusVariant,
  milestoneAchievedLabel,
  milestoneExpectedLabel,
  milestoneHeadline,
  milestoneStateVariant,
  normalizePlanItems,
  parseRatioInput,
  periodStatusLabel,
  periodStatusVariant,
  progressBarPercent,
  progressHeadline,
  progressNotice,
  removePlanItem,
  sortMilestoneItems,
  updatePlanItem,
  validateHandoutForm,
  validateMilestoneForm,
  validatePeriodForm,
  validatePlanForm,
  validateSessionForm,
  type PeriodStatus,
} from "../lib/rehab";

/**
 * 康复师工作台（054 B5）。
 *
 * 消费 B1–B4 的冻结路由（§5.2），把康复师的日常链路收在一页：
 *   康复周期（B1）→ 训练计划与明细（B1）→ 打卡与进度（B2）→
 *   里程碑四态（B3，读时派生）→ 家庭训练指导手册分发与签收（B4）。
 *
 * 边界：
 * - 周期/计划/打卡/里程碑/手册的权威都在服务端；本页不重算派生结果
 *   （`lib/rehab.ts` 只做枚举镜像、校验与展示格式化）。
 * - 里程碑四态由后端**读时派生**（同一儿童跨月再查会变化），前端只排序/计数/配色。
 * - 进度 `progress_ratio` **不封顶**（可能 > 100%）：文案原样展示，仅进度条宽度截断。
 * - 手册签收**单向**：事实源是 `acknowledged_at`；已签收不再显示签收按钮，重复签收由服务端 409 兜底。
 * - 入口显隐由 F1 机制按 `rehab:execute` 控制；**后端仍是唯一强制点**（无码直接请求 403），
 *   前端隐藏不替代 403。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function ChildRehabPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [error, setError] = useState("");

  // —— B1 康复周期 ——
  const [periods, setPeriods] = useState<ChildRehabPeriod[]>([]);
  const [periodStart, setPeriodStart] = useState(todayLocal());
  const [periodEnd, setPeriodEnd] = useState("");
  const [coordinator, setCoordinator] = useState("");
  const [periodSaving, setPeriodSaving] = useState(false);
  const [periodHint, setPeriodHint] = useState("");
  const [periodError, setPeriodError] = useState("");
  const [transitioningId, setTransitioningId] = useState("");

  // —— B1 训练计划 ——
  const [plans, setPlans] = useState<ChildRehabPlan[]>([]);
  const [planPeriodId, setPlanPeriodId] = useState("");
  const [planName, setPlanName] = useState("");
  const [planGoals, setPlanGoals] = useState("");
  const [planStart, setPlanStart] = useState(todayLocal());
  const [planEnd, setPlanEnd] = useState("");
  const [planItems, setPlanItems] = useState<ChildRehabPlanItemInput[]>([emptyPlanItem()]);
  const [planSaving, setPlanSaving] = useState(false);
  const [planHint, setPlanHint] = useState("");
  const [planError, setPlanError] = useState("");
  const [selectedPlanId, setSelectedPlanId] = useState("");

  // —— B2 打卡与进度 ——
  const [sessions, setSessions] = useState<ChildRehabSession[]>([]);
  const [progress, setProgress] = useState<ChildRehabProgress | null>(null);
  const [sessionDate, setSessionDate] = useState(todayLocal());
  const [sessionRatio, setSessionRatio] = useState("1");
  const [sessionPerformance, setSessionPerformance] = useState("");
  const [sessionNote, setSessionNote] = useState("");
  const [sessionSaving, setSessionSaving] = useState(false);
  const [sessionHint, setSessionHint] = useState("");
  const [sessionError, setSessionError] = useState("");
  const [sessionsLoading, setSessionsLoading] = useState(false);

  // —— B3 里程碑 ——
  const [milestoneStatus, setMilestoneStatus] = useState<ChildRehabMilestoneStatus | null>(null);
  const [milestoneRecords, setMilestoneRecords] = useState<ChildRehabMilestoneRecord[]>([]);
  const [milestoneCode, setMilestoneCode] = useState("");
  const [milestoneAchievedAt, setMilestoneAchievedAt] = useState(todayLocal());
  const [milestoneSaving, setMilestoneSaving] = useState(false);
  const [milestoneHint, setMilestoneHint] = useState("");
  const [milestoneError, setMilestoneError] = useState("");

  // —— B4 手册分发与签收 ——
  const [templates, setTemplates] = useState<ChildRehabHandoutTemplate[]>([]);
  const [handouts, setHandouts] = useState<ChildRehabHandout[]>([]);
  const [templateCode, setTemplateCode] = useState("");
  const [handoutContent, setHandoutContent] = useState("");
  const [handoutChannel, setHandoutChannel] = useState<string>(HANDOUT_CHANNEL_SUGGESTIONS[0]);
  const [deliveredAt, setDeliveredAt] = useState(nowLocalInput());
  const [handoutSaving, setHandoutSaving] = useState(false);
  const [handoutHint, setHandoutHint] = useState("");
  const [handoutError, setHandoutError] = useState("");
  const [acknowledgingId, setAcknowledgingId] = useState("");

  const selected = useMemo(
    () => children.find((child) => child.id === patientId) ?? null,
    [children, patientId],
  );
  const selectedPlan = useMemo(
    () => plans.find((plan) => plan.id === selectedPlanId) ?? null,
    [plans, selectedPlanId],
  );
  const activePeriods = useMemo(
    () => periods.filter((period) => period.status === "ACTIVE" || period.status === "SUSPENDED"),
    [periods],
  );
  const milestoneItems = useMemo(
    () => (milestoneStatus ? sortMilestoneItems(milestoneStatus.items ?? []) : []),
    [milestoneStatus],
  );

  // —— 儿童列表（默认取 URL 上的 patient，其次第一个）——
  useEffect(() => {
    let active = true;
    listPatients({ person_type: "儿童", status: "ACTIVE", limit: 200 })
      .then((response) => {
        if (!active) return;
        setChildren(response.records);
        const fromUrl =
          typeof window === "undefined"
            ? null
            : new URLSearchParams(window.location.search).get("patient");
        const initial =
          fromUrl && response.records.some((child) => child.id === fromUrl)
            ? fromUrl
            : response.records[0]?.id ?? "";
        setPatientId(initial);
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, `无法加载${currentEntityLabels().person}列表`));
      });
    return () => {
      active = false;
    };
  }, []);

  // —— B1 周期 ——
  const loadPeriods = useCallback(async () => {
    if (!patientId) {
      setPeriods([]);
      setPlanPeriodId("");
      return;
    }
    try {
      const response = await listChildRehabPeriods({ patient_id: patientId, limit: 50 });
      setPeriods(response.records);
      setPlanPeriodId((current) => {
        if (current && response.records.some((period) => period.id === current)) return current;
        const active =
          response.records.find((period) => period.status === "ACTIVE") ??
          response.records.find((period) => period.status === "SUSPENDED") ??
          response.records[0];
        return active?.id ?? "";
      });
    } catch (cause) {
      setPeriodError(errorMessage(cause, "无法加载康复周期"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadPeriods();
  }, [loadPeriods]);

  // —— B1 计划 ——
  const loadPlans = useCallback(async () => {
    if (!patientId) {
      setPlans([]);
      setSelectedPlanId("");
      return;
    }
    try {
      const response = await listChildRehabPlans({ patient_id: patientId, limit: 50 });
      setPlans(response.records);
      setSelectedPlanId((current) => {
        if (current && response.records.some((plan) => plan.id === current)) return current;
        return response.records[0]?.id ?? "";
      });
    } catch (cause) {
      setPlanError(errorMessage(cause, "无法加载训练计划"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadPlans();
  }, [loadPlans]);

  // —— B2 打卡与进度（按计划；带 plan_id 时服务端返回 progress）——
  const loadSessions = useCallback(async () => {
    if (!selectedPlanId) {
      setSessions([]);
      setProgress(null);
      return;
    }
    setSessionsLoading(true);
    try {
      const response = await listChildRehabSessions({
        plan_id: selectedPlanId,
        limit: PAGE_SIZE,
      });
      setSessions(response.records);
      setProgress(response.progress ?? null);
    } catch (cause) {
      setSessionError(errorMessage(cause, "无法加载打卡记录"));
    } finally {
      setSessionsLoading(false);
    }
  }, [selectedPlanId]);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  // —— B3 里程碑（读时四态 + 登记台账）——
  const loadMilestones = useCallback(async () => {
    if (!patientId) {
      setMilestoneStatus(null);
      setMilestoneRecords([]);
      setMilestoneCode("");
      return;
    }
    try {
      const [status, records] = await Promise.all([
        getChildRehabMilestoneStatus(patientId),
        listChildRehabMilestones({ patient_id: patientId, limit: 100 }),
      ]);
      setMilestoneStatus(status);
      setMilestoneRecords(records.records);
      setMilestoneCode((current) =>
        current && status.items.some((item) => item.milestone_code === current)
          ? current
          : status.items[0]?.milestone_code ?? "",
      );
    } catch (cause) {
      setMilestoneError(errorMessage(cause, "无法加载里程碑状态"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadMilestones();
  }, [loadMilestones]);

  // —— B4 手册模板（一次）+ 台账（按儿童）——
  useEffect(() => {
    let active = true;
    listChildRehabHandoutTemplates()
      .then((response) => {
        if (!active) return;
        setTemplates(response.records);
        const first = response.records[0] ?? null;
        setTemplateCode((current) => current || first?.template_code || "");
        setHandoutContent((current) => current || first?.content || "");
      })
      .catch((cause) => {
        if (active) setHandoutError(errorMessage(cause, "无法加载手册模板"));
      });
    return () => {
      active = false;
    };
  }, []);

  const loadHandouts = useCallback(async () => {
    if (!patientId) {
      setHandouts([]);
      return;
    }
    try {
      const response = await listChildRehabHandouts({ patient_id: patientId, limit: 50 });
      setHandouts(response.records);
    } catch (cause) {
      setHandoutError(errorMessage(cause, "无法加载手册分发台账"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadHandouts();
  }, [loadHandouts]);

  // —— 动作：创建周期 ——
  async function submitPeriod() {
    const validation = validatePeriodForm({ patientId, startDate: periodStart, endDate: periodEnd });
    if (validation) {
      setPeriodError(validation);
      return;
    }
    setPeriodSaving(true);
    setPeriodError("");
    setPeriodHint("");
    try {
      await createChildRehabPeriod({
        patient_id: patientId,
        start_date: periodStart,
        end_date: periodEnd.trim() === "" ? null : periodEnd.trim(),
        coordinator: coordinator.trim() === "" ? null : coordinator.trim(),
      });
      setPeriodHint("已创建康复服务周期（service_type=CHILD_REHAB，无入住关联）");
      setCoordinator("");
      await loadPeriods();
    } catch (cause) {
      // 同儿童已有启用周期 → 409，服务端中文文案原样透出
      setPeriodError(errorMessage(cause, "创建康复周期失败"));
    } finally {
      setPeriodSaving(false);
    }
  }

  // —— 动作：周期状态流转 ——
  async function transitionPeriod(period: ChildRehabPeriod, target: PeriodStatus) {
    setTransitioningId(period.id);
    setPeriodError("");
    setPeriodHint("");
    try {
      await updateChildRehabPeriodStatus(period.id, target);
      setPeriodHint(`周期已流转为「${periodStatusLabel(target)}」`);
      await loadPeriods();
    } catch (cause) {
      setPeriodError(errorMessage(cause, "周期状态流转失败"));
    } finally {
      setTransitioningId("");
    }
  }

  // —— 动作：创建计划（含明细）——
  async function submitPlan() {
    const validation = validatePlanForm({ periodId: planPeriodId, planName, items: planItems });
    if (validation) {
      setPlanError(validation);
      return;
    }
    setPlanSaving(true);
    setPlanError("");
    setPlanHint("");
    try {
      const created = await createChildRehabPlan({
        period_id: planPeriodId,
        plan_name: planName.trim(),
        goals: planGoals.trim() === "" ? null : planGoals.trim(),
        start_date: planStart.trim() === "" ? null : planStart.trim(),
        end_date: planEnd.trim() === "" ? null : planEnd.trim(),
        items: normalizePlanItems(planItems),
      });
      setPlanHint(`已创建训练计划「${created.plan_name}」（含 ${created.items.length} 条明细）`);
      setPlanName("");
      setPlanGoals("");
      setPlanItems([emptyPlanItem()]);
      await loadPlans();
      setSelectedPlanId(created.id);
    } catch (cause) {
      setPlanError(errorMessage(cause, "创建训练计划失败"));
    } finally {
      setPlanSaving(false);
    }
  }

  // —— 动作：打卡 ——
  async function submitSession() {
    const ratio = parseRatioInput(sessionRatio);
    const validation = validateSessionForm({
      planId: selectedPlanId,
      sessionDate,
      completionRatio: ratio,
    });
    if (validation) {
      setSessionError(validation);
      return;
    }
    setSessionSaving(true);
    setSessionError("");
    setSessionHint("");
    try {
      await createChildRehabSession({
        plan_id: selectedPlanId,
        session_date: sessionDate,
        completion_ratio: ratio ?? 1,
        performance: sessionPerformance.trim() === "" ? null : sessionPerformance.trim(),
        note: sessionNote.trim() === "" ? null : sessionNote.trim(),
      });
      setSessionHint("已记录本次训练打卡（进度为服务端读时汇总）");
      setSessionPerformance("");
      setSessionNote("");
      await loadSessions();
    } catch (cause) {
      setSessionError(errorMessage(cause, "训练打卡失败"));
    } finally {
      setSessionSaving(false);
    }
  }

  // —— 动作：登记里程碑达成 ——
  async function submitMilestone() {
    const validation = validateMilestoneForm({
      patientId,
      milestoneCode,
      achievedAt: milestoneAchievedAt,
    });
    if (validation) {
      setMilestoneError(validation);
      return;
    }
    setMilestoneSaving(true);
    setMilestoneError("");
    setMilestoneHint("");
    try {
      await createChildRehabMilestone({
        patient_id: patientId,
        milestone_code: milestoneCode,
        achieved_at: milestoneAchievedAt.trim() === "" ? null : milestoneAchievedAt.trim(),
      });
      setMilestoneHint("已登记里程碑达成；四态状态由服务端按当前月龄重新派生");
      await loadMilestones();
    } catch (cause) {
      setMilestoneError(errorMessage(cause, "登记里程碑失败"));
    } finally {
      setMilestoneSaving(false);
    }
  }

  // —— 动作：分发手册 ——
  async function submitHandout() {
    const validation = validateHandoutForm({ patientId, templateCode });
    if (validation) {
      setHandoutError(validation);
      return;
    }
    setHandoutSaving(true);
    setHandoutError("");
    setHandoutHint("");
    try {
      await createChildRehabHandout({
        patient_id: patientId,
        template_code: templateCode,
        content: handoutContent.trim() === "" ? undefined : handoutContent,
        channel: handoutChannel.trim() === "" ? undefined : handoutChannel.trim(),
        delivered_at: deliveredAt.trim() === "" ? undefined : toOffsetDateTime(deliveredAt),
      });
      setHandoutHint("已登记手册分发（初始状态：未签收）");
      await loadHandouts();
    } catch (cause) {
      setHandoutError(errorMessage(cause, "分发手册失败"));
    } finally {
      setHandoutSaving(false);
    }
  }

  // —— 动作：签收（单向；重复签收由服务端 409）——
  async function acknowledgeHandout(handout: ChildRehabHandout) {
    setAcknowledgingId(handout.id);
    setHandoutError("");
    setHandoutHint("");
    try {
      await acknowledgeChildRehabHandout(handout.id);
      setHandoutHint(`已签收「${handout.template_name}」`);
      await loadHandouts();
    } catch (cause) {
      setHandoutError(errorMessage(cause, "签收失败"));
    } finally {
      setAcknowledgingId("");
    }
  }

  const periodColumns: Column<ChildRehabPeriod>[] = [
    { key: "start_date", header: "开始", render: (row) => formatDate(row.start_date) },
    { key: "end_date", header: "结束", render: (row) => formatDate(row.end_date) },
    { key: "coordinator", header: "负责人", render: (row) => row.coordinator ?? "-" },
    {
      key: "status",
      header: "状态",
      render: (row) => (
        <Badge variant={periodStatusVariant(row.status)}>{periodStatusLabel(row.status)}</Badge>
      ),
    },
    { key: "created_at", header: "创建时间", render: (row) => formatDateTime(row.created_at) },
    {
      key: "actions",
      header: "流转",
      render: (row) => {
        const targets = allowedPeriodTargets(row.status);
        if (targets.length === 0) return <span className="text-xs text-fg-dimmed">终态</span>;
        return (
          <div className="flex flex-wrap gap-1.5">
            {targets.map((target) => (
              <Button
                key={target}
                size="sm"
                variant={target === "CANCELLED" ? "ghost" : "secondary"}
                loading={transitioningId === row.id}
                onClick={() => void transitionPeriod(row, target)}
              >
                {periodStatusLabel(target)}
              </Button>
            ))}
          </div>
        );
      },
    },
  ];

  const planColumns: Column<ChildRehabPlan>[] = [
    { key: "plan_name", header: "计划名称", render: (row) => row.plan_name },
    {
      key: "range",
      header: "起止",
      render: (row) => `${formatDate(row.start_date)} ~ ${formatDate(row.end_date)}`,
    },
    {
      key: "items",
      header: "明细",
      render: (row) => (
        <span className="text-xs text-fg-muted">
          {row.items.map((item) => item.action).join("、") || "-"}
        </span>
      ),
    },
    { key: "created_by", header: "创建人", render: (row) => row.created_by ?? "-" },
    {
      key: "select",
      header: "打卡",
      render: (row) => (
        <Button
          size="sm"
          variant={selectedPlanId === row.id ? "primary" : "ghost"}
          onClick={() => setSelectedPlanId(row.id)}
        >
          选择
        </Button>
      ),
    },
  ];

  const sessionColumns: Column<ChildRehabSession>[] = [
    { key: "session_date", header: "训练日期", render: (row) => formatDate(row.session_date) },
    { key: "completion_ratio", header: "完成度", render: (row) => formatPercent(row.completion_ratio) },
    { key: "performance", header: "表现", render: (row) => row.performance ?? "-" },
    { key: "note", header: "备注", render: (row) => row.note ?? "-" },
    { key: "operator", header: "记录人", render: (row) => row.operator ?? "-" },
    { key: "created_at", header: "记录时间", render: (row) => formatDateTime(row.created_at) },
  ];

  const handoutColumns: Column<ChildRehabHandout>[] = [
    { key: "template_name", header: "手册", render: (row) => row.template_name },
    { key: "channel", header: "渠道", render: (row) => row.channel },
    { key: "delivered_at", header: "分发时间", render: (row) => formatDateTime(row.delivered_at) },
    {
      key: "status",
      header: "签收",
      render: (row) => (
        <Badge variant={handoutStatusVariant(row.status)}>{row.status}</Badge>
      ),
    },
    {
      key: "acknowledged",
      header: "签收时间/人",
      render: (row) =>
        row.acknowledged_at
          ? `${formatDateTime(row.acknowledged_at)} · ${row.acknowledged_by ?? "-"}`
          : "-",
    },
    {
      key: "actions",
      header: "操作",
      render: (row) =>
        canAcknowledgeHandout(row) ? (
          <Button
            size="sm"
            variant="secondary"
            loading={acknowledgingId === row.id}
            onClick={() => void acknowledgeHandout(row)}
          >
            签收
          </Button>
        ) : (
          <span className="text-xs text-fg-dimmed">已签收（单向）</span>
        ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`康复师工作台（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-patient">
              选择{entity.person}
            </label>
            <select
              id="rehab-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setPeriodHint("");
                setPlanHint("");
                setSessionHint("");
                setMilestoneHint("");
                setHandoutHint("");
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
          {selected && <span className="text-sm text-fg-muted">当前 {formatAge(selected.birth_date)}</span>}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      {/* ── B1 康复服务周期 ─────────────────────────────────────────── */}
      <Card title="康复服务周期（B1）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[170px]">
              <Input
                label="开始日期"
                type="date"
                value={periodStart}
                onChange={(event) => setPeriodStart(event.target.value)}
              />
            </div>
            <div className="w-[170px]">
              <Input
                label="结束日期（可选）"
                type="date"
                value={periodEnd}
                onChange={(event) => setPeriodEnd(event.target.value)}
              />
            </div>
            <div className="min-w-[200px] flex-1">
              <Input
                label="负责人（可选，缺省为当前登录用户）"
                value={coordinator}
                onChange={(event) => setCoordinator(event.target.value)}
              />
            </div>
            <Button onClick={() => void submitPeriod()} loading={periodSaving} disabled={!patientId}>
              创建周期
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            儿童康复周期不关联入住（`service_type=CHILD_REHAB`、`encounter_id` 恒为空）；
            同一{entity.person}同时只允许一个「进行中」周期，重复启用由服务端 409 兜底。
          </p>
          {periodError && <p className="text-sm text-danger">{periodError}</p>}
          {periodHint && <p className="text-sm text-success">{periodHint}</p>}
          {periods.length === 0 ? (
            <EmptyState icon="🗓️" title="暂无康复周期" description="创建周期后才能建立训练计划" />
          ) : (
            <Table columns={periodColumns} data={periods} />
          )}
        </div>
      </Card>

      {/* ── B1 训练计划 ─────────────────────────────────────────────── */}
      <Card title="训练计划与明细（B1）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[220px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-plan-period">
                所属周期
              </label>
              <select
                id="rehab-plan-period"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={planPeriodId}
                onChange={(event) => setPlanPeriodId(event.target.value)}
              >
                <option value="">请选择</option>
                {(activePeriods.length > 0 ? activePeriods : periods).map((period) => (
                  <option key={period.id} value={period.id}>
                    {formatDate(period.start_date)} 起（{periodStatusLabel(period.status)}）
                  </option>
                ))}
              </select>
            </div>
            <div className="min-w-[200px] flex-1">
              <Input
                label="计划名称"
                value={planName}
                onChange={(event) => setPlanName(event.target.value)}
              />
            </div>
            <div className="min-w-[200px] flex-1">
              <Input label="康复目标（可选）" value={planGoals} onChange={(event) => setPlanGoals(event.target.value)} />
            </div>
            <div className="w-[160px]">
              <Input
                label="计划开始"
                type="date"
                value={planStart}
                onChange={(event) => setPlanStart(event.target.value)}
              />
            </div>
            <div className="w-[160px]">
              <Input
                label="计划结束"
                type="date"
                value={planEnd}
                onChange={(event) => setPlanEnd(event.target.value)}
              />
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-fg-muted">训练动作明细</span>
            {planItems.map((item, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <div className="min-w-[200px] flex-1">
                  <Input
                    label={index === 0 ? "动作" : undefined}
                    value={item.action}
                    placeholder="如：俯卧抬头训练"
                    onChange={(event) =>
                      setPlanItems((current) => updatePlanItem(current, index, { action: event.target.value }))
                    }
                  />
                </div>
                <div className="min-w-[160px] flex-1">
                  <Input
                    label={index === 0 ? "频次（可选）" : undefined}
                    value={item.frequency_name ?? ""}
                    placeholder="如：每日 3 组"
                    onChange={(event) =>
                      setPlanItems((current) =>
                        updatePlanItem(current, index, { frequency_name: event.target.value }),
                      )
                    }
                  />
                </div>
                <div className="w-[140px]">
                  <Input
                    label={index === 0 ? "持续天数" : undefined}
                    type="number"
                    min={1}
                    value={item.duration_days === undefined || item.duration_days === null ? "" : String(item.duration_days)}
                    onChange={(event) =>
                      setPlanItems((current) =>
                        updatePlanItem(current, index, {
                          duration_days: event.target.value === "" ? null : Number(event.target.value),
                        }),
                      )
                    }
                  />
                </div>
                <div className="min-w-[160px] flex-1">
                  <Input
                    label={index === 0 ? "备注" : undefined}
                    value={item.remark ?? ""}
                    onChange={(event) =>
                      setPlanItems((current) => updatePlanItem(current, index, { remark: event.target.value }))
                    }
                  />
                </div>
                <Button
                  variant="ghost"
                  onClick={() => setPlanItems((current) => removePlanItem(current, index))}
                  disabled={planItems.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
            <div>
              <Button variant="secondary" onClick={() => setPlanItems((current) => addPlanItem(current))}>
                添加动作明细
              </Button>
            </div>
          </div>

          {planError && <p className="text-sm text-danger">{planError}</p>}
          {planHint && <p className="text-sm text-success">{planHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitPlan()} loading={planSaving} disabled={!patientId}>
              创建计划
            </Button>
          </div>

          {plans.length === 0 ? (
            <EmptyState icon="📋" title="暂无训练计划" description="计划与明细复用 nursing_plans/plan_items" />
          ) : (
            <Table columns={planColumns} data={plans} />
          )}
        </div>
      </Card>

      {/* ── B2 打卡与进度 ───────────────────────────────────────────── */}
      <Card
        title={`训练打卡与进度（B2${selectedPlan ? ` · ${selectedPlan.plan_name}` : ""}）`}
        actions={
          <span className="text-sm text-fg-muted">
            {selectedPlan ? "进度按该计划全部打卡读时汇总" : "请先在上表选择计划"}
          </span>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[170px]">
              <Input
                label="训练日期"
                type="date"
                value={sessionDate}
                onChange={(event) => setSessionDate(event.target.value)}
              />
            </div>
            <div className="w-[140px]">
              <Input
                label="完成度（0–1）"
                value={sessionRatio}
                onChange={(event) => setSessionRatio(event.target.value)}
              />
            </div>
            <div className="min-w-[180px] flex-1">
              <Input
                label="表现（可选）"
                value={sessionPerformance}
                onChange={(event) => setSessionPerformance(event.target.value)}
              />
            </div>
            <div className="min-w-[180px] flex-1">
              <Input label="备注（可选）" value={sessionNote} onChange={(event) => setSessionNote(event.target.value)} />
            </div>
            <Button onClick={() => void submitSession()} loading={sessionSaving} disabled={!selectedPlanId}>
              记录打卡
            </Button>
          </div>

          {progress && (
            <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-alt/40 p-3">
              <div className="flex flex-wrap items-center gap-3">
                <span className="text-sm font-medium text-fg-emphasis">{progressHeadline(progress)}</span>
                <Badge variant={progress.progress_ratio >= 1 ? "success" : "info"}>
                  {formatPercent(progress.progress_ratio)}
                </Badge>
              </div>
              <div className="h-2 w-full rounded-full bg-surface-alt">
                <div
                  className="h-2 rounded-full bg-accent/60"
                  style={{ width: `${progressBarPercent(progress.progress_ratio)}%` }}
                />
              </div>
              <p className="text-xs text-fg-dimmed">{progressNotice(progress)}</p>
            </div>
          )}

          {sessionError && <p className="text-sm text-danger">{sessionError}</p>}
          {sessionHint && <p className="text-sm text-success">{sessionHint}</p>}

          {!selectedPlanId ? (
            <EmptyState icon="✅" title="尚未选择训练计划" description="选择计划后展示打卡记录与完成率" />
          ) : sessionsLoading ? (
            <div className="flex justify-center py-10">
              <LoadingSpinner />
            </div>
          ) : sessions.length === 0 ? (
            <EmptyState icon="✅" title="暂无打卡记录" description="每次训练记一条会话，完成度取 0–1" />
          ) : (
            <Table columns={sessionColumns} data={sessions} />
          )}
        </div>
      </Card>

      {/* ── B3 里程碑四态 ───────────────────────────────────────────── */}
      <Card
        title="发育里程碑四态（B3，读时派生）"
        actions={milestoneStatus ? <span className="text-sm text-fg-muted">{milestoneHeadline(milestoneStatus)}</span> : undefined}
      >
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[240px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-milestone">
                里程碑
              </label>
              <select
                id="rehab-milestone"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={milestoneCode}
                onChange={(event) => setMilestoneCode(event.target.value)}
              >
                <option value="">请选择</option>
                {milestoneItems.map((item) => (
                  <option key={item.milestone_code} value={item.milestone_code}>
                    {item.milestone_name}（{item.state} · {milestoneExpectedLabel(item)}）
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[170px]">
              <Input
                label="达成日期"
                type="date"
                value={milestoneAchievedAt}
                onChange={(event) => setMilestoneAchievedAt(event.target.value)}
              />
            </div>
            <Button onClick={() => void submitMilestone()} loading={milestoneSaving} disabled={!patientId}>
              登记达成
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            四态（未达/临近/已达成/逾期）由服务端按**当前月龄**读时派生，同一{entity.person}跨月再查会变化；
            清空达成日期并提交表示「未达成」。
          </p>
          {milestoneError && <p className="text-sm text-danger">{milestoneError}</p>}
          {milestoneHint && <p className="text-sm text-success">{milestoneHint}</p>}

          {milestoneItems.length === 0 ? (
            <EmptyState icon="🧩" title="暂无里程碑数据" description={`选择${entity.person}后展示常量表四态`} />
          ) : (
            <div className="flex flex-col gap-2">
              {milestoneItems.map((item) => (
                <div
                  key={item.milestone_code}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={milestoneStateVariant(item.state)}>{item.state}</Badge>
                    <span className="text-sm text-fg">{item.milestone_name}</span>
                    <span className="text-xs text-fg-muted">
                      {item.milestone_code} · {milestoneExpectedLabel(item)}
                    </span>
                  </div>
                  <span className="text-xs text-fg-muted">{milestoneAchievedLabel(item)}</span>
                </div>
              ))}
            </div>
          )}

          {milestoneRecords.length > 0 && (
            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">登记台账（{milestoneRecords.length}）</span>
              <span className="text-xs text-fg-muted">
                {milestoneRecords
                  .map(
                    (record) =>
                      `${record.milestone_name}：${record.status}${record.achieved_at ? ` ${record.achieved_at}` : ""}`,
                  )
                  .join("；")}
              </span>
            </div>
          )}
        </div>
      </Card>

      {/* ── B4 手册分发与签收 ───────────────────────────────────────── */}
      <Card title="家庭训练指导手册分发与签收（B4）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[260px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-handout-template">
                手册模板
              </label>
              <select
                id="rehab-handout-template"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={templateCode}
                onChange={(event) => {
                  const next = templates.find((template) => template.template_code === event.target.value);
                  setTemplateCode(event.target.value);
                  setHandoutContent(next?.content ?? "");
                }}
              >
                <option value="">请选择</option>
                {templates.map((template) => (
                  <option key={template.template_code} value={template.template_code}>
                    {template.template_name}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-handout-channel">
                分发渠道
              </label>
              <select
                id="rehab-handout-channel"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={handoutChannel}
                onChange={(event) => setHandoutChannel(event.target.value)}
              >
                {HANDOUT_CHANNEL_SUGGESTIONS.map((channel) => (
                  <option key={channel} value={channel}>
                    {channel}
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[200px]">
              <Input
                label="分发时间"
                type="datetime-local"
                value={deliveredAt}
                onChange={(event) => setDeliveredAt(event.target.value)}
              />
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="rehab-handout-content">
              指导内容（可编辑；缺省用模板正文）
            </label>
            <textarea
              id="rehab-handout-content"
              className="min-h-[120px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={handoutContent}
              onChange={(event) => setHandoutContent(event.target.value)}
            />
          </div>

          {handoutError && <p className="text-sm text-danger">{handoutError}</p>}
          {handoutHint && <p className="text-sm text-success">{handoutHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitHandout()} loading={handoutSaving} disabled={!patientId}>
              分发手册
            </Button>
          </div>

          {handouts.length === 0 ? (
            <EmptyState icon="📖" title="暂无分发台账" description="分发后在此登记签收（单向，不可回退）" />
          ) : (
            <Table columns={handoutColumns} data={handouts} />
          )}
        </div>
      </Card>
    </div>
  );
}
