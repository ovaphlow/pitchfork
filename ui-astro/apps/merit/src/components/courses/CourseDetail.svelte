<script lang="ts">
  import { onMount } from "svelte";
  import type { Chapter, Course } from "../../lib/merit-client";
  import {
    deleteChapter,
    deleteCourse,
    getCourse,
    listCourseChapters,
    updateChapter,
  } from "../../lib/merit-client";
  import { CHAPTER_LIST_LIMIT } from "../../lib/merit-options";
  import { apiUrl } from "../../lib/api";
  import ChapterForm from "./ChapterForm.svelte";

  interface Props {
    courseId?: string;
  }

  let { courseId = "" }: Props = $props();

  let resolvedId = $state("");
  let course = $state<Course | null>(null);
  let chapters = $state<Chapter[]>([]);
  let loading = $state(true);
  let errorMessage = $state("");

  // 章节编辑器状态：null = 关闭；create = 新建；edit = 编辑指定章节
  type EditorState = { mode: "create" } | { mode: "edit"; chapter: Chapter } | null;
  let editor = $state<EditorState>(null);

  // 排序工作副本与原始 sort_order（用于「保存排序」diff）
  let workingChapters = $state<Chapter[]>([]);
  let originalOrders = new Map<string, number>();
  let savingOrder = $state(false);
  let orderMessage = $state("");

  const orderDirty = $derived(
    workingChapters.some(
      (chapter, index) => chapter.sort_order !== originalOrders.get(chapter.id),
    ),
  );

  const nextSortOrder = $derived(
    chapters.length === 0
      ? 0
      : Math.max(...chapters.map((chapter) => chapter.sort_order)) + 1,
  );

  function syncWorking(): void {
    workingChapters = [...chapters].sort(
      (a, b) => a.sort_order - b.sort_order,
    );
  }

  function refreshOrders(): void {
    originalOrders = new Map(chapters.map((chapter) => [chapter.id, chapter.sort_order]));
  }

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function metaText(key: string): string {
    const value = course?.metadata?.[key];
    return value === undefined || value === null ? "" : String(value);
  }

  function blockSummary(chapter: Chapter): string {
    if (!chapter.blocks || chapter.blocks.length === 0) return "（无内容块）";
    return chapter.blocks.map((block) => block.type).join("、");
  }

  async function loadChapters(): Promise<void> {
    const result = await listCourseChapters(resolvedId, { limit: CHAPTER_LIST_LIMIT });
    chapters = result.records;
    syncWorking();
    refreshOrders();
  }

  async function loadAll(): Promise<void> {
    try {
      const [courseResult, chapterResult] = await Promise.all([
        getCourse(resolvedId),
        listCourseChapters(resolvedId, { limit: CHAPTER_LIST_LIMIT }),
      ]);
      course = courseResult;
      chapters = chapterResult.records;
      syncWorking();
      refreshOrders();
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
    } finally {
      loading = false;
    }
  }

  // 上移/下移调整展示顺序，并将 sort_order 重排为 0..n-1；
  // 保存时对变更章节逐条 updateChapter（携带更新后 sort_order）
  function moveChapter(index: number, direction: -1 | 1): void {
    const target = index + direction;
    if (target < 0 || target >= workingChapters.length) return;
    const next = workingChapters.map((chapter) => ({ ...chapter }));
    [next[index], next[target]] = [next[target], next[index]];
    workingChapters = next.map((chapter, i) => ({ ...chapter, sort_order: i }));
  }

  async function saveOrder(): Promise<void> {
    const changed = workingChapters.filter(
      (chapter) => chapter.sort_order !== originalOrders.get(chapter.id),
    );
    if (changed.length === 0) return;
    savingOrder = true;
    orderMessage = "";
    try {
      for (const chapter of changed) {
        await updateChapter(chapter.id, {
          title: chapter.title,
          sort_order: chapter.sort_order,
          blocks: chapter.blocks,
          quiz_config: chapter.quiz_config,
        });
      }
      orderMessage = "排序已保存";
      await loadChapters();
    } catch (err) {
      orderMessage = err instanceof Error ? err.message : String(err);
    } finally {
      savingOrder = false;
    }
  }

  async function removeChapter(chapter: Chapter): Promise<void> {
    if (!window.confirm(`确定删除章节「${chapter.title}」？`)) return;
    try {
      await deleteChapter(chapter.id);
      await loadChapters();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  async function removeCourse(): Promise<void> {
    if (!course) return;
    if (
      !window.confirm(
        `确定删除课程「${course.title}」？其全部章节将一并删除，此操作不可恢复。`,
      )
    )
      return;
    try {
      await deleteCourse(course.id);
      window.location.assign("/courses");
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  onMount(() => {
    const id =
      courseId || new URLSearchParams(window.location.search).get("id") || "";
    if (!id) {
      errorMessage = "缺少课程 ID（URL 需携带 ?id=…）";
      loading = false;
      return;
    }
    resolvedId = id;
    // htmx 交互入口：章节工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
    // 数据仍由 listCourseChapters 客户端方法拉取（编译期绑定成立）。
    const w = window as Window & { __meritReloadChapters?: () => void };
    w.__meritReloadChapters = () => {
      void loadChapters().catch((err: unknown) => {
        window.alert(err instanceof Error ? err.message : String(err));
      });
    };
    void loadAll();
    return () => {
      delete w.__meritReloadChapters;
    };
  });
</script>

<div class="space-y-6">
  <header class="flex flex-wrap items-center justify-between gap-4">
    <div>
      <p class="text-xs text-fg-dimmed">
        <a href="/courses" class="transition hover:text-fg">课程管理</a>
        <span class="mx-1">/</span>
        <span>课程详情</span>
      </p>
      <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">
        {course ? course.title : "课程详情"}
      </h1>
    </div>
    <div class="flex items-center gap-2">
      {#if course}
        <a
          href={`/courses/edit?id=${encodeURIComponent(course.id)}`}
          class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          编辑课程
        </a>
        <button
          type="button"
          onclick={() => void removeCourse()}
          class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-300 transition hover:bg-red-500/20"
        >
          删除课程
        </button>
      {/if}
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

  {#if errorMessage}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {errorMessage}
    </div>
  {/if}

  {#if loading}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      加载中…
    </div>
  {:else if course}
    <!-- 课程信息 -->
    <section class="space-y-4 rounded-xl border border-border bg-surface p-6">
      <div class="flex flex-wrap items-center gap-2">
        <span
          class="rounded-full border border-accent/40 bg-accent-subtle px-2.5 py-0.5 text-xs text-accent"
        >
          {course.topic}
        </span>
        <span
          class="rounded-full border border-border bg-surface-alt px-2.5 py-0.5 text-xs text-fg-muted"
        >
          {course.type}
        </span>
        {#if course.status === "启用"}
          <span
            class="rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2.5 py-0.5 text-xs text-emerald-300"
          >
            启用
          </span>
        {:else}
          <span
            class="rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2.5 py-0.5 text-xs text-zinc-300"
          >
            停用
          </span>
        {/if}
      </div>

      {#if metaText("session_time") || metaText("session_location") || metaText("instructor") || metaText("case_discussion")}
        <div class="grid grid-cols-1 gap-3 rounded-lg border border-border bg-surface-alt/60 p-4 text-sm sm:grid-cols-2">
          {#if metaText("session_time")}
            <div>
              <div class="text-xs text-fg-dimmed">线下场次时间</div>
              <div class="mt-1 text-fg">{metaText("session_time")}</div>
            </div>
          {/if}
          {#if metaText("session_location")}
            <div>
              <div class="text-xs text-fg-dimmed">线下场次地点</div>
              <div class="mt-1 text-fg">{metaText("session_location")}</div>
            </div>
          {/if}
          {#if metaText("instructor")}
            <div>
              <div class="text-xs text-fg-dimmed">讲师</div>
              <div class="mt-1 text-fg">{metaText("instructor")}</div>
            </div>
          {/if}
          {#if metaText("case_discussion")}
            <div class="sm:col-span-2">
              <div class="text-xs text-fg-dimmed">案例研讨</div>
              <div class="mt-1 whitespace-pre-wrap text-fg">{metaText("case_discussion")}</div>
            </div>
          {/if}
        </div>
      {/if}

      <div
        class="grid grid-cols-1 gap-3 rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm sm:grid-cols-3"
      >
        <div>
          <div class="text-xs text-fg-dimmed">创建人（只读）</div>
          <div class="mt-1 text-fg">{course.created_by || "—"}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">创建时间（只读）</div>
          <div class="mt-1 text-fg">{formatDateTime(course.created_at)}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">更新时间（只读）</div>
          <div class="mt-1 text-fg">{formatDateTime(course.updated_at)}</div>
        </div>
      </div>
    </section>

    <!-- 章节管理 -->
    <section class="space-y-4">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-lg font-semibold text-fg-emphasis">
          章节管理
          <span class="ml-2 text-sm font-normal text-fg-dimmed">共 {chapters.length} 章</span>
        </h2>
        <div class="flex items-center gap-2">
          {#if orderDirty}
            <button
              type="button"
              onclick={() => void saveOrder()}
              disabled={savingOrder}
              class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {savingOrder ? "保存中…" : "保存排序"}
            </button>
          {/if}
          <!-- htmx 交互：hx-on:click 事件绑定触发章节刷新（数据经 listCourseChapters 拉取） -->
          <button
            type="button"
            hx-on:click="window.__meritReloadChapters && window.__meritReloadChapters()"
            class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
          >
            刷新
          </button>
          <button
            type="button"
            onclick={() => (editor = { mode: "create" })}
            class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
          >
            新建章节
          </button>
        </div>
      </div>

      {#if orderMessage}
        <div class="rounded-lg border border-border bg-surface px-4 py-2 text-sm text-fg-muted">
          {orderMessage}
        </div>
      {/if}

      <div class="overflow-hidden rounded-xl border border-border bg-surface">
        {#if chapters.length === 0}
          <div class="px-4 py-10 text-center text-sm text-fg-dimmed">
            暂无章节（共 0 章）——点击「新建章节」添加
          </div>
        {:else}
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="border-b border-border text-xs text-fg-dimmed">
                <th class="w-16 px-4 py-3 font-medium">排序</th>
                <th class="px-4 py-3 font-medium">标题</th>
                <th class="px-4 py-3 font-medium">内容块</th>
                <th class="px-4 py-3 font-medium">互动问答配置</th>
                <th class="px-4 py-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {#each workingChapters as chapter, index (chapter.id)}
                <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                  <td class="px-4 py-3 tabular-nums text-fg-muted">{chapter.sort_order}</td>
                  <td class="px-4 py-3 font-medium text-fg-emphasis">{chapter.title}</td>
                  <td class="px-4 py-3 text-fg-muted">{blockSummary(chapter)}</td>
                  <td class="px-4 py-3 text-fg-muted">
                    {chapter.quiz_config ? "已配置" : "—"}
                  </td>
                  <td class="px-4 py-3">
                    <div class="flex items-center gap-2 text-xs">
                      <button
                        type="button"
                        onclick={() => moveChapter(index, -1)}
                        disabled={index === 0}
                        class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition enabled:hover:text-fg disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        上移
                      </button>
                      <button
                        type="button"
                        onclick={() => moveChapter(index, 1)}
                        disabled={index === workingChapters.length - 1}
                        class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition enabled:hover:text-fg disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        下移
                      </button>
                      <button
                        type="button"
                        onclick={() => (editor = { mode: "edit", chapter })}
                        class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        onclick={() => removeChapter(chapter)}
                        class="rounded-md border border-red-500/40 bg-red-500/10 px-2 py-1 text-red-300 transition hover:bg-red-500/20"
                      >
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        {/if}
      </div>

      {#if editor}
        <ChapterForm
          courseId={resolvedId}
          chapter={editor.mode === "edit" ? editor.chapter : null}
          nextSortOrder={nextSortOrder}
          onSave={() => {
            editor = null;
            void loadChapters().catch((err: unknown) => {
              errorMessage = err instanceof Error ? err.message : String(err);
            });
          }}
          onCancel={() => {
            editor = null;
          }}
        />
      {/if}
    </section>
  {/if}
</div>
