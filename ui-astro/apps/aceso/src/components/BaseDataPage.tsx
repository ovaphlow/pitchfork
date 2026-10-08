import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Table, type Column } from "@pitchfork/ui";
import {
  createBaseDevelopmentalScale,
  createBaseVaccinationType,
  listBaseDevelopmentalScales,
  listBaseVaccinationTypes,
  updateBaseDevelopmentalScale,
  updateBaseVaccinationType,
  type BaseDevelopmentalScale,
  type BaseVaccinationType,
} from "@pitchfork/shared/aceso";
import { formatDateTime } from "../lib/datetime";
import {
  CONTENT_STATUSES,
  ENABLED_ONLY_NOTICE,
  FALLBACK_NOTICE,
  addDraftLike,
  contentStatusVariant,
  emptyDomainDraft,
  emptyItemDraft,
  optionsLabel,
  parseMonthsText,
  removeDraftLike,
  scaleAgeLabel,
  scaleSummary,
  schedulePreviewLabel,
  toDefinitionDomains,
  toDefinitionItems,
  toDomainDrafts,
  toItemDrafts,
  updateDraftLike,
  vaccinationSummary,
  validateScaleForm,
  validateVaccinationForm,
  type DomainDraft,
  type ItemDraft,
} from "../lib/baseData";

/**
 * 基础数据维护页（054 F2 / F 链前端第一步）。
 *
 * 维护两类**内容**（迁移 V539）：疫苗类型、发育量表。
 * - **读库优先、常量回落**：内容表有启用记录时消费方用库内容；库为空/读失败时
 *   完全回落既有常量（052 接种状态与 053 筛查判读结果与改造前一致）。页面常驻该口径说明。
 * - **只有「启用」记录参与内容加载**；「停用」保留台账但不生效。
 * - 写入白名单与后端 `BaseDataService` 一致（多提交未知字段会被服务端 400 拒绝）；
 *   `code` / `(scale_code, scale_version)` 唯一，重复由服务端 409 兜底，文案原样透出。
 * - 入口显隐由 F1 机制按 `base_data:manage` 控制；**后端仍是唯一强制点**。
 */

const PAGE_SIZE = 20;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function BaseDataPage() {
  // —— 疫苗类型 ——
  const [vaccinations, setVaccinations] = useState<BaseVaccinationType[]>([]);
  const [vaccinationStatusFilter, setVaccinationStatusFilter] = useState("");
  const [vaccinationsLoading, setVaccinationsLoading] = useState(false);
  const [vaccinationEditingId, setVaccinationEditingId] = useState("");
  const [vaccinationCode, setVaccinationCode] = useState("");
  const [vaccinationName, setVaccinationName] = useState("");
  const [doseCountText, setDoseCountText] = useState("1");
  const [monthsText, setMonthsText] = useState("");
  const [sortOrderText, setSortOrderText] = useState("0");
  const [vaccinationStatus, setVaccinationStatus] = useState<string>(CONTENT_STATUSES[0]);
  const [vaccinationSaving, setVaccinationSaving] = useState(false);
  const [vaccinationHint, setVaccinationHint] = useState("");
  const [vaccinationError, setVaccinationError] = useState("");

  // —— 发育量表 ——
  const [scales, setScales] = useState<BaseDevelopmentalScale[]>([]);
  const [scaleStatusFilter, setScaleStatusFilter] = useState("");
  const [scalesLoading, setScalesLoading] = useState(false);
  const [scaleEditingId, setScaleEditingId] = useState("");
  const [scaleCode, setScaleCode] = useState("");
  const [scaleVersion, setScaleVersion] = useState("");
  const [scaleName, setScaleName] = useState("");
  const [ageMinText, setAgeMinText] = useState("0");
  const [ageMaxText, setAgeMaxText] = useState("36");
  const [scaleStatus, setScaleStatus] = useState<string>(CONTENT_STATUSES[0]);
  const [domains, setDomains] = useState<DomainDraft[]>([emptyDomainDraft()]);
  const [items, setItems] = useState<ItemDraft[]>([emptyItemDraft()]);
  const [scaleSaving, setScaleSaving] = useState(false);
  const [scaleHint, setScaleHint] = useState("");
  const [scaleError, setScaleError] = useState("");

  // —— 疫苗类型列表 ——
  const loadVaccinations = useCallback(async () => {
    setVaccinationsLoading(true);
    try {
      const response = await listBaseVaccinationTypes({
        status: vaccinationStatusFilter.trim() === "" ? undefined : vaccinationStatusFilter,
        limit: PAGE_SIZE,
      });
      setVaccinations(response.records);
      setVaccinationError("");
    } catch (cause) {
      setVaccinationError(errorMessage(cause, "无法加载疫苗类型"));
    } finally {
      setVaccinationsLoading(false);
    }
  }, [vaccinationStatusFilter]);

  useEffect(() => {
    void loadVaccinations();
  }, [loadVaccinations]);

  // —— 发育量表列表 ——
  const loadScales = useCallback(async () => {
    setScalesLoading(true);
    try {
      const response = await listBaseDevelopmentalScales({
        status: scaleStatusFilter.trim() === "" ? undefined : scaleStatusFilter,
        limit: PAGE_SIZE,
      });
      setScales(response.records);
      setScaleError("");
    } catch (cause) {
      setScaleError(errorMessage(cause, "无法加载发育量表"));
    } finally {
      setScalesLoading(false);
    }
  }, [scaleStatusFilter]);

  useEffect(() => {
    void loadScales();
  }, [loadScales]);

  function resetVaccinationForm() {
    setVaccinationEditingId("");
    setVaccinationCode("");
    setVaccinationName("");
    setDoseCountText("1");
    setMonthsText("");
    setSortOrderText("0");
    setVaccinationStatus(CONTENT_STATUSES[0]);
    setVaccinationError("");
    setVaccinationHint("");
  }

  function editVaccination(row: BaseVaccinationType) {
    setVaccinationEditingId(row.id);
    setVaccinationCode(row.code);
    setVaccinationName(row.name);
    setDoseCountText(String(row.dose_count));
    setMonthsText((row.recommended_months ?? []).join("、"));
    setSortOrderText(String(row.sort_order ?? 0));
    setVaccinationStatus(row.status);
    setVaccinationError("");
    setVaccinationHint(`正在编辑：${row.name}（${row.code}）`);
  }

  function resetScaleForm() {
    setScaleEditingId("");
    setScaleCode("");
    setScaleVersion("");
    setScaleName("");
    setAgeMinText("0");
    setAgeMaxText("36");
    setScaleStatus(CONTENT_STATUSES[0]);
    setDomains([emptyDomainDraft()]);
    setItems([emptyItemDraft()]);
    setScaleError("");
    setScaleHint("");
  }

  function editScale(row: BaseDevelopmentalScale) {
    setScaleEditingId(row.id);
    setScaleCode(row.scale_code);
    setScaleVersion(row.scale_version);
    setScaleName(row.name);
    setAgeMinText(String(row.age_min_months));
    setAgeMaxText(String(row.age_max_months));
    setScaleStatus(row.status);
    setDomains(toDomainDrafts(row.definition));
    setItems(toItemDrafts(row.definition));
    setScaleError("");
    setScaleHint(`正在编辑：${row.name}（${row.scale_code}@${row.scale_version}）`);
  }

  // —— 动作：保存疫苗类型 ——
  async function submitVaccination() {
    const validation = validateVaccinationForm({
      code: vaccinationCode,
      name: vaccinationName,
      doseCountText,
      monthsText,
      sortOrderText,
      status: vaccinationStatus,
    });
    if (validation) {
      setVaccinationError(validation);
      return;
    }
    setVaccinationSaving(true);
    setVaccinationError("");
    setVaccinationHint("");
    try {
      const payload = {
        code: vaccinationCode.trim(),
        name: vaccinationName.trim(),
        dose_count: Number(doseCountText.trim()),
        recommended_months: parseMonthsText(monthsText) ?? [],
        status: vaccinationStatus,
        sort_order: sortOrderText.trim() === "" ? 0 : Number(sortOrderText.trim()),
      };
      if (vaccinationEditingId) {
        await updateBaseVaccinationType(vaccinationEditingId, payload);
        setVaccinationHint(`已更新疫苗类型（${payload.name}）`);
      } else {
        await createBaseVaccinationType(payload);
        setVaccinationHint(`已创建疫苗类型（${payload.name}）`);
      }
      resetVaccinationForm();
      await loadVaccinations();
    } catch (cause) {
      // 编码重复 → 409；白名单外字段/非法值 → 400：服务端文案原样透出
      setVaccinationError(errorMessage(cause, "保存疫苗类型失败"));
    } finally {
      setVaccinationSaving(false);
    }
  }

  // —— 动作：保存发育量表 ——
  async function submitScale() {
    const validation = validateScaleForm({
      scaleCode,
      scaleVersion,
      name: scaleName,
      ageMinText,
      ageMaxText,
      domains,
      items,
      status: scaleStatus,
    });
    if (validation) {
      setScaleError(validation);
      return;
    }
    setScaleSaving(true);
    setScaleError("");
    setScaleHint("");
    try {
      const payload = {
        scale_code: scaleCode.trim(),
        scale_version: scaleVersion.trim(),
        name: scaleName.trim(),
        age_min_months: Number(ageMinText.trim()),
        age_max_months: Number(ageMaxText.trim()),
        definition: {
          domains: toDefinitionDomains(domains),
          items: toDefinitionItems(items),
        },
        status: scaleStatus,
      };
      if (scaleEditingId) {
        await updateBaseDevelopmentalScale(scaleEditingId, payload);
        setScaleHint(`已更新发育量表（${payload.name}）`);
      } else {
        await createBaseDevelopmentalScale(payload);
        setScaleHint(`已创建发育量表（${payload.name}）`);
      }
      resetScaleForm();
      await loadScales();
    } catch (cause) {
      // (scale_code, scale_version) 重复 → 409；definition 形状非法 → 400：原样透出
      setScaleError(errorMessage(cause, "保存发育量表失败"));
    } finally {
      setScaleSaving(false);
    }
  }

  const vaccinationColumns: Column<BaseVaccinationType>[] = [
    { key: "code", header: "编码", render: (row) => row.code },
    { key: "name", header: "名称", render: (row) => row.name },
    { key: "summary", header: "剂次 / 月龄", render: (row) => vaccinationSummary(row) },
    {
      key: "schedule",
      header: "排程展开预览",
      render: (row) => <span className="text-xs text-fg-muted">{schedulePreviewLabel(row)}</span>,
    },
    { key: "sort_order", header: "排序", render: (row) => String(row.sort_order ?? 0) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={contentStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "updated_at", header: "更新时间", render: (row) => formatDateTime(row.updated_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          size="sm"
          variant={vaccinationEditingId === row.id ? "primary" : "secondary"}
          onClick={() => editVaccination(row)}
        >
          编辑
        </Button>
      ),
    },
  ];

  const scaleColumns: Column<BaseDevelopmentalScale>[] = [
    { key: "scale_code", header: "编码", render: (row) => row.scale_code },
    { key: "scale_version", header: "版本", render: (row) => row.scale_version },
    { key: "name", header: "名称", render: (row) => row.name },
    { key: "age", header: "适用月龄", render: (row) => scaleAgeLabel(row) },
    { key: "summary", header: "结构", render: (row) => scaleSummary(row) },
    {
      key: "status",
      header: "状态",
      render: (row) => <Badge variant={contentStatusVariant(row.status)}>{row.status}</Badge>,
    },
    { key: "updated_at", header: "更新时间", render: (row) => formatDateTime(row.updated_at) },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button
          size="sm"
          variant={scaleEditingId === row.id ? "primary" : "secondary"}
          onClick={() => editScale(row)}
        >
          编辑
        </Button>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card title="基础数据维护（F2）">
        <div className="flex flex-col gap-2">
          <p className="text-sm text-fg-muted">{FALLBACK_NOTICE}</p>
          <p className="text-xs text-fg-dimmed">{ENABLED_ONLY_NOTICE}</p>
        </div>
      </Card>

      {/* ── 疫苗类型 ────────────────────────────────────────────────── */}
      <Card
        title={vaccinationEditingId ? "编辑疫苗类型" : "新建疫苗类型"}
        actions={
          vaccinationEditingId ? (
            <Button variant="ghost" onClick={resetVaccinationForm}>
              取消编辑
            </Button>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[150px]">
              <Input
                label="疫苗编码"
                value={vaccinationCode}
                placeholder="如 HEPB"
                onChange={(event) => setVaccinationCode(event.target.value)}
              />
            </div>
            <div className="min-w-[180px] flex-1">
              <Input
                label="疫苗名称"
                value={vaccinationName}
                placeholder="如 乙肝疫苗"
                onChange={(event) => setVaccinationName(event.target.value)}
              />
            </div>
            <div className="w-[120px]">
              <Input
                label="剂次数"
                value={doseCountText}
                onChange={(event) => setDoseCountText(event.target.value)}
              />
            </div>
            <div className="min-w-[220px] flex-1">
              <Input
                label="推荐月龄（逗号/顿号分隔，个数 = 剂次数）"
                value={monthsText}
                placeholder="如 0、1、6"
                onChange={(event) => setMonthsText(event.target.value)}
              />
            </div>
            <div className="w-[110px]">
              <Input
                label="排序号"
                value={sortOrderText}
                onChange={(event) => setSortOrderText(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="base-vaccination-status">
                状态
              </label>
              <select
                id="base-vaccination-status"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={vaccinationStatus}
                onChange={(event) => setVaccinationStatus(event.target.value)}
              >
                {CONTENT_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <Button onClick={() => void submitVaccination()} loading={vaccinationSaving}>
              {vaccinationEditingId ? "保存修改" : "创建"}
            </Button>
          </div>
          <p className="text-xs text-fg-dimmed">
            排程由「编码 × 剂次 1..剂次数」展开，第 n 剂取推荐月龄数组第 n 项（与后端
            `toScheduleEntries` 同口径）；编码业务唯一，重复由服务端 409 兜底。
          </p>
          {vaccinationError && <p className="text-sm text-danger">{vaccinationError}</p>}
          {vaccinationHint && <p className="text-sm text-success">{vaccinationHint}</p>}

          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-fg-emphasis">疫苗类型（{vaccinations.length}）</span>
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={vaccinationStatusFilter}
              onChange={(event) => setVaccinationStatusFilter(event.target.value)}
            >
              <option value="">全部状态</option>
              {CONTENT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {vaccinationsLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : vaccinations.length === 0 ? (
            <EmptyState
              icon="💉"
              title="内容表暂无疫苗类型"
              description="库为空时消费方回落既有常量，接种状态与改造前一致；可在此按需维护"
            />
          ) : (
            <Table columns={vaccinationColumns} data={vaccinations} />
          )}
        </div>
      </Card>

      {/* ── 发育量表 ────────────────────────────────────────────────── */}
      <Card
        title={scaleEditingId ? "编辑发育量表" : "新建发育量表"}
        actions={
          scaleEditingId ? (
            <Button variant="ghost" onClick={resetScaleForm}>
              取消编辑
            </Button>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-[150px]">
              <Input label="量表编码" value={scaleCode} onChange={(event) => setScaleCode(event.target.value)} />
            </div>
            <div className="w-[130px]">
              <Input
                label="版本"
                value={scaleVersion}
                placeholder="如 v1"
                onChange={(event) => setScaleVersion(event.target.value)}
              />
            </div>
            <div className="min-w-[180px] flex-1">
              <Input label="量表名称" value={scaleName} onChange={(event) => setScaleName(event.target.value)} />
            </div>
            <div className="w-[120px]">
              <Input
                label="月龄下界"
                value={ageMinText}
                onChange={(event) => setAgeMinText(event.target.value)}
              />
            </div>
            <div className="w-[120px]">
              <Input
                label="月龄上界"
                value={ageMaxText}
                onChange={(event) => setAgeMaxText(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="base-scale-status">
                状态
              </label>
              <select
                id="base-scale-status"
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                value={scaleStatus}
                onChange={(event) => setScaleStatus(event.target.value)}
              >
                {CONTENT_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <Button onClick={() => void submitScale()} loading={scaleSaving}>
              {scaleEditingId ? "保存修改" : "创建"}
            </Button>
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-fg-muted">领域（definition.domains）</span>
            {domains.map((domain, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <div className="w-[180px]">
                  <Input
                    label={index === 0 ? "领域编码" : undefined}
                    value={domain.code}
                    onChange={(event) =>
                      setDomains((current) => updateDraftLike(current, index, { code: event.target.value }))
                    }
                  />
                </div>
                <div className="min-w-[180px] flex-1">
                  <Input
                    label={index === 0 ? "领域名称（可选）" : undefined}
                    value={domain.name}
                    onChange={(event) =>
                      setDomains((current) => updateDraftLike(current, index, { name: event.target.value }))
                    }
                  />
                </div>
                <Button
                  variant="ghost"
                  onClick={() => setDomains((current) => removeDraftLike(current, index))}
                  disabled={domains.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
            <div>
              <Button variant="secondary" onClick={() => setDomains((current) => addDraftLike(current, emptyDomainDraft()))}>
                添加领域
              </Button>
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-sm font-medium text-fg-muted">条目（definition.items）</span>
            {items.map((item, index) => (
              <div key={index} className="flex flex-col gap-2 rounded-md border border-border p-2">
                <div className="flex flex-wrap items-end gap-2">
                  <div className="w-[160px]">
                    <Input
                      label={index === 0 ? "条目编码" : undefined}
                      value={item.code}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { code: event.target.value }))
                      }
                    />
                  </div>
                  <div className="flex flex-col gap-1.5 min-w-[160px]">
                    <label className="text-sm font-medium text-fg-muted" htmlFor={`base-item-domain-${index}`}>
                      {index === 0 ? "所属领域" : undefined}
                    </label>
                    <select
                      id={`base-item-domain-${index}`}
                      className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                      value={item.domain}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { domain: event.target.value }))
                      }
                    >
                      <option value="">请选择</option>
                      {toDefinitionDomains(domains).map((domain) => (
                        <option key={domain.code} value={domain.code}>
                          {domain.name || domain.code}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="w-[110px]">
                    <Input
                      label={index === 0 ? "月龄下界" : undefined}
                      value={item.min_months}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { min_months: event.target.value }))
                      }
                    />
                  </div>
                  <div className="w-[110px]">
                    <Input
                      label={index === 0 ? "月龄上界" : undefined}
                      value={item.max_months}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { max_months: event.target.value }))
                      }
                    />
                  </div>
                  <Button
                    variant="ghost"
                    onClick={() => setItems((current) => removeDraftLike(current, index))}
                    disabled={items.length <= 1}
                  >
                    删除条目
                  </Button>
                </div>
                <div className="flex flex-wrap items-end gap-2">
                  <div className="min-w-[240px] flex-1">
                    <Input
                      label="题目文本（prompt）"
                      value={item.prompt}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { prompt: event.target.value }))
                      }
                    />
                  </div>
                  <div className="min-w-[220px] flex-1">
                    <label className="text-sm font-medium text-fg-muted" htmlFor={`base-item-options-${index}`}>
                      选项（每行 <code>值|文案</code>）
                    </label>
                    <textarea
                      id={`base-item-options-${index}`}
                      className="min-h-[64px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
                      placeholder={"0|正常\n1|异常"}
                      value={item.optionsText}
                      onChange={(event) =>
                        setItems((current) => updateDraftLike(current, index, { optionsText: event.target.value }))
                      }
                    />
                  </div>
                </div>
              </div>
            ))}
            <div>
              <Button variant="secondary" onClick={() => setItems((current) => addDraftLike(current, emptyItemDraft()))}>
                添加条目
              </Button>
            </div>
          </div>

          <p className="text-xs text-fg-dimmed">
            definition 形状由服务端校验（domains 与 items 非空、条目 domain 必须命中领域、选项非空）；
            畸形内容在加载时会被跳过并回落常量。编码 + 版本组合唯一，重复由服务端 409 兜底。
          </p>
          {scaleError && <p className="text-sm text-danger">{scaleError}</p>}
          {scaleHint && <p className="text-sm text-success">{scaleHint}</p>}

          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-fg-emphasis">发育量表（{scales.length}）</span>
            <select
              className="h-9 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={scaleStatusFilter}
              onChange={(event) => setScaleStatusFilter(event.target.value)}
            >
              <option value="">全部状态</option>
              {CONTENT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          {scalesLoading ? (
            <div className="flex justify-center py-8">
              <LoadingSpinner />
            </div>
          ) : scales.length === 0 ? (
            <EmptyState
              icon="🧩"
              title="内容表暂无发育量表"
              description="库为空时消费方回落既有常量，筛查判读结果与改造前一致；可在此按需维护"
            />
          ) : (
            <Table columns={scaleColumns} data={scales} />
          )}

          {scales.length > 0 && (
            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-fg-emphasis">量表结构速览</span>
              <span className="text-xs text-fg-muted">
                {scales
                  .map(
                    (scale) =>
                      `${scale.name}（${scale.scale_code}@${scale.scale_version}，${scaleAgeLabel(scale)}，${scaleSummary(scale)}）`,
                  )
                  .join("；")}
              </span>
              <span className="text-xs text-fg-dimmed">
                {scales
                  .flatMap((scale) =>
                    (scale.definition?.items ?? []).map(
                      (item) => `${scale.scale_code}/${item.code} 选项：${optionsLabel(item.options)}`,
                    ),
                  )
                  .join("；")}
              </span>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
