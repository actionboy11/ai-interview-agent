# Voice Evaluation RabbitMQ Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate only voice-interview evaluation from Redis Stream to an isolated RabbitMQ topology with confirmed publishing, manual acknowledgement, 10/30/60-second retries, persisted dead letters, replay APIs, idempotency, and Redis rollback.

**Architecture:** Add a transport-neutral publisher port selected by `app.voice-evaluation.messaging.provider`. RabbitMQ uses dedicated durable main, retry, and dead-letter resources; consumers load evaluation data by `sessionId`, call the existing evaluation service outside database transactions, and use short persistence transactions for status, completion IDs, and dead-letter audit state.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP 4.1, RabbitMQ 4 Management, Spring Data JPA, PostgreSQL, Redis Stream fallback, JUnit 5, Mockito, AssertJ, Testcontainers RabbitMQ.

## Global Constraints

- Keep all existing voice-interview HTTP/WebSocket APIs and response payloads unchanged.
- Preserve `PENDING -> PROCESSING -> COMPLETED/FAILED` evaluation semantics.
- Migrate only voice evaluation; text-interview evaluation and knowledge-base vectorization remain on Redis Stream.
- Use dedicated `voice.evaluation.*` RabbitMQ resources inside the existing RabbitMQ instance.
- Use persistent messages, publisher confirms, publisher returns, and manual consumer acknowledgements.
- Retry after approximately 10, 30, and 60 seconds, then route to the final dead-letter queue.
- RabbitMQ messages contain identifiers only; never include audio, transcript, conversation, prompt, or evaluation content.
- Do not keep a database transaction open during AI calls or RabbitMQ publishing.
- Keep existing local learning comments and unrelated working-tree changes uncommitted and intact.
- Do not expose stored dead-letter payload JSON through administrative APIs.

---

## File Structure

**Create**

- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/VoiceEvaluationTaskPublisher.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationMessage.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProperties.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRetryPolicy.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConfig.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducer.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConsumer.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationDeadLetterConsumer.java`
- all audit entity, DTO, repository, service, and controller files under `modules/voiceinterview/deadletter/`
- focused tests matching each task below.

**Modify**

- `app/src/main/resources/application.yml`
- `app/src/test/resources/application-test.yml`
- `.env.example`
- `app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamProducer.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/model/VoiceInterviewEvaluationEntity.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewEvaluationRepository.java`
- `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java`
- `README.md`

---

### Task 1: Add voice-evaluation properties and dedicated topology

**Files:**
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationMessage.java`
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProperties.java`
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRetryPolicy.java`
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConfig.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitPropertiesTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRetryPolicyTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConfigTest.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/resources/application-test.yml`
- Modify: `.env.example`

**Interfaces:**
- Produces: `VoiceEvaluationMessage(UUID messageId, Long sessionId, int retryCount, OffsetDateTime createdAt, UUID originalMessageId)`.
- Produces: `VoiceEvaluationRetryPolicy.RetryDestination(String routingKey, Duration delay, boolean deadLetter)`.
- Produces: beans named `voiceEvaluationRabbitTemplate` and `voiceEvaluationRabbitListenerContainerFactory`.

- [ ] **Step 1: Write failing property and retry tests**

Assert property binding for provider, exchanges, queues, routing keys, and
`[10s, 30s, 60s]`. Assert:

```java
assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
assertThat(policy.destinationFor(3).deadLetter()).isTrue();
assertThatThrownBy(() -> policy.destinationFor(-1))
    .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run tests and verify missing-type failure**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitPropertiesTest" --tests "*VoiceEvaluationRetryPolicyTest"
```

Expected: compilation fails because the message, properties, and retry policy do
not exist.

- [ ] **Step 3: Implement the immutable message and properties**

Use prefix `app.voice-evaluation.messaging` and defaults:

```text
provider=rabbitmq
exchange=voice.evaluation.exchange
queue=voice.evaluation.queue
routingKey=voice.evaluation
deadExchange=voice.evaluation.dead.exchange
deadQueue=voice.evaluation.dead.queue
deadRoutingKey=voice.evaluation.dead
retryDelays=[10s,30s,60s]
```

Validate non-null IDs, positive `sessionId`, and non-negative retry count.

- [ ] **Step 4: Implement deterministic retry selection**

Retry routing keys are:

```text
voice.evaluation.retry.10s
voice.evaluation.retry.30s
voice.evaluation.retry.60s
```

Retry queue names are:

```text
voice.evaluation.queue.retry.10s
voice.evaluation.queue.retry.30s
voice.evaluation.queue.retry.60s
```

- [ ] **Step 5: Write the failing topology test**

Assert durable direct main/dead exchanges, durable main/dead queues, three
durable retry queues, exact `x-message-ttl` values, main exchange/routing key as
retry DLX destinations, `JacksonJsonMessageConverter`, mandatory
`RabbitTemplate`, and `AcknowledgeMode.MANUAL`.

- [ ] **Step 6: Implement topology configuration**

Declare two exchanges, five queues, all bindings, JSON conversion, the
mandatory template, and manual-ACK listener factory. Activate configuration
only when provider is `rabbitmq`.

- [ ] **Step 7: Add YAML and environment configuration**

Add all properties to `application.yml`; set provider to `redis-stream` and
listener auto-startup false in test configuration. Add
`APP_VOICE_EVALUATION_MESSAGING_PROVIDER=rabbitmq` to `.env.example`.

- [ ] **Step 8: Run focused tests**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitPropertiesTest" --tests "*VoiceEvaluationRetryPolicyTest" --tests "*VoiceEvaluationRabbitConfigTest"
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```powershell
git add .env.example app/src/main/resources/application.yml app/src/test/resources/application-test.yml app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit
git commit -m "feat: declare voice evaluation RabbitMQ topology"
```

---

### Task 2: Introduce the publisher port and confirmed RabbitMQ publishing

**Files:**
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/VoiceEvaluationTaskPublisher.java`
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamProducer.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducerTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducerContextTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceInterviewServiceMessagingTest.java`

**Interfaces:**
- Produces: `VoiceEvaluationTaskPublisher.PublishReceipt publish(Long sessionId)`.
- Redis and RabbitMQ publishers implement the same interface.

- [ ] **Step 1: Write failing publisher tests**

Mock `RabbitTemplate`. Verify `publish(42L)` sends a persistent
`VoiceEvaluationMessage` to `voice.evaluation.exchange` with routing key
`voice.evaluation`, waits for `CorrelationData.Confirm`, and returns the
generated message ID only after ACK. Verify NACK, timeout, and returned messages
throw `BusinessException`.

- [ ] **Step 2: Write the Spring constructor-selection regression test**

Register the producer, mocked `RabbitTemplate`, and properties in an
`AnnotationConfigApplicationContext`. Assert the producer bean is created. This
prevents the multiple-constructor bug already encountered in the resume
publisher.

- [ ] **Step 3: Write the service contract test**

Mock `VoiceEvaluationTaskPublisher`, invoke the existing operation that queues
evaluation, and verify:

```java
verify(publisher).publish(sessionId);
```

The session must remain `PENDING` immediately after publish.

- [ ] **Step 4: Run tests and verify missing types fail**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitProducerTest" --tests "*VoiceEvaluationRabbitProducerContextTest" --tests "*VoiceInterviewServiceMessagingTest"
```

- [ ] **Step 5: Implement the port and provider conditions**

Use:

```text
VoiceEvaluateStreamProducer + VoiceEvaluateStreamConsumer:
  havingValue=redis-stream

VoiceEvaluationRabbitProducer + Rabbit consumers:
  havingValue=rabbitmq
```

Change `VoiceInterviewService` to inject `VoiceEvaluationTaskPublisher`.

- [ ] **Step 6: Implement confirmed publishing**

Use a five-second bounded confirm timeout, `mandatory=true`, persistent delivery,
message ID, and correlation ID. On initial publish failure, use
`TransactionalExecutor.runRequiresNew` to mark the evaluation `FAILED` with a
500-character maximum error.

- [ ] **Step 7: Run focused tests**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitProducer*" --tests "*VoiceInterviewServiceMessagingTest"
```

Expected: all tests pass and Spring selects the production constructor.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/voiceinterview/messaging app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamProducer.java app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java app/src/test/java/interview/guide/modules/voiceinterview
git commit -m "refactor: decouple voice evaluation message transport"
```

---

### Task 3: Add evaluation completion idempotency

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/model/VoiceInterviewEvaluationEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewEvaluationRepository.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceEvaluationPersistenceIdempotencyTest.java`

**Interfaces:**
- Produces: `boolean isEvaluationMessageCompleted(String messageId)`.
- Produces: `CompletionResult generateEvaluation(Long sessionId, String messageId)` where result is `CREATED` or `ALREADY_COMPLETED`.

- [ ] **Step 1: Write failing idempotency tests**

Cover first completion, repeated same message ID, unique-constraint race,
missing session, and atomic result/status completion. Mock the external LLM call
so only persistence behavior is exercised.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationPersistenceIdempotencyTest"
```

- [ ] **Step 3: Add nullable unique message ID**

Add:

```java
@Column(name = "evaluation_message_id", unique = true, length = 36)
private String evaluationMessageId;
```

Add `existsByEvaluationMessageId(String messageId)` to the repository.

- [ ] **Step 4: Split AI generation from short persistence**

Keep the AI call outside a transaction. Add a short transactional completion
method that saves the result, message ID, and `COMPLETED` status atomically.
Duplicate IDs return `ALREADY_COMPLETED`.

- [ ] **Step 5: Run focused tests**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationPersistenceIdempotencyTest" --tests "*VoiceInterviewEvaluationServiceTest"
```

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/voiceinterview/model/VoiceInterviewEvaluationEntity.java app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewEvaluationRepository.java app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceEvaluationPersistenceIdempotencyTest.java
git commit -m "feat: make voice evaluation completion idempotent"
```

---

### Task 4: Implement manual-ACK consumption and delayed retries

**Files:**
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConsumer.java`
- Modify: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducer.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConsumerTest.java`

**Interfaces:**
- Consumes: `VoiceEvaluationMessage`, `VoiceInterviewService`,
  `VoiceInterviewEvaluationService`, producer, and retry policy.
- Produces: ACK, retry publish, or final dead publish.

- [ ] **Step 1: Write failing consumer branch tests**

Verify:

- missing session: ACK;
- completed session: ACK;
- duplicate message ID: ACK;
- success: mark processing, generate/complete, ACK;
- failures at retry counts 0/1/2: confirmed retry publish then ACK;
- retry publish failure: `basicNack(tag, false, true)`;
- failure at count 3: mark failed, confirmed dead publish, ACK;
- dead publish failure: NACK/requeue.

- [ ] **Step 2: Run and verify missing consumer failure**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitConsumerTest"
```

- [ ] **Step 3: Implement retry and dead publish helpers**

Add:

```java
PublishReceipt publishRetry(
    VoiceEvaluationMessage failed,
    RetryDestination destination
);

PublishReceipt publishDead(
    VoiceEvaluationMessage failed,
    String failureReason
);
```

Retries increment `retryCount`, all republished messages receive a new
`messageId`, and `originalMessageId` is preserved. Truncate the failure header
to 500 characters.

- [ ] **Step 4: Implement the listener**

Use the dedicated queue and container factory. Read the delivery tag from
`MessageProperties`; ACK only after successful persistence or successful
rerouting.

- [ ] **Step 5: Run focused tests**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitConsumerTest" --tests "*VoiceEvaluationRabbitProducerTest"
```

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitConsumer.java app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitProducer.java app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit
git commit -m "feat: consume voice evaluation through RabbitMQ"
```

---

### Task 5: Persist dead letters and expose replay APIs

**Files:**
- Create all files under `app/src/main/java/interview/guide/modules/voiceinterview/deadletter/`
- Create: `app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationDeadLetterConsumer.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/deadletter/VoiceEvaluationDeadLetterServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/deadletter/VoiceEvaluationDeadLetterControllerTest.java`
- Test: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationDeadLetterConsumerTest.java`

**Interfaces:**
- Produces:
  - `GET /api/admin/voice-evaluation/dead-letters`
  - `GET /api/admin/voice-evaluation/dead-letters/{id}`
  - `POST /api/admin/voice-evaluation/dead-letters/{id}/replay`

- [ ] **Step 1: Write failing service tests**

Verify first insert, duplicate `originalMessageId`, missing session, completed
session resolution, atomic `PENDING -> REPLAYING` claim, successful confirmed
replay, and rollback to `PENDING` after publish failure.

- [ ] **Step 2: Write failing controller tests**

Use `MockMvc` to assert `Result<T>` envelopes, deterministic pagination,
optional status filtering, invalid page/size validation, and absence of
`payloadJson` from responses.

- [ ] **Step 3: Write failing dead-consumer tests**

Verify database success then ACK, database failure then NACK/requeue, and
duplicate audit message ACK.

- [ ] **Step 4: Implement audit persistence**

Use a unique `original_message_id`, `TEXT` payload/reason columns, enum status,
and an atomic conditional update for replay claiming.

- [ ] **Step 5: Implement replay service**

Use `TransactionalExecutor.callRequiresNew` for the atomic claim. Publish
outside transactions. Restore `PENDING` on missing session or publish failure;
mark completed sessions `RESOLVED`.

- [ ] **Step 6: Implement DTO, page response, controller, and listener**

Apply rate limiting to replay. The DTO exposes only:

```text
id, originalMessageId, sessionId, retryCount, failureReason,
status, failedAt, replayedAt, replayMessageId
```

- [ ] **Step 7: Run focused tests**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationDeadLetter*"
```

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/interview/guide/modules/voiceinterview/deadletter app/src/main/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationDeadLetterConsumer.java app/src/test/java/interview/guide/modules/voiceinterview
git commit -m "feat: add voice evaluation dead-letter replay"
```

---

### Task 6: Verify real RabbitMQ routing

**Files:**
- Create: `app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitIntegrationTest.java`

**Interfaces:**
- Uses the existing Testcontainers RabbitMQ dependency.

- [ ] **Step 1: Write the container-backed test**

Use unique test queue names and 100/200/300-ms retry delays. Verify:

- main publish reaches the main queue;
- manual ACK removes a delivery;
- NACK/requeue redelivers;
- each TTL queue returns its message to the main queue;
- final dead publish reaches the dead queue;
- mandatory unroutable publish is reported as failure.

- [ ] **Step 2: Run and inspect initial failure**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitIntegrationTest"
```

Expected before final wiring: topology or dynamic-property assertion failure.

- [ ] **Step 3: Add dynamic RabbitMQ properties and bounded polling**

Use `@DynamicPropertySource` for host, AMQP port, credentials, provider, unique
resource names, and short retry delays. Poll conditions instead of fixed long
sleeps.

- [ ] **Step 4: Run integration test**

```powershell
.\gradlew.bat test --tests "*VoiceEvaluationRabbitIntegrationTest"
```

Expected: all real-broker routing assertions pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/test/java/interview/guide/modules/voiceinterview/messaging/rabbit/VoiceEvaluationRabbitIntegrationTest.java
git commit -m "test: verify voice evaluation RabbitMQ delivery"
```

---

### Task 7: End-to-end verification and documentation

**Files:**
- Modify: `README.md`

**Interfaces:**
- Produces reproducible startup, rollback, monitoring, and replay instructions.

- [ ] **Step 1: Start the mixed development dependencies**

```powershell
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml up -d
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml ps
```

Expected: PostgreSQL, Redis, RustFS, and RabbitMQ are healthy.

- [ ] **Step 2: Run full backend tests**

```powershell
.\gradlew.bat test --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Start RabbitMQ mode**

Set `APP_VOICE_EVALUATION_MESSAGING_PROVIDER=rabbitmq`, start with
`.\gradlew.bat bootRun`, and verify:

```text
GET /api/resumes/health -> 200
GET /v3/api-docs -> 200
```

- [ ] **Step 4: Inspect RabbitMQ resources**

Use Management API or UI to verify two exchanges, five durable queues, one main
consumer, one dead consumer, and no unintended voice Redis Stream consumer.

- [ ] **Step 5: Verify a successful evaluation**

Complete a disposable voice interview or invoke its existing evaluation
endpoint. Verify:

```text
PENDING -> PROCESSING -> COMPLETED
exactly one evaluation result
voice.evaluation.queue returns to zero messages
```

- [ ] **Step 6: Verify retry, dead letter, and replay**

In a disposable local run, make the evaluation provider fail. Observe retry
movement near 10/30/60 seconds and a `PENDING` audit row. Restore the provider,
call the replay endpoint, and verify `REPLAYING -> REPLAYED -> completed`.

- [ ] **Step 7: Verify Redis rollback**

Set provider to `redis-stream`, restart, and verify the original Stream consumer
starts while Rabbit voice listeners do not.

- [ ] **Step 8: Document operations**

Add provider variables, queue names, Management URL, retry schedule, dead-letter
endpoints, and rollback instructions to README. State explicitly that text
evaluation and knowledge-base vectorization remain on Redis Stream.

- [ ] **Step 9: Final evidence**

```powershell
git diff --check
.\gradlew.bat test --console=plain
docker compose -f docker-compose.dev.yml -f docker-compose.local.yml config --quiet
git status --short
```

Expected: no whitespace errors, all tests pass, Compose validates, and only
intentional files plus the user's pre-existing local comments are modified.

- [ ] **Step 10: Commit**

```powershell
git add README.md
git commit -m "docs: explain RabbitMQ voice evaluation workflow"
```
