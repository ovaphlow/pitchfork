import { useEffect, useState } from "react";
import { DOMAIN_CHANGE_EVENT, readDomain, type Domain } from "./domain";

/**
 * 订阅当前产品域（医疗 / 养老 / 儿保）。
 *
 * 域只存在于 `localStorage["aceso-domain"]`：切换由 `DashboardPage` 写入并派发
 * `aceso-domain-change`，跨标签页由 `storage` 事件同步。任何按域渲染的组件
 * （菜单、浏览器标题、页面内容）都必须走本 hook，才能在同一份口径上随切换一起更新
 * —— 2026-10-01 QA 的「菜单叫居民档案、页面叫长者档案」正是漏了页面内容的订阅。
 */
export function useDomain(): Domain {
  const [domain, setDomain] = useState<Domain>(readDomain);

  useEffect(() => {
    const sync = () => setDomain(readDomain());
    window.addEventListener("storage", sync);
    window.addEventListener(DOMAIN_CHANGE_EVENT, sync);
    return () => {
      window.removeEventListener("storage", sync);
      window.removeEventListener(DOMAIN_CHANGE_EVENT, sync);
    };
  }, []);

  return domain;
}
