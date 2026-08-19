<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Chapter,
    ChapterInput,
    ContentBlock,
    ContentBlockType,
  } from "../../lib/merit-client";
  import { createChapter, updateChapter } from "../../lib/merit-client";
  import { CONTENT_BLOCK_TYPES } from "../../lib/merit-options";

  /**
   * 内容块编辑器模型：type 为固定选项（视频/图文/互动问答），
   * 其余字段按块类型编辑；保存时按类型组织为 ContentBlock 透传
   * （title/url/content/question/options/answer 等自由扩展字段）。
   */
  interface EditableBlock {
    type: ContentBlockType;
    title: string; // 视频-名称
    url: string; // 视频-地址
    content: string; // 图文-内容
    question: string; // 互动问答-问题
    options: string; // 互动问答-选项（每行一个）
    answer: string; // 互动问答-答案
  }

  interface Props {
    courseId: string;
    chapter?: Chapter | null;
    nextSortOrder?: number;
    onSave?: () => void;
    onCancel?: () => void;
  }

  let { courseId, chapter = null, nextSortOrder = 0, onSave, onCancel }: Props =
    $props();

  const isEdit = $derived(chapter !== null);
  const sortOrder = $derived(isEdit ? (chapter?.sort_order ?? 0) : nextSortOrder);

  let title = $state("");
  let blocks = $state<EditableBlock[]>([]);
  let quizConfigText = $state("");
  let saving = $state(false);
  let errorMessage = $state("");

  function stringField(value: unknown): string {
    return value === undefined || value === null ? "" : String(value);
  }

  function toEditable(block: ContentBlock): EditableBlock {
    return {
      type: block.type,
      title: stringField(block.title),
      url: stringField(block.url),
      content: stringField(block.content),
      question: stringField(block.question),
      options: Array.isArray(block.options)
        ? block.options.map(String).join("\n")
        : stringField(block.options),
      answer: stringField(block.answer),
    };
  }

  function addBlock(): void {
    blocks = [
      ...blocks,
      {
        type: "视频",
        title: "",
        url: "",
        content: "",
        question: "",
        options: "",
        answer: "",
      },
    ];
  }

  function removeBlock(index: number): void {
    blocks = blocks.filter((_, i) => i !== index);
  }

  /** 按块类型把编辑器模型组织为 ContentBlock（自由扩展字段透传） */
  function buildBlocks(): ContentBlock[] {
    return blocks.map((block) => {
      if (block.type === "视频") {
        const result: ContentBlock = { type: "视频" };
        if (block.title.trim()) result.title = block.title.trim();
        if (block.url.trim()) result.url = block.url.trim();
        return result;
      }
      if (block.type === "图文") {
        return { type: "图文", content: block.content };
      }
      const result: ContentBlock = { type: "互动问答" };
      if (block.question.trim()) result.question = block.question.trim();
      const options = block.options
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line !== "");
      if (options.length > 0) result.options = options;
      if (block.answer.trim()) result.answer = block.answer.trim();
      return result;
    });
  }

  async function save(): Promise<void> {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      errorMessage = "请填写章节标题";
      return;
    }
    for (const [index, block] of blocks.entries()) {
      if (block.type === "视频" && !block.url.trim()) {
        errorMessage = `第 ${index + 1} 个内容块（视频）缺少视频地址`;
        return;
      }
      if (block.type === "互动问答" && !block.question.trim()) {
        errorMessage = `第 ${index + 1} 个内容块（互动问答）缺少问题`;
        return;
      }
    }

    // 互动问答配置：JSON 文本编辑，空值按 null 提交
    let quizConfig: Record<string, unknown> | null = null;
    const rawConfig = quizConfigText.trim();
    if (rawConfig) {
      try {
        const parsed: unknown = JSON.parse(rawConfig);
        if (parsed === null || parsed === undefined) {
          quizConfig = null;
        } else if (typeof parsed === "object" && !Array.isArray(parsed)) {
          quizConfig = parsed as Record<string, unknown>;
        } else {
          errorMessage = "互动问答配置必须是 JSON 对象（或留空）";
          return;
        }
      } catch {
        errorMessage = "互动问答配置不是合法 JSON";
        return;
      }
    }

    const input: ChapterInput = {
      title: trimmedTitle,
      sort_order: sortOrder,
      blocks: buildBlocks(),
      quiz_config: quizConfig,
    };
    saving = true;
    errorMessage = "";
    try {
      if (isEdit && chapter) {
        await updateChapter(chapter.id, input);
      } else {
        await createChapter(courseId, input);
      }
      onSave?.();
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      saving = false;
    }
  }

  onMount(() => {
    if (chapter) {
      title = chapter.title;
      blocks = (chapter.blocks ?? []).map(toEditable);
      quizConfigText =
        chapter.quiz_config == null
          ? ""
          : JSON.stringify(chapter.quiz_config, null, 2);
    }
  });
</script>

<div class="space-y-4 rounded-xl border border-border bg-surface p-5">
  <header class="flex items-center justify-between">
    <h3 class="text-base font-semibold text-fg-emphasis">
      {isEdit ? "编辑章节" : "新建章节"}
      <span class="ml-2 text-xs font-normal text-fg-dimmed">
        sort_order = {sortOrder}
      </span>
    </h3>
    <button
      type="button"
      onclick={onCancel}
      class="text-sm text-fg-muted transition hover:text-fg"
    >
      取消
    </button>
  </header>

  {#if errorMessage}
    <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
      {errorMessage}
    </div>
  {/if}

  <label class="flex flex-col gap-1 text-xs text-fg-muted">
    章节标题
    <input
      type="text"
      bind:value={title}
      placeholder="例如：第一节 客流特征与应对原则"
      class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
    />
  </label>

  <fieldset class="rounded-lg border border-border bg-surface-alt/60 p-4">
    <legend class="px-2 text-xs font-medium text-fg-muted">内容块（视频 / 图文 / 互动问答）</legend>
    {#if blocks.length === 0}
      <p class="py-3 text-center text-xs text-fg-dimmed">尚未添加内容块</p>
    {:else}
      <div class="space-y-3">
        {#each blocks as block, index (index)}
          <div class="rounded-lg border border-border bg-surface p-4">
            <div class="flex flex-wrap items-center gap-3">
              <label class="flex items-center gap-1 text-xs text-fg-muted">
                块类型
                <select
                  bind:value={block.type}
                  class="rounded-md border border-border bg-surface-alt px-2 py-1.5 text-sm text-fg"
                >
                  {#each CONTENT_BLOCK_TYPES as type}
                    <option value={type}>{type}</option>
                  {/each}
                </select>
              </label>
              <span class="text-xs text-fg-dimmed">第 {index + 1} 块</span>
              <button
                type="button"
                onclick={() => removeBlock(index)}
                class="ml-auto rounded-md border border-red-500/40 bg-red-500/10 px-2 py-1 text-xs text-red-300 transition hover:bg-red-500/20"
              >
                删除该块
              </button>
            </div>

            {#if block.type === "视频"}
              <div class="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label class="flex flex-col gap-1 text-xs text-fg-muted">
                  视频名称
                  <input
                    type="text"
                    bind:value={block.title}
                    placeholder="例如：客流高峰疏导示范"
                    class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                  />
                </label>
                <label class="flex flex-col gap-1 text-xs text-fg-muted">
                  视频地址（必填）
                  <input
                    type="text"
                    bind:value={block.url}
                    placeholder="https://…"
                    class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                  />
                </label>
              </div>
            {:else if block.type === "图文"}
              <label class="mt-3 flex flex-col gap-1 text-xs text-fg-muted">
                图文内容
                <textarea
                  bind:value={block.content}
                  rows="3"
                  placeholder="图文正文内容"
                  class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                ></textarea>
              </label>
            {:else}
              <div class="mt-3 space-y-3">
                <label class="flex flex-col gap-1 text-xs text-fg-muted">
                  问题（必填）
                  <input
                    type="text"
                    bind:value={block.question}
                    placeholder="例如：客流高峰时段应优先采取的疏导措施是？"
                    class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                  />
                </label>
                <label class="flex flex-col gap-1 text-xs text-fg-muted">
                  选项（每行一个）
                  <textarea
                    bind:value={block.options}
                    rows="3"
                    placeholder={"选项 A\n选项 B\n选项 C"}
                    class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                  ></textarea>
                </label>
                <label class="flex flex-col gap-1 text-xs text-fg-muted">
                  答案
                  <input
                    type="text"
                    bind:value={block.answer}
                    placeholder="例如：选项 A"
                    class="rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
                  />
                </label>
              </div>
            {/if}
          </div>
        {/each}
      </div>
    {/if}
    <button
      type="button"
      onclick={addBlock}
      class="mt-3 rounded-md border border-border bg-surface px-3 py-1.5 text-xs font-medium text-fg-muted transition hover:text-fg"
    >
      + 添加内容块
    </button>
  </fieldset>

  <label class="flex flex-col gap-1 text-xs text-fg-muted">
    互动问答配置（quiz_config，JSON 对象；留空表示无配置）
    <textarea
      bind:value={quizConfigText}
      rows="5"
      placeholder={'{\n  "mode": "练习",\n  "passing_score": 80\n}'}
      class="rounded-md border border-border bg-surface-alt px-3 py-2 font-mono text-xs text-fg placeholder:text-fg-dimmed"
    ></textarea>
  </label>

  <div class="flex items-center gap-3">
    <button
      type="button"
      onclick={() => void save()}
      disabled={saving}
      class="rounded-lg bg-accent px-5 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
    >
      {saving ? "提交中…" : isEdit ? "保存修改" : "创建章节"}
    </button>
    <button
      type="button"
      onclick={onCancel}
      class="rounded-lg border border-border bg-surface-alt px-5 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
    >
      取消
    </button>
  </div>
</div>
