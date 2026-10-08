import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  checkDrugAllergy,
  createPediatricDoseRule,
  getPediatricDoseCalculation,
  listInventoryMaterials,
  listPatients,
  listPediatricDoseRules,
  updatePediatricDoseRule,
  type DrugAllergyCheck,
  type InventoryMaterial,
  type Patient,
  type PediatricDoseCalculation,
  type PediatricDoseRule,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDateTime, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY, currentEntityLabels } from "../lib/domain";
import { ageMonthsBetween } from "../lib/growth";
import { useDomain } from "../lib/useDomain";
import {
  ALLERGY_CHECK_NOTICE,
  ALLERGY_CHECK_SCOPE_NOTICE,
  DOSE_RULE_STATUSES,
  DOSE_SAFETY_NOTICE,
  allergyCheckLabel,
  allergyCheckSummary,
  allergyCheckVariant,
  allergySeverityVariant,
  allergyWarningLabel,
  buildDoseRulePayload,
  calculationInputLabel,
  calculationSummary,
  cappedNotice,
  doseRuleFormOf,
  doseRuleStatusVariant,
  doseSourceLabel,
  doseSourceVariant,
  emptyDoseRuleForm,
  formatAllergyList,
  formatDose,
  ruleBandLabel,
  ruleDefinitionLabel,
  ruleLimitsLabel,
  sortAllergyWarnings,
  validateDoseRuleForm,
  type DoseRuleForm,
} from "../lib/pharmacyReview";

/**
 * 药师用药审查工作台（054 D4，含 D4 增补）。
 *
 * 消费 D1/D2/D3 的冻结路由（§5.4）：
 *   儿科用药规则库（建/查/改）→ 用量计算辅助（按体重/月龄/药品折算，展示 `capped` 与 `source`）
 *   → 过敏史只读展示 + **药物过敏交叉检查（053 D3 `GET /healthcare/v1/drug-allergy-check`）**。
 *
 * 安全边界（§12 R6，必须随结果展示）：
 * - 规则与计算结果只是**辅助计算 + 上限提示**：**不写医嘱、不替代药师判断**；
 *   `capped=true` 表示已触发上限封顶，药师必须人工复核。
 * - 交叉检查结果是**辅助提示**（命中来源：过敏史/交叉反应/年龄禁忌；严重项红色标注）：
 *   未命中不代表绝对安全，命中项须经药师复核后再决定是否发药。
 * - 入口显隐由 F1 机制按 `pharmacy:dispense` 控制；**后端仍是唯一强制点**，前端隐藏不替代 403。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function PharmacistWorkbenchPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [error, setError] = useState("");

  // —— 用量计算辅助 ——
  const [drugs, setDrugs] = useState<InventoryMaterial[]>([]);
  const [materialId, setMaterialId] = useState("");
  const [weightKg, setWeightKg] = useState("");
  const [ageMonthsText, setAgeMonthsText] = useState("");
  const [calculating, setCalculating] = useState(false);
  const [calculation, setCalculation] = useState<PediatricDoseCalculation | null>(null);
  const [calcError, setCalcError] = useState("");

  // —— 药物过敏交叉检查（053 D3）——
  const [allergyCheck, setAllergyCheck] = useState<DrugAllergyCheck | null>(null);
  const [checkingAllergy, setCheckingAllergy] = useState(false);
  const [allergyCheckError, setAllergyCheckError] = useState("");

  // —— 规则库 ——
  const [rules, setRules] = useState<PediatricDoseRule[]>([]);
  const [rulesTotal, setRulesTotal] = useState(0);
  const [rulesPage, setRulesPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState("");
  const [rulesLoading, setRulesLoading] = useState(false);
  const [ruleError, setRuleError] = useState("");

  const [form, setForm] = useState<DoseRuleForm>(emptyDoseRuleForm);
  const [editingId, setEditingId] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [formHint, setFormHint] = useState("");
  const [togglingId, setTogglingId] = useState("");

  const selected = useMemo(
    () => children.find((child) => child.id === patientId) ?? null,
    [children, patientId],
  );
  const rulesTotalPages = Math.max(1, Math.ceil(rulesTotal / PAGE_SIZE));

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

  // —— 药品目录（public.materials，与规则 material_id 同表）——
  useEffect(() => {
    let active = true;
    listInventoryMaterials({ category: "药品", status: "ACTIVE", limit: 200 })
      .then((response) => {
        if (!active) return;
        setDrugs(response.records);
        setMaterialId((current) => current || response.records[0]?.id || "");
      })
      .catch((cause) => {
        if (active) setCalcError(errorMessage(cause, "无法加载药品目录"));
      });
    return () => {
      active = false;
    };
  }, []);

  // —— 选中儿童时预填月龄（可改；月龄缺省由后端按区间规则决定是否必需）——
  useEffect(() => {
    if (!selected) {
      setAgeMonthsText("");
      return;
    }
    const months = ageMonthsBetween(selected.birth_date, todayLocal());
    setAgeMonthsText(months === null ? "" : String(months));
  }, [selected]);

  // —— 规则列表 ——
  const loadRules = useCallback(async () => {
    setRulesLoading(true);
    try {
      const response = await listPediatricDoseRules({
        status: statusFilter.trim() === "" ? undefined : statusFilter,
        limit: PAGE_SIZE,
        offset: (rulesPage - 1) * PAGE_SIZE,
      });
      setRules(response.records);
      setRulesTotal(response.meta.total);
      setRuleError("");
    } catch (cause) {
      setRuleError(errorMessage(cause, "无法加载儿科用药规则"));
    } finally {
      setRulesLoading(false);
    }
  }, [statusFilter, rulesPage]);

  useEffect(() => {
    void loadRules();
  }, [loadRules]);

  // —— 动作：计算 ——
  async function calculate() {
    if (!materialId) {
      setCalcError("请选择药品");
      return;
    }
    setCalculating(true);
    setCalcError("");
    setCalculation(null);
    try {
      const result = await getPediatricDoseCalculation({
        material_id: materialId,
        weight_kg: weightKg.trim() === "" ? undefined : weightKg.trim(),
        age_months: ageMonthsText.trim() === "" ? undefined : ageMonthsText.trim(),
      });
      setCalculation(result);
    } catch (cause) {
      // 无可用启用规则 → 404（不返回 0 剂量）；体重/年龄非法 → 400：服务端文案原样透出
      setCalcError(errorMessage(cause, "用量计算失败"));
    } finally {
      setCalculating(false);
    }
  }

  // —— 动作：药物过敏交叉检查（053 D3，只读接口；缺儿童/药品时不发请求）——
  const runAllergyCheck = useCallback(async () => {
    if (!patientId || !materialId) {
      setAllergyCheck(null);
      setAllergyCheckError("");
      return;
    }
    setCheckingAllergy(true);
    setAllergyCheckError("");
    try {
      const result = await checkDrugAllergy(patientId, materialId);
      setAllergyCheck(result);
    } catch (cause) {
      // 目录端口未接线 → 503；物资不存在 → 404；非药品/非启用 → 409；缺参 → 400：
      // 一律原样透出服务端文案，不臆造「未命中」结论
      setAllergyCheck(null);
      setAllergyCheckError(errorMessage(cause, "药物过敏交叉检查失败"));
    } finally {
      setCheckingAllergy(false);
    }
  }, [patientId, materialId]);

  // 选中儿童 + 药品后自动检查（两者任一变化都重查；任一缺失则清空结论）
  useEffect(() => {
    void runAllergyCheck();
  }, [runAllergyCheck]);

  // —— 动作：保存规则（新建 / 编辑）——
  async function submitRule() {
    const validation = validateDoseRuleForm(form);
    if (validation) {
      setFormError(validation);
      return;
    }
    setSaving(true);
    setFormError("");
    setFormHint("");
    try {
      const payload = buildDoseRulePayload(form);
      if (editingId) {
        const updated = await updatePediatricDoseRule(editingId, payload);
        setFormHint(`已更新规则（${updated.material_name ?? updated.material_id} · ${updated.unit}）`);
      } else {
        const created = await createPediatricDoseRule(payload);
        setFormHint(`已创建规则（${created.material_name ?? created.material_id} · ${created.unit}）`);
      }
      setForm(emptyDoseRuleForm());
      setEditingId("");
      await loadRules();
    } catch (cause) {
      // material_id 不存在 → 404；未知字段/非法值 → 400：服务端文案原样透出
      setFormError(errorMessage(cause, "保存规则失败"));
    } finally {
      setSaving(false);
    }
  }

  // —— 动作：启用/停用（只改 status，PUT 语义只覆盖出现的字段）——
  async function toggleRule(rule: PediatricDoseRule) {
    const target = rule.status === "启用" ? "停用" : "启用";
    setTogglingId(rule.id);
    setRuleError("");
    try {
      await updatePediatricDoseRule(rule.id, { status: target });
      await loadRules();
    } catch (cause) {
      setRuleError(errorMessage(cause, "规则状态更新失败"));
    } finally {
      setTogglingId("");
    }
  }

  const ruleColumns: Column<PediatricDoseRule>[] = [
    {
      key: "material",
      header: "药品",
      render: (row) => (
        <div className="flex flex-col">
          <span className="text-sm text-fg">{row.material_name ?? "-"}</span>
          <span className="text-xs text-fg-dimmed">{row.material_id}</span>
        </div>
      ),
    },
    { key: "definition", header: "剂量定义", render: (row) => ruleDefinitionLabel(row) },
    { key: "band", header: "适用区间", render: (row) => ruleBandLabel(row) },
    { key: "limits", header: "上限/频次", render: (row) => ruleLimitsLabel(row) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={doseRuleStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "updated_at", header: "更新时间", render: (row) => formatDateTime(row.updated_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex flex-wrap gap-1.5">
          <Button
            size="sm"
            variant={editingId === row.id ? "primary" : "secondary"}
            onClick={() => {
              setForm(doseRuleFormOf(row));
              setEditingId(row.id);
              setFormError("");
              setFormHint(`正在编辑：${row.material_name ?? row.material_id}`);
            }}
          >
            编辑
          </Button>
          <Button
            size="sm"
            variant="ghost"
            loading={togglingId === row.id}
            onClick={() => void toggleRule(row)}
          >
            {row.status === "启用" ? "停用" : "启用"}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title={`药师工作台（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="pharmacy-patient">
              选择{entity.person}
            </label>
            <select
              id="pharmacy-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => {
                setPatientId(event.target.value);
                setCalculation(null);
                setCalcError("");
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

      {/* ── 过敏史（只读）────────────────────────────────────────────── */}
      <Card title="过敏史（只读，来源 patients.allergies）">
        <div className="flex flex-col gap-2">
          <p className="text-sm text-fg">
            {selected ? formatAllergyList(selected.allergies) : "请选择儿童后展示"}
          </p>
          <p className="text-xs text-fg-dimmed">
            过敏史为只读展示；药物过敏交叉检查在下方「药物过敏交叉检查」中按儿童 + 药品实时调用。
          </p>
        </div>
      </Card>

      {/* ── D1/D2 用量计算辅助 ─────────────────────────────────────── */}
      <Card title="儿科用量计算辅助（D1/D2）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[260px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="pharmacy-material">
                药品（目录 public.materials）
              </label>
              <select
                id="pharmacy-material"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={materialId}
                onChange={(event) => {
                  setMaterialId(event.target.value);
                  setCalculation(null);
                }}
              >
                <option value="">请选择</option>
                {drugs.map((drug) => (
                  <option key={drug.id} value={drug.id}>
                    {drug.name}（{drug.code}）
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[150px]">
              <Input
                label="体重（kg）"
                value={weightKg}
                placeholder="如 12.5"
                onChange={(event) => setWeightKg(event.target.value)}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="月龄"
                value={ageMonthsText}
                onChange={(event) => setAgeMonthsText(event.target.value)}
              />
            </div>
            <Button onClick={() => void calculate()} loading={calculating} disabled={!materialId}>
              计算
            </Button>
          </div>

          <p className="text-xs text-fg-dimmed">
            规则命中顺序：体重+年龄区间 &gt; 体重区间 &gt; 年龄区间 &gt; 无区间通用；区间均不命中时回落通用规则
            （来源会标注为「区间未命中，回落通用规则」）。无可用启用规则时服务端返回 404，**不返回 0 剂量**。
          </p>

          {calcError && <p className="text-sm text-danger">{calcError}</p>}

          {calculation && (
            <div className="flex flex-col gap-3 rounded-md border border-border bg-surface-alt/40 p-3">
              <div className="flex flex-wrap items-center gap-3">
                <span className="text-base font-medium text-fg-emphasis">
                  {calculationSummary(calculation)}
                </span>
                <Badge variant={calculation.capped ? "danger" : "success"}>
                  {calculation.capped ? "已封顶" : "未封顶"}
                </Badge>
                <Badge variant={doseSourceVariant(calculation.source)}>
                  {doseSourceLabel(calculation.source)}
                </Badge>
              </div>
              <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-fg-muted">
                <span>药品：{calculation.material_name ?? calculation.material_id}</span>
                <span>{calculationInputLabel(calculation)}</span>
                <span>命中规则：{calculation.rule_id ?? "-"}</span>
                <span>
                  单次 {formatDose(calculation.single_dose, calculation.unit)} · 每日{" "}
                  {formatDose(calculation.daily_dose, calculation.unit)}
                </span>
              </div>
              <p className={`text-xs ${calculation.capped ? "text-danger" : "text-fg-dimmed"}`}>
                {cappedNotice(calculation.capped)}
              </p>
              {allergyCheck?.severe && (
                <p className="text-xs font-medium text-danger">
                  交叉检查存在严重提醒：{allergyCheckSummary(allergyCheck)}
                </p>
              )}
              <p className="text-xs font-medium text-warning">{DOSE_SAFETY_NOTICE}</p>
            </div>
          )}

          <p className="text-xs text-fg-dimmed">{DOSE_SAFETY_NOTICE}</p>
        </div>
      </Card>

      {/* ── 053 D3 药物过敏交叉检查（依赖上方选中的儿童 + 药品）────────── */}
      <Card
        title="药物过敏交叉检查（053 D3）"
        actions={
          <Button
            variant="secondary"
            onClick={() => void runAllergyCheck()}
            loading={checkingAllergy}
            disabled={!patientId || !materialId}
          >
            重新检查
          </Button>
        }
      >
        <div className="flex flex-col gap-3">
          {!patientId || !materialId ? (
            <EmptyState
              icon="🧪"
              title="尚未选择儿童或药品"
              description="选择儿童与药品后自动调用交叉检查（过敏史 / 交叉反应 / 年龄禁忌）"
            />
          ) : checkingAllergy && !allergyCheck ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : allergyCheckError ? (
            // 503（目录端口未接线）/ 404（物资不存在）/ 409（非药品或非启用）/ 400：原样透出
            <p className="text-sm text-danger">{allergyCheckError}</p>
          ) : allergyCheck ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-3">
                <Badge variant={allergyCheckVariant(allergyCheck)}>{allergyCheckLabel(allergyCheck)}</Badge>
                <span className="text-sm text-fg-emphasis">{allergyCheckSummary(allergyCheck)}</span>
                <span className="text-xs text-fg-muted">
                  药品：{allergyCheck.drug_name ?? allergyCheck.material_id}
                  {allergyCheck.drug_code ? `（${allergyCheck.drug_code}）` : ""}
                </span>
              </div>

              {allergyCheck.warnings.length === 0 ? (
                <p className="text-sm text-fg-muted">
                  未返回任何命中项。未命中不代表绝对安全，仍须药师复核。
                </p>
              ) : (
                <div className="flex flex-col gap-2">
                  {sortAllergyWarnings(allergyCheck.warnings).map((warning, index) => (
                    <div
                      key={`${warning.source}-${warning.matched}-${index}`}
                      className={`flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2 ${
                        warning.severity === "严重" ? "border-danger bg-danger-bg/40" : "border-border"
                      }`}
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant={allergySeverityVariant(warning.severity)}>{warning.severity}</Badge>
                        <span className="text-sm text-fg">{allergyWarningLabel(warning)}</span>
                      </div>
                      <span className="text-xs text-fg-muted">{warning.reason}</span>
                    </div>
                  ))}
                </div>
              )}

              <p className={`text-xs font-medium ${allergyCheck.severe ? "text-danger" : "text-fg-dimmed"}`}>
                {ALLERGY_CHECK_NOTICE}
              </p>
              <p className="text-xs text-fg-dimmed">{ALLERGY_CHECK_SCOPE_NOTICE}</p>
            </div>
          ) : (
            <EmptyState icon="🧪" title="暂无交叉检查结果" description="点击「重新检查」重试" />
          )}
        </div>
      </Card>

      {/* ── D2 规则库（建/查/改）────────────────────────────────────── */}
      <Card
        title={editingId ? "编辑儿科用药规则（D2）" : "新建儿科用药规则（D2）"}
        actions={
          editingId ? (
            <Button
              variant="ghost"
              onClick={() => {
                setForm(emptyDoseRuleForm());
                setEditingId("");
                setFormError("");
                setFormHint("");
              }}
            >
              取消编辑
            </Button>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5 min-w-[240px]">
              <label className="text-sm font-medium text-fg-muted" htmlFor="pharmacy-rule-material">
                药品
              </label>
              <select
                id="pharmacy-rule-material"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={form.materialId}
                onChange={(event) => setForm((current) => ({ ...current, materialId: event.target.value }))}
              >
                <option value="">请选择</option>
                {drugs.map((drug) => (
                  <option key={drug.id} value={drug.id}>
                    {drug.name}（{drug.code}）
                  </option>
                ))}
              </select>
            </div>
            <div className="w-[140px]">
              <Input
                label="单位"
                value={form.unit}
                placeholder="mg / mL"
                onChange={(event) => setForm((current) => ({ ...current, unit: event.target.value }))}
              />
            </div>
            <div className="w-[170px]">
              <Input
                label="按体重剂量"
                value={form.dosePerKg}
                placeholder="单位/kg"
                onChange={(event) => setForm((current) => ({ ...current, dosePerKg: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="固定剂量"
                value={form.fixedDose}
                onChange={(event) => setForm((current) => ({ ...current, fixedDose: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="单次上限"
                value={form.singleDoseMax}
                onChange={(event) => setForm((current) => ({ ...current, singleDoseMax: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="日上限"
                value={form.dailyDoseMax}
                onChange={(event) => setForm((current) => ({ ...current, dailyDoseMax: event.target.value }))}
              />
            </div>
            <div className="w-[130px]">
              <Input
                label="每日次数"
                value={form.dosesPerDay}
                onChange={(event) => setForm((current) => ({ ...current, dosesPerDay: event.target.value }))}
              />
            </div>
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[150px]">
              <Input
                label="年龄下限（月）"
                value={form.ageMinMonths}
                onChange={(event) => setForm((current) => ({ ...current, ageMinMonths: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="年龄上限（月）"
                value={form.ageMaxMonths}
                onChange={(event) => setForm((current) => ({ ...current, ageMaxMonths: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="体重下限（kg）"
                value={form.weightMinKg}
                onChange={(event) => setForm((current) => ({ ...current, weightMinKg: event.target.value }))}
              />
            </div>
            <div className="w-[150px]">
              <Input
                label="体重上限（kg）"
                value={form.weightMaxKg}
                onChange={(event) => setForm((current) => ({ ...current, weightMaxKg: event.target.value }))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="pharmacy-rule-status">
                状态
              </label>
              <select
                id="pharmacy-rule-status"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={form.status}
                onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}
              >
                {DOSE_RULE_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <div className="min-w-[220px] flex-1">
              <Input
                label="备注（可选）"
                value={form.note}
                onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))}
              />
            </div>
          </div>

          <p className="text-xs text-fg-dimmed">
            剂量定义二选一（可同时填，服务端优先按体重）；区间为**闭区间**，留空表示不限。
            提交的字段只含下方表单字段，`material_name`/`created_by` 由服务端管控。
          </p>
          {formError && <p className="text-sm text-danger">{formError}</p>}
          {formHint && <p className="text-sm text-success">{formHint}</p>}
          <div className="flex justify-end">
            <Button onClick={() => void submitRule()} loading={saving}>
              {editingId ? "保存修改" : "创建规则"}
            </Button>
          </div>
        </div>
      </Card>

      <Card
        title={`儿科用药规则库（${rulesTotal}）`}
        actions={
          <div className="flex items-center gap-2">
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={statusFilter}
              onChange={(event) => {
                setStatusFilter(event.target.value);
                setRulesPage(1);
              }}
            >
              <option value="">全部状态</option>
              {DOSE_RULE_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
            <Button variant="ghost" onClick={() => setRulesPage((value) => Math.max(1, value - 1))} disabled={rulesPage <= 1}>
              上一页
            </Button>
            <span className="text-sm text-fg-muted">
              {rulesPage} / {rulesTotalPages}
            </span>
            <Button
              variant="ghost"
              onClick={() => setRulesPage((value) => Math.min(rulesTotalPages, value + 1))}
              disabled={rulesPage >= rulesTotalPages}
            >
              下一页
            </Button>
          </div>
        }
      >
        {ruleError && <p className="mb-3 text-sm text-danger">{ruleError}</p>}
        {rulesLoading ? (
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        ) : rules.length === 0 ? (
          <EmptyState icon="💊" title="暂无儿科用药规则" description="规则建好后可用于上方用量计算辅助" />
        ) : (
          <Table columns={ruleColumns} data={rules} />
        )}
      </Card>
    </div>
  );
}
