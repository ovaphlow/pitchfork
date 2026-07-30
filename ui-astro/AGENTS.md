# Astro Frontend Work Rules

Root [`../AGENTS.md`](../AGENTS.md) defines the shared API and verification rules.
Product boundaries and environment configuration are in
[`../docs/architecture.md`](../docs/architecture.md).

## Scope And Structure

- Development servers are user-managed. Do not run `pnpm dev`, `pnpm preview` or
  `astro dev`; use `pnpm --filter <app> build` or `pnpm build` for verification.
- `apps/*/src/pages/` are thin Astro route and layout wrappers. Put page behavior
  in the app's React `components/` directory.
- `packages/shared/` owns typed API clients; `packages/ui/` owns reusable,
  product-neutral UI components. Keep business logic out of both route pages and
  the UI package.
- Limit app changes to the target app. Treat changes in `packages/` as shared
  changes and check all affected consumers.

## Product API Boundary

- Every app requires its own `PUBLIC_API_URL`; there is no fallback backend.
- Trainova apps import only `@pitchfork/shared/trainova`; Aceso imports only
  `@pitchfork/shared/aceso`. Add an API function to the matching client before
  use, instead of writing a request or another product URL in an app.
- The shared client owns JSON headers, JWT token handling, password encryption
  and 401 cleanup. Do not duplicate those behaviors in a component.

## UI Changes

- Reuse the target app's layouts, `global.css` theme tokens and existing
  `@pitchfork/ui` components before adding a new pattern.
- Use Tailwind utility classes rather than inline styles. Inspect component types
  in `packages/ui/src/index.tsx` instead of relying on a separate props catalog.
