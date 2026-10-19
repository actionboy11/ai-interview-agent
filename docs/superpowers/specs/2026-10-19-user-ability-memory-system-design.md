# 用户能力记忆与自适应出题系统设计

## 1. 背景

当前平台已经具备简历分析、文字面试、语音面试、知识库问答、面试评估和 Skill 驱动出题能力，但整体仍按单用户演示系统设计：没有正式登录、核心业务数据缺少用户归属，多数接口按客户端传入的资源 ID 直接查询。

现有文字面试按照 Skill 分类的固定优先级分配题量。例如 Java 后端 Skill 使用 `ALWAYS_ONE`、`CORE`、`NORMAL` 规则保证项目题和 Java、MySQL、Redis、Spring 等方向的覆盖。这套规则稳定，但无法根据用户跨场次积累的优势和薄弱点持续调整后续面试。

本设计先建立可信的用户身份和数据隔离，再从文字面试评估中形成经过用户确认的能力画像，最后使用确定性的 QuestionPlan 调整后续文字面试的题目分类、用途和难度。

Agent 离线评测系统不在本设计范围内，实施期间暂停继续扩展该系统。

## 2. 目标

一期目标是打通以下闭环：

```text
管理员生成邀请码
→ 用户注册并登录
→ 用户只能访问自己的业务数据
→ 用户完成文字面试并获得评估
→ 系统生成候选能力画像
→ 用户确认、修改或拒绝候选画像
→ 用户发起下一场文字面试
→ 系统依据 ACTIVE 能力画像生成 QuestionPlan
→ 题目在保持基础覆盖的同时强化弱项并校准强项
```

系统应满足：

- 能力记忆有明确用户归属、来源和确认状态。
- 未经用户确认的推断不影响后续出题。
- 出题规划由后端确定性规则完成，LLM 只生成具体题目。
- 记忆故障不阻断原有面试，能够完整回退当前固定分配逻辑。
- 用户能够理解画像从何而来，以及某道自适应题为什么被选中。

## 3. 非目标

一期不实现：

- 语音面试的记忆生成或自适应出题。
- 自动扫描历史面试并批量生成画像。
- 每回答一题便实时生成下一题。
- 开放注册、邮件验证和密码找回。
- 组织、团队和复杂 RBAC。
- 记忆 Embedding 或向量检索。
- 多 Agent 共享记忆。
- 未经确认自动覆盖能力画像。
- 管理员查看用户简历、面试内容或能力记忆。
- 管理员直接物理删除用户及其全部数据。

## 4. 当前缺口

### 4.1 身份与权限

项目当前没有 Spring Security、用户实体、注册登录接口、密码哈希、可信的当前用户上下文或前端登录状态。语音面试虽然存在 `userId` 字段，但使用固定默认值，查询接口还允许客户端直接传入 `userId`，不能作为安全身份。

### 4.2 数据归属

简历、文字面试、知识库、RAG 会话和面试日程等根实体没有用户外键。只增加登录而不改 Repository 查询会产生跨用户按 ID 读取、修改、删除和导出的风险。

简历和知识库的文件哈希目前全局唯一，多用户环境中需要调整为用户范围唯一，避免跨用户复用记录或泄露其他用户是否上传过相同文件。

### 4.3 数据库迁移

项目当前依赖 `ddl-auto=update`。用户外键、历史数据回填、唯一约束调整和生产环境升级需要使用 Flyway 显式迁移，完成后生产环境使用 `ddl-auto=validate`。

### 4.4 能力记忆

现有系统保存问题、回答、分数和评语，但没有当前能力画像、画像证据、候选更新、用户确认状态和画像到出题计划的映射。

现有评估链路部分位置传递展示分类名。记忆和规划必须使用稳定的 `category_key`，并将展示用 `category_label` 与其分离。

## 5. 总体架构

```text
身份认证层
  管理员创建账号 / 邀请码注册 / 登录 / 退出
            ↓
资源归属层
  简历、面试、知识库、RAG 会话、日程绑定 owner_user_id
            ↓
能力记忆层
  面试评估 → 能力证据 → Proposal → 用户确认 → ACTIVE 画像
            ↓
自适应出题层
  Skill + ACTIVE 画像 + 历史覆盖 → QuestionPlan → LLM 生成题目
```

新能力分别放入 `modules/auth` 和 `modules/memory`。QuestionPlan 属于文字面试业务编排，放在 `modules/interview` 下。业务 Service 通过清晰接口使用认证和记忆模块，不在 Controller 或现有 Service 中散落 Cookie、Header 或记忆查询逻辑。

## 6. 身份认证设计

### 6.1 技术选择

一期采用：

- Spring Security
- Spring Session Data Redis
- HttpOnly、Secure、SameSite Session Cookie
- BCrypt 密码哈希
- Cookie CSRF Token + 请求 Header

这是单一 React Web 客户端，Redis 已经是现有依赖。服务端 Session 支持立即注销和禁用账号，不需要一期实现 JWT 刷新、轮换和吊销体系。

### 6.2 用户模型

`users` 表包含：

- `id`：UUID
- `username`：归一化后的唯一登录名
- `password_hash`
- `display_name`
- `role`：`ADMIN`、`USER`
- `status`：`ACTIVE`、`DISABLED`
- `must_change_password`
- `created_at`、`updated_at`、`last_login_at`

首次部署通过环境变量创建初始管理员，不提供代码内默认密码。

### 6.3 邀请码模型

`registration_invites` 表包含邀请码哈希、状态、过期时间、创建人、使用人和使用时间。状态为 `ACTIVE`、`USED`、`REVOKED`、`EXPIRED`。

邀请码明文只在创建时返回一次，数据库仅保存哈希。注册在一个事务内校验并消费邀请码，条件更新和数据库约束保证并发情况下只能使用一次。

管理员可以直接创建带临时密码的用户，也可以生成和撤销邀请码。临时密码用户首次登录后必须修改密码。

### 6.4 接口

普通认证接口：

- `POST /api/auth/login`
- `POST /api/auth/register-by-invite`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- `POST /api/auth/change-password`
- `GET /api/auth/csrf`

管理员接口：

- 账号创建、查询、启用和禁用
- 邀请码创建、查询和撤销

认证失败和权限不足继续遵循项目的 HTTP 200 + `Result.error` 约定，但使用独立错误码区分未登录、Session 失效、权限不足、账号禁用、密码错误和邀请码无效。

### 6.5 当前用户

提供统一的 `CurrentUserProvider`：

```java
UUID requireUserId();
CurrentUser requireCurrentUser();
boolean isAdmin();
```

业务代码禁止信任客户端传入的 `userId`、`X-User-Id` 或自行解析 Cookie。

## 7. 资源归属与隔离

以下根实体增加非空 `owner_user_id`：

- `ResumeEntity`
- `InterviewSessionEntity`
- `VoiceInterviewSessionEntity`
- `KnowledgeBaseEntity`
- `RagChatSessionEntity`
- `InterviewScheduleEntity`

Repository 使用用户范围查询，例如：

```java
findByIdAndOwnerUserId(...)
findBySessionIdAndOwnerUserId(...)
findAllByOwnerUserIdOrderByCreatedAtDesc(...)
existsByIdAndOwnerUserId(...)
```

不存在和不属于当前用户统一表现为资源不存在，避免泄露资源存在性。读取、更新、删除、导出、重新分析、重新向量化、SSE 和 WebSocket 均执行相同归属校验。

文件哈希约束调整为 `UNIQUE(owner_user_id, file_hash)`。对象存储路径使用 `users/{userId}/...` 前缀，Redis 业务缓存 Key 包含用户 ID，pgvector 文档元数据写入用户 ID 并在数据库查询层强制过滤。

模型 Provider、全局模型设置和邀请码管理仅允许 `ADMIN`。管理员角色不提供读取用户业务内容的通用旁路。

## 8. 能力记忆模型

### 8.1 能力画像

`user_ability_profiles` 表表示当前已确认画像，按 `(user_id, skill_id, category_key)` 唯一。

主要字段：

- `level`：`WEAK`、`DEVELOPING`、`COMPETENT`、`PROFICIENT`、`STRONG`
- `score`：0～100
- `confidence`
- `summary`
- `confirmed_evidence_count`
- `status`：`ACTIVE`、`DISABLED`、`NEEDS_REVIEW`
- `last_evaluated_at`、`last_confirmed_at`
- `version`：乐观锁

只有 `ACTIVE` 画像参与 QuestionPlan。

### 8.2 能力证据

`user_ability_evidence` 保存每次文字面试的不可变事实：用户、Session、Answer、Proposal、Skill、分类 Key、知识点摘要、题目分数、难度、反馈摘要和发生时间。每条证据通过 `proposal_id` 关联本场同分类的候选更新，从而明确区分已确认、已拒绝和仍待处理的证据。

`interview_answer_id` 唯一，保证评估消息重复投递不会重复生成证据。

### 8.3 候选更新

`ability_profile_proposals` 保存本场评估对当前画像的建议变化，包括前后等级、前后分数、建议摘要、状态和乐观锁版本。状态为：

- `PROPOSED`
- `ACCEPTED`
- `ACCEPTED_WITH_EDIT`
- `REJECTED`

同一分类可以同时保留一个当前 ACTIVE 画像和一个等待确认的新 Proposal。未确认 Proposal 不影响出题。

### 8.4 候选生成

评估结果按 `skill_id + category_key` 分组，使用现有题目得分、`topicSummary` 和反馈确定性生成证据与 Proposal，一期不增加额外 LLM 调用。

分数与等级映射：

| 分数 | 等级 |
| --- | --- |
| 0～39 | WEAK |
| 40～59 | DEVELOPING |
| 60～74 | COMPETENT |
| 75～89 | PROFICIENT |
| 90～100 | STRONG |

一场只有少量题目时降低置信度，并在 UI 中标明证据不足。

### 8.5 用户决策

面试报告页展示当前画像、本场结果、证据数量和建议摘要。用户可以确认、修改后确认或拒绝。

修改后确认允许调整能力等级和摘要，但不修改原始题目分数和评估证据。确认操作在短事务内完成，并用乐观锁防止重复提交。

画像首次确认时采用本场分类平均分；已有画像使用可解释的增量更新：

```text
新分数 = 旧分数 × 70% + 本场分数 × 30%
```

置信度按已确认的不同面试场次增加，而不是按单场题数无限增长。

## 9. 自适应 QuestionPlan

### 9.1 原则

QuestionPlan 由后端规则计算，LLM 不决定题量分布。输入包括当前 Skill、总题数、基础难度、简历状态、ACTIVE 画像、最近 3～5 场分类覆盖和历史 `topicSummary`。

输出题槽包含：

- `slotId`
- `categoryKey`
- `purpose`
- `targetDifficulty`
- `sourceMode`
- `focusTopics`
- `avoidedTopics`
- `relatedProfileId`

用途为 `MANDATORY`、`BASELINE`、`WEAKNESS_REINFORCEMENT`、`STRENGTH_CALIBRATION`、`EXPLORATION`。

### 9.2 初始预算

10 道题的推荐预算：

- 强制题型 1
- 基础覆盖 4
- 弱项强化 3
- 强项校准 1
- 未知探索 1

题量不足时依次保证强制题、基础题、弱项题、探索题和强项校准题。没有弱项时将预算回流到基础覆盖和探索。

单分类原则上不超过总题数的 30%；Skill 分类过少时允许放宽。`ALWAYS_ONE`、`CORE`、`NORMAL` 仍然是基础覆盖规则，覆盖目标从单场全部覆盖调整为最近若干场滚动覆盖。

### 9.3 难度

用户选择的难度是基准：弱项题降低一级或保持，基础和探索题保持，强项校准题提高一级，项目题保持。系统不会因一个弱项降低整场难度。

### 9.4 简历题

没有有效画像时继续使用当前约 60% 简历题、40% 方向题。当当前 Skill 至少有两个 ACTIVE 画像，且每个画像至少来自两场已确认面试时，调整为约 40% 简历题、60% 能力方向题。

简历中确实存在相关技术时，简历题可以承担弱项验证；否则只能生成 Skill 方向题，禁止为匹配记忆编造项目经历。

现有两个并行生成器继续保留，QuestionPlan 分成 `resumeSlots` 和 `directionSlots` 后分别执行。

### 9.5 校验与持久化

`interview_question_plans` 与面试 Session 一对一，保存计划版本、模式、计划 JSON、创建时间和回退原因。每道题携带 `slotId`。

生成后验证题目数量、分类、题槽、简历事实和计划完整性。规划或画像读取失败时回退当前固定分配。回退只关闭自适应能力，不阻断原有面试。

## 10. 数据流与事务边界

### 10.1 创建面试

1. 从 SecurityContext 获取用户并验证简历归属。
2. 读取当前 Skill 的 ACTIVE 画像。
3. 生成并在短事务中保存 Session 和 QuestionPlan。
4. 在事务外调用 LLM 生成题目。
5. 校验题目并在短事务中保存。

### 10.2 评估与记忆派生

现有 RabbitMQ 消费者在事务外调用 LLM，随后在短事务中保存报告和逐题评估。报告提交成功后再触发能力证据派生，避免记忆失败导致评估结果回滚和昂贵的 LLM 重复调用。

一期使用独立、轻量的 `ability.memory.derivation.queue`。该任务不调用 LLM，只做确定性计算，并在 Session 上记录派生状态、错误和消息 ID。消费者在写入前检查 Session、用户、评估状态、消息 ID 和唯一约束。

### 10.3 删除

删除文字面试时同步删除该场未处理 Proposal 和能力证据，并使用剩余已确认证据重新计算受影响画像。没有剩余证据的画像停用。用户手工修改过的画像进入 `NEEDS_REVIEW`，不被自动覆盖。

### 10.4 账号禁用

禁用账号后立即删除其 Redis Session。队列消费者在处理前检查用户状态，禁用用户的任务不继续调用外部模型。一期不提供管理员物理删除账号。

## 11. 前端设计

新增：

- 登录页
- 邀请注册页
- 管理员账号与邀请码页
- 我的能力画像页

面试报告页增加候选画像卡片，支持确认、编辑后确认和拒绝。创建面试后展示本场计划的简化组成，并为自适应题提供简短原因，例如“用于复测最近两场表现较弱的 MySQL 事务方向”。前端不展示内部 Prompt。

Axios 开启凭证携带并处理 CSRF Token、未登录、Session 失效和权限不足。路由根据登录状态和角色保护。

## 12. 数据迁移

Flyway 迁移顺序：

1. 创建用户和邀请码表。
2. 创建由 `APP_LEGACY_OWNER_USERNAME` 指定的历史数据账号；该账号通常是初始管理员，也可以是预先创建的普通用户。
3. 给根业务表添加暂时可空的 `owner_user_id`。
4. 将全部历史数据归属给初始账号。
5. 校验不存在空归属。
6. 设置非空、外键和索引。
7. 将文件哈希唯一约束改为用户范围唯一。
8. 创建能力画像、证据、Proposal 和 QuestionPlan 表。
9. 生产配置改为 `ddl-auto=validate`。

旧面试不自动生成能力画像。后续如需回溯，由用户主动触发独立功能。

## 13. 安全与隐私

- CORS 仅允许明确配置的来源。
- Session Cookie 不暴露给 JavaScript。
- 修改请求启用 CSRF 防护。
- 密码和邀请码只保存哈希。
- 日志不记录密码、邀请码明文、Cookie、简历全文、完整回答、完整记忆摘要或包含记忆的 Prompt。
- 用户资源查询始终包含用户条件。
- 用户编辑的画像摘要作为不可信数据处理，注入 Prompt 时使用数据边界和长度限制。
- 管理员没有读取用户内容的默认能力。

## 14. 测试策略

认证测试覆盖邀请码单次使用、过期与撤销、密码哈希、账号禁用、首次改密、登录和退出。

权限集成测试使用两个用户，验证所有读取、修改、删除、导出、SSE 和 WebSocket 路径都不能跨用户访问。管理员也不能通过普通业务接口绕过所有权。

记忆测试覆盖证据和 Proposal 幂等、未确认不生效、接受与拒绝、乐观锁冲突、来源删除后重算，以及不同用户数据隔离。

QuestionPlan 测试使用固定输入断言总题数、强制覆盖、弱项加权、强项上限、探索题、难度调整、Skill 过滤、重复规避和固定分配回退。

迁移测试使用 PostgreSQL/Testcontainers 验证历史数据回填、约束创建和升级后应用启动。

## 15. 实施阶段

1. 认证、邀请码、Session 和 Flyway 基础。
2. 全部业务资源的用户归属与访问隔离。
3. 文字面试能力画像、证据、Proposal 和确认界面。
4. QuestionPlan、自适应配额、生成校验和解释界面。

每一阶段独立通过测试后再进入下一阶段。身份认证完成但资源尚未隔离时，不允许作为多用户版本部署。

## 16. 一期验收标准

一期只有在完整闭环通过后才算完成：

- 管理员能够创建用户或生成邀请码。
- 用户能够注册、登录、退出和修改密码。
- 用户只能访问自己的全部业务资源。
- 旧数据安全迁移给指定初始账号。
- 新文字面试评估能够生成候选画像。
- 用户可以确认、修改或拒绝候选画像。
- 只有 ACTIVE 画像参与下一场文字面试。
- QuestionPlan 在保持基础覆盖的同时强化弱项、校准强项和探索未知方向。
- 实际题目数量、分类和来源符合计划。
- 用户能够查看自适应出题的简化原因。
- 没有记忆或记忆模块失败时，现有固定出题流程保持可用。
- 两用户交叉访问、记忆串用和向量串用测试全部通过。
- 生产数据库结构由 Flyway 管理，不依赖 `ddl-auto=update`。
