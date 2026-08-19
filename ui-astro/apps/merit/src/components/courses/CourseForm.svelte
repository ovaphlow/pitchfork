<script lang="ts">
  import { onMount } from "svelte";
  import type {
    CourseDeliveryType,
    CourseInput,
    CourseStatus,
    CourseTopic,
  } from "../../lib/merit-client";
  import { createCourse, getCourse, updateCourse } from "../../lib/merit-client";
  import {
    COURSE_DELIVERY_TYPES,
    COURSE_STATUSES,
    COURSE_TOPICS,
  } from "../../lib/merit-options";
  import { apiUrl } from "../../lib/api";

  interface Props {
    mode: "create" | "edit";
    courseId?: string;
  }

  let { mode, courseId = "" }: Props = $props();

  const isEdit = $derived(mode === "edit");

  // 表单字段：标题、专题、类型、状态为类型化字段（与 CourseInput 一一绑定）
  let title = $state("");
  let topic = $state<CourseTopic>(COURSE_TOPICS[0]);
  let type = $state<CourseDeliveryType>(COURSE_DELIVERY_TYPES[0]);
  let status = $state<CourseStatus>("启用");

  // metadata 表单字段（编辑后组织为 metadata 对象透传）：
  // 线下场次时间 / 地点 / 讲师、案例研讨
  let sessionTime = $state("");
  let sessionLocation = $state("");
  let instructor = $state("");
  let caseDiscussion = $state("");

  // 服务端管理字段：只读回显，表单不编辑、不提交
  let createdBy = $state("");
  let createdAt = $state("");
  let updatedAt = $state("");

  let resolvedId = $state("");
  let saving = $state(false);
  let errorMessage = $state("");

  function stringValue(value: unknown): string {
    return value === undefined || value === null ? "" : String(value);
  }

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function buildMetadata(): Record<string, unknown> {
    const metadata: Record<string, unknown> = {};
    if (sessionTime.trim()) metadata.session_time = sessionTime.trim();
    if (sessionLocation.trim()) metadata.session_location = sessionLocation.trim();
    if (instructor.trim()) metadata.instructor = instructor.trim();
    if (caseDiscussion.trim()) metadata.case_discussion = caseDiscussion.trim();
    return metadata;
  }

  async function loadCourse(id: string): Promise<void> {
    try {
      const course = await getCourse(id);
      title = course.title;
      topic = course.topic;
      type = course.type;
      status = course.status;
      const meta = course.metadata ?? {};
      sessionTime = stringValue(meta.session_time);
      sessionLocation = stringValue(meta.session_location);
      instructor = stringValue(meta.instructor);
      caseDiscussion = stringValue(meta.case_discussion);
      // 服务端管理字段只读回显
      createdBy = course.created_by;
      createdAt = course.created_at;
      updatedAt = course.updated_at;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
    }
  }

  async function submit(): Promise<void> {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      errorMessage = "请填写课程标题";
      return;
    }
    // created_by/created_at/updated_at 为服务端管理字段：不编辑、不提交
    const input: CourseInput = {
      title: trimmedTitle,
      topic,
      type,
      status,
      metadata: buildMetadata(),
    };
    saving = true;
    errorMessage = "";
    try {
      const course = isEdit
        ? await updateCourse(resolvedId, input)
        : await createCourse(input);
      window.location.assign(`/courses/detail?id=${encodeURIComponent(course.id)}`);
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      saving = false;
    }
  }

  onMount(() => {
    if (mode === "edit") {
      const id =
        courseId || new URLSearchParams(window.location.search).get("id") || "";
      if (!id) {
        errorMessage = "缺少课程 ID（URL 需携带 ?id=…）";
        return;
      }
      resolvedId = id;
      void loadCourse(id);
    }
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/courses" class="transition hover:text-fg">课程管理</a>
      <span class="mx-1">/</span>
      <span>{isEdit ? "编辑课程" : "新建课程"}</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">
      {isEdit ? "编辑课程" : "新建课程"}
    </h1>
    <p class="mt-1 text-sm text-fg-muted">
      经 @pitchfork/shared/merit 客户端提交（{isEdit ? "updateCourse" : "createCourse"}）
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

  {#if errorMessage}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {errorMessage}
    </div>
  {/if}

  {#if isEdit && !resolvedId}
    <div class="rounded-lg border border-border bg-surface px-4 py-6 text-center text-sm text-fg-dimmed">
      等待课程数据加载…
    </div>
  {:else}
    <form
      onsubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      class="space-y-6 rounded-xl border border-border bg-surface p-6"
    >
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <label class="flex flex-col gap-1 text-xs text-fg-muted sm:col-span-2">
          课程标题
          <input
            type="text"
            bind:value={title}
            placeholder="例如：客流高峰时段服务引导规范"
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
          />
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          专题（固定 5 项）
          <select
            bind:value={topic}
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
          >
            {#each COURSE_TOPICS as option}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          类型
          <select
            bind:value={type}
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
          >
            {#each COURSE_DELIVERY_TYPES as option}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          状态
          <select
            bind:value={status}
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
          >
            {#each COURSE_STATUSES as option}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </label>
      </div>

      <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
        <legend class="px-2 text-xs font-medium text-fg-muted">
          metadata · 线下场次与案例研讨（JSONB 扩展字段）
        </legend>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            线下场次时间
            <input
              type="datetime-local"
              bind:value={sessionTime}
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg"
            />
          </label>
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            线下场次地点
            <input
              type="text"
              bind:value={sessionLocation}
              placeholder="例如：3 号楼 201 会议室"
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
            />
          </label>
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            讲师
            <input
              type="text"
              bind:value={instructor}
              placeholder="例如：张老师"
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
            />
          </label>
          <label class="flex flex-col gap-1 text-xs text-fg-muted sm:col-span-2">
            案例研讨
            <textarea
              bind:value={caseDiscussion}
              rows="3"
              placeholder="案例研讨内容（可选）"
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
            ></textarea>
          </label>
        </div>
        <p class="mt-2 text-xs text-fg-dimmed">
          提交时组织为 metadata 对象透传：session_time / session_location / instructor /
          case_discussion（空字段不提交）
        </p>
      </fieldset>

      {#if isEdit}
        <div
          class="grid grid-cols-1 gap-3 rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm sm:grid-cols-3"
        >
          <div>
            <div class="text-xs text-fg-dimmed">创建人（只读）</div>
            <div class="mt-1 text-fg">{createdBy || "—"}</div>
          </div>
          <div>
            <div class="text-xs text-fg-dimmed">创建时间（只读）</div>
            <div class="mt-1 text-fg">{formatDateTime(createdAt)}</div>
          </div>
          <div>
            <div class="text-xs text-fg-dimmed">更新时间（只读）</div>
            <div class="mt-1 text-fg">{formatDateTime(updatedAt)}</div>
          </div>
        </div>
      {/if}

      <div class="flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          class="rounded-lg bg-accent px-5 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {saving ? "提交中…" : isEdit ? "保存修改" : "创建课程"}
        </button>
        <a
          href="/courses"
          class="rounded-lg border border-border bg-surface-alt px-5 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          取消
        </a>
      </div>
    </form>
  {/if}
</div>
