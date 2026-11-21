import { useEffect } from "react";
import { menuItems } from "./Sidebar";

interface BreadcrumbTitleProps {
  /** 当前路由路径（构建期由 Astro 注入） */
  currentPath: string;
  /** 路径不在导航表中时的回退标题，即页面自己声明的 title */
  fallback: string;
}

/** 去掉结尾斜杠，让 /eldercare/elders 与 /eldercare/elders/ 视为同一路径 */
function normalizePath(value: string): string {
  const trimmed = value.replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}

/** 按路径解析导航标题；路径不在导航表中返回 null，交由调用方回退 */
function resolveMenuLabel(pathname: string): string | null {
  const target = normalizePath(pathname);
  for (const group of menuItems) {
    for (const child of group.children) {
      if (normalizePath(child.path) === target) return child.label;
    }
  }
  return null;
}

/**
 * 顶栏面包屑标题。
 *
 * 056：菜单为静态树（养老单产品），标题直接取菜单同一份 label，
 * 因此菜单、面包屑与浏览器标签页三处必然同名。
 */
export default function BreadcrumbTitle({ currentPath, fallback }: BreadcrumbTitleProps) {
  const label = resolveMenuLabel(currentPath) ?? fallback;

  // 浏览器标签页标题与面包屑保持一致
  useEffect(() => {
    document.title = `${label} — Aceso`;
  }, [label]);

  return <span className="text-sm text-fg font-medium">{label}</span>;
}
