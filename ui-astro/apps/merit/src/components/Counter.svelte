<script lang="ts">
  let count = $state(0);

  function increment() {
    count += 1;
  }

  function reset() {
    count = 0;
  }
</script>

<div
  class="rounded-xl border border-border bg-surface p-6 shadow-lg"
  data-testid="counter"
>
  <p class="mb-4 text-sm text-fg-muted">
    Svelte 组件已挂载（<code class="text-accent">client:load</code>，CSR 生效）
  </p>
  <div class="flex items-center gap-3">
    <button
      class="rounded-lg bg-accent px-4 py-2 font-medium text-white transition hover:opacity-90"
      onclick={increment}
    >
      +1
    </button>
    <button
      class="rounded-lg border border-border bg-surface-alt px-4 py-2 font-medium text-fg-muted transition hover:text-fg"
      onclick={reset}
    >
      重置
    </button>
    <span class="text-2xl font-bold tabular-nums text-fg-emphasis" data-testid="count">
      {count}
    </span>
  </div>
</div>
