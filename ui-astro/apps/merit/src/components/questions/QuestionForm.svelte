<script lang="ts">
  import { onMount } from "svelte";
  import type {
    JudgmentAnswer,
    QuestionInput,
    QuestionType,
  } from "../../lib/merit-client";
  import { createQuestion, getQuestion, updateQuestion } from "../../lib/merit-client";
  import {
    JUDGMENT_ANSWERS,
    QUESTION_DIFFICULTIES,
    QUESTION_OPTIONS_MIN,
    QUESTION_TYPES,
  } from "../../lib/question-options";
  import { apiUrl } from "../../lib/api";

  interface Props {
    mode: "create" | "edit";
    questionId?: string;
  }

  let { mode, questionId = "" }: Props = $props();

  const isEdit = $derived(mode === "edit");

  // 题型（固定 4 值）与难度（1–5 整数）
  let type = $state<QuestionType>("单选");
  let difficulty = $state<number>(3);

  // 标签：可编辑字符串数组（输入框逗号分隔，提交时解析）
  let tagsText = $state("");

  // 题干必填；解析可选
  let content = $state("");
  let explanation = $state("");

  // 选项：仅单选/多选提供 ≥2 项可增删编辑；判断/填空不提供
  let options = $state<string[]>(["", ""]);

  // 答案按题型形状分别保存（切换题型互不覆盖）：
  // 单选 → 单值 string（须为选项之一）；多选 → string[]（选项非空子集）；
  // 判断 → 正确/错误 二选一；填空 → 非空白 string
  let answerSingle = $state("");
  let answerMultiple = $state<string[]>([]);
  let answerJudgment = $state<JudgmentAnswer>("正确");
  let answerFill = $state("");

  // metadata：可选 JSON 扩展字段，空 → 提交 {}；提供则组织为对象透传
  let metadataText = $state("");

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

  function parseTags(value: string): string[] {
    return value
      .split(/[,，]/)
      .map((tag) => tag.trim())
      .filter((tag) => tag.length > 0);
  }

  async function loadQuestion(id: string): Promise<void> {
    try {
      const question = await getQuestion(id);
      type = question.type;
      difficulty = question.difficulty;
      tagsText = question.tags.join("、");
      content = question.content;
      options = question.options.length > 0 ? [...question.options] : ["", ""];
      explanation = question.explanation;
      // answer 按题型形状回填
      if (question.type === "单选") answerSingle = stringValue(question.answer);
      else if (question.type === "多选")
        answerMultiple = Array.isArray(question.answer) ? [...question.answer] : [];
      else if (question.type === "判断") answerJudgment = stringValue(question.answer) as JudgmentAnswer;
      else answerFill = stringValue(question.answer);
      // metadata 对象序列化回显（无扩展字段 → 空文本）
      const meta = question.metadata ?? {};
      metadataText = Object.keys(meta).length > 0 ? JSON.stringify(meta, null, 2) : "";
      // 服务端管理字段只读回显
      createdBy = question.created_by;
      createdAt = question.created_at;
      updatedAt = question.updated_at;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
    }
  }

  function addOption(): void {
    options = [...options, ""];
  }

  function removeOption(index: number): void {
    options = options.filter((_, i) => i !== index);
  }

  function optionAnswer(option: string): string {
    return option.trim();
  }

  function isOptionSelected(option: string): boolean {
    return answerMultiple.includes(option);
  }

  function toggleMultipleAnswer(option: string): void {
    answerMultiple = isOptionSelected(option)
      ? answerMultiple.filter((item) => item !== option)
      : [...answerMultiple, option];
  }

  async function submit(): Promise<void> {
    const trimmedContent = content.trim();
    if (!trimmedContent) {
      errorMessage = "请填写题干";
      return;
    }
    // 选项编辑仅对单选/多选提供；判断/填空不提供选项编辑
    const hasOptions = type === "单选" || type === "多选";
    const normalizedOptions = options.map(optionAnswer);
    if (hasOptions) {
      if (normalizedOptions.length < QUESTION_OPTIONS_MIN) {
        errorMessage = `${type}题至少需要 ${QUESTION_OPTIONS_MIN} 个选项`;
        return;
      }
      if (normalizedOptions.some((option) => option.length === 0)) {
        errorMessage = "选项内容不能为空";
        return;
      }
    }
    // answer 按题型形状组装
    let answer: string | string[];
    if (type === "单选") {
      if (!answerSingle) {
        errorMessage = "请选择单选题答案（须为选项之一）";
        return;
      }
      if (!normalizedOptions.includes(answerSingle)) {
        errorMessage = "单选题答案必须是选项之一";
        return;
      }
      answer = answerSingle;
    } else if (type === "多选") {
      if (answerMultiple.length === 0) {
        errorMessage = "请至少选择一个多选题答案";
        return;
      }
      const invalid = answerMultiple.filter((item) => !normalizedOptions.includes(item));
      if (invalid.length > 0) {
        errorMessage = "多选题答案必须是选项的子集";
        return;
      }
      answer = [...answerMultiple];
    } else if (type === "判断") {
      answer = answerJudgment;
    } else {
      if (!answerFill.trim()) {
        errorMessage = "请填写填空题答案（非空白字符串）";
        return;
      }
      answer = answerFill.trim();
    }
    // metadata：无扩展字段提交 {}，提供则组织为对象透传
    let metadata: Record<string, unknown> = {};
    if (metadataText.trim()) {
      try {
        const parsed = JSON.parse(metadataText);
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
          errorMessage = "metadata 扩展字段必须是 JSON 对象";
          return;
        }
        metadata = parsed as Record<string, unknown>;
      } catch {
        errorMessage = "metadata 扩展字段不是合法 JSON";
        return;
      }
    }
    // created_by/created_at/updated_at 为服务端管理字段：不编辑、不提交
    const input: QuestionInput = {
      type,
      difficulty,
      tags: parseTags(tagsText),
      content: trimmedContent,
      answer,
      explanation: explanation.trim(),
      metadata,
    };
    if (hasOptions) input.options = normalizedOptions;
    saving = true;
    errorMessage = "";
    try {
      const question = isEdit
        ? await updateQuestion(resolvedId, input)
        : await createQuestion(input);
      window.location.assign(`/questions?created=${encodeURIComponent(question.id)}`);
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      saving = false;
    }
  }

  onMount(() => {
    if (mode === "edit") {
      const id =
        questionId || new URLSearchParams(window.location.search).get("id") || "";
      if (!id) {
        errorMessage = "缺少题目 ID（URL 需携带 ?id=…）";
        return;
      }
      resolvedId = id;
      void loadQuestion(id);
    }
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/questions" class="transition hover:text-fg">题库管理</a>
      <span class="mx-1">/</span>
      <span>{isEdit ? "编辑题目" : "新建题目"}</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">
      {isEdit ? "编辑题目" : "新建题目"}
    </h1>
    <p class="mt-1 text-sm text-fg-muted">
      经 @pitchfork/shared/merit 客户端提交（{isEdit ? "updateQuestion" : "createQuestion"}）；答案形状随题型切换
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
      等待题目数据加载…
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
        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          题型（固定 4 项）
          <select
            bind:value={type}
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
          >
            {#each QUESTION_TYPES as option}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          难度（1–5 整数）
          <select
            bind:value={difficulty}
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg"
          >
            {#each QUESTION_DIFFICULTIES as option}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </label>

        <label class="flex flex-col gap-1 text-xs text-fg-muted">
          标签（可编辑字符串数组）
          <input
            type="text"
            bind:value={tagsText}
            placeholder="多个标签用逗号分隔"
            class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
          />
        </label>
      </div>

      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        题干（必填）
        <textarea
          bind:value={content}
          rows="3"
          placeholder="请输入题目内容"
          class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        ></textarea>
      </label>

      <!-- 选项编辑：仅单选/多选提供（≥2 项可增删）；判断/填空不提供 -->
      {#if type === "单选" || type === "多选"}
        <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
          <legend class="px-2 text-xs font-medium text-fg-muted">
            选项（至少 {QUESTION_OPTIONS_MIN} 项，可增删）
          </legend>
          <div class="space-y-2">
            {#each options as option, index (index)}
              <div class="flex items-center gap-2">
                <span class="w-6 shrink-0 text-center text-sm text-fg-dimmed tabular-nums">
                  {String.fromCharCode(65 + index)}
                </span>
                <input
                  type="text"
                  bind:value={options[index]}
                  placeholder={`选项 ${index + 1} 内容`}
                  class="flex-1 rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                />
                <button
                  type="button"
                  onclick={() => removeOption(index)}
                  disabled={options.length <= QUESTION_OPTIONS_MIN}
                  class="shrink-0 rounded-md border border-red-500/40 bg-red-500/10 px-2 py-1 text-xs text-red-300 transition enabled:hover:bg-red-500/20 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  删除
                </button>
              </div>
            {/each}
          </div>
          <button
            type="button"
            onclick={addOption}
            class="mt-3 rounded-md border border-border bg-surface px-3 py-1.5 text-xs text-fg-muted transition hover:text-fg"
          >
            + 添加选项
          </button>
        </fieldset>
      {/if}

      <!-- 答案编辑：形状随题型切换 -->
      <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
        <legend class="px-2 text-xs font-medium text-fg-muted">答案</legend>

        {#if type === "单选"}
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            单选题答案（单值，须为选项之一）
            <select
              bind:value={answerSingle}
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg"
            >
              <option value="">请选择…</option>
              {#each options as option}
                <option value={option.trim()}>{option.trim() || "（空选项）"}</option>
              {/each}
            </select>
          </label>
        {:else if type === "多选"}
          <div class="flex flex-col gap-2 text-xs text-fg-muted">
            <span>多选题答案（选项非空子集）</span>
            {#each options as option}
              <label class="flex items-center gap-2 text-sm text-fg">
                <input
                  type="checkbox"
                  checked={isOptionSelected(option.trim())}
                  onchange={() => toggleMultipleAnswer(option.trim())}
                  class="accent-accent"
                />
                <span>{option.trim() || "（空选项）"}</span>
              </label>
            {/each}
          </div>
        {:else if type === "判断"}
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            判断题答案（二选一：正确 / 错误）
            <select
              bind:value={answerJudgment}
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg"
            >
              {#each JUDGMENT_ANSWERS as option}
                <option value={option}>{option}</option>
              {/each}
            </select>
          </label>
        {:else}
          <label class="flex flex-col gap-1 text-xs text-fg-muted">
            填空题答案（非空白字符串）
            <input
              type="text"
              bind:value={answerFill}
              placeholder="请输入填空答案"
              class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
            />
          </label>
        {/if}
      </fieldset>

      <label class="flex flex-col gap-1 text-xs text-fg-muted">
        解析（可选）
        <textarea
          bind:value={explanation}
          rows="2"
          placeholder="答案解析（可选）"
          class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
        ></textarea>
      </label>

      <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
        <legend class="px-2 text-xs font-medium text-fg-muted">
          metadata · 扩展字段（JSONB，可选）
        </legend>
        <textarea
          bind:value={metadataText}
          rows="3"
          placeholder={'{"source": "教材第三章", "author": "张老师"}'}
          class="w-full rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-fg placeholder:text-fg-dimmed"
        ></textarea>
        <p class="mt-2 text-xs text-fg-dimmed">
          留空提交 {'{'}{'}'}；填写则解析为 JSON 对象原样透传（合法 JSON 对象校验）
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
          {saving ? "提交中…" : isEdit ? "保存修改" : "创建题目"}
        </button>
        <a
          href="/questions"
          class="rounded-lg border border-border bg-surface-alt px-5 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
        >
          取消
        </a>
      </div>
    </form>
  {/if}
</div>
