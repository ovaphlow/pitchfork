# service-vertx-kotlin — Skills & Capabilities

## Overview

**Crate** (Kubernetes: 克拉特) — a manufacturing employee training & quality management platform. Manages the full lifecycle of employee skill development: knowledge base, training courses, exam/assessment, on-site device QR scanning, AI-powered Q&A, preventive push rules, role-based access control, and analytics dashboards.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.3.x |
| Framework | Vert.x 4.5.x (Web, Config, Auth JWT) |
| Database | PostgreSQL |
| DB Access | jOOQ 3.19 + Flyway migrations |
| Build | Gradle 8.14 (wrapper), Kotlin DSL |
| Auth | JWT (HS256) + RSA-encrypted password transport |
| Logging | SLF4J + Logback + Logstash JSON encoder |
| JDK | 21+ (toolchain = 25) |

## Architecture

Monorepo: `apps/service` (entrypoint) + `libs/*` (domain modules).

All API routes are mounted under `/crate-api/<module>/v1/*`. The entrypoint `Main.kt` does CORS, JWT setup, DB pool creation, Flyway migration, and sub-router mounting.

## Module & API Reference

### Auth — `POST /crate-api/auth/v1/*`

RSA-encrypted password transport. Login returns JWT + user object.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/public-key` | Get RSA public key (Base64 X.509) for password encryption |
| POST | `/login` | Login with `email` + `password` (RSA encrypted), returns JWT |
| POST | `/sign-up` | Register with `email` + `password` (RSA encrypted) |
| GET | `/verify` | Verify JWT via `Authorization: Bearer <token>` |

### Settings — `POST /crate-api/settings/v1/*`

Generic key-value store using `settings` table with `category` discriminator. Also has dedicated department CRUD.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/departments` | List all departments (tree) |
| POST | `/departments` | Create department (`name`, `code`, `parent_code`, `description`) |
| PUT | `/departments/:id` | Update department |
| DELETE | `/departments/:id` | Delete department |
| GET | `/knowledge-categories` | List knowledge categories |
| POST | `/knowledge-categories` | Create category (`code`, `name`) |
| PUT | `/knowledge-categories/:code` | Update category name |
| DELETE | `/knowledge-categories/:code` | Delete category |
| GET | `/knowledge-tags` | List knowledge tags |
| POST | `/knowledge-tags` | Create tag (`code`, `name`) |
| DELETE | `/knowledge-tags/:code` | Delete tag |

### Files — `POST /crate-api/files/v1/*`

Static file serving.

### Permission — `POST /crate-api/permission/v1/*`

Hybrid RBAC + ReBAC + ABAC authorization engine.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/authorize` | Check access: `user_id` + `action` + `resource_type` + `resource_id` |
| POST | `/roles` | Create role (`name`, `description`) |
| GET | `/roles` | List all roles |
| GET | `/roles/:id` | Get role |
| PUT | `/roles/:id` | Update role |
| DELETE | `/roles/:id` | Delete role |
| POST | `/permissions` | Create permission (`name`, `resource`, `action`) |
| GET | `/permissions` | List permissions |
| DELETE | `/permissions/:id` | Delete permission |
| POST | `/roles/:id/permissions` | Assign permission to role |
| DELETE | `/roles/:id/permissions/:permId` | Remove permission from role |
| GET | `/roles/:id/permissions` | Get role permissions |
| POST | `/assignments` | Assign role to user (`user_id`, `role_id`, `scope_type`, `scope_id`) |
| DELETE | `/assignments` | Unassign role (query params) |
| GET | `/users/:userId/assignments` | Get user's role assignments |
| POST | `/relations` | Create ReBAC relation (`subject_type`, `subject_id`, `relation`, `object_type`, `object_id`) |
| DELETE | `/relations` | Delete ReBAC relation |
| GET | `/relations` | List ReBAC relations |
| POST | `/policies` | Create ABAC policy (`resource_type`, `action`, `effect`, `priority`, `condition_json`) |
| GET | `/policies` | List ABAC policies |
| PUT | `/policies/:id` | Update policy |
| DELETE | `/policies/:id` | Delete policy |
| PUT | `/users/:userId/attributes` | Set user attributes (JSON body) |
| GET | `/users/:userId/attributes` | Get user attributes |
| DELETE | `/users/:userId/attributes/:key` | Delete user attribute |

### Messages — `POST /crate-api/messages/v1/*`

Internal notification/messaging system.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/messages` | Create message (`message_type`, `sender_id`, `sender_type`, `receiver_id`, `receiver_type`, `payload`) |
| GET | `/messages` | List messages (filterable by type, sender, receiver, status) |
| GET | `/messages/:id` | Get message |
| PUT | `/messages/:id` | Update message (status, payload) |
| DELETE | `/messages/:id` | Delete message |
| PATCH | `/messages/:id/status` | Update message status |

### Users — `POST /crate-api/users/v1/*`

Employee user management.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/users` | List users (`search`, `status`, paginated) |
| PATCH | `/users/:id/status` | Update user status |
| PUT | `/users/:id` | Update user (e.g., `department_code`) |

### Knowledge — `POST /crate-api/knowledge/v1/*`

Knowledge base: hierarchical categories, versioned entries with content blocks, feedback system.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/categories` | List knowledge categories |
| POST | `/categories` | Create category (`name`, `parent_id`, `sort_order`, `description`) |
| PUT | `/categories/:id` | Update category |
| DELETE | `/categories/:id` | Delete category |
| GET | `/entries` | List entries (filter by `type`, `status`, `search`, `category_id`, `tags`, paginated) |
| POST | `/entries` | Create entry (`title`, `type`, `category_ids`, `device_ids`, `position_ids`, `tags`, `metadata`) |
| GET | `/entries/:id` | Get entry details |
| PUT | `/entries/:id` | Update entry |
| PATCH | `/entries/:id/status` | Update entry status |
| DELETE | `/entries/:id` | Delete entry |
| GET | `/entries/:id/versions` | List versions of an entry |
| POST | `/entries/:id/versions` | Create version (`content`, `content_blocks`, `attachment_files`, `change_note`, `version_number`) |
| POST | `/entries/:id/versions/:vid/approve` | Approve version |
| POST | `/entries/:id/versions/:vid/reject` | Reject version |
| GET | `/entries/:id/feedbacks` | List feedbacks for an entry |
| POST | `/entries/:id/feedbacks` | Create feedback (`type`, `content`) |
| POST | `/feedbacks/:id/reply` | Reply to feedback |

### Skills — `POST /crate-api/skills/v1/*`

Skills, positions (hierarchical), certificates, employee skill assessments.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/positions` | List positions (paginated) |
| POST | `/positions` | Create position (`name`, `parent_id`, `skill_requirements`, `assessment_config`, `extra`) |
| GET | `/positions/tree` | Get position tree (hierarchical) |
| GET | `/positions/:id` | Get position |
| PUT | `/positions/:id` | Update position |
| DELETE | `/positions/:id` | Delete position |
| GET | `/skills` | List skills (filter by `category`, paginated) |
| POST | `/skills` | Create skill (`name`, `category`, `evaluation_criteria`, `default_validity`) |
| GET | `/skills/:id` | Get skill |
| PUT | `/skills/:id` | Update skill |
| DELETE | `/skills/:id` | Delete skill |
| GET | `/employee-skills/:employeeId` | List employee's skills |
| POST | `/employee-skills/:employeeId` | Assign skill to employee |
| GET | `/employee-skills/:id` | Get employee skill record |
| PUT | `/employee-skills/:id` | Update employee skill |
| DELETE | `/employee-skills/:id` | Delete employee skill |
| POST | `/employee-skills/:id/assess` | Assess employee skill (update level) |
| GET | `/certificates` | List certificates (paginated) |
| POST | `/certificates` | Create certificate (`name`, `validity_config`, `description`) |
| GET | `/certificates/:id` | Get certificate |
| PUT | `/certificates/:id` | Update certificate |
| DELETE | `/certificates/:id` | Delete certificate |
| GET | `/employee-certificates/:employeeId` | List employee's certificates |
| POST | `/employee-certificates/:employeeId` | Assign certificate to employee |
| DELETE | `/employee-certificates/:employeeId` | Delete employee certificate (query: `certificate_id`) |

### Training — `POST /crate-api/training/v1/*`

Courses (线上/线下实操), chapters with content blocks & quiz config, assignments, learning progress tracking.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/courses` | List courses (filter by `status`, `type`, paginated) |
| POST | `/courses` | Create course (`title`, `type`, `cover_image`, `target_positions`, `completion_rules`, `metadata`) |
| GET | `/courses/:id` | Get course details |
| PUT | `/courses/:id` | Update course |
| DELETE | `/courses/:id` | Delete course |
| GET | `/courses/:courseId/chapters` | List chapters (ordered by `sort_order`) |
| POST | `/courses/:courseId/chapters` | Create chapter (`title`, `sort_order`, `blocks`, `quiz_config`) |
| GET | `/chapters/:id` | Get chapter |
| PUT | `/chapters/:id` | Update chapter |
| DELETE | `/chapters/:id` | Delete chapter |
| POST | `/assignments` | Create assignment (`course_id`, `assign_type`, `target_type`, `target_ids`) |
| GET | `/assignments` | List assignments (filter by `course_id`, `employee_id`, paginated) |
| DELETE | `/assignments/:id` | Delete assignment |
| GET | `/progress/:assignmentId/:employeeId` | Get learning progress |
| PUT | `/progress/:assignmentId/:employeeId/:chapterId` | Update chapter progress (`progress_percent`, `detail`) |
| POST | `/progress/:assignmentId/:employeeId/complete` | Mark all chapters complete |

### Exam — `POST /crate-api/exam/v1/*`

Question bank (单选/多选/判断/填空/看图识错), exam paper generation, exam records with answer submission and scoring.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/questions` | List questions (filter by `type`, `difficulty`, `tag`, `query`, paginated) |
| POST | `/questions` | Create question (`type`, `difficulty`, `tags`, `content`, `options`, `answer`, `explanation`) |
| POST | `/questions/import` | Bulk import questions (array) |
| GET | `/questions/:id` | Get question |
| PUT | `/questions/:id` | Update question |
| DELETE | `/questions/:id` | Delete question |
| GET | `/papers` | List exam papers (paginated) |
| POST | `/papers` | Create paper (`title`, `duration_minutes`, `pass_score`, `generation_strategy`, `anti_cheat_config`, `extra_rules`) |
| GET | `/papers/:id` | Get paper |
| PUT | `/papers/:id` | Update paper |
| DELETE | `/papers/:id` | Delete paper |
| POST | `/papers/:id/generate` | Auto-generate questions from strategy |
| GET | `/records` | List exam records (filter by `employee_id`, `paper_id`, `passed`, paginated) |
| POST | `/records` | Start exam (`employee_id`, `paper_id`) |
| GET | `/records/:id` | Get exam record |
| POST | `/records/:id/submit` | Submit answers |
| GET | `/records/:id/result` | Get exam result with score |

### Onsite — `POST /crate-api/onsite/v1/*`

On-site device QR code management, scan-to-view knowledge, offline cache policies per position.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/devices` | List devices (`search`, paginated) |
| POST | `/devices` | Create device (`device_id`, `code`, `linked_knowledge_ids`, `offline_cache_config`) |
| GET | `/devices/scan` | Scan device by `code` (returns linked knowledge) |
| GET | `/devices/:id` | Get device |
| PUT | `/devices/:id` | Update device |
| DELETE | `/devices/:id` | Delete device |
| GET | `/cache-policies` | List cache policies |
| POST | `/cache-policies` | Create policy (`position_id`, `cache_size_limit_mb`, `include_knowledge_types`, `include_recent_days`) |
| GET | `/cache-policies/:id` | Get cache policy |
| PUT | `/cache-policies/:id` | Update cache policy |
| DELETE | `/cache-policies/:id` | Delete cache policy |

### AI Assistant — `POST /crate-api/ai/v1/*`

AI-powered Q&A with feedback, FAQ management, preventive push rules.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/ask` | Ask question (`user_id`, `question`), returns answer + sources |
| POST | `/qa/:id/feedback` | Submit feedback (`feedback`: 有用/没用) |
| GET | `/faq` | List FAQ (`search`, paginated) |
| POST | `/faq` | Create FAQ (`question`, `answer`, `tags`, `enabled`, `created_by`) |
| GET | `/faq/:id` | Get FAQ |
| PUT | `/faq/:id` | Update FAQ |
| DELETE | `/faq/:id` | Delete FAQ |
| GET | `/push-rules` | List push rules (filter by `enabled`, paginated) |
| POST | `/push-rules` | Create push rule (`name`, `trigger_metric`, `threshold`, `target_positions`, `target_course_id`, `extra`) |
| GET | `/push-rules/:id` | Get push rule |
| PUT | `/push-rules/:id` | Update push rule |
| DELETE | `/push-rules/:id` | Delete push rule |

### Analytics — `POST /crate-api/analytics/v1/*`

Dashboard aggregation endpoints.

| Method | Endpoint | Description |
|---|---|---|
| GET | `/training/summary` | Training dashboard summary |
| GET | `/skill-heatmap` | Skill distribution heatmap (`department_id`) |
| GET | `/quality-correlation` | Quality vs training correlation (`department_id`) |

## Database

- **Tables**: users, settings, messages, files, behaviors, rbac_roles, rbac_permissions, rbac_assignments, rebac_relations, abac_policies, user_attributes, knowledge_categories, knowledge_entries, knowledge_versions, knowledge_feedbacks, positions, skills, employee_skills, certificates, employee_certificates, courses, course_chapters, training_assignments, learning_progress, questions, exam_papers, exam_records, device_qr_codes, offline_cache_policies, ai_qa_logs, faq_pairs, preventive_push_rules
- **Migration**: Flyway (versioned SQL in `libs/database/src/main/resources/db/migration/`)
- **Codegen**: jOOQ code generation via `libs/database/jooq-config.xml`

## Common Conventions

- **API prefix**: `/crate-api/<module>/v1/<resource>`
- **Pagination**: `{ records: [...], meta: { total: N } }`
- **Error response**: `{ "error": "<message>" }`
- **IDs**: ULID (26-char Crockford Base32)
- **Timestamps**: `created_at` / `updated_at` (OffsetDateTime with timezone)
- **JSONB columns**: Named `metadata` when storing extensible attributes
- **Chinese enums**: 线上/线下实操, 手动指派/自动触发, 单选/多选/判断/填空/看图识错, 启用/禁用, 有用/没用
