# Pitchfork Architecture

## Products

| Product | Backend | Database | API | Frontend |
|---|---|---|---:|---|
| Trainova | `apps:trainova` | `ovaphlow` | `8421` | `auth` (`4321`), `admin` (`4322`), `worker` (`4323`) |
| Aceso | `apps:aceso` | `aceso` | `8422` | `aceso` (`4324`) |

Backend routes use `/crate-api/<module>/v1/<resource>`. Each product has its own
composition root, API port and database; domain libraries and API clients do not
cross product boundaries.

## Ownership

| Layer | Libraries |
|---|---|
| Platform | `permissions`, `logging`, `database`, `common` |
| Trainova | `knowledge`, `skills`, `trainings`, `exams`, `onsite`, `analytics` |
| Aceso | `inventories`, `pharmacy`, `nursing`, `dining` |
| Incubating | `healthcare` |

An app owns route mounting, runtime configuration and its declared libraries.
Promote a capability to the platform layer only after ownership, data and API
contracts are product-neutral. `healthcare` cannot be mounted until it has a
complete product contract. Nexus owns shared `settings`, `messages` and `files`.

## Configuration And Data

- Backends load a local copy of `apps/<product>/config.example.json` through
  `PITCHFORK_CONFIG`; passwords and `PITCHFORK_JWT_SECRET` come only from the
  environment. Never commit secrets, local `config.json`, `.env` or generated
  jOOQ configuration.
- A domain table, its Flyway migration and its jOOQ config belong to the owning
  `libs/<module>`. The app receives only migrations from its runtime libraries.
- Trainova `apps/trainova/.../V5-V7` is executed legacy history: do not move,
  duplicate or add new schema there. Migration bands are defined in root
  [`AGENTS.md`](../AGENTS.md).

## Frontend

Every frontend requires an explicit `PUBLIC_API_URL`. Trainova apps import
`@pitchfork/shared/trainova`; Aceso imports `@pitchfork/shared/aceso`. Add an API
client export only after its backend route is mounted in that same product.
