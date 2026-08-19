<script lang="ts">
  import { onMount } from "svelte";
  import type { GenerationStrategy, PaperInput } from "../../lib/merit-client";
  import { createPaper, getPaper, updatePaper } from "../../lib/merit-client";
  import {
    PAPER_DURATION_MIN,
    PAPER_PASS_SCORE_MAX,
    PAPER_PASS_SCORE_MIN,
    PAPER_STRATEGY_TYPES,
  } from "../../lib/paper-options";
  import { apiUrl } from "../../lib/api";

  interface Props {
    mode: "create" | "edit";
    paperId?: string;
  }

  let { mode, paperId = "" }: Props = $props();

  const isEdit = $derived(mode === "edit");

  // 表单字段：标题必填；时长为 >0 整数控件；及格分为 0–100 整数控件（0 合法、必填）
  let title = $state("");
  let durationMinutes = $state<number | null>(null);
  let passScore = $state<number | null>(null);

  // 组卷策略：按题型设置数量（非负整数），至少一个题型为正数；
  // 提交时组织为 {题型: 数量} 对象透传（0 数量的题型不写入）
  let strategyCounts = $state<Record<string, number>>({
    单选: 0,
    多选: 0,
    判断: 0,
    填空: 0,
  });

  // 服务端管理字段：只读回显，表单不编辑、不提交
  let createdBy = $state("");
  let createdAt = $state("");
  let updatedAt = $state("");
  // questions 组卷快照：本表单只读展示数量，不编辑、不提交
  let questionCount = $state(0);

  let resolvedId = $state("");
  let saving = $state(false);
  let errorMessage = $state("");

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  async function loadPaper(id: string): Promise<void> {
    try {
      const paper = await getPaper(id);
      title = paper.title;
      durationMinutes = paper.duration_minutes;
      passScore = paper.pass_score;
      // generation_strategy：对象（题型 → 数量）回填到各题型数量控件
      const strategy = paper.generation_strategy ?? {};
      const counts: Record<string, number> = { 单选: 0, 多选: 0, 判断: 0, 填空: 0 };
      for (const type of PAPER_STRATEGY_TYPES) {
        const value = strategy[type];
        if (typeof value === "number" && value >= 0) counts[type] = value;
      }
      strategyCounts = counts;
      // questions 快照只读展示数量；created_by/created_at/updated_at 只读回显
      questionCount = paper.questions.length;
      createdBy = paper.created_by;
      createdAt = paper.created_at;
      updatedAt = paper.updated_at;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
    }
  }

  async function submit(): Promise<void> {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      errorMessage = "请填写试卷标题";
      return;
    }
    if (durationMinutes === null || !Number.isInteger(durationMinutes)) {
      errorMessage = `请填写时长（整数分钟，>0）`;
      return;
    }
    if (durationMinutes < PAPER_DURATION_MIN) {
      errorMessage = `时长必须大于 ${PAPER_DURATION_MIN - 1} 分钟`;
      return;
    }
    if (passScore === null || !Number.isInteger(passScore)) {
      errorMessage = `请填写及格分（0–${PAPER_PASS_SCORE_MAX} 整数，0 合法）`;
      return;
    }
    if (passScore < PAPER_PASS_SCORE_MIN || passScore > PAPER_PASS_SCORE_MAX) {
      errorMessage = `及格分必须在 ${PAPER_PASS_SCORE_MIN}–${PAPER_PASS_SCORE_MAX} 之间`;
      return;
    }
    // 组卷策略：至少一个题型数量为正数
    const strategy: GenerationStrategy = {};
    for (const type of PAPER_STRATEGY_TYPES) {
      const count = strategyCounts[type] ?? 0;
      if (count < 0 || !Number.isInteger(count)) {
        errorMessage = `${type}题数量必须是非负整数`;
        return;
      }
      if (count > 0) strategy[type] = count;
    }
    if (Object.keys(strategy).length === 0) {
      errorMessage = "组卷策略至少一个题型的数量为正数";
      return;
    }
    // created_by/created_at/updated_at 为服务端管理字段：不编辑、不提交；
    // questions 快照只由组卷写入，表单不提交
    const input: PaperInput = {
      title: trimmedTitle,
      duration_minutes: durationMinutes,
      pass_score: passScore,
      generation_strategy: strategy,
    };
    saving = true;
    errorMessage = "";
    try {
      const paper = isEdit
        ? await updatePaper(resolvedId, input)
        : await createPaper(input);
      window.location.assign(`/papers/detail?id=${encodeURIComponent(paper.id)}`);
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      saving = false;
    }
  }

  onMount(() => {
    if (mode === "edit") {
      const id =
        paperId || new URLSearchParams(window.location.search).get("id") || "";
      if (!id) {
        errorMessage = "缺少试卷 ID（URL 需携带 ?id=…）";
        return;
      }
      resolvedId = id;
      void loadPaper(id);
    }
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/papers" class="transition hover:text-fg">试卷管理</a>
      <span class="mx-1">/</span>
      <span>{isEdit ? "编辑试卷" : "新建试卷"}</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">
      {isEdit ? "编辑试卷" : "新建试卷"}
    </h1>
    <p class="mt-1 text-sm text-fg-muted">
      经 @pitchfork/shared/merit 客户端提交（{isEdit ? "updatePaper" : "createPaper"}）
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
      等待试卷数据加载…
    </div>
  {:else}
    <form
      onsubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      class="space-y-6 rounded-xl border border-border bg-surface p-6"
    >
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <label class="flex flex-col gap-1 text-xs text-fg-muted sm:col-span-3">
          试卷标题（必填）
          <input
            type="text"
            bind:value={title}
            placeholder="例如：2026 年度站务员业务知识考核"
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
          />
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          时长（分钟，>0 整数）
          <input
            type="number"
            min={PAPER_DURATION_MIN}
            step="1"
            bind:value={durationMinutes}
            placeholder="例如：60"
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
          />
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          及格分（0–100 整数，0 合法、必填）
          <input
            type="number"
            min={PAPER_PASS_SCORE_MIN}
            max={PAPER_PASS_SCORE_MAX}
            step="1"
            bind:value={passScore}
            placeholder="例如：60"
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
          />
        </label>
      </div>

      <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
        <legend class="px-2 text-xs font-medium text-fg-muted">
          组卷策略（各题型数量，至少一个题型为正数；难度随后端题库随机抽取）
        </legend>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {#each PAPER_STRATEGY_TYPES as type}
            <label class="flex flex-col gap-1 text-xs text-fg-muted">
              {type}题数量
              <input
                type="number"
                min="0"
                step="1"
                bind:value={strategyCounts[type]}
                class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg"
              />
            </label>
          {/each}
        </div>
        <p class="mt-2 text-xs text-fg-dimmed">
          提交时组织为 generation_strategy 对象透传（{'{'}题型: 数量{'}'}，数量为 0 的题型不写入）
        </p>
      </fieldset>

      {#if isEdit}
        <div
          class="grid grid-cols-1 gap-3 rounded-lg border border-border bg-surface-alt px-4 py-3 text-sm sm:grid-cols-4"
        >
          <div>
            <div class="text-xs text-fg-dimmed">组卷结果题目数（只读）</div>
            <div class="mt-1 text-fg tabular-nums">{questionCount}</div>
          </div>
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
          {saving ? "提交中…" : isEdit ? "保存修改" : "创建试卷"}
        </button>
        <a
          href="/papers"
          class="rounded-lg border border-border bg-surface-alt px-5 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          取消
        </a>
      </div>
    </form>
  {/if}
</div>
