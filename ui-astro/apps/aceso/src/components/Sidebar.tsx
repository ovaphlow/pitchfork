import {
  DOMAIN_CHANGE_EVENT,
  DOMAIN_STORAGE_KEY,
  PAGE_TITLE_DOMAIN_LABELS,
  displayLabel,
  readDomain,
  type Domain,
} from "../lib/domain";
import { useDomain } from "../lib/useDomain";

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
      { label: "医生诊疗", path: "/dashboard/orders", icon: "📝", domains: ["养老"], order: { 养老: 2 } },
      { label: "医嘱核对", path: "/dashboard/orders-check", icon: "✅", domains: ["养老"], order: { 养老: 3 } },
      { label: "药房管理",   path: "/dashboard/pharmacy",     icon: "💊", domains: ["医疗", "养老"], order: { 养老: 4 } },
      { label: "库存计量",   path: "/dashboard/inventory",    icon: "📦", domains: ["医疗", "养老"], order: { 养老: 5 } },
      { label: "体检管理",   path: "/dashboard/checkup",      icon: "🩻", domains: ["医疗", "养老", "儿保"], order: { 养老: 6 } },
    ],
  },
  {
    type: "group", label: "健康服务", children: [
      { label: "健康监测",   path: "/dashboard/health-monitor", icon: "❤️", domains: ["养老", "儿保"] },
      { label: "异常告警",   path: "/dashboard/abnormal-alerts", icon: "🔔", domains: ["养老"] },
      { label: "慢病档案",   path: "/dashboard/chronic",        icon: "🩺", domains: ["养老"] },
      { label: "膳食营养",   path: "/dashboard/dining",        icon: "🍱", domains: ["养老"] },
      { label: "康复活动",   path: "/dashboard/activities",    icon: "🎯", domains: ["养老"] },
    ],
  },
  {
    type: "group", label: "财务收费", children: [
      { label: "养老收费",   path: "/dashboard/billing",       icon: "💰", domains: ["养老"] },
      { label: "押金管理",   path: "/dashboard/deposits",      icon: "🏦", domains: ["养老"] },
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
    ],
  },
];

export default function Sidebar({ currentPath }: SidebarProps) {
  // 域订阅口径统一走 useDomain（与页面内容、浏览器标题同源）
  const domain = useDomain();

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
          const visibleChildren = group.children
            .filter((c) => c.domains.includes(domain))
            .sort((a, b) => (a.order?.[domain] ?? Number.MAX_SAFE_INTEGER) - (b.order?.[domain] ?? Number.MAX_SAFE_INTEGER));
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
