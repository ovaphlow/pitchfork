<script lang="ts">
  import { onMount } from "svelte";
  import type { Paper, PaperListParams } from "../../lib/merit-client";
  import { deletePaper, listPapers } from "../../lib/merit-client";
  import { PAPER_PAGE_SIZE } from "../../lib/paper-options";
  import { apiUrl } from "../../lib/api";

  let papers = $state<Paper[]>([]);
  let total = $state(0);
  let loading = $state(true);
  let errorMessage = $state("");

  // 分页：1 起始页码，limit/offset 由 page 与 pageSize 派生
  let page = $state(1);
  const pageSize = PAPER_PAGE_SIZE;

  const totalPages = $derived(total === 0 ? 1 : Math.ceil(total / pageSize));

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  /** 组卷策略对象（题型 → 数量）渲染为紧凑文本 */
  function formatStrategy(strategy: Record<string, number> | undefined): string {
    if (!strategy) return "—";
    const entries = Object.entries(strategy).filter(([, count]) => count > 0);
    if (entries.length === 0) return "—";
    return entries.map(([type, count]) => `${type} ${count}`).join(" · ");
  }

  async function load(): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      const params: PaperListParams = {
        limit: pageSize,
        offset: (page - 1) * pageSize,
      };
      const result = await listPapers(params);
      papers = result.records;
      total = result.meta.total;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      papers = [];
      total = 0;
    } finally {
      loading = false;
    }
  }

  async function removePaper(paper: Paper): Promise<void> {
    if (!window.confirm(`确定删除试卷「${paper.title}」？此操作不可恢复。`))
      return;
    try {
      await deletePaper(paper.id);
      if (papers.length === 1 && page > 1) page -= 1;
      await load();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 listPapers 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    void load();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadPapers?: () => void };
    w.__meritReloadPapers = refresh;
    void load();
    return () => {
      delete w.__meritReloadPapers;
    };
  });
</script>

<div class="space-y-6">
  <header class="flex flex-wrap items-center justify-between gap-4">
    <div>
      <p class="text-xs text-fg-dimmed">
        <a href="/" class="transition hover:text-fg">首页</a>
        <span class="mx-1">/</span>
        <span>试卷管理</span>
      </p>
      <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">试卷管理</h1>
      <p class="mt-1 text-sm text-fg-muted">
        limit/offset 分页（后端按 created_at DESC 返回）；数据经 @pitchfork/shared/merit 客户端读写
      </p>
    </div>
    <a
      href="/papers/new"
      class="rounded-lg bg-accent px-4 py-2 font-medium text-white transition hover:opacity-90"
    >
      新建试卷
    </a>
  </header>

  <!-- htmx 交互：沿用骨架 index.astro 的服务探测模式（hx-get + hx-trigger + hx-swap） -->
  <section
    hx-get={apiUrl("health/status")}
    hx-trigger="load delay:1s"
    hx-swap="innerHTML"
    class="rounded-lg border border-border bg-surface px-4 py-2 text-xs text-fg-dimmed"
  >
    正在探测后端服务状态…
  </section>

  {#if errorMessage}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {errorMessage}
    </div>
  {/if}

  <!-- 试卷列表 -->
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="border-b border-border text-xs text-fg-dimmed">
            <th class="px-4 py-3 font-medium">标题</th>
            <th class="px-4 py-3 font-medium">时长（分钟）</th>
            <th class="px-4 py-3 font-medium">及格分</th>
            <th class="px-4 py-3 font-medium">组卷策略</th>
            <th class="px-4 py-3 font-medium">题目数</th>
            <th class="px-4 py-3 font-medium">创建人</th>
            <th class="px-4 py-3 font-medium">创建时间</th>
            <th class="px-4 py-3 font-medium">更新时间</th>
            <th class="px-4 py-3 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          {#if loading}
            <tr>
              <td colspan="9" class="px-4 py-10 text-center text-fg-dimmed">加载中…</td>
            </tr>
          {:else if papers.length === 0}
            <tr>
              <td colspan="9" class="px-4 py-10 text-center text-fg-dimmed">
                暂无试卷{total === 0 ? "" : "（当前条件下无结果）"}
              </td>
            </tr>
          {:else}
            {#each papers as paper (paper.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 font-medium text-fg-emphasis">{paper.title}</td>
                <td class="px-4 py-3 text-fg-muted tabular-nums">{paper.duration_minutes}</td>
                <td class="px-4 py-3 text-fg-muted tabular-nums">{paper.pass_score}</td>
                <td class="px-4 py-3 text-fg-muted">{formatStrategy(paper.generation_strategy)}</td>
                <td class="px-4 py-3 text-fg-muted tabular-nums">{paper.questions.length}</td>
                <td class="px-4 py-3 text-fg-muted">{paper.created_by || "—"}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(paper.created_at)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(paper.updated_at)}</td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-2 text-xs">
                    <a
                      href={`/papers/detail?id=${encodeURIComponent(paper.id)}`}
                      class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                    >
                      详情/组卷
                    </a>
                    <a
                      href={`/papers/edit?id=${encodeURIComponent(paper.id)}`}
                      class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                    >
                      编辑
                    </a>
                    <button
                      type="button"
                      onclick={() => removePaper(paper)}
                      class="rounded-md border border-red-500/40 bg-red-500/10 px-2 py-1 text-red-300 transition hover:bg-red-500/20"
                    >
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            {/each}
          {/if}
        </tbody>
      </table>
    </div>
    <div class="flex items-center justify-between border-t border-border px-4 py-3 text-sm text-fg-muted">
      <span>共 {total} 条</span>
      <div class="flex items-center gap-3">
        <button
          type="button"
          onclick={() => {
            if (page > 1) {
              page -= 1;
              void load();
            }
          }}
          disabled={page <= 1}
          class="rounded-md border border-border bg-surface-alt px-3 py-1 text-fg-muted transition enabled:hover:text-fg disabled:cursor-not-allowed disabled:opacity-40"
        >
          上一页
        </button>
        <span class="tabular-nums">第 {page} / {totalPages} 页</span>
        <button
          type="button"
          onclick={() => {
            if (page < totalPages) {
              page += 1;
              void load();
            }
          }}
          disabled={page >= totalPages}
          class="rounded-md border border-border bg-surface-alt px-3 py-1 text-fg-muted transition enabled:hover:text-fg disabled:cursor-not-allowed disabled:opacity-40"
        >
          下一页
        </button>
      </div>
    </div>
  </section>
</div>
