import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  createMealIntakeRecord,
  createSpecialDietRequest,
  generateNutritionWeeklyReport,
  getNutritionAnalysis,
  listDietProfiles,
  listMealIntakeRecords,
  listNutritionWeeklyReports,
  listPatients,
  listSpecialDietRequests,
  upsertDietProfileAllergensByPatient,
  updateSpecialDietRequestStatus,
  type DietProfile,
  type MealIntakeItem,
  type MealIntakeRecord,
  type NutritionAnalysis,
  type NutritionWeeklyReport,
  type Patient,
  type SpecialDietRequest,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, formatDateTime, todayLocal, weekStartOf } from "../lib/datetime";
import { DOMAIN_ENTITY, currentEntityLabels } from "../lib/domain";
import { useDomain } from "../lib/useDomain";
import {
  MEAL_TIMES,
  SPECIAL_DIET_REQUEST_TYPES,
  addFoodItem,
  allergenOverwriteNotice,
  allergenProfileNotice,
  allergenSavedHint,
  allowedSpecialDietTargets,
  compositionSummary,
  dailyEnergyPercent,
  defaultMealTime,
  emptyFoodItem,
  formatAgeBand,
  formatAllergens,
  formatNutrientValue,
  formatNutrientWithUnit,
  formatRatio,
  maxDailyEnergy,
  normalizeFoodItems,
  nutrientRows,
  parseAllergenText,
  parseRatioInput,
  referenceVersionLabel,
  removeFoodItem,
  specialDietStatusVariant,
  totalAmountG,
  updateFoodItem,
  validateIntakeForm,
  weekRangeLabel,
  weeklyAverageRows,
  weeklyHeadline,
  type MealTime,
  type NutrientRow,
  type SpecialDietRequestType,
} from "../lib/nutrition";

/**
 * 营养师工作台（054 A6）。
 *
 * 消费 A1–A5 的冻结路由（§5.1），把营养师的日常链路收在一页：
 *   摄入登记（A1，同儿童同餐次幂等覆盖）→ 营养分析（A3，读时聚合）→
 *   过敏原标记（A4b，按 patient 覆盖式写 `dining_diet_profiles.allergies`，
 *   儿童无入住亦可 upsert 最小启用档案）→
 *   特殊膳食申请与审批流转（A4 状态机）→ 营养周报生成与查看（A5，同周覆盖）。
 *
 * 边界：
 * - 结论、缺口项、参考值、营养素汇总**一律服务端派生**；本页不重算、不提交这些字段
 *   （`lib/nutrition.ts` 只做校验与格式化）。
 * - 参考值是**决策支持提示**，不是诊断/处方；展示时始终带 `reference_version` 口径标注。
 * - 入口显隐由 F1 机制按 `dining:manage` 控制；**后端仍是唯一强制点**（无码直接请求 403），
 *   前端隐藏不替代 403。
 * - 状态机流转按钮只渲染合法目标，非法流转仍由服务端 400 兜底。
 * - 过敏原写入是**覆盖式（整体替换，非追加）**；非儿童无启用档案时服务端 400，
 *   其文案原样透出，前端不改写、不吞错。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function DietitianWorkbenchPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [intakeDate, setIntakeDate] = useState(todayLocal());
  const [mealTime, setMealTime] = useState<MealTime>(() => defaultMealTime(new Date().getHours()));
  const [error, setError] = useState("");

  // —— 摄入登记 ——
  const [items, setItems] = useState<MealIntakeItem[]>([emptyFoodItem()]);
  const [ratioText, setRatioText] = useState("1");
  const [remark, setRemark] = useState("");
  const [savingIntake, setSavingIntake] = useState(false);
  const [intakeHint, setIntakeHint] = useState("");
  const [intakeError, setIntakeError] = useState("");
  const [records, setRecords] = useState<MealIntakeRecord[]>([]);
  const [recordsTotal, setRecordsTotal] = useState(0);
  const [recordsPage, setRecordsPage] = useState(1);
  const [recordsLoading, setRecordsLoading] = useState(false);

  // —— 营养分析 ——
  const [analysisMeal, setAnalysisMeal] = useState<"全部" | MealTime>("全部");
  const [analysis, setAnalysis] = useState<NutritionAnalysis | null>(null);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisError, setAnalysisError] = useState("");

  // —— 过敏原标记（A4b：按 patient 覆盖式写入，儿童无入住亦可）——
  const [dietProfiles, setDietProfiles] = useState<DietProfile[]>([]);
  const [allergenText, setAllergenText] = useState("");
  const [allergenSaving, setAllergenSaving] = useState(false);
  const [allergenHint, setAllergenHint] = useState("");
  const [allergenError, setAllergenError] = useState("");

  // —— 特殊膳食申请 ——
  const [requests, setRequests] = useState<SpecialDietRequest[]>([]);
  const [requestType, setRequestType] = useState<SpecialDietRequestType>("过敏回避");
  const [requestMeal, setRequestMeal] = useState<MealTime>("午餐");
  const [requestAllergenText, setRequestAllergenText] = useState("");
  const [requestReason, setRequestReason] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [effectiveTo, setEffectiveTo] = useState("");
  const [requestSaving, setRequestSaving] = useState(false);
  const [requestHint, setRequestHint] = useState("");
  const [requestError, setRequestError] = useState("");
  const [transitioningId, setTransitioningId] = useState("");

  // —— 营养周报 ——
  const [reports, setReports] = useState<NutritionWeeklyReport[]>([]);
  const [reportWeek, setReportWeek] = useState(() => weekStartOf(todayLocal()));
  const [reportSaving, setReportSaving] = useState(false);
  const [reportHint, setReportHint] = useState("");
  const [reportError, setReportError] = useState("");
  const [selectedReportId, setSelectedReportId] = useState("");

  const selected = useMemo(
    () => children.find((child) => child.id === patientId) ?? null,
    [children, patientId],
  );
  /** 儿童允许无入住建档（A4b）；非儿童无启用档案时保存会 400（服务端文案原样透出） */
  const isChild = selected?.person_type === "儿童";
  const selectedReport = useMemo(
    () => reports.find((report) => report.id === selectedReportId) ?? reports[0] ?? null,
    [reports, selectedReportId],
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
        // 依赖数组为 []：兜底文案走 currentEntityLabels()，避免闭包锁死首屏的域
        if (active) setError(errorMessage(cause, `无法加载${currentEntityLabels().person}列表`));
      });
    return () => {
      active = false;
    };
  }, []);

  // —— 摄入记录（按儿童 + 日期）——
  const loadRecords = useCallback(async () => {
    if (!patientId) {
      setRecords([]);
      setRecordsTotal(0);
      return;
    }
    setRecordsLoading(true);
    try {
      const response = await listMealIntakeRecords({
        patient_id: patientId,
        date: intakeDate,
        limit: PAGE_SIZE,
        offset: (recordsPage - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setRecordsTotal(response.meta.total);
    } catch (cause) {
      setError(errorMessage(cause, "无法加载摄入记录"));
    } finally {
      setRecordsLoading(false);
    }
  }, [patientId, intakeDate, recordsPage]);

  useEffect(() => {
    void loadRecords();
  }, [loadRecords]);

  // —— 营养分析（按儿童 + 日期 + 餐次；缺省「全部」）——
  const loadAnalysis = useCallback(async () => {
    if (!patientId) {
      setAnalysis(null);
      return;
    }
    setAnalysisLoading(true);
    try {
      const response = await getNutritionAnalysis({
        patient_id: patientId,
        date: intakeDate,
        meal_time: analysisMeal === "全部" ? undefined : analysisMeal,
      });
      setAnalysis(response);
      setAnalysisError("");
    } catch (cause) {
      setAnalysis(null);
      setAnalysisError(errorMessage(cause, "无法加载营养分析"));
    } finally {
      setAnalysisLoading(false);
    }
  }, [patientId, intakeDate, analysisMeal]);

  useEffect(() => {
    void loadAnalysis();
  }, [loadAnalysis]);

  // —— 饮食档案（展示既有档案；写入走 patient 维度，不依赖档案已存在）——
  const loadDietProfiles = useCallback(async () => {
    if (!patientId) {
      setDietProfiles([]);
      setAllergenText("");
      return;
    }
    try {
      const response = await listDietProfiles({ patient_id: patientId, limit: 50 });
      setDietProfiles(response.records);
      const active =
        response.records.find((profile) => profile.status === "启用") ?? response.records[0] ?? null;
      setAllergenText(active ? active.allergies.join("、") : "");
    } catch (cause) {
      setAllergenError(errorMessage(cause, "无法加载饮食档案"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadDietProfiles();
  }, [loadDietProfiles]);

  // —— 特殊膳食申请台账 ——
  const loadRequests = useCallback(async () => {
    if (!patientId) {
      setRequests([]);
      return;
    }
    try {
      const response = await listSpecialDietRequests({ patient_id: patientId, limit: 50 });
      setRequests(response.records);
    } catch (cause) {
      setRequestError(errorMessage(cause, "无法加载特殊膳食申请"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadRequests();
  }, [loadRequests]);

  // —— 营养周报台账 ——
  const loadReports = useCallback(async () => {
    if (!patientId) {
      setReports([]);
      setSelectedReportId("");
      return;
    }
    try {
      const response = await listNutritionWeeklyReports({ patient_id: patientId, limit: 20 });
      setReports(response.records);
      setSelectedReportId(response.records[0]?.id ?? "");
    } catch (cause) {
      setReportError(errorMessage(cause, "无法加载营养周报"));
    }
  }, [patientId]);

  useEffect(() => {
    void loadReports();
  }, [loadReports]);

  // —— 动作：登记摄入（幂等覆盖）——
  async function submitIntake() {
    if (!patientId) return;
    const ratio = parseRatioInput(ratioText);
    const validation = validateIntakeForm(items, ratio);
    if (validation) {
      setIntakeError(validation);
      return;
    }
    setSavingIntake(true);
    setIntakeError("");
    setIntakeHint("");
    try {
      await createMealIntakeRecord({
        patient_id: patientId,
        intake_date: intakeDate,
        meal_time: mealTime,
        items: normalizeFoodItems(items),
        intake_ratio: ratio ?? 1,
        remark: remark.trim() === "" ? null : remark.trim(),
      });
      setIntakeHint(
        `已登记 ${intakeDate} ${mealTime} 的摄入（同一${entity.person}同一餐次重复提交为覆盖更新）`,
      );
      setRemark("");
      await Promise.all([loadRecords(), loadAnalysis()]);
    } catch (cause) {
      setIntakeError(errorMessage(cause, "登记摄入失败"));
    } finally {
      setSavingIntake(false);
    }
  }

  // —— 动作：过敏原覆盖式写入（054 A4b：按 patient；儿童无入住亦可 upsert）——
  async function submitAllergens() {
    if (!patientId) return;
    setAllergenSaving(true);
    setAllergenError("");
    setAllergenHint("");
    try {
      const updated = await upsertDietProfileAllergensByPatient(patientId, parseAllergenText(allergenText));
      setAllergenHint(allergenSavedHint(updated.allergies));
      setAllergenText(updated.allergies.join("、"));
      // 本地回显服务端返回的档案：儿童档案因后端 `GET /diet-profiles` 的关联口径
      // 可能查不到（见 A4b 缺陷报告），这里至少保证本次会话内可见。
      setDietProfiles((current) => [updated, ...current.filter((profile) => profile.id !== updated.id)]);
    } catch (cause) {
      // 非儿童无入住等 400 的服务端文案必须原样透出（如 encounter_id is required /
      // 非儿童必须先建立饮食档案），不吞错、不替换成泛化提示
      setAllergenError(errorMessage(cause, "保存过敏原失败"));
    } finally {
      setAllergenSaving(false);
    }
  }

  // —— 动作：创建特殊膳食申请 ——
  async function submitRequest() {
    if (!patientId) return;
    setRequestSaving(true);
    setRequestError("");
    setRequestHint("");
    try {
      await createSpecialDietRequest({
        patient_id: patientId,
        request_type: requestType,
        meal_type: requestMeal,
        allergens: parseAllergenText(requestAllergenText),
        reason: requestReason.trim() === "" ? null : requestReason.trim(),
        effective_from: effectiveFrom.trim() === "" ? null : effectiveFrom.trim(),
        effective_to: effectiveTo.trim() === "" ? null : effectiveTo.trim(),
      });
      setRequestHint("已提交特殊膳食申请（状态：待审批）");
      setRequestReason("");
      setRequestAllergenText("");
      await loadRequests();
    } catch (cause) {
      setRequestError(errorMessage(cause, "提交特殊膳食申请失败"));
    } finally {
      setRequestSaving(false);
    }
  }

  // —— 动作：状态流转 ——
  async function transitionRequest(request: SpecialDietRequest, target: string) {
    setTransitioningId(request.id);
    setRequestError("");
    setRequestHint("");
    try {
      await updateSpecialDietRequestStatus(request.id, target);
      setRequestHint(`申请 ${request.id.slice(-6)} 已流转为「${target}」`);
      await loadRequests();
    } catch (cause) {
      setRequestError(errorMessage(cause, "状态流转失败"));
    } finally {
      setTransitioningId("");
    }
  }

  // —— 动作：生成周报（同周覆盖）——
  async function submitReport() {
    if (!patientId) return;
    setReportSaving(true);
    setReportError("");
    setReportHint("");
    try {
      const report = await generateNutritionWeeklyReport({
        patient_id: patientId,
        week_start: reportWeek,
      });
      setReportHint(`已生成 ${weekRangeLabel(report)} 的营养周报（同周重复生成为覆盖更新）`);
      await loadReports();
      setSelectedReportId(report.id);
    } catch (cause) {
      setReportError(errorMessage(cause, "生成营养周报失败"));
    } finally {
      setReportSaving(false);
    }
  }

  const recordColumns: Column<MealIntakeRecord>[] = [
    { key: "meal_time", header: "餐次", render: (row) => row.meal_time },
    {
      key: "items",
      header: "食物项",
      render: (row) => (
        <span className="text-xs text-fg-muted">
          {row.items.map((item) => `${item.food_name} ${formatNutrientValue(item.amount_g)}g`).join("、") || "-"}
        </span>
      ),
    },
    { key: "intake_ratio", header: "摄入比例", render: (row) => formatRatio(row.intake_ratio) },
    {
      key: "energy_kcal",
      header: "热量",
      render: (row) => formatNutrientWithUnit(row.energy_kcal, "kcal"),
    },
    {
      key: "totals",
      header: "蛋白/脂肪/碳水/钠",
      render: (row) =>
        `${formatNutrientValue(row.protein_g)} / ${formatNutrientValue(row.fat_g)} / ${formatNutrientValue(row.carb_g)} / ${formatNutrientValue(row.sodium_mg)}`,
    },
    { key: "operator", header: "登记人", render: (row) => row.operator },
    { key: "updated_at", header: "更新时间", render: (row) => formatDateTime(row.updated_at) },
  ];

  const requestColumns: Column<SpecialDietRequest>[] = [
    { key: "request_type", header: "类型", render: (row) => row.request_type },
    { key: "meal_type", header: "餐次", render: (row) => row.meal_type },
    { key: "allergens", header: "过敏原", render: (row) => formatAllergens(row.allergens) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={specialDietStatusVariant(row.status)}>{row.status}</Badge>,
    },
    {
      key: "effective",
      header: "生效区间",
      render: (row) =>
        row.effective_from || row.effective_to
          ? `${formatDate(row.effective_from)} ~ ${formatDate(row.effective_to)}`
          : "-",
    },
    { key: "requested_by", header: "申请人", render: (row) => row.requested_by },
    {
      key: "reviewed",
      header: "审批",
      render: (row) =>
        row.reviewed_by ? `${row.reviewed_by}（${formatDateTime(row.reviewed_at)}）` : "-",
    },
    {
      key: "actions",
      header: "流转",
      render: (row) => {
        const targets = allowedSpecialDietTargets(row.status);
        if (targets.length === 0) return <span className="text-xs text-fg-dimmed">终态</span>;
        return (
          <div className="flex flex-wrap gap-1.5">
            {targets.map((target) => (
              <Button
                key={target}
                size="sm"
                variant={target === "已终止" ? "ghost" : "secondary"}
                loading={transitioningId === row.id}
                onClick={() => void transitionRequest(row, target)}
              >
                {target}
              </Button>
            ))}
          </div>
        );
      },
    },
  ];

  const reportColumns: Column<NutritionWeeklyReport>[] = [
    { key: "week", header: "周界", render: (row) => weekRangeLabel(row) },
    { key: "intake_days", header: "有记录天数", render: (row) => String(row.intake_days) },
    {
      key: "energy_avg_kcal",
      header: "日均热量",
      render: (row) => formatNutrientWithUnit(row.energy_avg_kcal, "kcal"),
    },
    {
      key: "conclusion",
      header: "结论",
      render: (row) => <span className="text-xs text-fg-muted">{row.conclusion}</span>,
    },
    { key: "generated_by", header: "生成人", render: (row) => row.generated_by },
    {
      key: "select",
      header: "查看",
      render: (row) => (
        <Button size="sm" variant={selectedReport?.id === row.id ? "primary" : "ghost"} onClick={() => setSelectedReportId(row.id)}>
          明细
        </Button>
      ),
    },
  ];

  const rows = analysis ? nutrientRows(analysis) : [];
  const composition = analysis ? compositionSummary(analysis) : { matched: 0, unmatched: [] as string[] };
  const recordsTotalPages = Math.max(1, Math.ceil(recordsTotal / PAGE_SIZE));
  const reportMaxEnergy = selectedReport ? maxDailyEnergy(selectedReport) : 0;

  const nutrientColumns: Column<NutrientRow>[] = [
    { key: "label", header: "营养素", render: (row) => row.label },
    { key: "value", header: "当日摄入", render: (row) => formatNutrientWithUnit(row.value, row.unit) },
    {
      key: "reference",
      header: "每日参考值",
      render: (row) => formatNutrientWithUnit(row.reference, row.unit),
    },
    {
      key: "ratio",
      header: "实际/参考",
      render: (row) => (row.ratio === null ? "-" : `${Math.round(row.ratio * 100)}%`),
    },
    {
      key: "verdict",
      header: "对比",
      render: (row) => (
        <Badge variant={row.verdict === "达到参考" ? "success" : row.verdict === "低于参考" ? "warning" : "default"}>
          {row.verdict}
        </Badge>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`营养师工作台（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-patient">
              选择{entity.person}
            </label>
            <select
              id="nutrition-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setRecordsPage(1);
                setIntakeHint("");
                setReportHint("");
                setAllergenHint("");
                setRequestHint("");
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
          <div className="min-w-[160px]">
            <Input
              label="日期"
              type="date"
              value={intakeDate}
              onChange={(event) => {
                setIntakeDate(event.target.value);
                setRecordsPage(1);
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-meal">
              餐次
            </label>
            <select
              id="nutrition-meal"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={mealTime}
              onChange={(event) => setMealTime(event.target.value as MealTime)}
            >
              {MEAL_TIMES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
          {selected && (
            <span className="text-sm text-fg-muted">
              当前 {formatAge(selected.birth_date)}
            </span>
          )}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      {/* ── A1 摄入登记 ─────────────────────────────────────────────── */}
      <Card title="每餐摄入登记（A1）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-2">
            {items.map((item, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <div className="min-w-[200px] flex-1">
                  <Input
                    label={index === 0 ? "食物名称" : undefined}
                    value={item.food_name}
                    placeholder="如：米饭 / 鸡蛋 / 牛奶"
                    onChange={(event) => setItems((current) => updateFoodItem(current, index, { food_name: event.target.value }))}
                  />
                </div>
                <div className="w-[140px]">
                  <Input
                    label={index === 0 ? "份量（g）" : undefined}
                    type="number"
                    min={0}
                    step="0.1"
                    value={item.amount_g === 0 ? "" : String(item.amount_g)}
                    onChange={(event) =>
                      setItems((current) =>
                        updateFoodItem(current, index, {
                          amount_g: event.target.value === "" ? 0 : Number(event.target.value),
                        }),
                      )
                    }
                  />
                </div>
                <div className="min-w-[160px] flex-1">
                  <Input
                    label={index === 0 ? "备注（可选）" : undefined}
                    value={item.note ?? ""}
                    onChange={(event) => setItems((current) => updateFoodItem(current, index, { note: event.target.value }))}
                  />
                </div>
                <Button
                  variant="ghost"
                  onClick={() => setItems((current) => removeFoodItem(current, index))}
                  disabled={items.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <Button variant="secondary" onClick={() => setItems((current) => addFoodItem(current))}>
              添加食物项
            </Button>
            <div className="w-[160px]">
              <Input
                label="摄入比例（0–1）"
                value={ratioText}
                onChange={(event) => setRatioText(event.target.value)}
              />
            </div>
            <span className="text-sm text-fg-muted">
              合计份量 {formatNutrientValue(totalAmountG(items))} g · 比例 {formatRatio(parseRatioInput(ratioText))}
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-remark">
              备注（可选）
            </label>
            <textarea
              id="nutrition-remark"
              className="min-h-[64px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={remark}
              onChange={(event) => setRemark(event.target.value)}
            />
          </div>
          <p className="text-xs text-fg-dimmed">
            营养素由服务端按「食物项 × 菜品营养（缺省回落内置成分常量）× 摄入比例」汇总；
            同一{entity.person}同一日期同一餐次重复提交为覆盖更新（幂等）。
          </p>
          {intakeError && <p className="text-sm text-danger">{intakeError}</p>}
          {intakeHint && <p className="text-sm text-success">{intakeHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitIntake()} loading={savingIntake} disabled={!patientId}>
              登记摄入
            </Button>
          </div>
        </div>
      </Card>

      <Card
        title={`${intakeDate} 摄入记录（${recordsTotal}）`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => setRecordsPage((value) => Math.max(1, value - 1))} disabled={recordsPage <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {recordsPage} / {recordsTotalPages}
            </span>
            <Button
              variant="ghost"
              onClick={() => setRecordsPage((value) => Math.min(recordsTotalPages, value + 1))}
              disabled={recordsPage >= recordsTotalPages}
            >
              下一页
            </Button>
          </div>
        }
      >
        {recordsLoading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : records.length === 0 ? (
          <EmptyState icon="🍚" title="当日暂无摄入记录" description="登记后此处按餐次展示，营养素为服务端汇总值" />
        ) : (
          <Table columns={recordColumns} data={records} />
        )}
      </Card>

      {/* ── A3 营养分析 ─────────────────────────────────────────────── */}
      <Card
        title="营养分析（A3，读时聚合）"
        actions={
          <div className="flex items-center gap-2">
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={analysisMeal}
              onChange={(event) => setAnalysisMeal(event.target.value as "全部" | MealTime)}
            >
              <option value="全部">全天（全部餐次）</option>
              {MEAL_TIMES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={() => void loadAnalysis()} loading={analysisLoading}>
              刷新分析
            </Button>
          </div>
        }
      >
        {analysisLoading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : analysisError ? (
          <p className="text-sm text-danger">{analysisError}</p>
        ) : !analysis ? (
          <EmptyState icon="📊" title="暂无分析结果" description={`选择${entity.person}后按日期聚合`} />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-3 text-sm text-fg-muted">
              <span>
                月龄段 {formatAgeBand(analysis)}
              </span>
              <span>餐次 {analysis.meal_time}</span>
              <Badge variant="info">{referenceVersionLabel(analysis.reference_version)}</Badge>
              <span className="text-xs text-fg-dimmed">
                参考值为决策支持提示（内置常量口径），不是诊断或处方依据
              </span>
            </div>

            <Table columns={nutrientColumns} data={rows} />

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">服务端结论</span>
              <p className="text-sm text-fg-muted">{analysis.conclusion}</p>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">缺口项（{analysis.gaps.length}）</span>
              {analysis.gaps.length === 0 ? (
                <p className="text-sm text-fg-muted">无缺口项</p>
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {analysis.gaps.map((gap) => (
                    <Badge key={gap} variant="warning">
                      {gap}
                    </Badge>
                  ))}
                </div>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">
                成分匹配（命中 {composition.matched} 项）
              </span>
              {composition.unmatched.length === 0 ? (
                <p className="text-sm text-fg-muted">全部食物项均命中成分来源</p>
              ) : (
                <p className="text-sm text-fg-muted">
                  未匹配（计 0，不报错）：{composition.unmatched.join("、")}
                </p>
              )}
            </div>

            {analysis.items.length > 0 && (
              <div className="flex flex-col gap-1">
                <span className="text-sm font-medium text-fg-emphasis">逐项折算</span>
                <span className="text-xs text-fg-muted">
                  {analysis.items
                    .map(
                      (item) =>
                        `${item.food_name} ${formatNutrientValue(item.amount_g)}g → ${formatNutrientValue(item.energy_kcal)} kcal${item.matched ? "" : "（未匹配）"}`,
                    )
                    .join("；")}
                </span>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* ── A4b 过敏原标记（按 patient，儿童无入住亦可）───────────────── */}
      <Card title="过敏原标记（A4b，按患者覆盖式写入）">
        <div className="flex flex-col gap-3">
          <p className="text-xs text-fg-dimmed">{allergenProfileNotice(dietProfiles.length, isChild)}</p>

          {dietProfiles.length > 0 && (
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-muted">
              {dietProfiles.map((profile) => (
                <span key={profile.id}>
                  {profile.meal_type}（{profile.status}
                  {profile.encounter_status ? ` · 入住 ${profile.encounter_status}` : " · 无入住"}）·
                  过敏原 {formatAllergens(profile.allergies)} · 更新于 {formatDateTime(profile.updated_at)}
                </span>
              ))}
            </div>
          )}

          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-allergens">
              过敏原（逗号/顿号/换行分隔；覆盖式写入）
            </label>
            <textarea
              id="nutrition-allergens"
              className="min-h-[72px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
              value={allergenText}
              placeholder="如：牛奶、鸡蛋、花生"
              onChange={(event) => {
                setAllergenText(event.target.value);
                setAllergenHint("");
              }}
            />
          </div>
          <p className="text-xs text-fg-dimmed">{allergenOverwriteNotice(parseAllergenText(allergenText))}</p>
          {allergenError && <p className="text-sm text-danger">{allergenError}</p>}
          {allergenHint && <p className="text-sm text-success">{allergenHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitAllergens()} loading={allergenSaving} disabled={!patientId}>
              保存过敏原
            </Button>
          </div>
        </div>
      </Card>

      {/* ── A4 特殊膳食申请 ─────────────────────────────────────────── */}
      <Card title="特殊膳食申请（A4，待审批 → 已批准/已驳回 → 已终止）">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-request-type">
                申请类型
              </label>
              <select
                id="nutrition-request-type"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={requestType}
                onChange={(event) => setRequestType(event.target.value as SpecialDietRequestType)}
              >
                {SPECIAL_DIET_REQUEST_TYPES.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="nutrition-request-meal">
                餐次
              </label>
              <select
                id="nutrition-request-meal"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={requestMeal}
                onChange={(event) => setRequestMeal(event.target.value as MealTime)}
              >
                {MEAL_TIMES.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </div>
            <Input
              label="过敏原（可选，逗号分隔）"
              value={requestAllergenText}
              onChange={(event) => setRequestAllergenText(event.target.value)}
            />
            <Input
              label="生效开始（可选）"
              type="date"
              value={effectiveFrom}
              onChange={(event) => setEffectiveFrom(event.target.value)}
            />
            <Input
              label="生效结束（可选）"
              type="date"
              value={effectiveTo}
              onChange={(event) => setEffectiveTo(event.target.value)}
            />
            <Input
              label="申请理由（可选）"
              value={requestReason}
              onChange={(event) => setRequestReason(event.target.value)}
            />
          </div>
          {requestError && <p className="text-sm text-danger">{requestError}</p>}
          {requestHint && <p className="text-sm text-success">{requestHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitRequest()} loading={requestSaving} disabled={!patientId}>
              提交申请
            </Button>
          </div>
        </div>
      </Card>

      <Card title={`特殊膳食申请台账（${requests.length}）`}>
        {requests.length === 0 ? (
          <EmptyState icon="📋" title="暂无特殊膳食申请" description="提交后在此按状态流转" />
        ) : (
          <Table columns={requestColumns} data={requests} />
        )}
      </Card>

      {/* ── A5 营养周报 ─────────────────────────────────────────────── */}
      <Card
        title="营养周报（A5，显式生成 + 快照）"
        actions={
          <div className="flex items-end gap-2">
            <div className="w-[170px]">
              <Input
                label="周起始（周一）"
                type="date"
                value={reportWeek}
                onChange={(event) => setReportWeek(event.target.value)}
              />
            </div>
            <Button onClick={() => void submitReport()} loading={reportSaving} disabled={!patientId}>
              生成周报
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-xs text-fg-dimmed">
            周界按周一归一（传周三也会落到该周周一）；同一{entity.person}同一周重复生成为覆盖更新，
            均值为「按有摄入记录的天数」平均；当周无记录仍生成可判定快照。
          </p>
          {reportError && <p className="text-sm text-danger">{reportError}</p>}
          {reportHint && <p className="text-sm text-success">{reportHint}</p>}
          {reports.length === 0 ? (
            <EmptyState icon="🗓️" title="暂无营养周报" description="选择周起始后生成" />
          ) : (
            <Table columns={reportColumns} data={reports} />
          )}
        </div>
      </Card>

      {selectedReport && (
        <Card title={`周报明细 ${weekRangeLabel(selectedReport)}`}>
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-3 text-sm text-fg-muted">
              <span>{weeklyHeadline(selectedReport)}</span>
              <Badge variant="info">{referenceVersionLabel(selectedReport.reference_version)}</Badge>
              <span>生成人 {selectedReport.generated_by}</span>
            </div>

            <div className="flex flex-wrap gap-4">
              {weeklyAverageRows(selectedReport).map((row) => (
                <div key={row.key} className="flex flex-col">
                  <span className="text-xs text-fg-dimmed">{row.label}（日均）</span>
                  <span className="text-sm text-fg">{formatNutrientWithUnit(row.value, row.unit)}</span>
                </div>
              ))}
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">结论</span>
              <p className="text-sm text-fg-muted">{selectedReport.conclusion}</p>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">
                缺口项（{selectedReport.gaps.length}）
              </span>
              {selectedReport.gaps.length === 0 ? (
                <p className="text-sm text-fg-muted">无缺口项</p>
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {selectedReport.gaps.map((gap) => (
                    <Badge key={gap} variant="warning">
                      {gap}
                    </Badge>
                  ))}
                </div>
              )}
            </div>

            <div className="flex flex-col gap-2">
              <span className="text-sm font-medium text-fg-emphasis">逐日热量</span>
              {selectedReport.daily.length === 0 ? (
                <p className="text-sm text-fg-muted">当周无逐日记录</p>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {selectedReport.daily.map((entry) => (
                    <div key={entry.intake_date} className="flex items-center gap-3 text-xs text-fg-muted">
                      <span className="w-[92px] shrink-0">{formatDate(entry.intake_date)}</span>
                      <span className="h-2 rounded-full bg-accent/60" style={{ width: `${dailyEnergyPercent(entry.energy_kcal, reportMaxEnergy)}%` }} />
                      <span>
                        {formatNutrientWithUnit(entry.energy_kcal, "kcal")} · {entry.conclusion}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}
