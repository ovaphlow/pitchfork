import { useState, useEffect } from "react";

interface SidebarProps {
  currentPath: string;
}

type Domain = "医疗" | "养老" | "儿保";

interface MenuChild {
  label: string;
  domainLabels?: Partial<Record<Domain, string>>;
  path: string;
  icon: string;
  domains: Domain[];
}

interface GroupItem {
  type: "group";
  label: string;
  domainLabels?: Partial<Record<Domain, string>>;
  children: MenuChild[];
}

type Item = GroupItem;

const STORAGE_KEY = "aceso-domain";

const items: Item[] = [
  {
    type: "group", label: "居民管理", domainLabels: { 养老: "长者管理", 儿保: "儿童管理" }, children: [
      { label: "居民档案", domainLabels: { 养老: "长者档案", 儿保: "儿童健康档案" }, path: "/dashboard/elders", icon: "👤", domains: ["医疗", "养老", "儿保"] },
      { label: "挂号登记",   path: "/dashboard/registration", icon: "📋", domains: ["医疗", "儿保"] },
      { label: "入院管理", domainLabels: { 养老: "入住管理" }, path: "/dashboard/admission", icon: "🏠", domains: ["医疗", "养老"] },
      { label: "随访管理", domainLabels: { 儿保: "儿童保健随访" }, path: "/dashboard/followup", icon: "📞", domains: ["医疗", "养老", "儿保"] },
    ],
  },
  {
    type: "group", label: "诊疗护理", domainLabels: { 养老: "照护服务", 儿保: "儿童保健" }, children: [
      { label: "住院护理", domainLabels: { 养老: "照护管理" }, path: "/dashboard/inpatient", icon: "🏥", domains: ["医疗", "养老"] },
      { label: "医生诊疗", path: "/dashboard/orders", icon: "📝", domains: ["养老"] },
      { label: "医嘱核对", path: "/dashboard/orders-check", icon: "✅", domains: ["养老"] },
      { label: "体检管理",   path: "/dashboard/checkup",      icon: "🩻", domains: ["医疗", "养老", "儿保"] },
      { label: "药房管理",   path: "/dashboard/pharmacy",     icon: "💊", domains: ["医疗", "养老"] },
      { label: "库存计量",   path: "/dashboard/inventory",    icon: "📦", domains: ["医疗", "养老"] },
    ],
  },
  {
    type: "group", label: "健康服务", children: [
      { label: "健康监测",   path: "/dashboard/health-monitor", icon: "❤️", domains: ["养老", "儿保"] },
      { label: "膳食营养",   path: "/dashboard/dining",        icon: "🍱", domains: ["养老"] },
      { label: "康复活动",   path: "/dashboard/activities",    icon: "🎯", domains: ["养老"] },
    ],
  },
  {
    type: "group", label: "财务收费", children: [
      { label: "门诊收费",   path: "/dashboard/billing",       icon: "💰", domains: ["医疗"] },
    ],
  },
  {
    type: "group", label: "系统设置", children: [
      { label: "用户",     path: "/users",                  icon: "👥", domains: ["医疗", "养老", "儿保"] },
      { label: "部门",     path: "/dashboard/departments",  icon: "🏢", domains: ["医疗", "养老", "儿保"] },
      { label: "仓库",     path: "/dashboard/warehouses",   icon: "📦", domains: ["医疗", "养老", "儿保"] },
      { label: "角色",     path: "/dashboard/roles",        icon: "🔐", domains: ["医疗", "养老", "儿保"] },
    ],
  },
];

function displayLabel(label: string, domainLabels: Partial<Record<Domain, string>> | undefined, domain: Domain): string {
  return domainLabels?.[domain] ?? label;
}

function readDomain(): Domain {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "医疗" || stored === "养老" || stored === "儿保") return stored;
  } catch { /* SSR */ }
  return "医疗";
}

export default function Sidebar({ currentPath }: SidebarProps) {
  const [domain, setDomain] = useState<Domain>(readDomain);

  useEffect(() => {
    const handler = () => setDomain(readDomain());
    window.addEventListener("storage", handler);
    window.addEventListener("aceso-domain-change", handler);
    return () => {
      window.removeEventListener("storage", handler);
      window.removeEventListener("aceso-domain-change", handler);
    };
  }, []);

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
        {items.map((group, gi) => {
          const visibleChildren = group.children.filter((c) => c.domains.includes(domain));
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
