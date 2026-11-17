<script lang="ts">
  import { onMount } from "svelte";

  import { describeRequestError, t } from "../i18n";
  import {
    listOrganizations,
    organizationLicenseURL,
    reviewOrganization,
    type OrganizationRecord,
  } from "../lib/api";

  const STATUSES = ["待审核", "已认证", "已驳回", "已停用"];

  const actionClass =
    "min-h-9 rounded-full border border-line px-3 text-[12px] text-muted transition-colors active:text-bone disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";
  const primaryClass =
    "min-h-9 rounded-full border border-acid/30 px-3 text-[12px] text-acid transition-colors active:bg-acid/10 disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";
  const fieldClass =
    "min-h-10 w-full rounded-lg border border-line bg-ink px-3 text-[13px] text-bone placeholder:text-muted/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";

  let records = $state<OrganizationRecord[]>([]);
  let phase = $state<"loading" | "ready" | "failed">("loading");
  let error = $state("");
  let notice = $state("");
  let actionError = $state("");
  let actionID = $state("");
  let actionNext = $state<"已认证" | "已驳回" | "已停用" | "">("");
  let rejectingID = $state("");
  let reason = $state("");
  let disablingID = $state("");
  let disableReason = $state("");

  let query = $state("");
  let status = $state("待审核");

  async function load(signal?: AbortSignal) {
    phase = "loading";
    error = "";
    try {
      const page = await listOrganizations(
        {
          status,
          q: query.trim() || undefined,
          limit: 50,
        },
        signal,
      );
      if (signal?.aborted) return;
      records = page.records;
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

  function search(event: SubmitEvent) {
    event.preventDefault();
    void load();
  }

  function beginReject(record: OrganizationRecord) {
    rejectingID = record.id;
    reason = "";
    disablingID = "";
    actionError = "";
  }

  function beginDisable(record: OrganizationRecord) {
    disablingID = record.id;
    disableReason = "";
    rejectingID = "";
    actionError = "";
  }

  function cancelInline() {
    rejectingID = "";
    disablingID = "";
    reason = "";
    disableReason = "";
  }

  async function review(
    record: OrganizationRecord,
    next: "已认证" | "已驳回" | "已停用",
  ) {
    if (actionID) return;
    if (next === "已驳回" && !reason.trim()) {
      actionError = $t.admin.reasonRequired;
      return;
    }
    actionID = record.id;
    actionNext = next;
    actionError = "";
    notice = "";
    try {
      await reviewOrganization(record.id, {
        status: next,
        reason: next === "已驳回" ? reason.trim() : next === "已停用" ? disableReason.trim() : "",
      });
      cancelInline();
      notice =
        next === "已认证"
          ? $t.admin.approved
          : next === "已驳回"
            ? $t.admin.rejected
            : $t.admin.organizations.disabled;
      await load();
    } catch (cause) {
      actionError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      actionID = "";
      actionNext = "";
    }
  }
</script>

<section aria-labelledby="admin-organizations-heading">
  <h1 id="admin-organizations-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.admin.organizations.heading}
  </h1>
  <p class="mt-2 text-[13px] leading-5 text-muted">{$t.admin.organizations.hint}</p>

  <form class="mt-4 space-y-2" onsubmit={search}>
    <div class="flex gap-2">
      <input
        class={fieldClass}
        bind:value={query}
        type="search"
        maxlength="64"
        placeholder={$t.admin.organizations.searchPlaceholder}
      />
      <button type="submit" disabled={phase === "loading"} class={`${primaryClass} shrink-0`}>
        {phase === "loading" ? $t.admin.organizations.searching : $t.admin.organizations.search}
      </button>
    </div>
    <label class="block">
      <span class="mb-1 block text-[12px] text-bone/80">
        {$t.admin.organizations.statusLabel}
      </span>
      <select class={fieldClass} bind:value={status} onchange={() => load()}>
        {#each [...STATUSES, "全部"] as value (value)}
          <option value={value}>
            {value === "全部" ? $t.admin.organizations.statusAll : value}
          </option>
        {/each}
      </select>
    </label>
  </form>

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
  {:else if records.length}
    <ul class="mt-4 space-y-2">
      {#each records as record (record.id)}
        <li class="rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
          <div class="flex items-start justify-between gap-3">
            <p class="min-w-0 truncate text-sm text-bone/90">{record.name}</p>
            <span
              class="shrink-0 rounded-full border border-line px-2 py-0.5 font-mono text-[10px] text-bone/70"
            >
              {record.status}
            </span>
          </div>
          <p class="mt-1 text-[11px] text-muted">
            {[record.org_type, record.city, record.district]
              .filter((part) => part.length > 0)
              .join(" · ")}
          </p>
          {#if record.created_at}
            <p class="mt-0.5 text-[11px] text-muted">
              {$t.admin.createdAt(record.created_at.slice(0, 10))}
            </p>
          {/if}
          {#if record.metadata.credit_code}
            <p class="mt-1 text-[11px] text-bone/70">
              <span class="text-muted">{$t.admin.creditCode}</span>
              <span class="ml-1 font-mono">{record.metadata.credit_code}</span>
            </p>
          {/if}
          {#if record.metadata.license_file_id}
            <a
              href={organizationLicenseURL(record.metadata.license_file_id)}
              target="_blank"
              rel="noreferrer"
              class="mt-1 inline-flex min-h-8 items-center text-[11px] text-acid underline-offset-4 active:underline"
            >
              {$t.admin.openLicense} ↗
            </a>
          {/if}

          <div class="mt-2 flex flex-wrap gap-2">
            {#if record.status === "待审核"}
              <button
                type="button"
                onclick={() => review(record, "已认证")}
                disabled={actionID !== ""}
                class={primaryClass}
              >
                {actionID === record.id && actionNext === "已认证"
                  ? $t.admin.approving
                  : $t.admin.approve}
              </button>
              <button
                type="button"
                onclick={() => beginReject(record)}
                disabled={actionID !== "" || rejectingID === record.id}
                class={actionClass}
              >
                {$t.admin.reject}
              </button>
            {:else if record.status === "已认证"}
              <button
                type="button"
                onclick={() => beginDisable(record)}
                disabled={actionID !== "" || disablingID === record.id}
                class={actionClass}
              >
                {$t.admin.organizations.disable}
              </button>
            {:else}
              <button
                type="button"
                onclick={() => review(record, "已认证")}
                disabled={actionID !== ""}
                class={primaryClass}
              >
                {actionID === record.id && actionNext === "已认证"
                  ? $t.admin.approving
                  : $t.admin.approve}
              </button>
            {/if}
          </div>

          {#if rejectingID === record.id}
            <div class="mt-2 space-y-2">
              <label class="block">
                <span class="mb-1 block text-[12px] text-bone/80">{$t.admin.reasonLabel}</span>
                <textarea
                  class={fieldClass}
                  bind:value={reason}
                  rows="3"
                  maxlength="200"
                  placeholder={$t.admin.reasonPlaceholder}
                ></textarea>
              </label>
              <div class="flex gap-2">
                <button
                  type="button"
                  onclick={() => review(record, "已驳回")}
                  disabled={actionID !== ""}
                  class={`${actionClass} border-amber-300/30 text-amber-300`}
                >
                  {actionID === record.id && actionNext === "已驳回"
                    ? $t.admin.rejecting
                    : $t.admin.confirmReject}
                </button>
                <button type="button" onclick={cancelInline} disabled={actionID !== ""} class={actionClass}>
                  {$t.admin.cancel}
                </button>
              </div>
            </div>
          {/if}

          {#if disablingID === record.id}
            <div class="mt-2 space-y-2">
              <label class="block">
                <span class="mb-1 block text-[12px] text-bone/80">
                  {$t.admin.organizations.disableReason}
                </span>
                <textarea
                  class={fieldClass}
                  bind:value={disableReason}
                  rows="2"
                  maxlength="200"
                ></textarea>
              </label>
              <div class="flex gap-2">
                <button
                  type="button"
                  onclick={() => review(record, "已停用")}
                  disabled={actionID !== ""}
                  class={`${actionClass} border-amber-300/30 text-amber-300`}
                >
                  {actionID === record.id && actionNext === "已停用"
                    ? $t.admin.rejecting
                    : $t.admin.organizations.disable}
                </button>
                <button type="button" onclick={cancelInline} disabled={actionID !== ""} class={actionClass}>
                  {$t.admin.cancel}
                </button>
              </div>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {:else}
    <p class="mt-4 text-[13px] text-muted">{$t.admin.organizations.empty}</p>
  {/if}

  {#if actionError}
    <p class="mt-2 text-[11px] leading-5 text-amber-200" role="alert">
      {actionError || $t.admin.actionFailed}
    </p>
  {/if}
</section>
