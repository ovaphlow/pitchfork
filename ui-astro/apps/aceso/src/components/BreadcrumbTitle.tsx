import { useEffect } from "react";
import { displayLabel, menuItems, type Domain } from "./Sidebar";
import { useDomain } from "../lib/useDomain";

interface BreadcrumbTitleProps {
  /** 当前路由路径（构建期由 Astro 注入） */
  currentPath: string;
  /** 路径不在导航表中时的回退标题，即页面自己声明的 title */
  fallback: string;
}

/** 去掉结尾斜杠，让 /dashboard/elders 与 /dashboard/elders/ 视为同一路径 */
function normalizePath(value: string): string {
  const trimmed = value.replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}

/** 按路径与域解析导航标题；路径不在导航表中返回 null，交由调用方回退 */
function resolveMenuLabel(pathname: string, domain: Domain): string | null {
  const target = normalizePath(pathname);
  for (const group of menuItems) {
    for (const child of group.children) {
      if (normalizePath(child.path) === target) {
        return displayLabel(child.label, child.domainLabels, domain);
      }
    }
  }
  return null;
}

/**
 * 顶栏面包屑标题。
 *
 * 域只存在于 localStorage，而本应用是静态构建，构建期无法得知域，
 * 因此标题必须按 CSR 渲染。readDomain 在首次渲染时同步执行，
 * 组件挂载即为正确文案，不会先闪一次错误叫法。
 */
export default function BreadcrumbTitle({ currentPath, fallback }: BreadcrumbTitleProps) {
  const domain = useDomain();

  const label = resolveMenuLabel(currentPath, domain) ?? fallback;

  // 浏览器标签页标题与面包屑保持一致
  useEffect(() => {
    document.title = `${label} — Aceso`;
  }, [label]);

  return <span className="text-sm text-fg font-medium">{label}</span>;
}
