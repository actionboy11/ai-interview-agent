# Knowledge Vectorization RabbitMQ Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate knowledge-base vectorization from Redis Stream to a dedicated RabbitMQ topology whose messages contain only identifiers, with RustFS reload, confirmed publishing, manual acknowledgement, retries, idempotency, dead-letter audit, replay, and Redis rollback.

**Architecture:** Add a transport-neutral `KnowledgeVectorizationTaskPublisher.publish(Long knowledgeBaseId)`. The Rabbit consumer loads metadata from PostgreSQL, downloads and parses the original file from RustFS, then invokes the existing temporary-job vectorization workflow outside database transactions; short transactions persist status, chunk count, completion IDs, and dead-letter state.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP 4.1, PostgreSQL, pgvector, RustFS/S3, Apache Tika, Redis Stream fallback, JUnit 5, Mockito, AssertJ, Testcontainers RabbitMQ.

## Global Constraints

- Preserve existing knowledge-base HTTP APIs, response fields, and frontend behavior.
- RabbitMQ messages contain only `messageId`, `knowledgeBaseId`, `retryCount`, `createdAt`, and `originalMessageId`.
- Never put document bytes, parsed text, chunks, prompts, embeddings, or vectors in RabbitMQ.
- Use dedicated durable `knowledge.vectorization.*` exchanges and queues.
- Use persistent messages, confirms, returns, manual ACK, and 10/30/60-second TTL retries.
- RustFS download, document parsing, embedding, vector-store calls, and RabbitMQ publishing must run outside database transactions.
- Preserve the existing pending-vector-job activation and cleanup mechanism.
- Keep `redis-stream` as an exclusive rollback provider.
- Keep the user's existing uncommitted learning comments intact and outside feature commits.

---

### Task 1: Declare the identifier-only message and dedicated RabbitMQ topology

**Files:**
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationMessage.java`
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationRabbitProperties.java`
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationRetryPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationRabbitConfig.java`
- Test: corresponding `*Test.java` files under `app/src/test/java/interview/guide/modules/knowledgebase/messaging/rabbit/`
- Modify: `.env.example`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/resources/application-test.yml`

**Interfaces:**
- Produces: `KnowledgeVectorizationMessage(UUID messageId, Long knowledgeBaseId, int retryCount, OffsetDateTime createdAt, UUID originalMessageId)`.
- Produces: `KnowledgeVectorizationRetryPolicy.RetryDestination(String routingKey, Duration delay, boolean deadLetter)`.
- Produces beans `knowledgeVectorizationRabbitTemplate` and `knowledgeVectorizationRabbitListenerContainerFactory`.

- [ ] Write failing tests that reject null/non-positive IDs and negative retry counts, bind all properties, select 10/30/60-second destinations, and assert five durable queues, two durable direct exchanges, TTL/DLX arguments, package-scoped JSON trust, mandatory publishing, and manual ACK.
- [ ] Run:

```powershell
.\gradlew.bat :app:test --tests "*KnowledgeVectorizationRabbitPropertiesTest" --tests "*KnowledgeVectorizationRetryPolicyTest" --tests "*KnowledgeVectorizationRabbitConfigTest" --no-daemon
```

Expected: compilation fails because the types do not exist.

- [ ] Implement defaults:

```text
provider=rabbitmq
exchange=knowledge.vectorization.exchange
queue=knowledge.vectorization.queue
routingKey=knowledge.vectorization
deadExchange=knowledge.vectorization.dead.exchange
deadQueue=knowledge.vectorization.dead.queue
deadRoutingKey=knowledge.vectorization.dead
retryDelays=[10s,30s,60s]
```

- [ ] Condition the config and retry policy on provider `rabbitmq`; add the YAML, test provider `redis-stream`, and `APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER=rabbitmq`.
- [ ] Re-run focused tests and commit:

```powershell
git commit -m "feat: declare knowledge vectorization RabbitMQ topology"
```

---

### Task 2: Introduce the publisher port and provider-specific adapters

**Files:**
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/KnowledgeVectorizationTaskPublisher.java`
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationRabbitProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java`
- Test: producer, provider-context, and upload-service messaging tests.

**Interfaces:**
- Produces: `PublishReceipt publish(Long knowledgeBaseId)`.
- Rabbit producer also produces `publishRetry(KnowledgeVectorizationMessage, RetryDestination)` and `publishDead(KnowledgeVectorizationMessage, String)`.

- [ ] Write failing tests for Rabbit publish ACK/NACK/timeout/returned message, persistent delivery, new retry IDs with preserved original ID, and provider-exclusive bean activation.
- [ ] Write failing upload/revectorize tests verifying both paths call:

```java
verify(publisher).publish(knowledgeBaseId);
```

- [ ] Run the focused tests and confirm missing port/producer failures.
- [ ] Implement the Rabbit producer with a five-second confirm timeout and `BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, ...)`.
- [ ] Make Redis producer implement the port and condition both Redis producer/consumer on `redis-stream`. Its `publish(Long)` loads the entity, calls `KnowledgeBaseParseService.downloadAndParseContent(storageKey, originalFilename)`, then sends the legacy Stream message containing content.
- [ ] Change `KnowledgeBaseUploadService` to inject the port and publish only the ID. Initial publish failure marks vector status `FAILED` in a short independent transaction.
- [ ] Run focused tests and commit:

```powershell
git commit -m "refactor: decouple knowledge vectorization transport"
```

---

### Task 3: Add idempotent vectorization completion

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/repository/KnowledgeBaseRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java`
- Modify: `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBasePersistenceService.java`
- Test: `KnowledgeVectorizationPersistenceIdempotencyTest.java`
- Test: existing `KnowledgeBaseVectorServiceTest.java`

**Interfaces:**
- Adds nullable unique `vectorizationMessageId`.
- Changes `vectorizeAndStore(Long, String)` to return the activated chunk count.
- Produces `CompletionResult completeVectorization(Long knowledgeBaseId, int chunkCount, String messageId)` with `CREATED`, `ALREADY_COMPLETED`, and `KNOWLEDGE_BASE_MISSING`.

- [ ] Write failing tests for first completion, duplicate ID, unique-constraint race, missing entity, atomic `chunkCount/messageId/COMPLETED`, and vector service returning the exact activated chunk count.
- [ ] Run focused tests and confirm failure for missing APIs.
- [ ] Add the unique field and repository `existsByVectorizationMessageId`.
- [ ] Return `totalChunks` only after `activateVectorJob` succeeds; preserve cleanup on all failures.
- [ ] Implement short transactional completion in `KnowledgeBasePersistenceService`; do not invoke transactional methods through self-calls.
- [ ] Run focused plus existing vector tests and commit:

```powershell
git commit -m "feat: make knowledge vectorization completion idempotent"
```

---

### Task 4: Consume from RabbitMQ with RustFS reload, manual ACK, and retries

**Files:**
- Create: `app/src/main/java/interview/guide/modules/knowledgebase/messaging/rabbit/KnowledgeVectorizationRabbitConsumer.java`
- Test: `KnowledgeVectorizationRabbitConsumerTest.java`

**Interfaces:**
- Consumes repository, parse service, vector service, persistence service, Rabbit producer, and retry policy.
- Produces ACK, confirmed retry/dead publication, or NACK/requeue.

- [ ] Write failing branch tests for deleted entity, completed/duplicate message, missing `storageKey`, download failure, empty parsed text, successful vectorization, retry counts 0/1/2, final dead at 3, and retry/dead publish failure.
- [ ] Run the consumer test and verify missing-type failure.
- [ ] Implement the listener on `knowledge.vectorization.queue` with the dedicated manual-ACK factory.
- [ ] Flow: load entity; skip deleted/completed; mark `PROCESSING`; download/parse outside transaction; call vector service; short-transaction completion; ACK.
- [ ] On failure, publish confirmed retry/dead before ACK. On republish failure use `basicNack(tag, false, true)`. Truncate stored/header errors to 500 characters.
- [ ] Run consumer, producer, parse, and vector tests; commit:

```powershell
git commit -m "feat: consume knowledge vectorization through RabbitMQ"
```

---

### Task 5: Persist dead letters and expose replay APIs

**Files:**
- Create all audit files under `app/src/main/java/interview/guide/modules/knowledgebase/deadletter/`
- Create: `KnowledgeVectorizationDeadLetterConsumer.java`
- Test: service, controller, and consumer tests.

**Interfaces:**
- `GET /api/admin/knowledge-vectorization/dead-letters`
- `GET /api/admin/knowledge-vectorization/dead-letters/{id}`
- `POST /api/admin/knowledge-vectorization/dead-letters/{id}/replay`

- [ ] Write failing tests for idempotent record insertion, newest-first pagination, status filtering, hidden `payloadJson`, atomic replay claim, deleted/completed resolution, successful replay, publish rollback to `PENDING`, ACK-after-persist, and NACK/requeue on persistence failure.
- [ ] Run focused tests and confirm missing types.
- [ ] Implement `knowledge_vectorization_dead_letters` with unique `original_message_id`, `TEXT` payload/reason, `PENDING/REPLAYING/REPLAYED/RESOLVED`, and atomic conditional claim query.
- [ ] Implement replay outside transactions; deleted/completed knowledge bases become `RESOLVED`, publication failure restores `PENDING`.
- [ ] Add `Result<T>` controller and GLOBAL/IP repeatable `@RateLimit`; never expose payload JSON.
- [ ] Run focused tests and commit:

```powershell
git commit -m "feat: add knowledge vectorization dead-letter replay"
```

---

### Task 6: Real routing, startup, rollback, documentation, and final verification

**Files:**
- Create: `KnowledgeVectorizationRabbitIntegrationTest.java`
- Create: `KnowledgeVectorizationProviderConditionTest.java`
- Modify: `README.md`

**Interfaces:**
- Verifies provider isolation and real broker behavior.
- Documents reproducible operation and rollback.

- [ ] Write Testcontainers routing tests using unique resource names and short whole-second TTLs: main delivery, NACK/requeue, all retry returns, final dead delivery, trusted deserialization, and mandatory unroutable failure.
- [ ] Write context tests proving Rabbit beans are absent in `redis-stream` mode and Redis producer/consumer are absent in `rabbitmq` mode.
- [ ] Run both integration tests and commit:

```powershell
git commit -m "test: verify knowledge vectorization RabbitMQ delivery"
```

- [ ] Document provider variable, five queue names, retry schedule, RabbitMQ Management URL, dead-letter APIs, RustFS reload behavior, and Redis rollback.
- [ ] Run:

```powershell
docker compose -f docker-compose.dev.yml config --quiet
.\gradlew.bat :app:test --no-daemon --console=plain
```

- [ ] Start Rabbit mode on port 8081 with the main workspace `.env` loaded only into the process. Verify `/v3/api-docs` returns 200, two exchanges/five queues exist, and main/dead each have one consumer.
- [ ] Restart with `APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER=redis-stream`. Verify Swagger 200, Redis vector consumer starts, and Rabbit knowledge queues have zero consumers. Stop the verification backend.
- [ ] Run final `git diff --check`, full tests, Compose validation, and `git status --short`.
- [ ] Commit:

```powershell
git commit -m "docs: explain RabbitMQ knowledge vectorization workflow"
```
