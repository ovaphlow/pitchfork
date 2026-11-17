<script lang="ts">
  import { t } from "../i18n";
  import type { AdminTab } from "../lib/nav";

  interface Props {
    active: AdminTab;
    onSelect: (tab: AdminTab) => void;
  }

  let { active, onSelect }: Props = $props();

  const items: { value: AdminTab; key: "home" | "recruiters" | "seekers" | "platform" }[] = [
    { value: "home", key: "home" },
    { value: "recruiters", key: "recruiters" },
    { value: "seekers", key: "seekers" },
    { value: "platform", key: "platform" },
  ];
</script>

{#snippet icon(tab: AdminTab)}
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    class="h-5 w-5"
    aria-hidden="true"
  >
    {#if tab === "home"}
      <path d="M4 10.5 12 4l8 6.5V20h-5v-5.5h-6V20H4z" />
    {:else if tab === "recruiters"}
      <path d="M4 20V7l8-3 8 3v13" />
      <path d="M9 20v-5h6v5" />
      <path d="M8 10h.01M12 10h.01M16 10h.01" />
    {:else if tab === "seekers"}
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20c1.4-3.4 4-5 7-5s5.6 1.6 7 5" />
    {:else}
      <path d="M4 7h16M4 12h16M4 17h16" />
      <circle cx="8" cy="7" r="1.6" />
      <circle cx="15" cy="12" r="1.6" />
      <circle cx="10" cy="17" r="1.6" />
    {/if}
  </svg>
{/snippet}

<nav
  aria-label={$t.admin.heading}
  class="sticky bottom-0 z-30 border-t border-line bg-ink/90 px-2 pt-1.5 pb-[calc(0.375rem+env(safe-area-inset-bottom))] backdrop-blur-md"
>
  <ul class="grid grid-cols-4">
    {#each items as item (item.value)}
      <li>
        <button
          type="button"
          onclick={() => onSelect(item.value)}
          aria-current={active === item.value ? "page" : undefined}
          class="relative flex min-h-12 w-full flex-col items-center justify-center gap-1 rounded-xl text-[10px] transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid {active ===
          item.value
            ? 'text-acid'
            : 'text-muted active:text-bone'}"
        >
          {#if active === item.value}
            <span class="absolute top-0 h-0.5 w-8 rounded-full bg-acid" aria-hidden="true"
            ></span>
          {/if}
          {@render icon(item.value)}
          <span>{$t.admin.nav[item.key]}</span>
        </button>
      </li>
    {/each}
  </ul>
</nav>
