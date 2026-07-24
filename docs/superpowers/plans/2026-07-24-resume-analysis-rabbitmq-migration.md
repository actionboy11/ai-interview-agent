# Resume Analysis RabbitMQ Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate only resume-analysis asynchronous work from Redis Stream to RabbitMQ with manual acknowledgement, 10/30/60-second delayed retries, persisted dead letters, and Swagger-accessible replay while preserving every existing frontend API and task status.

**Architecture:** Introduce a `ResumeAnalysisTaskPublisher` port with mutually exclusive Redis Stream and RabbitMQ implementations selected by `app.resume.messaging.provider`. RabbitMQ uses a durable direct exchange, a main queue, three TTL/DLX retry queues, and a final dead-letter queue; consumers keep external AI calls outside database transactions and persist successful results or dead-letter audit records in short transactions.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP, RabbitMQ Management, Spring Data JPA, PostgreSQL, JUnit 5, Mockito, AssertJ, Testcontainers RabbitMQ.

## Global Constraints

- Keep `/api/resumes/upload`, `/api/resumes/{id}/reanalyze`, response payloads, and `PENDING -> PROCESSING -> COMPLETED/FAILED` semantics unchanged.
- Migrate only resume analysis; knowledge-base vectorization, text interview evaluation, and voice interview evaluation remain on Redis Stream.
- Use durable RabbitMQ resources, persistent messages, publisher confirms, publisher returns, and manual consumer acknowledgements.
- Retry after approximately 10, 30, and 60 seconds, then route to the final dead-letter queue.
- Never place resume text in RabbitMQ messages; load it from PostgreSQL using `resumeId`.
- Never hold a database transaction while calling an LLM or RabbitMQ.
- Preserve the user's existing explanatory comments and unrelated working-tree changes.
- Do not expose `.env` secrets in tests, logs, commits, or API responses.

---

## File Structure

**Create**

- `app/src/main/java/interview/guide/modules/resume/messaging/ResumeAnalysisTaskPublisher.java` — transport-neutral publish port.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisMessage.java` — immutable JSON message contract.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProperties.java` — exchange, queue, routing, retry, and provider configuration.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRetryPolicy.java` — deterministic retry destination selection.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConfig.java` — Rabbit topology, converter, and listener container settings.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProducer.java` — confirmed persistent publishing.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConsumer.java` — manual-ACK business consumer.
- `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisDeadLetterConsumer.java` — dead-queue persistence consumer.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterEntity.java` — audit entity.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterStatus.java` — `PENDING`, `REPLAYING`, `REPLAYED`, `RESOLVED`.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterRepository.java` — persistence and filtering.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterService.java` — persistence and replay orchestration.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterController.java` — admin HTTP endpoints.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterDTO.java` — safe API projection.
- `app/src/main/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterPageResponse.java` — stable pagination payload.
- Focused unit and integration tests listed in each task.

**Modify**

- `app/build.gradle` — Spring AMQP and Testcontainers dependencies.
- `app/src/main/resources/application.yml` — RabbitMQ connection and resume messaging properties.
- `app/src/test/resources/application-test.yml` — disable Rabbit listeners by default in ordinary tests.
- `.env.example` — RabbitMQ variables without secrets.
- `docker-compose.dev.yml`, `docker-compose.yml` — RabbitMQ Management service and app dependency.
- `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamProducer.java` — implement transport port and activate only for `redis-stream`.
- `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java` — activate only for `redis-stream`.
- `app/src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java` — depend on the transport port.
- `app/src/main/java/interview/guide/modules/resume/model/ResumeAnalysisEntity.java` — add unique `analysisMessageId`.
- `app/src/main/java/interview/guide/modules/resume/repository/ResumeAnalysisRepository.java` — message-id lookup.
- `app/src/main/java/interview/guide/modules/resume/service/ResumePersistenceService.java` — idempotent completion and status methods.

---

### Task 1: Add RabbitMQ dependencies, topology properties, and local infrastructure

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/resources/application-test.yml`
- Modify: `.env.example`
- Modify: `docker-compose.dev.yml`
- Modify: `docker-compose.yml`
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProperties.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitPropertiesTest.java`

**Interfaces:**
- Produces: `ResumeAnalysisRabbitProperties` bound to `app.resume.messaging`.
- Produces local RabbitMQ endpoints `5672` and `15672`.

- [ ] **Step 1: Write the failing property-binding test**

```java
@SpringBootTest(properties = {
    "app.resume.messaging.provider=rabbitmq",
    "app.resume.messaging.exchange=resume.analysis.exchange",
    "app.resume.messaging.retry-delays=10s,30s,60s"
})
@ActiveProfiles("test")
class ResumeAnalysisRabbitPropertiesTest {
  @Autowired ResumeAnalysisRabbitProperties properties;

  @Test
  @DisplayName("应绑定简历分析 RabbitMQ 配置")
  void shouldBindProperties() {
    assertThat(properties.provider()).isEqualTo("rabbitmq");
    assertThat(properties.exchange()).isEqualTo("resume.analysis.exchange");
    assertThat(properties.retryDelays())
        .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
  }
}
```

- [ ] **Step 2: Run the test and verify the missing type failure**

Run:

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitPropertiesTest" --no-daemon
```

Expected: compilation fails because `ResumeAnalysisRabbitProperties` does not exist.

- [ ] **Step 3: Add dependencies**

Add to `app/build.gradle`:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-amqp'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:rabbitmq'
```

- [ ] **Step 4: Add immutable configuration properties**

Create a validated `@ConfigurationProperties(prefix = "app.resume.messaging")` record containing:

```java
String provider,
String exchange,
String queue,
String routingKey,
String deadExchange,
String deadQueue,
String deadRoutingKey,
List<Duration> retryDelays
```

Use constructor defaults:

```text
provider=redis-stream
exchange=resume.analysis.exchange
queue=resume.analysis.queue
routingKey=resume.analysis
deadExchange=resume.analysis.dead.exchange
deadQueue=resume.analysis.dead.queue
deadRoutingKey=resume.analysis.dead
retryDelays=[10s,30s,60s]
```

- [ ] **Step 5: Configure Spring RabbitMQ**

Add:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:interview}
    password: ${RABBITMQ_PASSWORD:interview}
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: manual
        default-requeue-rejected: true

app:
  resume:
    messaging:
      provider: ${APP_RESUME_MESSAGING_PROVIDER:redis-stream}
      exchange: resume.analysis.exchange
      queue: resume.analysis.queue
      routing-key: resume.analysis
      dead-exchange: resume.analysis.dead.exchange
      dead-queue: resume.analysis.dead.queue
      dead-routing-key: resume.analysis.dead
      retry-delays: 10s,30s,60s
```

Set `spring.rabbitmq.listener.simple.auto-startup: false` and
`app.resume.messaging.provider: redis-stream` in `application-test.yml`.

- [ ] **Step 6: Add RabbitMQ Management to both Compose files**

Use `rabbitmq:4-management`, durable named volume `rabbitmq_data`, health check
`rabbitmq-diagnostics -q ping`, and environment variables:

```yaml
RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:-interview}
RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-interview}
```

Expose `5672:5672` and `15672:15672`. In the full Compose file, make the app wait
for RabbitMQ health. Add matching keys to `.env.example`.

- [ ] **Step 7: Run binding test and validate Compose**

Run:

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitPropertiesTest" --no-daemon
docker compose -f docker-compose.dev.yml config
```

Expected: test passes and Compose renders a `rabbitmq` service without unresolved variables.

- [ ] **Step 8: Commit**

```powershell
git add app/build.gradle app/src/main/resources/application.yml app/src/test/resources/application-test.yml .env.example docker-compose.dev.yml docker-compose.yml app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProperties.java app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitPropertiesTest.java
git commit -m "build: add RabbitMQ resume analysis infrastructure"
```

---

### Task 2: Declare RabbitMQ topology and deterministic retry policy

**Files:**
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisMessage.java`
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRetryPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConfig.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRetryPolicyTest.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConfigTest.java`

**Interfaces:**
- Produces: `ResumeAnalysisMessage(UUID messageId, Long resumeId, int retryCount, OffsetDateTime createdAt, UUID originalMessageId)`.
- Produces: `RetryDestination routingKeyFor(int retryCount)` where final failure has `deadLetter=true`.

- [ ] **Step 1: Write retry-policy tests**

Test exact mapping:

```java
assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
assertThat(policy.destinationFor(3).deadLetter()).isTrue();
assertThatThrownBy(() -> policy.destinationFor(-1))
    .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRetryPolicyTest" --no-daemon
```

Expected: compilation fails because the policy does not exist.

- [ ] **Step 3: Implement message and retry policy**

The message record validates non-null `messageId`, positive `resumeId`,
non-negative `retryCount`, and non-null `createdAt`. The policy reads the three
delays from properties and returns immutable `RetryDestination` values.

- [ ] **Step 4: Write topology bean tests**

Load `ResumeAnalysisRabbitConfig` with a mocked connection factory and assert:

- main and dead exchanges are durable direct exchanges;
- main and dead queues are durable;
- each retry queue has the expected `x-message-ttl`;
- each retry queue has `x-dead-letter-exchange=resume.analysis.exchange`;
- each retry queue has `x-dead-letter-routing-key=resume.analysis`;
- JSON messages use `JacksonJsonMessageConverter`;
- listener container acknowledgement mode is `MANUAL`.

- [ ] **Step 5: Implement topology**

Create explicit beans for both exchanges, five queues, bindings, JSON converter,
`RabbitTemplate`, and the manual-ACK container factory. Name retry routing keys
`resume.analysis.retry.10s`, `.30s`, and `.60s`.

- [ ] **Step 6: Run tests**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRetryPolicyTest" --tests "*ResumeAnalysisRabbitConfigTest" --no-daemon
```

Expected: all topology and policy tests pass.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume/messaging/rabbit app/src/test/java/interview/guide/modules/resume/messaging/rabbit
git commit -m "feat: declare resume analysis RabbitMQ topology"
```

---

### Task 3: Introduce the transport port and confirmed RabbitMQ publisher

**Files:**
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/ResumeAnalysisTaskPublisher.java`
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitProducerTest.java`
- Create: `app/src/test/java/interview/guide/modules/resume/service/ResumeUploadServiceMessagingTest.java`

**Interfaces:**
- Produces: `PublishReceipt publish(Long resumeId)` with `UUID messageId`.
- `AnalyzeStreamProducer` implements the same interface when provider is `redis-stream`.
- `ResumeAnalysisRabbitProducer` implements it when provider is `rabbitmq`.

- [ ] **Step 1: Write publisher tests**

Mock `RabbitTemplate` and verify `publish(42L)`:

- sends a persistent `ResumeAnalysisMessage`;
- uses the main exchange and routing key;
- waits for a correlated confirm;
- returns the generated message ID only on ACK;
- throws `BusinessException` for NACK, timeout, or returned messages.

- [ ] **Step 2: Write upload-service contract test**

Construct `ResumeUploadService` with a mocked `ResumeAnalysisTaskPublisher`.
Verify a new upload calls `publisher.publish(savedResume.getId())` once and
still returns `id`, `filename`, and `analyzeStatus=PENDING`.

- [ ] **Step 3: Run tests and verify they fail**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitProducerTest" --tests "*ResumeUploadServiceMessagingTest" --no-daemon
```

- [ ] **Step 4: Add the transport port and conditions**

Use `@ConditionalOnProperty`:

```text
AnalyzeStreamProducer + AnalyzeStreamConsumer:
  name=app.resume.messaging.provider
  havingValue=redis-stream
  matchIfMissing=true

ResumeAnalysisRabbitProducer and Rabbit consumers:
  name=app.resume.messaging.provider
  havingValue=rabbitmq
```

Change `ResumeUploadService` to inject `ResumeAnalysisTaskPublisher`. Preserve all
existing explanatory comments and behavior.

- [ ] **Step 5: Implement confirmed publishing**

Build `CorrelationData` from `messageId`, set delivery mode `PERSISTENT`, set
`mandatory=true`, send with `RabbitTemplate`, and wait with a bounded timeout.
On failure, invoke the existing status-compensation behavior so the resume
becomes `FAILED` with a truncated error.

- [ ] **Step 6: Run tests**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitProducerTest" --tests "*ResumeUploadServiceMessagingTest" --no-daemon
```

Expected: all tests pass and exactly one publisher bean is active per provider.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume/messaging app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamProducer.java app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java app/src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java app/src/test/java/interview/guide/modules/resume
git commit -m "refactor: decouple resume analysis message transport"
```

---

### Task 4: Add result idempotency and transaction-safe status operations

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/resume/model/ResumeAnalysisEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/repository/ResumeAnalysisRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/resume/service/ResumePersistenceService.java`
- Create: `app/src/test/java/interview/guide/modules/resume/service/ResumePersistenceIdempotencyTest.java`

**Interfaces:**
- Produces: `boolean isAnalysisMessageCompleted(String messageId)`.
- Produces: `void markAnalysisProcessing(Long resumeId)`.
- Produces: `void markAnalysisFailed(Long resumeId, String error)`.
- Produces: `ResumePersistenceService.CompletionResult completeAnalysis(Long resumeId, String messageId, ResumeAnalysisResponse analysis)` where the nested enum contains `CREATED` and `ALREADY_COMPLETED`.

- [ ] **Step 1: Write idempotency tests**

Cover:

- first completion saves one analysis and sets `COMPLETED`;
- repeating the same `messageId` saves no additional analysis;
- error text is truncated to the entity column limit;
- missing resume raises `BusinessException`;
- completion transaction updates result and status together.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*ResumePersistenceIdempotencyTest" --no-daemon
```

- [ ] **Step 3: Add the unique message column**

Add:

```java
@Column(name = "analysis_message_id", unique = true, length = 36)
private String analysisMessageId;
```

Add `existsByAnalysisMessageId(String analysisMessageId)` to the repository.
The column remains nullable for rows created before this migration; every new
RabbitMQ analysis must receive a message ID before persistence.

- [ ] **Step 4: Implement short transaction methods**

Use `@Transactional(rollbackFor = Exception.class)` only around database work.
Catch `DataIntegrityViolationException` from the unique constraint and return an
`ALREADY_COMPLETED` result instead of creating duplicates.

- [ ] **Step 5: Run tests**

```powershell
.\gradlew.bat :app:test --tests "*ResumePersistenceIdempotencyTest" --no-daemon
```

Expected: all idempotency and state-transition tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume/model/ResumeAnalysisEntity.java app/src/main/java/interview/guide/modules/resume/repository/ResumeAnalysisRepository.java app/src/main/java/interview/guide/modules/resume/service/ResumePersistenceService.java app/src/test/java/interview/guide/modules/resume/service/ResumePersistenceIdempotencyTest.java
git commit -m "feat: make resume analysis completion idempotent"
```

---

### Task 5: Implement manual-ACK RabbitMQ consumption and delayed retries

**Files:**
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConsumer.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConsumerTest.java`

**Interfaces:**
- Consumes: `ResumeAnalysisMessage`, `ResumePersistenceService`,
  `ResumeGradingService`, `ResumeAnalysisRabbitProducer`,
  `ResumeAnalysisRetryPolicy`.
- Produces: ACK, retry publish, or final dead-letter publish.

- [ ] **Step 1: Write consumer behavior tests**

Use mocked `Channel` and dependencies to verify:

- missing resume: `basicAck`;
- completed resume: `basicAck`;
- success: mark processing, call LLM outside transaction helper, complete, ACK;
- retryable failure at counts 0/1/2: publish to expected retry route then ACK;
- retry publish failure: do not ACK and call `basicNack(tag, false, true)`;
- failure at count 3: mark failed, publish final dead letter, ACK;
- malformed message: publish final dead letter without invoking the LLM.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitConsumerTest" --no-daemon
```

- [ ] **Step 3: Implement listener**

Use:

```java
@RabbitListener(
    queues = "${app.resume.messaging.queue}",
    containerFactory = "resumeAnalysisRabbitListenerContainerFactory"
)
```

Accept `ResumeAnalysisMessage`, `Message`, and `Channel`. Read the delivery tag
from `MessageProperties`. Keep AI invocation between the processing-status
transaction and completion transaction.

- [ ] **Step 4: Implement retry/dead publishing helpers**

The producer exposes internal methods:

```java
PublishReceipt publishRetry(ResumeAnalysisMessage failed, RetryDestination destination);
PublishReceipt publishDead(ResumeAnalysisMessage failed, String failureReason);
```

Each method generates a new current `messageId`, increments retry count only for
retry messages, preserves `originalMessageId`, requires Broker Confirm, and
truncates failure headers.

- [ ] **Step 5: Run consumer tests**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitConsumerTest" --no-daemon
```

Expected: all ACK, retry, and failure branches pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume/messaging/rabbit app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitConsumerTest.java
git commit -m "feat: consume resume analysis through RabbitMQ"
```

---

### Task 6: Persist dead letters and expose replay APIs

**Files:**
- Create all files under `app/src/main/java/interview/guide/modules/resume/deadletter/`
- Create: `app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisDeadLetterConsumer.java`
- Create: `app/src/test/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterServiceTest.java`
- Create: `app/src/test/java/interview/guide/modules/resume/deadletter/ResumeAnalysisDeadLetterControllerTest.java`
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisDeadLetterConsumerTest.java`

**Interfaces:**
- Produces paginated GET `/api/admin/resume-analysis/dead-letters`.
- Produces GET `/api/admin/resume-analysis/dead-letters/{id}`.
- Produces POST `/api/admin/resume-analysis/dead-letters/{id}/replay`.

- [ ] **Step 1: Write dead-letter service tests**

Verify:

- first dead message creates a `PENDING` audit row;
- duplicate `originalMessageId` is idempotently ignored;
- replay of a missing resume raises the repository-standard business error;
- replay of a completed resume marks `RESOLVED` without publishing;
- replay atomically claims `PENDING -> REPLAYING`, resets an unfinished resume
  to `PENDING`, publishes once, and marks `REPLAYED` only after confirm;
- publish failure leaves the record `PENDING`.

- [ ] **Step 2: Write controller tests**

With `MockMvc`, assert the three paths return `Result<T>`, never return the raw
entity, paginate deterministically, and validate invalid status/page inputs.

- [ ] **Step 3: Run and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisDeadLetter*" --no-daemon
```

- [ ] **Step 4: Implement entity and repository**

Use a unique constraint on `original_message_id`, `@Enumerated(EnumType.STRING)`
for status, `TEXT` for payload and failure reason, and repository methods for
filtered `Page` queries plus an atomic conditional update from `PENDING` to
`REPLAYING`. Do not keep a database transaction open while publishing.

- [ ] **Step 5: Implement service, DTO mapping, and controller**

Return only:

```text
id, originalMessageId, resumeId, retryCount, failureReason,
status, failedAt, replayedAt, replayMessageId
```

Never expose `payloadJson` through the list endpoint. Replay is a POST and is
rate-limited using the project's existing `@RateLimit` convention.

- [ ] **Step 6: Implement dead-letter consumer**

Consume `resume.analysis.dead.queue` with manual ACK. Persist the audit record in
a transaction, ACK after commit, and NACK/requeue when persistence fails.

- [ ] **Step 7: Run tests**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisDeadLetter*" --no-daemon
```

Expected: service, controller, and dead consumer tests all pass.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/resume/deadletter app/src/main/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisDeadLetterConsumer.java app/src/test/java/interview/guide/modules/resume/deadletter app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisDeadLetterConsumerTest.java
git commit -m "feat: add resume analysis dead-letter replay"
```

---

### Task 7: Add RabbitMQ integration tests

**Files:**
- Create: `app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitIntegrationTest.java`

**Interfaces:**
- Uses a Testcontainers `RabbitMQContainer`.
- Verifies the actual declared topology and message movement.

- [ ] **Step 1: Write the container-backed integration test**

Use `@Testcontainers`, `@DynamicPropertySource`, and a real RabbitMQ container.
Test:

- main publish reaches the main queue;
- manual ACK removes the delivery;
- rejected/unacked delivery is redelivered;
- a test-only retry topology with 100/200/300-ms TTLs returns messages to the
  main queue within bounded tolerance;
- final dead publish reaches the dead queue;
- unroutable mandatory publish is reported as failure.

- [ ] **Step 2: Run and verify the test fails before any test wiring fixes**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitIntegrationTest" --no-daemon
```

- [ ] **Step 3: Add only the test wiring needed**

Override retry delays through test properties, wait with condition polling
rather than fixed sleeps, and isolate queue names per test class to prevent
cross-test messages.

- [ ] **Step 4: Run integration test**

```powershell
.\gradlew.bat :app:test --tests "*ResumeAnalysisRabbitIntegrationTest" --no-daemon
```

Expected: container starts, all message-routing assertions pass, and it shuts
down cleanly.

- [ ] **Step 5: Commit**

```powershell
git add app/src/test/java/interview/guide/modules/resume/messaging/rabbit/ResumeAnalysisRabbitIntegrationTest.java
git commit -m "test: verify resume analysis RabbitMQ delivery"
```

---

### Task 8: Run end-to-end migration verification and document operation

**Files:**
- Modify: `README.md`
- Modify: `docs/` project documentation only if the repository intentionally tracks the target file with `git add -f`

**Interfaces:**
- Produces reproducible local startup and failure/replay instructions.

- [ ] **Step 1: Start dependencies**

```powershell
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml up -d
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml ps
```

Expected: PostgreSQL, Redis, RustFS, and RabbitMQ are all healthy. RabbitMQ UI is
available at `http://localhost:15672`.

- [ ] **Step 2: Run the full backend test suite**

```powershell
.\gradlew.bat :app:test --no-daemon
```

Expected: zero failed tests.

- [ ] **Step 3: Run the frontend production build**

```powershell
Push-Location frontend
corepack pnpm@10.26.0 run build
Pop-Location
```

Expected: TypeScript and Vite build exit successfully; existing non-fatal bundle
or CSS warnings are recorded but not misreported as failures.

- [ ] **Step 4: Run RabbitMQ mode locally**

Set:

```dotenv
APP_RESUME_MESSAGING_PROVIDER=rabbitmq
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=interview
RABBITMQ_PASSWORD=interview
```

Start:

```powershell
.\gradlew.bat :app:bootRun
```

Upload a test resume through Swagger and verify:

```text
PENDING -> PROCESSING -> COMPLETED
exactly one resume_analyses row
resume.analysis.queue returns to zero ready messages
```

- [ ] **Step 5: Verify retry, dead-letter persistence, and replay**

Temporarily use an invalid model credential in a disposable local run, upload a
test resume, and observe retry movement at approximately 10/30/60 seconds.
Confirm a `PENDING` dead-letter record appears after the final failure. Restore
the valid credential and call:

```http
POST /api/admin/resume-analysis/dead-letters/{id}/replay
```

Expected: the audit row passes through `REPLAYING` to `REPLAYED`, the resume
completes, and only one successful analysis record exists for the replay message.

- [ ] **Step 6: Verify Redis Stream rollback mode**

Set `APP_RESUME_MESSAGING_PROVIDER=redis-stream`, restart the backend, upload a
new test resume, and verify the original Stream consumer processes it. Confirm
RabbitMQ resume listeners are not running.

- [ ] **Step 7: Document commands and architecture**

Update README with:

- RabbitMQ environment variables and Management URL;
- mixed-development startup command;
- provider switch and rollback;
- queue names and retry timing;
- dead-letter Swagger endpoints;
- explicit statement that the other three asynchronous workloads remain on
  Redis Stream.

- [ ] **Step 8: Final verification**

```powershell
git diff --check
.\gradlew.bat :app:test --no-daemon
Push-Location frontend
corepack pnpm@10.26.0 run build
Pop-Location
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml config
git status --short
```

Expected: no whitespace errors, all tests pass, frontend builds, Compose config
is valid, and only intentional files are modified.

- [ ] **Step 9: Commit**

```powershell
git add README.md .env.example docker-compose.dev.yml docker-compose.yml app
git commit -m "docs: explain RabbitMQ resume analysis workflow"
```

---

## Implementation Notes

- Before Task 1, inspect and preserve the current uncommitted learning comments
  in resume and Redis files. Do not reset or overwrite them.
- At each commit, stage exact paths and inspect `git diff --cached --name-only`
  because the worktree already contains unrelated user edits.
- Do not delete Redis Stream resume classes during the first migration phase;
  the provider switch is the rollback path and comparison mechanism.
- If Spring Boot 4.1 APIs differ from an assumed Spring AMQP signature, consult
  the resolved dependency Javadoc/source and adapt only that boundary without
  changing the confirmed semantics.
