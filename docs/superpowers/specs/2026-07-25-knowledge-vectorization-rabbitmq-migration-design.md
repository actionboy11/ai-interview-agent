# 知识库向量化 RabbitMQ 迁移设计

## 1. 目标与范围

将知识库向量化任务从 Redis Stream 迁移到 RabbitMQ，同时保留 Redis Stream
回滚能力。迁移后具备发布确认、手动 ACK、延迟重试、幂等完成、PostgreSQL
死信审计和管理端重放能力。

本次只改造知识库向量化。简历分析、语音面试评估和文字面试评估继续使用已经
完成的 RabbitMQ 链路。现有知识库 HTTP API、响应结构和前端行为保持不变。

## 2. 核心方案

RabbitMQ 消息只携带知识库和消息标识，不携带解析后的文档全文。消费者收到
任务后，根据 `knowledgeBaseId` 加载 PostgreSQL 元数据，使用 `storageKey`
从 RustFS 下载原文件，再调用现有知识库解析服务提取文本。

选择该方案的原因：

- 避免将最大 50 MB 的文档内容写入 RabbitMQ；
- 避免在 PostgreSQL 增加大体积全文字段；
- RustFS 中的原始文件是可恢复的事实来源；
- 消息契约稳定，不受文档格式和解析实现变化影响。

上传阶段仍保留一次同步解析，用于提前拒绝空文档并返回 `contentLength`。
消费者会重新下载并解析，因此会产生一次额外的对象存储读取和解析成本。

## 3. 组件边界

### 3.1 发布端口

新增传输无关的 `KnowledgeVectorizationTaskPublisher`。上传和重新向量化 Service
只依赖该端口，不直接依赖 Redis 或 RabbitMQ。

统一端口为 `publish(Long knowledgeBaseId)`。RabbitMQ 实现直接发送 ID；Redis
Stream 回滚实现根据 ID 查询 `storageKey`，从 RustFS 下载并解析全文后再构造
旧 Stream 消息。这样业务 Service 和端口契约都不会携带大文本。

提供两个互斥实现：

- RabbitMQ 实现：默认启用，使用持久化消息和发布确认；
- Redis Stream 实现：通过配置启用，保留回滚能力。

配置：

```text
app.knowledge-vectorization.messaging.provider
APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER
```

允许值为 `rabbitmq` 和 `redis-stream`，默认 `rabbitmq`。

### 3.2 RabbitMQ 拓扑

使用同一 RabbitMQ 实例中的独立资源：

```text
knowledge.vectorization.exchange
knowledge.vectorization.queue
knowledge.vectorization

knowledge.vectorization.queue.retry.10s
knowledge.vectorization.queue.retry.30s
knowledge.vectorization.queue.retry.60s

knowledge.vectorization.dead.exchange
knowledge.vectorization.dead.queue
knowledge.vectorization.dead
```

主交换机和死信交换机为 durable direct exchange。所有队列均为 durable。
重试队列通过 TTL 到期后死信回主交换机，不在消费线程中等待。

### 3.3 消息契约

消息只包含：

```text
messageId
knowledgeBaseId
retryCount
createdAt
originalMessageId
```

`messageId` 标识一次投递；重试和重放生成新值。
`originalMessageId` 在整条重试和死信链路中保持不变。

消息不得包含文档二进制、解析文本、文本分块、Embedding、提示词或向量数据。

## 4. 正常处理链路

1. 上传接口校验文件、计算哈希并同步解析一次，拒绝空内容。
2. 原始文件上传至 RustFS。
3. PostgreSQL 保存知识库元数据，向量化状态为 `PENDING`。
4. 数据库提交后通过 `KnowledgeVectorizationTaskPublisher` 发布任务。
5. 消费者根据 `knowledgeBaseId` 查询知识库；实体已删除则 ACK 丢弃。
6. 已完成或消息 ID 已处理则 ACK。
7. 使用短事务把状态更新为 `PROCESSING`。
8. 根据 `storageKey` 和原文件名从 RustFS 下载并解析文本。
9. 空文本按处理失败进入重试流程。
10. 在事务外调用现有 `KnowledgeBaseVectorService` 分块并写入向量库。
11. 使用短事务原子保存 `chunkCount`、完成消息 ID、清空错误并设置
    `COMPLETED`。
12. 持久化成功后手动 ACK。

RustFS 下载、文档解析、Embedding API 和 RabbitMQ 发布均不得位于数据库事务
中。

重新向量化接口不再把全文传给消息队列。它校验知识库和 RustFS 标识、将状态
更新为 `PENDING`，然后发布只包含 `knowledgeBaseId` 的任务。

## 5. 发布失败、重试与死信

初始发布必须等待 publisher confirm，并处理 returned message。NACK、超时、
无法路由或发送异常均视为发布失败。

初始发布失败后使用独立短事务将向量化状态更新为 `FAILED`，错误信息最多保存
500 个字符。

消费失败后根据当前 `retryCount` 依次路由到：

1. 10 秒重试队列；
2. 30 秒重试队列；
3. 60 秒重试队列；
4. 最终死信队列。

只有重试消息或死信消息得到发布确认后才 ACK 原消息。重新发布失败时对原消息
执行 NACK 并 requeue。

最终失败时将知识库向量化状态设置为 `FAILED`，并在死信消息头中携带截断后的
失败原因。

## 6. 幂等与向量一致性

在 `KnowledgeBaseEntity` 增加可空且唯一的 `vectorizationMessageId`。
消费者在下载文件和调用 Embedding API 前检查任务是否已完成，完成持久化时再
依靠唯一约束处理并发重复投递。

现有 `KnowledgeBaseVectorService` 的临时 Job metadata 机制继续保留：

- 新向量先使用 `pending:{knowledgeBaseId}:{jobId}` 临时标识；
- 所有批次成功后再激活为正式 `kb_id`；
- 任意失败时清理该 Job 的临时向量；
- 不让部分成功的向量污染正式检索结果。

向量激活成功后，`chunkCount`、`vectorizationMessageId` 和 `COMPLETED` 状态
必须通过短事务原子保存。重复消息不再次调用 Embedding API。

## 7. RustFS 与异常分类

- 知识库实体已删除：ACK 丢弃，不进入死信；
- `storageKey` 缺失：视为任务失败，进入重试；
- RustFS 文件不存在或下载失败：进入重试，最终进入死信；
- 文档解析为空或解析异常：进入重试，最终进入死信；
- Embedding 或向量库写入失败：由现有临时 Job 清理机制回收后进入重试；
- 状态更新失败：不 ACK；根据是否完成重新发布决定 NACK/requeue。

管理端重放时：

- 知识库已经删除：死信标记为 `RESOLVED`；
- 知识库已经完成：死信标记为 `RESOLVED`；
- 其他情况：状态重置为 `PENDING` 并确认发布新任务；
- 发布失败：死信恢复为 `PENDING`。

## 8. 死信审计与管理 API

新增 PostgreSQL 表 `knowledge_vectorization_dead_letters`，记录：

```text
id
originalMessageId
knowledgeBaseId
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
GET  /api/admin/knowledge-vectorization/dead-letters
GET  /api/admin/knowledge-vectorization/dead-letters/{id}
POST /api/admin/knowledge-vectorization/dead-letters/{id}/replay
```

重放通过条件更新原子领取 `PENDING` 记录，防止并发重复重放。重放接口使用
现有 GLOBAL 和 IP 两个维度的 `@RateLimit`。

## 9. Redis Stream 回滚

设置：

```text
APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER=redis-stream
```

并重启后端后，原 Redis Stream 生产者和消费者恢复启用，知识库向量化
RabbitMQ 生产者和监听器停止。两个提供方不能同时消费同一类任务。

Redis Stream 回滚实现继续携带解析文本，以保持旧链路兼容；该适配器在发布前
自行根据 `knowledgeBaseId` 从 RustFS 下载并解析。RabbitMQ 契约始终只携带
标识。业务 Service 通过统一发布端口调用，不感知两种载荷差异。

## 10. 测试与验收

测试覆盖：

- 配置绑定、独立拓扑和 10/30/60 秒重试策略；
- 发布 ACK、NACK、超时和无法路由；
- provider 条件互斥和 Spring 构造器选择；
- 上传与重新向量化通过统一发布端口发送任务；
- 实体删除、已完成和重复消息直接 ACK；
- RustFS 下载、重新解析、空文本和文件缺失；
- 正常向量化、临时 Job 激活与失败清理；
- 三次延迟重试、最终死信及重新发布失败 NACK/requeue；
- 完成消息 ID、chunk 数和状态的原子持久化；
- 死信记录幂等、分页、详情和重放；
- 真实 RabbitMQ 主路由、TTL 回流和死信路由；
- RabbitMQ 默认模式与 Redis Stream 回滚模式真实启动；
- 完整后端测试和 Compose 配置校验。

验收成功标准：

- 上传后状态按 `PENDING -> PROCESSING -> COMPLETED` 变化；
- RabbitMQ 消息不包含文档内容；
- 同一完成消息不会重复调用 Embedding API；
- 部分失败的临时向量不会出现在正式检索中；
- 失败任务按约 10、30、60 秒重试并最终进入死信审计；
- 管理端可以安全重放待处理死信；
- 简历、语音和文字评估 RabbitMQ 链路不受影响；
- 用户现有未提交学习注释保持不变。
