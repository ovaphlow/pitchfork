import { filterVisibleMenuEntries, useSubjectPermissions } from "../lib/permissions";
import { ENTITY_LABELS } from "../lib/labels";

/**
 * 后台左侧导航（056：养老单产品，菜单静态无域过滤）。
 *
 * 056 之前菜单项带 `domains: Domain[]` 与 `domainLabels`，靠运行期域切换过滤与改名；
 * 收窄为养老单产品后两者都不存在了：菜单就是这一棵树，标题取 `ENTITY_LABELS`，
 * 不再有「同一路径在不同域下叫法不同」的分叉。
 *
 * 未来儿保以 `/childcare/*` 回归时，应作为独立的一级路径与独立菜单树加入，
 * 不要在本文件重新引入「按域过滤」的中间层。
 */

interface SidebarProps {
  currentPath: string;
}

export interface MenuChild {
  label: string;
  path: string;
  icon: string;
  /**
   * 访问该入口所需的权限码（**任一命中即可见**）。
   *
   * 缺省或空数组 = 不设前端门槛；权限未知（读取中/读取失败）时受限入口不显示；
   * **平台管理员**（`platform_admin === true`）后端短路放行，前端同口径全量可见。
   * **前端隐藏不替代 403**——后端才是唯一强制点，见 `../lib/permissions.ts`。
   */
  requiredPermissions?: string[];
}

export interface GroupItem {
  type: "group";
  label: string;
  children: MenuChild[];
}

export type Item = GroupItem;

export const menuItems: Item[] = [
  {
    type: "group",
    label: "长者管理",
    children: [
      { label: ENTITY_LABELS.archive, path: "/eldercare/elders", icon: "👤" },
      { label: "入住管理", path: "/eldercare/admission", icon: "🏠" },
      { label: "随访管理", path: "/eldercare/followup", icon: "📞" },
    ],
  },
  {
    type: "group",
    label: "照护服务",
    children: [
      { label: "照护管理", path: "/eldercare/inpatient", icon: "🏥" },
      { label: "医生诊疗", path: "/eldercare/orders", icon: "📝" },
      { label: "医嘱核对", path: "/eldercare/orders-check", icon: "✅" },
      { label: "药房管理", path: "/eldercare/pharmacy", icon: "💊" },
      { label: "库存计量", path: "/eldercare/inventory", icon: "📦" },
      { label: "体检管理", path: "/eldercare/checkup", icon: "🩻" },
    ],
  },
  {
    type: "group",
    label: "健康服务",
    children: [
      { label: "健康监测", path: "/eldercare/health-monitor", icon: "❤️" },
      { label: "异常告警", path: "/eldercare/abnormal-alerts", icon: "🔔" },
      { label: "慢病档案", path: "/eldercare/chronic", icon: "🩺" },
      { label: "膳食营养", path: "/eldercare/dining", icon: "🍱" },
      { label: "康复活动", path: "/eldercare/activities", icon: "🎯" },
    ],
  },
  {
    type: "group",
    label: "财务收费",
    children: [
      { label: "养老收费", path: "/eldercare/billing", icon: "💰" },
      { label: "押金管理", path: "/eldercare/deposits", icon: "🏦" },
    ],
  },
  {
    type: "group",
    label: "系统设置",
    children: [
      { label: "用户", path: "/eldercare/users", icon: "👥" },
      { label: "部门", path: "/eldercare/departments", icon: "🏢" },
      { label: "仓库", path: "/eldercare/warehouses", icon: "📦" },
      { label: "物资", path: "/eldercare/materials", icon: "🏷️" },
      { label: "角色", path: "/eldercare/roles", icon: "🔐" },
      { label: "费用项目", path: "/eldercare/fee-items", icon: "🧾" },
      // 床位主数据（030 W8）：与「费用项目」同属系统设置分组
      { label: "床位管理", path: "/eldercare/beds", icon: "🛏️" },
      // 操作审计（只读）：审计查询是全系统唯一受门禁的读路由，受 access:manage
      { label: "操作审计", path: "/eldercare/audit-log", icon: "📜", requiredPermissions: ["access:manage"] },
    ],
  },
];

export default function Sidebar({ currentPath }: SidebarProps) {
  // 本人权限码 + 平台管理员标记（platformd `/crate-api/v1/subject-permissions`）；
  // 仅用于入口显隐。平台管理员后端短路放行，菜单同口径全量可见。
  const { codes: permissionCodes, platformAdmin } = useSubjectPermissions();

  return (
    <aside className="fixed top-0 left-0 z-40 w-[var(--sidebar-w)] h-screen bg-surface border-r border-border overflow-y-auto flex flex-col">
      {/* Branding */}
      <a href="/eldercare" className="flex items-center gap-2.5 px-3 h-[var(--topbar-h)] border-b border-border shrink-0">
        <svg width="24" height="24" viewBox="0 0 32 32" fill="none" className="shrink-0">
          <rect x="2" y="2" width="28" height="28" rx="6" className="fill-accent/10 stroke-accent" strokeWidth="1.5"/>
          <path d="M16 7L9.5 25h4.2l1.2-3.2h6.2l1.2 3.2h4.2L19 7h-3zm-1.3 11.2l2.3-6.2 2.3 6.2h-4.6z" className="fill-accent"/>
        </svg>
        <span className="text-base font-bold tracking-wider text-fg-emphasis">ACESO</span>
      </a>

      <nav className="flex flex-col gap-1 p-3">
        {menuItems.map((group, gi) => {
          const visibleChildren = filterVisibleMenuEntries(
            group.children,
            permissionCodes,
            platformAdmin,
          );
          if (visibleChildren.length === 0) return null;

          return (
            <div key={gi} className="flex flex-col gap-0.5">
              {gi > 0 && <div className="border-t border-border my-1" />}
              <span className="px-3 py-2 text-xs font-semibold text-fg-dimmed uppercase tracking-wider">
                {group.label}
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
                    <span>{child.label}</span>
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
