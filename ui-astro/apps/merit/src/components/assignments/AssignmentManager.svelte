<script lang="ts">
  import { onMount } from "svelte";
  import type {
    AssignType,
    Assignment,
    AssignmentInput,
    Course,
    TargetType,
  } from "../../lib/merit-client";
  import {
    createAssignment,
    deleteAssignment,
    listAssignments,
    listCourses,
  } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 指派方式固定选项（与后端中文枚举一致，取值由客户端 AssignType 编译期保证） */
  const ASSIGN_TYPES: readonly AssignType[] = ["手动指派", "自动触发"];

  /** 目标类型固定选项（与后端中文枚举一致，取值由客户端 TargetType 编译期保证） */
  const TARGET_TYPES: readonly TargetType[] = ["用户", "岗位", "部门"];

  /** 课程下拉一次拉取上限 */
  const COURSE_SELECT_LIMIT = 200;

  /** 指派列表一次拉取上限 */
  const ASSIGNMENT_LIST_LIMIT = 200;

  // ---- 指派列表 ----
  let courses = $state<Course[]>([]);
  let assignments = $state<Assignment[]>([]);
  let total = $state(0);
  let loading = $state(true);
  let listError = $state("");

  const courseMap = $derived(
    new Map(courses.map((course) => [course.id, course])),
  );

  function courseTitle(id: string): string {
    return courseMap.get(id)?.title || id;
  }

  // ---- 指派表单 ----
  let courseId = $state("");
  let assignType = $state<AssignType>("手动指派");
  let targetType = $state<TargetType>("用户");
  let targetIds = $state<string[]>([]);
  let targetIdInput = $state("");
  let deadline = $state("");
  let saving = $state(false);
  let formError = $state("");

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  /** datetime-local 值转 RFC3339（后端要求）；空串视为未设置 */
  function toRfc3339(value: string): string {
    const trimmed = value.trim();
    if (!trimmed) return "";
    const date = new Date(trimmed);
    return Number.isNaN(date.getTime()) ? "" : date.toISOString();
  }

  function addTargetId(): void {
    const id = targetIdInput.trim();
    if (!id) return;
    if (targetIds.includes(id)) {
      formError = `目标 ID「${id}」已存在`;
      return;
    }
    targetIds = [...targetIds, id];
    targetIdInput = "";
    formError = "";
  }

  function removeTargetId(index: number): void {
    targetIds = targetIds.filter((_, i) => i !== index);
  }

  async function load(): Promise<void> {
    loading = true;
    listError = "";
    try {
      const result = await listAssignments({ limit: ASSIGNMENT_LIST_LIMIT });
      assignments = result.records;
      total = result.meta.total;
    } catch (err) {
      listError = err instanceof Error ? err.message : String(err);
      assignments = [];
      total = 0;
    } finally {
      loading = false;
    }
  }

  async function submit(): Promise<void> {
    const trimmedCourseId = courseId.trim();
    if (!trimmedCourseId) {
      formError = "请选择课程";
      return;
    }
    if (targetIds.length === 0) {
      formError = "请至少添加一个目标 ID";
      return;
    }
    const input: AssignmentInput = {
      course_id: trimmedCourseId,
      assign_type: assignType,
      target_type: targetType,
      target_ids: targetIds,
    };
    const deadlineRfc3339 = toRfc3339(deadline);
    if (deadlineRfc3339) input.deadline = deadlineRfc3339;
    saving = true;
    formError = "";
    try {
      await createAssignment(input);
      // 创建成功后重置目标列表与截止时间，课程/枚举保留以便连续指派
      targetIds = [];
      targetIdInput = "";
      deadline = "";
      await load();
    } catch (err) {
      formError = err instanceof Error ? err.message : String(err);
    } finally {
      saving = false;
    }
  }

  async function removeAssignment(assignment: Assignment): Promise<void> {
    if (
      !window.confirm(
        `确定删除对课程「${courseTitle(assignment.course_id)}」的指派吗？删除后该指派下的学习进度记录随之失效。`,
      )
    )
      return;
    try {
      await deleteAssignment(assignment.id);
      // 删除成功后重新拉取列表，对应条目即被移除
      await load();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    }
  }

  // htmx 交互入口：工具栏「刷新」按钮经 hx-on:click 事件绑定调用本函数，
  // 数据仍由 listAssignments 客户端方法拉取（编译期绑定成立）。
  function refresh(): void {
    void load();
  }

  onMount(() => {
    const w = window as Window & { __meritReloadAssignments?: () => void };
    w.__meritReloadAssignments = refresh;
    void listCourses({ limit: COURSE_SELECT_LIMIT })
      .then((result) => {
        courses = result.records;
      })
      .catch((err) => {
        listError = `课程列表加载失败：${err instanceof Error ? err.message : String(err)}`;
      });
    void load();
    return () => {
      delete w.__meritReloadAssignments;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <span>培训任务指派</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">培训任务指派</h1>
    <p class="mt-1 text-sm text-fg-muted">
      选择课程并指定指派方式 / 目标类型 / 目标 ID / 截止时间创建指派；列表展示与删除经
      @pitchfork/shared/merit 客户端读写（createAssignment / listAssignments / deleteAssignment）
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

  {#if formError}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {formError}
    </div>
  {/if}

  <!-- 指派表单 -->
  <form
    onsubmit={(event) => {
      event.preventDefault();
      void submit();
    }}
    class="space-y-6 rounded-xl border border-border bg-surface p-6"
  >
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        指派课程
        <select
          bind:value={courseId}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          <option value="" disabled>请选择课程</option>
          {#each courses as course (course.id)}
            <option value={course.id}>{course.title}</option>
          {/each}
        </select>
      </label>

      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        指派类型
        <select
          bind:value={assignType}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          {#each ASSIGN_TYPES as option}
            <option value={option}>{option}</option>
          {/each}
        </select>
      </label>

      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        目标类型
        <select
          bind:value={targetType}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        >
          {#each TARGET_TYPES as option}
            <option value={option}>{option}</option>
          {/each}
        </select>
      </label>

      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        截止时间（可选）
        <input
          type="datetime-local"
          bind:value={deadline}
          class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
        />
      </label>
    </div>

    <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
      <legend class="px-2 text-xs font-medium text-fg-muted">
        目标 ID 列表（{targetType}，必填至少一个）
      </legend>
      <div class="flex flex-wrap items-center gap-2">
        <input
          type="text"
          bind:value={targetIdInput}
          placeholder={`输入${targetType} ID，例如：01ARZ3NDEKTSV4RRFFQ69G5FAV`}
          class="min-w-72 flex-1 rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        />
        <button
          type="button"
          onclick={addTargetId}
          class="rounded-lg border border-border bg-surface px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          添加
        </button>
      </div>
      {#if targetIds.length > 0}
        <div class="mt-3 flex flex-wrap gap-2">
          {#each targetIds as id, index (index)}
            <span
              class="inline-flex items-center gap-2 rounded-full border border-border bg-surface px-3 py-1 text-xs text-fg"
            >
              {id}
              <button
                type="button"
                onclick={() => removeTargetId(index)}
                aria-label={`移除目标 ${id}`}
                class="text-fg-dimmed transition hover:text-red-300"
              >
                ×
              </button>
            </span>
          {/each}
        </div>
      {:else}
        <p class="mt-3 text-xs text-fg-dimmed">尚未添加目标 ID，提交前请至少添加一个</p>
      {/if}
    </fieldset>

    <div class="flex items-center gap-3">
      <button
        type="submit"
        disabled={saving}
        class="rounded-lg bg-accent px-5 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {saving ? "提交中…" : "创建指派"}
      </button>
    </div>
  </form>

  <!-- 指派列表 -->
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
      <h2 class="text-sm font-semibold text-fg-emphasis">指派列表</h2>
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经 listAssignments 拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadAssignments && window.__meritReloadAssignments()"
        class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        刷新
      </button>
    </div>

    {#if listError}
      <div class="border-b border-border px-4 py-3 text-sm text-red-300">{listError}</div>
    {/if}

    <div class="overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="border-b border-border text-xs text-fg-dimmed">
            <th class="px-4 py-3 font-medium">课程</th>
            <th class="px-4 py-3 font-medium">指派类型</th>
            <th class="px-4 py-3 font-medium">目标类型</th>
            <th class="px-4 py-3 font-medium">目标 ID</th>
            <th class="px-4 py-3 font-medium">截止时间</th>
            <th class="px-4 py-3 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          {#if loading}
            <tr>
              <td colspan="6" class="px-4 py-10 text-center text-fg-dimmed">加载中…</td>
            </tr>
          {:else if assignments.length === 0}
            <tr>
              <td colspan="6" class="px-4 py-10 text-center text-fg-dimmed">
                暂无培训任务指派
              </td>
            </tr>
          {:else}
            {#each assignments as assignment (assignment.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 font-medium text-fg-emphasis">
                  {courseTitle(assignment.course_id)}
                </td>
                <td class="px-4 py-3 text-fg-muted">{assignment.assign_type}</td>
                <td class="px-4 py-3 text-fg-muted">{assignment.target_type}</td>
                <td class="px-4 py-3">
                  <div class="flex max-w-72 flex-wrap gap-1">
                    {#each assignment.target_ids as id, index (index)}
                      <span
                        class="rounded-full border border-border bg-surface-alt px-2 py-0.5 text-xs text-fg-muted"
                      >
                        {id}
                      </span>
                    {/each}
                  </div>
                </td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(assignment.deadline)}</td>
                <td class="px-4 py-3">
                  <button
                    type="button"
                    onclick={() => removeAssignment(assignment)}
                    class="rounded-md border border-red-500/40 bg-red-500/10 px-2 py-1 text-xs text-red-300 transition hover:bg-red-500/20"
                  >
                    删除
                  </button>
                </td>
              </tr>
            {/each}
          {/if}
        </tbody>
      </table>
    </div>
    <div class="flex items-center justify-between border-t border-border px-4 py-3 text-sm text-fg-muted">
      <span>共 {total} 条</span>
    </div>
  </section>
</div>
