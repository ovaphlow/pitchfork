<script lang="ts">
  import { onMount } from "svelte";
  import type { ExamRecord, Paper } from "../../lib/merit-client";
  import { listExamRecords, listPapers } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 试卷下拉一次拉取上限 */
  const PAPER_SELECT_LIMIT = 200;

  /** 记录列表每页条数（limit 分页参数） */
  const PAGE_SIZE = 10;

  let papers = $state<Paper[]>([]);
  let records = $state<ExamRecord[]>([]);
  let total = $state(0);
  let loading = $state(true);
  let errorMessage = $state("");

  // 筛选条件：空串表示「全部」
  let filterEmployeeId = $state("");
  let filterPaperId = $state("");

  // 分页：1 起始页码，limit/offset 由 page 与 pageSize 派生
  let page = $state(1);
  const totalPages = $derived(total === 0 ? 1 : Math.ceil(total / PAGE_SIZE));

  const paperMap = $derived(new Map(papers.map((paper) => [paper.id, paper])));

  function paperTitle(id: string): string {
    return paperMap.get(id)?.title || id;
  }

  function formatDateTime(value: string | null): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function passedLabel(record: ExamRecord): string {
    if (record.passed === true) return "通过";
    if (record.passed === false) return "未通过";
    return "待交卷";
  }

  function passedBadgeClass(record: ExamRecord): string {
    if (record.passed === true)
      return "rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300";
    if (record.passed === false)
      return "rounded-full border border-red-500/40 bg-red-500/10 px-2 py-0.5 text-xs text-red-300";
    return "rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2 py-0.5 text-xs text-zinc-300";
  }

  async function load(): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      const params: {
        employee_id?: string;
        paper_id?: string;
        limit: number;
        offset: number;
      } = {
        limit: PAGE_SIZE,
        offset: (page - 1) * PAGE_SIZE,
      };
      if (filterEmployeeId.trim()) params.employee_id = filterEmployeeId.trim();
      if (filterPaperId !== "") params.paper_id = filterPaperId;
      const result = await listExamRecords(params);
      records = result.records;
      total = result.meta.total;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      records = [];
      total = 0;
    } finally {
      loading = false;
    }
  }

  function applyFilters(): void {
    page = 1;
    void load();
  }

  function resetFilters(): void {
    filterEmployeeId = "";
    filterPaperId = "";
    page = 1;
    void load();
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 listExamRecords 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    void load();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadExamRecords?: () => void };
    w.__meritReloadExamRecords = refresh;
    void listPapers({ limit: PAPER_SELECT_LIMIT })
      .then((result) => {
        papers = result.records;
      })
      .catch((err) => {
        errorMessage = `试卷列表加载失败：${err instanceof Error ? err.message : String(err)}`;
      });
    void load();
    return () => {
      delete w.__meritReloadExamRecords;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <span>考核记录列表</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">考核记录列表</h1>
    <p class="mt-1 text-sm text-fg-muted">
      按员工 / 试卷筛选在线考核记录，展示成绩与通过状态；数据经 @pitchfork/shared/merit 客户端
      listExamRecords 拉取（limit/offset 分页）
    </p>
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

  <!-- 筛选工具栏 -->
  <section class="rounded-xl border border-border bg-surface p-4">
    <div class="flex flex-wrap items-end gap-3">
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        员工 ID
        <input
          type="text"
          bind:value={filterEmployeeId}
          placeholder="留空表示全部员工"
          class="w-72 rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        />
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        试卷
        <select
          bind:value={filterPaperId}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部试卷</option>
          {#each papers as paper (paper.id)}
            <option value={paper.id}>{paper.title}</option>
          {/each}
        </select>
      </label>
      <button
        type="button"
        onclick={applyFilters}
        class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
      >
        查询
      </button>
      <button
        type="button"
        onclick={resetFilters}
        class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        重置
      </button>
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经 listExamRecords 拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadExamRecords && window.__meritReloadExamRecords()"
        class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        刷新
      </button>
    </div>
  </section>

  {#if errorMessage}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {errorMessage}
    </div>
  {/if}

  <!-- 考核记录列表 -->
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="border-b border-border text-xs text-fg-dimmed">
            <th class="px-4 py-3 font-medium">员工 ID</th>
            <th class="px-4 py-3 font-medium">试卷</th>
            <th class="px-4 py-3 font-medium">开始时间</th>
            <th class="px-4 py-3 font-medium">结束时间</th>
            <th class="px-4 py-3 font-medium">成绩</th>
            <th class="px-4 py-3 font-medium">通过状态</th>
          </tr>
        </thead>
        <tbody>
          {#if loading}
            <tr>
              <td colspan="6" class="px-4 py-10 text-center text-fg-dimmed">加载中…</td>
            </tr>
          {:else if records.length === 0}
            <tr>
              <td colspan="6" class="px-4 py-10 text-center text-fg-dimmed">
                暂无考核记录{total === 0 ? "" : "（当前筛选条件下无结果）"}
              </td>
            </tr>
          {:else}
            {#each records as record (record.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 font-medium text-fg-emphasis">{record.employee_id}</td>
                <td class="px-4 py-3 text-fg-muted">{paperTitle(record.paper_id)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(record.start_time)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(record.end_time)}</td>
                <td class="px-4 py-3 tabular-nums text-fg-muted">
                  {record.score === null ? "—" : `${record.score} 分`}
                </td>
                <td class="px-4 py-3">
                  <span class={passedBadgeClass(record)}>{passedLabel(record)}</span>
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
