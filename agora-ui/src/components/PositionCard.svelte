<script lang="ts">
  import { t } from "../i18n";
  import type { PositionSummary } from "../lib/api";

  interface Props {
    position: PositionSummary;
  }

  let { position }: Props = $props();

  const salary = $derived(
    position.salary_negotiable ||
      (position.salary_min === null && position.salary_max === null)
      ? $t.workspace.salaryNegotiable
      : $t.workspace.salaryRange(
          position.salary_min,
          position.salary_max,
          position.salary_unit,
        ),
  );

  const meta = $derived(
    [position.organization_name, position.city, position.employment_type]
      .filter(Boolean)
      .join(" · "),
  );

  const tags = $derived(
    [position.education, position.experience].filter((tag) => tag.length > 0),
  );

  // Remaining days in the recruitment window; null when the API sends no window
  // or the deadline already passed (a stale card from a long-lived page).
  // Round, not ceil: a fresh 7-day window must read 7 even with clock skew.
  const daysLeft = $derived.by(() => {
    if (!position.open_until) return null;
    const deadline = Date.parse(position.open_until);
    if (!Number.isFinite(deadline)) return null;
    const days = Math.round((deadline - Date.now()) / 86_400_000);
    return days > 0 ? days : null;
  });
</script>

<li class="rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
  <div class="flex items-baseline justify-between gap-3">
    <h3 class="min-w-0 truncate text-sm font-medium text-bone/90">
      {position.title}
    </h3>
    <span class="shrink-0 text-[13px] tabular-nums text-acid">{salary}</span>
  </div>
  <p class="mt-1 truncate text-[11px] text-muted">{meta}</p>
  {#if tags.length || daysLeft !== null}
    <p class="mt-2 flex flex-wrap gap-1.5">
      {#each tags as tag (tag)}
        <span
          class="rounded-full border border-line px-2 py-0.5 font-mono text-[10px] text-bone/60"
        >
          {tag}
        </span>
      {/each}
      {#if daysLeft !== null}
        <span
          class={`rounded-full border px-2 py-0.5 font-mono text-[10px] ${
            daysLeft <= 3 ? "border-amber-300/30 text-amber-300" : "border-line text-bone/60"
          }`}
        >
          {$t.positions.daysLeft(daysLeft)}
        </span>
      {/if}
    </p>
  {/if}
</li>
