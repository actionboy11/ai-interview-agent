<div align="center">

# AI Interview Agent

**基于 Spring AI 的智能面试、RAG 与职业能力评估平台**

围绕简历理解、岗位分析、个性化出题、多轮面试、回答评估和知识问答，
构建覆盖模型调用、工具使用、上下文管理与长任务执行的完整 AI 工作流。

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-brightgreen)](https://spring.io/projects/spring-ai)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?logo=postgresql)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600?logo=rabbitmq)](https://www.rabbitmq.com/)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

</div>

---

## 项目概览

AI Interview Agent 面向求职训练场景，将用户简历、目标岗位、面试 Skill、专业参考资料与多轮会话组合为动态上下文，驱动大模型完成个性化出题、实时追问、逐题评价和综合报告生成。

项目不是单一聊天页面，而是一套可运行的 AI 应用工程：后端统一管理不同模型供应商，通过 Spring AI Advisor 和 Tool Calling 扩展模型能力，通过 pgvector 构建 RAG，通过结构化输出契约连接 LLM 与业务对象，并使用 RabbitMQ 保证耗时任务可靠执行。

### 核心能力

| 能力 | 实现 |
| --- | --- |
| Agent Workflow | 简历解析 → JD 识别 → Skill 选择 → 个性化出题 → 多轮面试 → 分批评估 → 报告汇总 |
| Tool Calling | Spring AI `ToolCallingAdvisor` + Agent Utils `SkillsTool` 动态加载面试技能 |
| 多模型路由 | 统一管理 DashScope、Kimi、DeepSeek、GLM、LM Studio 等 OpenAI 兼容 Provider |
| Prompt Engineering | StringTemplate 模板、动态上下文、数据边界、注入检测和任务级 Prompt 拆分 |
| Structured Output | JSON Schema 校验、DTO 映射、本地 JSON 修复、定向重试与 Micrometer 指标 |
| RAG | Apache Tika、Embedding、pgvector、Query Rewrite、动态 TopK/阈值和多轮上下文 |
| 可靠异步任务 | RabbitMQ 手动 ACK、Publisher Confirm、10/30/60 秒重试、幂等、死信审计与重放 |
| 实时语音 | WebSocket + ASR + LLM 流式输出 + 句子级并发 TTS |

### 本仓库的二次开发贡献

Agent、Advisor、Tool Calling、RAG、多模型路由、结构化输出和语音面试属于原项目已有的平台能力。本仓库当前的主要二次开发工作集中在异步可靠性与上线工程：

- 将简历分析、文字面试评估、语音面试评估和知识库向量化从 Redis Stream 默认链路迁移到四套独立 RabbitMQ 拓扑。
- 增加手动 ACK、Publisher Confirm、10/30/60 秒延迟重试、消费幂等、最终死信持久化与管理端重放。
- 保留逐模块 `redis-stream` 回滚开关，支持迁移期间的灰度切换与故障回退。
- 增加面向受邀演示的 Docker Compose、HTTPS、访问认证、资源限制、备份和公网部署能力。

## Agent 工作流

```mermaid
flowchart LR
    A["简历 / JD / 面试方向"] --> B["能力与岗位信息抽取"]
    B --> C["Skill 选择与参考资料加载"]
    C --> D["个性化问题与追问生成"]
    D --> E["文字或语音多轮面试"]
    E --> F["分批逐题评估"]
    F --> G["二次汇总与结构化报告"]
    G --> H["改进建议与参考答案"]
```

这是一套具备工具调用和工作流编排能力的单 Agent 系统。当前重点是可控的任务编排、上下文构建和业务可靠性，不将其包装为尚未实现的多 Agent 自主协作系统。

## 系统架构

```mermaid
flowchart TB
    UI["React / TypeScript"] --> API["Spring Boot API"]
    UI <-->|"SSE / WebSocket"| API

    API --> REG["LlmProviderRegistry"]
    REG --> CLIENT["ChatClient 配方"]
    CLIENT --> ADV["Advisor Chain"]
    ADV --> TOOL["SkillsTool"]
    ADV --> LLM["OpenAI-compatible LLM"]

    API --> RAG["RAG Pipeline"]
    RAG --> TIKA["Apache Tika"]
    RAG --> VECTOR["PostgreSQL + pgvector"]
    RAG --> STORE["RustFS / S3"]

    API --> MQ["RabbitMQ"]
    MQ --> WORKER["Async AI Workers"]
    WORKER --> DB["PostgreSQL"]
    API --> REDIS["Redis / Redisson"]
```

## Agent 工程设计

### 1. 多模型接入与 ChatClient 配方

`LlmProviderRegistry` 将 Provider 配置、ChatModel、EmbeddingModel 和 ChatClient 创建逻辑集中管理，使业务 Service 不依赖具体模型厂商。配置更新后通过整体替换缓存容器实现线程安全的懒重建，避免旧 API Key、模型名或 Base URL 被在途线程写回新缓存。

根据任务约束创建三类 ChatClient：

| Client | Tools | Advisor | 场景 |
| --- | --- | --- | --- |
| Default | SkillsTool | Tool Calling、SafeGuard、可选 Memory/Logger | RAG、普通 Agent 调用 |
| Plain | 无 | SafeGuard | 出题、评分等严格 JSON 任务 |
| Voice | SkillsTool | 流式 Tool Calling、SafeGuard | 实时语音面试 |

结构化任务使用 Plain Client，避免工具调用消息破坏 JSON 输出；语音场景由业务层按 `sessionId` 管理历史，因此不依赖全局 Memory Advisor，降低会话串扰风险。

### 2. Skill 与 Tool Calling

项目内置 Java 后端、算法、系统设计、前端、Python、测试开发、AI Agent 及企业专项等面试 Skill。每个 Skill 使用 `SKILL.md`、元数据和 references 描述面试角色、考察范围、分类分配及参考知识。

Spring AI Agent Utils 的 `SkillsTool` 将这些资源暴露为 ToolCallback，`ToolCallingAdvisor` 管理模型请求、工具执行和结果回传。出题阶段还会根据岗位描述、难度、历史题目和题量要求动态组装 Skill 上下文。

### 3. Prompt 与结构化输出

简历分析、JD 分类、面试出题、逐题评估、报告汇总、知识库查询和 Query Rewrite 使用独立 StringTemplate 模板。System Prompt 固定角色、评分标准和行为边界，User Prompt 只承载本次业务数据。

`StructuredOutputInvoker` 统一处理：

- `BeanOutputConverter` 与 JSON Schema 校验；
- 模型输出到 Java DTO 的映射；
- 未转义引号的有限本地修复；
- 解析失败后的严格 JSON 定向重试；
- 调用次数、尝试次数和耗时指标；
- 达到最大次数后的统一业务异常。

外部简历和 JD 通过随机数据边界标签包装，并结合 `PromptSanitizer`、安全提示和 `SafeGuardAdvisor` 降低 Prompt Injection 风险。

### 4. RAG 知识库

```mermaid
flowchart LR
    A["PDF / DOCX / Markdown"] --> B["Tika 解析"]
    B --> C["Token 分块"]
    C --> D["Embedding"]
    D --> E["pgvector"]
    Q["用户问题 + 历史"] --> R["Query Rewrite"]
    R --> S["动态 TopK / 相似度阈值"]
    S --> E
    E --> X["检索上下文注入"]
    X --> Y["SSE 流式回答"]
```

短问题、中等问题和长问题使用不同 TopK 与相似度阈值。查询改写失败时回退原问题；改写结果没有有效召回时继续使用原问题检索；没有有效文档命中时直接返回证据不足，而不是要求模型编造答案。

### 5. Agent 长任务可靠性

简历分析、文字面试评估、语音面试评估和知识库向量化默认使用独立 RabbitMQ 拓扑。消息只携带业务 ID，不传输简历正文、音频或大段文档；消费者从 PostgreSQL 或 RustFS 按需加载数据。

```mermaid
flowchart LR
    A["业务提交"] --> B["Publisher Confirm"]
    B --> C["主队列"]
    C --> D["消费者手动 ACK"]
    D -->|"失败"| E["10s / 30s / 60s TTL 重试"]
    E --> C
    D -->|"最终失败"| F["Dead Queue"]
    F --> G["PostgreSQL 死信审计"]
    G --> H["管理接口人工重放"]
```

每类任务均支持消费幂等、失败状态持久化、死信查询和人工重放。旧 Redis Stream 实现作为条件化适配器保留，可通过配置按模块回滚，但 Redis 主要继续承担缓存和限流职责。

## 功能模块

- **简历智能分析**：多格式解析、多维评分、项目经历审计、改进建议和 PDF 报告。
- **文字模拟面试**：Skill 驱动出题、简历定制题、历史去重、追问和异步评估。
- **实时语音面试**：WebSocket、实时字幕、上下文恢复、流式 LLM 和并发 TTS。
- **知识库问答**：多知识库会话、查询改写、向量检索、SSE 流式输出和 Markdown 展示。
- **面试安排**：邀请信息解析、日历视图、状态流转和提醒管理。
- **模型设置**：Provider 管理、默认 Chat/Embedding 模型切换、连通性检查和 API Key 加密。

## 技术选型

### 后端与 AI

| 技术 | 版本/用途 |
| --- | --- |
| Java / Spring Boot | Java 21、Spring Boot 4.1、虚拟线程 |
| Spring AI | 2.0，ChatClient、Advisor、Tool Calling、Structured Output |
| Spring AI Agent Utils | 0.10，SkillsTool |
| PostgreSQL + pgvector | 业务数据、向量存储、HNSW/COSINE 检索 |
| RabbitMQ | AI 长任务异步处理、延迟重试与死信 |
| Redis + Redisson | 缓存、限流和可回滚 Stream 适配器 |
| RustFS / S3 | 简历和知识库原始文件存储 |
| Apache Tika | PDF、DOC、DOCX、TXT、Markdown 解析 |
| Micrometer | 模型调用、结构化输出和语音链路指标 |
| JUnit 5 / Mockito / AssertJ | 单元测试与消息链路验证 |

### 前端

| 技术 | 用途 |
| --- | --- |
| React 18 / TypeScript | 页面与类型安全 |
| Vite / Tailwind CSS 4 | 构建与样式系统 |
| React Router | 页面路由 |
| Framer Motion | 交互动效 |
| Recharts | 评分与统计图表 |
| React Virtuoso | RAG 长会话虚拟列表 |

## 效果展示

### 面试中心与出题

![面试中心](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-hub.png)

![Skill 出题与 JD 解析](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-skill-jd-parse.png)

### 简历分析与面试报告

![简历上传分析](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-upload-analysis.png)

![简历分析详情](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-analysis-detail.png)

![模拟面试](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-mock-interview.png)

![面试详情](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-detail.png)

### RAG 知识库

![知识库管理](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-knowledge-base-management.png)

![问答助手](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-qa-assistant.png)

> 当前截图沿用项目已有资源。建议后续使用个人环境重新截图，以展示 RabbitMQ 管理台、Agent 配置页和最新界面。

## 项目结构

```text
InterviewGuide/
├── app/src/main/java/interview/guide/
│   ├── common/
│   │   ├── ai/                 # Provider Registry、Advisor、结构化输出、安全边界
│   │   ├── async/              # 可回滚的 Redis Stream 模板
│   │   └── evaluation/         # 文字/语音统一评估引擎
│   ├── infrastructure/         # Redis、文件存储、导出和对象映射
│   └── modules/
│       ├── resume/             # 简历解析与分析
│       ├── interview/          # 出题、Skill、文字面试和评估
│       ├── voiceinterview/     # 实时语音面试
│       ├── knowledgebase/      # RAG、向量化和会话管理
│       ├── interviewschedule/  # 面试安排
│       └── llmprovider/        # 模型配置管理
├── app/src/main/resources/
│   ├── prompts/                # StringTemplate Prompt
│   └── skills/                 # SKILL.md、元数据和 references
├── frontend/                   # React 前端
├── docker-compose.dev.yml      # 本地依赖环境
└── .env.example                # 环境变量示例
```

## 本地运行

推荐采用混合开发模式：PostgreSQL、Redis、RabbitMQ 和 RustFS 使用 Docker，前后端在本机运行，便于断点调试与热更新。

### 环境要求

- JDK 21
- Docker Desktop
- Node.js 20+
- pnpm 10+
- IntelliJ IDEA（推荐）

### 1. 克隆与配置

```powershell
git clone https://github.com/actionboy11/ai-interview-agent.git
cd ai-interview-agent
Copy-Item .env.example .env
```

至少填写：

```env
AI_BAILIAN_API_KEY=your_dashscope_api_key
APP_AI_CONFIG_ENCRYPTION_KEY=replace_with_a_random_long_secret

POSTGRES_PASSWORD=<generate-a-unique-database-password>
APP_STORAGE_ACCESS_KEY=<your-storage-access-key>
APP_STORAGE_SECRET_KEY=<generate-a-unique-storage-secret>
```

`.env` 已被 Git 忽略。Gradle `bootRun` 会从仓库根目录加载该文件，不要将真实 API Key 写入 `application.yml` 或提交到版本库。

### 2. 启动基础设施

```powershell
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672` |
| RabbitMQ 管理台 | http://localhost:15672 |
| RustFS API | `localhost:9000` |
| RustFS 控制台 | http://localhost:9001 |

RustFS 首次启动后创建名为 `interview-guide` 的 Bucket；应用也配置了自动创建能力。

### 3. 启动后端

```powershell
.\gradlew.bat :app:bootRun
```

- API：http://localhost:8080
- Swagger：http://localhost:8080/swagger-ui.html
- Health：http://localhost:8080/actuator/health

也可以在 IDEA 中直接运行 `app/src/main/java/interview/guide/App.java`。使用 IDEA 启动时，需要在 Run Configuration 中加载 `.env` 对应变量。

### 4. 启动前端

```powershell
cd frontend
pnpm install
pnpm run dev
```

访问：http://localhost:5173

### 5. 验证构建

```powershell
.\gradlew.bat :app:test --no-daemon
cd frontend
pnpm run build
```

### Agent 评测安全门

Agent 评测基础能力默认关闭。任何真实离线评测开始前，必须同时满足以下条件：

1. `APP_AGENT_EVALUATION_ENABLED=true`；
2. `APP_AGENT_EVALUATION_ARTIFACT_KEY` 已配置为独立的、Base64 编码的 32 字节随机密钥，且密钥已妥善备份；
3. 恢复集成测试通过：

```powershell
.\gradlew.bat :app:test --tests "*AgentEvaluationRecoveryIntegrationTest" --no-daemon
```

评测检查点持久化成功阶段及其产物引用。进程重启后会复用成功阶段，从首个未完成阶段继续；API Key 失效会暂停阶段、样本和批次，不会清空已经完成的结果。更新凭证后才能显式恢复。超时停留在 `RUNNING` 的阶段会以数据库条件更新回收到 `RETRY_WAITING`，避免多实例重复回收。

模型原始响应不会以明文写入数据库，而是使用独立密钥进行 AES-256-GCM 加密后保存到对象存储。密钥错误、AAD 不匹配或密文被篡改时读取会直接失败，不返回部分明文。不要复用 Provider API Key 加密密钥；评测产物密钥丢失后，已有加密产物无法恢复。

停止依赖但保留数据：

```powershell
docker compose -f docker-compose.dev.yml stop
```

> 谨慎使用 `docker compose -f docker-compose.dev.yml down -v`，该命令会删除数据库、消息和对象存储卷。

## RabbitMQ 任务拓扑

四类任务分别使用独立 Exchange、Queue、Routing Key、重试队列和死信队列：

| 任务 | 主队列 | 死信管理接口 |
| --- | --- | --- |
| 简历分析 | `resume.analysis.queue` | `/api/admin/resume-analysis/dead-letters` |
| 语音评估 | `voice.evaluation.queue` | `/api/admin/voice-evaluation/dead-letters` |
| 文字评估 | `interview.evaluation.queue` | `/api/admin/interview-evaluation/dead-letters` |
| 知识库向量化 | `knowledge.vectorization.queue` | `/api/admin/knowledge-vectorization/dead-letters` |

对应环境变量默认均为 `rabbitmq`：

```env
APP_RESUME_MESSAGING_PROVIDER=rabbitmq
APP_VOICE_EVALUATION_MESSAGING_PROVIDER=rabbitmq
APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER=rabbitmq
APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER=rabbitmq
```

将单个值改为 `redis-stream` 并重启后端，可对该模块独立回滚。

## 多用户与能力记忆运维

首次部署时，在私有 `.env` 中同时设置 `APP_AUTH_BOOTSTRAP_USERNAME`、`APP_AUTH_BOOTSTRAP_PASSWORD` 和 `APP_AUTH_BOOTSTRAP_DISPLAY_NAME`。三项必须完整；系统不会提供默认管理员密码，也不会用环境变量重置已存在的管理员。管理员登录后在管理页面生成有期限、一次性的邀请码（`POST /api/admin/invites`），用户在邀请注册页使用邀请码创建账号（`POST /api/auth/register-by-invite`）。管理员只能管理账号，不能直接读取用户的面试和能力记忆。

浏览器使用服务器 Session Cookie。前端先调用 `GET /api/auth/csrf` 获取 `XSRF-TOKEN`，变更请求携带 `X-XSRF-TOKEN`；登录会轮换会话和 CSRF 状态，登录后要重新获取令牌。跨域部署必须配置实际前端来源，且请求携带凭证。API 的认证、权限和业务错误仍用 HTTP 200 的 `Result.error` 结构表达；前端按 `code` 处理失效会话与拒绝访问。

已有单用户数据升级要分两阶段并暂停写入。先对 PostgreSQL 做完整备份，例如 `pg_dump -Fc -f <safe-backup-path> <database-name>`，同时备份对象存储与应用所需密钥，记录当前应用版本和 `flyway_schema_history`。第一阶段部署支持账号/邀请的版本并只运行到 V2，创建要承接旧数据的账号；第二阶段在私有环境变量中把 `APP_LEGACY_OWNER_USERNAME` 设为该账号的用户名，部署当前版本执行 V3 至 V10。V3 会在事务中锁定旧资源表，要求旧数据恰好对应这个账号，然后回填所有权并建立约束；全新空库可留空。不要在已有旧数据的库上跳过第一阶段、猜测用户名或修改已执行的迁移。生产库使用 `ddl-auto=validate`，迁移失败时停止服务、保留错误日志，修正账号/配置后再执行；需要回退时先停止应用与消费者，在隔离数据库运行 `pg_restore --clean --if-exists -d <isolated-restore-database> <safe-backup-path>` 验证备份，再把升级前数据库及对象存储备份恢复到服务使用的目标，并回到升级前应用版本。仅回滚二进制不能撤销 V3–V10 的 schema 改动。

文字面试评估完成后，能力证据派生使用独立 RabbitMQ 路由 `ability.memory.derivation.queue`，失败依次按配置的 10、30、60 秒重试，最终进入 `ability.memory.derivation.dead.queue`，会话上保存派生失败状态和错误。排查时先检查评估是否完成、队列/死信与消费者日志、会话派生状态；修复根因后可重投原任务，派生按会话幂等。当前没有供用户自行重放死信的管理 API，不能把“刷新建议”当作重试任务。评估失败或派生尚未完成时不会出现能力候选。

用户在面试详情确认、修改或拒绝候选画像。只有已确认且仍为 ACTIVE 的画像影响下一场文字面试；未确认候选和其他用户的数据不参与规划。出题时的槽位计划会随会话保存，`GET /api/interview/sessions/{sessionId}/plan` 只返回用途计数与逐题通用原因，不返回画像摘要、内部 Prompt 或模型输入。旧会话没有计划快照时返回“暂无说明”。没有已确认画像，或能力记忆读取/规划失败时，系统使用固定题量分配；若模型生成的自适应题目不符合槽位计划，也会保存固定回退计划并重新生成。供应商本身不可用时创建面试失败，不会伪装成能力记忆回退。

## 已知限制与 Roadmap

- 在现有调用审计、检查点和加密产物基础上增加离线数据集与基准执行器，输出结构化成功率、重试恢复率、调用次数、Token 和阶段耗时。
- 将面试 Agent 的阶段流转升级为显式状态机，增强可解释性和恢复能力。
- 打通知识库与模拟面试，让出题和参考答案可以按知识库动态增强。
- 探索 WebRTC、客户端 VAD 和端到端语音模型，降低语音链路延迟。
- 优化前端大 chunk 与部分 Tailwind 生成的 CSS 警告。

## 项目来源与开源说明

本项目基于 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 进行二次开发。原项目提供 AI 面试、Agent/Advisor、RAG、多模型与语音能力；本仓库在此基础上完成 RabbitMQ 异步可靠性迁移和生产演示环境建设。

感谢原项目作者及所有开源依赖的贡献。本项目继续按照 [GNU Affero General Public License v3.0](LICENSE) 发布；如通过网络向用户提供修改后的服务，请按照许可证要求提供对应源代码。

## License

[GNU Affero General Public License v3.0](LICENSE)
