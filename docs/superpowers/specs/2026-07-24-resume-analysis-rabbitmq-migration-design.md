# 简历分析 RabbitMQ 迁移设计

## 1. 背景与目标

InterviewGuide 当前使用 Redis Stream 承载简历分析、知识库向量化、文字面试评估和语音面试评估四类异步任务。Redis Stream 减少了基础设施数量，但项目需要自行维护消费线程、Consumer Group、ACK、Pending 回收、失败重试和状态补偿。

本次改造只迁移“简历分析”链路到 RabbitMQ，其他三类异步任务继续使用 Redis Stream。Redis 继续承担缓存、限流和会话状态，不从系统中移除。

改造目标：

- 保持前端、Controller、API 响应结构和任务状态机不变。
- 使用 RabbitMQ 原生确认、死信和队列机制承载简历分析。
- 实现 10 秒、30 秒、60 秒三级延迟重试。
- 最终失败后保留可查询、可审计的死信记录。
- 提供 Swagger 可调用的死信查询和手动重放接口。
- 防止重复投递生成重复分析结果。
- 不影响仍在使用 Redis Stream 的其他业务。

## 2. 范围

### 2.1 本次包含

- RabbitMQ Docker 开发环境和 Spring Boot 配置。
- 简历分析 RabbitMQ Producer、Consumer、重试队列和死信队列。
- Publisher Confirm、不可路由消息返回和手动 ACK。
- 简历分析幂等保护。
- PostgreSQL 死信记录表。
- 死信查询、详情和手动重放接口。
- 单元测试、Repository 测试、RabbitMQ 集成测试和端到端验证。

### 2.2 本次不包含

- 知识库向量化迁移。
- 文字面试评估迁移。
- 语音面试评估迁移。
- 删除 Redis Stream 公共模板。
- Transactional Outbox。
- RabbitMQ 集群、高可用和生产环境监控平台。
- 前端死信管理页面。

## 3. 总体架构

简历上传接口保持 `POST /api/resumes/upload` 不变。`ResumeUploadService` 保存简历后调用 RabbitMQ Producer，不再调用简历分析 Redis Stream Producer。

```text
ResumeUploadService
  -> ResumeAnalysisRabbitProducer
  -> resume.analysis.exchange
  -> resume.analysis.queue
  -> ResumeAnalysisRabbitConsumer
  -> ResumeGradingService
  -> ResumePersistenceService
```

失败路径：

```text
首次失败  -> resume.analysis.retry.10s.queue
第二次失败 -> resume.analysis.retry.30s.queue
第三次失败 -> resume.analysis.retry.60s.queue
再次失败  -> resume.analysis.dead.exchange
          -> resume.analysis.dead.queue
          -> ResumeAnalysisDeadLetterConsumer
          -> resume_analysis_dead_letters
```

重试队列通过消息 TTL 和 Dead Letter Exchange 将到期消息重新路由到主 Exchange，不依赖 RabbitMQ Delayed Message 插件。

## 4. RabbitMQ 拓扑

### 4.1 主链路

- Exchange：`resume.analysis.exchange`
- Exchange 类型：Direct
- 主 Routing Key：`resume.analysis`
- 主队列：`resume.analysis.queue`
- 主队列持久化：是

### 4.2 重试链路

三个持久化重试队列：

- `resume.analysis.retry.10s.queue`，TTL 10,000 ms
- `resume.analysis.retry.30s.queue`，TTL 30,000 ms
- `resume.analysis.retry.60s.queue`，TTL 60,000 ms

每个重试队列配置：

- `x-dead-letter-exchange=resume.analysis.exchange`
- `x-dead-letter-routing-key=resume.analysis`

Consumer 处理失败后，根据当前 `retryCount` 将新消息发布到对应重试 Routing Key。Broker Confirm 成功后 ACK 原消息。

### 4.3 最终死信链路

- Exchange：`resume.analysis.dead.exchange`
- Exchange 类型：Direct
- Routing Key：`resume.analysis.dead`
- 队列：`resume.analysis.dead.queue`
- 队列持久化：是

最终失败消息由 `ResumeAnalysisDeadLetterConsumer` 消费。该 Consumer 在数据库事务中写入死信记录，事务提交成功后 ACK；数据库写入失败时不 ACK，由 RabbitMQ 重新投递。

## 5. 消息契约

消息使用 JSON：

```json
{
  "messageId": "c81df81e-74ab-45ba-96de-a5c5db698b37",
  "resumeId": 123,
  "retryCount": 0,
  "createdAt": "2026-07-24T12:00:00+08:00",
  "originalMessageId": null
}
```

字段含义：

- `messageId`：当前投递的唯一标识。
- `resumeId`：简历业务主键和业务幂等键。
- `retryCount`：已经完成的失败次数，初始为 0。
- `createdAt`：当前消息生成时间。
- `originalMessageId`：手动重放时记录原始死信消息标识，首次投递为空。

消息不携带简历全文。Consumer 根据 `resumeId` 从 PostgreSQL 读取文本，避免敏感简历内容在 RabbitMQ 中重复存储，并降低消息大小。

## 6. 正常消费与事务边界

`ResumeAnalysisRabbitConsumer` 使用手动 ACK：

1. 解析并校验消息。
2. 按 `resumeId` 查询简历。
3. 简历不存在时记录警告并 ACK。
4. 状态已经是 `COMPLETED` 时视为幂等命中并 ACK。
5. 在短事务中将状态改为 `PROCESSING`。
6. 在数据库事务之外调用 `ResumeGradingService`。
7. 在同一事务中保存分析结果并将状态改为 `COMPLETED`。
8. 事务提交成功后 ACK。

模型调用不得放在数据库事务内，避免外部调用长时间占用连接或数据库锁。

## 7. 重试与最终失败

失败次数与目标队列：

| 当前 retryCount | 本次失败后的动作 | 新 retryCount |
|---:|---|---:|
| 0 | 发布到 10 秒队列 | 1 |
| 1 | 发布到 30 秒队列 | 2 |
| 2 | 发布到 60 秒队列 | 3 |
| 3 | 发布到最终死信 Exchange | 3 |

失败处理要求：

- 新消息发布获得 Broker Confirm 后，才 ACK 原消息。
- 发布失败或不可路由时不 ACK 原消息。
- 重试期间业务状态保留为 `PROCESSING`，并更新最近一次错误信息。
- 最终失败时将业务状态更新为 `FAILED`，再发布死信消息。
- 死信发布确认失败时不 ACK 原消息。

消息语义为至少一次投递，因此 Consumer 必须依靠业务幂等保证重复消息安全。

## 8. 幂等设计

以 `resumeId` 作为业务幂等键，并在分析结果中保存 `analysisMessageId`。

数据库约束：

- `resume_analyses.analysis_message_id` 对历史记录允许为空，新 RabbitMQ 分析记录必须赋值。
- `resume_analyses.analysis_message_id` 唯一。
- 同一条消息重复到达时，不产生第二条分析记录。

消费前状态判断：

- `COMPLETED`：直接 ACK。
- 简历不存在：直接 ACK。
- `PENDING`、`PROCESSING` 或 `FAILED`：允许按消息和重放规则处理。

保存结果时，唯一约束作为最终防线。如果数据库已存在同一 `analysisMessageId`，Consumer 将其视为此前事务已提交，修正简历状态为 `COMPLETED` 后 ACK。

手动重放会生成新的 `messageId`，并将原死信消息 ID 写入 `originalMessageId`。重放前若简历已经 `COMPLETED`，不再投递，将死信记录标记为 `RESOLVED`。

## 9. 首次发布可靠性

Producer 启用：

- Publisher Confirm。
- Publisher Returns。
- Mandatory 发布。
- 持久化 Exchange、Queue 和消息。

首次任务发布顺序：

1. 保存简历，状态为 `PENDING`。
2. 发布 RabbitMQ 消息。
3. 等待 Broker Confirm。
4. Confirm 成功则接口返回原有成功结构。
5. Confirm 失败或消息不可路由时，将简历更新为 `FAILED` 并记录错误。

该方案延续现有状态补偿语义，但不宣称数据库写入与消息发布具有原子性。Transactional Outbox 作为后续升级项。

## 10. 死信持久化与管理接口

RabbitMQ 不适合按业务字段分页检索消息，因此最终死信由专用 Consumer 写入 PostgreSQL 后 ACK。

表 `resume_analysis_dead_letters` 包含：

| 字段 | 说明 |
|---|---|
| `id` | 数据库主键 |
| `original_message_id` | 最终失败消息 ID，唯一 |
| `resume_id` | 简历 ID |
| `retry_count` | 最终重试次数 |
| `failure_reason` | 失败原因，限制长度 |
| `payload_json` | 原始消息 JSON |
| `status` | `PENDING`、`REPLAYING`、`REPLAYED` 或 `RESOLVED` |
| `failed_at` | 进入死信时间 |
| `replayed_at` | 重放时间，可空 |
| `replay_message_id` | 重放生成的新消息 ID，可空 |

接口：

```http
GET /api/admin/resume-analysis/dead-letters
GET /api/admin/resume-analysis/dead-letters/{id}
POST /api/admin/resume-analysis/dead-letters/{id}/replay
```

列表接口支持按 `status` 和 `resumeId` 过滤并分页。

重放操作：

1. 查询并锁定死信记录。
2. 通过条件更新将记录从 `PENDING` 原子切换为 `REPLAYING`；未抢占成功则拒绝重复重放。
3. 查询简历。
4. 简历不存在时返回业务错误。
5. 简历已完成时将记录标记为 `RESOLVED`。
6. 其他状态下将简历重置为 `PENDING`。
7. 发布具有新 `messageId` 的主队列消息。
8. Broker Confirm 成功后将死信记录标记为 `REPLAYED`；发布失败则恢复为 `PENDING`。

## 11. 代码边界

新增：

```text
app/src/main/java/interview/guide/common/config/RabbitMqConfig.java

app/src/main/java/interview/guide/modules/resume/messaging/rabbit/
  ResumeAnalysisMessage.java
  ResumeAnalysisRabbitProperties.java
  ResumeAnalysisRabbitProducer.java
  ResumeAnalysisRabbitConsumer.java
  ResumeAnalysisDeadLetterConsumer.java

app/src/main/java/interview/guide/modules/resume/deadletter/
  ResumeAnalysisDeadLetterEntity.java
  ResumeAnalysisDeadLetterStatus.java
  ResumeAnalysisDeadLetterRepository.java
  ResumeAnalysisDeadLetterService.java
  ResumeAnalysisDeadLetterController.java
  ResumeAnalysisDeadLetterDTO.java
  ResumeAnalysisDeadLetterPageResponse.java
```

修改：

- `app/build.gradle`：增加 Spring AMQP 依赖。
- `app/src/main/resources/application.yml`：增加 RabbitMQ 和队列配置。
- `.env.example`：增加 RabbitMQ 环境变量。
- `docker-compose.dev.yml`：增加 RabbitMQ Management 服务。
- `docker-compose.yml`：增加完整部署 RabbitMQ 服务。
- `ResumeUploadService`：改用 RabbitMQ Producer。
- `ResumePersistenceService`、`ResumeAnalysisEntity`：增加消息幂等字段和原子保存方法。

保留：

- `AnalyzeStreamProducer`、`AnalyzeStreamConsumer` 在第一阶段不删除，便于回滚和对比，但不再由简历上传链路调用；其 Bean 是否启用由明确配置控制，防止两个 Consumer 同时处理简历任务。
- Redis Stream 公共模板和其他业务 Consumer 保持不变。

## 12. 配置与回滚

新增配置：

```yaml
app:
  resume:
    messaging:
      provider: rabbitmq
```

允许值：

- `rabbitmq`：启用新 Producer 和 RabbitMQ Consumer。
- `redis-stream`：启用原有简历分析 Stream Producer 和 Consumer。

同一时间只允许一种实现生效。配置切换用于本地回滚和迁移对比，不允许双写。

RabbitMQ 本地端口：

- AMQP：`5672`
- Management UI：`15672`

凭证通过 `.env` 注入，不写入源码。

## 13. 错误处理

- 无效消息：记录错误，进入最终死信，不无限重试。
- 简历不存在：ACK 并记录警告，不进入死信。
- AI 临时失败：按三级延迟策略重试。
- 数据库失败：不 ACK，由 Broker 重新投递。
- 重试消息发布失败：不 ACK 原消息。
- 死信持久化失败：不 ACK 死信消息。
- 重放发布失败：将 `REPLAYING` 恢复为 `PENDING`，不更新为 `REPLAYED`。
- 错误信息写入数据库前必须截断，避免超长异常破坏持久化。

## 14. 测试策略

### 14.1 单元测试

- `retryCount=0/1/2/3` 分别选择 10 秒、30 秒、60 秒和最终死信。
- 已完成简历被重复投递时跳过。
- 简历不存在时 ACK。
- Broker Confirm 失败时不 ACK。
- 死信重放生成新消息 ID并保留原消息 ID。
- 已完成简历的死信记录被标记为 `RESOLVED`。

### 14.2 Repository 测试

- `original_message_id` 唯一约束。
- 状态和 `resumeId` 分页查询。
- `analysis_message_id` 唯一约束。
- 死信状态按 `PENDING -> REPLAYING -> REPLAYED` 或 `PENDING -> RESOLVED` 转换。

### 14.3 RabbitMQ 集成测试

- 主队列发布和消费。
- 手动 ACK 后消息从未确认集合移除。
- 未 ACK 消息在 Consumer 断开后重新投递。
- 10 秒、30 秒、60 秒队列按容差窗口回流主队列。
- 最终失败进入死信队列并持久化。
- 不可路由消息触发 Return。

### 14.4 端到端验证

- 上传正常简历，前端仍接收原有响应结构。
- 状态按 `PENDING -> PROCESSING -> COMPLETED` 流转。
- 最终只生成一条分析结果。
- 人为制造 AI 失败后按三级延迟重试。
- 最终失败可通过 Swagger 查询。
- Swagger 重放后任务重新进入主队列。
- Redis Stream 的其他三类业务仍能正常运行。

## 15. 验收标准

- 前端无需修改。
- 简历 API 路径和响应结构不变。
- 正常分析只保存一条结果。
- 延迟重试时间约为 10 秒、30 秒和 60 秒。
- 最终失败有数据库死信记录。
- 死信可查询、分页和手动重放。
- 重复投递不会产生重复结果。
- RabbitMQ 发布失败对用户和任务状态可见。
- RabbitMQ Management 可以观察主队列、重试队列和死信队列。
- Redis 继续提供缓存、限流和其他三类 Stream 任务。
- 后端测试和前端构建通过。

## 16. 后续演进

完成第一阶段并验证稳定后，再分别评估知识库向量化、文字面试评估和语音面试评估是否迁移。若对数据库与消息发布的一致性提出更高要求，引入 Transactional Outbox 和后台发布器；若消息规模扩大，再评估 RabbitMQ 集群、监控、容量和告警方案。
