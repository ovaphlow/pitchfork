import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Table, type Column } from "@pitchfork/ui";
import {
  listPendingNurseCheckOrders,
  nurseCheckMedicalOrder,
  type NurseCheckPendingOrder,
} from "@pitchfork/shared/aceso";

const PAGE_SIZE = 50;

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function details(row: NurseCheckPendingOrder): Record<string, unknown> {
  return row.order_details ?? {};
}

/** 医嘱核对汇总：跨入住展示所有待核对（未核对）的用药医嘱，护士在此直接核对 */
export default function OrdersCheckPage() {
  const [records, setRecords] = useState<NurseCheckPendingOrder[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [checkingId, setCheckingId] = useState<string | null>(null);
  const [checkError, setCheckError] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [offset, setOffset] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const response = await listPendingNurseCheckOrders({ search: search || undefined, limit: PAGE_SIZE, offset });
      setRecords(response.records);
      setTotal(response.meta.total);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载待核对医嘱"));
    } finally {
      setLoading(false);
    }
  }, [search, offset]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleCheck = useCallback(async (order: NurseCheckPendingOrder) => {
    if (checkingId !== null) return;
    setCheckingId(order.id);
    setCheckError("");
    try {
      await nurseCheckMedicalOrder(order.id);
      // 核对成功后刷新汇总；该医嘱随即在药房待接方列表可见
      await load();
    } catch (error) {
      setCheckError(errorMessage(error, "核对失败，请稍后重试"));
    } finally {
      setCheckingId(null);
    }
  }, [checkingId, load]);

  const columns: Column<NurseCheckPendingOrder>[] = [
    { key: "patient_name", header: "长者", render: (row) => row.patient_name || row.patient_id },
    { key: "encounter_no", header: "入住号", className: "min-w-[110px]", render: (row) => row.encounter_no ?? "-" },
    { key: "drug_name", header: "药品", className: "min-w-[120px]", render: (row) => String(details(row).drug_name ?? row.order_content) },
    { key: "dose", header: "剂量", render: (row) => {
        const d = details(row);
        return d.dose ? `${String(d.dose)}${d.unit ? ` ${String(d.unit)}` : ""}` : "-";
      } },
    { key: "route", header: "给药途径", render: (row) => String(details(row).route ?? "-") },
    { key: "frequency_name", header: "频次", render: (row) => {
        const d = details(row);
        return String(d.frequency_name ?? d.frequency_code ?? "按需");
      } },
    { key: "doctor", header: "医生", className: "min-w-[90px]", render: (row) => row.doctor || "-" },
    { key: "start_time", header: "开始时间", className: "min-w-[130px]", render: (row) => formatDateTime(row.start_time) },
    { key: "status", header: "核对状态", render: (row) => (
        row.nurse_checked_at ? <Badge variant="success">已核对</Badge> : <Badge variant="warning">待核对</Badge>
      ) },
    { key: "actions", header: "操作", render: (row) => (
        row.nurse_checked_at ? (
          <span className="text-xs text-fg-dimmed">已核对</span>
        ) : (
          <Button
            size="sm"
            loading={checkingId === row.id}
            disabled={checkingId !== null}
            onClick={() => void handleCheck(row)}
          >
            核对
          </Button>
        )
      ) },
  ];

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">医嘱核对汇总</h2>
        <p className="mt-1 text-sm text-fg-muted">跨入住汇总所有待核对用药医嘱；核对确认后药房才可见并可发药</p>
      </div>

      <Card
        className="min-w-0 overflow-hidden"
        title="待核对用药医嘱"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              placeholder="搜索药名 / 医嘱内容 / 老人 / 入住号"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  setSearch(searchInput.trim());
                  setOffset(0);
                }
              }}
              className="w-64"
            />
            <Button
              variant="secondary"
              onClick={() => {
                setSearch(searchInput.trim());
                setOffset(0);
              }}
            >
              查询
            </Button>
            <Button variant="link" loading={loading} onClick={() => void load()}>
              刷新
            </Button>
          </div>
        }
      >
        {checkError && <div className="mb-3 rounded border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">{checkError}</div>}
        {pageError ? (
          <p className="py-10 text-center text-sm text-danger">{pageError}</p>
        ) : records.length === 0 && !loading ? (
          <EmptyState
            icon="✅"
            title="暂无待核对用药医嘱"
            description="当前所有活动养老入住下的用药医嘱均已由护士核对。"
          />
        ) : (
          <Table
            className="min-w-[1100px]"
            columns={columns}
            data={records}
            loading={loading}
            emptyMessage="暂无匹配的待核对用药医嘱"
          />
        )}
        {total > PAGE_SIZE && (
          <div className="flex items-center justify-between border-t border-border px-5 py-3">
            <span className="text-xs text-fg-dimmed">
              共 {total} 条 · 第 {Math.floor(offset / PAGE_SIZE) + 1} 页
            </span>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset((value) => Math.max(0, value - PAGE_SIZE))}>
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={offset + PAGE_SIZE >= total}
                onClick={() => setOffset((value) => value + PAGE_SIZE)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}
