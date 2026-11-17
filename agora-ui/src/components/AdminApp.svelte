<script lang="ts">
  import { onMount } from "svelte";

  import AdminHome from "./AdminHome.svelte";
  import AdminNav from "./AdminNav.svelte";
  import AdminOrganizations from "./AdminOrganizations.svelte";
  import AdminPlatform from "./AdminPlatform.svelte";
  import AdminSeekers from "./AdminSeekers.svelte";
  import AuthPanel from "./AuthPanel.svelte";
  import { describeRequestError, locale, t, toggleLocale } from "../i18n";
  import { getSession, logout, type AuthSession } from "../lib/api";
  import type { AdminTab } from "../lib/nav";

  let session = $state<AuthSession | null>(null);
  let ready = $state(false);
  let error = $state("");
  let signingOut = $state(false);
  let tab = $state<AdminTab>("home");

  $effect(() => {
    document.documentElement.lang = $locale;
    document
      .querySelector('meta[name="description"]')
      ?.setAttribute("content", $t.meta.description);
  });

  // 非管理员不能停留在管理台；入口本身也不在任何页面出现。
  $effect(() => {
    if (ready && session && !session.platform_admin) {
      window.location.replace("/");
    }
  });

  onMount(() => {
    let cancelled = false;
    void (async () => {
      try {
        const next = await getSession();
        if (!cancelled) session = next;
      } catch (cause) {
        if (!cancelled) error = describeRequestError(cause, $t, window.location.origin);
      } finally {
        if (!cancelled) ready = true;
      }
    })();
    return () => {
      cancelled = true;
    };
  });

  function handleAuthenticated(next: AuthSession) {
    if (!next.platform_admin) {
      window.location.replace("/");
      return;
    }
    session = next;
    error = "";
  }

  async function signOut() {
    if (signingOut) return;
    signingOut = true;
    try {
      await logout();
    } catch {
      // BFF 仍会清理本地 Cookie；这里按已退出处理。
    }
    window.location.replace("/");
  }
</script>

<main class="relative flex min-h-dvh justify-center overflow-x-clip bg-ink text-bone">
  <div class="relative flex min-h-dvh w-full max-w-[27rem] flex-col sm:max-w-[40rem]">
    <header
      class="sticky top-0 z-30 flex items-center justify-between gap-3 border-b border-line bg-ink/85 px-5 pt-[calc(0.75rem+env(safe-area-inset-top))] pb-3 backdrop-blur-md"
    >
      <a href="/" class="-m-1 flex items-center gap-2.5 p-1" aria-label={$t.brand.home}>
        <span
          class="grid size-9 place-items-center rounded-[0.6rem] bg-acid text-sm font-bold text-ink transition-transform active:scale-95"
        >
          {$t.brand.mark}
        </span>
        <span class="text-[13px] font-medium tracking-[0.12em] text-bone/90">
          {$t.brand.name}
        </span>
      </a>

      <div class="flex min-w-0 items-center gap-1">
        <button
          type="button"
          onclick={toggleLocale}
          aria-label={$t.language.toggleLabel}
          class="grid size-11 shrink-0 place-items-center rounded-full border border-line text-[11px] font-medium text-muted transition-colors active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
        >
          {$t.language.toggleText}
        </button>

        {#if session}
          <span class="max-w-[4.5rem] truncate text-[13px] text-bone/80">
            {session.display_name || $t.common.yourAccount}
          </span>
          <button
            type="button"
            onclick={signOut}
            disabled={signingOut}
            aria-label={$t.common.signOut}
            class="grid size-11 shrink-0 place-items-center rounded-full border border-line text-muted transition-colors active:text-bone disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
          >
            <span aria-hidden="true">↪</span>
          </button>
        {/if}
      </div>
    </header>

    {#if !ready}
      <div class="flex flex-1 items-center justify-center px-5">
        <p class="text-[13px] text-muted">{$t.common.checkingSession}</p>
      </div>
    {:else if !session}
      <AuthPanel onAuthenticated={handleAuthenticated} initialError={error} showIntent={false} />
    {:else if session.platform_admin}
      <div class="relative flex-1 px-5 pt-6 pb-6">
        {#if tab === "home"}
          <AdminHome onOpen={(next) => (tab = next)} />
        {:else if tab === "recruiters"}
          <AdminOrganizations />
        {:else if tab === "seekers"}
          <AdminSeekers />
        {:else}
          <AdminPlatform {session} />
        {/if}
      </div>
      <AdminNav active={tab} onSelect={(next) => (tab = next)} />
    {:else}
      <div class="flex flex-1 items-center justify-center px-5">
        <p class="text-[13px] text-muted">{$t.common.checkingSession}</p>
      </div>
    {/if}
  </div>
</main>
