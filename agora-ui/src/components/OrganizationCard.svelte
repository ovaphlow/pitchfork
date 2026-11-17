<script lang="ts">
  import type { Membership } from "../lib/api";
  import OrganizationPositions from "./OrganizationPositions.svelte";

  interface Props {
    membership: Membership;
  }

  let { membership }: Props = $props();
</script>

<li class="rounded-2xl border border-line bg-ink-soft/90 px-4 py-3">
  <div class="flex items-center justify-between gap-3">
    <p class="min-w-0 truncate text-sm text-bone/90">
      {membership.organization.name}
    </p>
    <span
      class="shrink-0 rounded-full border border-line px-2.5 py-1 font-mono text-[11px] text-bone/70"
    >
      {membership.member_role}
    </span>
  </div>
  <p class="mt-1 text-[11px] text-muted">
    {[membership.organization.org_type, membership.organization.status]
      .filter((part) => part.length > 0)
      .join(" · ")}
  </p>

  <OrganizationPositions organization={membership.organization} />
</li>
