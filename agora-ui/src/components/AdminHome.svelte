<script lang="ts">
  import { onMount } from "svelte";

  import { describeRequestError, t } from "../i18n";
  import {
    listOrganizations,
    listPositions,
    listSubjects,
    type OrganizationRecord,
  } from "../lib/api";
  import type { AdminTab } from "../lib/nav";

  interface Props {
    onOpen: (tab: AdminTab) => void;
  }

  let { onOpen }: Props = $props();

  let phase = $state<"loading" | "ready" | "failed">("loading");
  let error = $state("");
  let counts = $state({
    pending: 0,
    verified: 0,
    rejected: 0,
    disabled: 0,
    subjects: 0,
    openPositions: 0,
  });
  let pending = $state<OrganizationRecord[]>([]);

  async function load(signal?: AbortSignal) {
    phase = "loading";
    error = "";
    try {
      const [pendingPage, verifiedPage, rejectedPage, disabledPage, subjects, positions, tasks] =
        await Promise.all([
          listOrganizations({ status: "待审核", limit: 1 }, signal),
          listOrganizations({ status: "已认证", limit: 1 }, signal),
          listOrganizations({ status: "已驳回", limit: 1 }, signal),
          listOrganizations({ status: "已停用", limit: 1 }, signal),
          listSubjects({ limit: 1 }, signal),
          listPositions({ limit: 1 }, signal),
          listOrganizations({ status: "待审核", limit: 5 }, signal),
        ]);
      if (signal?.aborted) return;
      counts = {
        pending: pendingPage.total,
        verified: verifiedPage.total,
        rejected: rejectedPage.total,
        disabled: disabledPage.total,
        subjects: subjects.total,
        openPositions: positions.total,
      };
      pending = tasks.records;
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

  const stats = $derived([
    { label: $t.admin.home.pending, value: counts.pending, tone: "text-amber-300" },
    { label: $t.admin.home.verified, value: counts.verified, tone: "text-acid" },
    { label: $t.admin.home.rejected, value: counts.rejected, tone: "text-bone/80" },
    { label: $t.admin.home.disabled, value: counts.disabled, tone: "text-bone/80" },
    { label: $t.admin.home.subjects, value: counts.subjects, tone: "text-bone/80" },
    { label: $t.admin.home.openPositions, value: counts.openPositions, tone: "text-acid" },
  ]);
</script>

<section aria-labelledby="admin-home-heading">
  <h1 id="admin-home-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.admin.home.heading}
  </h1>
  <p class="mt-2 text-[13px] leading-5 text-muted">{$t.admin.home.hint}</p>

  {#if phase === "failed"}
    <p
      class="mt-4 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
      role="alert"
    >
      {error || $t.admin.failed}
    </p>
  {:else}
    <div class="mt-4 grid grid-cols-2 gap-2">
      {#each stats as stat (stat.label)}
        <div class="rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
          <p class="text-[11px] text-muted">{stat.label}</p>
          <p class={`mt-1 text-2xl tabular-nums ${stat.tone}`}>
            {phase === "loading" ? "—" : stat.value}
          </p>
        </div>
      {/each}
    </div>

    <div class="mt-5 flex items-baseline justify-between gap-3">
      <h2 class="text-sm font-medium">{$t.admin.home.tasksHeading}</h2>
      <button
        type="button"
        onclick={() => onOpen("recruiters")}
        class="min-h-8 text-[12px] text-acid underline-offset-4 active:underline"
      >
        {$t.admin.home.viewAll}
      </button>
    </div>

    {#if phase === "loading"}
      <p class="mt-3 text-[13px] text-muted">{$t.admin.loading}</p>
    {:else if pending.length}
      <ul class="mt-3 space-y-2">
        {#each pending as record (record.id)}
          <li class="rounded-xl border border-line bg-ink px-3 py-2.5">
            <div class="flex items-baseline justify-between gap-3">
              <p class="min-w-0 truncate text-[13px] text-bone/90">{record.name}</p>
              <span
                class="shrink-0 rounded-full border border-amber-300/30 px-2 py-0.5 font-mono text-[10px] text-amber-300"
              >
                {record.status}
              </span>
            </div>
            <p class="mt-1 text-[11px] text-muted">
              {[record.org_type, record.city, record.district]
                .filter((part) => part.length > 0)
                .join(" · ")}
            </p>
          </li>
        {/each}
      </ul>
    {:else}
      <p class="mt-3 text-[13px] text-muted">{$t.admin.home.tasksEmpty}</p>
    {/if}
  {/if}
</section>
