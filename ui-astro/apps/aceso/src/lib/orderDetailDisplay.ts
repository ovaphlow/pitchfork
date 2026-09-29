/**
 * 医嘱「主明细」展示规则 — 医生页（OrdersPage）与护理页（NursingPage）共用。
 *
 * 医嘱正文（`order_content`）是自由文本，结构化事实在 `order_details` JSONB 里；
 * 用药医嘱若只显示正文，护士看不出是什么药、多少剂量、什么途径。这里把医生页
 * 已有的推导规则抽出来，避免医生页与护理页的展示口径漂移。
 */

/** 各医嘱类型的主明细字段，与后端 DETAIL_WHITELIST / REQUIRED_DETAIL_KEY 保持一致 */
export const ORDER_PRIMARY_DETAIL_KEY: Record<string, string> = {
  MEDICATION: "drug_name",
  THERAPY: "treatment_item",
  EXAMINATION: "item_name",
  LAB_TEST: "item_name",
};

export function formatOrderDetailValue(value: unknown): string {
  if (value === null || value === undefined) return "-";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

/** 明细值的展示文本；`undefined` / `null` / 空字符串都视为「没有值」 */
function detailText(value: unknown): string {
  if (value === null || value === undefined || value === "") return "";
  return formatOrderDetailValue(value);
}

/**
 * 由医嘱类型与结构化明细推导「药品 / 项目」标签：
 * - MEDICATION：`<药名> <剂量> <单位> <途径>`（空值跳过）；药名优先 `drug_name`，
 *   缺失时退回 `material_name`（025 绑定目录药品之前的历史医嘱）；
 * - 其它类型：只显示主明细字段；
 * - 没有可用明细（含空对象）时返回 null，由调用方决定显示 `-` 还是隐藏整行。
 */
export function formatOrderItemLabel(
  orderType: string | null | undefined,
  orderDetails: Record<string, unknown> | null | undefined,
): string | null {
  if (!orderDetails) return null;
  const type = orderType ?? "";
  const primaryKey = ORDER_PRIMARY_DETAIL_KEY[type];

  let primary = primaryKey ? detailText(orderDetails[primaryKey]) : "";
  if (primary === "" && type === "MEDICATION") primary = detailText(orderDetails.material_name);
  if (primary === "") return null;

  const parts = [primary];
  if (type === "MEDICATION") {
    for (const key of ["dose", "unit", "route"]) {
      const text = detailText(orderDetails[key]);
      if (text !== "") parts.push(text);
    }
  }
  return parts.join(" ");
}
