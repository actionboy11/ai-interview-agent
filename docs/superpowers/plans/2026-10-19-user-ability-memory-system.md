# User Ability Memory System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add invite-only user accounts, strict per-user resource isolation, confirmed ability memories from text interviews, and deterministic memory-aware question planning.

**Architecture:** Build four dependent layers in order: Redis-backed session authentication, database-level resource ownership, confirmed ability profiles derived from text-interview evidence, and a deterministic QuestionPlan that constrains existing LLM question generators. Every external call remains outside database transactions, unconfirmed proposals never affect planning, and memory failures fall back to the current fixed allocation.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security, Spring Session Data Redis, Flyway, PostgreSQL, RabbitMQ, Spring AI 2.0, React 18, TypeScript, Axios, JUnit 5, Mockito, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-19-user-ability-memory-system-design.md`

## Global Constraints

- Pause Agent evaluation development; do not extend `modules/agentevaluation` in this plan.
- Preserve existing uncommitted workspace changes; execution must begin in an isolated worktree.
- Use `Controller -> Service -> Repository`; return `Result<T>` and never return entities.
- Use `BusinessException(ErrorCode.XXX, message)` for business failures.
- Keep LLM, S3, and external HTTP calls outside transactions.
- Use constructor injection, 2-space Java indentation, no wildcard imports, and Chinese `@DisplayName` text.
- Use Flyway for production schema changes and end with production `ddl-auto=validate`.
- Do not trust request `userId`, `X-User-Id`, URL IDs, or cookies parsed in business code; use `CurrentUserProvider`.
- Only `ACTIVE` confirmed profiles affect QuestionPlan; `PROPOSED` data never does.
- Do not log passwords, invite codes, session cookies, resume text, full answers, profile summaries, or memory-bearing prompts.
- Backend public API errors, including authentication failures, remain HTTP 200 with `Result.error` business codes.
- After backend common changes run `./gradlew :app:test --no-daemon`; after frontend changes run `pnpm run build`.

## Review Focus

- Two users submit the same file hash: both uploads succeed as separate owner-scoped records, while duplicates for one owner are rejected.
- An authenticated user changes a resume/session/knowledge-base ID to another user's ID: every read, mutation, download, SSE, and WebSocket path behaves as not found.
- Two clients redeem one invite concurrently: exactly one user is created and the second request receives `INVITE_INVALID`.
- RabbitMQ redelivers evaluation or memory-derivation messages: evidence and proposals are created once and no second model call is caused by memory derivation.
- A profile changes or memory lookup fails after a session starts: the persisted plan remains stable, and a new session either uses the new profile or falls back to fixed allocation.

---

### Task 1: Introduce a reproducible Flyway baseline

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/resources/db/migration/V1__baseline_existing_schema.sql`
- Modify: `app/src/main/resources/application.yml`
- Create: `app/src/main/resources/application-prod.yml`
- Modify: `docker-compose.prod.yml`
- Modify: `app/src/test/resources/application-test.yml`
- Test: `app/src/test/java/interview/guide/migration/FlywayBaselineIntegrationTest.java`

**Interfaces:**
- Produces: a clean PostgreSQL database can be created from `V1`; a non-empty legacy database can be baselined at version `1` before later migrations.
- Produces: test profile keeps unrelated H2 unit tests isolated from PostgreSQL-only migration syntax; production uses `ddl-auto=validate` and disables Spring AI schema initialization.

- [ ] **Step 1: Write the failing PostgreSQL migration test**

Use Testcontainers with `pgvector/pgvector:pg16`. Assert a clean migration creates every existing business table and required pgvector extension/table, and a legacy fixture with version-1 schema can baseline without dropping data.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./gradlew :app:test --tests "*FlywayBaselineIntegrationTest" --no-daemon`

Expected: FAIL because Flyway dependencies and `V1` do not exist.

- [ ] **Step 3: Add Flyway and the version-1 schema**

Add `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, and `org.testcontainers:postgresql`. Create a complete idempotence-free clean-install schema matching current entities and indexes. Configure production migrations with legacy baseline version `1`; disable Flyway in the H2 test profile except migration tests. Add a `prod` profile with `ddl-auto=validate` and pgvector `initialize-schema=false`, and activate it in `docker-compose.prod.yml`.

- [ ] **Step 4: Run migration and compile verification**

Run: `./gradlew :app:test --tests "*FlywayBaselineIntegrationTest" --no-daemon`

Run: `./gradlew :app:compileJava`

Expected: PASS; a fresh PostgreSQL schema and a baselined legacy schema both start.

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle app/src/main/resources/db/migration app/src/main/resources/application.yml app/src/main/resources/application-prod.yml app/src/test/resources/application-test.yml app/src/test/java/interview/guide/migration docker-compose.prod.yml
git commit -m "build: establish Flyway schema baseline"
```

### Task 2: Add user and invite persistence

**Files:**
- Create: `app/src/main/resources/db/migration/V2__create_identity_tables.sql`
- Create: `app/src/main/java/interview/guide/modules/auth/model/UserEntity.java`
- Create: `app/src/main/java/interview/guide/modules/auth/model/UserRole.java`
- Create: `app/src/main/java/interview/guide/modules/auth/model/UserStatus.java`
- Create: `app/src/main/java/interview/guide/modules/auth/model/RegistrationInviteEntity.java`
- Create: `app/src/main/java/interview/guide/modules/auth/model/RegistrationInviteStatus.java`
- Create: `app/src/main/java/interview/guide/modules/auth/repository/UserRepository.java`
- Create: `app/src/main/java/interview/guide/modules/auth/repository/RegistrationInviteRepository.java`
- Test: `app/src/test/java/interview/guide/modules/auth/repository/AuthRepositoryTest.java`

**Interfaces:**
- Produces: `Optional<UserEntity> UserRepository.findByNormalizedUsername(String normalizedUsername)`.
- Produces: a conditional repository update that changes one `ACTIVE` invite to `USED` for a supplied user and time, returning the affected row count.
- Produces: UUID user IDs, `ADMIN|USER` roles, `ACTIVE|DISABLED` user status, and `ACTIVE|USED|REVOKED|EXPIRED` invite status.

- [ ] **Step 1: Write failing repository tests**

Assert normalized usernames are unique, invite hashes are unique, invite consumption changes exactly one ACTIVE row, expired/revoked/used invites cannot be consumed, and concurrent consumption yields one success.

- [ ] **Step 2: Run and observe missing-schema failure**

Run: `./gradlew :app:test --tests "*AuthRepositoryTest" --no-daemon`

Expected: FAIL because identity tables and repositories are absent.

- [ ] **Step 3: Implement the migration, entities, and repositories**

Use `@Version` on mutable entities. Keep username display and normalized username separate. Store only password and invite hashes. Add indexes for normalized username, user status, invite hash, status, and expiry.

- [ ] **Step 4: Run repository tests**

Run: `./gradlew :app:test --tests "*AuthRepositoryTest" --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/resources/db/migration/V2__create_identity_tables.sql app/src/main/java/interview/guide/modules/auth app/src/test/java/interview/guide/modules/auth/repository
git commit -m "feat: add user and invite persistence"
```

### Task 3: Implement account and invite services

**Files:**
- Create: `app/src/main/java/interview/guide/modules/auth/config/AuthBootstrapProperties.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/UsernameNormalizer.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/InviteCodeService.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/AuthService.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/AdminAccountService.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/CreateAdminUserCommand.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/IssuedInvite.java`
- Create: `app/src/main/java/interview/guide/modules/auth/service/InitialAdminBootstrap.java`
- Modify: `app/src/main/java/interview/guide/common/exception/ErrorCode.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `app/src/test/java/interview/guide/modules/auth/service/InviteCodeServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/auth/service/AuthServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/auth/service/InitialAdminBootstrapTest.java`

**Interfaces:**
- Produces: `IssuedInvite InviteCodeService.issue(Duration ttl, UUID adminUserId)` and `UserEntity InviteCodeService.register(String rawCode, String username, String password, String displayName)`.
- Produces: `UserEntity AuthService.authenticate(String username, String rawPassword)` and `void changePassword(UUID userId, String currentPassword, String newPassword)`.
- Produces: `UserEntity AdminAccountService.createUser(CreateAdminUserCommand command)`, `void disable(UUID userId)`, and `void enable(UUID userId)`.

- [ ] **Step 1: Write failing service tests**

Assert BCrypt storage, case-normalized usernames, a 256-bit random invite whose database representation is SHA-256 only, concurrent single-use behavior, expiry/revocation rejection, disabled-user rejection, initial-admin idempotency, and no checked-in default admin password.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:test --tests "*AuthServiceTest" --tests "*InviteCodeServiceTest" --tests "*InitialAdminBootstrapTest" --no-daemon`

Expected: FAIL because services and auth-specific error codes are absent.

- [ ] **Step 3: Implement the services**

Use `BusinessException` with distinct auth/invite codes. Return invite plaintext only from `issue`. Consume the invite and create the user in one Service transaction. Read initial admin username/password/display name only from `AuthBootstrapProperties` environment-backed fields.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add .env.example app/src/main/resources/application.yml app/src/main/java/interview/guide/common/exception/ErrorCode.java app/src/main/java/interview/guide/modules/auth app/src/test/java/interview/guide/modules/auth/service
git commit -m "feat: add invite-only account services"
```

### Task 4: Add Spring Security, Redis sessions, and auth APIs

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/interview/guide/modules/auth/security/CurrentUser.java`
- Create: `app/src/main/java/interview/guide/modules/auth/security/CurrentUserProvider.java`
- Create: `app/src/main/java/interview/guide/modules/auth/security/SecurityConfiguration.java`
- Create: `app/src/main/java/interview/guide/modules/auth/security/ResultAuthenticationEntryPoint.java`
- Create: `app/src/main/java/interview/guide/modules/auth/security/ResultAccessDeniedHandler.java`
- Create: `app/src/main/java/interview/guide/modules/auth/controller/AuthController.java`
- Create: `app/src/main/java/interview/guide/modules/auth/controller/AdminUserController.java`
- Create: `app/src/main/java/interview/guide/modules/auth/controller/AdminInviteController.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/LoginRequest.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/InviteRegistrationRequest.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/ChangePasswordRequest.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/CurrentUserResponse.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/AdminCreateUserRequest.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/AdminUserResponse.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/CreateInviteRequest.java`
- Create: `app/src/main/java/interview/guide/modules/auth/dto/IssuedInviteResponse.java`
- Modify: `app/src/main/resources/application.yml`
- Test: `app/src/test/java/interview/guide/modules/auth/controller/AuthControllerTest.java`
- Test: `app/src/test/java/interview/guide/modules/auth/security/SecurityBoundaryIntegrationTest.java`

**Interfaces:**
- Produces: `UUID CurrentUserProvider.requireUserId()`, `CurrentUser requireCurrentUser()`, and `boolean isAdmin()`.
- Produces: `/api/auth/login`, `/register-by-invite`, `/logout`, `/me`, `/change-password`, `/csrf` and `/api/admin/users|invites` endpoints, all wrapped in `Result<T>`.
- Consumes: Task 3 account and invite services.

- [ ] **Step 1: Write failing MVC and security tests**

Assert anonymous access is limited to auth bootstrap endpoints and health, protected APIs return HTTP 200 plus `UNAUTHORIZED`, USER cannot call admin APIs, ADMIN cannot gain a business-content bypass, CSRF is required for mutations, login creates a Redis-backed session, logout invalidates it, and disabling a user invalidates future authenticated access.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:test --tests "*AuthControllerTest" --tests "*SecurityBoundaryIntegrationTest" --no-daemon`

Expected: FAIL because Security configuration and endpoints are absent.

- [ ] **Step 3: Implement security and controllers**

Add Security, Security Test, and Spring Session Redis dependencies. Use explicit allowed origins, credentialed CORS, `CookieCsrfTokenRepository`, custom HTTP-200 security handlers, and method/request authorization for ADMIN endpoints. Never accept an owner user ID in public request DTOs.

- [ ] **Step 4: Run focused tests and full backend tests**

Run: `./gradlew :app:test --tests "*AuthControllerTest" --tests "*SecurityBoundaryIntegrationTest" --no-daemon`

Run: `./gradlew :app:test --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle app/src/main/resources/application.yml app/src/main/java/interview/guide/modules/auth app/src/test/java/interview/guide/modules/auth
git commit -m "feat: secure APIs with Redis sessions"
```

### Task 5: Build the authentication and administration frontend

**Files:**
- Create: `frontend/src/types/auth.ts`
- Create: `frontend/src/api/auth.ts`
- Modify: `frontend/src/api/request.ts`
- Create: `frontend/src/hooks/useAuth.tsx`
- Create: `frontend/src/components/ProtectedRoute.tsx`
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/InviteRegisterPage.tsx`
- Create: `frontend/src/pages/AdminUsersPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/Layout.tsx`
- Modify: `frontend/src/constants/routes.ts`
- Test: `frontend/src/api/request.test.ts`
- Test: `frontend/src/api/auth.test.ts`

**Interfaces:**
- Produces: `AuthProvider` with `currentUser`, `loading`, `login`, `logout`, and `refreshCurrentUser`.
- Consumes: Task 4 auth endpoints and CSRF cookie/header contract.

- [ ] **Step 1: Write failing API tests**

Assert Axios uses `withCredentials`, mutation requests send the CSRF header, an auth business code clears current-user state and navigates to login, and normal `Result<T>` unwrapping remains unchanged.

- [ ] **Step 2: Run frontend tests and verify failure**

Run from `frontend`: `pnpm test`

Expected: FAIL because auth API and credential handling are absent.

- [ ] **Step 3: Implement auth state, pages, route guards, and admin UI**

Keep ADMIN navigation hidden from USER. Show invite plaintext once after creation. Do not persist passwords, invite codes, or session identifiers in localStorage.

- [ ] **Step 4: Run frontend tests and production build**

Run from `frontend`: `pnpm test`

Run from `frontend`: `pnpm run build`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add invite-only authentication UI"
```

### Task 6: Add ownership columns and migrate legacy data

**Files:**
- Create: `app/src/main/resources/db/migration/V3__add_resource_ownership.sql`
- Modify: `app/src/main/java/interview/guide/modules/resume/model/ResumeEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/model/VoiceInterviewSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/model/RagChatSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interviewschedule/model/InterviewScheduleEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/repository/ResumeRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/repository/InterviewSessionRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewSessionRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/repository/KnowledgeBaseRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/repository/RagChatSessionRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/interviewschedule/repository/InterviewScheduleRepository.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `.env.example`
- Create: `docs/user-ability-memory-upgrade-runbook.md`
- Test: `app/src/test/java/interview/guide/migration/ResourceOwnershipMigrationTest.java`
- Test: `app/src/test/java/interview/guide/modules/ownership/OwnerScopedRepositoryTest.java`

**Interfaces:**
- Produces: non-null `owner_user_id` on every root business table.
- Produces: `APP_LEGACY_OWNER_USERNAME` migration requirement and user-scoped repository methods for every public resource identifier.
- Consumes: a legacy-owner account created by a deployed Task 3/4 build before this migration is deployed to a non-empty legacy database.

- [ ] **Step 1: Write failing migration and repository tests**

Assert a clean database with no business rows migrates without a pre-existing legacy account; a non-empty legacy database assigns rows to the configured existing account; a non-empty database with a missing legacy owner aborts migration; foreign keys are non-null after backfill; same-owner duplicate hashes fail; cross-owner equal hashes succeed; and all repositories can query by owner plus ID/session ID.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:test --tests "*ResourceOwnershipMigrationTest" --tests "*OwnerScopedRepositoryTest" --no-daemon`

Expected: FAIL because ownership columns and methods are absent.

- [ ] **Step 3: Implement migration, entity relations, and repository contracts**

Use `UserEntity owner` with lazy `ManyToOne`; expose scalar owner ID only in internal code where needed. Replace global file-hash uniqueness with `(owner_user_id, file_hash)`. Add owner-first indexes for list and lookup queries. Configure the Flyway `legacy-owner-username` placeholder from `APP_LEGACY_OWNER_USERNAME`; if legacy rows exist, resolve exactly one existing user before backfill and fail closed otherwise.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Document and verify the two-stage upgrade gate**

Create `docs/user-ability-memory-upgrade-runbook.md`: deploy the Task 1-4 build first, let `InitialAdminBootstrap` create the account named by `APP_LEGACY_OWNER_USERNAME`, back up the database, then deploy the ownership migration. A fresh database skips the legacy-row lookup because there is nothing to backfill.

- [ ] **Step 6: Commit**

```powershell
git add .env.example docs/user-ability-memory-upgrade-runbook.md app/src/main/resources/application.yml app/src/main/resources/db/migration/V3__add_resource_ownership.sql app/src/main/java/interview/guide/modules app/src/test/java/interview/guide/migration app/src/test/java/interview/guide/modules/ownership
git commit -m "feat: assign business data to users"
```

### Task 7: Enforce ownership in resume and text-interview workflows

**Files:**
- Modify: resume controllers/services/repositories under `app/src/main/java/interview/guide/modules/resume/`
- Modify: `app/src/main/java/interview/guide/modules/interview/InterviewController.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewHistoryService.java`
- Modify: `app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java`
- Modify: `app/src/main/java/interview/guide/infrastructure/file/FileStorageService.java`
- Test: `app/src/test/java/interview/guide/modules/ownership/ResumeOwnershipIntegrationTest.java`
- Test: `app/src/test/java/interview/guide/modules/ownership/TextInterviewOwnershipIntegrationTest.java`

**Interfaces:**
- Consumes: `CurrentUserProvider.requireUserId()` and owner-scoped repositories.
- Produces: all resume/text-interview create, list, read, update, delete, export, resume, and history operations are owner-scoped.

- [ ] **Step 1: Write two-user integration tests**

For every public resume and text-interview operation, create users A/B and assert A cannot act on B's IDs. Assert async workers can still load by internal business ID, but validate that linked entities share one owner. Assert cache and S3 keys contain owner ID.

- [ ] **Step 2: Run tests and verify cross-user failures**

Run: `./gradlew :app:test --tests "*ResumeOwnershipIntegrationTest" --tests "*TextInterviewOwnershipIntegrationTest" --no-daemon`

Expected: FAIL on current unscoped methods.

- [ ] **Step 3: Thread owner context through services**

Controllers delegate only. Public Service methods derive owner from `CurrentUserProvider`; internal RabbitMQ methods accept business IDs and verify persisted ownership consistency. Never add a public request `userId`.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume app/src/main/java/interview/guide/modules/interview app/src/main/java/interview/guide/infrastructure app/src/test/java/interview/guide/modules/ownership
git commit -m "feat: isolate resumes and text interviews"
```

### Task 8: Enforce ownership in knowledge, schedule, voice, SSE, and WebSocket flows

**Files:**
- Modify: knowledge-base controllers/services/repositories under `app/src/main/java/interview/guide/modules/knowledgebase/`
- Modify: schedule controllers/services/repositories under `app/src/main/java/interview/guide/modules/interviewschedule/`
- Modify: voice controllers/services/repositories under `app/src/main/java/interview/guide/modules/voiceinterview/`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`
- Modify: Provider/controller authorization under `app/src/main/java/interview/guide/modules/llmprovider/`
- Test: `app/src/test/java/interview/guide/modules/ownership/KnowledgeAndScheduleOwnershipIntegrationTest.java`
- Test: `app/src/test/java/interview/guide/modules/ownership/VoiceWebSocketOwnershipIntegrationTest.java`

**Interfaces:**
- Produces: owner-scoped knowledge/RAG/schedule/voice APIs, pgvector filters, SSE sessions, and authenticated WebSocket handshakes.
- Produces: global Provider/voice settings restricted to ADMIN.

- [ ] **Step 1: Write two-user API, SSE, vector-filter, and WebSocket tests**

Assert A cannot attach B's knowledge base to a RAG session, query B's vectors, open B's SSE session, edit B's schedule, pass `userId=B`, or connect to B's voice Session. Assert USER cannot change global Provider settings and ADMIN still cannot use normal content endpoints to read B's data.

- [ ] **Step 2: Run tests and verify failures**

Run: `./gradlew :app:test --tests "*KnowledgeAndScheduleOwnershipIntegrationTest" --tests "*VoiceWebSocketOwnershipIntegrationTest" --no-daemon`

Expected: FAIL on current unscoped paths.

- [ ] **Step 3: Implement owner enforcement**

Remove public voice `userId` selection. Resolve WebSocket HTTP Session authentication during handshake and compare it with persisted owner before accepting. Put `ownerUserId` into vector metadata and enforce it in the database-side filter expression.

- [ ] **Step 4: Run focused and full backend tests**

Run: the Step 2 command.

Run: `./gradlew :app:test --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/knowledgebase app/src/main/java/interview/guide/modules/interviewschedule app/src/main/java/interview/guide/modules/voiceinterview app/src/main/java/interview/guide/modules/llmprovider app/src/test/java/interview/guide/modules/ownership
git commit -m "feat: enforce ownership across remaining modules"
```

### Task 9: Preserve stable category keys through evaluation

**Files:**
- Modify: `app/src/main/java/interview/guide/common/evaluation/QaRecord.java`
- Modify: `app/src/main/java/interview/guide/common/evaluation/EvaluationReport.java`
- Modify: `app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewAnswerEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewReportDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/AnswerEvaluationService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`
- Create: `app/src/main/resources/db/migration/V4__split_interview_category_key_and_label.sql`
- Test: `app/src/test/java/interview/guide/modules/interview/service/StableCategoryEvaluationTest.java`

**Interfaces:**
- Produces: every evaluated answer has immutable `categoryKey` and display `categoryLabel`; aggregation and memory use the key.
- Consumes: `InterviewQuestionDTO.type()` as key and `.category()` as label.

- [ ] **Step 1: Write failing propagation tests**

Assert `MYSQL` survives question DTO → QaRecord → evaluation report → answer persistence even if the label changes from `MySQL` to another localized label. Assert legacy rows receive a safe normalized key or `OTHER`.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew :app:test --tests "*StableCategoryEvaluationTest" --no-daemon`

Expected: FAIL because evaluation currently collapses key and label.

- [ ] **Step 3: Implement the schema and DTO propagation**

Keep the LLM-facing display label but require structured results to echo the supplied stable key. Validate returned keys against the Session question map and correct labels from local Skill metadata.

- [ ] **Step 4: Run focused and evaluation regression tests**

Run: `./gradlew :app:test --tests "*StableCategoryEvaluationTest" --tests "*UnifiedEvaluationServiceTest" --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/resources/db/migration/V4__split_interview_category_key_and_label.sql app/src/main/java/interview/guide/common/evaluation app/src/main/java/interview/guide/modules/interview app/src/test/java/interview/guide/modules/interview/service
git commit -m "refactor: preserve interview category keys"
```

### Task 10: Add ability profile, evidence, and proposal persistence

**Files:**
- Create: `app/src/main/resources/db/migration/V5__create_ability_memory_tables.sql`
- Create: `app/src/main/java/interview/guide/modules/memory/model/UserAbilityProfileEntity.java`
- Create: `app/src/main/java/interview/guide/modules/memory/model/UserAbilityEvidenceEntity.java`
- Create: `app/src/main/java/interview/guide/modules/memory/model/AbilityProfileProposalEntity.java`
- Create: `app/src/main/java/interview/guide/modules/memory/model/AbilityLevel.java`
- Create: `app/src/main/java/interview/guide/modules/memory/model/AbilityProfileStatus.java`
- Create: `app/src/main/java/interview/guide/modules/memory/model/AbilityProposalStatus.java`
- Create: `app/src/main/java/interview/guide/modules/memory/repository/UserAbilityProfileRepository.java`
- Create: `app/src/main/java/interview/guide/modules/memory/repository/UserAbilityEvidenceRepository.java`
- Create: `app/src/main/java/interview/guide/modules/memory/repository/AbilityProfileProposalRepository.java`
- Create: `app/src/main/java/interview/guide/modules/memory/service/AbilityScorePolicy.java`
- Test: `app/src/test/java/interview/guide/modules/memory/repository/AbilityMemoryRepositoryTest.java`
- Test: `app/src/test/java/interview/guide/modules/memory/service/AbilityScorePolicyTest.java`

**Interfaces:**
- Produces: unique profile `(userId, skillId, categoryKey)`, unique evidence `interviewAnswerId`, and unique proposal `(interviewSessionId, categoryKey)`.
- Produces: `AbilityLevel AbilityScorePolicy.levelFor(int score)` and `int mergeScore(int oldScore, int sessionScore)` using `70/30` weighting with deterministic rounding.

- [ ] **Step 1: Write failing persistence and table-driven scoring tests**

Cover exact score boundaries 0/39/40/59/60/74/75/89/90/100, out-of-range rejection, 70/30 rounding, evidence-to-proposal linkage, optimistic locking, and cross-user uniqueness.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:test --tests "*AbilityMemoryRepositoryTest" --tests "*AbilityScorePolicyTest" --no-daemon`

Expected: FAIL because memory schema and policy are absent.

- [ ] **Step 3: Implement schema, entities, repositories, and pure policy**

Define profile states `ACTIVE|DISABLED|NEEDS_REVIEW`, proposal states from the spec, and indexes for owner/skill/status, source Session, and pending proposals.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/resources/db/migration/V5__create_ability_memory_tables.sql app/src/main/java/interview/guide/modules/memory app/src/test/java/interview/guide/modules/memory
git commit -m "feat: add ability memory persistence"
```

### Task 11: Derive idempotent ability proposals after text evaluation

**Files:**
- Create: `app/src/main/resources/db/migration/V6__add_memory_derivation_status.sql`
- Create: `app/src/main/java/interview/guide/modules/memory/service/AbilityEvidenceDerivationService.java`
- Create: `app/src/main/java/interview/guide/modules/memory/service/DerivationResult.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/AbilityMemoryDerivationTaskPublisher.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryDerivationMessage.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryRabbitProperties.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryRabbitConfig.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryRabbitProducer.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryRabbitConsumer.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryRetryPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/memory/messaging/rabbit/AbilityMemoryDeadLetterConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConsumer.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `app/src/test/java/interview/guide/modules/memory/service/AbilityEvidenceDerivationServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/memory/messaging/AbilityMemoryRabbitIntegrationTest.java`

**Interfaces:**
- Produces: `DerivationResult AbilityEvidenceDerivationService.derive(String sessionId, UUID messageId)`.
- Produces: `AbilityMemoryDerivationTaskPublisher.publish(String sessionId)` after evaluation-report commit.
- Consumes: persisted evaluated answers with stable keys; performs no LLM call.

- [ ] **Step 1: Write failing derivation and redelivery tests**

Assert only evaluated text interviews derive data, unanswered/invalid categories are skipped, one proposal is created per Session/category, evidence is linked to it, redelivery is idempotent, disabled/missing owners are discarded, and derivation failure does not change evaluation completion.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:test --tests "*AbilityEvidenceDerivationServiceTest" --tests "*AbilityMemoryRabbitIntegrationTest" --no-daemon`

Expected: FAIL because derivation workflow is absent.

- [ ] **Step 3: Implement the derivation queue and after-commit publication**

Use a dedicated exchange/queue/retry/dead-letter topology following existing RabbitMQ templates. Persist `memoryDerivationStatus`, error, and message ID in short Service transactions. Do not call `derive` inside evaluation report persistence.

- [ ] **Step 4: Run focused messaging tests**

Expected command: the Step 2 command.

Expected: PASS and zero ChatClient interactions in memory derivation tests.

- [ ] **Step 5: Commit**

```powershell
git add .env.example app/src/main/resources/application.yml app/src/main/resources/db/migration/V6__add_memory_derivation_status.sql app/src/main/java/interview/guide/modules/memory app/src/main/java/interview/guide/modules/interview app/src/test/java/interview/guide/modules/memory
git commit -m "feat: derive ability proposals from evaluations"
```

### Task 12: Add proposal decisions and ability-profile APIs

**Files:**
- Create: `app/src/main/java/interview/guide/modules/memory/service/AbilityProfileCommandService.java`
- Create: `app/src/main/java/interview/guide/modules/memory/service/AbilityProfileQueryService.java`
- Create: `app/src/main/java/interview/guide/modules/memory/service/AbilityProfileSnapshot.java`
- Create: `app/src/main/java/interview/guide/modules/memory/controller/AbilityMemoryController.java`
- Create: `app/src/main/java/interview/guide/modules/memory/dto/AbilityProfileResponse.java`
- Create: `app/src/main/java/interview/guide/modules/memory/dto/AbilityEvidenceResponse.java`
- Create: `app/src/main/java/interview/guide/modules/memory/dto/AbilityProposalResponse.java`
- Create: `app/src/main/java/interview/guide/modules/memory/dto/AcceptProposalRequest.java`
- Create: `app/src/main/java/interview/guide/modules/memory/dto/AcceptProposalWithEditRequest.java`
- Modify: text-interview deletion flow in `InterviewSessionService` and persistence Service
- Modify: `app/src/main/java/interview/guide/common/exception/ErrorCode.java`
- Test: `app/src/test/java/interview/guide/modules/memory/service/AbilityProfileCommandServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/memory/controller/AbilityMemoryControllerTest.java`

**Interfaces:**
- Produces: owner-scoped list/detail endpoints plus `accept`, `acceptWithEdit`, `reject`, `disable`, and `enable` commands.
- Produces: `AbilityProfileSnapshot accept(UUID userId, UUID proposalId, long version)` and edited equivalent.

- [ ] **Step 1: Write failing state-transition and ownership tests**

Assert only PROPOSED can be resolved, accepted evidence updates 70/30 profile score, edited level/summary is retained without changing evidence, rejected evidence does not affect profile, stale versions fail, user A cannot resolve B's proposal, and deleting a source Session recalculates or marks manually edited profiles `NEEDS_REVIEW`.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:test --tests "*AbilityProfileCommandServiceTest" --tests "*AbilityMemoryControllerTest" --no-daemon`

Expected: FAIL because command/query services and APIs are absent.

- [ ] **Step 3: Implement transactional decisions and deletion recalculation**

Controllers validate and delegate only. Commands load by proposal/profile ID plus current user. Keep evidence immutable; store both proposed and final user-approved values.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/memory app/src/main/java/interview/guide/modules/interview app/src/main/java/interview/guide/common/exception/ErrorCode.java app/src/test/java/interview/guide/modules/memory
git commit -m "feat: add confirmed ability profile workflow"
```

### Task 13: Add the ability-profile frontend

**Files:**
- Create: `frontend/src/types/memory.ts`
- Create: `frontend/src/api/memory.ts`
- Create: `frontend/src/pages/AbilityProfilePage.tsx`
- Create: `frontend/src/components/AbilityProposalCard.tsx`
- Modify: `frontend/src/components/InterviewDetailPanel.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/Layout.tsx`
- Modify: `frontend/src/constants/routes.ts`
- Test: `frontend/src/utils/abilityLevel.test.ts`
- Create: `frontend/src/utils/abilityLevel.ts`

**Interfaces:**
- Consumes: Task 12 owner-scoped memory APIs.
- Produces: profile list/evidence view and report-page proposal accept/edit/reject interactions.

- [ ] **Step 1: Write failing level-display tests**

Assert every backend ability level and proposal state maps to a Chinese label, color, and actionable UI state; unknown enum values render a neutral fallback rather than crashing.

- [ ] **Step 2: Run tests and verify failure**

Run from `frontend`: `pnpm test`

Expected: FAIL because ability types and mapping are absent.

- [ ] **Step 3: Implement the profile page and report proposal cards**

Show current versus proposed level/score, evidence count, source Session link, confirmation warning for low evidence, and disabled/needs-review state. Never allow editing raw evidence scores.

- [ ] **Step 4: Run frontend tests and build**

Run from `frontend`: `pnpm test`

Run from `frontend`: `pnpm run build`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add ability profile management UI"
```

### Task 14: Implement the deterministic QuestionPlan policy

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/QuestionPlan.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/QuestionSlot.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/QuestionPurpose.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/QuestionSourceMode.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/PlanningMode.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/QuestionPlanningCommand.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/AdaptiveAllocationPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/QuestionPlanningService.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/QuestionPlanValidator.java`
- Test: `app/src/test/java/interview/guide/modules/interview/planning/AdaptiveAllocationPolicyTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/planning/QuestionPlanningServiceTest.java`

**Interfaces:**
- Produces: `QuestionPlan QuestionPlanningService.plan(QuestionPlanningCommand command)`.
- Produces: slots with `MANDATORY|BASELINE|WEAKNESS_REINFORCEMENT|STRENGTH_CALIBRATION|EXPLORATION`, difficulty, source mode, topics, avoided topics, and related profile ID.
- Consumes: current Skill categories, ACTIVE profiles, recent coverage, historical topics, resume presence, total count, and base difficulty.

- [ ] **Step 1: Write table-driven failing policy tests**

Cover 5- and 10-question budgets, exact total reconciliation, ALWAYS_ONE first, 30% category cap, weak-profile ranking, strong calibration cap, unknown exploration, no-memory parity with current allocation, unrelated-Skill exclusion, low-evidence 60/40 source ratio, qualified-profile 40/60 ratio, deterministic tie-breaking, and memory-read failure fallback.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :app:test --tests "*AdaptiveAllocationPolicyTest" --tests "*QuestionPlanningServiceTest" --no-daemon`

Expected: FAIL because planning types and policy are absent.

- [ ] **Step 3: Implement the pure policy and validator**

Use stable ordering: level ascending, confidence descending, last evaluated ascending, category key ascending. Allocate integer budgets with largest-remainder rounding and apply the spec's shortage priority. The fallback delegates to the existing Skill allocation method.

- [ ] **Step 4: Run focused tests**

Expected command: the Step 2 command.

Expected: PASS with no LLM or database needed for policy tests.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/interview/planning app/src/test/java/interview/guide/modules/interview/planning
git commit -m "feat: plan memory-aware interview questions"
```

### Task 15: Persist QuestionPlan and constrain both question generators

**Files:**
- Create: `app/src/main/resources/db/migration/V7__create_interview_question_plans.sql`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/model/InterviewQuestionPlanEntity.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/repository/InterviewQuestionPlanRepository.java`
- Create: `app/src/main/java/interview/guide/modules/interview/planning/service/InterviewQuestionPlanPersistenceService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewQuestionDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- Modify: `app/src/main/resources/prompts/interview-question-skill-user.st`
- Modify: `app/src/main/resources/prompts/interview-question-resume-user.st`
- Test: `app/src/test/java/interview/guide/modules/interview/planning/AdaptiveQuestionGenerationTest.java`

**Interfaces:**
- Consumes: Task 14 `QuestionPlan` and Task 10 ACTIVE profiles.
- Produces: persisted plan snapshot before LLM calls; each generated main question echoes one `slotId`.
- Produces: separate `resumeSlots` and `directionSlots` while retaining current parallel generation.

- [ ] **Step 1: Write failing orchestration tests with a fake ChatClient**

Assert the plan is persisted before model invocation, no transaction is active during model calls, prompts contain only bounded/sanitized slot data, output validates exact slot/count/category membership, absent resume cannot receive resume slots, invalid adaptive output triggers fixed-plan regeneration, and the original fixed path works when memory is unavailable.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:test --tests "*AdaptiveQuestionGenerationTest" --no-daemon`

Expected: FAIL because plans are not persisted or consumed.

- [ ] **Step 3: Integrate planning and prompt constraints**

Persist plan JSON, version, mode, owner, and fallback reason in a short Service transaction. Add `slotId` to structured output and Session question JSON. Validate against local Skill metadata; never treat profile summary text as instructions.

- [ ] **Step 4: Run focused and interview regression tests**

Run: `./gradlew :app:test --tests "*AdaptiveQuestionGenerationTest" --tests "*InterviewQuestion*" --tests "*InterviewSession*" --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/resources/db/migration/V7__create_interview_question_plans.sql app/src/main/resources/prompts app/src/main/java/interview/guide/modules/interview app/src/test/java/interview/guide/modules/interview/planning
git commit -m "feat: generate questions from persisted plans"
```

### Task 16: Expose plan explanations and complete end-to-end verification

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/planning/dto/QuestionPlanSummaryResponse.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/InterviewController.java`
- Modify: `frontend/src/types/interview.ts`
- Modify: `frontend/src/api/interview.ts`
- Modify: `frontend/src/pages/InterviewPage.tsx`
- Modify: `frontend/src/components/InterviewDetailPanel.tsx`
- Create: `app/src/test/java/interview/guide/integration/UserAbilityMemoryJourneyTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces: a sanitized plan summary containing purpose counts and per-question human-readable reasons, never internal prompts or full memory text.
- Consumes: the complete auth → evaluation → proposal → confirmation → planning workflow.

- [ ] **Step 1: Write the failing end-to-end journey test**

Create ADMIN, invite USER A and USER B, migrate/seed owner-scoped data, run A's evaluated text interview, derive and accept a weak MYSQL proposal, start A's next interview, and assert MYSQL receives weakness slots and an explanation. Assert B receives no A memory, unconfirmed proposals do not affect planning, same-hash resources remain isolated, and memory failure uses fixed allocation.

- [ ] **Step 2: Run the journey test and verify failure**

Run: `./gradlew :app:test --tests "*UserAbilityMemoryJourneyTest" --no-daemon`

Expected: FAIL until all cross-layer response wiring exists.

- [ ] **Step 3: Add sanitized plan APIs, UI explanation, and operations documentation**

Document initial admin and legacy owner variables, invite flow, migration backup/rollback, Session/CSRF behavior, memory derivation retry, profile confirmation, and fixed-allocation fallback. Do not document default secrets.

- [ ] **Step 4: Run final verification**

Run: `./gradlew :app:test --no-daemon`

Run: `./gradlew :app:compileJava`

Run from `frontend`: `pnpm test`

Run from `frontend`: `pnpm run build`

Expected: all commands PASS; no real LLM provider is contacted by tests.

- [ ] **Step 5: Commit**

```powershell
git add README.md app/src/main/java/interview/guide/modules/interview/planning app/src/test/java/interview/guide/integration frontend/src
git commit -m "feat: complete adaptive ability-memory journey"
```
