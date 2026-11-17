<script lang="ts">
  import { onMount } from "svelte";

  import { t } from "../i18n";
  import { getReadiness, getStatus, type AuthSession } from "../lib/api";

  interface Props {
    session: AuthSession;
  }

  let { session }: Props = $props();

  let probing = $state(false);
  let apiStatus = $state("");
  let databaseStatus = $state("");
  let latency = $state<number | null>(null);
  let error = $state("");

  async function probe() {
    if (probing) return;
    probing = true;
    error = "";
    const started = performance.now();
    try {
      const [status, readiness] = await Promise.all([getStatus(), getReadiness()]);
      apiStatus = status.status;
      databaseStatus = readiness.status;
      latency = Math.round(performance.now() - started);
    } catch {
      apiStatus = "";
      databaseStatus = "";
      latency = null;
      error = $t.admin.failed;
    } finally {
      probing = false;
    }
  }

  onMount(() => {
    void probe();
  });
</script>

<section aria-labelledby="admin-platform-heading">
  <h1 id="admin-platform-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.admin.platform.heading}
  </h1>
  <p class="mt-2 text-[13px] leading-5 text-muted">{$t.admin.platform.hint}</p>

  <div class="mt-4 rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
    <div class="flex items-baseline justify-between gap-3">
      <h2 class="text-sm font-medium">{$t.admin.platform.serviceHeading}</h2>
      <button
        type="button"
        onclick={probe}
        disabled={probing}
        class="min-h-8 text-[12px] text-acid underline-offset-4 active:underline disabled:opacity-50"
      >
        {probing ? $t.admin.platform.probing : $t.admin.platform.probe}
      </button>
    </div>
    <dl class="mt-2 space-y-1 text-[12px]">
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.api}</dt>
        <dd class="text-bone/90">{apiStatus || "—"}</dd>
      </div>
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.database}</dt>
        <dd class="text-bone/90">
          {databaseStatus === "ready"
            ? $t.admin.platform.ready
            : databaseStatus
              ? $t.admin.platform.notReady
              : "—"}
        </dd>
      </div>
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.latency}</dt>
        <dd class="tabular-nums text-bone/90">{latency === null ? "—" : `${latency} ms`}</dd>
      </div>
    </dl>
    {#if error}
      <p class="mt-2 text-[11px] leading-5 text-amber-200" role="alert">{error}</p>
    {/if}
  </div>

  <div class="mt-3 rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
    <h2 class="text-sm font-medium">{$t.admin.platform.adminHeading}</h2>
    <dl class="mt-2 space-y-1 text-[12px]">
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.subject}</dt>
        <dd class="truncate font-mono text-bone/90">{session.subject_id}</dd>
      </div>
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.roles}</dt>
        <dd class="text-right text-bone/90">
          {session.roles.length ? session.roles.join(" · ") : $t.admin.platform.none}
        </dd>
      </div>
      <div class="flex justify-between gap-3">
        <dt class="text-muted">{$t.admin.platform.permissions}</dt>
        <dd class="text-right text-bone/90">
          {session.permissions.length ? session.permissions.join(" · ") : $t.admin.platform.none}
        </dd>
      </div>
    </dl>
  </div>
</section>
