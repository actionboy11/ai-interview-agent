# 文字面试评估 RabbitMQ 迁移设计

## 1. 目标与范围

将文字面试评估任务从 Redis Stream 迁移到 RabbitMQ，同时保留 Redis
Stream 回滚能力。迁移后具备发布确认、手动 ACK、延迟重试、消费幂等、
PostgreSQL 死信审计和管理端重放能力。

本次只改造文字面试评估。简历分析和语音面试评估继续使用现有 RabbitMQ
实现，知识库向量化继续使用 Redis Stream。现有文字面试 HTTP API、报告结构
和前端行为保持不变。

同时删除 `VoiceInterviewController` 中未使用的
`VoiceEvaluateStreamProducer` 注入，避免 RabbitMQ 模式下该 Redis Bean 被条件
禁用后造成应用启动失败。

## 2. 方案选择

采用独立的文字评估 RabbitMQ 链路，不把简历、语音和文字评估强行抽象成统一
框架，也不让文字与语音共用队列。

这样可以保持业务故障隔离、独立监控和独立扩缩容，并降低对现有稳定链路的
回归风险。实现风格与语音评估保持一致，但每个业务模块拥有自己的配置、消息、
消费者和死信审计模型。

## 3. 组件边界

### 3.1 发布端口

新增传输无关的 `InterviewEvaluationTaskPublisher`。业务 Service 只依赖该
端口，不直接依赖 Redis 或 RabbitMQ。

提供两个实现：

- RabbitMQ 实现：默认启用，使用持久化消息和发布确认。
- Redis Stream 实现：通过配置启用，保留现有回滚链路。

配置开关：

```text
app.interview-evaluation.messaging.provider
APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER
```

允许值为 `rabbitmq` 和 `redis-stream`，默认 `rabbitmq`。

### 3.2 RabbitMQ 拓扑

使用同一 RabbitMQ 实例中的独立资源：

```text
interview.evaluation.exchange
interview.evaluation.queue
interview.evaluation

interview.evaluation.queue.retry.10s
interview.evaluation.queue.retry.30s
interview.evaluation.queue.retry.60s

interview.evaluation.dead.exchange
interview.evaluation.dead.queue
interview.evaluation.dead
```

主交换机和死信交换机为 durable direct exchange。所有队列均为 durable。
重试队列通过 TTL 到期后死信回主交换机，不在消费者线程中等待。

### 3.3 消息契约

RabbitMQ 消息只包含：

```text
messageId
sessionId
retryCount
createdAt
originalMessageId
```

消息不得包含题目、回答、简历文本、提示词或评估报告。消费者根据
`sessionId` 从 PostgreSQL 加载最新业务数据。

`messageId` 标识一次投递，重试和重放会生成新值；
`originalMessageId` 在整条重试及死信链路中保持不变。

## 4. 数据流

### 4.1 正常链路

1. 文字面试结束或重新触发评估。
2. Service 将会话评估状态保存为 `PENDING`。
3. 事务提交后，通过 `InterviewEvaluationTaskPublisher` 发布任务。
4. RabbitMQ 消费者读取任务并校验会话是否存在。
5. 已删除会话直接 ACK；已完成或消息已处理则直接 ACK。
6. 使用短事务将状态更新为 `PROCESSING`。
7. 在事务外加载题目、答案和模型配置，并调用 LLM 生成报告。
8. 使用短事务原子保存报告、完成消息 ID 和 `COMPLETED` 状态。
9. 数据持久化成功后手动 ACK。

LLM 调用和 RabbitMQ 发布均不得位于数据库事务中。

### 4.2 发布失败

初始发布必须等待 publisher confirm，并处理 returned message。NACK、超时、
无法路由或发送异常都视为发布失败。

发布失败后使用独立短事务将会话评估状态更新为 `FAILED`，错误信息最多保存
500 个字符。

### 4.3 消费失败与重试

消费失败后根据当前 `retryCount` 路由到：

1. 10 秒重试队列；
2. 30 秒重试队列；
3. 60 秒重试队列；
4. 最终死信队列。

只有重试消息或死信消息得到发布确认后，才 ACK 原消息。重新发布失败时对原
消息执行 NACK 并 requeue，避免任务丢失。

最终失败时将会话状态更新为 `FAILED`，并在死信消息头中携带截断后的失败原因。

## 5. 幂等与事务

文字面试报告记录增加可空且唯一的评估消息 ID。消费者开始昂贵的 LLM 调用前
检查消息是否已完成，保存结果时再次依靠唯一约束防止并发重复写入。

报告、完成消息 ID 和会话 `COMPLETED` 状态必须在同一个短事务中保存。
不得通过同类内部调用 `@Transactional` 方法实现事务代理；使用独立持久化
Service 或现有 `TransactionalExecutor`。

重复消息和唯一约束竞争均按已完成处理并 ACK，不重复生成最终报告。

## 6. 死信审计与重放

新增 PostgreSQL 文字评估死信表，至少记录：

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

`originalMessageId` 唯一，重复死信不会产生重复审计记录。管理 API 不返回
`payloadJson`。

提供：

```text
GET  /api/admin/interview-evaluation/dead-letters
GET  /api/admin/interview-evaluation/dead-letters/{id}
POST /api/admin/interview-evaluation/dead-letters/{id}/replay
```

重放使用条件更新原子地把 `PENDING` 改为 `REPLAYING`，防止并发重复重放。
发布成功后改为 `REPLAYED`；发布失败或会话不存在时恢复 `PENDING`；会话已经
完成时改为 `RESOLVED`。重放接口使用现有可重复 `@RateLimit`。

## 7. 配置与回滚

RabbitMQ 为默认提供方。切换到：

```text
APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER=redis-stream
```

并重启后端后，原 Redis Stream 生产者和消费者恢复启用，文字评估 RabbitMQ
生产者和监听器停止。两个提供方不能同时消费同一类文字评估任务。

## 8. 测试与验收

测试覆盖：

- 配置绑定、拓扑声明和 10/30/60 秒重试策略；
- 发布 ACK、NACK、超时和无法路由；
- Spring 构造器选择及 provider 条件；
- 会话不存在、已经完成和重复消息；
- 正常消费、三次延迟重试和最终死信；
- 重试或死信重新发布失败时 NACK/requeue；
- 完成持久化的幂等和原子性；
- 死信记录幂等、分页、详情、重放及并发领取；
- 真实 RabbitMQ 容器中的主路由、TTL 回流和死信路由；
- RabbitMQ 默认模式应用启动；
- Redis Stream 回滚模式应用启动；
- 完整后端测试与 Compose 配置校验。

验收成功标准：

- 文字面试结束后状态按 `PENDING -> PROCESSING -> COMPLETED` 变化；
- 同一任务最多产生一份最终报告；
- 失败任务按约 10、30、60 秒重试并最终进入死信审计；
- 管理端可以安全重放待处理死信；
- 简历、语音和知识库异步链路不受影响；
- 用户现有未提交学习注释保持不变。
