<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Course,
    CourseDeliveryType,
    CourseListParams,
    CourseStatus,
    CourseTopic,
  } from "../../lib/merit-client";
  import { deleteCourse, listCourses } from "../../lib/merit-client";
  import {
    COURSE_DELIVERY_TYPES,
    COURSE_PAGE_SIZE,
    COURSE_STATUSES,
    COURSE_TOPICS,
  } from "../../lib/merit-options";
  import { apiUrl } from "../../lib/api";

  let courses = $state<Course[]>([]);
  let total = $state(0);
  let loading = $state(true);
  let errorMessage = $state("");

  // 筛选条件：空串表示「全部」
  let filterTopic = $state<"" | CourseTopic>("");
  let filterType = $state<"" | CourseDeliveryType>("");
  let filterStatus = $state<"" | CourseStatus>("");

  // 分页：1 起始页码，limit/offset 由 page 与 pageSize 派生
  let page = $state(1);
  const pageSize = COURSE_PAGE_SIZE;

  const totalPages = $derived(total === 0 ? 1 : Math.ceil(total / pageSize));

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  async function load(): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      const params: CourseListParams = {
        limit: pageSize,
        offset: (page - 1) * pageSize,
      };
      if (filterTopic !== "") params.topic = filterTopic;
      if (filterType !== "") params.type = filterType;
      if (filterStatus !== "") params.status = filterStatus;
      const result = await listCourses(params);
      courses = result.records;
      total = result.meta.total;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      courses = [];
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
    filterTopic = "";
    filterType = "";
    filterStatus = "";
    page = 1;
    void load();
  }

  async function removeCourse(course: Course): Promise<void> {
    if (
      !window.confirm(
        `确定删除课程「${course.title}」？其全部章节将一并删除，此操作不可恢复。`,
      )
    )
      return;
    try {
      await deleteCourse(course.id);
      if (courses.length === 1 && page > 1) page -= 1;
      await load();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 listCourses 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    void load();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadCourses?: () => void };
    w.__meritReloadCourses = refresh;
    void load();
    return () => {
      delete w.__meritReloadCourses;
    };
  });
</script>

<div class="space-y-6">
  <header class="flex flex-wrap items-center justify-between gap-4">
    <div>
      <p class="text-xs text-fg-dimmed">
        <a href="/" class="transition hover:text-fg">首页</a>
        <span class="mx-1">/</span>
        <span>课程管理</span>
      </p>
      <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">课程管理</h1>
      <p class="mt-1 text-sm text-fg-muted">
        按 5 个专题 + 状态/类型筛选，limit/offset 分页；数据经 @pitchfork/shared/merit 客户端读写
      </p>
    </div>
    <a
      href="/courses/new"
      class="rounded-lg bg-accent px-4 py-2 font-medium text-white transition hover:opacity-90"
    >
      新建课程
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

  <!-- 筛选工具栏 -->
  <section class="rounded-xl border border-border bg-surface p-4">
    <div class="flex flex-wrap items-end gap-3">
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        专题
        <select
          bind:value={filterTopic}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部</option>
          {#each COURSE_TOPICS as topic}
            <option value={topic}>{topic}</option>
          {/each}
        </select>
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        类型
        <select
          bind:value={filterType}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部</option>
          {#each COURSE_DELIVERY_TYPES as type}
            <option value={type}>{type}</option>
          {/each}
        </select>
      </label>
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        状态
        <select
          bind:value={filterStatus}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="">全部</option>
          {#each COURSE_STATUSES as status}
            <option value={status}>{status}</option>
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
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经 listCourses 拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadCourses && window.__meritReloadCourses()"
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

  <!-- 课程列表 -->
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="border-b border-border text-xs text-fg-dimmed">
            <th class="px-4 py-3 font-medium">标题</th>
            <th class="px-4 py-3 font-medium">专题</th>
            <th class="px-4 py-3 font-medium">类型</th>
            <th class="px-4 py-3 font-medium">状态</th>
            <th class="px-4 py-3 font-medium">创建人</th>
            <th class="px-4 py-3 font-medium">创建时间</th>
            <th class="px-4 py-3 font-medium">更新时间</th>
            <th class="px-4 py-3 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          {#if loading}
            <tr>
              <td colspan="8" class="px-4 py-10 text-center text-fg-dimmed">加载中…</td>
            </tr>
          {:else if courses.length === 0}
            <tr>
              <td colspan="8" class="px-4 py-10 text-center text-fg-dimmed">
                暂无课程{total === 0 ? "" : "（当前筛选条件下无结果）"}
              </td>
            </tr>
          {:else}
            {#each courses as course (course.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 font-medium text-fg-emphasis">{course.title}</td>
                <td class="px-4 py-3 text-fg-muted">{course.topic}</td>
                <td class="px-4 py-3 text-fg-muted">{course.type}</td>
                <td class="px-4 py-3">
                  {#if course.status === "启用"}
                    <span
                      class="rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300"
                    >
                      启用
                    </span>
                  {:else}
                    <span
                      class="rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2 py-0.5 text-xs text-zinc-300"
                    >
                      停用
                    </span>
                  {/if}
                </td>
                <td class="px-4 py-3 text-fg-muted">{course.created_by || "—"}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(course.created_at)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(course.updated_at)}</td>
                <td class="px-4 py-3">
                  <div class="flex items-center gap-2 text-xs">
                    <a
                      href={`/courses/detail?id=${encodeURIComponent(course.id)}`}
                      class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                    >
                      详情
                    </a>
                    <a
                      href={`/courses/edit?id=${encodeURIComponent(course.id)}`}
                      class="rounded-md border border-border bg-surface-alt px-2 py-1 text-fg-muted transition hover:text-fg"
                    >
                      编辑
                    </a>
                    <button
                      type="button"
                      onclick={() => removeCourse(course)}
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
