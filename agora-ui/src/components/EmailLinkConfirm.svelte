<script lang="ts">
  import { describeRequestError, locale, t } from "../i18n";
  import { AuthError, exchangeEmailLink, type AuthSession } from "../lib/api";

  interface Props {
    token: string;
    onAuthenticated: (session: AuthSession) => void;
    onCancel: () => void;
  }

  let { token, onAuthenticated, onCancel }: Props = $props();

  let pending = $state(false);
  let error = $state("");

  // 只在用户点击时才消费 token：邮件客户端/浏览器的预取不会把链接用掉。
  async function confirm() {
    if (pending) return;
    error = "";
    pending = true;
    try {
      onAuthenticated(await exchangeEmailLink(token));
    } catch (cause) {
      error =
        cause instanceof AuthError && cause.status === 401
          ? $t.auth.linkInvalid
          : describeRequestError(cause, $t, window.location.origin);
    } finally {
      pending = false;
    }
  }
</script>

<section
  class="flex flex-1 flex-col px-5 pt-8 pb-8"
  aria-labelledby="email-link-heading"
>
  <p class="text-[11px] tracking-[0.16em] text-muted">
    {$t.auth.eyebrow}
  </p>
  <h1
    id="email-link-heading"
    class="mt-3 text-[2.25rem] leading-[1.1] font-medium {$locale === 'en'
      ? 'tracking-[-0.05em]'
      : 'tracking-[-0.02em]'}"
  >
    {$t.auth.linkHeading}
  </h1>

  <p class="mt-4 text-[15px] leading-6 text-muted">
    {$t.auth.linkSubcopy}
  </p>

  {#if error}
    <p
      class="mt-6 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-[13px] leading-5 text-amber-200"
      role="alert"
    >
      {error}
    </p>
  {/if}

  <button
    type="button"
    onclick={confirm}
    disabled={pending}
    class="mt-7 flex min-h-12 w-full items-center justify-center gap-2 rounded-full bg-acid px-5 text-[15px] font-semibold text-ink transition-colors active:bg-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-acid"
  >
    {#if pending}
      <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-ink" aria-hidden="true"
      ></span>
    {/if}
    {pending ? $t.auth.working : $t.auth.continue}
  </button>

  <div class="mt-auto pt-10 text-center">
    <button
      type="button"
      onclick={onCancel}
      class="min-h-11 text-[13px] text-muted underline-offset-4 transition-colors active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
    >
      {$t.auth.backToLogin}
    </button>
  </div>
</section>
