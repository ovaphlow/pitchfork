interface SidebarProps {
  currentPath: string;
}

interface GroupItem {
  type: "group";
  label: string;
  children: { label: string; path: string; icon: string }[];
}

interface LinkItem {
  type: "link";
  label: string;
  path: string;
  icon: string;
}

interface DividerItem {
  type: "divider";
}

type Item = GroupItem | LinkItem | DividerItem;

const items: Item[] = [
  { type: "group", label: "临床业务", children: [
    { label: "门诊挂号", path: "/dashboard/registration", icon: "📋" },
    { label: "门诊收费", path: "/dashboard/billing", icon: "💰" },
    { label: "住院管理", path: "/dashboard/inpatient", icon: "🏥" },
    { label: "药房管理", path: "/dashboard/pharmacy", icon: "💊" },
  ]},
  { type: "group", label: "养老业务", children: [
    { label: "长者档案", path: "/dashboard/elders", icon: "👴" },
    { label: "入住管理", path: "/dashboard/admission", icon: "🏠" },
    { label: "健康监测", path: "/dashboard/health-monitor", icon: "❤️" },
    { label: "护理管理", path: "/dashboard/nursing", icon: "🩺" },
    { label: "体检管理", path: "/dashboard/checkup", icon: "🩻" },
    { label: "随访管理", path: "/dashboard/followup", icon: "📞" },
    { label: "餐饮管理", path: "/dashboard/dining", icon: "🍱" },
    { label: "活动管理", path: "/dashboard/activities", icon: "🎯" },
  ]},
  { type: "divider" },
  { type: "group", label: "系统设置", children: [
    { label: "用户", path: "/users", icon: "👤" },
    { label: "部门", path: "/dashboard/departments", icon: "🏢" },
    { label: "角色", path: "/dashboard/roles", icon: "🔐" },
  ]},
];

export default function Sidebar({ currentPath }: SidebarProps) {
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
        {items.map((item, i) => {
          if (item.type === "divider") {
            return <div key={i} className="border-t border-border my-1" />;
          }
          if (item.type === "group") {
            return (
              <div key={i} className="flex flex-col gap-0.5">
                <span className="px-3 py-2 text-xs font-semibold text-fg-dimmed uppercase tracking-wider">
                  {item.label}
                </span>
                {item.children.map((child) => {
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
                      <span>{child.label}</span>
                    </a>
                  );
                })}
              </div>
            );
          }
          const active = currentPath === item.path;
          return (
            <a
              key={item.path}
              href={item.path}
              className={`flex items-center gap-2.5 px-3 py-2 rounded-md text-sm transition-all duration-150 ${
                active
                  ? "bg-accent/10 text-accent font-medium"
                  : "text-fg-muted hover:bg-surface-alt hover:text-fg"
              }`}
            >
              <span className="text-base">{item.icon}</span>
              <span>{item.label}</span>
            </a>
          );
        })}
      </nav>
    </aside>
  );
}
