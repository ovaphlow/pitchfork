<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Chapter,
    ChapterProgress,
    ContentBlock,
    ProgressStatus,
    ProgressSummary,
  } from "../../lib/merit-client";
  import {
    completeAssignmentProgress,
    getAssignmentProgress,
    listCourseChapters,
    reportChapterProgress,
  } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 章节一次拉取上限 */
  const CHAPTER_LIST_LIMIT = 200;

  /** 演示学员身份缺省值：与任务列表页口径统一（employee_id 查询参数，缺省 u-001） */
  const DEFAULT_EMPLOYEE_ID = "u-001";

  /** 互动问答块作答状态：key = `${chapterId}#${blockIndex}` */
  let quizAnswers = $state<Record<string, string>>({});

  let assignmentId = $state("");
  let courseId = $state("");
  let employeeId = $state(DEFAULT_EMPLOYEE_ID);

  let chapters = $state<Chapter[]>([]);
  let summary = $state<ProgressSummary | null>(null);
  let loading = $state(false);
  let errorMessage = $state("");
  /** 章节完成动作的错误（与页面级加载错误分开展示） */
  let actionError = $state("");
  let actingChapterId = $state("");

  function formatDateTime(value: string | null): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function statusBadgeClass(status: ProgressStatus): string {
    return status === "已完成"
      ? "rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300"
      : "rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs text-amber-300";
  }

  /** 总进度条宽度（0-100%） */
  function totalPercent(): number {
    if (!summary || summary.total_chapters <= 0) return 0;
    return Math.round((summary.completed_chapters / summary.total_chapters) * 100);
  }

  /** 汇总中该章节的进度行（未上报章节为零值行） */
  function chapterRow(chapter: Chapter): ChapterProgress | undefined {
    return summary?.chapters.find((entry) => entry.chapter_id === chapter.id);
  }

  /** 互动问答块选项（块字段为自由扩展 JSON，防御性取值） */
  function blockOptions(block: ContentBlock): string[] {
    return Array.isArray(block.options)
      ? block.options.filter((option): option is string => typeof option === "string")
      : [];
  }

  /** 互动问答块即时反馈：未作答返回 null，已作答返回选中项与是否正确 */
  function quizFeedback(
    key: string,
    block: ContentBlock,
  ): { selected: string; correct: boolean } | null {
    const selected = quizAnswers[key];
    if (selected === undefined) return null;
    const answer = typeof block.answer === "string" ? block.answer : "";
    return { selected, correct: selected === answer };
  }

  async function load(): Promise<void> {
    if (!assignmentId || !courseId) {
      errorMessage = "缺少必要参数：请从「我的培训任务」进入课程学习（需要 assignment_id 与 course_id）";
      return;
    }
    loading = true;
    errorMessage = "";
    try {
      // 章节（含 blocks/quiz_config）与进度汇总并行加载
      const [chapterPage, summaryResult] = await Promise.all([
        listCourseChapters(courseId, { limit: CHAPTER_LIST_LIMIT }),
        getAssignmentProgress(assignmentId, employeeId),
      ]);
      chapters = [...chapterPage.records].sort((a, b) => a.sort_order - b.sort_order);
      summary = summaryResult;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      chapters = [];
      summary = null;
    } finally {
      loading = false;
    }
  }

  /**
   * 标记本章完成：先上报单章进度（progress_percent=100），再拉取最新汇总；
   * 全部章节完成后触发 complete 动作（后端幂等），页面按返回的 summary
   * 更新状态（学习中→已完成）与已完成章节数。
   */
  async function markChapterComplete(chapter: Chapter): Promise<void> {
    if (!assignmentId || !employeeId) return;
    actionError = "";
    actingChapterId = chapter.id;
    try {
      await reportChapterProgress(assignmentId, employeeId, chapter.id, {
        progress_percent: 100,
        detail: { source: "course-learning-page", chapter_title: chapter.title },
      });
      const fresh = await getAssignmentProgress(assignmentId, employeeId);
      if (fresh.total_chapters > 0 && fresh.completed_chapters === fresh.total_chapters) {
        summary = await completeAssignmentProgress(assignmentId, employeeId);
      } else {
        summary = fresh;
      }
    } catch (err) {
      actionError = `进度上报失败：${err instanceof Error ? err.message : String(err)}`;
    } finally {
      actingChapterId = "";
    }
  }

  /** htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 绑定调用，数据仍由客户端方法拉取 */
  function refresh(): void {
    void load();
  }

  onMount(() => {
    // 身份口径：employee_id 查询参数，缺省演示学员 u-001
    const params = new URLSearchParams(window.location.search);
    assignmentId = params.get("assignment_id") ?? "";
    courseId = params.get("course_id") ?? "";
    const fromQuery = params.get("employee_id");
    if (fromQuery) employeeId = fromQuery;
    const w = window as Window & { __meritReloadLearning?: () => void };
    w.__meritReloadLearning = refresh;
    void load();
    return () => {
      delete w.__meritReloadLearning;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <a href={`/my-training/?employee_id=${encodeURIComponent(employeeId)}`} class="transition hover:text-fg">
        我的培训任务
      </a>
      <span class="mx-1">/</span>
      <span>课程学习</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">课程学习</h1>
  </header>

  <!-- htmx 交互：沿用骨架的服务探测模式（hx-get + hx-trigger + hx-swap） -->
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

  {#if actionError}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {actionError}
    </div>
  {/if}

  <!-- 进度状态总览（字段来自 progress summary） -->
  {#if summary}
    <section class="rounded-xl border border-border bg-surface p-4">
      <div class="flex flex-wrap items-center gap-x-8 gap-y-3">
        <div>
          <div class="text-xs text-fg-dimmed">课程</div>
          <div class="mt-0.5 text-lg font-semibold text-fg-emphasis">
            {summary.course_title || courseId}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">学习状态</div>
          <div class="mt-1">
            <span class={statusBadgeClass(summary.status)}>{summary.status}</span>
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">章节进度</div>
          <div class="mt-0.5 text-sm text-fg-muted">
            {summary.completed_chapters} / {summary.total_chapters} 章已完成
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">指派编号</div>
          <div class="mt-0.5 font-mono text-xs text-fg-muted">{summary.assignment_id}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">学员</div>
          <div class="mt-0.5 font-mono text-xs text-fg-muted">{summary.employee_id}</div>
        </div>
        <div class="ml-auto flex items-center gap-2">
          <a
            href={`/exam/?employee_id=${encodeURIComponent(employeeId)}`}
            class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
          >
            在线考核
          </a>
          <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经客户端方法拉取） -->
          <button
            type="button"
            hx-on:click="window.__meritReloadLearning && window.__meritReloadLearning()"
            class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
          >
            刷新
          </button>
        </div>
      </div>
      <div class="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-surface-alt">
        <div
          class="h-full rounded-full bg-accent transition-all"
          style="width: {totalPercent()}%"
        ></div>
      </div>
    </section>
  {/if}

  <!-- 章节列表与内容块渲染 -->
  {#if loading}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      加载中…
    </div>
  {:else if chapters.length === 0}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      该课程暂无章节（空态）
    </div>
  {:else}
    <div class="space-y-6">
      {#each chapters as chapter, chapterIndex (chapter.id)}
        {@const row = chapterRow(chapter)}
        <section class="overflow-hidden rounded-xl border border-border bg-surface">
          <header class="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-border px-4 py-3">
            <span class="text-xs text-fg-dimmed">第 {chapterIndex + 1} 章</span>
            <h2 class="text-base font-semibold text-fg-emphasis">{chapter.title}</h2>
            {#if row}
              <span class={statusBadgeClass(row.status)}>{row.status}</span>
              <span class="text-xs text-fg-dimmed">进度 {row.progress_percent}%</span>
            {/if}
          </header>

          <div class="space-y-3 p-4">
            {#if chapter.blocks.length === 0}
              <p class="text-sm text-fg-dimmed">本章暂无内容块</p>
            {:else}
              {#each chapter.blocks as block, blockIndex (blockIndex)}
                <!-- 视频块：渲染可播放地址 -->
                {#if block.type === "视频"}
                  <div class="rounded-lg border border-border/70 bg-surface-alt p-4">
                    {#if typeof block.title === "string" && block.title}
                      <div class="text-sm font-medium text-fg-emphasis">{block.title}</div>
                    {/if}
                    {#if typeof block.url === "string" && block.url}
                      <video
                        controls
                        preload="metadata"
                        src={block.url}
                        class="mt-3 w-full rounded-md bg-black"
                      ></video>
                    {:else}
                      <p class="mt-1 text-xs text-fg-dimmed">视频块缺少可播放地址</p>
                    {/if}
                  </div>
                {:else if block.type === "图文"}
                  <!-- 图文块：渲染文本/图片 -->
                  <div class="rounded-lg border border-border/70 bg-surface-alt p-4">
                    {#if typeof block.content === "string" && block.content}
                      <div class="whitespace-pre-wrap text-sm leading-relaxed text-fg">
                        {block.content}
                      </div>
                    {:else}
                      <p class="text-xs text-fg-dimmed">图文块暂无内容</p>
                    {/if}
                    {#if typeof block.image === "string" && block.image}
                      <img
                        src={block.image}
                        alt="图文配图"
                        loading="lazy"
                        class="mt-3 max-h-96 rounded-md border border-border object-contain"
                      />
                    {/if}
                  </div>
                {:else if block.type === "互动问答"}
                  <!-- 互动问答块：可作答并展示即时反馈 -->
                  {@const key = `${chapter.id}#${blockIndex}`}
                  {@const options = blockOptions(block)}
                  {@const feedback = quizFeedback(key, block)}
                  <div class="rounded-lg border border-border/70 bg-surface-alt p-4">
                    <div class="text-sm font-medium text-fg-emphasis">
                      互动问答：
                      {typeof block.question === "string" && block.question
                        ? block.question
                        : "（无题目内容）"}
                    </div>
                    {#if options.length > 0}
                      <div class="mt-3 space-y-2">
                        {#each options as option (option)}
                          <label
                            class="flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 text-sm text-fg transition hover:border-accent/60"
                          >
                            <input
                              type="radio"
                              name={key}
                              value={option}
                              checked={feedback?.selected === option}
                              onchange={() => {
                                quizAnswers = { ...quizAnswers, [key]: option };
                              }}
                            />
                            <span>{option}</span>
                          </label>
                        {/each}
                      </div>
                    {:else}
                      <input
                        type="text"
                        value={quizAnswers[key] ?? ""}
                        placeholder="输入你的答案"
                        oninput={(event) => {
                          quizAnswers = { ...quizAnswers, [key]: event.currentTarget.value };
                        }}
                        class="mt-3 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                      />
                    {/if}
                    {#if feedback}
                      <div
                        class={
                          feedback.correct
                            ? "mt-3 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300"
                            : "mt-3 rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300"
                        }
                      >
                        {#if feedback.correct}
                          回答正确
                        {:else}
                          回答错误
                          {#if typeof block.answer === "string" && block.answer}
                            ，正确答案：{block.answer}
                          {/if}
                        {/if}
                        {#if typeof block.explanation === "string" && block.explanation}
                          <div class="mt-1 text-xs text-fg-muted">{block.explanation}</div>
                        {/if}
                      </div>
                    {/if}
                  </div>
                {:else}
                  <!-- 未知块类型兜底：展示原始内容，不崩溃 -->
                  <div class="rounded-lg border border-dashed border-border/70 bg-surface-alt p-4">
                    <p class="text-xs text-fg-dimmed">
                      未知内容块类型：{String(block.type)}（已跳过渲染）
                    </p>
                    <pre class="mt-2 overflow-x-auto text-xs text-fg-dimmed">{JSON.stringify(block, null, 2)}</pre>
                  </div>
                {/if}
              {/each}
            {/if}
          </div>

          <!-- 章节完成动作：进度上报 + 全部完成后 complete -->
          <footer class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
            <div class="text-xs text-fg-dimmed">
              {#if row?.status === "已完成"}
                已于 {formatDateTime(row.completed_at)} 完成本章学习
              {:else}
                学习本章后点击「标记本章完成」上报学习进度
              {/if}
            </div>
            <button
              type="button"
              onclick={() => void markChapterComplete(chapter)}
              disabled={row?.status === "已完成" || actingChapterId === chapter.id}
              class={
                row?.status === "已完成"
                  ? "cursor-not-allowed rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-300"
                  : "rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
              }
            >
              {actingChapterId === chapter.id
                ? "上报中…"
                : row?.status === "已完成"
                  ? "已完成"
                  : "标记本章完成"}
            </button>
          </footer>
        </section>
      {/each}
    </div>
  {/if}
</div>
