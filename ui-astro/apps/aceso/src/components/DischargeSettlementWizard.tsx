// 离院结算向导（实施计划 030 §4.7 / W4）
//
// 把「已离院/已去世」养老长者的账单收尾串成一个三步向导：
//   ① 生成区间最终账单（关账时自动完成，说明性步骤）
//   ② 核销关账（押金核销 + 未结减免留痕）
//   ③ 退还押金余额
// 每一步显示状态（已完成 / 进行中 / 未开始）与下一步动作，未完成前不放出下一步。
//
// 数据来源（组件自洽：自己加载、自己处理错误，宿主页只提供 encounterId）：
//   - 结算预览：`previewEncounterBilling`（只读、可反复调用，与关账执行 `settleEncounterBilling`
//     共用同一套资格校验、区间口径与押金余额口径）；
//   - 「已关账」判定：**previewEncounterBilling**。已关账时它返回 409
//     `encounter billing is already settled`（BillService.settlementContext），是与关账执行同源的
//     权威信号，也正是「结算关账」这一业务状态的资格拒绝分支。故不采用 `listBills`：账单列表接口
//     不返回 `settled_at`，且「已关账但一条账单都没有」的入住无法与「未关账」区分。
//   - 押金余额：`listDeposits(...).meta.balance`，与 DepositsPage「当前押金余额」卡片、
//     BillingPage 结算入口完全同源（Σ登记 − Σ退押 − Σ核销），不另造一套求和口径。
//
// 本组件只做只读预览与显式点击提交，不做任何自动提交；所有失败都经 `billingMessages.ts`
// 映射为中文，资格类失败（未离院/未去世等）另外给出「下一步」指引。

import { useCallback, useEffect, useMemo, useRef, useState, type JSX } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner } from "@pitchfork/ui";
import {
  ApiRequestError,
  createDepositRefund,
  listDeposits,
  previewEncounterBilling,
  settleEncounterBilling,
  type SettlementPreview,
} from "@pitchfork/shared/aceso";
import { formatDate } from "../lib/datetime";
import { billingErrorMessage } from "./billingMessages";

/** 减免原因的服务端上限（trim 后字符数），与 POST billing-settlement 契约一致 */
const WRITE_OFF_REASON_MAX_LENGTH = 500;

/** 退押备注上限（与押金登记/退押弹窗一致） */
const REFUND_REMARK_MAX_LENGTH = 500;

/** 金额输入：非负、至多两位小数（与养老收费各页既有口径一致） */
const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/** 押金余额探针的分页大小：余额取 `meta.balance`（与条数无关），1 条足够 */
const DEPOSIT_PROBE_LIMIT = 1;

/** 金额「为零」的容差：金额均为两位小数，容忍浮点求和的极小误差 */
const ZERO_AMOUNT_EPSILON = 0.005;

/**
 * 「已关账」的权威错误文案：`previewEncounterBilling` 在已关账时返回 409 该 message
 * （见文件头「已关账」判定）。前端据此把预览失败区分成「已关账（业务状态，非错误）」。
 */
const ALREADY_SETTLED_MESSAGE = "encounter billing is already settled";

type StepState = "done" | "active" | "todo";

const STEP_STATE_LABEL: Record<StepState, string> = {
  done: "已完成",
  active: "进行中",
  todo: "未开始",
};

const STEP_STATE_BADGE: Record<StepState, "success" | "info" | "default"> = {
  done: "success",
  active: "info",
  todo: "default",
};

const STEP_STATE_DOT: Record<StepState, string> = {
  done: "bg-success-bg text-success border-success/30",
  active: "bg-accent-subtle text-accent border-accent/30",
  todo: "bg-surface-alt text-fg-dimmed border-border",
};

/** 收费域错误中文化（映射表见 ./billingMessages） */
function errorMessage(error: unknown, fallback: string): string {
  return billingErrorMessage(error, fallback);
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

function isZeroAmount(value: number): boolean {
  return Math.abs(value) < ZERO_AMOUNT_EPSILON;
}

/** 预览失败是否为「已关账」这一业务状态（而非需要展示的加载错误） */
function isAlreadySettled(error: unknown): boolean {
  return (
    error instanceof ApiRequestError && error.status === 409 && error.message.trim() === ALREADY_SETTLED_MESSAGE
  );
}

/**
 * 资格类失败的「下一步」指引（中文、可操作）；非资格类失败返回 null。
 * 中文主提示一律由 `billingErrorMessage` 给出，这里只补「接下来该做什么」。
 */
function eligibilityHint(error: unknown): string | null {
  const message = error instanceof Error ? error.message.trim() : "";
  if (!message) return null;
  if (/not discharged or deceased/.test(message)) {
    return "下一步：请先到「入住管理」为这位长者办理离院（已去世请登记去世日期），再回到本向导完成结算关账。";
  }
  if (/has no discharge date|has no death date/.test(message)) {
    return "下一步：该入住缺少离院/去世日期，请先到「入住管理」补齐后再结算。";
  }
  if (/is not an elderly admission/.test(message)) {
    return "下一步：本向导只适用于养老入住（ELDERLY_CARE），请确认所选入住的类型。";
  }
  if (/has no admit date/.test(message)) {
    return "下一步：该入住缺少入住日期，请先到「入住管理」补齐后再计费与结算。";
  }
  return null;
}

function StepHeader({ index, title, state }: { index: number; title: string; state: StepState }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <span
        className={`flex h-7 w-7 items-center justify-center rounded-full border text-xs font-semibold ${STEP_STATE_DOT[state]}`}
      >
        {index}
      </span>
      <h4 className="text-sm font-semibold text-fg-emphasis">{title}</h4>
      <Badge variant={STEP_STATE_BADGE[state]}>{STEP_STATE_LABEL[state]}</Badge>
    </div>
  );
}

/** 只读数字块（沿用收费页的 surface-alt 卡片样式） */
function AmountTile({
  label,
  value,
  hint,
  tone = "fg",
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "fg" | "accent" | "danger" | "warning" | "success";
}) {
  const toneClass: Record<string, string> = {
    fg: "text-fg",
    accent: "text-accent",
    danger: "text-danger",
    warning: "text-warning",
    success: "text-success",
  };
  return (
    <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
      <p className="text-xs text-fg-dimmed">{label}</p>
      <p className={`text-lg font-bold mt-0.5 ${toneClass[tone]}`}>{value}</p>
      {hint && <p className="text-xs text-fg-dimmed mt-1">{hint}</p>}
    </div>
  );
}

function ErrorBlock({ message, hint }: { message: string; hint?: string }) {
  return (
    <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
      <p className="font-medium">{message}</p>
      {hint && <p className="mt-1">{hint}</p>}
    </div>
  );
}

function NoticeBlock({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-success/30 bg-success-bg px-4 py-3 text-sm text-success">{message}</div>
  );
}

function LockedHint({ text }: { text: string }) {
  return (
    <p className="rounded-md border border-border bg-surface-alt px-4 py-3 text-sm text-fg-dimmed">{text}</p>
  );
}

export default function DischargeSettlementWizard(props: {
  encounterId: string;
  onSettled?: () => void;
}): JSX.Element {
  const { encounterId, onSettled } = props;

  // 只读预览与「已关账」状态
  const [preview, setPreview] = useState<SettlementPreview | null>(null);
  const [settled, setSettled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [previewHint, setPreviewHint] = useState("");

  // 步骤 2：核销关账
  const [offsetAmount, setOffsetAmount] = useState("");
  const [writeOffReason, setWriteOffReason] = useState("");
  const [settling, setSettling] = useState(false);
  const [settleError, setSettleError] = useState("");
  const [settleHint, setSettleHint] = useState("");
  const [settleNotice, setSettleNotice] = useState("");

  // 步骤 3：押金余额与退押
  const [balance, setBalance] = useState<number | null>(null);
  const [depositLoading, setDepositLoading] = useState(false);
  const [depositError, setDepositError] = useState("");
  const [refundAmount, setRefundAmount] = useState("");
  const [refundRemark, setRefundRemark] = useState("");
  const [refunding, setRefunding] = useState(false);
  const [refundError, setRefundError] = useState("");
  const [refundNotice, setRefundNotice] = useState("");

  /** 换入住/卸载时作废在途响应：后到的旧响应不得覆盖新入住的状态 */
  const loadSeq = useRef(0);

  /**
   * 只读刷新：只调只读接口（结算预览 + 押金余额探针），不产生任何写入，可反复调用。
   * 两个请求各自结算成败、互不阻塞：预览失败不影响押金余额展示，反之亦然。
   */
  const refresh = useCallback(async () => {
    const seq = ++loadSeq.current;
    if (!encounterId) {
      setPreview(null);
      setSettled(false);
      setPreviewError("");
      setPreviewHint("");
      setBalance(null);
      setDepositError("");
      setLoading(false);
      setDepositLoading(false);
      return;
    }
    setLoading(true);
    setPreviewError("");
    setPreviewHint("");
    setDepositLoading(true);
    setDepositError("");

    const [previewResult, depositResult] = await Promise.allSettled([
      previewEncounterBilling(encounterId),
      listDeposits(encounterId, { limit: DEPOSIT_PROBE_LIMIT, offset: 0 }),
    ]);
    if (loadSeq.current !== seq) return;

    if (previewResult.status === "fulfilled") {
      setPreview(previewResult.value);
      setSettled(false);
      setPreviewError("");
      setPreviewHint("");
      // 核销额默认 = 核销上限 max_offset（0 表示不核销，由操作员改小或清零）
      setOffsetAmount(formatAmount(Math.max(previewResult.value.max_offset, 0)));
    } else if (isAlreadySettled(previewResult.reason)) {
      // 已关账是步骤 1、2 的完成态，不是错误：不再展示预览数字
      setPreview(null);
      setSettled(true);
      setPreviewError("");
      setPreviewHint("");
      setOffsetAmount("");
    } else {
      setPreview(null);
      setSettled(false);
      setPreviewError(errorMessage(previewResult.reason, "无法加载结算预览"));
      setPreviewHint(eligibilityHint(previewResult.reason) ?? "");
      setOffsetAmount("");
    }

    if (depositResult.status === "fulfilled") {
      // 余额口径与 DepositsPage「当前押金余额」、BillingPage 结算入口同源：直接读 meta.balance
      setBalance(depositResult.value.meta.balance);
      setDepositError("");
    } else {
      setBalance(null);
      setDepositError(errorMessage(depositResult.reason, "无法加载押金余额"));
    }

    setLoading(false);
    setDepositLoading(false);
  }, [encounterId]);

  useEffect(() => {
    // 换入住：清空全部表单与动作提示，只保留刷新出来的只读状态
    setWriteOffReason("");
    setSettleError("");
    setSettleHint("");
    setSettleNotice("");
    setRefundAmount("");
    setRefundRemark("");
    setRefundError("");
    setRefundNotice("");
    void refresh();
    return () => {
      // 卸载/换入住时作废在途响应
      loadSeq.current += 1;
    };
  }, [refresh]);

  // ─── 步骤状态（未完成前不放出下一步） ───────────────────────────────
  //
  // 步骤 1 由关账自动完成、没有任何手工动作，因此它的「完成」= 服务端已确认区间
  // （preview 成功）或已关账；这同时是步骤 2 的解锁条件——若把步骤 1 的完成态定义为
  // 「已关账」，步骤 1 与步骤 2 会互相等待（关账正是步骤 2 的动作）。
  const step1State: StepState = settled || preview !== null ? "done" : loading ? "active" : "todo";
  const step2State: StepState = settled ? "done" : preview !== null ? "active" : "todo";
  // 步骤 3 的完成态 = 当前押金余额为 0（无押金可退）；步骤 2 未完成前不放出本步
  const noDepositToRefund = balance !== null && isZeroAmount(balance);
  const step3State: StepState = !settled ? "todo" : noDepositToRefund ? "done" : "active";

  // ─── 步骤 2 的核销与减免校验 ────────────────────────────────────────

  /** 核销上限（预览的 max_offset 是权威口径：min(押金余额, 未结合计)） */
  const maxOffset = preview ? Math.max(preview.max_offset, 0) : null;

  const offsetCheck = useMemo((): { value: number; error: string } => {
    const raw = offsetAmount.trim();
    if (raw === "") return { value: 0, error: "" };
    if (!AMOUNT_PATTERN.test(raw)) {
      return { value: 0, error: "核销金额必须是不超过两位小数的正数（0 表示不核销）" };
    }
    const value = Number(raw);
    if (!Number.isFinite(value)) {
      return { value: 0, error: "核销金额必须是不超过两位小数的正数（0 表示不核销）" };
    }
    if (maxOffset !== null && value > maxOffset + 1e-9) {
      return {
        value,
        error: `核销金额不得超过核销上限 ¥ ${formatAmount(maxOffset)}（= min(押金余额, 未结合计)）`,
      };
    }
    return { value, error: "" };
  }, [offsetAmount, maxOffset]);

  const offsetValue = offsetCheck.error === "" ? offsetCheck.value : 0;

  /** 核销后仍未结 = outstanding_total − 本次核销（下限 0）；null = 预览不可用 */
  const remaining: number | null = preview ? Math.max(preview.outstanding_total - offsetValue, 0) : null;

  /**
   * 是否必须填写减免原因：`requires_write_off === true` 是服务端的权威判定；
   * `remaining > 0` 是它的超集（把核销额改小到低于 max_offset 时，服务端同样要求原因），
   * 两者取或，避免多一次注定 409 的提交。
   */
  const requiresWriteOff = preview?.requires_write_off === true;
  const writeOffRequired = requiresWriteOff || (remaining !== null && remaining > 0);

  const writeOffReasonTrimmed = writeOffReason.trim();
  const writeOffReasonValid =
    writeOffReasonTrimmed !== "" && writeOffReasonTrimmed.length <= WRITE_OFF_REASON_MAX_LENGTH;

  const settleDisabled =
    settled || preview === null || settling || offsetCheck.error !== "" || (writeOffRequired && !writeOffReasonValid);

  // ─── 步骤 3 的退押校验 ──────────────────────────────────────────────

  const refundCheck = useMemo((): { value: number; error: string } => {
    const raw = refundAmount.trim();
    if (raw === "") return { value: 0, error: "" };
    if (!AMOUNT_PATTERN.test(raw)) {
      return { value: 0, error: "退押金额必须是不超过两位小数的正数" };
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value <= 0) {
      return { value: 0, error: "退押金额必须大于 0" };
    }
    if (balance !== null && value > balance + 1e-9) {
      return { value, error: `退押金额不得超过当前押金余额 ¥ ${formatAmount(balance)}` };
    }
    return { value, error: "" };
  }, [refundAmount, balance]);

  const refundValue = refundCheck.error === "" ? refundCheck.value : 0;
  const refundDisabled =
    !settled ||
    refunding ||
    balance === null ||
    noDepositToRefund ||
    refundCheck.error !== "" ||
    refundValue <= 0;

  // ─── 提交 ───────────────────────────────────────────────────────────

  async function handleSettle() {
    if (!encounterId || settled || preview === null) return;
    if (offsetCheck.error) {
      setSettleError(offsetCheck.error);
      return;
    }
    if (writeOffRequired && !writeOffReasonValid) {
      setSettleError(
        `核销后仍未结 ¥ ${formatAmount(remaining ?? 0)}，请填写减免原因（trim 后不超过 ${WRITE_OFF_REASON_MAX_LENGTH} 字符）后再提交。`,
      );
      return;
    }
    // 请求体：核销为 0（或留空）时不发 deposit_offset；无需减免时不发 write_off_reason
    const payload: { depositOffset?: number; writeOffReason?: string } = {
      ...(offsetValue > 0 ? { depositOffset: offsetValue } : {}),
      ...(writeOffRequired ? { writeOffReason: writeOffReasonTrimmed } : {}),
    };
    setSettling(true);
    setSettleError("");
    setSettleHint("");
    setSettleNotice("");
    try {
      await settleEncounterBilling(encounterId, payload);
      setWriteOffReason("");
      setSettleNotice("结算关账完成：区间最终账单已生成（本期无需生成时为无），全部账单已冻结。");
      onSettled?.();
      // 关账后 preview 必然 409（已关账）→ 刷新会把步骤 1、2 置为完成
      await refresh();
    } catch (error) {
      setSettleError(errorMessage(error, "结算关账失败"));
      setSettleHint(eligibilityHint(error) ?? "");
    } finally {
      setSettling(false);
    }
  }

  async function handleRefund() {
    if (!encounterId || refundDisabled) return;
    const remark = refundRemark.trim();
    setRefunding(true);
    setRefundError("");
    setRefundNotice("");
    try {
      await createDepositRefund(encounterId, {
        amount: refundValue,
        ...(remark ? { remark } : {}),
      });
      setRefundNotice(`退押成功：已退 ¥ ${formatAmount(refundValue)}，押金余额已更新。`);
      setRefundAmount("");
      setRefundRemark("");
      // 退押改变押金余额（余额归零时步骤 3 转为完成）
      await refresh();
    } catch (error) {
      setRefundError(errorMessage(error, "退押失败"));
    } finally {
      setRefunding(false);
    }
  }

  if (!encounterId) {
    return (
      <Card title="离院结算向导">
        <EmptyState icon="🧾" title="请先选择入住" description="离院结算向导需要选定一位已离院/已去世的长者" />
      </Card>
    );
  }

  return (
    <Card
      title="离院结算向导"
      actions={
        <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={loading || depositLoading}>
          刷新预览
        </Button>
      }
    >
      <div className="space-y-6">
        <p className="text-sm text-fg-muted">
          已离院/已去世长者的账单收尾按三步走：① 关账时自动生成区间最终账单 → ② 核销并关账 →
          ③ 退还押金余额。上一步未完成不放下一步；预览为只读，可反复刷新，不会产生任何写入。
        </p>

        {/* ─── 步骤 1：区间最终账单（关账时自动完成） ─── */}
        <section className="space-y-3">
          <StepHeader index={1} title="生成区间最终账单（关账时自动完成）" state={step1State} />
          {settled ? (
            <p className="text-sm text-fg-muted">
              该入住已关账：区间最终账单已随关账生成（本区间无需生成时即为无），全部账单已冻结。
            </p>
          ) : loading ? (
            <div className="flex items-center gap-2 rounded-md border border-border bg-surface-alt px-4 py-3 text-sm text-fg-muted">
              <LoadingSpinner size={16} />
              <span>正在加载结算预览…</span>
            </div>
          ) : preview ? (
            <div className="grid gap-3 md:grid-cols-2">
              <AmountTile
                label="区间最终账单账期"
                value={
                  preview.settlement_period === null
                    ? "本期无需生成区间最终账单"
                    : `${formatDate(preview.settlement_period.start, "—")} ~ ${formatDate(preview.settlement_period.end, "—")}`
                }
                hint={
                  preview.settlement_period === null
                    ? "关账只冻结既有账单，不会新增区间最终账单。"
                    : "区间 = 上次已结算账期末日 + 1（或入住日）至离院/去世日"
                }
              />
              <AmountTile
                label="区间最终账单金额（元）"
                value={formatAmount(preview.final_bill_total)}
                hint="关账时自动生成，无需在本步手工操作"
              />
            </div>
          ) : (
            <ErrorBlock message={previewError || "无法加载结算预览"} hint={previewHint || undefined} />
          )}
        </section>

        {/* ─── 步骤 2：核销关账 ─── */}
        <section className="space-y-3">
          <StepHeader index={2} title="核销关账" state={step2State} />
          {settled ? (
            <p className="text-sm text-fg-muted">
              该入住已关账：全部账单已冻结，不可再生成账单、手工加项或缴费。
            </p>
          ) : preview === null ? (
            <LockedHint
              text={
                loading
                  ? "正在加载结算预览…"
                  : previewError
                    ? "结算预览不可用，请先按上方提示处理后点「刷新预览」。"
                    : "请先完成步骤 1：结算预览加载成功后即可提交关账。"
              }
            />
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-3">
                <AmountTile
                  label="既有欠费（核销前，元）"
                  value={formatAmount(preview.pending_balance)}
                  tone="danger"
                  hint="既有「待缴费」账单未结合计"
                />
                <AmountTile
                  label="关账后未结合计（元）"
                  value={formatAmount(preview.outstanding_total)}
                  tone="warning"
                  hint="= 既有欠费 + 区间最终账单金额"
                />
                <AmountTile
                  label="当前押金余额（元）"
                  value={formatAmount(preview.deposit_balance)}
                  tone="accent"
                  hint="= 累计登记 − 累计退押 − 累计核销"
                />
                <AmountTile
                  label="可核销上限（元）"
                  value={formatAmount(maxOffset ?? 0)}
                  hint="= min(押金余额, 未结合计)"
                />
                <AmountTile label="本次核销（元）" value={formatAmount(offsetValue)} />
                <AmountTile
                  label="核销后仍未结（元）"
                  value={remaining === null ? "—" : formatAmount(remaining)}
                  tone={remaining !== null && remaining > 0 ? "warning" : "success"}
                  hint={
                    remaining !== null && remaining > 0
                      ? "将随本次关账冻结、不可再收，必须填写减免原因"
                      : "核销后未结清零"
                  }
                />
                <AmountTile
                  label="全额核销后是否仍未结"
                  value={requiresWriteOff ? "是（必须减免）" : "否（可结清）"}
                  tone={requiresWriteOff ? "warning" : "success"}
                  hint={
                    requiresWriteOff
                      ? "即使按可核销上限全额核销仍会剩余未结，必须填写减免原因"
                      : "按可核销上限全额核销即可结清剩余未结，无需减免原因"
                  }
                />
              </div>

              <div className="flex items-end gap-2">
                <div className="flex-1">
                  <Input
                    label="押金核销金额（元，0 = 不核销）"
                    type="number"
                    min="0"
                    step="0.01"
                    value={offsetAmount}
                    error={offsetCheck.error || undefined}
                    onChange={(event) => setOffsetAmount(event.target.value)}
                    placeholder="如 3000.00"
                  />
                </div>
                <Button
                  variant="secondary"
                  disabled={maxOffset === null || maxOffset <= 0}
                  onClick={() => setOffsetAmount(formatAmount(maxOffset ?? 0))}
                >
                  全部核销
                </Button>
              </div>
              <p className="text-xs text-fg-dimmed">
                押金核销只在结算关账时发生：核销会同时写入缴费流水（方式「押金」）与押金台账（类型「核销」），
                押金余额与账单欠费同减，不产生现金流入。
              </p>

              {writeOffRequired ? (
                <div className="space-y-3 rounded-md border border-warning/30 bg-warning-bg px-4 py-3 text-sm text-warning">
                  <p className="font-medium">
                    核销后仍未结 ¥ {formatAmount(remaining ?? 0)}，将随本次关账冻结、不可再收。
                  </p>
                  {requiresWriteOff && <p>预览判定：即使按上限全额核销仍会剩余未结，必须填写减免原因。</p>}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted">
                      减免原因（必填，最多 {WRITE_OFF_REASON_MAX_LENGTH} 字符）
                    </label>
                    <textarea
                      value={writeOffReason}
                      maxLength={WRITE_OFF_REASON_MAX_LENGTH}
                      rows={3}
                      onChange={(event) => setWriteOffReason(event.target.value)}
                      placeholder="如：离院结算，家属书面确认不再追收"
                      className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed transition-colors duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                    />
                    {!writeOffReasonValid && (
                      <p className="text-xs text-danger">
                        减免原因不能为空（trim 后），且不超过 {WRITE_OFF_REASON_MAX_LENGTH} 字符
                      </p>
                    )}
                  </div>
                </div>
              ) : (
                <div className="rounded-md border border-success/30 bg-success-bg px-4 py-3 text-sm text-success">
                  核销后未结清零，无需减免原因。
                </div>
              )}

              <div className="flex flex-wrap items-center gap-3">
                <Button variant="warning" loading={settling} disabled={settleDisabled} onClick={() => void handleSettle()}>
                  确认结算关账
                </Button>
                <span className="text-xs text-fg-dimmed">
                  关账为一次性动作：提交后全部账单冻结，不可再生成账单、手工加项或缴费。
                </span>
              </div>
            </>
          )}
          {settleError && <ErrorBlock message={settleError} hint={settleHint || undefined} />}
          {settleNotice && <NoticeBlock message={settleNotice} />}
        </section>

        {/* ─── 步骤 3：退还押金余额 ─── */}
        <section className="space-y-3">
          <StepHeader index={3} title="退还押金余额" state={step3State} />
          {step3State === "todo" ? (
            <LockedHint text="请先完成步骤 2 结算关账，再办理退押。" />
          ) : depositLoading ? (
            <div className="flex items-center gap-2 rounded-md border border-border bg-surface-alt px-4 py-3 text-sm text-fg-muted">
              <LoadingSpinner size={16} />
              <span>正在加载押金余额…</span>
            </div>
          ) : depositError ? (
            <ErrorBlock message={depositError} />
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-2">
                <AmountTile
                  label="当前押金余额（元）"
                  value={balance === null ? "—" : formatAmount(balance)}
                  tone="accent"
                  hint="= 累计登记 − 累计退押 − 累计核销（与「押金管理」页同源）"
                />
                <AmountTile label="本次退押（元）" value={formatAmount(refundValue)} />
              </div>

              {noDepositToRefund ? (
                <div className="flex flex-wrap items-center gap-3">
                  <Button variant="warning" disabled>
                    确认退押
                  </Button>
                  <span className="text-sm text-fg-muted">无押金可退：当前押金余额为 ¥ 0.00，本步骤已完成。</span>
                </div>
              ) : (
                <>
                  <div className="flex items-end gap-2">
                    <div className="flex-1">
                      <Input
                        label="退押金额（元）"
                        type="number"
                        min="0.01"
                        step="0.01"
                        value={refundAmount}
                        error={refundCheck.error || undefined}
                        onChange={(event) => setRefundAmount(event.target.value)}
                        placeholder="如 5000.00"
                      />
                    </div>
                    <Button
                      variant="secondary"
                      disabled={balance === null || balance <= 0}
                      onClick={() => setRefundAmount(formatAmount(balance ?? 0))}
                    >
                      全部退押
                    </Button>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label className="text-sm font-medium text-fg-muted">
                      退押备注（可选，最多 {REFUND_REMARK_MAX_LENGTH} 字符）
                    </label>
                    <textarea
                      value={refundRemark}
                      maxLength={REFUND_REMARK_MAX_LENGTH}
                      rows={2}
                      onChange={(event) => setRefundRemark(event.target.value)}
                      placeholder="如：离院结算，押金余额柜台退还"
                      className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed transition-colors duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                    />
                  </div>
                  <div className="flex flex-wrap items-center gap-3">
                    <Button variant="warning" loading={refunding} disabled={refundDisabled} onClick={() => void handleRefund()}>
                      确认退押
                    </Button>
                    <span className="text-xs text-fg-dimmed">
                      退押为独立操作：离院/去世后仍可退押，累计退押不得超过当前余额。
                    </span>
                  </div>
                </>
              )}
            </>
          )}
          {refundError && <ErrorBlock message={refundError} />}
          {refundNotice && <NoticeBlock message={refundNotice} />}
        </section>
      </div>
    </Card>
  );
}
