<script lang="ts">
  import { onMount } from "svelte";
  import type {
    ExamAnswers,
    ExamRecord,
    ExamRecordQuestion,
    Paper,
  } from "../../lib/merit-client";
  import {
    getExamRecord,
    listExamRecords,
    listPapers,
    startExam,
    submitExam,
  } from "../../lib/merit-client";
  import { apiUrl } from "../../lib/api";

  /** 试卷一次拉取上限 */
  const PAPER_LIST_LIMIT = 200;

  /** 考核记录列表一次拉取上限 */
  const RECORD_LIST_LIMIT = 50;

  /** 演示学员身份缺省值：与任务列表/课程学习页口径统一（employee_id 查询参数，缺省 u-001） */
  const DEFAULT_EMPLOYEE_ID = "u-001";

  /** 判断题型固定选项（后端判断题 options 为 []，展示固定 正确/错误） */
  const JUDGMENT_OPTIONS = ["正确", "错误"] as const;

  let employeeId = $state(DEFAULT_EMPLOYEE_ID);

  /** 试卷列表（开考入口） */
  let papers = $state<Paper[]>([]);
  /** 当前考核记录：null=未开考；end_time 为空=作答中；end_time 非空=已交卷（结果） */
  let current = $state<ExamRecord | null>(null);
  /** 逐题作答收集的答案（键为快照题目 id） */
  let answers = $state<ExamAnswers>({});
  /** 结果视图是否来自历史记录查看（是则隐藏「重新开考」按钮） */
  let viewingHistory = $state(false);

  /** 该学员的历史考核记录（结果查询入口） */
  let records = $state<ExamRecord[]>([]);

  let loading = $state(false);
  let starting = $state(false);
  let submitting = $state(false);
  let errorMessage = $state("");
  let historyError = $state("");

  const paperMap = $derived(new Map(papers.map((paper) => [paper.id, paper])));
  const inExam = $derived(current !== null && current.end_time === null);

  function formatDateTime(value: string | null): string {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  function passedLabel(record: ExamRecord): string {
    if (record.passed === true) return "通过";
    if (record.passed === false) return "未通过";
    return "待交卷";
  }

  function passedBadgeClass(record: ExamRecord): string {
    if (record.passed === true) {
      return "rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300";
    }
    if (record.passed === false) {
      return "rounded-full border border-red-500/40 bg-red-500/10 px-2 py-0.5 text-xs text-red-300";
    }
    return "rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2 py-0.5 text-xs text-zinc-300";
  }

  function paperTitle(id: string): string {
    return paperMap.get(id)?.title || id;
  }

  /** 单值答案（单选/判断/填空） */
  function textAnswer(questionId: string): string {
    const value = answers[questionId];
    return typeof value === "string" ? value : "";
  }

  /** 多选答案 */
  function multiAnswer(questionId: string): string[] {
    const value = answers[questionId];
    return Array.isArray(value) ? value : [];
  }

  /** 题目选项：判断题型固定 正确/错误，其余按快照 options */
  function questionOptions(question: ExamRecordQuestion): string[] {
    if (question.type === "判断") return [...JUDGMENT_OPTIONS];
    return question.options;
  }

  function setTextAnswer(questionId: string, value: string): void {
    answers = { ...answers, [questionId]: value };
  }

  function toggleMulti(questionId: string, option: string): void {
    const selected = multiAnswer(questionId);
    const next = selected.includes(option)
      ? selected.filter((item) => item !== option)
      : [...selected, option];
    answers = { ...answers, [questionId]: next };
  }

  /**
   * 交卷载荷组装（契约字段一致）：未作答题目不提交（后端漏答计 0 分，
   * 空串/空数组会被判 400，因此仅收集非空答案）。
   */
  function collectAnswers(): ExamAnswers {
    const collected: ExamAnswers = {};
    for (const [id, value] of Object.entries(answers)) {
      if (Array.isArray(value)) {
        if (value.length > 0) collected[id] = value;
      } else if (value.trim() !== "") {
        collected[id] = value;
      }
    }
    return collected;
  }

  /** 结果回顾：未作答返回 null，已作答返回与快照答案是否一致（判分以服务端为准） */
  function reviewResult(question: ExamRecordQuestion): boolean | null {
    const submitted = answers[question.id];
    if (submitted === undefined) return null;
    const normalize = (value: string | string[]): string[] =>
      Array.isArray(value) ? [...value].sort() : [value];
    return JSON.stringify(normalize(submitted)) === JSON.stringify(normalize(question.answer));
  }

  function reviewText(question: ExamRecordQuestion): string {
    const value = answers[question.id];
    if (value === undefined) return "未作答";
    return Array.isArray(value) ? value.join("、") : value;
  }

  /** 开考：服务端生成 id/start_time 并快照试卷（题目数据来自快照 answers_snapshot） */
  async function beginExam(paperId: string): Promise<void> {
    starting = true;
    errorMessage = "";
    try {
      current = await startExam({
        employee_id: employeeId.trim() || DEFAULT_EMPLOYEE_ID,
        paper_id: paperId,
      });
      answers = {};
      viewingHistory = false;
    } catch (err) {
      errorMessage = `开考失败：${err instanceof Error ? err.message : String(err)}`;
    } finally {
      starting = false;
    }
  }

  /** 交卷：按快照判分，成绩/通过结果以服务端返回为准 */
  async function submit(): Promise<void> {
    if (!current || current.end_time !== null) return;
    submitting = true;
    errorMessage = "";
    try {
      current = await submitExam(current.id, collectAnswers());
    } catch (err) {
      // 失败路径：未开考交卷/重复交卷等 400/404 均在此展示错误提示，不白屏
      errorMessage = `交卷失败：${err instanceof Error ? err.message : String(err)}`;
    } finally {
      submitting = false;
    }
  }

  /** 结果查询：查看历史考核记录详情（getExamRecord 返回成绩/通过与快照） */
  async function viewRecord(recordId: string): Promise<void> {
    errorMessage = "";
    try {
      const record = await getExamRecord(recordId);
      current = record;
      // 以该记录快照题目重建答案视图（已交卷记录没有逐题答案，仅展示回顾）
      answers = {};
      for (const question of record.answers_snapshot.questions) {
        answers = { ...answers, [question.id]: "" };
      }
      viewingHistory = true;
    } catch (err) {
      errorMessage = `考核记录查询失败：${err instanceof Error ? err.message : String(err)}`;
    }
  }

  /** 重新开考：回到试卷选择 */
  function resetExam(): void {
    current = null;
    answers = {};
    viewingHistory = false;
    errorMessage = "";
  }

  async function loadHistory(): Promise<void> {
    historyError = "";
    try {
      const page = await listExamRecords({
        employee_id: employeeId.trim() || DEFAULT_EMPLOYEE_ID,
        limit: RECORD_LIST_LIMIT,
      });
      records = page.records;
    } catch (err) {
      records = [];
      historyError = `考核记录加载失败：${err instanceof Error ? err.message : String(err)}`;
    }
  }

  async function load(): Promise<void> {
    loading = true;
    errorMessage = "";
    try {
      const page = await listPapers({ limit: PAPER_LIST_LIMIT });
      papers = page.records;
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err);
      papers = [];
    } finally {
      loading = false;
    }
    void loadHistory();
  }

  /** htmx 交互入口：「刷新」按钮经 hx-on:click 绑定调用，数据仍由客户端方法拉取 */
  function refresh(): void {
    void load();
  }

  onMount(() => {
    // 身份口径：employee_id 查询参数，缺省演示学员 u-001；paper_id 可选预选
    const params = new URLSearchParams(window.location.search);
    const fromQuery = params.get("employee_id");
    if (fromQuery) employeeId = fromQuery;
    const w = window as Window & { __meritReloadExam?: () => void };
    w.__meritReloadExam = refresh;
    void load();
    return () => {
      delete w.__meritReloadExam;
    };
  });
</script>

<div class="space-y-6">
  <header>
    <p class="text-xs text-fg-dimmed">
      <a href="/" class="transition hover:text-fg">首页</a>
      <span class="mx-1">/</span>
      <a href={`/my-training/?employee_id=${encodeURIComponent(employeeId)}`} class="transition hover:text-fg">
        我的培训任务
      </a>
      <span class="mx-1">/</span>
      <span>在线考核</span>
    </p>
    <h1 class="mt-1 text-2xl font-bold tracking-tight text-fg-emphasis">在线考核</h1>
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
      <!-- htmx 交互：hx-on:click 事件绑定触发组件刷新（数据经客户端方法拉取） -->
      <button
        type="button"
        hx-on:click="window.__meritReloadExam && window.__meritReloadExam()"
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

  {#if !current}
    <!-- 开考入口：试卷列表 -->
    <section class="space-y-3">
      <div class="flex items-center justify-between">
        <h2 class="text-base font-semibold text-fg-emphasis">选择试卷开考</h2>
      </div>
      {#if loading}
        <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
          加载中…
        </div>
      {:else if papers.length === 0}
        <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
          暂无可用的试卷（空态）
        </div>
      {:else}
        <div class="grid gap-4 md:grid-cols-2">
          {#each papers as paper (paper.id)}
            <div class="flex flex-col rounded-xl border border-border bg-surface p-4 transition hover:border-accent/50">
              <div class="text-base font-semibold text-fg-emphasis">{paper.title}</div>
              <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-dimmed">
                <span>题量：{paper.questions.length} 题</span>
                <span>时长：{paper.duration_minutes} 分钟</span>
                <span>及格分：{paper.pass_score} 分</span>
              </div>
              <div class="mt-4">
                <button
                  type="button"
                  onclick={() => void beginExam(paper.id)}
                  disabled={starting}
                  class="w-full rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {starting ? "开考中…" : "开始考核"}
                </button>
              </div>
            </div>
          {/each}
        </div>
      {/if}
    </section>
  {:else if inExam}
    <!-- 逐题作答 -->
    <section class="space-y-4">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-xl border border-border bg-surface p-4">
        <div>
          <div class="text-xs text-fg-dimmed">试卷</div>
          <div class="mt-0.5 text-base font-semibold text-fg-emphasis">
            {paperTitle(current.paper_id)}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">开考时间</div>
          <div class="mt-0.5 text-sm text-fg-muted">{formatDateTime(current.start_time)}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">及格分</div>
          <div class="mt-0.5 text-sm text-fg-muted">
            {current.answers_snapshot.pass_score} 分（每题 1 分，共
            {current.answers_snapshot.questions.length} 题）
          </div>
        </div>
        <div class="ml-auto text-sm text-fg-muted">
          已作答：{Object.keys(collectAnswers()).length} / {current.answers_snapshot.questions.length} 题
        </div>
      </div>

      {#each current.answers_snapshot.questions as question, index (question.id)}
        <section class="rounded-xl border border-border bg-surface p-4">
          <div class="flex items-start gap-2">
            <span
              class="mt-0.5 shrink-0 rounded-full border border-accent/40 bg-accent-subtle px-2 py-0.5 text-xs text-accent"
            >
              {question.type}
            </span>
            <div class="text-sm leading-relaxed text-fg-emphasis">
              {index + 1}. {question.content}
            </div>
          </div>

          <div class="mt-3 space-y-2">
            {#if question.type === "填空"}
              <input
                type="text"
                value={textAnswer(question.id)}
                placeholder="请输入答案"
                oninput={(event) => setTextAnswer(question.id, event.currentTarget.value)}
                class="w-full rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg placeholder:text-fg-dimmed"
              />
            {:else if question.type === "多选"}
              {#each questionOptions(question) as option (option)}
                <label
                  class="flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 text-sm text-fg transition hover:border-accent/60"
                >
                  <input
                    type="checkbox"
                    checked={multiAnswer(question.id).includes(option)}
                    onchange={() => toggleMulti(question.id, option)}
                  />
                  <span>{option}</span>
                </label>
              {/each}
            {:else}
              {#each questionOptions(question) as option (option)}
                <label
                  class="flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 text-sm text-fg transition hover:border-accent/60"
                >
                  <input
                    type="radio"
                    name={question.id}
                    value={option}
                    checked={textAnswer(question.id) === option}
                    onchange={() => setTextAnswer(question.id, option)}
                  />
                  <span>{option}</span>
                </label>
              {/each}
            {/if}
          </div>
        </section>
      {/each}

      <div class="sticky bottom-4 flex justify-end">
        <button
          type="button"
          onclick={() => void submit()}
          disabled={submitting}
          class="rounded-lg bg-accent px-6 py-2.5 text-sm font-medium text-white transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {submitting ? "交卷中…" : "交卷"}
        </button>
      </div>
    </section>
  {:else}
    <!-- 交卷后成绩与通过结果展示（以服务端返回为准） -->
    {@const record = current}
    <section class="rounded-xl border border-border bg-surface p-6">
      <div class="flex flex-wrap items-center gap-x-10 gap-y-4">
        <div>
          <div class="text-xs text-fg-dimmed">试卷</div>
          <div class="mt-0.5 text-lg font-semibold text-fg-emphasis">{paperTitle(record.paper_id)}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">成绩</div>
          <div class="mt-0.5 text-3xl font-bold tabular-nums text-fg-emphasis">
            {record.score}
            <span class="text-base font-normal text-fg-dimmed"> / {record.answers_snapshot.questions.length} 分</span>
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">结果</div>
          <div class="mt-1">
            <span class={passedBadgeClass(record)}>{passedLabel(record)}</span>
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">及格分</div>
          <div class="mt-0.5 text-sm text-fg-muted">{record.answers_snapshot.pass_score} 分</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">开考时间</div>
          <div class="mt-0.5 text-sm text-fg-muted">{formatDateTime(record.start_time)}</div>
        </div>
        <div>
          <div class="text-xs text-fg-dimmed">交卷时间</div>
          <div class="mt-0.5 text-sm text-fg-muted">{formatDateTime(record.end_time)}</div>
        </div>
      </div>

      {#if !viewingHistory}
        <div class="mt-5 flex flex-wrap gap-2 border-t border-border pt-4">
          <button
            type="button"
            onclick={resetExam}
            class="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white transition hover:opacity-90"
          >
            重新开考
          </button>
          <a
            href={`/my-training/?employee_id=${encodeURIComponent(employeeId)}`}
            class="rounded-lg border border-border bg-surface-alt px-4 py-2 text-sm font-medium text-fg-muted transition hover:text-fg"
          >
            返回我的培训任务
          </a>
        </div>
      {/if}
    </section>

    <!-- 逐题回顾：你的答案 vs 快照正确答案（成绩以服务端为准） -->
    <section class="space-y-3">
      <h2 class="text-base font-semibold text-fg-emphasis">答题回顾</h2>
      {#each record.answers_snapshot.questions as question, index (question.id)}
        {@const result = reviewResult(question)}
        <div class="rounded-xl border border-border bg-surface p-4">
          <div class="flex items-start gap-2">
            <span
              class={
                result === true
                  ? "mt-0.5 shrink-0 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-300"
                  : result === false
                    ? "mt-0.5 shrink-0 rounded-full border border-red-500/40 bg-red-500/10 px-2 py-0.5 text-xs text-red-300"
                    : "mt-0.5 shrink-0 rounded-full border border-zinc-500/40 bg-zinc-500/10 px-2 py-0.5 text-xs text-zinc-300"
              }
            >
              {result === true ? "正确" : result === false ? "错误" : "未作答"}
            </span>
            <span
              class="mt-0.5 shrink-0 rounded-full border border-accent/40 bg-accent-subtle px-2 py-0.5 text-xs text-accent"
            >
              {question.type}
            </span>
            <div class="text-sm leading-relaxed text-fg-emphasis">
              {index + 1}. {question.content}
            </div>
          </div>
          <div class="mt-2 grid gap-1 text-xs text-fg-muted sm:grid-cols-2">
            <div>你的答案：{reviewText(question) || "—"}</div>
            <div>
              正确答案：
              {Array.isArray(question.answer) ? question.answer.join("、") : question.answer || "—"}
            </div>
          </div>
        </div>
      {/each}
    </section>
  {/if}

  <!-- 历史考核记录（结果查询） -->
  <section class="space-y-3">
    <h2 class="text-base font-semibold text-fg-emphasis">我的考核记录</h2>
    {#if historyError}
      <div class="rounded-lg border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-300">
        {historyError}
      </div>
    {:else if records.length === 0}
      <div class="rounded-xl border border-border bg-surface px-4 py-10 text-center text-sm text-fg-dimmed">
        暂无考核记录
      </div>
    {:else}
      <div class="overflow-x-auto rounded-xl border border-border bg-surface">
        <table class="w-full text-left text-sm">
          <thead>
            <tr class="border-b border-border text-xs text-fg-dimmed">
              <th class="px-4 py-3 font-medium">试卷</th>
              <th class="px-4 py-3 font-medium">开考时间</th>
              <th class="px-4 py-3 font-medium">交卷时间</th>
              <th class="px-4 py-3 font-medium">成绩</th>
              <th class="px-4 py-3 font-medium">结果</th>
              <th class="px-4 py-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {#each records as record (record.id)}
              <tr class="border-b border-border/60 last:border-b-0 transition hover:bg-surface-alt">
                <td class="px-4 py-3 text-fg-emphasis">{paperTitle(record.paper_id)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(record.start_time)}</td>
                <td class="px-4 py-3 text-fg-muted">{formatDateTime(record.end_time)}</td>
                <td class="px-4 py-3 tabular-nums text-fg-muted">
                  {record.score === null ? "—" : `${record.score} 分`}
                </td>
                <td class="px-4 py-3">
                  <span class={passedBadgeClass(record)}>{passedLabel(record)}</span>
                </td>
                <td class="px-4 py-3 text-right">
                  {#if record.end_time !== null}
                    <button
                      type="button"
                      onclick={() => void viewRecord(record.id)}
                      class="rounded-md border border-border bg-surface-alt px-3 py-1.5 text-xs font-medium text-fg-muted transition hover:text-fg"
                    >
                      查看结果
                    </button>
                  {:else}
                    <span class="text-xs text-fg-dimmed">作答中</span>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </section>
</div>
