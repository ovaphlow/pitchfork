import {
  DOMAIN_CHANGE_EVENT,
  DOMAIN_STORAGE_KEY,
  PAGE_TITLE_DOMAIN_LABELS,
  displayLabel,
  readDomain,
  type Domain,
} from "../lib/domain";
import { useDomain } from "../lib/useDomain";
import { filterVisibleMenuEntries, useSubjectPermissions } from "../lib/permissions";

// 域常量与解析函数已迁到 ../lib/domain.ts：Astro 的 DashboardLayout / pages 需要
// 在首屏内联脚本里复用同一份口径，而 Astro 模板不能 import 本文件（JSX）。
// 这里原样再导出，`BreadcrumbTitle` / `DashboardPage` 的既有 import 保持不变。
export { DOMAIN_CHANGE_EVENT, DOMAIN_STORAGE_KEY, PAGE_TITLE_DOMAIN_LABELS, displayLabel, readDomain };
export type { Domain };

interface SidebarProps {
  currentPath: string;
}

export interface MenuChild {
  label: string;
  domainLabels?: Partial<Record<Domain, string>>;
  path: string;
  icon: string;
  domains: Domain[];
  /**
   * 054 F1：访问该入口所需的权限码（**任一命中即可见**）。
   *
   * 缺省或空数组 = 不设前端门槛（既有菜单项行为不变）；权限未知（读取中/读取失败）
   * 时受限入口不显示；**平台管理员**（`platform_admin === true`）后端短路放行，
   * 前端同口径全量可见。**前端隐藏不替代 403**——后端才是唯一强制点，见
   * `../lib/permissions.ts` 的模块说明。
   */
  requiredPermissions?: string[];
  /** 按路线自定义排序；未配置的项保持数组原有相对顺序 */
  order?: Partial<Record<Domain, number>>;
}

export interface GroupItem {
  type: "group";
  label: string;
  domainLabels?: Partial<Record<Domain, string>>;
  children: MenuChild[];
}

export type Item = GroupItem;

export const menuItems: Item[] = [
  {
    type: "group", label: "居民管理", domainLabels: { 养老: "长者管理", 儿保: "儿童管理" }, children: [
      {
        // 标题口径单一事实来源：与首屏 <title> / BreadcrumbTitle 同源（lib/domain.ts）
        label: PAGE_TITLE_DOMAIN_LABELS["/dashboard/elders"].base,
        domainLabels: PAGE_TITLE_DOMAIN_LABELS["/dashboard/elders"].labels,
        path: "/dashboard/elders", icon: "👤", domains: ["医疗", "养老", "儿保"],
      },
      {
        label: PAGE_TITLE_DOMAIN_LABELS["/dashboard/admission"].base,
        domainLabels: PAGE_TITLE_DOMAIN_LABELS["/dashboard/admission"].labels,
        path: "/dashboard/admission", icon: "🏠", domains: ["医疗", "养老"],
      },
      {
        label: PAGE_TITLE_DOMAIN_LABELS["/dashboard/followup"].base,
        domainLabels: PAGE_TITLE_DOMAIN_LABELS["/dashboard/followup"].labels,
        path: "/dashboard/followup", icon: "📞", domains: ["医疗", "养老", "儿保"],
      },
    ],
  },
  {
    type: "group", label: "诊疗护理", domainLabels: { 养老: "照护服务", 儿保: "儿童保健" }, children: [
      {
        label: PAGE_TITLE_DOMAIN_LABELS["/dashboard/inpatient"].base,
        domainLabels: PAGE_TITLE_DOMAIN_LABELS["/dashboard/inpatient"].labels,
        path: "/dashboard/inpatient", icon: "🏥", domains: ["医疗", "养老"], order: { 养老: 1 },
      },
      // 医生诊疗/医嘱核对是住院诊疗的主体环节，医疗域同样可见（041 Q4）
      { label: "医生诊疗", path: "/dashboard/orders", icon: "📝", domains: ["医疗", "养老"], order: { 养老: 2 } },
      { label: "医嘱核对", path: "/dashboard/orders-check", icon: "✅", domains: ["医疗", "养老"], order: { 养老: 3 } },
      { label: "药房管理",   path: "/dashboard/pharmacy",     icon: "💊", domains: ["医疗", "养老"], order: { 养老: 4 } },
      { label: "库存计量",   path: "/dashboard/inventory",    icon: "📦", domains: ["医疗", "养老"], order: { 养老: 5 } },
      { label: "体检管理",   path: "/dashboard/checkup",      icon: "🩻", domains: ["医疗", "养老", "儿保"], order: { 养老: 6 } },
      // 052 儿保日常功能链：仅儿保域可见（其他域的菜单与行为不变）
      { label: "儿童体检",   path: "/dashboard/child-checkup",    icon: "🩺", domains: ["儿保"] },
      { label: "成长曲线",   path: "/dashboard/child-growth",     icon: "📈", domains: ["儿保"] },
      { label: "预防接种",   path: "/dashboard/child-vaccination", icon: "💉", domains: ["儿保"] },
      { label: "健康指导",   path: "/dashboard/child-guidance",    icon: "📋", domains: ["儿保"] },
      // 053 C2：可打印报告 + JSON/CSV 导出
      { label: "健康报告",   path: "/dashboard/child-report",      icon: "📄", domains: ["儿保"] },
      // 053 A4：发育筛查录入与结果视图
      { label: "发育筛查",   path: "/dashboard/child-screening",   icon: "🧩", domains: ["儿保"] },
    ],
  },
  {
    type: "group", label: "健康服务", children: [
      { label: "健康监测",   path: "/dashboard/health-monitor", icon: "❤️", domains: ["养老", "儿保"] },
      // 052 P4：儿保域的「异常提醒」是新页（儿童口径聚合 + 家长通报台账）；
      // 养老域的「异常告警」仍是成人/养老口径，两页互不替代。
      { label: "异常提醒",   path: "/dashboard/child-alerts",  icon: "🔔", domains: ["儿保"] },
      { label: "异常告警",   path: "/dashboard/abnormal-alerts", icon: "🔔", domains: ["养老"] },
      { label: "慢病档案",   path: "/dashboard/chronic",        icon: "🩺", domains: ["养老"] },
      { label: "膳食营养",   path: "/dashboard/dining",        icon: "🍱", domains: ["养老"] },
      // 054 A6：营养师工作台（儿保域）。写入口（登记/过敏原/特殊膳食/周报生成）
      // 受 dining:manage 显隐控制（F1 机制）；后端仍是唯一强制点，前端隐藏不替代 403。
      { label: "营养师工作台", path: "/dashboard/dietitian-workbench", icon: "🥗", domains: ["儿保"], requiredPermissions: ["dining:manage"] },
      // 054 B5：康复师工作台（儿保域）。写入口（周期/计划/打卡/里程碑登记/手册分发）
      // 受 rehab:execute 显隐控制（F1 机制）；后端仍是唯一强制点，前端隐藏不替代 403。
      { label: "康复师工作台", path: "/dashboard/child-rehab", icon: "🤸", domains: ["儿保"], requiredPermissions: ["rehab:execute"] },
      // 054 D4：药师工作台（儿保域）。写入口（规则库建/改）受 pharmacy:dispense 显隐控制
      // （F1 机制）；用量计算与规则查询为读开放，后端仍是唯一强制点。
      { label: "药师工作台", path: "/dashboard/pharmacist-workbench", icon: "🧪", domains: ["儿保"], requiredPermissions: ["pharmacy:dispense"] },
      // 054 C3：保育员工作台（儿保域）。写入口（活动日志/行为观测/日程/安全巡查）
      // 受 nursing:document 显隐控制（F1 机制）；后端仍是唯一强制点，前端隐藏不替代 403。
      { label: "保育员工作台", path: "/dashboard/childcare-workbench", icon: "🧸", domains: ["儿保"], requiredPermissions: ["nursing:document"] },
      { label: "康复活动",   path: "/dashboard/activities",    icon: "🎯", domains: ["养老"] },
    ],
  },
  {
    type: "group", label: "财务收费", children: [
      { label: "养老收费",   path: "/dashboard/billing",       icon: "💰", domains: ["养老"] },
      { label: "押金管理",   path: "/dashboard/deposits",      icon: "🏦", domains: ["养老"] },
      // 054 E4：收银工作台（儿保域）。写入口（套餐/订阅/结算/收款/发票）受 billing:write
      // 显隐控制（F1 机制）；后端仍是唯一强制点，前端隐藏不替代 403。
      { label: "收银工作台", path: "/dashboard/cashier-workbench", icon: "💴", domains: ["儿保"], requiredPermissions: ["billing:write"] },
    ],
  },
  {
    type: "group", label: "系统设置", children: [
      { label: "用户",     path: "/users",                  icon: "👥", domains: ["医疗", "养老", "儿保"] },
      { label: "部门",     path: "/dashboard/departments",  icon: "🏢", domains: ["医疗", "养老", "儿保"] },
      { label: "仓库",     path: "/dashboard/warehouses",   icon: "📦", domains: ["医疗", "养老", "儿保"] },
      { label: "物资",     path: "/dashboard/materials",    icon: "🏷️", domains: ["医疗", "养老", "儿保"] },
      { label: "角色",     path: "/dashboard/roles",        icon: "🔐", domains: ["医疗", "养老", "儿保"] },
      { label: "费用项目", path: "/dashboard/fee-items",    icon: "🧾", domains: ["养老"] },
      {
        // 床位主数据（030 W8）：与「费用项目」同属系统设置分组
        label: PAGE_TITLE_DOMAIN_LABELS["/dashboard/beds"].base,
        domainLabels: PAGE_TITLE_DOMAIN_LABELS["/dashboard/beds"].labels,
        path: "/dashboard/beds", icon: "🛏️", domains: ["医疗", "养老", "儿保"],
      },
      // 054 F2：基础数据维护（疫苗类型 / 发育量表内容）。写入口受 base_data:manage
      // 显隐控制（F1 机制）；读库优先、库空回落常量，后端仍是唯一强制点。
      { label: "基础数据维护", path: "/dashboard/base-data", icon: "🗂️", domains: ["儿保"], requiredPermissions: ["base_data:manage"] },
      // 054 F3：操作审计（只读）。审计查询是全系统唯一受门禁的读路由，
      // 受 access:manage；页面无任何修改入口。
      { label: "操作审计", path: "/dashboard/audit-log", icon: "📜", domains: ["儿保"], requiredPermissions: ["access:manage"] },
    ],
  },
];

export default function Sidebar({ currentPath }: SidebarProps) {
  // 域订阅口径统一走 useDomain（与页面内容、浏览器标题同源）
  const domain = useDomain();
  // 054 F1：本人权限码 + 平台管理员标记（platformd `/crate-api/v1/subject-permissions`）；
  // 仅用于入口显隐。平台管理员后端短路放行，菜单同口径全量可见。
  const { codes: permissionCodes, platformAdmin } = useSubjectPermissions();

  return (
    <aside className="fixed top-0 left-0 z-40 w-[var(--sidebar-w)] h-screen bg-surface border-r border-border overflow-y-auto flex flex-col">
      {/* Branding */}
      <a href="/dashboard" className="flex items-center gap-2.5 px-3 h-[var(--topbar-h)] border-b border-border shrink-0">
        <svg width="24" height="24" viewBox="0 0 32 32" fill="none" className="shrink-0">
          <rect x="2" y="2" width="28" height="28" rx="6" className="fill-accent/10 stroke-accent" strokeWidth="1.5"/>
          <path d="M16 7L9.5 25h4.2l1.2-3.2h6.2l1.2 3.2h4.2L19 7h-3zm-1.3 11.2l2.3-6.2 2.3 6.2h-4.6z" className="fill-accent"/>
        </svg>
        <span className="text-base font-bold tracking-wider text-fg-emphasis">ACESO</span>
      </a>

      <nav className="flex flex-col gap-1 p-3">
        {menuItems.map((group, gi) => {
          const visibleChildren = filterVisibleMenuEntries(
            group.children.filter((c) => c.domains.includes(domain)),
            permissionCodes,
            platformAdmin,
          ).sort((a, b) => (a.order?.[domain] ?? Number.MAX_SAFE_INTEGER) - (b.order?.[domain] ?? Number.MAX_SAFE_INTEGER));
          if (visibleChildren.length === 0) return null;

          return (
            <div key={gi} className="flex flex-col gap-0.5">
              {gi > 0 && <div className="border-t border-border my-1" />}
              <span className="px-3 py-2 text-xs font-semibold text-fg-dimmed uppercase tracking-wider">
                {displayLabel(group.label, group.domainLabels, domain)}
              </span>
              {visibleChildren.map((child) => {
                const active = currentPath === child.path;
                return (
                  <a
                    key={child.path}
                    href={child.path}
                    className={`flex items-center gap-2.5 px-3 py-2 rounded-md text-sm transition-all duration-150 ${
                      active
                        ? "bg-accent/10 text-accent font-medium"
                        : "text-fg-muted hover:bg-surface-alt hover:text-fg"
                    }`}
                  >
                    <span className="text-base">{child.icon}</span>
                    <span>{displayLabel(child.label, child.domainLabels, domain)}</span>
                  </a>
                );
              })}
            </div>
          );
        })}
      </nav>
    </aside>
  );
}
