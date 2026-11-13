<script lang="ts">
  import IntentSwitch from "./IntentSwitch.svelte";
  import { describeRequestError, locale, t } from "../i18n";
  import { login, requestEmailLink, type AuthSession } from "../lib/api";
  import { intent } from "../lib/intent";

  interface Props {
    onAuthenticated: (session: AuthSession) => void;
    initialError?: string;
  }

  let { onAuthenticated, initialError = "" }: Props = $props();

  type Mode = "login" | "register";
  // 登录面板默认邮箱链接；密码登录只作为存量非邮箱账号的兜底入口。
  type LoginMethod = "email" | "password";

  let mode = $state<Mode>("login");
  let loginMethod = $state<LoginMethod>("email");
  let displayName = $state("");
  let email = $state("");
  let identifier = $state("");
  let password = $state("");
  let pending = $state(false);
  // 面板在会话检查完成后才挂载，因此这里只取一次初始错误。
  // svelte-ignore state_referenced_locally
  let error = $state(initialError);
  let notice = $state("");

  // 身份只改变文案与登录后的工作台，不影响请求体。
  const subcopy = $derived(
    mode === "register"
      ? $intent === "recruiter"
        ? $t.auth.registerSubcopyRecruiter
        : $t.auth.registerSubcopyJobSeeker
      : $intent === "recruiter"
        ? $t.auth.loginSubcopyRecruiter
        : $t.auth.loginSubcopyJobSeeker,
  );

  // 注册与邮箱链接登录都只发信，不直接建立会话。
  const sendsLink = $derived(mode === "register" || loginMethod === "email");

  const inputClass =
    "min-h-12 w-full rounded-xl border border-line bg-ink-soft px-4 text-[15px] text-bone placeholder:text-muted/60 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";

  function switchMode(next: Mode) {
    mode = next;
    // 注册只是“输入邮箱”的另一种说法；回到登录一律从邮箱链接开始。
    if (next === "login") {
      loginMethod = "email";
    }
    password = "";
    error = "";
    notice = "";
  }

  function switchLoginMethod(next: LoginMethod) {
    loginMethod = next;
    password = "";
    error = "";
    notice = "";
  }

  async function sendLink(address: string, name?: string) {
    pending = true;
    try {
      await requestEmailLink(address, name);
      notice = $t.auth.noticeLinkSent;
    } catch (cause) {
      error = describeRequestError(cause, $t, window.location.origin);
    } finally {
      pending = false;
    }
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (pending) return;

    error = "";
    notice = "";

    if (mode === "register") {
      const name = displayName.trim();
      const address = email.trim();
      if (!name) {
        error = $t.auth.errors.displayName;
        return;
      }
      if (!address) {
        error = $t.auth.errors.email;
        return;
      }
      await sendLink(address, name);
      return;
    }

    if (loginMethod === "email") {
      const address = email.trim();
      if (!address) {
        error = $t.auth.errors.email;
        return;
      }
      await sendLink(address);
      return;
    }

    const account = identifier.trim();
    if (!account) {
      error = $t.auth.errors.account;
      return;
    }
    if (!password) {
      error = $t.auth.errors.password;
      return;
    }

    pending = true;
    try {
      onAuthenticated(await login(account, password));
    } catch (cause) {
      error = describeRequestError(cause, $t, window.location.origin);
    } finally {
      pending = false;
    }
  }
</script>

<section
  class="flex flex-1 flex-col px-5 pt-8 pb-8"
  aria-labelledby="auth-heading"
>
  <p class="text-[11px] tracking-[0.16em] text-muted">
    {$t.auth.eyebrow}
  </p>
  <h1
    id="auth-heading"
    class="mt-3 text-[2.25rem] leading-[1.1] font-medium {$locale === 'en'
      ? 'tracking-[-0.05em]'
      : 'tracking-[-0.02em]'}"
  >
    {mode === "register" ? $t.auth.registerHeading : $t.auth.loginHeading}
  </h1>
  <div class="mt-6">
    <IntentSwitch label={$t.identity.label} />
  </div>

  <p class="mt-4 text-[15px] leading-6 text-muted">
    {subcopy}
  </p>

  <form class="mt-7 space-y-4" onsubmit={submit} aria-busy={pending}>
    {#if mode === "register"}
      <label class="block">
        <span class="mb-1.5 block text-[13px] text-bone/80">{$t.auth.displayName}</span>
        <input
          class={inputClass}
          bind:value={displayName}
          type="text"
          name="display_name"
          autocomplete="name"
          maxlength="120"
          required
          placeholder={$t.auth.displayNamePlaceholder}
        />
      </label>

      <label class="block">
        <span class="mb-1.5 block text-[13px] text-bone/80">{$t.auth.email}</span>
        <input
          class={inputClass}
          bind:value={email}
          type="email"
          name="email"
          autocomplete="email"
          autocapitalize="none"
          spellcheck="false"
          inputmode="email"
          maxlength="64"
          required
          placeholder={$t.auth.emailPlaceholder}
        />
      </label>
    {:else if loginMethod === "email"}
      <label class="block">
        <span class="mb-1.5 block text-[13px] text-bone/80">{$t.auth.email}</span>
        <input
          class={inputClass}
          bind:value={email}
          type="email"
          name="email"
          autocomplete="email"
          autocapitalize="none"
          spellcheck="false"
          inputmode="email"
          maxlength="64"
          required
          placeholder={$t.auth.emailPlaceholder}
        />
      </label>
    {:else}
      <label class="block">
        <span class="mb-1.5 block text-[13px] text-bone/80">{$t.auth.accountOrEmail}</span>
        <input
          class={inputClass}
          bind:value={identifier}
          type="text"
          name="identifier"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          inputmode="email"
          maxlength="64"
          required
          placeholder={$t.auth.identifierPlaceholder}
        />
      </label>

      <label class="block">
        <span class="mb-1.5 block text-[13px] text-bone/80">{$t.auth.password}</span>
        <input
          class={inputClass}
          bind:value={password}
          type="password"
          name="password"
          autocomplete="current-password"
          maxlength="1024"
          required
          placeholder="••••••••••••"
        />
      </label>
    {/if}

    {#if error}
      <p
        class="rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-[13px] leading-5 text-amber-200"
        role="alert"
      >
        {error}
      </p>
    {/if}

    {#if notice}
      <p
        class="rounded-xl border border-acid/20 bg-acid/[0.06] px-4 py-3 text-[13px] leading-5 text-acid"
        role="status"
      >
        {notice}
      </p>
    {/if}

    <button
      type="submit"
      disabled={pending}
      class="flex min-h-12 w-full items-center justify-center gap-2 rounded-full bg-acid px-5 text-[15px] font-semibold text-ink transition-colors active:bg-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-acid"
    >
      {#if pending}
        <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-ink" aria-hidden="true"
        ></span>
      {/if}
      {pending
        ? $t.auth.working
        : mode === "register"
          ? $t.auth.signUp
          : sendsLink
            ? $t.auth.sendLink
            : $t.auth.login}
    </button>
  </form>

  <div class="mt-auto flex flex-col items-center gap-1 pt-10 text-center">
    {#if mode === "login"}
      <button
        type="button"
        onclick={() => switchLoginMethod(loginMethod === "email" ? "password" : "email")}
        class="min-h-11 text-[13px] text-muted underline-offset-4 transition-colors active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
      >
        {loginMethod === "email" ? $t.auth.usePasswordLogin : $t.auth.useEmailLinkLogin}
      </button>
    {/if}

    <button
      type="button"
      onclick={() => switchMode(mode === "login" ? "register" : "login")}
      class="min-h-11 text-[13px] text-muted underline-offset-4 transition-colors active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
    >
      {mode === "login" ? $t.auth.switchToRegister : $t.auth.switchToLogin}
    </button>
  </div>
</section>
