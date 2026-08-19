<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Question,
    QuestionListParams,
    QuestionType,
  } from "../../lib/merit-client";
  import { deleteQuestion, listQuestions } from "../../lib/merit-client";
  import {
    QUESTION_DIFFICULTIES,
    QUESTION_PAGE_SIZE,
    QUESTION_TYPES,
  } from "../../lib/question-options";
  import { apiUrl } from "../../lib/api";

  let questions = $state<Question[]>([]);
  let total = $state(0);
  let loading = $state(true);
  let errorMessage = $state("");

  // 筛选条件：空串表示「全部」；tags 为可编辑字符串数组（输入框逗号分隔）
  let filterType = $state<"" | QuestionType>("");
  let filterDifficulty = $state<"" | number>("");
  let filterTags = $state("");

  // 分页：1 起始页码，limit/offset 由 page 与 pageSize 派生
  let page = $state(1);
  const pageSize = QUESTION_PAGE_SIZE;

  const totalPages = $derived(total === 0 ? 1 : Math.ceil(total / pageSize));

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function parseTags(value: string): string[] {
    return value
      .split(/[,，]/)
      .map((tag) => tag.trim())
      .filter((tag) => tag.length > 0);
  }

  function formatAnswer(question: Question): string {
    const answer = question.answer;
    const text = Array.isArray(answer) ? answer.join("、") : String(answer);
    return text.length > 40 ? `${text.slice(0, 40)}…` : text;
  }

  async function load(): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      const params: QuestionListParams = {
        limit: pageSize,
        offset: (page - 1) * pageSize,
      };
      if (filterType !== "") params.type = filterType;
      if (filterDifficulty !== "") params.difficulty = filterDifficulty;
      const tags = parseTags(filterTags);
      if (tags.length > 0) params.tags = tags;
      const result = await listQuestions(params);
      questions = result.records;
      total = result.meta.total;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      questions = [];
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
    filterType = "";
    filterDifficulty = "";
    filterTags = "";
    page = 1;
    void load();
  }

  async function removeQuestion(question: Question): Promise<void> {
    if (!window.confirm(`确定删除题目「${question.content}」？此操作不可恢复。`))
      return;
    try {
      await deleteQuestion(question.id);
      if (questions.length === 1 && page > 1) page -= 1;
      await load();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 listQuestions 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    void load();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadQuestions?: () => void };
    w.__meritReloadQuestions = refresh;
    void load();
    return () => {
      delete w.__meritReloadQuestions;
    };
  });
</script>

<div class="space-y-6">
  <header class="flex flex-wrap items-center justify-between gap-4">
    <div>
      <p class="text-xs text-fg-dimmed">
        <a href="/" class="transition hover:text-fg">首页</a>
        <span class="mx-1">/</span>
        <span>题库管理</span>
      </p>
      <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">题库管理</h1>
      <p class="mt-1 text-sm text-fg-muted">
        按题型/难度/标签筛选（AND），limit/offset 分页；数据经 @pitchfork/shared/merit 客户端读写
      </p>
    </div>
    <div class="flex items-center gap-2">
      <a
        href="/questions/import"
        class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        批量导入
      </a>
      <a
        href="/questions/new"
        class="rounded-lg bg-accent px-4 py-2 font-medium text-white transition hover:opacity-90"
      >
        新建题目
      </a>
    </div>
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
        题型
        <select
          bind:value={filterType}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部</option>
          {#each QUESTION_TYPES as type}
            <option value={type}>{type}</option>
          {/each}
        </select>
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        难度
        <select
          bind:value={filterDifficulty}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部</option>
          {#each QUESTION_DIFFICULTIES as difficulty}
            <option value={difficulty}>{difficulty}</option>
          {/each}
        </select>
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        标签
        <input
          type="text"
          bind:value={filterTags}
          placeholder="多个标签用逗号分隔（AND）"
          class="w-56 rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        />
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
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经 listQuestions 拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadQuestions && window.__meritReloadQuestions()"
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

  <!-- 题目列表 -->
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="border-b border-border text-xs text-fg-dimmed">
            <th class="px-4 py-3 font-medium">题干</th>
            <th class="px-4 py-3 font-medium">题型</th>
            <th class="px-4 py-3 font-medium">难度</th>
            <th class="px-4 py-3 font-medium">标签</th>
            <th class="px-4 py-3 font-medium">答案</th>
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
          {:else if questions.length === 0}
            <tr>
              <td colspan="9" class="px-4 py-10 text-center text-fg-dimmed">
                暂无题目{total === 0 ? "" : "（当前筛选条件下无结果）"}
              </td>
            </tr>
          {:else}
            {#each questions as question (question.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="max-w-md px-4 py-3 font-medium text-fg-emphasis">
                  <span class="line-clamp-2">
                    {question.content}
                  </span>
                </td>
                <td class="px-4 py-3">
                  <span
                    class="rounded-full border border-sky-500/40 bg-sky-500/10 px-2 py-0.5 text-xs text-sky-300"
                  >
                    {question.type}
                  </span>
                </td>
                <td class="px-4 py-3 text-fg-muted">
                  <span class="tabular-nums">{question.difficulty}</span>
                </td>
                <td class="px-4 py-3 text-fg-muted">
                  {#if question.tags.length > 0}
                    <span class="flex flex-wrap gap-1">
                      {#each question.tags as tag}
                        <span
                          class="rounded border border-border bg-surface-alt px-1.5 py-0.5 text-xs text-fg-dimmed"
                        >
                          {tag}
                        </span>
                      {/each}
                    </span>
                  {:else}
                    —
                  {/if}
                </td>
                <td class="max-w-xs px-4 py-3 text-fg-muted">{formatAnswer(question)}</td>
                <td class="px-4 py-3 text-fg-muted">{question.created_by || "—"}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(question.created_at)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(question.updated_at)}</td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-2 text-xs">
                    <a
                      href={`/questions/edit?id=${encodeURIComponent(question.id)}`}
                      class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                    >
                      编辑
                    </a>
                    <button
                      type="button"
                      onclick={() => removeQuestion(question)}
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
