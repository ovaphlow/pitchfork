<script lang="ts">
  import { onMount } from "svelte";
  import type { Paper, PaperQuestion } from "../../lib/merit-client";
  import { generatePaper, getPaper } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  interface Props {
    paperId?: string;
  }

  let { paperId = "" }: Props = $props();

  let paper = $state<Paper | null>(null);
  let loading = $state(true);
  let generating = $state(false);
  let errorMessage = $state("");

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function formatStrategy(strategy: Record<string, number> | undefined): string {
    if (!strategy) return "—";
    const entries = Object.entries(strategy).filter(([, count]) => count > 0);
    if (entries.length === 0) return "—";
    return entries.map(([type, count]) => `${type} ${count}`).join(" · ");
  }

  function formatAnswer(question: PaperQuestion): string {
    const answer = question.answer;
    return Array.isArray(answer) ? answer.join("、") : String(answer);
  }

  function formatOptions(question: PaperQuestion): string {
    if (!question.options || question.options.length === 0) return "—";
    return question.options.map((option, index) => `${String.fromCharCode(65 + index)}. ${option}`).join("　");
  }

  // 组卷触发：经 generatePaper 客户端方法调用后端自动组卷，返回结果
  // （含 questions 快照）渲染题目清单。
  async function runGenerate(): Promise<void> {
    if (!paper) return;
    generating = true;
    errorMessage = "";
    try {
      paper = await generatePaper(paper.id);
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
    } finally {
      generating = false;
    }
  }

  async function loadPaper(id: string): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      paper = await getPaper(id);
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      paper = null;
    } finally {
      loading = false;
    }
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 getPaper 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    if (paper) void loadPaper(paper.id);
  }

  onMount(() => {
    const id =
      paperId || new URLSearchParams(window.location.search).get("id") || "";
    if (!id) {
      errorMessage = "缺少试卷 ID（URL 需携带 ?id=…）";
      loading = false;
      return;
    }
    const w = window as Window & { __meritReloadPaperDetail?: () => void };
    w.__meritReloadPaperDetail = refresh;
    void loadPaper(id);
    return () => {
      delete w.__meritReloadPaperDetail;
    };
  });
</script>

<div class="space-y-6">
  <header class="flex flex-wrap items-center justify-between gap-4">
    <div>
      <p class="text-xs text-fg-dimmed">
        <a href="/" class="transition hover:text-fg">首页</a>
        <span class="mx-1">/</span>
        <a href="/papers" class="transition hover:text-fg">试卷管理</a>
        <span class="mx-1">/</span>
        <span>试卷详情与组卷</span>
      </p>
      <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">
        {paper ? paper.title : "试卷详情"}
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        组卷触发经 @pitchfork/shared/merit 客户端的 generatePaper；题目清单为只读快照
      </p>
    </div>
    {#if paper}
      <div class="flex items-center gap-2">
        <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经 getPaper 拉取） -->
        <button
          type="button"
          hx-on:click="window.__meritReloadPaperDetail && window.__meritReloadPaperDetail()"
          class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          刷新
        </button>
        <a
          href={`/papers/edit?id=${encodeURIComponent(paper.id)}`}
          class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          编辑
        </a>
        <button
          type="button"
          onclick={() => void runGenerate()}
          disabled={generating}
          class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {generating ? "组卷中…" : paper.questions.length > 0 ? "重新组卷" : "触发组卷"}
        </button>
      </div>
    {/if}
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

  {#if loading}
    <div class="rounded-lg border border-border bg-surface px-4 py-6 text-center text-sm text-fg-dimmed">
      加载中…
    </div>
  {:else if paper}
    <!-- 试卷信息（服务端管理字段只读回显） -->
    <section class="grid grid-cols-1 gap-3 rounded-xl border border-border bg-surface p-5 text-sm sm:grid-cols-2 lg:grid-cols-5">
      <div>
        <div class="text-xs text-fg-dimmed">时长（分钟）</div>
        <div class="mt-1 text-fg tabular-nums">{paper.duration_minutes}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">及格分</div>
        <div class="mt-1 text-fg tabular-nums">{paper.pass_score}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">组卷策略（只读）</div>
        <div class="mt-1 text-fg">{formatStrategy(paper.generation_strategy)}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">题目数（只读快照）</div>
        <div class="mt-1 text-fg tabular-nums">{paper.questions.length}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">创建人（只读）</div>
        <div class="mt-1 text-fg">{paper.created_by || "—"}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">创建时间（只读）</div>
        <div class="mt-1 text-fg">{formatDateTime(paper.created_at)}</div>
      </div>
      <div>
        <div class="text-xs text-fg-dimmed">更新时间（只读）</div>
        <div class="mt-1 text-fg">{formatDateTime(paper.updated_at)}</div>
      </div>
    </section>

    <!-- 题目清单（组卷结果只读展示，不编辑、不提交） -->
    <section class="overflow-hidden rounded-xl border border-border bg-surface">
      <div class="border-b border-border px-4 py-3 text-sm font-medium text-fg-emphasis">
        题目清单（组卷结果只读）
      </div>
      {#if paper.questions.length === 0}
        <div class="px-4 py-10 text-center text-sm text-fg-dimmed">
          尚未组卷。点击「触发组卷」按组卷策略从题库抽取题目。
        </div>
      {:else}
        <ol class="divide-y divide-border/60">
          {#each paper.questions as question, index (question.id)}
            <li class="px-4 py-4">
              <div class="flex flex-wrap items-center gap-2 text-sm">
                <span class="text-fg-dimmed tabular-nums">{index + 1}.</span>
                <span class="font-medium text-fg-emphasis">{question.content}</span>
                <span
                  class="rounded-full border border-sky-500/40 bg-sky-500/10 px-2 py-0.5 text-xs text-sky-300"
                >
                  {question.type}
                </span>
                <span class="rounded-full border border-border bg-surface-alt px-2 py-0.5 text-xs text-fg-dimmed tabular-nums">
                  难度 {question.difficulty}
                </span>
              </div>
              {#if question.options.length > 0}
                <p class="mt-1.5 text-xs leading-relaxed text-fg-muted">
                  {formatOptions(question)}
                </p>
              {/if}
              <p class="mt-1 text-xs text-fg-dimmed">
                答案：{formatAnswer(question)}
              </p>
            </li>
          {/each}
        </ol>
      {/if}
    </section>
  {/if}
</div>
