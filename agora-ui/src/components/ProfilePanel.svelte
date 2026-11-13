<script lang="ts">
  import { onMount } from "svelte";

  import { describeRequestError, locale, t } from "../i18n";
  import {
    API_BASE_URL,
    getReadiness,
    getStatus,
    isApiConfigured,
    type AuthSession,
  } from "../lib/api";
  import { intent } from "../lib/intent";

  interface Props {
    session: AuthSession;
  }

  let { session }: Props = $props();

  type Phase = "checking" | "online" | "offline";

  let phase = $state<Phase>("checking");
  let databaseReady = $state(false);
  let latency = $state<number | null>(null);
  let checkedAt = $state<Date | null>(null);
  let error = $state("");
  let inflight: AbortController | null = null;

  const phaseLabel = $derived(
    phase === "checking"
      ? $t.status.phase.checking
      : phase === "online"
        ? $t.status.phase.online
        : $t.status.phase.offline,
  );

  const statusDot = $derived(
    phase === "online" ? "bg-acid" : phase === "checking" ? "bg-amber-300" : "bg-red-400",
  );

  const checkedLabel = $derived(
    checkedAt === null
      ? "—"
      : new Intl.DateTimeFormat($locale, {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
        }).format(checkedAt),
  );

  async function probe() {
    inflight?.abort();
    const controller = new AbortController();
    inflight = controller;

    phase = "checking";
    databaseReady = false;
    latency = null;
    checkedAt = null;
    error = "";

    if (!isApiConfigured) {
      phase = "offline";
      error = $t.errors.notConfigured;
      inflight = null;
      return;
    }

    const startedAt = performance.now();
    try {
      const status = await getStatus(controller.signal);
      latency = Math.round(performance.now() - startedAt);
      phase = status.status === "ok" ? "online" : "offline";

      if (phase === "offline") {
        error = $t.status.reported(status.status);
      } else {
        try {
          const readiness = await getReadiness(controller.signal);
          databaseReady = readiness.status === "ready";
        } catch {
          databaseReady = false;
        }
      }
    } catch (cause) {
      if (controller.signal.aborted) return;
      phase = "offline";
      latency = Math.round(performance.now() - startedAt);
      error = describeRequestError(cause, $t, window.location.origin);
    } finally {
      if (!controller.signal.aborted) {
        checkedAt = new Date();
        if (inflight === controller) inflight = null;
      }
    }
  }

  onMount(() => {
    void probe();
    return () => inflight?.abort();
  });
</script>

<section aria-labelledby="profile-heading">
  <h1 id="profile-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.profile.heading}
  </h1>

  <dl class="mt-4 divide-y divide-line border-y border-line">
    <div class="flex min-h-12 items-center justify-between gap-4 py-3">
      <dt class="text-sm text-muted">{$t.profile.name}</dt>
      <dd class="min-w-0 truncate text-sm text-bone/90">
        {session.display_name || $t.common.yourAccount}
      </dd>
    </div>
    {#if session.identifier}
      <div class="flex min-h-12 items-center justify-between gap-4 py-3">
        <dt class="text-sm text-muted">{$t.profile.identifier}</dt>
        <dd class="min-w-0 truncate font-mono text-[13px] text-bone/90">
          {session.identifier}
        </dd>
      </div>
    {/if}
    <div class="flex min-h-12 items-center justify-between gap-4 py-3">
      <dt class="text-sm text-muted">{$t.profile.subject}</dt>
      <dd class="min-w-0 truncate font-mono text-[12px] text-bone/70">
        {session.subject_id}
      </dd>
    </div>
    <div class="flex min-h-12 items-center justify-between gap-4 py-3">
      <dt class="text-sm text-muted">{$t.profile.identity}</dt>
      <dd class="text-sm text-bone/90">
        {$intent === "recruiter" ? $t.identity.recruiter : $t.identity.jobSeeker}
      </dd>
    </div>
  </dl>
  <p class="mt-2 text-[11px] leading-5 text-muted">{$t.profile.identityHint}</p>

  <section class="mt-8" aria-labelledby="access-heading">
    <div class="flex items-baseline justify-between gap-3">
      <h2 id="access-heading" class="text-sm font-medium">{$t.access.heading}</h2>
      <span class="text-[11px] text-muted">{$t.access.roles(session.roles.length)}</span>
    </div>
    {#if session.roles.length}
      <ul class="mt-3 flex flex-wrap gap-2">
        {#each session.roles as role (role)}
          <li
            class="rounded-full border border-line bg-ink-soft px-3 py-1.5 font-mono text-[11px] text-bone/80"
          >
            {role}
          </li>
        {/each}
      </ul>
    {:else}
      <p class="mt-2 text-sm text-muted">{$t.access.empty}</p>
    {/if}
    {#if !session.nexus_ready}
      <p class="mt-2 text-xs leading-5 text-amber-300">{$t.access.unavailable}</p>
    {/if}
  </section>

  <section class="mt-8" aria-labelledby="status-heading">
    <div class="flex items-center justify-between gap-3">
      <h2 id="status-heading" class="text-sm font-medium">{$t.status.heading}</h2>
      <span class="inline-flex items-center gap-2 text-[11px] text-muted" role="status" aria-live="polite">
        <span class={`h-1.5 w-1.5 rounded-full ${statusDot}`} aria-hidden="true"></span>
        {phaseLabel}
      </span>
    </div>
    <p class="mt-1 text-[11px] tabular-nums text-muted">
      {$t.status.checkedAt(checkedLabel)}
    </p>

    <div class="mt-3 overflow-hidden rounded-2xl border border-line bg-ink-soft/90">
      <dl class="divide-y divide-line">
        <div class="flex min-h-12 items-center justify-between gap-4 px-4 py-3">
          <dt class="text-sm text-muted">{$t.status.http}</dt>
          <dd
            class="text-sm tabular-nums"
            class:text-acid={phase === "online"}
            class:text-amber-300={phase === "offline"}
            class:text-muted={phase === "checking"}
          >
            {phase === "online" ? "200" : phase === "checking" ? "…" : "—"}
          </dd>
        </div>
        <div class="flex min-h-12 items-center justify-between gap-4 px-4 py-3">
          <dt class="text-sm text-muted">{$t.status.database}</dt>
          <dd
            class="text-sm"
            class:text-acid={databaseReady && phase === "online"}
            class:text-muted={!databaseReady || phase !== "online"}
          >
            {phase === "checking" ? "…" : databaseReady ? $t.status.ready : $t.status.notReady}
          </dd>
        </div>
        <div class="flex min-h-12 items-center justify-between gap-4 px-4 py-3">
          <dt class="text-sm text-muted">{$t.status.latency}</dt>
          <dd class="text-sm tabular-nums text-bone/90">
            {latency === null ? "—" : $t.status.latencyValue(latency)}
          </dd>
        </div>
      </dl>

      <div class="border-t border-line px-4 py-4">
        <p class="text-[11px] tracking-[0.12em] text-muted">{$t.status.endpoint}</p>
        <p class="mt-1 font-mono text-[13px] leading-5 break-all text-bone/90">
          {API_BASE_URL || $t.status.notConfigured}
        </p>
      </div>

      {#if error}
        <p
          class="border-t border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
          role="alert"
        >
          {error}
        </p>
      {/if}

      <div class="flex items-center justify-between gap-3 border-t border-line px-4 py-3">
        <div class="min-w-0">
          <p class="font-mono text-[11px] leading-5 text-muted">
            <span class="text-acid">GET</span>
            <span class="break-all">/crate-api/agora/v1/status</span>
          </p>
          <p class="mt-1 font-mono text-[11px] leading-5 text-muted">
            <span class="text-acid">GET</span>
            <span class="break-all">/readyz</span>
          </p>
        </div>
        {#if isApiConfigured}
          <a
            href={`${API_BASE_URL}/crate-api/agora/v1/status`}
            target="_blank"
            rel="noreferrer"
            aria-label={$t.status.openEndpoint}
            class="grid size-11 shrink-0 place-items-center rounded-full border border-line text-muted transition-colors active:border-bone/30 active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
          >
            ↗
          </a>
        {/if}
      </div>

      <div class="border-t border-line px-4 py-3">
        <button
          type="button"
          onclick={probe}
          disabled={phase === "checking"}
          class="flex min-h-11 w-full items-center justify-center gap-2 rounded-full border border-line text-[13px] text-muted transition-colors active:text-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
        >
          {#if phase === "checking"}
            <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-acid" aria-hidden="true"
            ></span>
          {/if}
          {phase === "checking" ? $t.status.probing : $t.status.probe}
        </button>
      </div>
    </div>
  </section>

  <section class="mt-8" aria-labelledby="stack-heading">
    <h2 id="stack-heading" class="text-sm font-medium">{$t.stack.heading}</h2>
    <dl class="mt-3 divide-y divide-line border-y border-line">
      <div class="flex min-h-12 items-center justify-between gap-4 py-3">
        <dt class="text-sm text-muted">{$t.stack.shell}</dt>
        <dd class="text-sm text-bone/90">{$t.stack.shellValue}</dd>
      </div>
      <div class="flex min-h-12 items-center justify-between gap-4 py-3">
        <dt class="text-sm text-muted">{$t.stack.surface}</dt>
        <dd class="text-sm text-bone/90">{$t.stack.surfaceValue}</dd>
      </div>
      <div class="flex min-h-12 items-center justify-between gap-4 py-3">
        <dt class="text-sm text-muted">{$t.stack.backend}</dt>
        <dd class="text-sm text-bone/90">{$t.stack.backendValue}</dd>
      </div>
    </dl>
  </section>

  <footer class="mt-8 border-t border-line py-5 text-[11px] tracking-[0.12em] text-muted">
    <p>{$t.footer.tagline}</p>
    <p class="mt-1">{$t.footer.stack}</p>
  </footer>
</section>
