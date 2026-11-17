<script lang="ts">
  import { onMount } from "svelte";

  import { describeRequestError, t } from "../i18n";
  import { listSubjects, updateSubjectStatus, type SubjectRecord } from "../lib/api";

  const PAGE_SIZE = 20;

  const actionClass =
    "min-h-9 rounded-full border border-line px-3 text-[12px] text-muted transition-colors active:text-bone disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";
  const fieldClass =
    "min-h-10 w-full rounded-lg border border-line bg-ink px-3 text-[13px] text-bone placeholder:text-muted/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";

  let records = $state<SubjectRecord[]>([]);
  let total = $state(0);
  let offset = $state(0);
  let phase = $state<"loading" | "ready" | "failed">("loading");
  let error = $state("");
  let notice = $state("");
  let actionError = $state("");
  let actionID = $state("");
  let search = $state("");
  let statusFilter = $state("全部");
  let typeFilter = $state("全部");

  async function load(signal?: AbortSignal) {
    phase = "loading";
    error = "";
    try {
      const page = await listSubjects({ limit: PAGE_SIZE, offset }, signal);
      if (signal?.aborted) return;
      records = page.records;
      total = page.total;
      phase = "ready";
    } catch (cause) {
      if (signal?.aborted) return;
      error = describeRequestError(cause, $t, window.location.origin);
      phase = "failed";
    }
  }

  onMount(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  });

  const filtered = $derived.by(() => {
    const needle = search.trim().toLowerCase();
    return records.filter((record) => {
      if (statusFilter !== "全部" && record.status !== statusFilter) return false;
      if (typeFilter === "未选择" && record.user_type !== "") return false;
      if (typeFilter === "求职者" && record.user_type !== "job_seeker") return false;
      if (typeFilter === "招聘者" && record.user_type !== "recruiter") return false;
      if (!needle) return true;
      return (
        record.display_name.toLowerCase().includes(needle) ||
        record.identifier.toLowerCase().includes(needle)
      );
    });
  });

  const page = $derived(Math.floor(offset / PAGE_SIZE) + 1);

  async function toggle(record: SubjectRecord) {
    if (actionID) return;
    const next = record.status === "启用" ? "禁用" : "启用";
    const name = record.display_name || record.identifier || record.id;
    const message =
      next === "禁用"
        ? $t.admin.seekers.disableConfirm(name)
        : $t.admin.seekers.enableConfirm(name);
    if (!window.confirm(message)) return;

    actionID = record.id;
    actionError = "";
    notice = "";
    try {
      const updated = await updateSubjectStatus(record.id, next);
      records = records.map((item) => (item.id === updated.id ? updated : item));
      notice = next === "禁用" ? $t.admin.seekers.disabled : $t.admin.seekers.enabled;
    } catch (cause) {
      actionError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      actionID = "";
    }
  }

  function go(nextOffset: number) {
    offset = Math.max(0, nextOffset);
    void load();
  }
</script>

<section aria-labelledby="admin-seekers-heading">
  <h1 id="admin-seekers-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.admin.seekers.heading}
  </h1>
  <p class="mt-2 text-[13px] leading-5 text-muted">{$t.admin.seekers.hint}</p>

  <div class="mt-4 space-y-2">
    <input
      class={fieldClass}
      bind:value={search}
      type="search"
      placeholder={$t.admin.seekers.searchPlaceholder}
    />
    <div class="grid grid-cols-2 gap-2">
      <select class={fieldClass} bind:value={statusFilter}>
        {#each ["全部", "启用", "禁用"] as value (value)}
          <option value={value}>
            {value === "全部" ? $t.admin.seekers.statusAll : value}
          </option>
        {/each}
      </select>
      <select class={fieldClass} bind:value={typeFilter}>
        {#each ["全部", "求职者", "招聘者", "未选择"] as value (value)}
          <option value={value}>
            {value === "全部"
              ? $t.admin.seekers.userTypeAll
              : value === "求职者"
                ? $t.admin.seekers.jobSeeker
                : value === "招聘者"
                  ? $t.admin.seekers.recruiter
                  : $t.admin.seekers.unset}
          </option>
        {/each}
      </select>
    </div>
  </div>

  {#if notice}
    <p
      class="mt-3 rounded-xl border border-acid/20 bg-acid/[0.06] px-4 py-3 text-[13px] leading-5 text-acid"
      role="status"
    >
      {notice}
    </p>
  {/if}

  {#if phase === "loading"}
    <p class="mt-4 text-[13px] text-muted">{$t.admin.loading}</p>
  {:else if phase === "failed"}
    <p
      class="mt-4 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
      role="alert"
    >
      {error || $t.admin.failed}
    </p>
  {:else if records.length === 0}
    <p class="mt-4 text-[13px] text-muted">{$t.admin.seekers.empty}</p>
  {:else if filtered.length === 0}
    <p class="mt-4 text-[13px] text-muted">{$t.admin.seekers.noMatch}</p>
  {:else}
    <ul class="mt-4 space-y-2">
      {#each filtered as record (record.id)}
        <li class="rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
          <div class="flex items-start justify-between gap-3">
            <p class="min-w-0 truncate text-sm text-bone/90">
              {record.display_name || record.identifier || record.id}
            </p>
            <span
              class={`shrink-0 rounded-full border px-2 py-0.5 font-mono text-[10px] ${
                record.status === "启用"
                  ? "border-acid/30 text-acid"
                  : "border-amber-300/30 text-amber-300"
              }`}
            >
              {record.status}
            </span>
          </div>
          <p class="mt-1 truncate font-mono text-[11px] text-muted">{record.identifier}</p>
          <p class="mt-1 text-[11px] text-bone/70">
            {record.user_type === "job_seeker"
              ? $t.admin.seekers.jobSeeker
              : record.user_type === "recruiter"
                ? $t.admin.seekers.recruiter
                : $t.admin.seekers.unset}
          </p>
          {#if record.platform_admin}
            <p class="mt-1 text-[11px] text-acid">{$t.admin.seekers.platformAdmin}</p>
          {/if}
          <div class="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              onclick={() => toggle(record)}
              disabled={actionID !== ""}
              class={actionClass}
            >
              {record.status === "启用" ? $t.admin.seekers.disable : $t.admin.seekers.enable}
            </button>
          </div>
        </li>
      {/each}
    </ul>
  {/if}

  <div class="mt-4 flex items-center justify-between gap-3">
    <button
      type="button"
      onclick={() => go(offset - PAGE_SIZE)}
      disabled={offset === 0 || phase === "loading"}
      class={actionClass}
    >
      {$t.admin.seekers.prev}
    </button>
    <p class="text-[11px] text-muted">{$t.admin.seekers.page(page, total)}</p>
    <button
      type="button"
      onclick={() => go(offset + PAGE_SIZE)}
      disabled={offset + PAGE_SIZE >= total || phase === "loading"}
      class={actionClass}
    >
      {$t.admin.seekers.next}
    </button>
  </div>

  {#if actionError}
    <p class="mt-2 text-[11px] leading-5 text-amber-200" role="alert">
      {actionError || $t.admin.actionFailed}
    </p>
  {/if}
</section>
