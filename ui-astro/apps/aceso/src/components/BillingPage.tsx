import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, LoadingSpinner, Modal, Table, type Column } from "@pitchfork/ui";
import {
  addBillItem,
  createPayment,
  generateBill,
  getBill,
  getPaymentSummary,
  listArrears,
  listBills,
  listDeposits,
  listElderlyAdmissions,
  listFeeItems,
  listPatients,
  listPayments,
  precheckBillGeneration,
  previewEncounterBilling,
  settleEncounterBilling,
  type Arrear,
  type Bill,
  type BillItem,
  type BillPrecheck,
  type BillPrecheckNotice,
  type BillPrecheckRequirement,
  type Encounter,
  type FeeItem,
  type Payment,
  type PaymentMethod,
  type PaymentSummary,
  type SettlementPreview,
} from "@pitchfork/shared/aceso";
import { formatDate, formatDateTime, todayLocal } from "../lib/datetime";
import { BILLING_BLOCKED_REASONS, FEE_ITEMS_PAGE_PATH, billingErrorMessage } from "./billingMessages";
import DischargeSettlementWizard from "./DischargeSettlementWizard";

const PAGE_SIZE = 50;

/** 生成账单前置校验的防抖间隔（ms）：账期输入时不逐字符打接口 */
const PRECHECK_DEBOUNCE_MS = 300;

/** 结算核销上限用的「本入住欠费合计」一次取足：按 encounter_id 过滤，避免被全局列表截断 */
const ENCOUNTER_ARREARS_LIMIT = 500;

/** 减免原因的服务端上限（trim 后字符数），与 POST billing-settlement 契约一致 */
const WRITE_OFF_REASON_MAX_LENGTH = 500;

/** 「系统设置 → 费用项目」在页面文案里的统一写法 */
const FEE_ITEMS_LOCATION = "「系统设置 → 费用项目」";

const ENCOUNTER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "在住",
  DISCHARGED: "已离院",
  TRANSFERRED: "已转出",
  DECEASED: "已去世",
};

const ENCOUNTER_STATUS_VARIANT: Record<string, "success" | "default" | "warning" | "danger"> = {
  ACTIVE: "success",
  DISCHARGED: "default",
  TRANSFERRED: "warning",
  DECEASED: "danger",
};

const BILL_STATUS_VARIANT: Record<string, "success" | "default" | "warning"> = {
  待缴费: "warning",
  已结清: "success",
  已结算: "default",
};

const PAYMENT_METHODS: PaymentMethod[] = ["现金", "转账", "银行卡", "微信", "支付宝"];

interface Admission extends Encounter {
  patientName: string;
}

/** 可缴费对象（账单或欠费行，欠费行 id 即账单 ID） */
interface PayableBill {
  id: string;
  total_amount: number;
  period_start: string;
  period_end: string;
}

/** 收费域错误中文化（映射表见 ./billingMessages）；保留本地同名包装以减少调用点改动 */
function errorMessage(error: unknown, fallback: string): string {
  return billingErrorMessage(error, fallback);
}

function formatAmount(value: number): string {
  return value.toFixed(2);
}

function currentMonth(): string {
  return todayLocal().slice(0, 7);
}

/** 生成账单前置提示条目（缺项 / 阻塞原因） */
interface GenerateBlockNotice {
  key: string;
  text: string;
}

/**
 * 把 precheck 返回的缺项槽位渲染成中文可操作提示。
 * 只读 `category` / `level` / `enabled_count` 决定措辞，`satisfied` 一律以服务端为准。
 */
function requirementNotice(requirement: BillPrecheckRequirement): string {
  const level = requirement.level?.trim() || null;
  if (level) {
    return requirement.enabled_count > 1
      ? `护理等级「${level}」存在多条启用的「护理费」项目，请到${FEE_ITEMS_LOCATION}只保留一条启用。`
      : `缺少绑定护理等级「${level}」的启用「护理费」项目：请到${FEE_ITEMS_LOCATION}新增或修改护理费，并把「护理等级」选为「${level}」（计费按等级绑定，与项目名称无关）。`;
  }
  if (requirement.enabled_count > 1) {
    return `「${requirement.category}」存在多条启用的费用项目，请到${FEE_ITEMS_LOCATION}只保留一条启用。`;
  }
  const basis =
    requirement.category === "床位费"
      ? "自动计费要求「床位费」恰好一条启用项（按在院天数计费）。"
      : requirement.category === "伙食费"
        ? "账期内有就餐记录时自动计费要求「伙食费」恰好一条启用项（按折合餐次计费）。"
        : "自动计费要求该分类恰好一条启用项。";
  return `缺少启用的「${requirement.category}」费用项目：${basis}`;
}

/**
 * 计费口径提示（服务端 `notices`）→ 中文文案（计划 030 §3 D3/D8）。
 * 这些提示**不阻塞**生成账单，只说明「本账期有一类费用不会计入」以及原因与下一步，
 * 修掉的是「少了护理费/伙食费但页面不告诉用户」的原缺陷。
 */
function precheckNoticeText(notice: BillPrecheckNotice): string {
  switch (notice.code) {
    case "nursing_fee_not_billed_no_assessment":
      return "账期内没有生效的护理评估（账期之前也没有）：本账期不计护理费。请先到「照护服务 → 护理评估」补做评估；确需补记本期护理费时，可在账单生成后用「手工加项」单独补一条。";
    case "meal_fee_not_billed_no_dining_record":
      return "账期内没有就餐登记（膳食营养 → 配餐名单与就餐登记）：本账期不计伙食费。伙食费按就餐登记折合餐次计费（正常=1 餐、部分=0.5 餐、未就餐/拒食=0 餐）；请假、外出、住院期间未登记就餐同样不计费。";
    default:
      return notice.category
        ? `「${notice.category}」本账期不计费（原因码 ${notice.code}）。`
        : `本账期有一类费用不会计入（原因码 ${notice.code}）。`;
  }
}

export default function BillingPage() {
  // 入住与费用字典
  const [admissions, setAdmissions] = useState<Admission[]>([]);
  const [admissionsLoading, setAdmissionsLoading] = useState(true);
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [feeItems, setFeeItems] = useState<FeeItem[]>([]);
  const [feeItemsLoading, setFeeItemsLoading] = useState(true);

  // 账单与欠费
  const [bills, setBills] = useState<Bill[]>([]);
  const [billsLoading, setBillsLoading] = useState(false);
  const [billTotal, setBillTotal] = useState(0);
  const [arrears, setArrears] = useState<Arrear[]>([]);
  const [arrearsLoading, setArrearsLoading] = useState(false);
  const [summary, setSummary] = useState<PaymentSummary | null>(null);

  const [pageError, setPageError] = useState("");
  const [actionError, setActionError] = useState("");
  const [arrearsError, setArrearsError] = useState("");

  // 账单生成
  const [generateOpen, setGenerateOpen] = useState(false);
  const [month, setMonth] = useState(currentMonth());
  const [generating, setGenerating] = useState(false);

  // 手工加项
  const [addItemBill, setAddItemBill] = useState<Bill | null>(null);
  const [itemId, setItemId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [unitPrice, setUnitPrice] = useState("");
  const [itemRemark, setItemRemark] = useState("");
  const [addingItem, setAddingItem] = useState(false);

  // 缴费
  const [payTarget, setPayTarget] = useState<{ bill: PayableBill; remaining: number } | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState<PaymentMethod>("现金");
  const [payRemark, setPayRemark] = useState("");
  const [paying, setPaying] = useState(false);

  // 账单明细
  const [detail, setDetail] = useState<{ bill: Bill; items: BillItem[]; payments: Payment[] } | null>(null);

  // 生成账单前置校验：由服务端 precheck 判定（前端只渲染，不再复刻计价/取级规则）。
  // null 表示尚未拿到结果或请求失败，两种情况都走刻意降级（不禁用生成）。
  const [precheck, setPrecheck] = useState<BillPrecheck | null>(null);
  // 生成/缴费/加项/关账会改变账期状态（如某账期已生成），用它触发 precheck 复验
  const [precheckTick, setPrecheckTick] = useState(0);

  // 结算关账
  const [settleOpen, setSettleOpen] = useState(false);
  const [settling, setSettling] = useState(false);
  // 结算核销：押金余额（已扣除核销）与本次核销金额输入
  const [depositBalance, setDepositBalance] = useState<number | null>(null);
  const [depositLoading, setDepositLoading] = useState(false);
  const [depositError, setDepositError] = useState("");
  const [offsetAmount, setOffsetAmount] = useState("");
  // 结算核销上限用的「本入住欠费合计」：按 encounter_id 单独查询（不再从全局欠费列表里过滤）
  const [encounterArrears, setEncounterArrears] = useState(0);
  const [encounterArrearsLoading, setEncounterArrearsLoading] = useState(false);
  const [encounterArrearsError, setEncounterArrearsError] = useState("");
  // 结算关账预览（只读）：提交前展示「会发生什么」的权威口径（含区间最终账单，故与卡片上的欠费合计不同源）
  const [settlePreview, setSettlePreview] = useState<SettlementPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  // 预览不可用时的中文错误（走 billingMessages）；此时弹窗退化为「不显示预览数字、按后端错误兜底」
  const [previewError, setPreviewError] = useState("");
  // 减免确认（本次任务核心）：有未结余额时「勾选确认」与「原因」缺一不可，后端 409 兜底
  const [writeOffConfirmed, setWriteOffConfirmed] = useState(false);
  const [writeOffReason, setWriteOffReason] = useState("");

  const loadAdmissions = useCallback(async () => {
    setAdmissionsLoading(true);
    setPageError("");
    try {
      // 含已离院/已去世入住（结算关账入口需要），ACTIVE 优先排列
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

  const loadFeeItems = useCallback(async () => {
    setFeeItemsLoading(true);
    try {
      const response = await listFeeItems({ status: "启用", limit: 200 });
      setFeeItems(response.records);
    } catch (error) {
      setPageError(errorMessage(error, "无法加载费用字典"));
    } finally {
      setFeeItemsLoading(false);
    }
  }, []);

  const loadBills = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setBills([]);
      setBillTotal(0);
      return;
    }
    setBillsLoading(true);
    setActionError("");
    try {
      const response = await listBills(encounterId, { limit: PAGE_SIZE });
      setBills(response.records);
      setBillTotal(response.meta.total);
    } catch (error) {
      setActionError(errorMessage(error, "无法加载账单列表"));
    } finally {
      setBillsLoading(false);
    }
  }, []);

  const loadArrearsAndSummary = useCallback(async () => {
    setArrearsLoading(true);
    setArrearsError("");
    try {
      const [arrearsResponse, summaryResponse] = await Promise.all([
        listArrears({ limit: PAGE_SIZE }),
        getPaymentSummary(),
      ]);
      setArrears(arrearsResponse.records);
      setSummary(summaryResponse);
    } catch (error) {
      setArrearsError(errorMessage(error, "无法加载欠费列表"));
    } finally {
      setArrearsLoading(false);
    }
  }, []);

  /**
   * 结算核销上限用的「本入住欠费合计」：按 encounter_id 过滤查询（records 与 meta.total 同源）。
   * 与上方全局「欠费列表」各自独立：这里不受全局列表分页条数限制，避免上限被低估。
   *
   * 求和前再按 `row.encounter_id === encounterId` 过滤一次，属**纵深防御**、不是权威口径：
   * 服务端按 `encounter_id` 过滤才是权威；服务端过滤生效时这一层是幂等的空操作。
   * 它只用于兜住「服务端参数尚未生效」的过渡态——那时返回的是全局欠费，不过滤会把它们
   * 求和成「本入住欠费合计」，使核销上限虚高。
   */
  const loadEncounterArrears = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setEncounterArrears(0);
      setEncounterArrearsError("");
      return;
    }
    setEncounterArrearsLoading(true);
    setEncounterArrearsError("");
    try {
      const response = await listArrears({ encounter_id: encounterId, limit: ENCOUNTER_ARREARS_LIMIT });
      setEncounterArrears(
        response.records
          .filter((row) => row.encounter_id === encounterId)
          .reduce((acc, row) => acc + row.balance, 0),
      );
    } catch (error) {
      setEncounterArrears(0);
      setEncounterArrearsError(errorMessage(error, "无法加载本入住欠费合计"));
    } finally {
      setEncounterArrearsLoading(false);
    }
  }, []);

  /** 结算核销所需的押金余额（meta.balance 已扣除核销） */
  const loadDepositBalance = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setDepositBalance(null);
      setDepositError("");
      return;
    }
    setDepositLoading(true);
    setDepositError("");
    try {
      const ledger = await listDeposits(encounterId, { limit: 1 });
      setDepositBalance(ledger.meta.balance);
    } catch (error) {
      setDepositBalance(null);
      setDepositError(errorMessage(error, "无法加载押金余额"));
    } finally {
      setDepositLoading(false);
    }
  }, []);

  /**
   * 结算关账预览（只读，与关账实际算法同源）：打开关账弹窗时拉取。
   *
   * 失败时**不阻塞关账**：只显示中文错误，`settlePreview` 保持 `null`，
   * 弹窗内退化为「不显示预览数字、核销上限不做前端校验、减免原因可自愿填写」，
   * 最终以服务端返回的错误文案兜底（`unsettled bills require explicit write-off` 已有中文映射）。
   */
  const loadSettlePreview = useCallback(async (encounterId: string) => {
    if (!encounterId) {
      setSettlePreview(null);
      setPreviewError("");
      return;
    }
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const preview = await previewEncounterBilling(encounterId);
      setSettlePreview(preview);
    } catch (error) {
      setSettlePreview(null);
      setPreviewError(errorMessage(error, "无法加载结算预览，本次将不显示预览数字，提交后以服务端校验结果为准"));
    } finally {
      setPreviewLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAdmissions();
    void loadFeeItems();
    void loadArrearsAndSummary();
  }, [loadAdmissions, loadFeeItems, loadArrearsAndSummary]);

  useEffect(() => {
    void loadBills(selectedEncounterId);
  }, [selectedEncounterId, loadBills]);

  useEffect(() => {
    setOffsetAmount("");
    // 换入住即作废上一人的预览：避免加载新预览期间用旧数字判定核销上限与减免
    setSettlePreview(null);
    setPreviewError("");
    setWriteOffConfirmed(false);
    setWriteOffReason("");
    void loadDepositBalance(selectedEncounterId);
    void loadEncounterArrears(selectedEncounterId);
  }, [selectedEncounterId, loadDepositBalance, loadEncounterArrears]);

  /**
   * 生成账单前置校验：`(selectedEncounterId, month)` 变化时调用服务端 precheck。
   * 防抖 [PRECHECK_DEBOUNCE_MS] 毫秒（账期输入时不逐字符打接口），cleanup 同时取消防抖与在途响应。
   */
  useEffect(() => {
    if (!selectedEncounterId || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
      setPrecheck(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const result = await precheckBillGeneration(selectedEncounterId, month);
          if (cancelled) return;
          setPrecheck(result);
        } catch {
          if (cancelled) return;
          // 刻意降级：precheck 不可用时（接口尚未上线、网络错误、401 等）不阻塞生成——
          // 清空校验结果、不禁用按钮、不显示缺项清单，让用户照常提交，失败以后端返回的错误为准
          // （已有 billingMessages 的中文映射兜底）。
          setPrecheck(null);
        }
      })();
    }, PRECHECK_DEBOUNCE_MS);
    return () => {
      // 取消防抖与在途响应：账期每敲一个字符只保留最后一次请求的结果
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [selectedEncounterId, month, precheckTick]);

  const selectedAdmission = useMemo(
    () => admissions.find((admission) => admission.id === selectedEncounterId) ?? null,
    [admissions, selectedEncounterId],
  );

  const admissionById = useMemo(() => new Map(admissions.map((admission) => [admission.id, admission])), [admissions]);

  // ─── 生成账单前置校验（服务端 precheck 驱动；前端不再复刻取级/计价规则） ───

  /**
   * 缺项清单 = 服务端标记 `required` 且 `!satisfied` 的槽位。
   * `satisfied` 由服务端按「启用条数恰好为 1」判定，这里只做过滤，绝不重算。
   */
  const missingFeeItems = useMemo(
    () => (precheck?.requirements ?? []).filter((requirement) => requirement.required && !requirement.satisfied),
    [precheck],
  );

  /** 缺项逐条说明 + 非缺项的阻塞原因，共用同一提示区 */
  const generateBlockNotices = useMemo((): GenerateBlockNotice[] => {
    const notices: GenerateBlockNotice[] = missingFeeItems.map((requirement) => ({
      key: `${requirement.category}-${requirement.level ?? ""}`,
      text: requirementNotice(requirement),
    }));
    const blockedBy = precheck?.blocked_by ?? null;
    if (blockedBy !== null && blockedBy !== "missing_fee_items") {
      notices.push({ key: blockedBy, text: BILLING_BLOCKED_REASONS[blockedBy] });
    }
    return notices;
  }, [missingFeeItems, precheck]);

  const feeItemsEmpty = !feeItemsLoading && feeItems.length === 0;
  const generateBlocked = missingFeeItems.length > 0;

  /**
   * 计费口径提示（计划 030 §4.2，不阻塞生成）：
   * 服务端 `notices` 只说明「本账期有这样一类费用不会计入」，文案在前端映射，未知 code 归入兜底文案。
   */
  const precheckNotices = useMemo((): GenerateBlockNotice[] => {
    return (precheck?.notices ?? []).map((notice: BillPrecheckNotice, index) => ({
      key: `${notice.code}-${notice.category ?? ""}-${index}`,
      text: precheckNoticeText(notice),
    }));
  }, [precheck]);

  /**
   * 按钮可用性完全取服务端 `can_generate`；precheck 尚未返回或请求失败（null）时**不禁用**，
   * 走刻意降级：交给后端在提交时给出错误文案。
   */
  const precheckAllowsGenerate = precheck === null ? true : precheck.can_generate;

  const canGenerateBill =
    Boolean(selectedEncounterId) && !selectedAdmission?.settled_at && precheckAllowsGenerate;

  // ─── 结算核销金额与减免确认 ─────────────────────────────────────────

  // encounterArrears 由 loadEncounterArrears 按 encounter_id 过滤查询后写入（见上方 loader）

  /** 结算卡片显示用的核销上限 = min(押金余额, 欠费合计)（不含尚未生成的区间最终账单） */
  const offsetLimit = Math.min(Math.max(depositBalance ?? 0, 0), Math.max(encounterArrears, 0));

  /**
   * 结算弹窗的核销上限：预览的 `max_offset`（= min(押金余额, 未结合计)，已含区间最终账单），
   * 是权威口径，不再用卡片上基于欠费列表的 [offsetLimit]。
   * `null` 表示预览不可用（加载中或失败）：此时不做前端上限校验，交由服务端 400/409 兜底。
   */
  const previewMaxOffset = settlePreview ? Math.max(settlePreview.max_offset, 0) : null;

  /** 本次关账是否真的生成区间最终账单：账期为 `null` 或金额为 0 都表示不生成 */
  const hasFinalBill =
    settlePreview != null && settlePreview.settlement_period !== null && settlePreview.final_bill_total !== 0;

  const offsetCheck = useMemo((): { value: number; error: string } => {
    const raw = offsetAmount.trim();
    if (raw === "") return { value: 0, error: "" };
    if (!/^\d+(\.\d{1,2})?$/.test(raw)) {
      return { value: 0, error: "押金核销金额必须是不超过两位小数的正数" };
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0) {
      return { value: 0, error: "押金核销金额必须是不超过两位小数的正数" };
    }
    if (previewMaxOffset !== null && value > previewMaxOffset + 1e-9) {
      return {
        value,
        error: `押金核销金额不得超过核销上限 ¥ ${formatAmount(previewMaxOffset)}（取押金余额与未结合计的较小值，含区间最终账单）`,
      };
    }
    return { value, error: "" };
  }, [offsetAmount, previewMaxOffset]);

  const offsetValue = offsetCheck.error === "" ? offsetCheck.value : 0;

  /**
   * 核销后仍未结的金额 = `outstanding_total − 本次核销`（下限 0）。
   * `null` 表示预览不可用、无从判定——此时不强制减免确认，按后端 409 文案兜底。
   */
  const remaining: number | null = settlePreview
    ? Math.max(settlePreview.outstanding_total - offsetValue, 0)
    : null;

  /** 减免原因（trim 后）：非空且 ≤ [WRITE_OFF_REASON_MAX_LENGTH] 字符才算有效 */
  const writeOffReasonTrimmed = writeOffReason.trim();
  const writeOffReasonValid =
    writeOffReasonTrimmed !== "" && writeOffReasonTrimmed.length <= WRITE_OFF_REASON_MAX_LENGTH;

  /** 是否必须走减免确认：只有「预览可知核销后仍有未结」时才强制（本次任务核心） */
  const writeOffRequired = remaining !== null && remaining > 0;
  /** 减免确认是否已满足：勾选 + 有效原因，缺一不可 */
  const writeOffSatisfied = writeOffConfirmed && writeOffReasonValid;

  /** 预览判定「即使全额核销仍会剩余未结」：额外提示必须填写减免原因 */
  const previewRequiresWriteOff = settlePreview?.requires_write_off === true;

  /**
   * 提交时是否携带减免原因：预览可用时严格按契约（仅 `remaining > 0` 才发送）；
   * 预览不可用时，操作员自愿填写的有效原因照发——否则一旦后端 409，弹窗内没有补救入口。
   */
  const sendWriteOffReason = remaining === null ? writeOffReasonValid : remaining > 0;

  /** 提交按钮可用性：核销金额非法，或必须减免确认而未满足 → 禁用 */
  const settleDisabled = offsetCheck.error !== "" || (writeOffRequired && !writeOffSatisfied);

  /** 变更后联动刷新账单、全局欠费汇总、本入住欠费合计，并复验生成前置校验 */
  const refreshAfterChange = useCallback(() => {
    void loadBills(selectedEncounterId);
    void loadArrearsAndSummary();
    void loadEncounterArrears(selectedEncounterId);
    setPrecheckTick((tick) => tick + 1);
  }, [selectedEncounterId, loadBills, loadArrearsAndSummary, loadEncounterArrears]);

  // ─── 账单生成 ───────────────────────────────────────────────────────

  function openGenerate() {
    setMonth(currentMonth());
    setActionError("");
    // 打开弹窗时复验一次：账期文本没变也要拿到最新状态（如该账期已生成过）
    setPrecheckTick((tick) => tick + 1);
    setGenerateOpen(true);
  }

  async function handleGenerate() {
    if (!selectedEncounterId) return;
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
      setActionError("账期格式应为 YYYY-MM");
      return;
    }
    if (generateBlocked) {
      setActionError("费用字典缺项，请先到「系统设置 → 费用项目」补齐并启用后再生成账单");
      return;
    }
    setGenerating(true);
    setActionError("");
    try {
      await generateBill(selectedEncounterId, month);
      setGenerateOpen(false);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "账单生成失败"));
    } finally {
      setGenerating(false);
    }
  }

  // ─── 手工加项 ───────────────────────────────────────────────────────

  function openAddItem(bill: Bill) {
    setItemId("");
    setQuantity("1");
    setUnitPrice("");
    setItemRemark("");
    setActionError("");
    setAddItemBill(bill);
  }

  async function handleAddItem() {
    if (!addItemBill) return;
    if (!itemId) {
      setActionError("请选择费用项目");
      return;
    }
    const parsedQuantity = Number(quantity);
    if (!Number.isFinite(parsedQuantity) || parsedQuantity <= 0) {
      setActionError("数量必须为正数");
      return;
    }
    let parsedUnitPrice: number | undefined;
    if (unitPrice.trim() !== "") {
      parsedUnitPrice = Number(unitPrice);
      if (!Number.isFinite(parsedUnitPrice) || parsedUnitPrice <= 0) {
        setActionError("单价必须为正数");
        return;
      }
    }
    setAddingItem(true);
    setActionError("");
    try {
      await addBillItem(addItemBill.id, {
        item_id: itemId,
        quantity: parsedQuantity,
        ...(parsedUnitPrice !== undefined ? { unit_price: parsedUnitPrice } : {}),
        ...(itemRemark.trim() ? { remark: itemRemark.trim() } : {}),
      });
      setAddItemBill(null);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "手工加项失败"));
    } finally {
      setAddingItem(false);
    }
  }

  // ─── 缴费 ───────────────────────────────────────────────────────────

  async function openPayment(payable: PayableBill, suggestedAmount?: number) {
    setActionError("");
    setPayTarget(null);
    let remaining = payable.total_amount;
    try {
      const payments = await listPayments(payable.id, { limit: 100 });
      const paid = payments.records.reduce((acc, payment) => acc + payment.amount, 0);
      remaining = payable.total_amount - paid;
    } catch {
      // 流水加载失败时以账单合计为上限，超缴由服务端兜底
    }
    remaining = Math.max(remaining, 0);
    setPayTarget({ bill: payable, remaining });
    setPayAmount(suggestedAmount !== undefined ? formatAmount(suggestedAmount) : formatAmount(remaining));
    setPayMethod("现金");
    setPayRemark("");
  }

  async function handlePay() {
    if (!payTarget) return;
    const parsed = Number(payAmount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setActionError("缴费金额必须为正数");
      return;
    }
    if (parsed > payTarget.remaining + 1e-9) {
      setActionError(`缴费金额不得超过剩余应缴 ¥ ${formatAmount(payTarget.remaining)}`);
      return;
    }
    setPaying(true);
    setActionError("");
    try {
      await createPayment(payTarget.bill.id, {
        amount: parsed,
        method: payMethod,
        ...(payRemark.trim() ? { remark: payRemark.trim() } : {}),
      });
      setPayTarget(null);
      refreshAfterChange();
    } catch (error) {
      setActionError(errorMessage(error, "缴费失败"));
    } finally {
      setPaying(false);
    }
  }

  // ─── 账单明细 ───────────────────────────────────────────────────────

  async function openDetail(bill: Bill) {
    setActionError("");
    try {
      const [detailResponse, paymentsResponse] = await Promise.all([
        getBill(bill.id),
        listPayments(bill.id, { limit: PAGE_SIZE }),
      ]);
      setDetail({ bill: detailResponse, items: detailResponse.items ?? [], payments: paymentsResponse.records });
    } catch (error) {
      setActionError(errorMessage(error, "无法加载账单明细"));
    }
  }

  // ─── 结算关账 ───────────────────────────────────────────────────────

  const canSettle =
    selectedAdmission != null &&
    (selectedAdmission.status === "DISCHARGED" || selectedAdmission.status === "DECEASED") &&
    !selectedAdmission.settled_at;

  /**
   * 打开结算关账确认：押金余额与欠费合计已在选择入住时随结算入口加载（卡片显示不变），
   * 这里只清空上次的核销输入与减免勾选，并拉取只读预览（含区间最终账单的权威口径）。
   */
  function openSettle() {
    setActionError("");
    setOffsetAmount("");
    setWriteOffConfirmed(false);
    setWriteOffReason("");
    // 先作废上一次的预览：加载新预览期间一律按「预览不可用」处理，不用旧数字做上限与减免判定
    setSettlePreview(null);
    void loadSettlePreview(selectedEncounterId);
    setSettleOpen(true);
  }

  async function handleSettle() {
    if (!selectedEncounterId) return;
    if (offsetCheck.error) {
      setActionError(offsetCheck.error);
      return;
    }
    // 减免守卫（第二道；第一道是提交按钮 disabled）：有未结余额必须显式勾选并写明原因
    if (writeOffRequired && !writeOffSatisfied) {
      setActionError(
        `核销后仍未结 ¥ ${formatAmount(remaining ?? 0)}，请勾选「确认减免」并填写减免原因（trim 后不超过 ${WRITE_OFF_REASON_MAX_LENGTH} 字符）后再提交`,
      );
      return;
    }
    // 请求体：两个键都可省略；核销为 0 时不发 deposit_offset，无需减免时不发 write_off_reason
    const payload: { depositOffset?: number; writeOffReason?: string } = {
      ...(offsetValue > 0 ? { depositOffset: offsetValue } : {}),
      ...(sendWriteOffReason ? { writeOffReason: writeOffReasonTrimmed } : {}),
    };
    setSettling(true);
    setActionError("");
    try {
      const encounter = await settleEncounterBilling(selectedEncounterId, payload);
      setAdmissions((current) =>
        current.map((admission) =>
          admission.id === encounter.id ? { ...admission, ...encounter, patientName: admission.patientName } : admission,
        ),
      );
      setSettleOpen(false);
      setOffsetAmount("");
      setWriteOffConfirmed(false);
      setWriteOffReason("");
      // 关账后该入住已结算，预览必然 409（已关账）：清空而不重拉，避免把 409 当加载失败展示；
      // 若弹窗再次打开（选了别的可关账入住），openSettle 会重新拉取最新预览。
      setSettlePreview(null);
      setPreviewError("");
      refreshAfterChange();
      void loadDepositBalance(selectedEncounterId);
    } catch (error) {
      setActionError(errorMessage(error, "结算关账失败"));
    } finally {
      setSettling(false);
    }
  }

  // ─── 表格列 ─────────────────────────────────────────────────────────

  const billColumns: Column<Bill>[] = [
    {
      key: "period",
      header: "账期",
      render: (row) => (
        <span className="text-fg">{formatDate(row.period_start, "—")} ~ {formatDate(row.period_end, "—")}</span>
      ),
    },
    {
      key: "status",
      header: "状态",
      render: (row) => (
        <div className="flex flex-wrap items-center gap-1">
          <Badge variant={BILL_STATUS_VARIANT[row.status] ?? "default"}>{row.status}</Badge>
          {/* 只有「已结算 且 关账时未结余额 > 0」才标记：outstanding_amount = 0 的已结算账单不显示，避免噪音 */}
          {row.status === "已结算" && row.outstanding_amount > 0 && (
            <Badge variant="warning" className="cursor-help">
              <span title={`关账时未结 ¥ ${formatAmount(row.outstanding_amount)}｜减免原因：${row.write_off_reason ?? "—"}`}>
                关账时未结
              </span>
            </Badge>
          )}
        </div>
      ),
    },
    {
      key: "total_amount",
      header: "合计（元）",
      render: (row) => <span className="font-medium">{formatAmount(row.total_amount)}</span>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <div className="flex items-center gap-1">
          <Button variant="link" size="sm" onClick={() => void openDetail(row)}>明细</Button>
          {row.status === "待缴费" && (
            <>
              <Button variant="link" size="sm" onClick={() => openAddItem(row)}>加项</Button>
              <Button variant="link" size="sm" onClick={() => void openPayment(row)}>缴费</Button>
            </>
          )}
        </div>
      ),
    },
  ];

  const itemColumns: Column<BillItem>[] = [
    {
      key: "source",
      header: "来源",
      render: (row) => <Badge variant={row.source === "自动" ? "default" : "info"}>{row.source}</Badge>,
    },
    { key: "item_name", header: "项目", render: (row) => <span className="text-fg">{row.item_name}</span> },
    { key: "unit_price", header: "单价（元）", render: (row) => formatAmount(row.unit_price) },
    { key: "quantity", header: "数量", render: (row) => row.quantity },
    {
      key: "amount",
      header: "金额（元）",
      render: (row) => <span className="font-medium">{formatAmount(row.amount)}</span>,
    },
    { key: "remark", header: "备注", render: (row) => <span className="text-fg-muted text-sm">{row.remark ?? "—"}</span> },
  ];

  const paymentColumns: Column<Payment>[] = [
    {
      key: "created_at",
      header: "时间",
      render: (row) => <span className="text-fg-muted text-sm">{formatDateTime(row.created_at, "—")}</span>,
    },
    {
      key: "method",
      header: "方式",
      // 缴费流水可能出现结算核销写入的 method = 押金（客户端不可提交），单独标注区分
      render: (row) =>
        row.method === "押金" ? <Badge variant="info">押金核销</Badge> : <span className="text-fg">{row.method}</span>,
    },
    {
      key: "amount",
      header: "金额（元）",
      render: (row) => <span className="text-success font-medium">−{formatAmount(row.amount)}</span>,
    },
    { key: "operator", header: "操作人", render: (row) => <span className="text-fg-muted text-sm">{row.operator}</span> },
    { key: "remark", header: "备注", render: (row) => <span className="text-fg-muted text-sm">{row.remark ?? "—"}</span> },
  ];

  const arrearColumns: Column<Arrear>[] = [
    {
      key: "encounter",
      header: "长者",
      render: (row) => (
        <span className="text-fg">{admissionById.get(row.encounter_id)?.patientName ?? row.encounter_id}</span>
      ),
    },
    {
      key: "period",
      header: "账期",
      render: (row) => (
        <span className="text-fg-muted text-sm">{formatDate(row.period_start, "—")} ~ {formatDate(row.period_end, "—")}</span>
      ),
    },
    { key: "total_amount", header: "合计（元）", render: (row) => formatAmount(row.total_amount) },
    { key: "paid_amount", header: "已缴（元）", render: (row) => formatAmount(row.paid_amount) },
    {
      key: "balance",
      header: "欠费（元）",
      render: (row) => <span className="text-danger font-medium">{formatAmount(row.balance)}</span>,
    },
    {
      key: "actions",
      header: "操作",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openPayment(row, row.balance)}>缴费</Button>
      ),
    },
  ];

  const modalError = actionError && (
    <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
  );

  /**
   * 生成账单前置提示：缺项时逐条说明缺哪一类（文案取自服务端 requirements），
   * 其余阻塞原因（如该账期已生成）直接显示 `blocked_by` 的中文映射。
   * 服务端 precheck 不可用时 `generateBlockNotices` 为空 → 不渲染本区块（刻意降级）。
   */
  const generateGateNotice = generateBlockNotices.length > 0 && (
    <div className="mb-4 rounded-md border border-warning/30 bg-warning-bg px-4 py-3 text-sm">
      <p className="font-medium text-warning">
        {/* 标题与按钮可用性同源（都取 can_generate），避免出现「已禁用」但按钮可用 */}
        {precheckAllowsGenerate
          ? "生成校验提示"
          : precheck?.blocked_by != null && precheck.blocked_by !== "missing_fee_items"
            ? "当前账期不能生成账单"
            : "费用字典缺项，「生成账单」已禁用"}
      </p>
      <ul className="mt-2 space-y-1 text-fg-muted">
        {generateBlockNotices.map((notice) => (
          <li key={notice.key}>· {notice.text}</li>
        ))}
      </ul>
      {feeItemsEmpty && (
        <p className="mt-2 text-fg-muted">
          当前没有任何启用的费用项目。自动计费至少需要：床位费 ×1、护理费 ×每个评估等级 ×1、伙食费 ×1。
        </p>
      )}
      <div className="mt-3">
        <Button variant="secondary" size="sm" onClick={() => window.location.assign(FEE_ITEMS_PAGE_PATH)}>
          去配置费用项目
        </Button>
      </div>
    </div>
  );

  /**
   * 自动计费口径说明 + 本账期口径提示（计划 030 §4.2）：始终渲染口述口径，
   * 服务端 `notices` 存在时逐条补上「哪一类不计 + 原因 + 下一步」。不阻塞生成。
   */
  const generateCaliberNotice = precheck !== null && (
    <div className="mb-4 rounded-md border border-border bg-surface-alt px-4 py-3 text-xs text-fg-muted">
      <p className="font-medium text-fg">自动计费口径</p>
      <ul className="mt-2 space-y-1">
        <li>· 床位费 = 床位单价 × 账期内在院天数（入住日与离院日均计费）。</li>
        <li>
          · 护理费 = 按护理评估「结果等级」分段，每段 = 该等级绑定的护理费单价 × 天数；
          <span className="text-fg">没有护理评估覆盖的天数不计护理费</span>。
        </li>
        <li>
          · 伙食费 = 账期内就餐登记折合餐次 × 单价（正常=1、部分=0.5、未就餐/拒食=0）；
          <span className="text-fg">没有就餐登记不计伙食费</span>。
        </li>
      </ul>
      {precheckNotices.length > 0 && (
        <ul className="mt-2 space-y-1 text-warning">
          {precheckNotices.map((notice) => (
            <li key={notice.key}>· {notice.text}</li>
          ))}
        </ul>
      )}
    </div>
  );

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-fg-emphasis">养老收费</h2>
        <p className="text-sm text-fg-muted mt-1">
          账单生成（按月自动计费）、手工加项、缴费、欠费列表与结算关账入口。押金登记/退押见「押金管理」页。
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
                <Badge variant={ENCOUNTER_STATUS_VARIANT[selectedAdmission.status] ?? "default"}>
                  {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
                </Badge>
                {selectedAdmission.settled_at && <Badge variant="default">已关账</Badge>}
                <span className="text-sm text-fg-muted">
                  {selectedAdmission.patientName} · {selectedAdmission.department ?? "—"} {selectedAdmission.ward ?? ""}
                </span>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* 账单列表：账单生成 + 手工加项 + 缴费 */}
      <Card
        title="账单列表"
        actions={
          <Button
            size="sm"
            onClick={openGenerate}
            disabled={!canGenerateBill || Boolean(selectedAdmission?.settled_at)}
          >
            生成账单
          </Button>
        }
      >
        {!selectedEncounterId ? (
          <EmptyState icon="🧾" title="请先选择入住" description="选择入住后展示账单，并可生成账单、手工加项、缴费" />
        ) : (
          <>
            {actionError && !generateOpen && !addItemBill && !payTarget && !settleOpen && !detail && (
              <div className="mb-4 rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{actionError}</div>
            )}
            {generateGateNotice}
            {selectedAdmission?.settled_at && (
              <p className="mb-4 text-sm text-fg-muted">该入住已关账，账单已冻结（不可生成账单、手工加项或缴费）。</p>
            )}
            <Table
              columns={billColumns}
              data={bills}
              keyField="id"
              loading={billsLoading}
              emptyMessage="暂无账单，点击右上角「生成账单」按月自动计费"
            />
            {billTotal > 0 && (
              <p className="text-xs text-fg-dimmed mt-3">共 {billTotal} 条账单（当前显示最近 {bills.length} 条）</p>
            )}
          </>
        )}
      </Card>

      {/* 结算入口 */}
      <Card title="结算关账">
        {!selectedAdmission ? (
          <EmptyState icon="🏁" title="请先选择入住" description="结算关账需要选定一位入住长者" />
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-3">
              <Badge variant={ENCOUNTER_STATUS_VARIANT[selectedAdmission.status] ?? "default"}>
                {ENCOUNTER_STATUS_LABEL[selectedAdmission.status] ?? selectedAdmission.status}
              </Badge>
              <span className="text-sm text-fg-muted">
                {selectedAdmission.patientName} · {selectedAdmission.encounter_no}
                {selectedAdmission.department ? ` · ${selectedAdmission.department} ${selectedAdmission.ward ?? ""}` : ""}
              </span>
              {selectedAdmission.settled_at ? (
                <Badge variant="default">已关账 {formatDateTime(selectedAdmission.settled_at, "—")}</Badge>
              ) : (
                <Badge variant="info">未结算</Badge>
              )}
            </div>
            <p className="text-sm text-fg-muted">
              {selectedAdmission.settled_at
                ? "该入住已关账：全部账单已冻结，不可再生成账单、手工加项或缴费。"
                : canSettle
                  ? "该入住已离院/去世且未结算：结算关账将生成区间最终账单并冻结全部账单，冻结后不可再生成账单、手工加项或缴费。关账时可选择用押金余额手工核销欠费。"
                  : "结算关账适用于已离院/去世的养老入住；在住入住不可结算。"}
            </p>
            {!selectedAdmission.settled_at && (
              <div className="grid gap-4 md:grid-cols-2">
                <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                  <p className="text-xs text-fg-dimmed">当前押金余额（元）</p>
                  <p className="text-lg font-bold text-accent mt-0.5">
                    {depositLoading ? "加载中…" : depositBalance === null ? "—" : formatAmount(depositBalance)}
                  </p>
                  <p className="text-xs text-fg-dimmed mt-1">余额 = 累计登记 − 累计退押 − 累计核销</p>
                </div>
                <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                  <p className="text-xs text-fg-dimmed">本入住欠费合计（元）</p>
                  <p className="text-lg font-bold text-danger mt-0.5">
                    {encounterArrearsLoading ? "加载中…" : formatAmount(encounterArrears)}
                  </p>
                  <p className="text-xs text-fg-dimmed mt-1">核销上限 = min(押金余额, 欠费合计) = ¥ {formatAmount(offsetLimit)}</p>
                </div>
              </div>
            )}
            {depositError && (
              <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{depositError}</div>
            )}
            {encounterArrearsError && (
              <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{encounterArrearsError}</div>
            )}
            {!selectedAdmission.settled_at && (
              <Button
                variant="warning"
                disabled={!canSettle}
                onClick={openSettle}
              >
                结算关账
              </Button>
            )}
          </div>
        )}
      </Card>

      {/* 离院结算向导（030 W4）：把「关账（含核销）→ 退押」串成三步，避免离院时漏步骤。
          仅对已离院/已去世或已关账的入住显示；在住长者的账期账单仍走上方生成/缴费入口。 */}
      {selectedAdmission && (selectedAdmission.settled_at || selectedAdmission.status !== "ACTIVE") && (
        <Card title="离院结算向导">
          <p className="mb-4 text-sm text-fg-muted">
            按「① 生成区间最终账单 → ② 核销关账 → ③ 退还押金余额」三步收尾，每步完成后再进行下一步。
            与上方「结算关账」卡片共用同一套服务端口径（同一资格校验与押金余额来源），这里只把三步串成流程。
          </p>
          <DischargeSettlementWizard
            encounterId={selectedAdmission.id}
            onSettled={() => {
              refreshAfterChange();
              void loadDepositBalance(selectedAdmission.id);
              void loadAdmissions();
            }}
          />
        </Card>
      )}

      {/* 欠费列表 */}
      <Card
        title="欠费列表"
        actions={
          <Button variant="ghost" size="sm" onClick={() => void loadArrearsAndSummary()} disabled={arrearsLoading}>
            刷新
          </Button>
        }
      >
        {summary && (
          <>
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4 mb-5">
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">应缴合计（元）</p>
                <p className="text-lg font-bold text-fg mt-0.5">{formatAmount(summary.due_amount)}</p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">已缴合计（元）</p>
                <p className="text-lg font-bold text-success mt-0.5">{formatAmount(summary.paid_amount)}</p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">欠费合计（元）</p>
                <p className="text-lg font-bold text-danger mt-0.5">{formatAmount(summary.arrears_amount)}</p>
              </div>
              {/* 关账减免：既不是收入（已缴）也不是欠费，故用 warning 系配色以示「已放弃」 */}
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">关账减免（元）</p>
                <p className="text-lg font-bold text-warning mt-0.5">{formatAmount(summary.write_off_amount ?? 0)}</p>
                <p className="text-xs text-fg-dimmed mt-1">关账时未结而被放弃的金额合计（不并入欠费）</p>
              </div>
            </div>
            <p className="text-xs text-fg-dimmed mt-3 mb-5">应缴 − 已缴 = 欠费 + 关账减免（减免为 0 时即 应缴 − 已缴 = 欠费）</p>
          </>
        )}
        {arrearsError && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{arrearsError}</div>
        )}
        <Table
          columns={arrearColumns}
          data={arrears}
          keyField="id"
          loading={arrearsLoading}
          emptyMessage="暂无欠费账单"
        />
        {arrears.length > 0 && (
          <p className="text-xs text-fg-dimmed mt-3">共 {arrears.length} 条欠费账单（当前显示最近 {PAGE_SIZE} 条）</p>
        )}
      </Card>

      {/* 生成账单 */}
      <Modal open={generateOpen} onClose={() => setGenerateOpen(false)} title="生成账单">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            按自然月自动计费（床位费/护理费/伙食费），账期裁剪到在院区间；同入住同账期仅能生成一次。
            {selectedAdmission ? ` 当前入住：${selectedAdmission.patientName}。` : ""}
          </p>          <Input label="账期（月）" value={month} onChange={(event) => setMonth(event.target.value)} placeholder="YYYY-MM" />
          {generateGateNotice}
          {generateCaliberNotice}
          {modalError}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setGenerateOpen(false)}>取消</Button>
            {/* 服务端 can_generate 为 false 时禁用；precheck 不可用时不禁用，提交后由后端报错 */}
            <Button loading={generating} disabled={!precheckAllowsGenerate} onClick={() => void handleGenerate()}>生成账单</Button>
          </div>
        </div>
      </Modal>

      {/* 手工加项 */}
      <Modal open={addItemBill !== null} onClose={() => setAddItemBill(null)} title="手工加项">
        {addItemBill && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              为 {formatDate(addItemBill.period_start, "—")} ~ {formatDate(addItemBill.period_end, "—")} 账单添加自费药/检查费等手工项目；
              明细为字典快照，单价缺省取字典单价，可覆盖。
            </p>
            {feeItemsLoading ? (
              <div className="flex justify-center py-6"><LoadingSpinner /></div>
            ) : feeItems.length === 0 ? (
              <EmptyState icon="🧾" title="暂无启用费用项目" description="请先配置启用的费用字典项目" />
            ) : (
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">费用项目</label>
                <select
                  value={itemId}
                  onChange={(event) => setItemId(event.target.value)}
                  className="h-10 px-3 rounded-md bg-surface border border-border text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <option value="">请选择</option>
                  {feeItems.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.category} · {item.name}
                      {item.category === "护理费" && item.nursing_level ? `（等级 ${item.nursing_level}）` : ""}
                      （¥ {formatAmount(item.unit_price)}）
                    </option>
                  ))}
                </select>
              </div>
            )}
            <Input label="数量" type="number" min="0.01" step="0.01" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
            <Input
              label="单价覆盖（元，可选）"
              type="number"
              min="0.01"
              step="0.01"
              value={unitPrice}
              onChange={(event) => setUnitPrice(event.target.value)}
              placeholder="缺省取字典单价"
            />
            <Input label="备注（可选）" value={itemRemark} onChange={(event) => setItemRemark(event.target.value)} maxLength={500} />
            {modalError}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="ghost" onClick={() => setAddItemBill(null)}>取消</Button>
              <Button loading={addingItem} onClick={() => void handleAddItem()}>确认加项</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 缴费 */}
      <Modal open={payTarget !== null} onClose={() => setPayTarget(null)} title="缴费">
        {payTarget && (
          <div className="space-y-4">
            <p className="text-sm text-fg-muted">
              账单 {formatDate(payTarget.bill.period_start, "—")} ~ {formatDate(payTarget.bill.period_end, "—")}：
              合计 ¥ {formatAmount(payTarget.bill.total_amount)}，剩余应缴 ¥ {formatAmount(payTarget.remaining)}。
              支持多次部分缴费，余额归零后账单转为已结清。
            </p>
            <Input
              label="缴费金额（元）"
              type="number"
              min="0.01"
              step="0.01"
              value={payAmount}
              onChange={(event) => setPayAmount(event.target.value)}
            />
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted">缴费方式</label>
              <div className="flex flex-wrap gap-2">
                {PAYMENT_METHODS.map((method) => (
                  <button
                    key={method}
                    type="button"
                    onClick={() => setPayMethod(method)}
                    className={`h-9 px-3 rounded-md text-sm border transition-colors cursor-pointer ${
                      payMethod === method
                        ? "border-accent text-accent bg-accent/10"
                        : "border-border text-fg-muted hover:text-fg"
                    }`}
                  >
                    {method}
                  </button>
                ))}
              </div>
            </div>
            <Input label="备注（可选）" value={payRemark} onChange={(event) => setPayRemark(event.target.value)} maxLength={500} />
            {modalError}
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="ghost" onClick={() => setPayTarget(null)}>取消</Button>
              <Button loading={paying} onClick={() => void handlePay()}>确认缴费</Button>
            </div>
          </div>
        )}
      </Modal>

      {/* 账单明细 */}
      <Modal open={detail !== null} onClose={() => setDetail(null)} title="账单明细" width="48rem">
        {detail && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center gap-3">
              <Badge variant={BILL_STATUS_VARIANT[detail.bill.status] ?? "default"}>{detail.bill.status}</Badge>
              <span className="text-sm text-fg-muted">
                {formatDate(detail.bill.period_start, "—")} ~ {formatDate(detail.bill.period_end, "—")} · 合计 ¥ {formatAmount(detail.bill.total_amount)}
              </span>
              {detail.bill.settled_at && <Badge variant="default">已关账 {formatDateTime(detail.bill.settled_at, "—")}</Badge>}
            </div>
            {/* 关账时未结（减免）留痕：仅 outstanding_amount > 0 的已结算账单显示 */}
            {detail.bill.status === "已结算" && detail.bill.outstanding_amount > 0 && (
              <div className="rounded-md border border-warning/30 bg-warning-bg px-4 py-3 text-sm text-warning">
                <p className="font-medium">关账时未结 ¥ {formatAmount(detail.bill.outstanding_amount)}</p>
                <p className="mt-1">减免原因：{detail.bill.write_off_reason ?? "—"}</p>
                <p className="mt-1 text-xs">该未结余额在结算关账时被显式放弃（减免），已随关账冻结、不可再收。</p>
              </div>
            )}
            <div>
              <h4 className="text-sm font-semibold text-fg-muted mb-2">明细（{detail.items.length}）</h4>
              <Table columns={itemColumns} data={detail.items} keyField="id" emptyMessage="暂无明细" />
            </div>
            <div>
              <h4 className="text-sm font-semibold text-fg-muted mb-2">缴费流水（{detail.payments.length}）</h4>
              <Table columns={paymentColumns} data={detail.payments} keyField="id" emptyMessage="暂无缴费记录" />
            </div>
            {detail.bill.status === "待缴费" && (
              <div className="flex justify-end gap-2 pt-1">
                <Button
                  variant="ghost"
                  onClick={() => {
                    const bill = detail.bill;
                    setDetail(null);
                    openAddItem(bill);
                  }}
                >
                  手工加项
                </Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    const bill = detail.bill;
                    setDetail(null);
                    void openPayment(bill);
                  }}
                >
                  缴费
                </Button>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* 结算关账确认（含押金核销） */}
      <Modal open={settleOpen} onClose={() => setSettleOpen(false)} title="确认结算关账">
        <div className="space-y-4">
          <p className="text-sm text-fg-muted">
            将为{selectedAdmission?.patientName ?? ""}生成区间最终账单并冻结全部账单（关账后不可再生成账单、手工加项或缴费）。
          </p>

          {/* 结算预览（只读，与关账实际算法同源）：提交前告知会发生什么，含本次将生成的区间最终账单 */}
          {previewLoading ? (
            <div className="flex items-center gap-2 rounded-md border border-border bg-surface-alt px-4 py-3 text-sm text-fg-muted">
              <LoadingSpinner size={16} />
              <span>正在加载结算预览…</span>
            </div>
          ) : settlePreview ? (
            <div className="grid gap-3 md:grid-cols-2">
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">区间最终账单金额（元）</p>
                <p className="text-lg font-bold text-fg mt-0.5">
                  {hasFinalBill ? formatAmount(settlePreview.final_bill_total) : "—"}
                </p>
                <p className="text-xs text-fg-dimmed mt-1">
                  {hasFinalBill
                    ? `账期 ${formatDate(settlePreview.settlement_period?.start, "—")} ~ ${formatDate(settlePreview.settlement_period?.end, "—")}`
                    : "本次不生成区间最终账单"}
                </p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">当前欠费（核销前，元）</p>
                <p className="text-lg font-bold text-danger mt-0.5">{formatAmount(settlePreview.pending_balance)}</p>
                <p className="text-xs text-fg-dimmed mt-1">既有「待缴费」账单未结合计</p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">关账后未结合计（元）</p>
                <p className="text-lg font-bold text-warning mt-0.5">{formatAmount(settlePreview.outstanding_total)}</p>
                <p className="text-xs text-fg-dimmed mt-1">= 当前欠费 + 区间最终账单金额</p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3">
                <p className="text-xs text-fg-dimmed">当前押金余额（元）</p>
                <p className="text-lg font-bold text-accent mt-0.5">{formatAmount(settlePreview.deposit_balance)}</p>
              </div>
              <div className="rounded-md border border-border bg-surface-alt px-4 py-3 md:col-span-2">
                <p className="text-xs text-fg-dimmed">可核销上限（元）</p>
                <p className="text-lg font-bold text-fg mt-0.5">{formatAmount(settlePreview.max_offset)}</p>
                <p className="text-xs text-fg-dimmed mt-1">
                  = min(押金余额 ¥ {formatAmount(settlePreview.deposit_balance)}, 未结合计 ¥{" "}
                  {formatAmount(settlePreview.outstanding_total)})
                </p>
              </div>
            </div>
          ) : (
            <div className="rounded-md border border-warning/30 bg-warning-bg px-4 py-3 text-sm text-warning">
              <p className="font-medium">结算预览不可用</p>
              <p className="mt-1">{previewError || "无法加载结算预览"}</p>
              <p className="mt-1 text-xs">
                本次不显示预览数字，也不做前端核销上限校验：仍可提交，以服务端返回的结果为准
                （若仍有未结余额，服务端会拒绝并提示填写减免原因）。
              </p>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <Input
                  label="押金核销金额（元，可留空 = 不核销）"
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
                disabled={previewMaxOffset === null || previewMaxOffset <= 0}
                onClick={() => setOffsetAmount(formatAmount(previewMaxOffset ?? 0))}
              >
                全部核销
              </Button>
            </div>
            <p className="text-xs text-fg-dimmed">
              押金核销只在结算关账时发生：核销会同时写入缴费流水（方式「押金」）与押金台账（类型「核销」），
              押金余额与账单欠费同减，不产生现金流入。
            </p>
          </div>

          {depositError && (
            <div className="rounded-md border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{depositError}</div>
          )}

          {/* 减免确认（本次任务核心）：核销后仍有未结时，「勾选确认」与「原因」缺一不可，否则禁止提交 */}
          {remaining === null ? (
            <div className="space-y-3 rounded-md border border-border bg-surface-alt px-4 py-3 text-sm">
              <p className="text-fg-muted">
                预览不可用，无法在此计算「核销后仍未结」，因此不做强制减免确认。
                若服务端拒绝并提示仍有未结余额需要减免，请在此填写减免原因后重新提交（服务端以是否提供原因为准）。
              </p>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted">
                  减免原因（可选，最多 {WRITE_OFF_REASON_MAX_LENGTH} 字符）
                </label>
                <textarea
                  value={writeOffReason}
                  maxLength={WRITE_OFF_REASON_MAX_LENGTH}
                  rows={3}
                  onChange={(event) => setWriteOffReason(event.target.value)}
                  placeholder="如：离院结算，家属书面确认不再追收"
                  className="px-3 py-2 rounded-md bg-surface border border-border text-sm text-fg placeholder:text-fg-dimmed transition-colors duration-150 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                />
              </div>
            </div>
          ) : remaining > 0 ? (
            <div className="space-y-3 rounded-md border border-warning/30 bg-warning-bg px-4 py-3 text-sm">
              <p className="font-medium text-warning">
                核销后仍未结 ¥ {formatAmount(remaining)}，将随本次关账冻结、不可再收。
              </p>
              {previewRequiresWriteOff && (
                <p className="text-warning">即使全额核销仍会剩余未结，必须填写减免原因。</p>
              )}
              <label className="flex items-center gap-2 text-fg cursor-pointer">
                <input
                  type="checkbox"
                  checked={writeOffConfirmed}
                  onChange={(event) => setWriteOffConfirmed(event.target.checked)}
                />
                <span>确认减免：我确认放弃上述未结余额，并已取得相应依据</span>
              </label>
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
              核销后未结清零，无需减免确认。
            </div>
          )}

          {modalError}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setSettleOpen(false)}>取消</Button>
            <Button
              variant="warning"
              loading={settling}
              disabled={settleDisabled}
              onClick={() => void handleSettle()}
            >
              确认结算
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
