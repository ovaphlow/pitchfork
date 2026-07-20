# Aceso App — Design Specification

> **Status (2026-07-20):** Implemented and reconciled with the current product boundaries. The authoritative topology, ports, configuration, and API-import contract are in [`docs/architecture.md`](../../architecture.md).

## Overview

Aceso is a new frontend app within the pitchfork monorepo. It provides a minimal authentication flow (login only, no registration) with a post-login dashboard. The corresponding backend already lives at `service-vertx-kotlin/apps/aceso/` and needs only auth routes added.

## Scope

- New Astro + React 19 app under `ui-astro/apps/aceso/`
- Login page at `/login`
- Dashboard page at `/dashboard` (requires auth)
- Index page at `/` redirects to `/login`
- No registration — no register link, no register API call, no sign-up functionality

## Architecture

```
User → /login (LoginForm.tsx)
         → RSA encrypt password (via @pitchfork/shared/aceso::login)
         → POST /crate-api/auth/v1/login (aceso backend)
         → JWT token → localStorage → redirect /dashboard

User → /dashboard (DashboardPage.tsx)
         → React component with welcome message & placeholder stats
```

## Backend Changes

**Repo:** `service-vertx-kotlin/apps/aceso/`

### File: `build.gradle.kts`
Add one dependency:
```kotlin
implementation(project(":libs:auth"))
```

### File: `src/main/kotlin/.../Main.kt`
- Import `AuthRoutes`
- Mount auth sub-router:
  ```kotlin
  apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx, pool, jwtSecret))
  ```

The database already has the `aceso` database created via `init-dbs.sh`. The auth lib handles migrations and user table creation.

## Frontend Structure

**Repo:** `ui-astro/apps/aceso/`

| Path | Type | Description |
|------|------|-------------|
| `package.json` | config | `@pitchfork/aceso`, deps: astro, react, tailwind, shared, ui |
| `tsconfig.json` | config | Extends `astro/tsconfigs/strict`, jsx: react-jsx |
| `astro.config.mjs` | config | Port `4324`, react integration, tailwindcss vite plugin |
| `src/env.d.ts` | types | Astro env types |
| `src/styles/global.css` | styles | `@import "tailwindcss"` |
| `src/layouts/AuthLayout.astro` | layout | Full-screen dark split layout with Aceso branding |
| `src/layouts/DashboardLayout.astro` | layout | Sidebar + topbar + content area (no nav items) |
| `src/components/AuthCard.tsx` | component | Login-only card wrapper with success transition |
| `src/components/LoginForm.tsx` | component | Email/password form, uses `@pitchfork/shared/aceso::login` |
| `src/components/DashboardPage.tsx` | component | Welcome greeting + placeholder stat cards |
| `src/components/ThemeToggle.tsx` | component | Sun/moon button that toggles dark/light class on `<html>` |
| `src/pages/index.astro` | page | Redirects to `/login` |
| `src/pages/login.astro` | page | Renders AuthCard with LoginForm |
| `src/pages/dashboard.astro` | page | Renders DashboardPage inside DashboardLayout |
| `src/styles/theme.css` | styles | `.light` class overrides for all CSS variables |

## Key Design Decisions

### No Registration
- `AuthCard.tsx` does not support `mode="register"` — removed entirely
- `index.astro` redirects to `/login` instead of showing a welcome page with login/register buttons
- `LoginForm.tsx` has no "注册" link
- The Aceso API export does not expose a registration function

### Port
- Port `4324`

### Theme Toggle (Dark / Light)
- `AuthLayout.astro` and `DashboardLayout.astro` support dark/light mode via CSS class `dark` / `light` on `<html>`
- Dark mode: the existing CSS variables (oklch dark values) remain as default
- Light mode: `.light` class overrides with light-friendly oklch values
- A moon/sun toggle button in the dashboard topbar switches between themes
- Preference persisted to `localStorage('aceso-theme')`
- Inline `<script>` in layout `<head>` reads localStorage and applies class **before** first paint (no flash)
- AuthLayout also respects the theme preference

### Dashboard
- Minimal placeholder: welcome message + 3 stat cards (总用户数, 今日活跃, 系统状态)
- Styled with Tailwind v4, matching the existing dark/light theme system
- Fully client-rendered React component (no SSR data fetching)

## Constraints

- The Aceso backend uses port `8422`, independently from Trainova on `8421`
- `PITCHFORK_CONFIG`, `PITCHFORK_DB_PASSWORD`, and `PITCHFORK_JWT_SECRET` are mandatory; no secret or configuration fallback exists
- Auth routes require the `users` table which is created by flyway migrations in the `:libs:auth` module
- The `aceso` database is already created via `init-dbs.sh`

## Non-Goals

- No user management / role management / settings pages
- No registration API or UI
- No knowledge / training / exam / skills modules
- No analytics
- No multi-language support
