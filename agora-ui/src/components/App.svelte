<script lang="ts">
  import { onMount } from "svelte";

  import AuthPanel from "./AuthPanel.svelte";
  import BottomNav from "./BottomNav.svelte";
  import EmailLinkConfirm from "./EmailLinkConfirm.svelte";
  import OrganizationCard from "./OrganizationCard.svelte";
  import PositionCard from "./PositionCard.svelte";
  import ProfilePanel from "./ProfilePanel.svelte";
  import SearchPanel from "./SearchPanel.svelte";
  import { describeRequestError, locale, t, toggleLocale } from "../i18n";
  import {
    ApiError,
    MAX_UPLOAD_BYTES,
    createOrganization,
    getSession,
    listMyOrganizations,
    listPositions,
    logout,
    saveIntent,
    uploadFile,
    type AuthSession,
    type Membership,
    type PositionSummary,
  } from "../lib/api";
  import { intent } from "../lib/intent";
  import type { Tab } from "../lib/nav";

  let session = $state<AuthSession | null>(null);
  let sessionReady = $state(false);
  let sessionError = $state("");
  let signingOut = $state(false);
  let tab = $state<Tab>("home");
  // 邮件链接落在 URL 上的待兑换 token；只有用户点击“继续登录”才消费。
  let emailToken = $state<string | null>(null);

  type LoadPhase = "idle" | "loading" | "ready" | "failed";

  let memberships = $state<Membership[]>([]);
  let orgPhase = $state<LoadPhase>("idle");
  let orgError = $state("");

  // 机构类型必须与后端 organizations.OrgTypes 保持一致。
  const ORG_TYPES = ["企业", "医院", "学校", "社会组织", "政府机关", "其他"];

  const fieldClass =
    "min-h-11 w-full rounded-xl border border-line bg-ink px-3.5 text-[14px] text-bone placeholder:text-muted/60 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";

  let creating = $state(false);
  let submitting = $state(false);
  let createName = $state("");
  let createType = $state(ORG_TYPES[0]);
  let createShortName = $state("");
  let createCreditCode = $state("");
  let createLicense = $state<File | null>(null);
  let createError = $state("");
  let createNotice = $state("");
  let licenseInput = $state<HTMLInputElement | null>(null);

  const hasUnverifiedOrganization = $derived(
    memberships.some((membership) => membership.organization.status !== "已认证"),
  );

  const POSITION_PAGE_SIZE = 20;

  let positions = $state<PositionSummary[]>([]);
  let positionTotal = $state(0);
  let positionPhase = $state<LoadPhase>("idle");
  let positionError = $state("");
  let moreError = $state("");
  let loadingMore = $state(false);

  $effect(() => {
    document.documentElement.lang = $locale;
    document
      .querySelector('meta[name="description"]')
      ?.setAttribute("content", $t.meta.description);
  });

  // 平台管理员使用独立管理台：主应用不提供入口，登录后直接跳转。
  $effect(() => {
    if (session?.platform_admin) {
      window.location.replace("/admin");
    }
  });

  function handleAuthenticated(next: AuthSession) {
    session = next;
    sessionError = "";
    tab = "home";
    // Best effort: the preference must never block sign-in.
    void saveIntent($intent).catch(() => {});
  }

  // 一次性 token 不该留在地址栏（历史记录、截图、分享）。
  function clearEmailToken() {
    emailToken = null;
    if (typeof window !== "undefined") {
      window.history.replaceState({}, "", window.location.pathname + window.location.hash);
    }
  }

  function handleEmailLinkAuthenticated(next: AuthSession) {
    clearEmailToken();
    handleAuthenticated(next);
  }

  async function signOut() {
    if (signingOut) return;
    signingOut = true;
    try {
      await logout();
    } catch {
      // BFF 仍会清理本地 Cookie；这里按已退出处理。
    }
    session = null;
    sessionError = "";
    tab = "home";
    signingOut = false;
  }

  // 招聘方工作台需要所属机构；求职者身份下不发请求。
  async function loadMemberships(signal?: AbortSignal) {
    orgPhase = "loading";
    orgError = "";
    try {
      const next = await listMyOrganizations(signal);
      if (signal?.aborted) return;
      memberships = next;
      orgPhase = "ready";
    } catch (cause) {
      if (signal?.aborted) return;
      if (cause instanceof ApiError && cause.status === 401) {
        // 会话已失效：回到登录面板，而不是把 401 当成普通请求错误。
        session = null;
        sessionError = "";
        tab = "home";
        return;
      }
      orgError = describeRequestError(cause, $t, window.location.origin);
      orgPhase = "failed";
    }
  }

  $effect(() => {
    if (!session || $intent !== "recruiter") {
      memberships = [];
      orgPhase = "idle";
      orgError = "";
      creating = false;
      return;
    }

    const controller = new AbortController();
    void loadMemberships(controller.signal);
    return () => controller.abort();
  });

  // 求职者工作台展示公开岗位列表：后端只返回「招聘中且未过期」的记录。
  $effect(() => {
    if (!session || $intent !== "job_seeker") {
      positions = [];
      positionTotal = 0;
      positionPhase = "idle";
      positionError = "";
      moreError = "";
      return;
    }

    const controller = new AbortController();
    positionPhase = "loading";
    positionError = "";
    moreError = "";

    void (async () => {
      try {
        const page = await listPositions(
          { limit: POSITION_PAGE_SIZE },
          controller.signal,
        );
        if (controller.signal.aborted) return;
        positions = page.records;
        positionTotal = page.total;
        positionPhase = "ready";
      } catch (cause) {
        if (controller.signal.aborted) return;
        positionError = describeRequestError(cause, $t, window.location.origin);
        positionPhase = "failed";
      }
    })();

    return () => controller.abort();
  });

  async function loadMorePositions() {
    if (positionPhase !== "ready" || loadingMore) return;
    if (positions.length >= positionTotal) return;

    loadingMore = true;
    moreError = "";
    try {
      const page = await listPositions({
        limit: POSITION_PAGE_SIZE,
        offset: positions.length,
      });
      positions = [...positions, ...page.records];
      positionTotal = page.total;
    } catch (cause) {
      // 加载更多失败时保留已有列表，只提示本次失败。
      moreError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      loadingMore = false;
    }
  }

  function openCreateForm() {
    creating = true;
    createError = "";
    createNotice = "";
  }

  function resetCreateForm() {
    createName = "";
    createType = ORG_TYPES[0];
    createShortName = "";
    createCreditCode = "";
    createLicense = null;
    if (licenseInput) licenseInput.value = "";
  }

  function cancelCreate() {
    creating = false;
    createError = "";
    resetCreateForm();
  }

  function onLicenseChange(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    createLicense = input.files?.[0] ?? null;
  }

  // 先上传营业执照拿到 Nexus files.id，再带着 credit_code 创建机构。
  async function submitOrganization(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;

    const name = createName.trim();
    const creditCode = createCreditCode.trim();
    createError = "";
    createNotice = "";

    if (!name) {
      createError = $t.workspace.createErrors.name;
      return;
    }
    if (!creditCode) {
      createError = $t.workspace.createErrors.creditCode;
      return;
    }
    if (!createLicense) {
      createError = $t.workspace.createErrors.license;
      return;
    }
    if (createLicense.size > MAX_UPLOAD_BYTES) {
      createError = $t.workspace.createErrors.fileSize;
      return;
    }

    submitting = true;
    try {
      const uploaded = await uploadFile(createLicense);
      await createOrganization({
        name,
        short_name: createShortName.trim(),
        org_type: createType,
        metadata: {
          credit_code: creditCode,
          license_file_id: uploaded.id,
        },
      });

      resetCreateForm();
      creating = false;
      createNotice = $t.workspace.created;
      await loadMemberships();
    } catch (cause) {
      createError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      submitting = false;
    }
  }

  onMount(() => {
    const controller = new AbortController();
    let cancelled = false;
    // 邮件链接把一次性 token 放在查询串里：先记下来，登录态确认后再决定去向。
    const pendingEmailToken = new URL(window.location.href).searchParams.get("email_token");

    void (async () => {
      try {
        const current = await getSession(controller.signal);
        if (cancelled) return;
        session = current;
        if (current) {
          // 已有会话时链接没有用武之地，顺手把它从地址栏移除。
          clearEmailToken();
        } else if (pendingEmailToken) {
          emailToken = pendingEmailToken;
        }
      } catch (cause) {
        if (cancelled) return;
        sessionError = describeRequestError(cause, $t, window.location.origin);
      } finally {
        if (!cancelled) sessionReady = true;
      }
    })();

    return () => {
      cancelled = true;
      controller.abort();
    };
  });
</script>

<svelte:head>
  <title>{$t.meta.title}</title>
</svelte:head>

<main class="relative flex min-h-dvh justify-center overflow-x-clip bg-ink text-bone">
  <div class="relative flex min-h-dvh w-full max-w-[27rem] flex-col sm:max-w-[40rem]">
    <div class="grid-field pointer-events-none absolute inset-0" aria-hidden="true"></div>
    <div
      class="pointer-events-none absolute -left-24 top-0 h-72 w-72 rounded-full bg-acid/[0.08] blur-[100px]"
      aria-hidden="true"
    ></div>
    <div
      class="pointer-events-none absolute -right-24 bottom-24 h-64 w-64 rounded-full bg-emerald-400/[0.07] blur-[90px]"
      aria-hidden="true"
    ></div>

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

    {#if !sessionReady}
      <div class="flex flex-1 items-center justify-center px-5">
        <p class="text-[13px] text-muted">{$t.common.checkingSession}</p>
      </div>
    {:else if !session && emailToken}
      <EmailLinkConfirm
        token={emailToken}
        onAuthenticated={handleEmailLinkAuthenticated}
        onCancel={clearEmailToken}
      />
    {:else if !session}
      <AuthPanel
        onAuthenticated={handleAuthenticated}
        initialError={sessionError}
      />
    {:else}
      <div class="relative flex-1 px-5 pt-6 pb-6">
        {#if tab === "home"}
        <section aria-labelledby="hero-heading">
          <h1
            id="hero-heading"
            class="text-[2.75rem] leading-[1.08] font-medium {$locale === 'en'
              ? 'tracking-[-0.055em]'
              : 'tracking-[-0.02em]'}"
          >
            {$t.hero.line1}<br />
            {$t.hero.line2Prefix}<span class="text-acid">{$t.hero.accent}</span>{$t.hero.line2Suffix}
          </h1>
          <p class="mt-3 text-[15px] leading-6 text-muted">
            {$t.hero.signedIn(session.display_name || $t.common.yourAccount)}
          </p>
        </section>

        {#if $intent === "recruiter"}
          <section class="mt-6" aria-labelledby="workspace-heading">
            <div class="flex items-baseline justify-between gap-3">
              <h2 id="workspace-heading" class="text-sm font-medium">
                {$t.workspace.recruiterHeading}
              </h2>
              {#if orgPhase === "ready"}
                <span class="text-[11px] text-muted">
                  {$t.workspace.organizations(memberships.length)}
                </span>
              {/if}
            </div>
            <p class="mt-1 text-[11px] leading-5 text-muted">
              {$t.workspace.recruiterCopy}
            </p>

            {#if createNotice}
              <p
                class="mt-3 rounded-xl border border-acid/20 bg-acid/[0.06] px-4 py-3 text-[13px] leading-5 text-acid"
                role="status"
              >
                {createNotice}
              </p>
            {/if}

            {#if orgPhase === "loading"}
              <p class="mt-3 text-sm text-muted">{$t.workspace.loading}</p>
            {:else if orgPhase === "failed"}
              <p
                class="mt-3 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
                role="alert"
              >
                {orgError || $t.workspace.failed}
              </p>
            {:else}
              {#if memberships.length}
                <ul class="mt-3 space-y-2">
                  {#each memberships as membership (membership.organization.id)}
                    <OrganizationCard {membership} />
                  {/each}
                </ul>
                {#if hasUnverifiedOrganization}
                  <p class="mt-2 text-[11px] leading-5 text-amber-300">
                    {$t.workspace.pendingHint}
                  </p>
                {/if}
              {:else}
                <div
                  class="mt-3 rounded-2xl border border-line bg-ink-soft/90 px-4 py-4"
                >
                  <p class="text-sm text-bone/90">{$t.workspace.empty}</p>
                  <p class="mt-2 text-xs leading-5 text-muted">
                    {$t.workspace.emptyHint}
                  </p>
                  {#if !creating}
                    <button
                      type="button"
                      onclick={openCreateForm}
                      class="mt-3 flex min-h-11 w-full items-center justify-center gap-2 rounded-full bg-acid px-5 text-[14px] font-semibold text-ink transition-colors active:bg-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
                    >
                      {$t.workspace.createAction}
                    </button>
                  {/if}
                </div>
              {/if}

              {#if memberships.length && !creating}
                <button
                  type="button"
                  onclick={openCreateForm}
                  class="mt-3 flex min-h-11 w-full items-center justify-center gap-2 rounded-full border border-line text-[13px] text-muted transition-colors active:text-bone focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
                >
                  <span aria-hidden="true">＋</span>
                  {$t.workspace.createAction}
                </button>
              {/if}

              {#if creating}
                <form
                  class="mt-3 space-y-3 rounded-2xl border border-line bg-ink-soft/90 p-4"
                  onsubmit={submitOrganization}
                  aria-busy={submitting}
                >
                  <p class="text-sm font-medium">{$t.workspace.createHeading}</p>
                  <p class="text-[11px] leading-5 text-muted">
                    {$t.workspace.createHint}
                  </p>

                  <label class="block">
                    <span class="mb-1.5 block text-[13px] text-bone/80">
                      {$t.workspace.fields.name}
                    </span>
                    <input
                      class={fieldClass}
                      bind:value={createName}
                      type="text"
                      maxlength="120"
                      required
                      placeholder={$t.workspace.fields.namePlaceholder}
                    />
                  </label>

                  <label class="block">
                    <span class="mb-1.5 block text-[13px] text-bone/80">
                      {$t.workspace.fields.orgType}
                    </span>
                    <select class={fieldClass} bind:value={createType}>
                      {#each ORG_TYPES as type (type)}
                        <option value={type}>{type}</option>
                      {/each}
                    </select>
                  </label>

                  <label class="block">
                    <span class="mb-1.5 block text-[13px] text-bone/80">
                      {$t.workspace.fields.shortName}
                    </span>
                    <input
                      class={fieldClass}
                      bind:value={createShortName}
                      type="text"
                      maxlength="60"
                      placeholder={$t.workspace.fields.shortNamePlaceholder}
                    />
                  </label>

                  <label class="block">
                    <span class="mb-1.5 block text-[13px] text-bone/80">
                      {$t.workspace.fields.creditCode}
                    </span>
                    <input
                      class={fieldClass}
                      bind:value={createCreditCode}
                      type="text"
                      maxlength="32"
                      required
                      autocapitalize="characters"
                      spellcheck="false"
                      placeholder={$t.workspace.fields.creditCodePlaceholder}
                    />
                  </label>

                  <label class="block">
                    <span class="mb-1.5 block text-[13px] text-bone/80">
                      {$t.workspace.fields.license}
                    </span>
                    <input
                      bind:this={licenseInput}
                      class="block w-full text-[13px] text-muted file:mr-3 file:min-h-10 file:rounded-full file:border-0 file:bg-acid file:px-4 file:text-[13px] file:font-semibold file:text-ink"
                      type="file"
                      accept="image/jpeg,image/png,image/webp,application/pdf"
                      onchange={onLicenseChange}
                      required
                    />
                    <span class="mt-1 block text-[11px] leading-5 text-muted">
                      {$t.workspace.fields.licenseHelper}
                    </span>
                  </label>

                  {#if createError}
                    <p
                      class="rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-[13px] leading-5 text-amber-200"
                      role="alert"
                    >
                      {createError}
                    </p>
                  {/if}

                  <div class="flex gap-2 pt-1">
                    <button
                      type="submit"
                      disabled={submitting}
                      class="flex min-h-11 flex-1 items-center justify-center gap-2 rounded-full bg-acid px-4 text-[14px] font-semibold text-ink transition-colors active:bg-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
                    >
                      {submitting
                        ? $t.workspace.createSubmitting
                        : $t.workspace.createSubmit}
                    </button>
                    <button
                      type="button"
                      onclick={cancelCreate}
                      disabled={submitting}
                      class="min-h-11 rounded-full border border-line px-5 text-[13px] text-muted transition-colors active:text-bone disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
                    >
                      {$t.workspace.createCancel}
                    </button>
                  </div>
                </form>
              {/if}
            {/if}
          </section>
        {:else}
          <section class="mt-6" aria-labelledby="workspace-heading">
            <div class="flex items-baseline justify-between gap-3">
              <h2 id="workspace-heading" class="text-sm font-medium">
                {$t.workspace.seekerHeading}
              </h2>
              {#if positionPhase === "ready"}
                <span class="text-[11px] tabular-nums text-muted">
                  {$t.workspace.positionsCount(positionTotal)}
                </span>
              {/if}
            </div>
            <p class="mt-1 text-[11px] leading-5 text-muted">
              {$t.workspace.seekerCopy}
            </p>

            {#if positionPhase === "loading"}
              <p class="mt-3 text-sm text-muted">
                {$t.workspace.positionsLoading}
              </p>
            {:else if positionPhase === "failed"}
              <p
                class="mt-3 rounded-xl border border-amber-300/20 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-200"
                role="alert"
              >
                {positionError || $t.workspace.positionsFailed}
              </p>
            {:else if positions.length}
              <ul class="mt-3 space-y-2">
                {#each positions as position (position.id)}
                  <PositionCard {position} />
                {/each}
              </ul>

              {#if positions.length < positionTotal}
                <button
                  type="button"
                  onclick={loadMorePositions}
                  disabled={loadingMore}
                  class="mt-3 flex min-h-11 w-full items-center justify-center rounded-full border border-line text-[13px] text-muted transition-colors active:text-bone disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
                >
                  {loadingMore ? $t.workspace.loadingMore : $t.workspace.loadMore}
                </button>
              {/if}

              {#if moreError}
                <p class="mt-2 text-xs leading-5 text-amber-200" role="alert">
                  {moreError}
                </p>
              {/if}
            {:else}
              <div
                class="mt-3 rounded-2xl border border-line bg-ink-soft/90 px-4 py-4"
              >
                <p class="text-sm text-bone/90">{$t.workspace.positionsEmpty}</p>
              </div>
            {/if}
          </section>
        {/if}

        {:else if tab === "search"}
          <SearchPanel />
        {:else if tab === "messages"}
          <section aria-labelledby="messages-heading">
            <h1 id="messages-heading" class="text-[1.75rem] leading-tight font-medium">
              {$t.messages.heading}
            </h1>
            <p class="mt-2 text-[13px] leading-5 text-muted">{$t.messages.copy}</p>
            <div
              class="mt-6 rounded-2xl border border-line bg-ink-soft/90 px-4 py-6 text-center"
            >
              <p class="text-sm text-bone/90">{$t.messages.empty}</p>
              <p class="mt-2 text-xs leading-5 text-muted">{$t.messages.hint}</p>
            </div>
          </section>
        {:else}
          <ProfilePanel session={session} />
        {/if}
      </div>

      <BottomNav active={tab} onSelect={(next) => (tab = next)} />
    {/if}
  </div>
</main>
