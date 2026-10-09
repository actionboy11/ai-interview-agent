# Production Demo Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the AI Interview Platform on the existing Alibaba Cloud Hong Kong ECS through an authenticated HTTPS URL while keeping every infrastructure service private.

**Architecture:** Caddy is the only public application edge and handles automatic TLS plus HTTP Basic Auth. It forwards authenticated traffic to the frontend Nginx container, which serves React and proxies REST/SSE and WebSocket traffic to Spring Boot; PostgreSQL, Redis, RabbitMQ, and MinIO stay on a private Compose network.

**Tech Stack:** Docker Engine 29, Docker Compose 5, Caddy 2, Nginx, Spring Boot 4.1/Java 21, React 18/TypeScript/Vite, Vitest, PostgreSQL/pgvector, Redis, RabbitMQ, MinIO

**Spec:** `docs/superpowers/specs/2026-10-09-production-deployment-design.md`

## Global Constraints

- Target host is Ubuntu 22.04 x86_64 with 2 vCPU, 4 GiB memory, 4 GiB swap, and a 40 GiB system disk.
- Publicly publish only ports 80 and 443 for the application; retain port 22 only for necessary administrator access.
- Do not publish ports 5432, 6379, 5672, 15672, 9000, 9001, or 8080 from production Compose.
- Store real credentials only in root-readable `.env.production` on the ECS; never commit plaintext credentials, password hashes, or API keys.
- Keep the local hybrid-development Compose files and commands unchanged.
- All four asynchronous modules must explicitly use `rabbitmq` in production.
- Preserve REST, SSE, 50 MiB uploads, and voice-interview WebSocket behavior.
- Use `https://47-76-185-182.sslip.io` for the first deployment, with a configuration variable that permits later domain replacement.
- Never run `docker compose down -v` on the production host.

## Review Focus

- A relative, `ws://localhost`, or `wss://localhost` WebSocket value must resolve to the current browser host in non-local environments; pin this in Task 2 tests.
- HTTPS pages must always produce `wss://` and retain the session path and port from the current Origin; pin this in Task 2 tests.
- SSE responses must not be buffered or cut off by Nginx; pin the required proxy directives in Task 3 verification.
- Dollar signs in the Caddy password hash must survive Compose interpolation; pin the quoted `.env.production` format and rendered environment check in Task 4.
- Production Compose must expose no infrastructure or backend ports even when all services are rendered; pin the published-port allowlist in Task 4 verification.

---

## File Map

- `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`: return a deployment-neutral relative WebSocket path.
- `app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceInterviewServiceTest.java`: prove session responses no longer contain localhost.
- `frontend/src/utils/webSocketUrl.ts`: normalize backend WebSocket paths to the browser's current Origin.
- `frontend/src/utils/webSocketUrl.test.ts`: exercise HTTP, HTTPS, localhost, absolute URL, and relative URL cases.
- `frontend/src/pages/VoiceInterviewPage.tsx`: use the URL normalizer for new and resumed sessions.
- `frontend/package.json`, `frontend/pnpm-lock.yaml`: add the Vitest test runner and test script.
- `frontend/nginx.conf`: proxy REST/SSE and WebSocket traffic correctly.
- `app/Dockerfile`: include the runtime health-check client used by Compose.
- `deploy/Caddyfile`: automatic HTTPS, Basic Auth, and forwarding to frontend.
- `.env.production.example`: document production-only variables without real secrets.
- `docker-compose.prod.yml`: define the isolated, resource-limited production stack.
- `deploy/verify-production-config.sh`: validate rendered Compose, Caddy, Nginx, port exposure, and required variables.
- `deploy/backup.sh`: create bounded-retention PostgreSQL and MinIO backups.
- `docs/deployment.md`: document first deployment, updates, backup, restore, monitoring, and rollback.

### Task 1: Deployment-Neutral Backend WebSocket Path

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`
- Modify: `app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceInterviewServiceTest.java`

**Interfaces:**
- Produces: `SessionResponseDTO.webSocketUrl` containing `/ws/voice-interview/{sessionId}`.
- Consumes: existing session creation and retrieval service APIs.

- [ ] **Step 1: Write the failing backend test**

Add `@DisplayName("会话响应返回与部署环境无关的 WebSocket 相对路径")` to the existing service test. Arrange a session whose ID is `42L`, obtain its response through the public service method already used by neighboring tests, and assert:

```java
assertThat(response.getWebSocketUrl()).isEqualTo("/ws/voice-interview/42");
assertThat(response.getWebSocketUrl()).doesNotContain("localhost");
```

- [ ] **Step 2: Run the focused test and verify red**

Run:

```powershell
.\gradlew.bat :app:test --tests "*VoiceInterviewServiceTest*" --no-daemon
```

Expected: FAIL because the response is `ws://localhost:8080/ws/voice-interview/42`.

- [ ] **Step 3: Implement the relative path**

Change `VoiceInterviewService.buildSessionResponse(VoiceInterviewSessionEntity)` so `webSocketUrl` is exactly `/ws/voice-interview/{id}`. Do not add a new public-base-url property.

- [ ] **Step 4: Run the focused backend test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceInterviewServiceTest.java
git commit -m "fix: make voice websocket URL deployment neutral"
```

### Task 2: Browser-Origin WebSocket URL Normalization

**Files:**
- Create: `frontend/src/utils/webSocketUrl.ts`
- Create: `frontend/src/utils/webSocketUrl.test.ts`
- Modify: `frontend/src/pages/VoiceInterviewPage.tsx`
- Modify: `frontend/package.json`
- Modify: `frontend/pnpm-lock.yaml`

**Interfaces:**
- Consumes: backend `webSocketUrl: string` from Task 1 and `window.location`.
- Produces: `resolveVoiceWebSocketUrl(sessionId: number, candidate?: string, location?: Pick<Location, 'protocol' | 'host' | 'hostname'>): string`.

- [ ] **Step 1: Add Vitest and the frontend test command**

Run from `frontend/`:

```bash
pnpm add -D vitest
```

Add script `"test": "vitest run"` to `frontend/package.json`.

- [ ] **Step 2: Write failing URL-normalizer tests**

Create `webSocketUrl.test.ts` with these cases:

- HTTPS `example.com` plus `/ws/voice-interview/7` returns `wss://example.com/ws/voice-interview/7`.
- HTTP `example.com:8088` plus no candidate returns `ws://example.com:8088/ws/voice-interview/7`.
- HTTPS public host plus legacy `ws://localhost:8080/...` returns the public `wss://` same-origin URL.
- Localhost development plus an explicit same-localhost absolute URL remains usable.
- A candidate with an unexpected path is ignored in favor of `/ws/voice-interview/{sessionId}`.

- [ ] **Step 3: Run the frontend test and verify red**

Run:

```bash
pnpm test -- webSocketUrl.test.ts
```

Expected: FAIL because `resolveVoiceWebSocketUrl` does not exist.

- [ ] **Step 4: Implement `resolveVoiceWebSocketUrl`**

Create the exact exported signature from the Interfaces block. Select `wss:` for `https:` and `ws:` otherwise, use `location.host`, and always build `/ws/voice-interview/{sessionId}`. Permit the explicit candidate only when its parsed hostname is `localhost` or `127.0.0.1` and the current page is also local development.

- [ ] **Step 5: Replace page-local URL fallbacks**

In both new-session and resume-session branches of `VoiceInterviewPage.tsx`, replace `session.webSocketUrl || ws://localhost...` with `resolveVoiceWebSocketUrl(session.sessionId, session.webSocketUrl)`.

- [ ] **Step 6: Run frontend tests and build**

Run:

```bash
pnpm test
pnpm run build
```

Expected: all Vitest tests PASS and Vite build exits 0.

- [ ] **Step 7: Commit Task 2**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml frontend/src/utils/webSocketUrl.ts frontend/src/utils/webSocketUrl.test.ts frontend/src/pages/VoiceInterviewPage.tsx
git commit -m "fix: resolve voice websocket from browser origin"
```

### Task 3: Production Reverse Proxy Behavior

**Files:**
- Modify: `frontend/nginx.conf`
- Modify: `app/Dockerfile`
- Create: `deploy/Caddyfile`

**Interfaces:**
- Consumes: internal services `frontend:80` and `app:8080`.
- Produces: authenticated HTTPS entry, `/api/` REST/SSE proxy, and `/ws/` WebSocket proxy.

- [ ] **Step 1: Record failing configuration checks**

Before edits, run and save the expected failures for:

```bash
rg -n "location /ws/|proxy_buffering off|Upgrade" frontend/nginx.conf
test -f deploy/Caddyfile
```

Expected: no WebSocket/SSE directives and missing Caddyfile.

- [ ] **Step 2: Add Nginx SSE and WebSocket proxy directives**

Keep SPA behavior and the 50 MiB upload limit. In `/api/`, set `proxy_buffering off`, disable proxy cache, and retain 300-second timeouts. Add `/ws/` forwarding to `http://app:8080` with HTTP/1.1, `Upgrade`, `Connection "upgrade"`, forwarded headers, and 3600-second read/send timeouts.

- [ ] **Step 3: Add the Caddy edge configuration**

Create `deploy/Caddyfile` with site address `{$SITE_ADDRESS}`, `basic_auth` credentials from `{$DEMO_USERNAME}` and `{$DEMO_PASSWORD_HASH}`, response security headers, and `reverse_proxy frontend:80`. Do not duplicate API routing in Caddy.

- [ ] **Step 4: Add a runtime health-check client**

Modify the JRE stage in `app/Dockerfile` to install `curl` with the image's native package manager and remove package indexes. The production Compose health check will call `http://localhost:8080/actuator/health`.

- [ ] **Step 5: Validate standalone proxy configurations**

Run:

```bash
docker run --rm -v "$PWD/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine nginx -t
docker run --rm -e SITE_ADDRESS=example.com -e DEMO_USERNAME=demo -e DEMO_PASSWORD_HASH='$2a$14$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuuu' -v "$PWD/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile
```

Expected: both configurations are valid. If the placeholder hash is rejected, generate a temporary valid hash with `caddy hash-password` and repeat without saving it.

- [ ] **Step 6: Commit Task 3**

```bash
git add frontend/nginx.conf app/Dockerfile deploy/Caddyfile
git commit -m "feat: add authenticated HTTPS reverse proxy"
```

### Task 4: Isolated Resource-Limited Production Compose

**Files:**
- Create: `docker-compose.prod.yml`
- Create: `.env.production.example`
- Create: `deploy/verify-production-config.sh`

**Interfaces:**
- Consumes: Dockerfiles and proxy files from Tasks 1-3.
- Produces: `docker compose --env-file .env.production -f docker-compose.prod.yml` production stack.

- [ ] **Step 1: Write the failing production-config verifier**

Create `deploy/verify-production-config.sh` that exits nonzero unless all of the following hold after `docker compose config` renders:

- Services are exactly the required production services plus the one-shot Bucket initializer.
- Only Caddy publishes host ports, limited to 80 and 443.
- `app` contains all four `APP_*_MESSAGING_PROVIDER=rabbitmq` variables.
- `app` contains a non-empty `JAVA_TOOL_OPTIONS` memory limit.
- Caddy receives `SITE_ADDRESS`, `DEMO_USERNAME`, and the literal non-empty password hash.
- No required secret retains `change_me`, `password`, `minioadmin`, or an empty value.

The script must use a temporary rendered file and remove it with a shell `trap`; it must never echo secret values.

- [ ] **Step 2: Run the verifier and verify red**

Run:

```bash
bash deploy/verify-production-config.sh .env.production.example
```

Expected: FAIL because the Compose and example environment files do not exist.

- [ ] **Step 3: Create the environment template**

Create `.env.production.example` with placeholders for `SITE_ADDRESS`, `DEMO_USERNAME`, single-quoted `DEMO_PASSWORD_HASH`, PostgreSQL, RabbitMQ, MinIO, AI encryption, DashScope, model, and all four messaging providers. Include comments showing secure generation commands, but no valid credential.

- [ ] **Step 4: Create `docker-compose.prod.yml`**

Define `caddy`, `frontend`, `app`, `postgres`, `redis`, `rabbitmq`, `minio`, and `createbuckets` on one private network. Publish only Caddy `80:80` and `443:443`. Add named volumes for all persistent state, health checks, dependency conditions, restart policies, logging rotation, and the resource budgets from the spec.

Set:

- `JAVA_TOOL_OPTIONS=-Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError`.
- PostgreSQL `shared_buffers=128MB` and `max_connections=50`.
- Redis `maxmemory=128mb` with an appropriate cache eviction policy.
- RabbitMQ absolute/relative memory watermark consistent with a 384 MiB container.
- `APP_AI_CONFIG_YAML_PATH` and `APP_AI_CONFIG_ENV_PATH` inside a persistent mounted directory.
- CORS origin to `https://${SITE_ADDRESS}`.

- [ ] **Step 5: Generate a temporary valid test environment**

Copy the template to an ignored temporary environment file, replace every placeholder with randomly generated local-only test values, and create a valid Caddy hash. Quote the hash with single quotes so all `$` characters survive Compose interpolation.

- [ ] **Step 6: Run the production-config verifier**

Run:

```bash
bash deploy/verify-production-config.sh .env.production.test
```

Expected: PASS without printing any secret.

- [ ] **Step 7: Render and build the production stack**

Run:

```bash
docker compose --env-file .env.production.test -f docker-compose.prod.yml config --quiet
docker compose --env-file .env.production.test -f docker-compose.prod.yml build app
docker compose --env-file .env.production.test -f docker-compose.prod.yml build frontend
```

Expected: configuration and both image builds exit 0.

- [ ] **Step 8: Delete the temporary environment and commit Task 4**

Ensure `.env.production.test` is absent and ignored, then commit:

```bash
git add docker-compose.prod.yml .env.production.example deploy/verify-production-config.sh
git commit -m "feat: add isolated production compose stack"
```

### Task 5: Backup, Restore, and Operator Documentation

**Files:**
- Create: `deploy/backup.sh`
- Create: `deploy/test-backup.sh`
- Create: `docs/deployment.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: production service and volume names from Task 4.
- Produces: timestamped, bounded-retention backups and exact operator procedures.

- [ ] **Step 1: Write the failing backup-script tests**

Create `deploy/test-backup.sh` with isolated temporary directories and mocked `docker`. Assert that the future script accepts `--env-file` and `--output-dir`, defaults retention to 7 days, fails before writing if the production stack is unavailable, creates a PostgreSQL custom-format dump plus a MinIO archive/mirror, and removes only backup files older than the configured retention inside the resolved backup directory.

Create a shell test mode or command-mocking harness that verifies:

- A missing environment file fails.
- A failed `pg_dump` keeps no falsely successful database artifact.
- Retention never resolves outside the requested backup directory.

- [ ] **Step 2: Run backup tests and verify red**

Run:

```bash
bash deploy/test-backup.sh
```

Expected: FAIL because `deploy/backup.sh` does not exist.

- [ ] **Step 3: Implement `deploy/backup.sh`**

Use `set -Eeuo pipefail`, resolve absolute output paths before cleanup, use Compose exec/run commands rather than published database ports, and never print credentials.

- [ ] **Step 4: Run backup tests against command mocks**

Run:

```bash
bash deploy/test-backup.sh
```

Expected: all safety checks PASS without requiring live production data.

- [ ] **Step 5: Write deployment documentation**

Document:

- Alibaba security-group rules for 22/80/443 and removal of every other inbound rule.
- ECS repository clone, `.env.production` creation, `chmod 600`, password-hash generation, and validation.
- Serial app/frontend builds, stack start, health/log commands, `docker stats`, and URL checks.
- Backup and restore commands.
- Git-based update and rollback commands.
- Free-trial quota monitoring and safe shutdown.
- The prohibition on `down -v`.

- [ ] **Step 6: Narrow the docs ignore rule**

Update `.gitignore` so `docs/deployment.md`, the approved spec, and this plan remain tracked without broadly unignoring unrelated generated docs.

- [ ] **Step 7: Commit Task 5**

```bash
git add .gitignore deploy/backup.sh deploy/test-backup.sh docs/deployment.md docs/superpowers/specs/2026-10-09-production-deployment-design.md docs/superpowers/plans/2026-10-09-production-demo-deployment.md
git commit -m "docs: add production deployment operations guide"
```

### Task 6: Full Local Verification and Repository Publication

**Files:**
- Verify all files changed in Tasks 1-5.
- Correct: `README.md` attribution/contribution wording if it still claims non-MQ Agent features as original work.

**Interfaces:**
- Consumes: completed implementation.
- Produces: a verified Git commit reachable from `origin/master`.

- [ ] **Step 1: Correct contribution boundaries in README**

Keep platform capabilities separate from personal modifications. State that the primary second-development contribution is the four-module Redis Stream-to-RabbitMQ migration and production deployment work; do not claim original authorship of pre-existing Agent, Advisor, RAG, or structured-output capabilities.

- [ ] **Step 2: Run the full backend suite**

```powershell
.\gradlew.bat :app:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full frontend verification**

```bash
cd frontend
pnpm test
pnpm run build
```

Expected: tests and build exit 0; pre-existing non-fatal bundle warnings may remain documented.

- [ ] **Step 4: Run configuration and diff verification**

Regenerate the ignored `.env.production.test` using Task 4 Step 5, then run:

```bash
bash deploy/verify-production-config.sh .env.production.test
git diff --check
git status --short
```

Expected: production validation passes, no whitespace errors, and only intended files are present.

Delete `.env.production.test` immediately after this verification.

- [ ] **Step 5: Review the complete branch diff**

Verify no real IP credentials beyond the public site hostname, API keys, password hashes, `.env.production`, build output, or backup files are staged.

- [ ] **Step 6: Commit final documentation corrections**

```bash
git add README.md
git commit -m "docs: distinguish platform features from project contributions"
```

Skip this commit if Task 6 Step 1 produces no diff.

- [ ] **Step 7: Push verified commits**

```bash
git push origin master
```

Expected: remote `master` resolves to the local `HEAD`.

### Task 7: ECS Deployment and Live Acceptance

**Files:**
- Server-only secret file: `/opt/ai-interview-agent/.env.production`
- Server checkout: `/opt/ai-interview-agent`
- No secrets are copied back into the repository.

**Interfaces:**
- Consumes: verified `origin/master`, ECS Docker/Compose installation, and Alibaba security group.
- Produces: authenticated live site at `https://47-76-185-182.sslip.io`.

- [ ] **Step 1: Restrict the Alibaba security group**

Allow TCP 80 and 443 from `0.0.0.0/0`. Keep TCP 22 only for the required administrative source or Alibaba Workbench path. Remove public rules for 5432, 6379, 5672, 15672, 8080, 9000, and 9001.

- [ ] **Step 2: Clone the verified repository**

Clone `https://github.com/actionboy11/ai-interview-agent.git` into `/opt/ai-interview-agent` and verify its `HEAD` equals the pushed local commit.

- [ ] **Step 3: Create production secrets**

Copy `.env.production.example` to `.env.production`, replace every placeholder with unique random values, generate the Caddy hash on the ECS, single-quote it, set `SITE_ADDRESS=47-76-185-182.sslip.io`, and run:

```bash
chmod 600 .env.production
bash deploy/verify-production-config.sh .env.production
```

Expected: validation PASS and no secret printed.

- [ ] **Step 4: Build serially and start**

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml build app
docker compose --env-file .env.production -f docker-compose.prod.yml build frontend
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

- [ ] **Step 5: Verify container health and resource use**

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml ps
docker stats --no-stream
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=200 app caddy frontend
```

Expected: long-running services are healthy/running, one-shot Bucket initialization exited 0, and the host is not swapping continuously.

- [ ] **Step 6: Run unauthenticated and authenticated edge checks**

Verify HTTP redirects to HTTPS, HTTPS without credentials returns 401, and authenticated `/` plus a harmless health/API request succeed. Do not place the plaintext password in shell history; use an interactive prompt or temporary protected variable and unset it immediately.

- [ ] **Step 7: Verify externally closed ports**

From the local workstation, confirm 5432, 6379, 5672, 15672, 8080, 9000, and 9001 are unreachable while 443 is reachable.

- [ ] **Step 8: Complete browser acceptance**

Using the authenticated HTTPS site:

- Load and refresh the React SPA.
- Upload a small sample resume and observe RabbitMQ-backed analysis completion.
- Start a text interview or RAG stream and verify incremental output.
- Grant microphone permission and establish a voice interview WebSocket.
- Restart the Compose stack and confirm persisted data remains.

- [ ] **Step 9: Create the first backup and record operations**

Run `deploy/backup.sh`, copy the resulting backup off the ECS, record the trial expiry/remaining quota, and schedule only the documented bounded-retention backup command.
