# Aceso App — Design Specification

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
         → RSA encrypt password (via @pitchfork/shared::login)
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
  apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx, pool))
  ```

The database already has the `aceso` database created via `init-dbs.sh`. The auth lib handles migrations and user table creation.

## Frontend Structure

**Repo:** `ui-astro/apps/aceso/`

| Path | Type | Description |
|------|------|-------------|
| `package.json` | config | `@pitchfork/aceso`, deps: astro, react, tailwind, shared, ui |
| `tsconfig.json` | config | Extends `astro/tsconfigs/strict`, jsx: react-jsx |
| `astro.config.mjs` | config | Port `4323`, react integration, tailwindcss vite plugin |
| `src/env.d.ts` | types | Astro env types |
| `src/styles/global.css` | styles | `@import "tailwindcss"` |
| `src/layouts/AuthLayout.astro` | layout | Full-screen dark split layout with Aceso branding |
| `src/layouts/DashboardLayout.astro` | layout | Sidebar + topbar + content area (no nav items) |
| `src/components/AuthCard.tsx` | component | Login-only card wrapper with success transition |
| `src/components/LoginForm.tsx` | component | Email/password form, uses `@pitchfork/shared::login` |
| `src/components/DashboardPage.tsx` | component | Welcome greeting + placeholder stat cards |
| `src/pages/index.astro` | page | Redirects to `/login` |
| `src/pages/login.astro` | page | Renders AuthCard with LoginForm |
| `src/pages/dashboard.astro` | page | Renders DashboardPage inside DashboardLayout |

## Key Design Decisions

### No Registration
- `AuthCard.tsx` does not support `mode="register"` — removed entirely
- `index.astro` redirects to `/login` instead of showing a welcome page with login/register buttons
- `LoginForm.tsx` has no "注册" link
- The shared `signUp()` function exists in `@pitchfork/shared` but is never imported

### Port
- Port `4323` to avoid collision with auth (`4321`), admin, and worker apps

### Dashboard
- Minimal placeholder: welcome message + 3 stat cards (总用户数, 今日活跃, 系统状态)
- Styled with Tailwind v4, matching the existing dark theme
- Fully client-rendered React component (no SSR data fetching)

## Constraints

- The aceso backend already uses port `8421` (same as trainova backend)
- JWT secret defaults to `"crate-default-secret"` if not specified in config.json
- Auth routes require the `users` table which is created by flyway migrations in the `:libs:auth` module
- The `aceso` database is already created via `init-dbs.sh`

## Non-Goals

- No user management / role management / settings pages
- No registration API or UI
- No knowledge / training / exam / skills modules
- No analytics
- No multi-language support
