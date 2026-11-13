<script lang="ts">
  import { t } from "../i18n";
  import { intent, setIntent, type Intent } from "../lib/intent";

  interface Props {
    /** 分组标题，如「登录身份」或「当前身份」。 */
    label: string;
  }

  let { label }: Props = $props();

  const options: { value: Intent; key: "jobSeeker" | "recruiter" }[] = [
    { value: "job_seeker", key: "jobSeeker" },
    { value: "recruiter", key: "recruiter" },
  ];
</script>

<fieldset class="min-w-0 border-0 p-0">
  <legend class="text-[11px] tracking-[0.16em] text-muted">{label}</legend>
  <div
    class="mt-2 grid grid-cols-2 gap-1 rounded-full border border-line bg-ink-soft p-1"
  >
    {#each options as option (option.value)}
      <label
        class="flex min-h-10 cursor-pointer items-center justify-center rounded-full px-3 text-[13px] font-medium text-muted transition-colors has-[:checked]:bg-acid has-[:checked]:text-ink has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-acid"
      >
        <input
          class="sr-only"
          type="radio"
          name="login-intent"
          value={option.value}
          checked={$intent === option.value}
          onchange={() => setIntent(option.value)}
        />
        {$t.identity[option.key]}
      </label>
    {/each}
  </div>
</fieldset>
