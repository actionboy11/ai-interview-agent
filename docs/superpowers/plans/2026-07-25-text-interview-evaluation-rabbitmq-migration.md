# Text Interview Evaluation RabbitMQ Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate text-interview evaluation from Redis Stream to an isolated RabbitMQ topology with confirmed publishing, manual acknowledgement, delayed retries, idempotent completion, persisted dead letters, replay APIs, and a Redis Stream rollback switch.

**Architecture:** Introduce a transport-neutral publisher selected by `app.interview-evaluation.messaging.provider`. RabbitMQ uses dedicated durable main, retry, and dead-letter resources; the consumer loads all evaluation data from PostgreSQL by `sessionId`, performs LLM work outside transactions, and uses short transactions for status, report completion, idempotency, and dead-letter state.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP 4.1, Spring Data JPA, PostgreSQL, Redis Stream fallback, JUnit 5, Mockito, AssertJ, Testcontainers RabbitMQ.

## Global Constraints

- Preserve all existing text-interview HTTP APIs, response payloads, and report fields.
- Preserve `PENDING -> PROCESSING -> COMPLETED/FAILED` evaluation semantics.
- Migrate only text-interview evaluation; knowledge-base vectorization remains on Redis Stream.
- Use dedicated `interview.evaluation.*` RabbitMQ resources in the existing broker.
- RabbitMQ messages contain identifiers only; never include questions, answers, resume text, prompts, or report content.
- Use persistent messages, publisher confirms, publisher returns, and manual consumer acknowledgements.
- Retry after approximately 10, 30, and 60 seconds, then route to a final dead-letter queue.
- Do not keep database transactions open during LLM calls or RabbitMQ publishing.
- Keep the Redis Stream provider as an exclusive rollback option.
- Keep the user's existing uncommitted learning comments intact and outside all feature commits.
- Follow two-space Java indentation, constructor injection, `BusinessException`, and `Result<T>` conventions.

---

## File Structure

**Create**

- `app/src/main/java/interview/guide/modules/interview/messaging/InterviewEvaluationTaskPublisher.java`
- RabbitMQ message, properties, retry policy, config, producer, main consumer, and dead consumer under `modules/interview/messaging/rabbit/`
- dead-letter entity, status, repository, DTO, page response, service, and controller under `modules/interview/deadletter/`
- focused tests matching each task.

**Modify**

- `.env.example`
- `README.md`
- `app/src/main/resources/application.yml`
- `app/src/test/resources/application-test.yml`
- `app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamProducer.java`
- `app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`
- `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`
- `app/src/main/java/interview/guide/modules/interview/repository/InterviewSessionRepository.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java`

---

### Task 1: Fix the RabbitMQ-mode startup regression and declare the text-evaluation topology

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationMessage.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitProperties.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRetryPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConfig.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewControllerContextTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitPropertiesTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRetryPolicyTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConfigTest.java`
- Modify: `.env.example`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/resources/application-test.yml`

**Interfaces:**
- Produces: `InterviewEvaluationMessage(UUID messageId, String sessionId, int retryCount, OffsetDateTime createdAt, UUID originalMessageId)`.
- Produces: `InterviewEvaluationRetryPolicy.RetryDestination(String routingKey, Duration delay, boolean deadLetter)`.
- Produces: beans named `interviewEvaluationRabbitTemplate` and `interviewEvaluationRabbitListenerContainerFactory`.

- [ ] **Step 1: Write a failing controller context regression test**

Create a narrow Spring context with mocked `VoiceInterviewService` and
`VoiceInterviewEvaluationService`, but no `VoiceEvaluateStreamProducer`. Register
`VoiceInterviewController` and assert the bean can be created.

- [ ] **Step 2: Run the regression test**

```powershell
.\gradlew.bat :app:test --tests "*VoiceInterviewControllerContextTest" --no-daemon
```

Expected: FAIL because the generated constructor still requires
`VoiceEvaluateStreamProducer`.

- [ ] **Step 3: Remove the unused controller dependency**

Delete the import and field:

```java
private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
```

Run the regression test again and expect PASS.

- [ ] **Step 4: Write failing message, property, retry, and topology tests**

Assert:

```java
var messageId = UUID.randomUUID();
var message = new InterviewEvaluationMessage(
    messageId, "session-42", 0, OffsetDateTime.now(), null
);
assertThat(message.originalMessageId()).isEqualTo(messageId);

assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
assertThat(policy.destinationFor(3).deadLetter()).isTrue();
assertThatThrownBy(() -> policy.destinationFor(-1))
    .isInstanceOf(IllegalArgumentException.class);
```

The topology test must assert durable direct main/dead exchanges, durable
main/dead queues, three durable retry queues, exact TTL/DLX arguments,
`JacksonJsonMessageConverter` trusted only for the text-evaluation message
package, mandatory `RabbitTemplate`, and `AcknowledgeMode.MANUAL`.

- [ ] **Step 5: Run the focused tests and verify missing-type failure**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationRabbitPropertiesTest" --tests "*InterviewEvaluationRetryPolicyTest" --tests "*InterviewEvaluationRabbitConfigTest" --no-daemon
```

Expected: compilation fails because the new types do not exist.

- [ ] **Step 6: Implement message validation, properties, retry policy, and topology**

Use prefix `app.interview-evaluation.messaging` and defaults:

```text
provider=rabbitmq
exchange=interview.evaluation.exchange
queue=interview.evaluation.queue
routingKey=interview.evaluation
deadExchange=interview.evaluation.dead.exchange
deadQueue=interview.evaluation.dead.queue
deadRoutingKey=interview.evaluation.dead
retryDelays=[10s,30s,60s]
```

Reject blank `sessionId` and negative retry counts. Retry queue/routing suffixes
are `10s`, `30s`, and `60s`.

- [ ] **Step 7: Add application, test, and environment configuration**

Add all topology properties to `application.yml`. Set provider to
`redis-stream` and Rabbit listeners to non-starting in `application-test.yml`.
Add:

```text
APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER=rabbitmq
```

to `.env.example`.

- [ ] **Step 8: Run focused tests**

```powershell
.\gradlew.bat :app:test --tests "*VoiceInterviewControllerContextTest" --tests "*InterviewEvaluationRabbitPropertiesTest" --tests "*InterviewEvaluationRetryPolicyTest" --tests "*InterviewEvaluationRabbitConfigTest" --no-daemon
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```powershell
git add .env.example app/src/main/resources/application.yml app/src/test/resources/application-test.yml app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java app/src/main/java/interview/guide/modules/interview/messaging/rabbit app/src/test/java/interview/guide/modules/voiceinterview/controller app/src/test/java/interview/guide/modules/interview/messaging/rabbit
git commit -m "feat: declare text evaluation RabbitMQ topology"
```

---

### Task 2: Introduce the transport-neutral publisher and confirmed publishing

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/InterviewEvaluationTaskPublisher.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitProducerTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitProducerContextTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/service/InterviewSessionServiceMessagingTest.java`

**Interfaces:**
- Produces: `InterviewEvaluationTaskPublisher.PublishReceipt publish(String sessionId)`.
- Redis Stream and RabbitMQ producers implement the same interface.
- Produces from Rabbit producer:
  `PublishReceipt publishRetry(InterviewEvaluationMessage failed, RetryDestination destination)`
  and `PublishReceipt publishDead(InterviewEvaluationMessage failed, String failureReason)`.

- [ ] **Step 1: Write failing Rabbit producer tests**

Mock `RabbitTemplate`. Verify `publish("session-42")` sends a persistent
`InterviewEvaluationMessage` to the main exchange/routing key, sets message and
correlation IDs, waits at most five seconds for `CorrelationData.Confirm`, and
returns a receipt only after ACK. NACK, timeout, returned message, and send
exception must throw `BusinessException`.

- [ ] **Step 2: Write the constructor-selection context test**

Register the producer with mocked `RabbitTemplate` and properties in
`AnnotationConfigApplicationContext`; assert Spring creates the production
constructor without ambiguity.

- [ ] **Step 3: Write service messaging tests**

For both existing evaluation trigger sites in `InterviewSessionService`, verify:

```java
verify(publisher).publish(sessionId);
```

The session must be `PENDING` immediately before/after successful publication.
If initial publication fails, a `REQUIRES_NEW` action must mark it `FAILED` and
truncate the stored error to 500 characters.

- [ ] **Step 4: Run tests and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationRabbitProducer*" --tests "*InterviewSessionServiceMessagingTest" --no-daemon
```

Expected: compilation fails because the publisher port and Rabbit producer do
not exist.

- [ ] **Step 5: Implement the publisher port and provider conditions**

Use:

```text
EvaluateStreamProducer + EvaluateStreamConsumer:
  havingValue=redis-stream

InterviewEvaluationRabbitProducer + Rabbit consumers:
  havingValue=rabbitmq, matchIfMissing=true
```

Change `InterviewSessionService` to inject
`InterviewEvaluationTaskPublisher`. Publish only after the surrounding
transaction commits, using the existing transaction synchronization pattern.

- [ ] **Step 6: Implement confirmed persistent publishing**

Use a five-second bounded confirm timeout, `mandatory=true`, persistent
delivery, message ID, correlation ID, and returned-message detection. Retry
messages generate a new `messageId`, increment `retryCount`, and preserve
`originalMessageId`. Limit the dead-letter failure header to 500 characters.

- [ ] **Step 7: Run focused tests**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationRabbitProducer*" --tests "*InterviewSessionServiceMessagingTest" --no-daemon
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/interview/messaging app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamProducer.java app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java app/src/test/java/interview/guide/modules/interview
git commit -m "refactor: decouple text evaluation message transport"
```

---

### Task 3: Make text-evaluation completion idempotent and atomic

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/repository/InterviewSessionRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`
- Test: `app/src/test/java/interview/guide/modules/interview/service/InterviewEvaluationPersistenceIdempotencyTest.java`

**Interfaces:**
- Produces: `boolean existsByEvaluationMessageId(String messageId)`.
- Produces:
  `InterviewPersistenceService.CompletionResult saveReport(String sessionId, InterviewReportDTO report, String messageId)`,
  where the result is `CREATED`, `ALREADY_COMPLETED`, or `SESSION_MISSING`.
- Keeps the existing two-argument `saveReport(String, InterviewReportDTO)` for
  synchronous callers.

- [ ] **Step 1: Write failing idempotency tests**

Cover:

- first completion saves every report field, message ID, `COMPLETED` evaluation
  status, `EVALUATED` session status, and completion timestamp;
- repeated same message ID returns `ALREADY_COMPLETED`;
- a unique-constraint race returns `ALREADY_COMPLETED`;
- missing session returns `SESSION_MISSING`;
- no report field is partially committed if persistence fails.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationPersistenceIdempotencyTest" --no-daemon
```

Expected: compilation fails because the message ID and completion result do not
exist.

- [ ] **Step 3: Add the nullable unique message ID**

Add to `InterviewSessionEntity`:

```java
@Column(name = "evaluation_message_id", unique = true, length = 36)
private String evaluationMessageId;
```

Add repository method:

```java
boolean existsByEvaluationMessageId(String evaluationMessageId);
```

- [ ] **Step 4: Implement short atomic completion**

Move report-field assignment into the three-argument transactional public
method. It must save the report fields, `evaluationMessageId`,
`evaluateStatus=COMPLETED`, `status=EVALUATED`, and `completedAt` atomically.
Catch only the unique-constraint race needed for idempotency; do not swallow
other persistence failures.

- [ ] **Step 5: Run focused and existing persistence tests**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationPersistenceIdempotencyTest" --tests "*InterviewPersistenceServiceTest" --no-daemon
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java app/src/main/java/interview/guide/modules/interview/repository/InterviewSessionRepository.java app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java app/src/test/java/interview/guide/modules/interview/service/InterviewEvaluationPersistenceIdempotencyTest.java
git commit -m "feat: make text evaluation completion idempotent"
```

---

### Task 4: Implement manual-ACK consumption and delayed retries

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConsumer.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConsumerTest.java`

**Interfaces:**
- Consumes: session repository, answer evaluation service, persistence service,
  object mapper, LLM provider registry, Rabbit producer, and retry policy.
- Produces: ACK, confirmed retry publish, confirmed final dead publish, or
  NACK/requeue.

- [ ] **Step 1: Write failing consumer branch tests**

Verify:

- missing session: ACK without LLM call;
- completed session or duplicate message ID: ACK;
- success: mark processing, reconstruct answered questions, evaluate outside a
  transaction, atomically save completion, then ACK;
- failures at retry counts 0, 1, and 2: confirmed retry publish then ACK;
- retry publish failure: `basicNack(tag, false, true)`;
- failure at retry count 3: mark failed, confirmed dead publish, then ACK;
- dead publish failure: NACK/requeue.

- [ ] **Step 2: Run and verify missing consumer failure**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationRabbitConsumerTest" --no-daemon
```

Expected: compilation fails because the consumer does not exist.

- [ ] **Step 3: Implement the Rabbit listener**

Use:

```java
@RabbitListener(
    queues = "${app.interview-evaluation.messaging.queue:interview.evaluation.queue}",
    containerFactory = "interviewEvaluationRabbitListenerContainerFactory"
)
```

Read the delivery tag from `MessageProperties`. Do not annotate the listener
with `@Transactional`. Load session/resume/questions/answers, obtain the
`ChatClient` from `LlmProviderRegistry`, call `AnswerEvaluationService`, then
invoke the short persistence method.

- [ ] **Step 4: Implement failure routing**

Use `InterviewEvaluationRetryPolicy.destinationFor(retryCount)`. ACK only after
confirmed retry/dead publication. On final failure, store a 500-character
maximum error on the session before dead publication.

- [ ] **Step 5: Run focused tests**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationRabbitConsumerTest" --tests "*InterviewEvaluationRabbitProducerTest" --no-daemon
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConsumer.java app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitConsumerTest.java
git commit -m "feat: consume text evaluation through RabbitMQ"
```

---

### Task 5: Persist text-evaluation dead letters and expose replay APIs

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterStatus.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterEntity.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterRepository.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterDTO.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterPageResponse.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterService.java`
- Create: `app/src/main/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterController.java`
- Create: `app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationDeadLetterConsumer.java`
- Test: `app/src/test/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/deadletter/InterviewEvaluationDeadLetterControllerTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationDeadLetterConsumerTest.java`

**Interfaces:**
- Produces:
  `GET /api/admin/interview-evaluation/dead-letters`
- Produces:
  `GET /api/admin/interview-evaluation/dead-letters/{id}`
- Produces:
  `POST /api/admin/interview-evaluation/dead-letters/{id}/replay`

- [ ] **Step 1: Write failing service tests**

Verify first insert, duplicate `originalMessageId`, paginated/status-filtered
listing, detail lookup, missing session, completed session resolution, atomic
`PENDING -> REPLAYING` claim, successful confirmed replay, and restoration to
`PENDING` after publication failure.

- [ ] **Step 2: Write failing controller tests**

Use `MockMvc` to assert `Result<T>` envelopes, default newest-first pagination,
optional enum status filtering, page `>= 0`, size `1..100`, replay rate limits,
and absence of `payloadJson` from all responses.

- [ ] **Step 3: Write failing dead-consumer tests**

Verify audit persistence success then ACK, persistence failure then
NACK/requeue, and duplicate original message ACK.

- [ ] **Step 4: Implement the audit model and repository**

Map table `interview_evaluation_dead_letters`. Use a unique
`original_message_id`, `TEXT` payload/reason columns, enum status, timestamps,
and:

```java
@Modifying
@Query("""
    update InterviewEvaluationDeadLetterEntity d
       set d.status = interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterStatus.REPLAYING
     where d.id = :id
       and d.status = interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterStatus.PENDING
    """)
int claimForReplay(@Param("id") Long id);
```

- [ ] **Step 5: Implement the dead-letter service**

Serialize the internal message for audit but never expose the JSON in the DTO.
Use `TransactionalExecutor.callRequiresNew` for the claim. Publish outside a
transaction. Missing sessions and publish failures restore `PENDING`; completed
sessions become `RESOLVED`; successful confirmed publication records
`replayMessageId`, `replayedAt`, and `REPLAYED`.

- [ ] **Step 6: Implement controller and dead listener**

The controller returns `Result<T>`. Apply both GLOBAL and IP `@RateLimit` to
replay. The dead listener persists first and ACKs afterward; persistence
failure performs NACK/requeue.

- [ ] **Step 7: Run focused tests**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationDeadLetter*" --no-daemon
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/interview/deadletter app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationDeadLetterConsumer.java app/src/test/java/interview/guide/modules/interview/deadletter app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationDeadLetterConsumerTest.java
git commit -m "feat: add text evaluation dead-letter replay"
```

---

### Task 6: Verify provider isolation and real RabbitMQ routing

**Files:**
- Create: `app/src/test/java/interview/guide/modules/interview/messaging/InterviewEvaluationProviderContextTest.java`
- Create: `app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitIntegrationTest.java`

**Interfaces:**
- Verifies exclusive provider activation.
- Uses the existing Testcontainers RabbitMQ dependency.

- [ ] **Step 1: Write provider-isolation tests**

Create two application contexts:

```text
provider=rabbitmq:
  Rabbit producer and both Rabbit listeners present
  Redis producer and consumer absent

provider=redis-stream:
  Redis producer and consumer present
  Rabbit producer and listeners absent
```

Mock all unrelated dependencies so the test validates bean selection only.

- [ ] **Step 2: Write the container-backed routing test**

Use unique resource names and short whole-second retry delays. Verify:

- main publish reaches the main queue;
- a raw-channel NACK with requeue redelivers the message;
- each TTL retry queue returns its message to the main queue;
- final dead publish reaches the dead queue;
- mandatory unroutable publication is surfaced as failure;
- the JSON converter deserializes `InterviewEvaluationMessage` while trusting
  only its package.

- [ ] **Step 3: Run the integration tests**

```powershell
.\gradlew.bat :app:test --tests "*InterviewEvaluationProviderContextTest" --tests "*InterviewEvaluationRabbitIntegrationTest" --no-daemon
```

Expected: all tests pass; the container test is skipped only when Docker is
unavailable.

- [ ] **Step 4: Commit**

```powershell
git add app/src/test/java/interview/guide/modules/interview/messaging/InterviewEvaluationProviderContextTest.java app/src/test/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitIntegrationTest.java
git commit -m "test: verify text evaluation RabbitMQ delivery"
```

---

### Task 7: Document, start, and verify the merged backend

**Files:**
- Modify: `README.md`

**Interfaces:**
- Produces reproducible startup, rollback, monitoring, and replay instructions.

- [ ] **Step 1: Document the new provider and topology**

Add:

```text
APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER=rabbitmq
```

Document main/retry/dead resource names, 10/30/60-second schedule, RabbitMQ
Management URL, dead-letter API paths, and Redis rollback. State that
knowledge-base vectorization remains on Redis Stream.

- [ ] **Step 2: Validate and start dependencies**

```powershell
docker compose -f docker-compose.dev.yml config --quiet
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

Expected: PostgreSQL, Redis, RabbitMQ, and RustFS are healthy.

- [ ] **Step 3: Run the complete backend suite**

```powershell
.\gradlew.bat :app:test --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Start RabbitMQ mode on a non-conflicting port**

Set `APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER=rabbitmq` and run:

```powershell
.\gradlew.bat :app:bootRun --no-daemon --args="--server.port=8081"
```

Verify:

```text
GET http://localhost:8081/v3/api-docs -> 200
```

Inspect the RabbitMQ Management API/UI and confirm the dedicated exchanges,
five queues, one main consumer, and one dead consumer.

- [ ] **Step 5: Verify Redis rollback context**

Stop the 8081 process, set provider to `redis-stream`, restart on 8081, and
verify the Redis producer/consumer are present while text-evaluation Rabbit
listeners are absent. Stop the verification backend afterward.

- [ ] **Step 6: Run final evidence commands**

```powershell
git diff --check
.\gradlew.bat :app:test --no-daemon --console=plain
docker compose -f docker-compose.dev.yml config --quiet
git status --short
```

Expected: no whitespace errors, all tests pass, Compose validates, and only
intentional files plus the user's pre-existing learning comments are modified.

- [ ] **Step 7: Commit**

```powershell
git add README.md
git commit -m "docs: explain RabbitMQ text evaluation workflow"
```
