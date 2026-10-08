import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  appendChildcareSafetyInspectionItem,
  closeChildcareSafetyInspection,
  createChildcareActivityLog,
  createChildcareBehaviorObservation,
  createChildcareClassSchedule,
  createChildcareSafetyInspection,
  listChildcareActivityLogs,
  listChildcareBehaviorObservations,
  listChildcareClassSchedules,
  listChildcareSafetyInspections,
  listPatients,
  updateChildcareClassScheduleStatus,
  type ChildcareActivityLog,
  type ChildcareBehaviorObservation,
  type ChildcareClassSchedule,
  type ChildcareSafetyInspection,
  type ChildcareSafetyInspectionItem,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDateTime, nowLocalInput, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY, currentEntityLabels } from "../lib/domain";
import { useDomain } from "../lib/useDomain";
import {
  BEHAVIOR_LEVELS,
  CHECK_ITEMS,
  INSPECTION_RESULTS,
  INSPECTION_STATUSES,
  SCHEDULE_STATUSES,
  TIME_SLOTS,
  allowedScheduleTargets,
  behaviorLevelCount,
  behaviorLevelLabel,
  behaviorLevelVariant,
  canAppendInspectionItem,
  canCloseInspection,
  checkItemName,
  hazardItems,
  inspectionHeadline,
  inspectionItemLabel,
  inspectionResultVariant,
  inspectionStatusVariant,
  normalizeInspectionItem,
  optionalInstant,
  parseParticipantCount,
  scheduleStatusVariant,
  sortBehaviorObservations,
  validateActivityLogForm,
  validateAppendItemForm,
  validateBehaviorObservationForm,
  validateClassScheduleForm,
  validateSafetyInspectionForm,
} from "../lib/childcare";

/**
 * 保育员工作台（054 C3）。
 *
 * 消费 C1/C2 的冻结路由（§5.3），把保育员的日常链路收在一页：
 *   日常活动日志（C1）→ 行为观测（C1，append-only）→ 班级日程（C2，发布/取消）→
 *   环境安全巡查（C2，隐患闭环 待整改 →(追加整改项)→ 已整改 →(关闭)→ 已关闭）。
 *
 * 边界：
 * - 持久化与状态派生都在服务端；本页只做表单校验与展示（`lib/childcare.ts` 为纯函数）。
 * - **同时段冲突（409）与跳步关闭（409）的服务端文案原样透出**，前端不重写、不吞错；
 *   前端只隐藏明显非法的流转按钮，服务端才是唯一强制点。
 * - 行为观测 append-only：页面不提供修改入口。
 * - 入口显隐由 F1 机制按 `nursing:document` 控制；**后端仍是唯一强制点**，前端隐藏不替代 403。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

interface ChecklistRow {
  result: string;
  hazard: string;
}

export default function ChildcareWorkbenchPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [className, setClassName] = useState("");
  const [error, setError] = useState("");

  // —— C1 活动日志 ——
  const [activityName, setActivityName] = useState("");
  const [activityDate, setActivityDate] = useState(todayLocal());
  const [participantCount, setParticipantCount] = useState("");
  const [activityNote, setActivityNote] = useState("");
  const [logs, setLogs] = useState<ChildcareActivityLog[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [logSaving, setLogSaving] = useState(false);
  const [logHint, setLogHint] = useState("");
  const [logError, setLogError] = useState("");

  // —— C1 行为观测 ——
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [level, setLevel] = useState("");
  const [observedAt, setObservedAt] = useState(nowLocalInput());
  const [observations, setObservations] = useState<ChildcareBehaviorObservation[]>([]);
  const [levelFilter, setLevelFilter] = useState("");
  const [observationsLoading, setObservationsLoading] = useState(false);
  const [behaviorSaving, setBehaviorSaving] = useState(false);
  const [behaviorHint, setBehaviorHint] = useState("");
  const [behaviorError, setBehaviorError] = useState("");

  // —— C2 班级日程 ——
  const [scheduleDate, setScheduleDate] = useState(todayLocal());
  const [timeSlot, setTimeSlot] = useState<string>(TIME_SLOTS[0]);
  const [scheduleActivity, setScheduleActivity] = useState("");
  const [schedules, setSchedules] = useState<ChildcareClassSchedule[]>([]);
  const [scheduleStatusFilter, setScheduleStatusFilter] = useState("");
  const [schedulesLoading, setSchedulesLoading] = useState(false);
  const [scheduleSaving, setScheduleSaving] = useState(false);
  const [scheduleHint, setScheduleHint] = useState("");
  const [scheduleError, setScheduleError] = useState("");
  const [transitioningId, setTransitioningId] = useState("");

  // —— C2 安全巡查 ——
  const [area, setArea] = useState("");
  const [inspectedAt, setInspectedAt] = useState(nowLocalInput());
  const [checklist, setChecklist] = useState<Record<string, ChecklistRow>>({});
  const [inspections, setInspections] = useState<ChildcareSafetyInspection[]>([]);
  const [inspectionStatusFilter, setInspectionStatusFilter] = useState("");
  const [inspectionsLoading, setInspectionsLoading] = useState(false);
  const [inspectionSaving, setInspectionSaving] = useState(false);
  const [inspectionHint, setInspectionHint] = useState("");
  const [inspectionError, setInspectionError] = useState("");
  const [selectedInspectionId, setSelectedInspectionId] = useState("");
  const [appendItemCode, setAppendItemCode] = useState("");
  const [appendResult, setAppendResult] = useState("");
  const [appendHazard, setAppendHazard] = useState("");
  const [appending, setAppending] = useState(false);
  const [closing, setClosing] = useState(false);

  const selected = useMemo(
    () => children.find((child) => child.id === patientId) ?? null,
    [children, patientId],
  );
  const selectedInspection = useMemo(
    () => inspections.find((inspection) => inspection.id === selectedInspectionId) ?? null,
    [inspections, selectedInspectionId],
  );
  const behaviorSummary = useMemo(() => behaviorLevelCount(observations), [observations]);

  /** 只提交已选择结果的检查项（未选的项不提交，不默认「合格」）。 */
  const checklistItems = useMemo<ChildcareSafetyInspectionItem[]>(
    () =>
      CHECK_ITEMS.filter((item) => (checklist[item.code]?.result ?? "") !== "").map((item) => ({
        item_code: item.code,
        result: checklist[item.code]?.result ?? "",
        hazard: (checklist[item.code]?.hazard ?? "").trim() === "" ? null : checklist[item.code]?.hazard?.trim(),
      })),
    [checklist],
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

  // —— 活动日志列表（按班级 + 日期过滤）——
  const loadLogs = useCallback(async () => {
    setLogsLoading(true);
    try {
      const response = await listChildcareActivityLogs({
        class_name: className.trim() === "" ? undefined : className.trim(),
        date: activityDate.trim() === "" ? undefined : activityDate.trim(),
        limit: PAGE_SIZE,
      });
      setLogs(response.records);
    } catch (cause) {
      setLogError(errorMessage(cause, "无法加载活动日志"));
    } finally {
      setLogsLoading(false);
    }
  }, [className, activityDate]);

  useEffect(() => {
    void loadLogs();
  }, [loadLogs]);

  // —— 行为观测列表（按儿童 + 级别过滤）——
  const loadObservations = useCallback(async () => {
    setObservationsLoading(true);
    try {
      const response = await listChildcareBehaviorObservations({
        patient_id: patientId.trim() === "" ? undefined : patientId.trim(),
        level: levelFilter.trim() === "" ? undefined : levelFilter.trim(),
        limit: PAGE_SIZE,
      });
      setObservations(response.records);
    } catch (cause) {
      setBehaviorError(errorMessage(cause, "无法加载行为观测"));
    } finally {
      setObservationsLoading(false);
    }
  }, [patientId, levelFilter]);

  useEffect(() => {
    void loadObservations();
  }, [loadObservations]);

  // —— 班级日程列表 ——
  const loadSchedules = useCallback(async () => {
    setSchedulesLoading(true);
    try {
      const response = await listChildcareClassSchedules({
        class_name: className.trim() === "" ? undefined : className.trim(),
        status: scheduleStatusFilter.trim() === "" ? undefined : scheduleStatusFilter.trim(),
        limit: PAGE_SIZE,
      });
      setSchedules(response.records);
    } catch (cause) {
      setScheduleError(errorMessage(cause, "无法加载班级日程"));
    } finally {
      setSchedulesLoading(false);
    }
  }, [className, scheduleStatusFilter]);

  useEffect(() => {
    void loadSchedules();
  }, [loadSchedules]);

  // —— 安全巡查列表 ——
  const loadInspections = useCallback(async () => {
    setInspectionsLoading(true);
    try {
      const response = await listChildcareSafetyInspections({
        area: area.trim() === "" ? undefined : area.trim(),
        status: inspectionStatusFilter.trim() === "" ? undefined : inspectionStatusFilter.trim(),
        limit: PAGE_SIZE,
      });
      setInspections(response.records);
      setSelectedInspectionId((current) =>
        current && response.records.some((inspection) => inspection.id === current)
          ? current
          : response.records[0]?.id ?? "",
      );
    } catch (cause) {
      setInspectionError(errorMessage(cause, "无法加载安全巡查记录"));
    } finally {
      setInspectionsLoading(false);
    }
  }, [area, inspectionStatusFilter]);

  useEffect(() => {
    void loadInspections();
  }, [loadInspections]);

  // —— 动作：登记活动日志 ——
  async function submitLog() {
    const validation = validateActivityLogForm({
      className,
      activityName,
      activityDate,
      participantCount,
    });
    if (validation) {
      setLogError(validation);
      return;
    }
    setLogSaving(true);
    setLogError("");
    setLogHint("");
    try {
      await createChildcareActivityLog({
        class_name: className.trim(),
        activity_name: activityName.trim(),
        activity_date: activityDate.trim() === "" ? undefined : activityDate.trim(),
        participant_count: parseParticipantCount(participantCount) ?? 0,
        patient_id: patientId.trim() === "" ? null : patientId.trim(),
        note: activityNote.trim() === "" ? null : activityNote.trim(),
      });
      setLogHint("已登记活动日志（班级级；关联儿童可选）");
      setActivityName("");
      setActivityNote("");
      await loadLogs();
    } catch (cause) {
      setLogError(errorMessage(cause, "登记活动日志失败"));
    } finally {
      setLogSaving(false);
    }
  }

  // —— 动作：登记行为观测（append-only）——
  async function submitObservation() {
    const validation = validateBehaviorObservationForm({
      patientId,
      category,
      description,
      level,
      observedAt,
    });
    if (validation) {
      setBehaviorError(validation);
      return;
    }
    setBehaviorSaving(true);
    setBehaviorError("");
    setBehaviorHint("");
    try {
      await createChildcareBehaviorObservation({
        patient_id: patientId.trim(),
        category: category.trim(),
        description: description.trim(),
        level: level.trim() === "" ? null : level.trim(),
        observed_at: optionalInstant(observedAt),
      });
      setBehaviorHint("已登记行为观测（append-only，不可修改）");
      setCategory("");
      setDescription("");
      await loadObservations();
    } catch (cause) {
      setBehaviorError(errorMessage(cause, "登记行为观测失败"));
    } finally {
      setBehaviorSaving(false);
    }
  }

  // —— 动作：排课 ——
  async function submitSchedule() {
    const validation = validateClassScheduleForm({
      className,
      scheduleDate,
      timeSlot,
      activityName: scheduleActivity,
    });
    if (validation) {
      setScheduleError(validation);
      return;
    }
    setScheduleSaving(true);
    setScheduleError("");
    setScheduleHint("");
    try {
      await createChildcareClassSchedule({
        class_name: className.trim(),
        schedule_date: scheduleDate,
        time_slot: timeSlot,
        activity_name: scheduleActivity.trim(),
      });
      setScheduleHint("已排课（初始状态：草稿；发布是显式动作）");
      setScheduleActivity("");
      await loadSchedules();
    } catch (cause) {
      // 同时段冲突 → 409「该班级该日期该时段已有日程（同时段冲突）」：原样透出
      setScheduleError(errorMessage(cause, "排课失败"));
    } finally {
      setScheduleSaving(false);
    }
  }

  // —— 动作：日程状态流转（发布/取消）——
  async function transitionSchedule(schedule: ChildcareClassSchedule, target: string) {
    setTransitioningId(schedule.id);
    setScheduleError("");
    setScheduleHint("");
    try {
      await updateChildcareClassScheduleStatus(schedule.id, target);
      setScheduleHint(`日程已流转为「${target}」`);
      await loadSchedules();
    } catch (cause) {
      // 非法流转 → 409：原样透出
      setScheduleError(errorMessage(cause, "日程状态流转失败"));
    } finally {
      setTransitioningId("");
    }
  }

  // —— 动作：创建安全巡查 ——
  async function submitInspection() {
    const validation = validateSafetyInspectionForm({ area, items: checklistItems });
    if (validation) {
      setInspectionError(validation);
      return;
    }
    setInspectionSaving(true);
    setInspectionError("");
    setInspectionHint("");
    try {
      const created = await createChildcareSafetyInspection({
        area: area.trim(),
        inspected_at: optionalInstant(inspectedAt),
        items: checklistItems,
      });
      setInspectionHint(
        `已创建巡查记录（状态：${created.status}；不合格 ${hazardItems(created.items).length} 项）`,
      );
      setChecklist({});
      await loadInspections();
      setSelectedInspectionId(created.id);
    } catch (cause) {
      setInspectionError(errorMessage(cause, "创建巡查记录失败"));
    } finally {
      setInspectionSaving(false);
    }
  }

  // —— 动作：追加整改项（待整改 → 已整改）——
  async function submitAppendItem() {
    if (!selectedInspection) return;
    const validation = validateAppendItemForm({ itemCode: appendItemCode, result: appendResult });
    if (validation) {
      setInspectionError(validation);
      return;
    }
    setAppending(true);
    setInspectionError("");
    setInspectionHint("");
    try {
      await appendChildcareSafetyInspectionItem(
        selectedInspection.id,
        normalizeInspectionItem({ itemCode: appendItemCode, result: appendResult, hazard: appendHazard }),
      );
      setInspectionHint("已追加整改项（「待整改」会顺带流转到「已整改」）");
      setAppendItemCode("");
      setAppendResult("");
      setAppendHazard("");
      await loadInspections();
    } catch (cause) {
      // 已关闭 → 409：原样透出
      setInspectionError(errorMessage(cause, "追加整改项失败"));
    } finally {
      setAppending(false);
    }
  }

  // —— 动作：关闭隐患（仅 已整改 → 已关闭）——
  async function submitClose() {
    if (!selectedInspection) return;
    setClosing(true);
    setInspectionError("");
    setInspectionHint("");
    try {
      await closeChildcareSafetyInspection(selectedInspection.id);
      setInspectionHint("已关闭隐患（单向：已整改 → 已关闭）");
      await loadInspections();
    } catch (cause) {
      // 待整改跳步 → 409：原样透出
      setInspectionError(errorMessage(cause, "关闭隐患失败"));
    } finally {
      setClosing(false);
    }
  }

  const logColumns: Column<ChildcareActivityLog>[] = [
    { key: "activity_date", header: "日期", render: (row) => row.activity_date },
    { key: "class_name", header: "班级", render: (row) => row.class_name },
    { key: "activity_name", header: "活动", render: (row) => row.activity_name },
    { key: "participant_count", header: "参与人数", render: (row) => String(row.participant_count) },
    {
      key: "patient",
      header: "关联儿童",
      render: (row) => row.patient_id ?? "班级级",
    },
    { key: "note", header: "备注", render: (row) => row.note ?? "-" },
    { key: "operator", header: "记录人", render: (row) => row.operator ?? "-" },
    { key: "created_at", header: "登记时间", render: (row) => formatDateTime(row.created_at) },
  ];

  const observationColumns: Column<ChildcareBehaviorObservation>[] = [
    { key: "observed_at", header: "观测时刻", render: (row) => formatDateTime(row.observed_at) },
    { key: "category", header: "分类", render: (row) => row.category },
    {
      key: "level",
      header: "级别",
      render: (row) => (
        <Badge variant={behaviorLevelVariant(row.level)}>{behaviorLevelLabel(row.level)}</Badge>
      ),
    },
    { key: "description", header: "描述", render: (row) => row.description },
    { key: "operator", header: "记录人", render: (row) => row.operator ?? "-" },
    { key: "created_at", header: "登记时间", render: (row) => formatDateTime(row.created_at) },
  ];

  const scheduleColumns: Column<ChildcareClassSchedule>[] = [
    { key: "schedule_date", header: "日期", render: (row) => row.schedule_date },
    { key: "time_slot", header: "时段", render: (row) => row.time_slot },
    { key: "class_name", header: "班级", render: (row) => row.class_name },
    { key: "activity_name", header: "活动", render: (row) => row.activity_name },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={scheduleStatusVariant(row.status)}>{row.status}</Badge>,
    },
    {
      key: "actions",
      header: "流转",
      render: (row) => {
        const targets = allowedScheduleTargets(row.status);
        if (targets.length === 0) return <span className="text-xs text-fg-dimmed">终态</span>;
        return (
          <div className="flex flex-wrap gap-1.5">
            {targets.map((target) => (
              <Button
                key={target}
                size="sm"
                variant={target === "已取消" ? "ghost" : "secondary"}
                loading={transitioningId === row.id}
                onClick={() => void transitionSchedule(row, target)}
              >
                {target}
              </Button>
            ))}
          </div>
        );
      },
    },
  ];

  const inspectionColumns: Column<ChildcareSafetyInspection>[] = [
    { key: "area", header: "区域", render: (row) => row.area },
    { key: "inspected_at", header: "巡查时刻", render: (row) => formatDateTime(row.inspected_at) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={inspectionStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "summary", header: "结果", render: (row) => inspectionHeadline(row) },
    { key: "operator", header: "巡查人", render: (row) => row.operator ?? "-" },
    {
      key: "select",
      header: "明细",
      render: (row) => (
        <Button
          size="sm"
          variant={selectedInspectionId === row.id ? "primary" : "ghost"}
          onClick={() => setSelectedInspectionId(row.id)}
        >
          查看
        </Button>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`保育员工作台（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[180px] flex-1">
            <Input
              label="班级（活动日志与日程按班级）"
              value={className}
              placeholder="如：小班一班"
              onChange={(event) => setClassName(event.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="childcare-patient">
              选择{entity.person}（行为观测必选；活动日志可选）
            </label>
            <select
              id="childcare-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setBehaviorHint("");
              }}
            >
              <option value="">不关联（班级级）</option>
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

      {/* ── C1 活动日志 ─────────────────────────────────────────────── */}
      <Card title="日常活动日志（C1）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[200px] flex-1">
              <Input
                label="活动名称"
                value={activityName}
                placeholder="如：户外集体游戏"
                onChange={(event) => setActivityName(event.target.value)}
              />
            </div>
            <div className="w-[160px]">
              <Input
                label="活动日期"
                type="date"
                value={activityDate}
                onChange={(event) => setActivityDate(event.target.value)}
              />
            </div>
            <div className="w-[140px]">
              <Input
                label="参与人数"
                value={participantCount}
                placeholder="0"
                onChange={(event) => setParticipantCount(event.target.value)}
              />
            </div>
            <div className="min-w-[200px] flex-1">
              <Input
                label="备注（可选）"
                value={activityNote}
                onChange={(event) => setActivityNote(event.target.value)}
              />
            </div>
            <Button onClick={() => void submitLog()} loading={logSaving} disabled={className.trim() === ""}>
              登记日志
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            班级级日志不关联儿童（上方儿童选择留空即可）；参与人数缺省 0，日期缺省为当天。
          </p>
          {logError && <p className="text-sm text-danger">{logError}</p>}
          {logHint && <p className="text-sm text-success">{logHint}</p>}
          {logsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : logs.length === 0 ? (
            <EmptyState icon="🧸" title="暂无活动日志" description="按班级与日期登记后在此展示" />
          ) : (
            <Table columns={logColumns} data={logs} />
          )}
        </div>
      </Card>

      {/* ── C1 行为观测（append-only）────────────────────────────────── */}
      <Card
        title="行为观测（C1，append-only）"
        actions={
          <div className="flex items-center gap-2">
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={levelFilter}
              onChange={(event) => setLevelFilter(event.target.value)}
            >
              <option value="">全部级别</option>
              {BEHAVIOR_LEVELS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <span className="text-xs text-fg-muted">
              需干预 {behaviorSummary.需干预} / 关注 {behaviorSummary.关注} / 一般 {behaviorSummary.一般}
            </span>
          </div>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[160px] flex-1">
              <Input
                label="行为分类"
                value={category}
                placeholder="如：进餐 / 午睡 / 同伴交往"
                onChange={(event) => setCategory(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="childcare-level">
                级别
              </label>
              <select
                id="childcare-level"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={level}
                onChange={(event) => setLevel(event.target.value)}
              >
                <option value="">不填</option>
                {BEHAVIOR_LEVELS.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[210px]">
              <Input
                label="观测时刻"
                type="datetime-local"
                value={observedAt}
                onChange={(event) => setObservedAt(event.target.value)}
              />
            </div>
            <div className="min-w-[220px] flex-1">
              <Input
                label="行为描述"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </div>
            <Button
              onClick={() => void submitObservation()}
              loading={behaviorSaving}
              disabled={patientId.trim() === ""}
            >
              登记观测
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            行为观测只新增、不可修改（append-only，响应无 `updated_at`）；级别白名单：一般 / 关注 / 需干预。
            列表按「需干预 → 关注 → 一般」排序，便于优先跟进。
          </p>
          {behaviorError && <p className="text-sm text-danger">{behaviorError}</p>}
          {behaviorHint && <p className="text-sm text-success">{behaviorHint}</p>}
          {observationsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : observations.length === 0 ? (
            <EmptyState icon="👀" title="暂无行为观测" description="选择儿童后登记观测记录" />
          ) : (
            <Table columns={observationColumns} data={sortBehaviorObservations(observations)} />
          )}
        </div>
      </Card>

      {/* ── C2 班级日程 ─────────────────────────────────────────────── */}
      <Card
        title="班级日程（C2）"
        actions={
          <select
            className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
            value={scheduleStatusFilter}
            onChange={(event) => setScheduleStatusFilter(event.target.value)}
          >
            <option value="">全部状态</option>
            {SCHEDULE_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[160px]">
              <Input
                label="日程日期"
                type="date"
                value={scheduleDate}
                onChange={(event) => setScheduleDate(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="childcare-slot">
                时段（固定 8 个）
              </label>
              <select
                id="childcare-slot"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={timeSlot}
                onChange={(event) => setTimeSlot(event.target.value)}
              >
                {TIME_SLOTS.map((slot) => (
                  <option key={slot} value={slot}>
                    {slot}
                  </option>
                ))}
              </select>
            </div>
            <div className="min-w-[220px] flex-1">
              <Input
                label="活动名称"
                value={scheduleActivity}
                placeholder="如：音乐律动"
                onChange={(event) => setScheduleActivity(event.target.value)}
              />
            </div>
            <Button
              onClick={() => void submitSchedule()}
              loading={scheduleSaving}
              disabled={className.trim() === ""}
            >
              排课
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            新建日程一律为「草稿」，发布是显式动作；同班级同日期同「未取消」时段唯一——
            重复排课由服务端返回 409（文案原样透出）。取消后时段释放，可重新排课。
          </p>
          {scheduleError && <p className="text-sm text-danger">{scheduleError}</p>}
          {scheduleHint && <p className="text-sm text-success">{scheduleHint}</p>}
          {schedulesLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : schedules.length === 0 ? (
            <EmptyState icon="🗓️" title="暂无班级日程" description="按班级与固定时段排课" />
          ) : (
            <Table columns={scheduleColumns} data={schedules} />
          )}
        </div>
      </Card>

      {/* ── C2 环境安全巡查 ─────────────────────────────────────────── */}
      <Card
        title="环境安全巡查（C2，12 项检查项）"
        actions={
          <select
            className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
            value={inspectionStatusFilter}
            onChange={(event) => setInspectionStatusFilter(event.target.value)}
          >
            <option value="">全部状态</option>
            {INSPECTION_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[200px] flex-1">
              <Input
                label="巡查区域"
                value={area}
                placeholder="如：小班教室 / 户外活动区"
                onChange={(event) => setArea(event.target.value)}
              />
            </div>
            <div className="w-[210px]">
              <Input
                label="巡查时刻"
                type="datetime-local"
                value={inspectedAt}
                onChange={(event) => setInspectedAt(event.target.value)}
              />
            </div>
            <span className="text-sm text-fg-muted">已选结果 {checklistItems.length} / 12 项</span>
            <Button onClick={() => void submitInspection()} loading={inspectionSaving} disabled={area.trim() === ""}>
              创建巡查
            </Button>
          </div>

          <div className="flex flex-col gap-2">
            {CHECK_ITEMS.map((item) => {
              const row = checklist[item.code] ?? { result: "", hazard: "" };
              return (
                <div key={item.code} className="flex flex-wrap items-center gap-3">
                  <span className="min-w-[220px] flex-1 text-sm text-fg">
                    {item.name}
                    <span className="ml-2 text-xs text-fg-dimmed">{item.code}</span>
                  </span>
                  <select
                    aria-label={`${item.name}检查结果`}
                    className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                    value={row.result}
                    onChange={(event) =>
                      setChecklist((current) => ({
                        ...current,
                        [item.code]: { ...row, result: event.target.value },
                      }))
                    }
                  >
                    <option value="">未检查</option>
                    {INSPECTION_RESULTS.map((result) => (
                      <option key={result} value={result}>
                        {result}
                      </option>
                    ))}
                  </select>
                  <div className="min-w-[220px] flex-1">
                    <Input
                      label={undefined}
                      placeholder="隐患描述（不合格时填写）"
                      value={row.hazard}
                      onChange={(event) =>
                        setChecklist((current) => ({
                          ...current,
                          [item.code]: { ...row, hazard: event.target.value },
                        }))
                      }
                    />
                  </div>
                </div>
              );
            })}
          </div>
          <p className="text-xs text-fg-dimmed">
            未选择结果的项目不提交（不默认「合格」，避免掩盖真实隐患）；结果白名单：合格 / 不合格 / 不适用。
            创建后状态为「待整改」，闭环为「待整改 →(追加整改项)→ 已整改 →(关闭)→ 已关闭」。
          </p>
          {inspectionError && <p className="text-sm text-danger">{inspectionError}</p>}
          {inspectionHint && <p className="text-sm text-success">{inspectionHint}</p>}

          {inspectionsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : inspections.length === 0 ? (
            <EmptyState icon="🛡️" title="暂无巡查记录" description="创建巡查后在此闭环隐患" />
          ) : (
            <Table columns={inspectionColumns} data={inspections} />
          )}
        </div>
      </Card>

      {/* ── C2 巡查明细与隐患闭环 ───────────────────────────────────── */}
      {selectedInspection && (
        <Card
          title={`巡查明细：${selectedInspection.area}（${selectedInspection.status}）`}
          actions={<Badge variant={inspectionStatusVariant(selectedInspection.status)}>{selectedInspection.status}</Badge>}
        >
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-3 text-sm text-fg-muted">
              <span>{inspectionHeadline(selectedInspection)}</span>
              <span>巡查时刻 {formatDateTime(selectedInspection.inspected_at)}</span>
              <span>巡查人 {selectedInspection.operator ?? "-"}</span>
            </div>

            {selectedInspection.items.length === 0 ? (
              <p className="text-sm text-fg-muted">暂无检查项记录；可在下方追加。</p>
            ) : (
              <div className="flex flex-col gap-2">
                {selectedInspection.items.map((item, index) => (
                  <div
                    key={`${item.item_code}-${index}`}
                    className={`flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2 ${
                      item.result === "不合格" ? "border-danger bg-danger-bg/40" : "border-border"
                    }`}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant={inspectionResultVariant(item.result)}>{item.result}</Badge>
                      <span className="text-sm text-fg">{checkItemName(item.item_code)}</span>
                      <span className="text-xs text-fg-dimmed">{item.item_code}</span>
                    </div>
                    {item.hazard ? <span className="text-xs text-danger">隐患：{item.hazard}</span> : null}
                  </div>
                ))}
              </div>
            )}

            <div className="flex flex-col gap-2">
              <span className="text-sm font-medium text-fg-emphasis">追加检查/整改项</span>
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex flex-col gap-1.5 min-w-[240px]">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="childcare-append-item">
                    检查项
                  </label>
                  <select
                    id="childcare-append-item"
                    className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                    value={appendItemCode}
                    onChange={(event) => setAppendItemCode(event.target.value)}
                  >
                    <option value="">请选择</option>
                    {CHECK_ITEMS.map((item) => (
                      <option key={item.code} value={item.code}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="childcare-append-result">
                    结果
                  </label>
                  <select
                    id="childcare-append-result"
                    className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                    value={appendResult}
                    onChange={(event) => setAppendResult(event.target.value)}
                  >
                    <option value="">请选择</option>
                    {INSPECTION_RESULTS.map((result) => (
                      <option key={result} value={result}>
                        {result}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="min-w-[220px] flex-1">
                  <Input
                    label="隐患描述（可选）"
                    value={appendHazard}
                    onChange={(event) => setAppendHazard(event.target.value)}
                  />
                </div>
                <Button
                  onClick={() => void submitAppendItem()}
                  loading={appending}
                  disabled={!canAppendInspectionItem(selectedInspection.status)}
                >
                  追加
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => void submitClose()}
                  loading={closing}
                  disabled={!canCloseInspection(selectedInspection.status)}
                >
                  关闭隐患
                </Button>
              </div>
              <p className="text-xs text-fg-dimmed">
                「待整改」追加整改项会顺带流转到「已整改」；「已关闭」不可再追加（服务端 409）。
                关闭仅允许「已整改 → 已关闭」，待整改跳步关闭由服务端 409 兜底，文案原样透出。
              </p>
              {selectedInspection.status === "待整改" && hazardItems(selectedInspection.items).length > 0 && (
                <p className="text-xs text-warning">
                  当前仍为「待整改」：需先追加整改项流转到「已整改」后才能关闭。
                </p>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">检查项明细（{selectedInspection.items.length}）</span>
              <span className="text-xs text-fg-muted">
                {selectedInspection.items.map(inspectionItemLabel).join("；") || "暂无"}
              </span>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}
