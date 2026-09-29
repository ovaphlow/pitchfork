/**
 * 产品域（医疗 / 养老 / 儿保）与域相关页面标题的**单一事实来源**。
 *
 * 背景：应用是 `output: "static"` 的静态构建，域只存在于
 * `localStorage["aceso-domain"]`，构建期无法得知用户选择，所以任何与域相关的
 * 文案都必须在运行期解析。首屏 `document.title` 由 `DashboardLayout.astro` 的
 * 同步内联脚本改写，运行期切域由 `BreadcrumbTitle` 改写，导航项由 `Sidebar`
 * 渲染——三处必须产出完全相同的字符串，因此常量与解析函数集中在本文件。
 *
 * 本文件保持纯 TS（不 import React），以便同时被 Astro 模板（layout / pages）
 * 与 React 组件复用。
 */

export type Domain = "医疗" | "养老" | "儿保";

export const DOMAIN_STORAGE_KEY = "aceso-domain";
export const DOMAIN_CHANGE_EVENT = "aceso-domain-change";

/** 需要按域改写首屏标题的页面路径 */
export type PageTitlePath =
  | "/dashboard/elders"
  | "/dashboard/admission"
  | "/dashboard/inpatient"
  | "/dashboard/followup";

export interface PageTitleDomainLabel {
  /** 医疗域基准名：静态 `<title>`、`BreadcrumbTitle` fallback 与内联脚本兜底都用它 */
  base: string;
  /** 非医疗域覆盖名；与 `Sidebar.menuItems` 中同路径条目的 `domainLabels` 同源 */
  labels: Partial<Record<Domain, string>>;
}

/**
 * 路径 → 标题。`base` 即 `Sidebar.menuItems` 里该条目的 `label`（医疗基准名），
 * `labels` 即该条目的 `domainLabels`；`Sidebar` 直接引用本表，两处不会分叉。
 */
export const PAGE_TITLE_DOMAIN_LABELS: Record<PageTitlePath, PageTitleDomainLabel> = {
  "/dashboard/elders": { base: "居民档案", labels: { 养老: "长者档案", 儿保: "儿童健康档案" } },
  "/dashboard/admission": { base: "入院管理", labels: { 养老: "入住管理" } },
  "/dashboard/inpatient": { base: "住院护理", labels: { 养老: "照护管理" } },
  "/dashboard/followup": { base: "随访管理", labels: { 儿保: "儿童保健随访" } },
};

/** 按域取标签；该域没有覆盖名时回退基准名 */
export function displayLabel(
  label: string,
  domainLabels: Partial<Record<Domain, string>> | undefined,
  domain: Domain,
): string {
  return domainLabels?.[domain] ?? label;
}

/** 读当前域；localStorage 不可用或值非法时回退「医疗」（与首屏内联脚本同一口径） */
export function readDomain(): Domain {
  try {
    const stored = localStorage.getItem(DOMAIN_STORAGE_KEY);
    if (stored === "医疗" || stored === "养老" || stored === "儿保") return stored;
  } catch { /* SSR / 隐私模式禁用 storage */ }
  return "医疗";
}
