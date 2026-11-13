<script lang="ts">
  import { describeRequestError, t } from "../i18n";
  import { listPositions, type PositionSummary } from "../lib/api";
  import PositionCard from "./PositionCard.svelte";

  type Phase = "idle" | "loading" | "ready" | "failed";

  const PAGE_SIZE = 20;

  let keyword = $state("");
  let city = $state("");
  let results = $state<PositionSummary[]>([]);
  let total = $state(0);
  let phase = $state<Phase>("idle");
  let error = $state("");

  async function search(event: SubmitEvent) {
    event.preventDefault();
    if (phase === "loading") return;

    phase = "loading";
    error = "";
    try {
      const page = await listPositions({
        limit: PAGE_SIZE,
        q: keyword.trim(),
        city: city.trim(),
      });
      results = page.records;
      total = page.total;
      phase = "ready";
    } catch (cause) {
      error = describeRequestError(cause, $t, window.location.origin);
      phase = "failed";
    }
  }
</script>

<section aria-labelledby="search-heading">
  <h1 id="search-heading" class="text-[1.75rem] leading-tight font-medium">
    {$t.search.heading}
  </h1>
  <p class="mt-2 text-[13px] leading-5 text-muted">{$t.search.copy}</p>

  <form class="mt-4 space-y-3" onsubmit={search} aria-busy={phase === "loading"}>
    <label class="block">
      <span class="mb-1.5 block text-[13px] text-bone/80">{$t.search.keyword}</span>
      <input
        class="min-h-11 w-full rounded-xl border border-line bg-ink-soft px-3.5 text-[14px] text-bone placeholder:text-muted/60 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
        bind:value={keyword}
        type="search"
        name="q"
        maxlength="120"
        placeholder={$t.search.keywordPlaceholder}
      />
    </label>

    <label class="block">
      <span class="mb-1.5 block text-[13px] text-bone/80">{$t.search.city}</span>
      <input
        class="min-h-11 w-full rounded-xl border border-line bg-ink-soft px-3.5 text-[14px] text-bone placeholder:text-muted/60 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
        bind:value={city}
        type="text"
        name="city"
        maxlength="32"
        placeholder={$t.search.cityPlaceholder}
      />
    </label>

    <button
      type="submit"
      disabled={phase === "loading"}
      class="flex min-h-11 w-full items-center justify-center rounded-full bg-acid px-5 text-[14px] font-semibold text-ink transition-colors active:bg-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
    >
      {phase === "loading" ? $t.search.working : $t.search.submit}
    </button>
  </form>

  {#if phase === "idle"}
    <p class="mt-6 text-sm text-muted">{$t.search.idle}</p>
  {:else if phase === "failed"}
    <p
      class="mt-4 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
      role="alert"
    >
      {error || $t.search.failed}
    </p>
  {:else if phase === "ready"}
    <div class="mt-6 flex items-baseline justify-between gap-3">
      <h2 class="text-sm font-medium">{$t.search.results}</h2>
      <span class="text-[11px] tabular-nums text-muted">
        {$t.workspace.positionsCount(total)}
      </span>
    </div>
    {#if results.length}
      <ul class="mt-3 space-y-2">
        {#each results as position (position.id)}
          <PositionCard {position} />
        {/each}
      </ul>
      {#if total > results.length}
        <p class="mt-2 text-[11px] leading-5 text-muted">
          {$t.search.truncated(PAGE_SIZE)}
        </p>
      {/if}
    {:else}
      <div class="mt-3 rounded-2xl border border-line bg-ink-soft/90 px-4 py-4">
        <p class="text-sm text-bone/90">{$t.search.empty}</p>
      </div>
    {/if}
  {/if}
</section>
