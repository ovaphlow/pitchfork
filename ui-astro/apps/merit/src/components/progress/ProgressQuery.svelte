<script lang="ts">
  import { onMount } from "svelte";
  import type { Course, ProgressStatus, ProgressSummary } from "../../lib/merit-client";
  import {
    getAssignmentProgress,
    listAssignments,
    listCourses,
  } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 课程下拉一次拉取上限 */
  const COURSE_SELECT_LIMIT = 200;

  /** 课程下指派一次拉取上限 */
  const ASSIGNMENT_LIST_LIMIT = 200;

  let courses = $state<Course[]>([]);

  // 查询条件：课程 + 员工
  let courseId = $state("");
  let employeeId = $state("");

  // 查询结果：每个指派一条进度汇总（客户端 getAssignmentProgress 返回结构）
  let summaries = $state<ProgressSummary[]>([]);
  let loading = $state(false);
  let errorMessage = $state("");
  /** 是否已执行过查询（用于区分「尚未查询」与「查询结果为空」两种占位） */
  let searched = $state(false);

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

  function shortId(id: string): string {
    return id.length > 13 ? `${id.slice(0, 13)}…` : id;
  }

  async function query(): Promise<void> {
    const trimmedCourseId = courseId.trim();
    const trimmedEmployeeId = employeeId.trim();
    if (!trimmedCourseId) {
      errorMessage = "请选择课程";
      return;
    }
    if (!trimmedEmployeeId) {
      errorMessage = "请填写员工 ID";
      return;
    }
    loading = true;
    errorMessage = "";
    searched = false;
    try {
      // 先按课程列出指派，再逐指派查询该员工的学习进度汇总
      const page = await listAssignments({
        course_id: trimmedCourseId,
        limit: ASSIGNMENT_LIST_LIMIT,
      });
      if (page.records.length === 0) {
        summaries = [];
        searched = true;
        return;
      }
      const settled = await Promise.allSettled(
        page.records.map((assignment) =>
          getAssignmentProgress(assignment.id, trimmedEmployeeId),
        ),
      );
      const fulfilled: ProgressSummary[] = [];
      let failed = 0;
      for (const result of settled) {
        if (result.status === "fulfilled") {
          fulfilled.push(result.value);
        } else {
          failed += 1;
        }
      }
      summaries = fulfilled;
      searched = true;
      if (fulfilled.length === 0) {
        errorMessage = `该课程下 ${page.records.length} 个指派的学习进度查询均失败`;
      } else if (failed > 0) {
        errorMessage = `有 ${failed} 个指派查询失败，已展示其余结果`;
      }
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      summaries = [];
    } finally {
      loading = false;
    }
  }

  function reset(): void {
    courseId = "";
    employeeId = "";
    summaries = [];
    errorMessage = "";
    searched = false;
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    if (searched) void query();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadProgress?: () => void };
    w.__meritReloadProgress = refresh;
    void listCourses({ limit: COURSE_SELECT_LIMIT })
      .then((result) => {
        courses = result.records;
      })
      .catch((err) => {
        errorMessage = `课程列表加载失败：${err instanceof Error ? err.message : String(err)}`;
      });
    return () => {
      delete w.__meritReloadProgress;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <span>学习进度查询</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">学习进度查询</h1>
    <p class="mt-1 text-sm text-fg-muted">
      按课程 + 员工筛选，经 listAssignments 定位指派后逐项调用 getAssignmentProgress 展示章节进度与完成状态
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

  <!-- 查询表单 -->
  <section class="rounded-xl border border-border bg-surface p-4">
    <div class="flex flex-wrap items-end gap-3">
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        课程
        <select
          bind:value={courseId}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">请选择课程</option>
          {#each courses as course (course.id)}
            <option value={course.id}>{course.title}</option>
          {/each}
        </select>
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        员工 ID
        <input
          type="text"
          bind:value={employeeId}
          placeholder="例如：01ARZ3NDEKTSV4RRFFQ69G5FAV"
          class="w-72 rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        />
      </label>
      <button
        type="button"
        onclick={() => void query()}
        class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
      >
        查询
      </button>
      <button
        type="button"
        onclick={reset}
        class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        重置
      </button>
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经客户端方法拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadProgress && window.__meritReloadProgress()"
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

  <!-- 进度结果 -->
  {#if loading}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      查询中…
    </div>
  {:else if searched && summaries.length === 0}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      该课程下暂无培训任务指派，无学习进度数据
    </div>
  {:else if summaries.length > 0}
    <div class="space-y-6">
      {#each summaries as summary (summary.assignment_id)}
        <section class="overflow-hidden rounded-xl border border-border bg-surface">
          <div class="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-border px-4 py-3">
            <div>
              <div class="text-xs text-fg-dimmed">课程</div>
              <div class="mt-0.5 text-sm font-semibold text-fg-emphasis">
                {summary.course_title || shortId(summary.course_id)}
              </div>
            </div>
            <div>
              <div class="text-xs text-fg-dimmed">指派</div>
              <div class="mt-0.5 text-sm text-fg-muted">{shortId(summary.assignment_id)}</div>
            </div>
            <div>
              <div class="text-xs text-fg-dimmed">员工</div>
              <div class="mt-0.5 text-sm text-fg-muted">{shortId(summary.employee_id)}</div>
            </div>
            <div>
              <div class="text-xs text-fg-dimmed">章节进度</div>
              <div class="mt-0.5 text-sm text-fg-muted">
                {summary.completed_chapters} / {summary.total_chapters} 章已完成
              </div>
            </div>
            <div>
              <div class="text-xs text-fg-dimmed">指派状态</div>
              <div class="mt-1">
                <span class={statusBadgeClass(summary.status)}>{summary.status}</span>
              </div>
            </div>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-left text-sm">
              <thead>
                <tr class="border-b border-border text-xs text-fg-dimmed">
                  <th class="px-4 py-3 font-medium">章节</th>
                  <th class="px-4 py-3 font-medium">进度</th>
                  <th class="px-4 py-3 font-medium">状态</th>
                  <th class="px-4 py-3 font-medium">开始时间</th>
                  <th class="px-4 py-3 font-medium">完成时间</th>
                </tr>
              </thead>
              <tbody>
                {#if summary.chapters.length === 0}
                  <tr>
                    <td colspan="5" class="px-4 py-8 text-center text-fg-dimmed">
                      该课程暂无章节
                    </td>
                  </tr>
                {:else}
                  {#each summary.chapters as chapter (chapter.chapter_id)}
                    <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                      <td class="px-4 py-3 text-fg-emphasis">
                        {chapter.chapter_title || shortId(chapter.chapter_id)}
                      </td>
                      <td class="px-4 py-3 tabular-nums text-fg-muted">
                        {chapter.progress_percent}%
                      </td>
                      <td class="px-4 py-3">
                        <span class={statusBadgeClass(chapter.status)}>{chapter.status}</span>
                      </td>
                      <td class="px-4 py-3 text-fg-muted">{formatDateTime(chapter.started_at)}</td>
                      <td class="px-4 py-3 text-fg-muted">{formatDateTime(chapter.completed_at)}</td>
                    </tr>
                  {/each}
                {/if}
              </tbody>
            </table>
          </div>
        </section>
      {/each}
    </div>
  {:else}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      请选择课程并填写员工 ID 后点击「查询」
    </div>
  {/if}
</div>
