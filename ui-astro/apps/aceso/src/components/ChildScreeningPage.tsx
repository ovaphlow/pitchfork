import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  LoadingSpinner,
  Modal,
  Table,
  type Column,
} from "@pitchfork/ui";
import {
  concludeChildDevelopmentalScreening,
  createChildDevelopmentalScreening,
  createChildReferral,
  listChildDevelopmentalScreenings,
  listPatients,
  type ChildDevelopmentalScreening,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDateTime, nowLocalInput, toOffsetDateTime } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import {
  CLASSIFICATIONS,
  PLACEHOLDER_SCALE_CODE,
  SCALE,
  ageAppropriateItems,
  canRefer,
  previewScreening,
  scoreScreening,
} from "../lib/developmentalScales";
import { ageMonthsBetween } from "../lib/growth";
import { useDomain } from "../lib/useDomain";

/**
 * 发育筛查页（053 A4）。
 *
 * 按当前月龄取占位量表（`lib/developmentalScales.ts`，与后端 A1 同源）→ 逐项作答 →
 * 实时领域分/总分 → 完成全部适龄条目后给出判读与触发项 → 提交落库（`createChildDevelopmentalScreening`，
 * 写码 `health:manage`）；结果视图可对「可疑/异常」一键判读转诊（`concludeChildDevelopmentalScreening`
 * + `createChildReferral`，写码 `clinical:write`）。所有请求经 `@pitchfork/shared/aceso`，
 * token 注入 / JSON / 401 处理复用共享客户端。
 *
 * 服务端会按 `birth_date` 与 `administered_at` 的**业务日**重算月龄与判读，客户端不得提交
 * `classification` / `domain_scores` 等派生字段（053 A2）。
 */

const PAGE_SIZE = 20;

/** 转诊类型白名单（与后端 `ChildReferralService.referralTypes` 同值）。 */
const REFERRAL_TYPES = ["院内转诊", "转上级医院", "专科转诊"];

/** 紧急度白名单（与后端 `ChildReferralService.urgencies` 同值）。 */
const URGENCIES = ["常规", "急"];

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function classificationBadge(classification: string | null | undefined) {
  if (classification === CLASSIFICATIONS.NORMAL) return <Badge variant="success">正常</Badge>;
  if (classification === CLASSIFICATIONS.SUSPECT) return <Badge variant="warning">可疑</Badge>;
  if (classification === CLASSIFICATIONS.ABNORMAL) return <Badge variant="danger">异常</Badge>;
  return <Badge>{classification ?? "未判读"}</Badge>;
}

/** 条目编码 → 提示语（历史记录回显触发项用）。 */
function itemPrompt(code: string): string {
  return SCALE.items.find((item) => item.code === code)?.prompt ?? code;
}

export default function ChildScreeningPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [records, setRecords] = useState<ChildDevelopmentalScreening[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [administeredAt, setAdministeredAt] = useState(nowLocalInput());
  const [answers, setAnswers] = useState<Record<string, number>>({});
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");
  const [savedHint, setSavedHint] = useState("");

  // 结果视图（判读 + 转诊）。
  const [resultTarget, setResultTarget] = useState<ChildDevelopmentalScreening | null>(null);
  const [conclusion, setConclusion] = useState("");
  const [referralType, setReferralType] = useState(REFERRAL_TYPES[0] ?? "院内转诊");
  const [urgency, setUrgency] = useState(URGENCIES[0] ?? "常规");
  const [targetOrg, setTargetOrg] = useState("");
  const [targetDept, setTargetDept] = useState("");
  const [problemSummary, setProblemSummary] = useState("");
  const [resultSaving, setResultSaving] = useState(false);
  const [resultError, setResultError] = useState("");
  const [resultHint, setResultHint] = useState("");
  const [referralDone, setReferralDone] = useState(false);

  const selected = useMemo(() => children.find((child) => child.id === patientId) ?? null, [children, patientId]);

  // 月龄按「给药/执行日」的业务日派生，与后端 A2 的 age_months 口径一致。
  const administeredDate = administeredAt.slice(0, 10);
  const ageMonths = selected ? ageMonthsBetween(selected.birth_date, administeredDate) : null;

  const items = useMemo(
    () => (ageMonths === null ? [] : ageAppropriateItems(ageMonths)),
    [ageMonths],
  );

  const preview = useMemo(
    () => (ageMonths === null ? null : previewScreening(ageMonths, answers)),
    [ageMonths, answers],
  );

  const score = useMemo(() => {
    if (ageMonths === null || items.length === 0) return null;
    if (items.some((item) => answers[item.code] === undefined)) return null;
    try {
      return scoreScreening(ageMonths, answers);
    } catch {
      return null;
    }
  }, [ageMonths, items, answers]);

  const answeredCount = items.filter((item) => answers[item.code] !== undefined).length;
  const complete = items.length > 0 && answeredCount === items.length;

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

  // 换儿童或月龄档变化时清空作答（旧条目在新档下是「多余项」）。
  useEffect(() => {
    setAnswers({});
    setSavedHint("");
    setFormError("");
  }, [patientId, ageMonths]);

  const load = useCallback(async () => {
    if (!patientId) {
      setRecords([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const response = await listChildDevelopmentalScreenings({
        patient_id: patientId,
        limit: PAGE_SIZE,
        offset: (page - 1) * PAGE_SIZE,
      });
      setRecords(response.records);
      setTotal(response.meta.total);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载筛查记录"));
    } finally {
      setLoading(false);
    }
  }, [patientId, page]);

  useEffect(() => {
    void load();
  }, [load]);

  function openResult(row: ChildDevelopmentalScreening) {
    setResultTarget(row);
    setConclusion(row.conclusion ?? "");
    setReferralType(REFERRAL_TYPES[0] ?? "院内转诊");
    setUrgency(URGENCIES[0] ?? "常规");
    setTargetOrg("");
    setTargetDept("");
    setProblemSummary(
      `发育筛查判读：${row.classification}（总分 ${row.total_score}/${row.max_total_score}）`,
    );
    setResultError("");
    setResultHint("");
    setReferralDone(false);
  }

  async function submit() {
    if (!patientId || ageMonths === null || !complete) return;
    setSaving(true);
    setFormError("");
    setSavedHint("");
    try {
      const created = await createChildDevelopmentalScreening({
        patient_id: patientId,
        scale_code: PLACEHOLDER_SCALE_CODE,
        administered_at: toOffsetDateTime(administeredAt),
        item_scores: answers,
      });
      setSavedHint(`已保存筛查记录（判读：${created.classification}）`);
      setAnswers({});
      openResult(created);
      await load();
    } catch (cause) {
      setFormError(errorMessage(cause, "保存筛查记录失败"));
    } finally {
      setSaving(false);
    }
  }

  async function saveConclusion() {
    if (!resultTarget || !conclusion.trim()) return;
    setResultSaving(true);
    setResultError("");
    setResultHint("");
    try {
      const updated = await concludeChildDevelopmentalScreening(resultTarget.id, {
        conclusion: conclusion.trim(),
      });
      setResultTarget(updated);
      setResultHint("已保存判读结论");
      await load();
    } catch (cause) {
      setResultError(errorMessage(cause, "保存判读结论失败"));
    } finally {
      setResultSaving(false);
    }
  }

  async function submitReferral() {
    if (!resultTarget || !canRefer(resultTarget.classification) || !problemSummary.trim()) return;
    setResultSaving(true);
    setResultError("");
    setResultHint("");
    try {
      // 053 A4 口径：先判读（若结论有改动），再按需建转诊。
      const trimmed = conclusion.trim();
      if (trimmed && trimmed !== (resultTarget.conclusion ?? "")) {
        const updated = await concludeChildDevelopmentalScreening(resultTarget.id, { conclusion: trimmed });
        setResultTarget(updated);
      }
      const referral = await createChildReferral({
        patient_id: resultTarget.patient_id,
        source_type: "筛查",
        source_id: resultTarget.id,
        problem_summary: problemSummary.trim(),
        referral_type: referralType,
        urgency,
        target_org: targetOrg.trim() || undefined,
        target_dept: targetDept.trim() || undefined,
      });
      setReferralDone(true);
      setResultHint(`已创建转诊记录（${referral.status}）`);
      await load();
    } catch (cause) {
      setResultError(errorMessage(cause, "创建转诊记录失败"));
    } finally {
      setResultSaving(false);
    }
  }

  const columns: Column<ChildDevelopmentalScreening>[] = [
    { key: "administered_at", header: "筛查时间", render: (row) => formatDateTime(row.administered_at) },
    { key: "age_months", header: "月龄", render: (row) => `${row.age_months} 月` },
    {
      key: "scale_version",
      header: "量表",
      render: (row) => (
        <span className="text-xs text-fg-muted">
          {row.scale_code} v{row.scale_version}
        </span>
      ),
    },
    {
      key: "total_score",
      header: "得分",
      render: (row) => (
        <span>
          {row.total_score}
          <span className="text-fg-dimmed"> / {row.max_total_score}</span>
        </span>
      ),
    },
    { key: "classification", header: "判读", render: (row) => classificationBadge(row.classification) },
    {
      key: "triggered_items",
      header: "触发项",
      render: (row) => (row.triggered_items.length > 0 ? `${row.triggered_items.length} 项` : "-"),
    },
    {
      key: "conclusion",
      header: "结论",
      render: (row) => (
        <span className="text-xs text-fg-muted">{row.conclusion ? row.conclusion : "未判读"}</span>
      ),
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="ghost" onClick={() => openResult(row)}>
          判读/转诊
        </Button>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <Card title={`发育筛查（${entity.person}）`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="screening-patient">
              选择{entity.person}
            </label>
            <select
              id="screening-patient"
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
          <Input
            label="筛查时间"
            type="datetime-local"
            value={administeredAt}
            max={nowLocalInput()}
            onChange={(event) => setAdministeredAt(event.target.value)}
          />
          {selected && ageMonths !== null && (
            <span className="text-sm text-fg-muted">
              当前 {formatAge(selected.birth_date)}（{ageMonths} 月）· 量表 {SCALE.code} v{SCALE.version}
            </span>
          )}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      <Card
        title="量表作答"
        actions={
          <span className="text-sm text-fg-muted">
            已作答 {answeredCount} / {items.length}
          </span>
        }
      >
        {ageMonths === null ? (
          <EmptyState icon="🧩" title="请选择儿童" description="选择儿童后按其当前月龄加载适龄条目" />
        ) : items.length === 0 ? (
          <EmptyState icon="🧩" title="无适龄条目" description="该月龄超出占位量表适用区间（0–60 月）" />
        ) : (
          <div className="flex flex-col gap-4">
            {items.map((item) => {
              const domainName = SCALE.domains.find((entry) => entry.code === item.domain)?.name ?? item.domain;
              return (
                <div key={item.code} className="flex flex-col gap-2 rounded-md border border-border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="info">{domainName}</Badge>
                    <span className="text-sm text-fg-emphasis">{item.prompt}</span>
                    <span className="text-xs text-fg-dimmed">{item.code}</span>
                  </div>
                  <div className="flex flex-wrap gap-4">
                    {item.options.map((option) => (
                      <label key={option.value} className="flex items-center gap-2 text-sm text-fg">
                        <input
                          type="radio"
                          name={item.code}
                          value={option.value}
                          checked={answers[item.code] === option.value}
                          onChange={() => setAnswers((current) => ({ ...current, [item.code]: option.value }))}
                        />
                        {option.label}
                        <span className="text-xs text-fg-dimmed">（{option.value} 分）</span>
                      </label>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {ageMonths !== null && items.length > 0 && (
        <Card title="实时计分与判读">
          <div className="flex flex-col gap-3">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {SCALE.domains.map((entry) => (
                <div key={entry.code} className="rounded-md border border-border p-3">
                  <p className="text-xs text-fg-dimmed">{entry.name}</p>
                  <p className="text-lg font-semibold text-fg-emphasis">
                    {preview?.domainScores[entry.code] ?? 0}
                    <span className="text-sm text-fg-dimmed"> / {preview?.domainMaxScores[entry.code] ?? 0}</span>
                  </p>
                </div>
              ))}
              <div className="rounded-md border border-border p-3">
                <p className="text-xs text-fg-dimmed">总分</p>
                <p className="text-lg font-semibold text-fg-emphasis">
                  {preview?.totalScore ?? 0}
                  <span className="text-sm text-fg-dimmed"> / {preview?.maxTotalScore ?? 0}</span>
                </p>
              </div>
            </div>

            {score ? (
              <div className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm text-fg-muted">判读</span>
                  {classificationBadge(score.classification)}
                </div>
                {score.triggeredItems.length > 0 && (
                  <div className="text-sm text-fg-muted">
                    触发项：
                    {score.triggeredItems.map((code) => (
                      <span key={code} className="mr-3 inline-block text-warning">
                        {itemPrompt(code)}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <p className="text-sm text-fg-dimmed">完成全部适龄条目后自动给出判读与触发项。</p>
            )}

            {formError && <p className="text-sm text-danger">{formError}</p>}
            {savedHint && <p className="text-sm text-success">{savedHint}</p>}
            <div className="flex justify-end">
              <Button onClick={submit} loading={saving} disabled={!patientId || !complete}>
                提交筛查记录
              </Button>
            </div>
          </div>
        </Card>
      )}

      <Card
        title={`筛查记录（${total}）`}
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
          <EmptyState icon="🧩" title="暂无筛查记录" description="选择儿童后完成量表作答并提交" />
        ) : (
          <Table columns={columns} data={records} />
        )}
      </Card>

      <Modal
        open={resultTarget !== null}
        onClose={() => setResultTarget(null)}
        title="筛查结果判读与转诊"
        width="720px"
      >
        {resultTarget && (
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm text-fg-muted">判读</span>
              {classificationBadge(resultTarget.classification)}
              <span className="text-sm text-fg-muted">
                总分 {resultTarget.total_score} / {resultTarget.max_total_score} · {resultTarget.age_months} 月 ·{" "}
                {formatDateTime(resultTarget.administered_at)}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {SCALE.domains.map((entry) => (
                <div key={entry.code} className="rounded-md border border-border p-3">
                  <p className="text-xs text-fg-dimmed">{entry.name}</p>
                  <p className="text-lg font-semibold text-fg-emphasis">
                    {resultTarget.domain_scores[entry.code] ?? 0}
                    <span className="text-sm text-fg-dimmed"> / {resultTarget.domain_max_scores[entry.code] ?? 0}</span>
                  </p>
                </div>
              ))}
            </div>

            {resultTarget.triggered_items.length > 0 && (
              <div className="text-sm text-fg-muted">
                触发项：
                {resultTarget.triggered_items.map((code) => (
                  <span key={code} className="mr-3 inline-block text-warning">
                    {itemPrompt(code)}
                  </span>
                ))}
              </div>
            )}

            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="screening-conclusion">
                医生判读结论
              </label>
              <textarea
                id="screening-conclusion"
                className="min-h-[96px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
                value={conclusion}
                onChange={(event) => setConclusion(event.target.value)}
              />
              <div className="flex justify-end">
                <Button variant="secondary" onClick={saveConclusion} loading={resultSaving} disabled={!conclusion.trim()}>
                  保存判读结论
                </Button>
              </div>
            </div>

            {canRefer(resultTarget.classification) ? (
              <div className="flex flex-col gap-3 rounded-md border border-warning/40 p-3">
                <span className="text-sm font-semibold text-fg-emphasis">一键转诊（判读为「可疑/异常」）</span>
                <div className="grid grid-cols-2 gap-3">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="referral-type">
                      转诊类型
                    </label>
                    <select
                      id="referral-type"
                      className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                      value={referralType}
                      onChange={(event) => setReferralType(event.target.value)}
                    >
                      {REFERRAL_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {type}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted" htmlFor="referral-urgency">
                      紧急度
                    </label>
                    <select
                      id="referral-urgency"
                      className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
                      value={urgency}
                      onChange={(event) => setUrgency(event.target.value)}
                    >
                      {URGENCIES.map((level) => (
                        <option key={level} value={level}>
                          {level}
                        </option>
                      ))}
                    </select>
                  </div>
                  <Input label="转诊机构（可选）" value={targetOrg} onChange={(event) => setTargetOrg(event.target.value)} />
                  <Input label="转诊科室（可选）" value={targetDept} onChange={(event) => setTargetDept(event.target.value)} />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="referral-summary">
                    问题摘要
                  </label>
                  <textarea
                    id="referral-summary"
                    className="min-h-[72px] px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg"
                    value={problemSummary}
                    onChange={(event) => setProblemSummary(event.target.value)}
                  />
                </div>
                <div className="flex justify-end">
                  <Button
                    onClick={submitReferral}
                    loading={resultSaving}
                    disabled={referralDone || !problemSummary.trim()}
                  >
                    {referralDone ? "已转诊" : "创建转诊记录"}
                  </Button>
                </div>
              </div>
            ) : (
              <p className="text-sm text-fg-dimmed">判读为「正常」，无需转诊。</p>
            )}

            {resultError && <p className="text-sm text-danger">{resultError}</p>}
            {resultHint && <p className="text-sm text-success">{resultHint}</p>}

            <div className="flex justify-end">
              <Button variant="ghost" onClick={() => setResultTarget(null)}>
                关闭
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
