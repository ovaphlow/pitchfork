import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, EmptyState, Input, LoadingSpinner } from "@pitchfork/ui";
import {
  getChildHealthReport,
  listPatients,
  type ChildHealthReport,
  type Patient,
} from "@pitchfork/shared/aceso";
import { formatAge } from "../lib/age";
import { formatDate, todayLocal } from "../lib/datetime";
import { DOMAIN_ENTITY } from "../lib/domain";
import {
  buildReportSections,
  filterReportByRange,
  reportFileName,
  toReportCsv,
  toReportJson,
  formatReportDateTime,
} from "../lib/reportModel";
import { useDomain } from "../lib/useDomain";

/**
 * 儿童健康报告页（053 C2）。
 *
 * 选择儿童后调用 C1 的 `getChildHealthReport`（`@pitchfork/shared/aceso`，token/JSON/401
 * 由共享客户端负责），渲染**可打印视图**（浏览器打印→PDF），并提供 JSON / CSV 本地导出。
 * 报告**不落服务端**（053 §11 裁定 7）；报告 DTO 不含身份证号，导出再经 `reportModel`
 * 剔除敏感键（053 §3 Dh）。
 */

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 触发浏览器下载（纯前端，不落服务端）。 */
function downloadFile(filename: string, content: string, mime: string): void {
  if (typeof window === "undefined") return;
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

export default function ChildReportPage() {
  const domain = useDomain();
  const entity = DOMAIN_ENTITY[domain];

  const [children, setChildren] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState("");
  const [report, setReport] = useState<ChildHealthReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

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
      setReport(null);
      return;
    }
    setLoading(true);
    try {
      const response = await getChildHealthReport(patientId);
      setReport(response);
      setError("");
    } catch (cause) {
      setError(errorMessage(cause, "无法加载儿童健康报告"));
    } finally {
      setLoading(false);
    }
  }, [patientId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 时间区间筛选：仅影响展示与导出，不改后端契约（053 §4 C2）。
  const view = useMemo(() => {
    if (!report) return null;
    if (!from && !to) return report;
    return filterReportByRange(report, from, to);
  }, [report, from, to]);

  const sections = useMemo(() => (view ? buildReportSections(view) : []), [view]);

  const fileNameBase = useMemo(
    () => (report ? reportFileName(report.patient.name, report.meta.generated_at) : "儿童健康报告"),
    [report],
  );

  function handlePrint() {
    if (typeof window !== "undefined") window.print();
  }

  function handleExportJson() {
    if (!view) return;
    downloadFile(`${fileNameBase}.json`, toReportJson(view), "application/json;charset=utf-8");
  }

  function handleExportCsv() {
    if (!view) return;
    downloadFile(`${fileNameBase}.csv`, toReportCsv(view), "text/csv;charset=utf-8");
  }

  return (
    <div className="flex flex-col gap-5">
      <Card title={`儿童健康报告（${entity.person}）`} className="print:hidden">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5 min-w-[240px]">
            <label className="text-sm font-medium text-fg-muted" htmlFor="report-patient">
              选择{entity.person}
            </label>
            <select
              id="report-patient"
              className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg"
              value={patientId}
              onChange={(event) => setPatientId(event.target.value)}
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
            label="起始日期"
            type="date"
            value={from}
            max={to || todayLocal()}
            onChange={(event) => setFrom(event.target.value)}
          />
          <Input
            label="截止日期"
            type="date"
            value={to}
            min={from || undefined}
            max={todayLocal()}
            onChange={(event) => setTo(event.target.value)}
          />
          <div className="ml-auto flex flex-wrap items-center gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={!patientId || loading}>
              刷新
            </Button>
            <Button variant="secondary" onClick={handlePrint} disabled={!view}>
              打印
            </Button>
            <Button variant="secondary" onClick={handleExportJson} disabled={!view}>
              导出 JSON
            </Button>
            <Button variant="secondary" onClick={handleExportCsv} disabled={!view}>
              导出 CSV
            </Button>
          </div>
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      </Card>

      {loading ? (
        <Card>
          <div className="flex justify-center py-10">
            <LoadingSpinner />
          </div>
        </Card>
      ) : !view ? (
        <Card>
          <EmptyState icon="📄" title="请选择儿童" description="选择儿童后生成可打印的健康报告，并支持 JSON/CSV 导出" />
        </Card>
      ) : (
        <Card bodyClassName="p-6">
          <div id="child-report-printable" className="flex flex-col">
            <header className="mb-6 border-b border-border pb-4">
              <h1 className="text-xl font-bold text-fg-emphasis">儿童健康报告</h1>
              <p className="mt-2 text-sm text-fg-muted">
                {view.patient.name ?? "未知"} · {view.patient.gender || "性别未知"} · 出生日期{" "}
                {formatDate(view.patient.birth_date)} · {formatAge(view.patient.birth_date)}
              </p>
              <p className="mt-1 text-xs text-fg-dimmed">
                报告生成时间 {formatReportDateTime(view.meta.generated_at)}
                {(from || to) && ` · 已按 ${from || "起始"} ~ ${to || "至今"} 过滤展示`}
              </p>
            </header>

            {sections.map((section) => (
              <section key={section.key} className="mb-6 break-inside-avoid">
                <h2 className="mb-2 text-base font-semibold text-fg-emphasis">{section.label}</h2>
                {section.rows.length === 0 ? (
                  <p className="text-sm text-fg-dimmed">（无记录）</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full border-collapse text-sm">
                      <thead>
                        <tr>
                          {section.headers.map((header) => (
                            <th
                              key={header}
                              className="border border-border bg-surface-alt px-2 py-1.5 text-left font-medium text-fg-muted"
                            >
                              {header}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {section.rows.map((row, rowIndex) => (
                          <tr key={rowIndex}>
                            {row.map((cell, cellIndex) => (
                              <td key={cellIndex} className="border border-border px-2 py-1.5 align-top text-fg">
                                {cell}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            ))}
          </div>
        </Card>
      )}

      {selected && !view && !loading && !error && (
        <p className="text-sm text-fg-dimmed">已选择 {selected.name}，正在生成报告…</p>
      )}
    </div>
  );
}
