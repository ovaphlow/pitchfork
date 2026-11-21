# Pitchfork Architecture

## Products

| Product | Backend | Database | API | Frontend |
|---|---|---|---:|---|
| Trainova | `apps:trainova` | `ovaphlow` | `8421` | `auth` (`4321`), `admin` (`4322`), `worker` (`4323`) |
| Aceso | `apps:aceso` | `aceso` | `8422` | `aceso` (`4324`)，路由前缀 `/eldercare/*` |

Backend routes use `/crate-api/<module>/v1/<resource>`. Each product has its own
composition root, API port and database; domain libraries and API clients do not
cross product boundaries.

## Ownership

| Layer | Libraries |
|---|---|
| Platform | `logging`, `database`, `common` |
| Trainova | `knowledge`, `skills`, `trainings`, `exams`, `onsite`, `analytics` |
| Aceso | `healthcare`, `inventories`, `pharmacy`, `nursing`, `dining` |

An app owns route mounting, runtime configuration and its declared libraries.
Promote a capability to the platform layer only after ownership, data and API
contracts are product-neutral.

### Aceso 是养老单产品（056，2026-11-21）

Aceso 已收窄为**养老单产品**：医疗与儿保不再是产品内的「域」，也没有运行期域切换。

- 前端一级路径 = 产品域：`/eldercare/*`。未来儿保以 `/childcare/*` 平行回归，
  两套页面物理隔离，不再共用一棵菜单树与一套 `domainLabels` 文案；
- `healthcare` 不再是孵化中的通用医疗域，而是 Aceso 的养老临床能力（患者、入住、
  医嘱、护理、体检、慢病、收费）；其表结构里残留的 `child_*` 表为 052–054 的历史遗留，
  代码已下线，可供未来儿保重做时参考或清理；
- 儿保相关实现（052/053/054）已从 `healthcare`/`nursing`/`dining`/`pharmacy` 全量移除，
  详见 [`plans/056.aceso-eldercare-only-cutover.md`](plans/056.aceso-eldercare-only-cutover.md)。

The former `service-nexus-shared` capabilities (`settings`, `messages`, `files`,
`interactions`, role/department directories) now live in platformd behind
`/crate-api/v1/*` (plan 055).

## Configuration And Data

- Backends load a local copy of `apps/<product>/config.example.json` through
  `PITCHFORK_CONFIG`; passwords come only from the environment. Never commit
  secrets, local `config.json`, `.env` or generated jOOQ configuration.
- A domain table, its Flyway migration and its jOOQ config belong to the owning
  `libs/<module>`. The app receives only migrations from its runtime libraries.
- Trainova `apps/trainova/.../V5-V7` is executed legacy history: do not move,
  duplicate or add new schema there. Migration bands are defined in root
  [`AGENTS.md`](../AGENTS.md).

## Frontend

Every frontend requires an explicit `PUBLIC_API_URL`. Trainova apps import
`@pitchfork/shared/trainova`; Aceso imports `@pitchfork/shared/aceso`. Add an API
client export only after its backend route is mounted in that same product.
