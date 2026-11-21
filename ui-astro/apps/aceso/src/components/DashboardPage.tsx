import { ENTITY_LABELS } from "../lib/labels";

/**
 * 养老控制台（056：域切换器已移除）。
 *
 * 056 之前这里有一个「养老 / 医疗 / 儿保」切换器，改动的是
 * `localStorage["aceso-domain"]` 并广播事件让菜单与文案跟随。产品收窄为养老后
 * 不再存在第二、第三个域，切换器随之删除；文案统一取 `ENTITY_LABELS`。
 */
export default function DashboardPage() {
  return (
    <div className="space-y-6">
      {/* 欢迎 */}
      <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-fg-emphasis">欢迎回来</h1>
          <p className="text-sm text-fg-muted mt-1">养老照护平台</p>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-accent/10 flex items-center justify-center">
              <svg className="w-5 h-5 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
                <line x1="16" y1="13" x2="8" y2="13"/>
                <line x1="16" y1="17" x2="8" y2="17"/>
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">
                {/* 与菜单/页面同源取词（居民 / 长者 / 儿童），不再各写一份三元表达式 */}
                {`${ENTITY_LABELS.person}健康档案`}
              </p>
              <p className="text-xl font-semibold text-fg-emphasis">—</p>
            </div>
          </div>
        </div>

        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-warning-bg flex items-center justify-center">
              <svg className="w-5 h-5 text-warning" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">
                老年人随访管理
              </p>
              <p className="text-xl font-semibold text-fg-emphasis">—</p>
            </div>
          </div>
        </div>

        <div className="rounded-lg border border-border bg-surface p-5">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-info-bg flex items-center justify-center">
              <svg className="w-5 h-5 text-info" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10"/>
                <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
                <line x1="9" y1="9" x2="9.01" y2="9"/>
                <line x1="15" y1="9" x2="15.01" y2="9"/>
              </svg>
            </div>
            <div>
              <p className="text-xs text-fg-dimmed uppercase tracking-wider">
                健康监测
              </p>
              <p className="text-xl font-semibold text-fg-emphasis">—</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
