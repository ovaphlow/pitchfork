import { useCallback, useEffect, useState } from "react";
import { Badge, Button, Card, EmptyState, Input, Modal, Table, type Column } from "@pitchfork/ui";
import {
  createMedicalOrder,
  getMedicalOrder,
  listActiveElderlyAdmissions,
  listMedicalOrders,
  listPatients,
  updateMedicalOrderStatus,
  type Encounter,
  type MedicalOrder,
  type MedicalOrderExecutionSummary,
  type MedicalOrderInput,
} from "@pitchfork/shared/aceso";

interface ActiveAdmission extends Encounter {
  patientName: string;
}

interface OrderForm {
  orderType: string;
  orderContent: string;
  doctor: string;
  startTime: string;
  drugName: string;
  dose: string;
  unit: string;
  route: string;
  frequencyCode: string;
  frequencyName: string;
  durationDays: string;
  remark: string;
  treatmentItem: string;
  itemName: string;
}

const orderFormDefaults: OrderForm = {
  orderType: "MEDICATION",
  orderContent: "",
  doctor: "",
  startTime: "",
  drugName: "",
  dose: "",
  unit: "",
  route: "",
  frequencyCode: "",
  frequencyName: "",
  durationDays: "",
  remark: "",
  treatmentItem: "",
  itemName: "",
};

const FREQUENCY_OPTIONS: Array<{ code: string; label: string }> = [
  { code: "QD", label: "每日一次" },
  { code: "BID", label: "每日两次" },
  { code: "TID", label: "每日三次" },
  { code: "QID", label: "每日四次" },
  { code: "QOD", label: "隔日一次" },
  { code: "QW", label: "每周一次" },
  { code: "BIW", label: "每周两次" },
  { code: "TIW", label: "每周三次" },
  { code: "PRN", label: "按需" },
  { code: "STAT", label: "立即" },
];

const ORDER_TYPE_LABEL: Record<string, string> = {
  MEDICATION: "用药",
  THERAPY: "诊疗",
  EXAMINATION: "检查",
  LAB_TEST: "检验",
};

const ORDER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "进行中",
  DISCONTINUED: "已停嘱",
  CANCELLED: "已作废",
  COMPLETED: "已完成",
};

const ORDER_STATUS_VARIANT: Record<string, "default" | "success" | "warning" | "danger"> = {
  ACTIVE: "default",
  DISCONTINUED: "warning",
  CANCELLED: "danger",
  COMPLETED: "success",
};

const EXECUTION_SUMMARY_ITEMS: Array<[keyof MedicalOrderExecutionSummary, string]> = [
  ["PENDING", "待执行"],
  ["IN_PROGRESS", "执行中"],
  ["COMPLETED", "已完成"],
  ["SKIPPED", "已跳过"],
  ["CANCELLED", "已取消"],
];

const ORDER_DETAIL_LABELS: Record<string, string> = {
  drug_name: "药名",
  dose: "剂量",
  unit: "单位",
  route: "途径",
  treatment_item: "诊疗项目",
  item_name: "项目名称",
  frequency_code: "频次编码",
  frequency_name: "频次",
  duration_days: "天数",
  remark: "备注",
};

const selectClass = "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";
const textareaClass = "w-full resize-none rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDateTime(value: string | null | undefined): string {
  return value ? value.replace("T", " ").slice(0, 16) : "-";
}

/** SSR 安全地读取 URL 上的 encounter_id，用于进入页面时优先选中该入住 */
function readEncounterIdFromUrl(): string {
  if (typeof window === "undefined") return "";
  try {
    return new URLSearchParams(window.location.search).get("encounter_id") ?? "";
  } catch {
    return "";
  }
}

function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined) return "-";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

export default function OrdersPage() {
  const [admissions, setAdmissions] = useState<ActiveAdmission[]>([]);
  const [admissionsLoading, setAdmissionsLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [selectedEncounterId, setSelectedEncounterId] = useState("");
  const [preferredEncounterId] = useState(readEncounterIdFromUrl);

  const [orders, setOrders] = useState<MedicalOrder[]>([]);
  const [ordersTotal, setOrdersTotal] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState("");
  const [orderTypeFilter, setOrderTypeFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");

  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<OrderForm>(orderFormDefaults);
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  const [detailTarget, setDetailTarget] = useState<MedicalOrder | null>(null);
  const [detail, setDetail] = useState<MedicalOrder | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [statusAction, setStatusAction] = useState("");
  const [statusError, setStatusError] = useState("");

  const loadAdmissions = useCallback(async () => {
    setAdmissionsLoading(true);
    setPageError("");
    try {
      const [patientResponse, encounterResponse] = await Promise.all([
        listPatients({ status: "ACTIVE", limit: 100 }),
        listActiveElderlyAdmissions({ limit: 100 }),
      ]);
      const patientById = new Map(patientResponse.records.map((patient) => [patient.id, patient]));
      const records = encounterResponse.records.map((encounter) => ({
        ...encounter,
        patientName: patientById.get(encounter.patient_id)?.name ?? encounter.patient_id,
      }));
      setAdmissions(records);
      setSelectedEncounterId((current) => {
        if (records.some((record) => record.id === current)) return current;
        const candidate = preferredEncounterId || records[0]?.id || "";
        return records.some((record) => record.id === candidate) ? candidate : records[0]?.id || "";
      });
    } catch (error) {
      setPageError(errorMessage(error, "无法加载活动入住"));
    } finally {
      setAdmissionsLoading(false);
    }
  }, [preferredEncounterId]);

  const loadOrders = useCallback(async () => {
    if (!selectedEncounterId) return;
    setOrdersLoading(true);
    setOrdersError("");
    try {
      const response = await listMedicalOrders(selectedEncounterId, {
        order_type: orderTypeFilter || undefined,
        status: statusFilter || undefined,
        limit: 100,
      });
      setOrders(response.records);
      setOrdersTotal(response.meta.total);
    } catch (error) {
      setOrders([]);
      setOrdersError(errorMessage(error, "无法加载医嘱列表"));
    } finally {
      setOrdersLoading(false);
    }
  }, [selectedEncounterId, orderTypeFilter, statusFilter]);

  useEffect(() => {
    void loadAdmissions();
  }, [loadAdmissions]);

  useEffect(() => {
    void loadOrders();
  }, [loadOrders]);

  function openCreate() {
    setForm(orderFormDefaults);
    setFormError("");
    setEditorOpen(true);
  }

  function handleFrequencyChange(code: string) {
    const option = FREQUENCY_OPTIONS.find((item) => item.code === code);
    setForm((current) => ({
      ...current,
      frequencyCode: code,
      frequencyName: option ? option.label : current.frequencyName,
    }));
  }

  function buildOrderInput(): MedicalOrderInput | null {
    const orderContent = form.orderContent.trim();
    const doctor = form.doctor.trim();
    const startTime = form.startTime.trim();
    if (!orderContent || !doctor || !startTime) return null;

    const details: Record<string, unknown> = {};
    if (form.orderType === "MEDICATION") {
      details.drug_name = form.drugName.trim();
      if (form.dose.trim()) details.dose = form.dose.trim();
      if (form.unit.trim()) details.unit = form.unit.trim();
      if (form.route.trim()) details.route = form.route.trim();
    } else if (form.orderType === "THERAPY") {
      details.treatment_item = form.treatmentItem.trim();
    } else if (form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") {
      details.item_name = form.itemName.trim();
    }

    // 频次 code/name 必须成对提交：有 code 时补上名称，无 code 时两者都不提交
    if (form.frequencyCode) {
      const label = FREQUENCY_OPTIONS.find((option) => option.code === form.frequencyCode)?.label ?? "";
      details.frequency_code = form.frequencyCode;
      details.frequency_name = form.frequencyName.trim() || label;
    }

    const durationDays = form.durationDays.trim();
    if (durationDays) {
      const days = Number(durationDays);
      if (!Number.isInteger(days) || days < 1) return null;
      details.duration_days = days;
    }

    if (form.remark.trim()) details.remark = form.remark.trim();

    return {
      order_type: form.orderType,
      order_content: orderContent,
      doctor,
      start_time: `${startTime}:00+08:00`,
      ...(Object.keys(details).length > 0 ? { order_details: details } : {}),
    };
  }

  async function handleSave() {
    const input = buildOrderInput();
    if (!input) {
      if (!form.orderContent.trim() || !form.doctor.trim() || !form.startTime.trim()) {
        setFormError("医嘱正文、医生和开始时间不能为空");
      } else if (form.orderType === "MEDICATION" && !form.drugName.trim()) {
        setFormError("用药医嘱必须填写药名");
      } else if (form.orderType === "THERAPY" && !form.treatmentItem.trim()) {
        setFormError("诊疗医嘱必须填写诊疗项目");
      } else if ((form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && !form.itemName.trim()) {
        setFormError("检查/检验医嘱必须填写项目名称");
      } else {
        setFormError("时长必须是正整数");
      }
      return;
    }

    setSaving(true);
    setFormError("");
    try {
      await createMedicalOrder(selectedEncounterId, input);
      setEditorOpen(false);
      setForm(orderFormDefaults);
      await loadOrders();
    } catch (error) {
      setFormError(errorMessage(error, "无法开立医嘱"));
    } finally {
      setSaving(false);
    }
  }

  async function openDetail(order: MedicalOrder) {
    setDetailTarget(order);
    setDetail(null);
    setDetailError("");
    setStatusError("");
    setStatusAction("");
    setDetailLoading(true);
    try {
      const data = await getMedicalOrder(order.id);
      setDetail(data);
    } catch (error) {
      setDetailError(errorMessage(error, "无法读取医嘱详情"));
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    if (statusAction) return;
    setDetailTarget(null);
    setDetail(null);
    setDetailError("");
    setStatusError("");
    setStatusAction("");
  }

  async function handleStatusUpdate(orderId: string, status: string) {
    setStatusAction(status);
    setStatusError("");
    try {
      const updated = await updateMedicalOrderStatus(orderId, status);
      setDetail(updated);
      await loadOrders();
    } catch (error) {
      setStatusError(errorMessage(error, "无法更新医嘱状态"));
    } finally {
      setStatusAction("");
    }
  }

  const columns: Column<MedicalOrder>[] = [
    {
      key: "order_type",
      header: "类型",
      className: "min-w-[100px]",
      render: (row) => ORDER_TYPE_LABEL[row.order_type] ?? row.order_type,
    },
    {
      key: "order_content",
      header: "医嘱正文",
      className: "min-w-[240px] max-w-[380px]",
      render: (row) => (
        <span className="block truncate" title={row.order_content}>
          {row.order_content}
        </span>
      ),
    },
    {
      key: "doctor",
      header: "医生",
      className: "min-w-[110px]",
      render: (row) => row.doctor || "-",
    },
    {
      key: "start_time",
      header: "开始时间",
      className: "min-w-[150px]",
      render: (row) => formatDateTime(row.start_time),
    },
    {
      key: "status",
      header: "状态",
      className: "min-w-[100px]",
      render: (row) => (
        <Badge variant={ORDER_STATUS_VARIANT[row.status] ?? "default"}>
          {ORDER_STATUS_LABEL[row.status] ?? row.status}
        </Badge>
      ),
    },
    {
      key: "actions",
      header: "操作",
      className: "w-[90px]",
      render: (row) => (
        <Button variant="link" size="sm" onClick={() => void openDetail(row)}>
          详情
        </Button>
      ),
    },
  ];

  const selectedAdmission = admissions.find((admission) => admission.id === selectedEncounterId) ?? null;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg-emphasis">医嘱管理</h2>
          <p className="mt-1 text-sm text-fg-muted">选择活动入住后开立、筛选并查看医嘱</p>
        </div>
        {admissions.length > 0 && (
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-fg-muted" htmlFor="orders-encounter">活动入住</label>
              <select
                id="orders-encounter"
                className={`${selectClass} min-w-[260px]`}
                value={selectedEncounterId}
                onChange={(event) => setSelectedEncounterId(event.target.value)}
                disabled={admissionsLoading}
              >
                {admissions.map((admission) => (
                  <option key={admission.id} value={admission.id}>
                    {admission.patientName} · {admission.encounter_no}
                  </option>
                ))}
              </select>
            </div>
            <Button variant="primary" onClick={openCreate}>开立医嘱</Button>
          </div>
        )}
      </div>

      {pageError && <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{pageError}</div>}

      {admissionsLoading ? (
        <Card>
          <p className="py-10 text-center text-sm text-fg-dimmed">正在加载活动入住…</p>
        </Card>
      ) : admissions.length === 0 ? (
        <Card>
          <EmptyState
            icon="🏠"
            title="暂无活动入住"
            description="请先在入住管理办理养老入住，再开立医嘱。"
            action={
              <a
                href="/dashboard/admission"
                className="inline-flex items-center justify-center rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:brightness-110"
              >
                前往入住管理
              </a>
            }
          />
        </Card>
      ) : (
        <>
          {selectedAdmission && (
            <div className="rounded-lg border border-info/30 bg-info-bg px-4 py-3 text-sm text-info">
              当前入住：{selectedAdmission.patientName} · {selectedAdmission.encounter_no}（{selectedAdmission.ward || selectedAdmission.department || "未设置床位"}）
            </div>
          )}

          <Card
            title="医嘱列表"
            actions={<span className="text-sm text-fg-dimmed">共 {ordersTotal} 条</span>}
          >
            <div className="mb-4 flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="order-type-filter">类型</label>
                <select
                  id="order-type-filter"
                  className={selectClass}
                  value={orderTypeFilter}
                  onChange={(event) => setOrderTypeFilter(event.target.value)}
                >
                  <option value="">全部</option>
                  <option value="MEDICATION">用药医嘱</option>
                  <option value="THERAPY">诊疗医嘱</option>
                  <option value="EXAMINATION">检查医嘱</option>
                  <option value="LAB_TEST">检验医嘱</option>
                </select>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-fg-muted" htmlFor="order-status-filter">状态</label>
                <select
                  id="order-status-filter"
                  className={selectClass}
                  value={statusFilter}
                  onChange={(event) => setStatusFilter(event.target.value)}
                >
                  <option value="">全部</option>
                  <option value="ACTIVE">进行中</option>
                  <option value="DISCONTINUED">已停嘱</option>
                  <option value="CANCELLED">已作废</option>
                  <option value="COMPLETED">已完成</option>
                </select>
              </div>
              <Button variant="secondary" size="md" disabled={ordersLoading} onClick={() => void loadOrders()}>
                刷新
              </Button>
            </div>

            {ordersError && (
              <div className="mb-4 rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
                {ordersError}
              </div>
            )}

            <Table
              columns={columns}
              data={orders}
              loading={ordersLoading}
              emptyMessage="暂无医嘱记录"
            />
          </Card>
        </>
      )}

      {/* 开立医嘱弹窗 */}
      <Modal open={editorOpen} onClose={() => !saving && setEditorOpen(false)} title="开立医嘱">
        <form
          className="space-y-5"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void handleSave();
          }}
        >
          {formError && (
            <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
              {formError}
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-type">医嘱类型</label>
              <select
                id="order-type"
                className={selectClass}
                value={form.orderType}
                onChange={(event) => setForm((current) => ({ ...current, orderType: event.target.value }))}
              >
                <option value="MEDICATION">用药医嘱</option>
                <option value="THERAPY">诊疗医嘱</option>
                <option value="EXAMINATION">检查医嘱</option>
                <option value="LAB_TEST">检验医嘱</option>
              </select>
            </div>

            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-content">医嘱正文（必填）</label>
              <textarea
                id="order-content"
                className={textareaClass}
                rows={3}
                maxLength={2000}
                value={form.orderContent}
                onChange={(event) => setForm((current) => ({ ...current, orderContent: event.target.value }))}
                placeholder="请输入医嘱正文，最多 2000 字"
                required
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                label="医生（必填）"
                value={form.doctor}
                onChange={(event) => setForm((current) => ({ ...current, doctor: event.target.value }))}
                placeholder="请输入开嘱医生"
                maxLength={100}
                required
              />
            </div>

            <div className="sm:col-span-2">
              <Input
                label="开始时间（必填）"
                type="datetime-local"
                value={form.startTime}
                onChange={(event) => setForm((current) => ({ ...current, startTime: event.target.value }))}
                required
              />
            </div>

            {form.orderType === "MEDICATION" && (
              <>
                <Input
                  label="药名（必填）"
                  value={form.drugName}
                  onChange={(event) => setForm((current) => ({ ...current, drugName: event.target.value }))}
                  placeholder="请输入药名"
                  required
                />
                <Input
                  label="剂量"
                  value={form.dose}
                  onChange={(event) => setForm((current) => ({ ...current, dose: event.target.value }))}
                  placeholder="如 500mg"
                />
                <Input
                  label="单位"
                  value={form.unit}
                  onChange={(event) => setForm((current) => ({ ...current, unit: event.target.value }))}
                  placeholder="如 片/次"
                />
                <Input
                  label="途径"
                  value={form.route}
                  onChange={(event) => setForm((current) => ({ ...current, route: event.target.value }))}
                  placeholder="如 口服"
                />
              </>
            )}

            {form.orderType === "THERAPY" && (
              <div className="sm:col-span-2">
                <Input
                  label="诊疗项目（必填）"
                  value={form.treatmentItem}
                  onChange={(event) => setForm((current) => ({ ...current, treatmentItem: event.target.value }))}
                  placeholder="请输入诊疗项目"
                  required
                />
              </div>
            )}

            {(form.orderType === "EXAMINATION" || form.orderType === "LAB_TEST") && (
              <div className="sm:col-span-2">
                <Input
                  label="项目名称（必填）"
                  value={form.itemName}
                  onChange={(event) => setForm((current) => ({ ...current, itemName: event.target.value }))}
                  placeholder="请输入检查/检验项目名称"
                  required
                />
              </div>
            )}

            {(form.orderType === "MEDICATION" || form.orderType === "THERAPY") && (
              <>
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-fg-muted" htmlFor="order-frequency">频次</label>
                  <select
                    id="order-frequency"
                    className={selectClass}
                    value={form.frequencyCode}
                    onChange={(event) => handleFrequencyChange(event.target.value)}
                  >
                    <option value="">不指定</option>
                    {FREQUENCY_OPTIONS.map((option) => (
                      <option key={option.code} value={option.code}>{option.label}</option>
                    ))}
                  </select>
                </div>
                <Input
                  label="频次名称"
                  value={form.frequencyName}
                  onChange={(event) => setForm((current) => ({ ...current, frequencyName: event.target.value }))}
                  placeholder="选中频次后自动填写"
                />
                <div className="sm:col-span-2">
                  <Input
                    label="时长（天）"
                    type="number"
                    min={1}
                    step={1}
                    value={form.durationDays}
                    onChange={(event) => setForm((current) => ({ ...current, durationDays: event.target.value }))}
                    placeholder="正整数"
                  />
                </div>
              </>
            )}

            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <label className="text-sm font-medium text-fg-muted" htmlFor="order-remark">备注（可选）</label>
              <textarea
                id="order-remark"
                className={textareaClass}
                rows={2}
                value={form.remark}
                onChange={(event) => setForm((current) => ({ ...current, remark: event.target.value }))}
                placeholder="请输入备注"
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-1">
            <Button type="button" variant="ghost" onClick={() => setEditorOpen(false)} disabled={saving}>取消</Button>
            <Button type="submit" loading={saving} disabled={saving}>保存医嘱</Button>
          </div>
        </form>
      </Modal>

      {/* 医嘱详情弹窗 */}
      <Modal
        open={detailTarget !== null}
        onClose={closeDetail}
        title="医嘱详情"
      >
        {detailLoading && <p className="text-sm text-fg-dimmed">正在读取医嘱详情…</p>}

        {!detailLoading && detailError && !detail && (
          <div className="space-y-4">
            <div className="rounded-lg border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">{detailError}</div>
            <div className="flex justify-end">
              <Button type="button" variant="ghost" onClick={closeDetail}>关闭</Button>
            </div>
          </div>
        )}

        {!detailLoading && detail && (
          <div className="space-y-5">
            <div className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <p><span className="text-fg-dimmed">医嘱 ID：</span><span className="break-all">{detail.id}</span></p>
              <p><span className="text-fg-dimmed">入住 ID：</span><span className="break-all">{detail.encounter_id}</span></p>
              <p><span className="text-fg-dimmed">类型：</span>{ORDER_TYPE_LABEL[detail.order_type] ?? detail.order_type}</p>
              <p>
                <span className="text-fg-dimmed">状态：</span>
                <Badge variant={ORDER_STATUS_VARIANT[detail.status] ?? "default"}>
                  {ORDER_STATUS_LABEL[detail.status] ?? detail.status}
                </Badge>
              </p>
              <p className="sm:col-span-2"><span className="text-fg-dimmed">医嘱正文：</span>{detail.order_content}</p>
              <p><span className="text-fg-dimmed">医生：</span>{detail.doctor || "-"}</p>
              <p><span className="text-fg-dimmed">开始时间：</span>{formatDateTime(detail.start_time)}</p>
              <p><span className="text-fg-dimmed">结束时间：</span>{formatDateTime(detail.end_time)}</p>
              <p><span className="text-fg-dimmed">任务 ID：</span>{detail.task_id ?? "-"}</p>
              <p><span className="text-fg-dimmed">创建时间：</span>{formatDateTime(detail.created_at)}</p>
              <p><span className="text-fg-dimmed">更新时间：</span>{formatDateTime(detail.updated_at)}</p>
            </div>

            <section>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">医嘱明细</h4>
              {Object.keys(detail.order_details ?? {}).length === 0 ? (
                <p className="text-sm text-fg-dimmed">无结构化明细</p>
              ) : (
                <div className="grid gap-x-6 gap-y-1.5 rounded-md border border-border bg-surface-alt p-3 text-sm sm:grid-cols-2">
                  {Object.entries(detail.order_details).map(([key, value]) => (
                    <p key={key}>
                      <span className="text-fg-dimmed">{ORDER_DETAIL_LABELS[key] ?? key}：</span>
                      {formatDetailValue(value)}
                    </p>
                  ))}
                </div>
              )}
            </section>

            <section>
              <h4 className="mb-2 text-sm font-semibold text-fg-emphasis">执行汇总</h4>
              {detail.execution_summary ? (
                <div className="flex flex-wrap gap-2">
                  {EXECUTION_SUMMARY_ITEMS.map(([key, label]) => (
                    <span key={key} className="inline-flex items-center gap-1 rounded-full bg-surface-alt px-3 py-1 text-sm text-fg-muted">
                      <span className="font-semibold text-fg">{detail.execution_summary?.[key] ?? 0}</span>
                      {label}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-fg-dimmed">暂无执行汇总</p>
              )}
            </section>

            {detail.status === "ACTIVE" && (
              <div className="border-t border-border pt-4">
                <p className="mb-2 text-sm text-fg-muted">医嘱状态操作：</p>
                {statusError && (
                  <div role="alert" className="mb-3 rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
                    {statusError}
                  </div>
                )}
                <div className="flex flex-wrap gap-3">
                  <Button
                    variant="warning"
                    size="sm"
                    loading={statusAction === "DISCONTINUED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "DISCONTINUED")}
                  >
                    停嘱
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    loading={statusAction === "CANCELLED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "CANCELLED")}
                  >
                    作废
                  </Button>
                  <Button
                    variant="primary"
                    size="sm"
                    loading={statusAction === "COMPLETED"}
                    disabled={statusAction !== ""}
                    onClick={() => void handleStatusUpdate(detail.id, "COMPLETED")}
                  >
                    完成
                  </Button>
                </div>
              </div>
            )}

            <div className="flex justify-end">
              <Button type="button" variant="ghost" onClick={closeDetail}>关闭</Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
