import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createDeposit,
  createDepositRefund,
  listDeposits,
  listElderlyAdmissions,
  listPatients,
  type DepositLedger,
  type DepositRecord,
  type DepositType,
  type Encounter,
} from "@pitchfork/shared/aceso";
import { formatDateTime } from "../lib/datetime";
import { billingErrorMessage } from "./billingMessages";

const PAGE_SIZE = 50;

const ENCOUNTER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "在住",
  DISCHARGED: "已离院",
  TRANSFERRED: "已转出",
  DECEASED: "已去世",
};

const STATUS_BADGE_VARIANT: Record<string, "success" | "default" | "warning" | "danger"> = {
  ACTIVE: "success",
  DISCHARGED: "default",
  TRANSFERRED: "warning",
  DECEASED: "danger",
};

interface Admission extends Encounter {
  patientName: string;
}

/** 收费域错误中文化（映射表见 ./billingMessages）；保留本地同名包装以减少调用点改动 */
function errorMessage(error: unknown, fallback: string): string {
  return billingErrorMessage(error, fallback);
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

export default function DepositsPage() {
  const [admissions, setAdmissions] = useState<Admission[]>([]);
  const [admissionsLoading, setAdmissionsLoading] = useState(true);
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [ledger, setLedger] = useState<DepositLedger | null>(null);
  const [ledgerLoading, setLedgerLoading] = useState(false);
  const [pageError, setPageError] = useState("");
  const [actionError, setActionError] = useState("");
  const [modal, setModal] = useState<"登记" | "退押" | null>(null);
  const [amount, setAmount] = useState("");
  const [remark, setRemark] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const loadAdmissions = useCallback(async () => {
    setAdmissionsLoading(true);
    setPageError("");
    try {
      // 含已离院/已去世入住（离院后仍可退押），ACTIVE 优先排列
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ limit: 200 }),
        listElderlyAdmissions({ status: "", limit: 200 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      const statusRank: Record<string, number> = { ACTIVE: 0, DISCHARGED: 1, DECEASED: 2, TRANSFERRED: 3 };
      records.sort((a, b) => (statusRank[a.status] ?? 9) - (statusRank[b.status] ?? 9) || (b.admit_date ?? "").localeCompare(a.admit_date ?? ""));
      setAdmissions(records);
      setSelectedEncounterId((current) => {
        if (records.some((record) => record.id === current)) return current;
        return records.find((record) => record.status === "ACTIVE")?.id || records[0]?.id || "";
      });
    } catch (error) {
      setPageError(errorMessage(error, "无法加载入住列表"));
    } finally {
      setAdmissionsLoading(false);
    }
  }, []);

  const loadLedger = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setLedger(null);
      return;
    }
    setLedgerLoading(true);
    setActionError("");
    try {
      setLedger(await listDeposits(encounterId, { limit: PAGE_SIZE }));
    } catch (error) {
      setActionError(errorMessage(error, "无法加载押金台账"));
    } finally {
      setLedgerLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAdmissions();
  }, [loadAdmissions]);

  useEffect(() => {
    void loadLedger(selectedEncounterId);
  }, [selectedEncounterId, loadLedger]);

  const selectedAdmission = useMemo(
    () => admissions.find((admission) => admission.id === selectedEncounterId) ?? null,
    [admissions, selectedEncounterId],
  );

  /** 台账汇总：按类型显式分流，「核销」不再被并入退押 */
  const totals = useMemo((): Record<DepositType, number> => {
    const initial: Record<DepositType, number> = { 登记: 0, 退押: 0, 核销: 0 };
    if (!ledger) return initial;
    return ledger.records.reduce((acc, record) => {
      acc[record.type] += record.amount;
      return acc;
    }, initial);
  }, [ledger]);

  /** 台账中是否存在结算关账写入的核销记录（用于展示来源说明） */
  const hasOffsetRecords = useMemo(
    () => (ledger?.records ?? []).some((record) => record.type === "核销"),
    [ledger],
  );

  /** 核销记录关联的账单 ID（deposit_records.metadata.bill_id，服务端写入） */
  function offsetBillId(record: DepositRecord): string | null {
    const billId = record.metadata?.bill_id;
    return typeof billId === "string" && billId ? billId : null;
  }

  function openModal(kind: "登记" | "退押") {
    setModal(kind);
    setAmount("");
    setRemark("");
    setActionError("");
  }

  async function handleSubmit() {
    if (!selectedEncounterId) return;
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setActionError("金额必须为正数");
      return;
    }
    setSubmitting(true);
    setActionError("");
    try {
      const input = { amount: parsed, ...(remark.trim() ? { remark: remark.trim() } : {}) };
      if (modal === "登记") {
        await createDeposit(selectedEncounterId, input);
      } else {
        await createDepositRefund(selectedEncounterId, input);
      }
      setModal(null);
      await loadLedger(selectedEncounterId);
    } catch (error) {
      setActionError(errorMessage(error, "操作失败"));
    } finally {
      setSubmitting(false);
    }
  }

  const columns: Column<DepositRecord>[] = [
    {
      key: "created_at",
      header: "时间",
      render: (row) => <span className="text-fg-muted text-sm">{formatDateTime(row.created_at, "—")}</span>,
    },
    {
      key: "type",
      header: "类型",
      render: (row) => (
        <Badge variant={row.type === "登记" ? "success" : row.type === "核销" ? "info" : "warning"}>{row.type}</Badge>
      ),
    },
    {
      key: "amount",
      header: "金额（元）",
      render: (row) => (
        <span
          className={
            row.type === "登记"
              ? "text-success font-medium"
              : row.type === "核销"
                ? "text-accent font-medium"
                : "text-warning font-medium"
          }
        >
          {row.type === "登记" ? "+" : "−"}{formatAmount(row.amount)}
        </span>
      ),
    },
    { key: "operator", header: "操作人", render: (row) => <span className="text-fg-muted text-sm">{row.operator}</span> },
    {
      key: "remark",
      header: "备注",
      render: (row) => {
        const billId = row.type === "核销" ? offsetBillId(row) : null;
        return (
          <span className="text-fg-muted text-sm">
            {row.remark ?? (row.type === "核销" ? "结算关账押金核销" : "—")}
            {billId ? `（关联账单 ${billId}）` : ""}
          </span>
        );
      },
    },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">押金管理</h2>
        <p className="text-sm text-fg-muted mt-1">
          入住押金登记、退押与台账。退押仍是独立操作：结算关账不会自动冲抵押金，离院/去世后仍可退押。
          押金核销不在本页操作，而是在「养老收费 → 结算关账」时手工选择核销金额；核销会同时记入本台账（类型「核销」）。
        </p>
      </div>

      {pageError && (
        <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>
      )}

      <Card title="入住选择" actions={
        <Button variant="ghost" size="sm" onClick={() => void loadAdmissions()} disabled={admissionsLoading}>
          刷新
        </Button>
      }>
        {admissionsLoading ? (
          <div className="flex justify-center py-8"><LoadingSpinner /></div>
        ) : admissions.length === 0 ? (
          <EmptyState icon="🏠" title="暂无入住记录" description="请先在「入住管理」登记养老入住" />
        ) : (
          <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_auto] items-end">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">入住（encounter）</label>
              <select
                value={selectedEncounterId}
                onChange={(event) => setSelectedEncounterId(event.target.value)}
                className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                {admissions.map((admission) => (
                  <option key={admission.id} value={admission.id}>
                    {admission.patientName} · {admission.encounter_no}（{ENCOUNTER_STATUS_LABEL[admission.status] ?? admission.status}）
                  </option>
                ))}
              </select>
            </div>
            {selectedAdmission && (
              <div className="flex items-center gap-3 pb-1">
                <Badge variant={STATUS_BADGE_VARIANT[selectedAdmission.status] ?? "default"}>
                  {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
                </Badge>
                <span className="text-sm text-fg-muted">
                  {selectedAdmission.patientName} · {selectedAdmission.department ?? "—"} {selectedAdmission.ward ?? ""}
                </span>
              </div>
            )}
          </div>
        )}
      </Card>

      {selectedEncounterId && (
        <>
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <Card title="当前押金余额">
              <div className="text-2xl font-bold text-accent">
                {ledger ? `¥ ${formatAmount(ledger.meta.balance)}` : "—"}
              </div>
              <p className="text-xs text-fg-dimmed mt-1">余额 = 累计登记 − 累计退押 − 累计核销（不为负）</p>
            </Card>
            <Card title="累计登记">
              <div className="text-2xl font-bold text-success">{ledger ? `¥ ${formatAmount(totals.登记)}` : "—"}</div>
              <p className="text-xs text-fg-dimmed mt-1">本页展示的登记金额合计</p>
            </Card>
            <Card title="累计退押">
              <div className="text-2xl font-bold text-warning">{ledger ? `¥ ${formatAmount(totals.退押)}` : "—"}</div>
              <p className="text-xs text-fg-dimmed mt-1">本页展示的退押金额合计</p>
            </Card>
            <Card title="累计核销">
              <div className="text-2xl font-bold text-accent">{ledger ? `¥ ${formatAmount(totals.核销)}` : "—"}</div>
              <p className="text-xs text-fg-dimmed mt-1">结算关账时用押金抵扣欠费的金额合计</p>
            </Card>
          </div>

          <Card
            title="押金台账"
            actions={
              <div className="flex items-center gap-2">
                <Button size="sm" onClick={() => openModal("登记")} disabled={!selectedEncounterId}>登记押金</Button>
                <Button size="sm" variant="warning" onClick={() => openModal("退押")} disabled={!selectedEncounterId}>退押</Button>
              </div>
            }
          >
            {actionError && !modal && (
              <div className="mb-4 rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
            )}
            {hasOffsetRecords && (
              <p className="mb-4 text-xs text-fg-dimmed">
                核销：结算关账时用押金抵扣欠费，同时写入缴费流水（方式「押金」），关联账单见养老收费页。
              </p>
            )}
            <Table
              columns={columns}
              data={ledger?.records ?? []}
              keyField="id"
              loading={ledgerLoading}
              emptyMessage="暂无押金记录"
            />
            {ledger && ledger.meta.total > 0 && (
              <p className="text-xs text-fg-dimmed mt-3">
                共 {ledger.meta.total} 条记录（当前显示最近 {ledger.records.length} 条）
              </p>
            )}
          </Card>
        </>
      )}

      <Modal open={modal !== null} onClose={() => setModal(null)} title={modal === "登记" ? "登记押金" : "退押"}>
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            {modal === "登记"
              ? "登记入住押金：金额记入当前入住押金余额。"
              : "退押为独立操作：离院/去世后仍可退押；累计退押不得超过当前余额。"}
          </p>
          <Input
            label="金额（元）"
            type="number"
            min="0.01"
            step="0.01"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="如 5000.00"
          />
          <Input
            label="备注（可选）"
            value={remark}
            onChange={(event) => setRemark(event.target.value)}
            placeholder="登记/退押原因"
            maxLength={500}
          />
          {actionError && (
            <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setModal(null)}>取消</Button>
            <Button variant={modal === "登记" ? "primary" : "warning"} loading={submitting} onClick={() => void handleSubmit()}>
              确认{modal === "登记" ? "登记" : "退押"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
