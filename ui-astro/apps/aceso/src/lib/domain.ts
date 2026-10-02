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
  | "/dashboard/followup"
  | "/dashboard/beds";

export interface PageTitleDomainLabel {
  /** 医疗域基准名：静态 `<title>`、`BreadcrumbTitle` fallback 与内联脚本兜底都用它 */
  base: string;
  /** 非医疗域覆盖名；与 `Sidebar.menuItems` 中同路径条目的 `domainLabels` 同源 */
  labels: Partial<Record<Domain, string>>;
}

/**
 * 档案主体在各域的叫法（菜单 / 浏览器标题 / 页面内容共用同一份）。
 *
 * 背景（2026-10-01 QA）：域切换原先只切了菜单与浏览器标题，页面内容仍写死「长者」，
 * 于是医疗模式下出现「菜单叫居民档案、页面标题与内容叫长者档案」。
 * 任何按域改写的命名都必须从本表取词，组件里不再硬编码。
 */
/**
 * `patients.person_type` 的合法取值，与后端白名单逐字一致。
 *
 * 组件不得再用 `person`（展示名词）去猜 API 值：两者当前同值，但一个用于展示、
 * 一个用于写入请求体，必须各有单一事实来源。
 */
export type PersonType = "居民" | "长者" | "儿童";

export interface DomainEntityLabels {
  /** 主体名词：居民 / 长者 / 儿童 */
  person: string;
  /** 档案名：居民档案 / 长者档案 / 儿童健康档案 */
  archive: string;
  /** 写入 `patients.person_type` 的域值：居民 / 长者 / 儿童 */
  personType: PersonType;
}

export const DOMAIN_ENTITY: Record<Domain, DomainEntityLabels> = {
  医疗: { person: "居民", archive: "居民档案", personType: "居民" },
  养老: { person: "长者", archive: "长者档案", personType: "长者" },
  儿保: { person: "儿童", archive: "儿童健康档案", personType: "儿童" },
};

/**
 * 「入院 / 入住」动作在各域的说法。
 *
 * 医生诊疗、医嘱核对两页已对医疗域开放（041），其空态文案必须随域取词，
 * 否则医疗模式下会出现「办理养老入住」。医疗/儿保用「入院」，养老沿用改前逐字的「养老入住」。
 */
export const DOMAIN_ADMISSION_PHRASE: Record<Domain, string> = {
  医疗: "入院",
  养老: "养老入住",
  儿保: "入院",
};

/**
 * 路径 → 标题。`base` 即 `Sidebar.menuItems` 里该条目的 `label`（医疗基准名），
 * `labels` 即该条目的 `domainLabels`；`Sidebar` 直接引用本表，两处不会分叉。
 */
export const PAGE_TITLE_DOMAIN_LABELS: Record<PageTitlePath, PageTitleDomainLabel> = {
  // 档案名由 DOMAIN_ENTITY 派生：菜单、浏览器标题、页面内容必然同名
  "/dashboard/elders": {
    base: DOMAIN_ENTITY.医疗.archive,
    labels: { 养老: DOMAIN_ENTITY.养老.archive, 儿保: DOMAIN_ENTITY.儿保.archive },
  },
  "/dashboard/admission": { base: "入院管理", labels: { 养老: "入住管理" } },
  "/dashboard/inpatient": { base: "住院护理", labels: { 养老: "照护管理" } },
  "/dashboard/followup": { base: "随访管理", labels: { 儿保: "儿童保健随访" } },
  // 床位主数据（030 W8）：与域无关，三个域共用同一名称，故 labels 为空
  "/dashboard/beds": { base: "床位管理", labels: {} },
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

/**
 * 运行期直接取当前域的词表（`DOMAIN_ENTITY[readDomain()]`）。
 *
 * 专供**依赖数组必须保持稳定**的场景：例如 `useCallback([], ...)` 里的兜底文案。
 * 这类回调不能依赖 `useDomain()` 的返回值，否则切域会改变回调标识，进而重置分页或
 * 重发请求；而兜底文案只在真正报错的那一刻才需要词，直接读一次当前域即可。
 * 组件渲染路径请一律使用 `useDomain()`，以便切换域时自动重渲染。
 */
export function currentEntityLabels(): DomainEntityLabels {
  return DOMAIN_ENTITY[readDomain()];
}
