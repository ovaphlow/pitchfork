<script lang="ts">
  import { onMount } from "svelte";

  import { describeRequestError, t } from "../i18n";
  import {
    createPosition,
    listOrganizationPositions,
    setPositionStatus,
    type MembershipOrganization,
    type OrganizationPosition,
  } from "../lib/api";

  interface Props {
    organization: MembershipOrganization;
  }

  let { organization }: Props = $props();

  // 枚举取值必须与后端 positions 模型保持一致；空值由后端补默认值。
  const EMPLOYMENT_TYPES = ["全职", "兼职", "实习", "临时", "项目制"];
  const WORK_MODES = ["坐班", "远程", "混合"];
  const EDUCATIONS = ["不限", "高中及以下", "大专", "本科", "硕士", "博士"];
  const EXPERIENCES = ["不限", "应届生", "1-3年", "3-5年", "5-10年", "10年以上"];
  const SALARY_UNITS = ["时", "日", "月", "年"];

  const fieldClass =
    "min-h-10 w-full rounded-lg border border-line bg-ink-soft px-3 text-[13px] text-bone placeholder:text-muted/60 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";
  const actionClass =
    "min-h-9 rounded-full border border-line px-3 text-[12px] text-muted transition-colors active:text-bone disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid";

  let positions = $state<OrganizationPosition[]>([]);
  let phase = $state<"loading" | "ready" | "failed">("loading");
  let error = $state("");

  let creating = $state(false);
  let submitting = $state(false);
  let actionID = $state("");
  let actionError = $state("");

  let createError = $state("");
  let createNotice = $state("");

  let title = $state("");
  let employmentType = $state(EMPLOYMENT_TYPES[0]);
  let workMode = $state(WORK_MODES[0]);
  let headcount = $state("1");
  let negotiable = $state(false);
  let salaryMin = $state("");
  let salaryMax = $state("");
  let salaryUnit = $state("月");
  let education = $state(EDUCATIONS[0]);
  let experience = $state(EXPERIENCES[0]);
  let city = $state("");
  let description = $state("");
  let requirements = $state("");

  const verified = $derived(organization.status === "已认证");

  async function load(signal?: AbortSignal) {
    phase = "loading";
    error = "";
    try {
      const page = await listOrganizationPositions(organization.id, { limit: 50 }, signal);
      if (signal?.aborted) return;
      positions = page.records;
      phase = "ready";
    } catch (cause) {
      if (signal?.aborted) return;
      error = describeRequestError(cause, $t, window.location.origin);
      phase = "failed";
    }
  }

  onMount(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  });

  function salaryLabel(position: OrganizationPosition): string {
    if (
      position.salary_negotiable ||
      (position.salary_min === null && position.salary_max === null)
    ) {
      return $t.workspace.salaryNegotiable;
    }
    return $t.workspace.salaryRange(
      position.salary_min,
      position.salary_max,
      position.salary_unit,
    );
  }

  function statusClass(status: string): string {
    switch (status) {
      case "招聘中":
        return "border-acid/30 text-acid";
      case "已暂停":
      case "已停招":
        return "border-amber-300/30 text-amber-300";
      default:
        return "border-line text-muted";
    }
  }

  // The API stops a role once its window elapses, but a long-lived page can hold
  // stale data; derive the same state from open_until so the UI matches.
  function effectiveStatus(position: OrganizationPosition): string {
    if (
      position.status === "招聘中" &&
      position.open_until !== null &&
      Date.parse(position.open_until) <= Date.now()
    ) {
      return "已停招";
    }
    return position.status;
  }

  // Round, not ceil: the window is exactly N days at publish/restore time, and
  // any client/server clock skew would make ceil report N+1 on that boundary.
  function windowDaysLeft(position: OrganizationPosition): number {
    if (!position.open_until) return 0;
    return Math.max(
      1,
      Math.round((Date.parse(position.open_until) - Date.now()) / 86_400_000),
    );
  }

  function resetForm() {
    title = "";
    employmentType = EMPLOYMENT_TYPES[0];
    workMode = WORK_MODES[0];
    headcount = "1";
    negotiable = false;
    salaryMin = "";
    salaryMax = "";
    salaryUnit = "月";
    education = EDUCATIONS[0];
    experience = EXPERIENCES[0];
    city = "";
    description = "";
    requirements = "";
  }

  function openForm() {
    creating = true;
    createError = "";
    createNotice = "";
  }

  function cancelForm() {
    creating = false;
    createError = "";
    resetForm();
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;

    const name = title.trim();
    const count = Number.parseInt(headcount, 10);
    const minimum = negotiable || salaryMin.trim() === "" ? null : Number.parseInt(salaryMin, 10);
    const maximum = negotiable || salaryMax.trim() === "" ? null : Number.parseInt(salaryMax, 10);

    createError = "";
    createNotice = "";

    if (!name) {
      createError = $t.positions.errors.title;
      return;
    }
    if (!Number.isFinite(count) || count < 1) {
      createError = $t.positions.errors.headcount;
      return;
    }
    if (!negotiable && (minimum === null || !Number.isFinite(minimum) || minimum < 1)) {
      createError = $t.positions.errors.salaryMin;
      return;
    }
    if (
      minimum !== null &&
      maximum !== null &&
      Number.isFinite(minimum) &&
      Number.isFinite(maximum) &&
      maximum < minimum
    ) {
      createError = $t.positions.errors.salaryRange;
      return;
    }

    submitting = true;
    try {
      const created = await createPosition({
        organization_id: organization.id,
        title: name,
        employment_type: employmentType,
        work_mode: workMode,
        headcount: count,
        salary_negotiable: negotiable,
        salary_min: negotiable || minimum === null ? undefined : minimum,
        salary_max: negotiable || maximum === null ? undefined : maximum,
        salary_unit: salaryUnit,
        education,
        experience,
        city: city.trim(),
        metadata: {
          description: description.trim(),
          requirements: requirements.trim(),
        },
      });
      positions = [created, ...positions];
      resetForm();
      creating = false;
      createNotice = $t.positions.created;
    } catch (cause) {
      createError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      submitting = false;
    }
  }

  async function transition(position: OrganizationPosition, next: string) {
    if (actionID) return;
    actionID = position.id;
    actionError = "";
    try {
      const updated = await setPositionStatus(position.id, next);
      positions = positions.map((item) => (item.id === updated.id ? updated : item));
    } catch (cause) {
      actionError = describeRequestError(cause, $t, window.location.origin);
    } finally {
      actionID = "";
    }
  }
</script>

<div class="mt-3 border-t border-line pt-3">
  {#if createNotice}
    <p
      class="mb-3 rounded-lg border border-acid/20 bg-acid/[0.06] px-3 py-2 text-[12px] leading-5 text-acid"
      role="status"
    >
      {createNotice}
    </p>
  {/if}

  {#if phase === "loading"}
    <p class="text-[13px] text-muted">{$t.positions.loading}</p>
  {:else if phase === "failed"}
    <p class="rounded-lg border border-amber-300/20 bg-amber-300/[0.06] px-3 py-2 text-[12px] leading-5 text-amber-200" role="alert">
      {error || $t.positions.failed}
    </p>
  {:else}
    {#if !creating}
      <button type="button" onclick={openForm} class={`${actionClass} w-full`}>
        ＋ {$t.positions.create}
      </button>
    {/if}

    {#if creating}
      <form class="space-y-2.5" onsubmit={submit} aria-busy={submitting}>
        <p class="text-[13px] font-medium">{$t.positions.createHeading}</p>
        <p class="text-[11px] leading-5 text-muted">{$t.positions.createHint}</p>

        <label class="block">
          <span class="mb-1 block text-[12px] text-bone/80">{$t.positions.fields.title}</span>
          <input
            class={fieldClass}
            bind:value={title}
            type="text"
            maxlength="200"
            required
            placeholder={$t.positions.fields.titlePlaceholder}
          />
        </label>

        <div class="grid grid-cols-2 gap-2">
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.employmentType}
            </span>
            <select class={fieldClass} bind:value={employmentType}>
              {#each EMPLOYMENT_TYPES as value (value)}
                <option value={value}>{value}</option>
              {/each}
            </select>
          </label>
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.workMode}
            </span>
            <select class={fieldClass} bind:value={workMode}>
              {#each WORK_MODES as value (value)}
                <option value={value}>{value}</option>
              {/each}
            </select>
          </label>
        </div>

        <div class="grid grid-cols-2 gap-2">
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.headcount}
            </span>
            <input
              class={fieldClass}
              bind:value={headcount}
              type="number"
              inputmode="numeric"
              min="1"
              max="999"
              required
            />
          </label>
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.salaryUnit}
            </span>
            <select class={fieldClass} bind:value={salaryUnit}>
              {#each SALARY_UNITS as value (value)}
                <option value={value}>{value}</option>
              {/each}
            </select>
          </label>
        </div>

        <label class="flex min-h-10 items-center gap-2 rounded-lg border border-line px-3">
          <input
            type="checkbox"
            class="size-4 accent-[color:var(--color-acid)]"
            bind:checked={negotiable}
          />
          <span class="text-[12px] text-bone/80">{$t.positions.fields.negotiable}</span>
        </label>

        {#if !negotiable}
          <div class="grid grid-cols-2 gap-2">
            <label class="block">
              <span class="mb-1 block text-[12px] text-bone/80">
                {$t.positions.fields.salaryMin}
              </span>
              <input
                class={fieldClass}
                bind:value={salaryMin}
                type="number"
                inputmode="numeric"
                min="1"
                required
              />
            </label>
            <label class="block">
              <span class="mb-1 block text-[12px] text-bone/80">
                {$t.positions.fields.salaryMax}
              </span>
              <input
                class={fieldClass}
                bind:value={salaryMax}
                type="number"
                inputmode="numeric"
                min="1"
              />
            </label>
          </div>
        {/if}

        <div class="grid grid-cols-2 gap-2">
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.education}
            </span>
            <select class={fieldClass} bind:value={education}>
              {#each EDUCATIONS as value (value)}
                <option value={value}>{value}</option>
              {/each}
            </select>
          </label>
          <label class="block">
            <span class="mb-1 block text-[12px] text-bone/80">
              {$t.positions.fields.experience}
            </span>
            <select class={fieldClass} bind:value={experience}>
              {#each EXPERIENCES as value (value)}
                <option value={value}>{value}</option>
              {/each}
            </select>
          </label>
        </div>

        <label class="block">
          <span class="mb-1 block text-[12px] text-bone/80">
            {$t.positions.fields.city}
          </span>
          <input
            class={fieldClass}
            bind:value={city}
            type="text"
            maxlength="32"
            placeholder={$t.positions.fields.cityPlaceholder}
          />
        </label>

        <label class="block">
          <span class="mb-1 block text-[12px] text-bone/80">
            {$t.positions.fields.description}
          </span>
          <textarea
            class={`${fieldClass} min-h-20 py-2`}
            bind:value={description}
            rows="4"
            maxlength="20000"
            placeholder={$t.positions.fields.descriptionPlaceholder}
          ></textarea>
        </label>

        <label class="block">
          <span class="mb-1 block text-[12px] text-bone/80">
            {$t.positions.fields.requirements}
          </span>
          <textarea
            class={`${fieldClass} min-h-20 py-2`}
            bind:value={requirements}
            rows="4"
            maxlength="20000"
            placeholder={$t.positions.fields.requirementsPlaceholder}
          ></textarea>
        </label>

        {#if createError}
          <p class="rounded-lg border border-amber-300/20 bg-amber-300/[0.06] px-3 py-2 text-[12px] leading-5 text-amber-200" role="alert">
            {createError}
          </p>
        {/if}

        <div class="flex gap-2">
          <button
            type="submit"
            disabled={submitting}
            class="flex min-h-10 flex-1 items-center justify-center rounded-full bg-acid px-4 text-[13px] font-semibold text-ink transition-colors active:bg-bone disabled:cursor-wait disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
          >
            {submitting ? $t.positions.createSubmitting : $t.positions.createSubmit}
          </button>
          <button
            type="button"
            onclick={cancelForm}
            disabled={submitting}
            class="min-h-10 rounded-full border border-line px-4 text-[12px] text-muted transition-colors active:text-bone disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-acid"
          >
            {$t.positions.cancel}
          </button>
        </div>
      </form>
    {/if}

    <p class="mt-3 text-[11px] leading-5 text-muted">{$t.positions.windowHint}</p>

    {#if positions.length}
      <ul class="mt-3 space-y-2">
        {#each positions as position (position.id)}
          {@const state = effectiveStatus(position)}
          <li class="rounded-xl border border-line bg-ink px-3 py-2.5">
            <div class="flex items-baseline justify-between gap-3">
              <p class="min-w-0 truncate text-[13px] text-bone/90">{position.title}</p>
              <span
                class={`shrink-0 rounded-full border px-2 py-0.5 font-mono text-[10px] ${statusClass(
                  state,
                )}`}
              >
                {state}
              </span>
            </div>
            <p class="mt-1 text-[11px] text-muted">
              {[
                position.employment_type,
                position.work_mode,
                $t.positions.headcountValue(position.headcount),
                position.city,
              ]
                .filter((part) => part.length > 0)
                .join(" · ")}
            </p>
            <p class="mt-1 text-[12px] tabular-nums text-acid">
              {salaryLabel(position)}
            </p>

            {#if state === "招聘中"}
              <p class="mt-1 text-[11px] text-muted">
                {$t.positions.windowLeft(windowDaysLeft(position))}
              </p>
            {:else if state === "已停招"}
              <p class="mt-1 text-[11px] leading-5 text-amber-300">
                {$t.positions.stoppedHint}
              </p>
            {:else if state === "已关闭"}
              <p class="mt-1 text-[11px] leading-5 text-muted">
                {$t.positions.closedHint}
              </p>
            {/if}

            <div class="mt-2 flex flex-wrap gap-2">
              {#if state === "草稿"}
                <button
                  type="button"
                  onclick={() => transition(position, "招聘中")}
                  disabled={!verified || actionID !== ""}
                  class={`${actionClass} border-acid/30 text-acid`}
                >
                  {$t.positions.publish}
                </button>
              {:else if state === "招聘中"}
                <button
                  type="button"
                  onclick={() => transition(position, "已暂停")}
                  disabled={actionID !== ""}
                  class={actionClass}
                >
                  {$t.positions.pause}
                </button>
                <button
                  type="button"
                  onclick={() => transition(position, "已关闭")}
                  disabled={actionID !== ""}
                  class={actionClass}
                >
                  {$t.positions.close}
                </button>
              {:else if state === "已暂停"}
                <button
                  type="button"
                  onclick={() => transition(position, "招聘中")}
                  disabled={!verified || actionID !== ""}
                  class={`${actionClass} border-acid/30 text-acid`}
                >
                  {$t.positions.resume}
                </button>
                <button
                  type="button"
                  onclick={() => transition(position, "已关闭")}
                  disabled={actionID !== ""}
                  class={actionClass}
                >
                  {$t.positions.close}
                </button>
              {:else if state === "已停招"}
                <button
                  type="button"
                  onclick={() => transition(position, "招聘中")}
                  disabled={!verified || actionID !== ""}
                  class={`${actionClass} border-acid/30 text-acid`}
                >
                  {$t.positions.restore}
                </button>
                <button
                  type="button"
                  onclick={() => transition(position, "已关闭")}
                  disabled={actionID !== ""}
                  class={actionClass}
                >
                  {$t.positions.close}
                </button>
              {:else if state === "已关闭"}
                <button
                  type="button"
                  onclick={() => transition(position, "招聘中")}
                  disabled={!verified || actionID !== ""}
                  class={`${actionClass} border-acid/30 text-acid`}
                >
                  {$t.positions.restore}
                </button>
              {/if}
            </div>
          </li>
        {/each}
      </ul>
    {:else if !creating}
      <p class="mt-3 text-[13px] text-muted">{$t.positions.empty}</p>
    {/if}

    {#if !verified}
      <p class="mt-2 text-[11px] leading-5 text-amber-300">
        {$t.positions.notVerified}
      </p>
    {/if}

    {#if actionError}
      <p class="mt-2 text-[11px] leading-5 text-amber-200" role="alert">
        {actionError || $t.positions.actionFailed}
      </p>
    {/if}
  {/if}
</div>
