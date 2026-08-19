<script lang="ts">
  import { onMount } from "svelte";
  import type {
    Question,
    QuestionInput,
    QuestionType,
  } from "../../lib/merit-client";
  import { importQuestions } from "../../lib/merit-client";
  import { QUESTION_TYPES } from "../../lib/question-options";
  import { apiUrl } from "../../lib/api";

  // 批量导入：粘贴 JSON 题目数组，经 importQuestions 客户端方法整批提交。
  // 后端对每一条做与新建相同的校验（answer 按题型形状），任一失败整批
  // 400 并带逐条 details（错误信息直接回显）。

  let payloadText = $state("");
  let importing = $state(false);
  let errorMessage = $state("");

  // 导入结果：imported 成功条数 + 完整记录列表
  let importedCount = $state(0);
  let importedRecords = $state<Question[]>([]);

  function formatDateTime(value: string): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function formatAnswer(question: Question): string {
    const answer = question.answer;
    return Array.isArray(answer) ? answer.join("、") : String(answer);
  }

  function fillExample(): void {
    payloadText = JSON.stringify(
      [
        {
          type: "单选",
          difficulty: 2,
          tags: ["客流", "服务"],
          content: "客流高峰时段，站台引导员的首要职责是？",
          options: ["维持秩序", "售卖商品", "清洁卫生", "设备巡检"],
          answer: "维持秩序",
          explanation: "客流高峰首要保障乘客安全与秩序。",
        },
        {
          type: "多选",
          difficulty: 3,
          tags: ["应急"],
          content: "发生大客流预警时，应采取的疏导措施包括？",
          options: ["限流", "加开备用通道", "暂停运营", "增派引导员"],
          answer: ["限流", "加开备用通道", "增派引导员"],
        },
        {
          type: "判断",
          difficulty: 1,
          tags: ["安全"],
          content: "恶劣天气下应加强站台巡视频次。",
          answer: "正确",
        },
        {
          type: "填空",
          difficulty: 2,
          tags: ["设备"],
          content: "自动扶梯紧急停止按钮位于扶梯________端。",
          answer: "两",
        },
      ],
      null,
      2,
    );
  }

  function validateInputs(inputs: QuestionInput[]): string {
    if (inputs.length === 0) return "题目数组不能为空";
    for (let i = 0; i < inputs.length; i += 1) {
      const input = inputs[i];
      if (typeof input !== "object" || input === null) return `第 ${i + 1} 条不是对象`;
      if (!QUESTION_TYPES.includes(input.type as QuestionType))
        return `第 ${i + 1} 条题型非法（须为 ${QUESTION_TYPES.join("/")}）`;
      if (typeof input.difficulty !== "number" || !Number.isInteger(input.difficulty))
        return `第 ${i + 1} 条难度须为整数`;
      if (input.difficulty < 1 || input.difficulty > 5)
        return `第 ${i + 1} 条难度须在 1–5 之间`;
      if (typeof input.content !== "string" || input.content.trim().length === 0)
        return `第 ${i + 1} 条题干必填`;
      const type = input.type as QuestionType;
      if (type === "单选" || type === "多选") {
        const options = Array.isArray(input.options) ? input.options : [];
        if (options.length < 2 || options.some((option) => !option.trim()))
          return `第 ${i + 1} 条（${type}）至少 2 个非空选项`;
      }
      if (type === "判断" && input.answer !== "正确" && input.answer !== "错误")
        return `第 ${i + 1} 条判断题答案须为「正确/错误」`;
      if (type === "填空" && typeof input.answer === "string" && !input.answer.trim())
        return `第 ${i + 1} 条填空题答案不能为空白`;
      if (type === "单选" && typeof input.answer !== "string")
        return `第 ${i + 1} 条单选题答案须为单值字符串`;
      if (type === "多选" && (!Array.isArray(input.answer) || input.answer.length === 0))
        return `第 ${i + 1} 条多选题答案须为非空字符串数组`;
    }
    return "";
  }

  async function submit(): Promise<void> {
    let inputs: QuestionInput[];
    try {
      const parsed = JSON.parse(payloadText);
      if (!Array.isArray(parsed)) {
        errorMessage = "负载必须是题目 JSON 数组";
        return;
      }
      inputs = parsed as QuestionInput[];
    } catch {
      errorMessage = "负载不是合法 JSON";
      return;
    }
    const validationError = validateInputs(inputs);
    if (validationError) {
      errorMessage = validationError;
      return;
    }
    importing = true;
    errorMessage = "";
    try {
      const result = await importQuestions(inputs);
      importedCount = result.imported;
      importedRecords = result.records;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      importedCount = 0;
      importedRecords = [];
    } finally {
      importing = false;
    }
  }

  // htmx 交互入口：工具栏「清空」按钮经 hx-on:click 事件绑定调用本函数
  function clearAll(): void {
    payloadText = "";
    importedCount = 0;
    importedRecords = [];
    errorMessage = "";
  }

  onMount(() => {
    const w = window as Window & { __meritClearImport?: () => void };
    w.__meritClearImport = clearAll;
    return () => {
      delete w.__meritClearImport;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/questions" class="transition hover:text-fg">题库管理</a>
      <span class="mx-1">/</span>
      <span>批量导入</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">批量导入题目</h1>
    <p class="mt-1 text-sm text-fg-muted">
      粘贴题目 JSON 数组，经 @pitchfork/shared/merit 客户端的 importQuestions 整批提交
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

  <section class="rounded-xl border border-border bg-surface p-6">
    <div class="mb-3 flex items-center justify-between">
      <p class="text-xs text-fg-muted">
        每一条与新建题目同构：type / difficulty / tags / content / options / answer /
        explanation / metadata（answer 按题型形状：单选单值、多选数组、判断「正确/错误」、填空字符串；判断/填空无 options）
      </p>
      <div class="flex items-center gap-2">
        <!-- htmx 交互：hx-on:click 事件绑定触发组件清空（不绕过客户端） -->
        <button
          type="button"
          hx-on:click="window.__meritClearImport && window.__meritClearImport()"
          class="rounded-md border border-border bg-surface-alt px-3 py-1.5 text-xs text-fg-muted transition hover:text-fg"
        >
          清空
        </button>
        <button
          type="button"
          onclick={fillExample}
          class="rounded-md border border-border bg-surface-alt px-3 py-1.5 text-xs text-fg-muted transition hover:text-fg"
        >
          填入示例
        </button>
      </div>
    </div>
    <textarea
      bind:value={payloadText}
      rows="14"
      placeholder='[&#10;  &#123; "type": "单选", "difficulty": 2, "tags": ["客流"], "content": "…", "options": ["…", "…"], "answer": "…" &#125;&#10;]'
      class="w-full rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-fg placeholder:text-fg-dimmed"
    ></textarea>
    <div class="mt-4 flex items-center gap-3">
      <button
        type="button"
        onclick={() => void submit()}
        disabled={importing}
        class="rounded-lg bg-accent px-5 py-2 text-sm font-medium text-white transition enabled:hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {importing ? "导入中…" : "开始导入"}
      </button>
      <a
        href="/questions"
        class="rounded-lg border border-border bg-surface-alt px-5 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
      >
        返回列表
      </a>
    </div>
  </section>

  {#if importedCount > 0}
    <section class="overflow-hidden rounded-xl border border-border bg-surface">
      <div class="border-b border-border px-4 py-3 text-sm font-medium text-fg-emphasis">
        导入成功：共 {importedCount} 条
      </div>
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead>
            <tr class="border-b border-border text-xs text-fg-dimmed">
              <th class="px-4 py-3 font-medium">#</th>
              <th class="px-4 py-3 font-medium">题干</th>
              <th class="px-4 py-3 font-medium">题型</th>
              <th class="px-4 py-3 font-medium">难度</th>
              <th class="px-4 py-3 font-medium">答案</th>
              <th class="px-4 py-3 font-medium">创建时间</th>
            </tr>
          </thead>
          <tbody>
            {#each importedRecords as question, index (question.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 text-fg-dimmed tabular-nums">{index + 1}</td>
                <td class="max-w-md px-4 py-3 font-medium text-fg-emphasis">
                  <span class="line-clamp-2">{question.content}</span>
                </td>
                <td class="px-4 py-3 text-fg-muted">{question.type}</td>
                <td class="px-4 py-3 text-fg-muted tabular-nums">{question.difficulty}</td>
                <td class="max-w-xs px-4 py-3 text-fg-muted">{formatAnswer(question)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(question.created_at)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </section>
  {/if}
</div>
