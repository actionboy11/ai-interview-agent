# Voice Evaluation RabbitMQ Migration Design

## 1. Goal

Migrate only asynchronous voice-interview evaluation from Redis Stream to
RabbitMQ while preserving the existing API contract and evaluation status flow.

The migration must provide:

- manual acknowledgement;
- retries after approximately 10, 30, and 60 seconds;
- final dead-letter persistence in PostgreSQL;
- administrative query, detail, and replay endpoints;
- publisher confirms and returned-message detection;
- idempotent evaluation completion;
- a Redis Stream rollback switch.

Text-interview evaluation and knowledge-base vectorization remain on Redis
Stream.

## 2. Scope

### Included

- `VoiceEvaluateStreamProducer` and `VoiceEvaluateStreamConsumer`;
- calls that currently publish voice-evaluation tasks;
- voice-evaluation status persistence;
- RabbitMQ topology and message contract;
- dead-letter audit persistence and replay;
- Docker/local configuration, tests, and operational documentation.

### Excluded

- resume-analysis RabbitMQ behavior;
- text-interview evaluation;
- knowledge-base vectorization;
- audio upload, ASR, TTS, and WebSocket flows;
- changes to existing frontend endpoints or response bodies.

## 3. Architecture Decision

Voice evaluation receives a dedicated RabbitMQ topology inside the existing
RabbitMQ instance. It does not share exchanges or queues with resume analysis.

Resources:

```text
voice.evaluation.exchange
  -> voice.evaluation.queue
  -> voice.evaluation.queue.retry.10s
  -> voice.evaluation.queue.retry.30s
  -> voice.evaluation.queue.retry.60s

voice.evaluation.dead.exchange
  -> voice.evaluation.dead.queue
```

Retry queues use message TTL plus dead-letter exchange routing back to the main
queue. No RabbitMQ delayed-message plugin is required.

All exchanges and queues are durable. Messages are persistent.

## 4. Provider Switch

Configuration property:

```yaml
app:
  voice-evaluation:
    messaging:
      provider: rabbitmq
```

Supported values:

- `rabbitmq`: RabbitMQ producer and consumers are active;
- `redis-stream`: the existing Stream producer and consumer are active.

RabbitMQ is the default after migration. The two implementations are selected
with mutually exclusive `@ConditionalOnProperty` conditions.

## 5. Message Contract

The RabbitMQ message contains only identifiers and delivery metadata:

```java
VoiceEvaluationMessage(
    UUID messageId,
    Long sessionId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
)
```

It must not contain audio, transcript text, conversation messages, prompts, or
evaluation results. The consumer loads all required data using `sessionId`.

Every retry gets a new `messageId` while preserving `originalMessageId`.

## 6. Publishing Flow

Introduce a transport-neutral `VoiceEvaluationTaskPublisher`:

```java
PublishReceipt publish(Long sessionId)
```

`VoiceInterviewService` depends on this interface instead of
`VoiceEvaluateStreamProducer`.

The RabbitMQ publisher:

1. creates a persistent message;
2. publishes with `mandatory=true`;
3. waits for a correlated broker confirm with a bounded timeout;
4. treats NACK, timeout, and returned messages as failures;
5. records a truncated failure reason and marks the session evaluation failed
   if the initial publish cannot be confirmed.

## 7. Consumer and Status Flow

The status contract remains:

```text
PENDING -> PROCESSING -> COMPLETED
                        -> retry
                        -> FAILED
```

The consumer uses manual acknowledgement:

- missing session: ACK;
- evaluation already completed: ACK;
- message already completed: ACK;
- successful evaluation and persistence: ACK;
- retryable failure at counts 0, 1, or 2:
  publish to the selected retry queue, then ACK;
- retry publish failure: NACK with requeue;
- failure at count 3:
  mark the evaluation failed, publish to the final dead exchange, then ACK;
- final dead publish failure: NACK with requeue.

The AI evaluation call is not executed inside a database transaction. Status
and result persistence use short transactions.

## 8. Idempotency

Voice evaluation completion is associated with a unique RabbitMQ message ID.
The persistence layer exposes:

```text
isEvaluationMessageCompleted(messageId)
completeEvaluation(sessionId, messageId, result)
```

The database enforces uniqueness for the completion message ID. Duplicate
delivery of the same message acknowledges without generating or saving another
evaluation.

Existing evaluation rows remain valid because the new message-ID column is
nullable for historical data.

## 9. Retry and Dead-Letter Handling

Retry mapping is deterministic:

| Current retry count | Destination |
|---:|---|
| 0 | 10-second retry queue |
| 1 | 30-second retry queue |
| 2 | 60-second retry queue |
| 3 or greater | final dead-letter exchange |

The final dead queue has a dedicated consumer. It persists an audit record only
after receiving the message and ACKs only after the database transaction
commits.

Audit fields:

```text
id
originalMessageId
sessionId
retryCount
failureReason
payloadJson
status
failedAt
replayedAt
replayMessageId
```

Statuses:

```text
PENDING, REPLAYING, REPLAYED, RESOLVED
```

`originalMessageId` is unique, making dead-letter persistence idempotent.

## 10. Administrative API

Endpoints:

```http
GET  /api/admin/voice-evaluation/dead-letters
GET  /api/admin/voice-evaluation/dead-letters/{id}
POST /api/admin/voice-evaluation/dead-letters/{id}/replay
```

The list endpoint supports deterministic pagination and optional status
filtering. API responses use DTOs and never expose the stored `payloadJson`.

Replay behavior:

1. atomically claim `PENDING -> REPLAYING`;
2. if the session is missing, return the standard not-found error and restore
   the audit record to `PENDING`;
3. if evaluation is already complete, mark the record `RESOLVED`;
4. otherwise reset evaluation status to `PENDING`;
5. publish a new confirmed message outside the database transaction;
6. after confirm, record the new message ID and mark `REPLAYED`;
7. on publish failure, restore `PENDING`.

Replay is protected with the project's existing rate-limit annotation.

## 11. Testing

Focused tests cover:

- property binding and provider selection;
- topology durability, bindings, TTL and DLX arguments;
- publisher ACK, NACK, timeout, return, and persistent delivery;
- manual ACK/NACK behavior;
- retry destination selection;
- missing and already-completed sessions;
- idempotent completion;
- dead-letter audit idempotency;
- replay state transitions and publish failure;
- controller pagination and safe DTO output;
- Redis Stream rollback mode;
- real RabbitMQ message routing using Testcontainers when Docker is available.

Final verification includes:

- full backend test suite;
- RabbitMQ-mode application startup;
- health and OpenAPI endpoints;
- Management API inspection of exchanges, queues, and consumers;
- one successful voice-evaluation task;
- one controlled retry/dead-letter/replay cycle where suitable test credentials
  are available.

## 12. Operational Notes

- RabbitMQ runs in the existing container and Management UI.
- Queue buildup is observable independently from resume analysis.
- Resume analysis continues using its own topology.
- Text evaluation and vectorization remain on Redis Stream.
- Rollback requires changing only the voice-evaluation provider property and
  restarting the backend.
