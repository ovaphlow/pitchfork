<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Assignment,
    ProgressStatus,
    ProgressSummary,
  } from "../../lib/merit-client";
  import {
    getAssignmentProgress,
    getCourse,
    listAssignments,
  } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 指派列表一次拉取上限 */
  const ASSIGNMENT_LIST_LIMIT = 200;

  /**
   * 演示学员身份缺省值：原型无认证，统一从 URL 查询参数 employee_id 读取，
   * 缺省 u-001（与后端 employee_id 纯字符串维度一致，不新增后端能力）。
   */
  const DEFAULT_EMPLOYEE_ID = "u-001";

  /** 列表项：指派 + 该指派下的进度摘要（摘要失败时回退课程接口取名称） */
  interface TaskRow {
    assignment: Assignment;
    courseTitle: string;
    summary: ProgressSummary | null;
  }

  let employeeId = $state(DEFAULT_EMPLOYEE_ID);
  let rows = $state<TaskRow[]>([]);
  let loading = $state(false);
  let errorMessage = $state("");
  /** 是否已执行过查询（区分「尚未查询」与「查询结果为空」两种占位） */
  let searched = $state(false);

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function statusBadgeClass(status: ProgressStatus | null): string {
    if (status === "已完成") {
      return "rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300";
    }
    if (status === "学习中") {
      return "rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs text-amber-300";
    }
    return "rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2 py-0.5 text-xs text-zinc-300";
  }

  /** 进度进度条宽度（0-100%） */
  function progressPercent(row: TaskRow): number {
    const summary = row.summary;
    if (!summary || summary.total_chapters <= 0) return 0;
    return Math.round((summary.completed_chapters / summary.total_chapters) * 100);
  }

  /** 截止时间已过且未完成 → 逾期高亮 */
  function isOverdue(row: TaskRow): boolean {
    if (row.summary?.status === "已完成") return false;
    if (!row.assignment.deadline) return false;
    const deadline = new Date(row.assignment.deadline);
    return !Number.isNaN(deadline.getTime()) && deadline.getTime() < Date.now();
  }

  /** 进入课程学习页（路由由课程学习页卡片交付，本页仅链接并携带身份/上下文参数） */
  function learnHref(row: TaskRow): string {
    const params = new URLSearchParams({
      assignment_id: row.assignment.id,
      course_id: row.assignment.course_id,
      employee_id: employeeId.trim() || DEFAULT_EMPLOYEE_ID,
    });
    return `/learn/?${params.toString()}`;
  }

  /** 进入在线考核页（路由由在线考核页卡片交付，本页仅链接并携带身份参数） */
  function examHref(): string {
    const params = new URLSearchParams({
      employee_id: employeeId.trim() || DEFAULT_EMPLOYEE_ID,
    });
    return `/exam/?${params.toString()}`;
  }

  async function load(): Promise<void> {
    const id = employeeId.trim() || DEFAULT_EMPLOYEE_ID;
    loading = true;
    errorMessage = "";
    searched = false;
    try {
      // 1) assignments 列表：employee_id 筛选（后端 employee 展开规则只命中 用户 指派）
      const page = await listAssignments({ employee_id: id, limit: ASSIGNMENT_LIST_LIMIT });
      rows = [];
      if (page.records.length === 0) {
        searched = true;
        return;
      }
      // 2) progress 摘要：按 assignment 获取课程名称 / 学习状态 / 章节进度
      const settled = await Promise.allSettled(
        page.records.map((assignment) => getAssignmentProgress(assignment.id, id)),
      );
      const next: TaskRow[] = [];
      let failed = 0;
      for (let index = 0; index < page.records.length; index += 1) {
        const assignment = page.records[index];
        const result = settled[index];
        if (result.status === "fulfilled") {
          next.push({
            assignment,
            courseTitle: result.value.course_title,
            summary: result.value,
          });
        } else {
          failed += 1;
          // 摘要失败（如课程已删除）：回退课程接口取课程名称，状态/进度留空
          let courseTitle = assignment.course_id;
          try {
            const course = await getCourse(assignment.course_id);
            courseTitle = course.title;
          } catch {
            // 保留 course_id 作为展示兜底
          }
          next.push({ assignment, courseTitle, summary: null });
        }
      }
      rows = next;
      searched = true;
      if (failed > 0) {
        errorMessage = `有 ${failed} 个任务的进度摘要获取失败，已展示课程名称与其余信息`;
      }
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      rows = [];
    } finally {
      loading = false;
    }
  }

  /** htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 绑定调用，数据仍由客户端方法拉取 */
  function refresh(): void {
    if (searched) void load();
  }

  onMount(() => {
    // 身份口径：employee_id 查询参数，缺省演示学员 u-001
    const params = new URLSearchParams(window.location.search);
    const fromQuery = params.get("employee_id");
    if (fromQuery) employeeId = fromQuery;
    const w = window as Window & { __meritReloadMyTraining?: () => void };
    w.__meritReloadMyTraining = refresh;
    void load();
    return () => {
      delete w.__meritReloadMyTraining;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <span>我的培训任务</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">我的培训任务</h1>
    <p class="mt-1 text-sm text-fg-muted">
      当前学员：
      <span class="font-mono text-fg-emphasis">{employeeId}</span>
      （原型无认证，可通过 URL 查询参数 employee_id 或下方输入框切换演示学员）
    </p>
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

  <!-- 身份切换 -->
  <section class="rounded-xl border border-border bg-surface p-4">
    <div class="flex flex-wrap items-end gap-3">
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        学员 ID
        <input
          type="text"
          bind:value={employeeId}
          placeholder={DEFAULT_EMPLOYEE_ID}
          class="w-64 rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        />
      </label>
      <button
        type="button"
        onclick={() => void load()}
        class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
      >
        查询
      </button>
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经客户端方法拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadMyTraining && window.__meritReloadMyTraining()"
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

  {#if loading}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      加载中…
    </div>
  {:else if searched && rows.length === 0}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      暂无培训任务指派（{employeeId} 名下 records 为空），可切换学员 ID 后重试
    </div>
  {:else if rows.length > 0}
    <div class="space-y-4">
      {#each rows as row (row.assignment.id)}
        <section class="rounded-xl border border-border bg-surface p-4 transition hover:border-accent/50">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div class="min-w-0">
              <div class="text-base font-semibold text-fg-emphasis">{row.courseTitle}</div>
              <div class="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-fg-dimmed">
                <span>
                  指派方式：
                  <span
                    class={
                      row.assignment.assign_type === "自动触发"
                        ? "rounded-full border border-sky-500/40 bg-sky-500/10 px-2 py-0.5 text-xs text-sky-300"
                        : "rounded-full border border-violet-500/40 bg-violet-500/10 px-2 py-0.5 text-xs text-violet-300"
                    }
                  >
                    {row.assignment.assign_type}
                  </span>
                </span>
                <span>指派编号：<span class="font-mono">{row.assignment.id}</span></span>
                <span>
                  截止时间：
                  <span class={isOverdue(row) ? "text-red-300" : ""}>
                    {formatDateTime(row.assignment.deadline)}
                    {#if isOverdue(row)}
                      （已逾期）
                    {/if}
                  </span>
                </span>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <div class="text-right">
                <div class="text-xs text-fg-dimmed">完成状态</div>
                <div class="mt-1">
                  {#if row.summary}
                    <span class={statusBadgeClass(row.summary.status)}>{row.summary.status}</span>
                  {:else}
                    <span class={statusBadgeClass(null)}>状态未知</span>
                  {/if}
                </div>
              </div>
              <div class="text-right">
                <div class="text-xs text-fg-dimmed">章节进度</div>
                <div class="mt-1 text-sm text-fg-muted">
                  {#if row.summary}
                    {row.summary.completed_chapters} / {row.summary.total_chapters} 章
                  {:else}
                    —
                  {/if}
                </div>
              </div>
            </div>
          </div>

          {#if row.summary}
            <div class="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-surface-alt">
              <div
                class="h-full rounded-full bg-accent transition-all"
                style="width: {progressPercent(row)}%"
              ></div>
            </div>
          {/if}

          <div class="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-3">
            <a
              href={learnHref(row)}
              class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
            >
              进入课程学习
            </a>
            <a
              href={examHref()}
              class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
            >
              在线考核
            </a>
            <span class="ml-auto text-xs text-fg-dimmed">
              指派目标：{row.assignment.target_type}（{row.assignment.target_ids.join("、")}）
            </span>
          </div>
        </section>
      {/each}
    </div>
  {:else}
    <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
      正在加载培训任务…
    </div>
  {/if}
</div>
