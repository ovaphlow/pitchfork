<script lang="ts">
  import { t } from "../i18n";
  import type { Tab } from "../lib/nav";

  interface Props {
    active: Tab;
    onSelect: (tab: Tab) => void;
  }

  let { active, onSelect }: Props = $props();

  const items: { value: Tab; key: "home" | "search" | "messages" | "profile" }[] = [
    { value: "home", key: "home" },
    { value: "search", key: "search" },
    { value: "messages", key: "messages" },
    { value: "profile", key: "profile" },
  ];
</script>

{#snippet icon(tab: Tab)}
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
    {:else if tab === "search"}
      <circle cx="11" cy="11" r="6.5" />
      <path d="m16 16 4 4" />
    {:else if tab === "messages"}
      <path d="M4 6h16v11H9l-5 3z" />
    {:else}
      <circle cx="12" cy="9" r="3.5" />
      <path d="M5 20c1.4-3.4 4-5 7-5s5.6 1.6 7 5" />
    {/if}
  </svg>
{/snippet}

<nav
  aria-label={$t.nav.label}
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
          <span>{$t.nav[item.key]}</span>
        </button>
      </li>
    {/each}
  </ul>
</nav>
