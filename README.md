# AI Interview Agent

面向持续练习的 AI 模拟面试平台：结合用户的简历、岗位方向、历史表现和已确认能力画像规划题目，再根据当场回答决定是否追问。

本仓库：[actionboy11/ai-interview-agent](https://github.com/actionboy11/ai-interview-agent)。

项目基于 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 二次开发。我们当前的重点是把一次性的模拟面试，连接成“练习 → 评价 → 确认能力记忆 → 个性化再练习”的闭环，而不是让大模型不受约束地决定整个面试流程。

## 当前实现

| 能力 | 说明 |
| --- | --- |
| 账号与数据归属 | 管理员创建账号或邀请码注册；登录、密码管理、Redis Session、CSRF 防护及按用户隔离业务数据 |
| 简历与岗位分析 | 解析简历、分析岗位要求，为出题提供背景 |
| 个性化主问题规划 | 后端规则规划器结合页面输入、已确认画像、历史题目与方向规则分配题槽，优先保证方向覆盖 |
| 动态文字面试 | 先生成主问题池；回答充分就进入下一主问题，需要补充证据时才调用模型追问 |
| 面试评价与报告 | 动态面试按主问题组评价，追问不额外放大该能力的权重；支持报告与导出 |
| 长期能力记忆 | 从正式评价提取能力证据，生成更新建议，用户确认、修改或拒绝后再影响后续规划 |
| 知识库与语音 | 保留文档解析、向量检索、RAG 问答、多模型配置和实时语音面试能力 |
| 异步可靠性 | RabbitMQ 任务重试与死信；动态追问使用 Redis Stream，并结合数据库任务状态恢复 |

### 本仓库的主要改造

原项目提供了简历分析、模拟面试、语音、RAG、多模型调用等基础。本仓库在此基础上持续完成：

- 多用户登录、管理员与邀请码、资源所有权隔离及旧数据归属迁移。
- 可追溯的能力证据、待确认的画像更新建议，以及长期能力画像管理。
- 覆盖优先的规则出题规划，控制题型分布，并保存计划快照与出题原因。
- 有预算、有超时、有状态恢复的回答感知动态追问。
- 按主问题组进行评价和记忆派生，区分“不会”“跳过”“未评估”。
- RabbitMQ 异步任务迁移、重试、死信和部署配置。

## 一场面试如何运行

```text
页面输入 + 简历/岗位背景 + 已确认能力画像 + 历史题目 + 方向规则
                              ↓
                  后端规则规划器生成主问题题槽
                              ↓
                  大模型按计划生成主问题池和观察目标
                              ↓
                     展示当前问题，用户回答
                              ↓
          后端校验状态和预算，模型判断是否需要追问
               ├─ 回答充分 / 明确不会 / 跳过 → 下一主问题
               └─ 需要补充证据且预算允许 → 生成追问
                              ↓
                    完成面试，按主问题组评价
                              ↓
                    异步提取证据，形成记忆建议
                              ↓
                  用户确认 / 修改 / 拒绝更新建议
                              ↓
                    已确认画像用于下一场规划
```

这里有两个不同的决策层：

- **规则规划器**负责出题覆盖、题槽分配、顺序和边界，由后端 Service 编排实现。
- **大模型**负责按计划生成题目、理解当前回答、提出有限追问，以及输出结构化评价。

长期能力画像不是聊天记录，也不会因为一次回答“不知道”就直接被改成薄弱项。回答先形成可追溯证据，画像变更需要用户确认。

### 动态追问的边界

- 创建时确定主问题计划，面试中不随意重排整个主问题池。
- 每个主问题最多追问 2 次；全场追问上限为计划主问题数。
- 回答充分时不强制追问；明确“不会”时结束当前题组，不强制再问简单题。
- 单次追问决策有 30 秒总时限；异常时保留已提交回答并继续主流程。
- 通过请求幂等、状态版本和数据库任务记录处理重复提交、迟到结果与刷新恢复。
- 主问题与追问组成一个评价组；跳过、未出题或系统缺失不能伪装成真实零分。
- 当前动态流程面向文字面试；旧固定题面试兼容保留，语音流程独立运行。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.0、Gradle、Spring AI 2.0.0 |
| 前端 | React 18、TypeScript、Vite、Tailwind CSS 4 |
| 数据库 | PostgreSQL、pgvector（1024 维、COSINE）、Flyway |
| 缓存与会话 | Redis、Redisson、服务器 Session |
| 异步任务 | RabbitMQ、Redis Stream |
| 文档与存储 | Apache Tika、RustFS / S3 兼容存储 |
| 映射与导出 | MapStruct、iText 8、SpringDoc OpenAPI |

PostgreSQL 保存业务事实与状态；Redis 用于会话、缓存、限流及部分异步链路，不作为长期能力画像的唯一存储。

## 本地启动

以下命令以 Windows PowerShell 为例。需要 JDK 21、Node.js 20+、pnpm 和 Docker。

### 1. 获取代码与配置

```powershell
git clone https://github.com/actionboy11/ai-interview-agent.git
cd ai-interview-agent
Copy-Item .env.example .env
```

编辑私有的 `.env`，至少配置：

- `AI_BAILIAN_API_KEY`：默认模型服务所需密钥；其他 Provider 按模板配置。
- `APP_AI_CONFIG_ENCRYPTION_KEY`：替换为随机长密钥，部署后妥善保管。
- `APP_AUTH_BOOTSTRAP_USERNAME`、`APP_AUTH_BOOTSTRAP_PASSWORD`、`APP_AUTH_BOOTSTRAP_DISPLAY_NAME`：首次部署的管理员信息，三项一起填写。
- 数据库、RabbitMQ 和对象存储凭证，确保应用与容器使用相同配置。

使用 `docker-compose.dev.yml` 时，RustFS 的容器配置读取 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`；应用读取 `APP_STORAGE_ACCESS_KEY` / `APP_STORAGE_SECRET_KEY`。这两组值必须对应，不能直接假定模板中的 MinIO 示例值适用于 RustFS。

不要提交真实 `.env`、API Key、密码或 Token。已存在的管理员不会通过启动变量自动重置密码。

### 2. 启动依赖

```powershell
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

| 服务 | 本地地址 |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672` |
| RabbitMQ 管理台 | [localhost:15672](http://localhost:15672) |
| RustFS API / 控制台 | `localhost:9000` / [localhost:9001](http://localhost:9001) |

确保对象存储中存在 `APP_STORAGE_BUCKET` 指定的 Bucket；模板默认名称为 `interview-guide`。

### 3. 启动后端

在仓库根目录执行：

```powershell
.\gradlew.bat :app:bootRun
```

`bootRun` 会加载根目录 `.env`。通过 IDE 启动时，需要在运行配置中加载相同变量。

后端默认 [localhost:8080](http://localhost:8080)，接口文档位于 [Swagger UI](http://localhost:8080/swagger-ui.html)。

### 4. 启动前端

另开一个终端：

```powershell
cd frontend
pnpm install
pnpm run dev
```

打开 [localhost:5173](http://localhost:5173)，用初始化管理员登录，再创建用户或生成邀请码。项目不是开放注册模式。

### 5. 常用验证

```powershell
# 仓库根目录
.\gradlew.bat :app:compileJava
.\gradlew.bat :app:test --no-daemon

# frontend 目录
pnpm run build
```

真实模型的出题效果需配置可用 Provider 后实测；构建和自动化测试不等于题目质量已经得到验证。

停止依赖并保留数据：

```powershell
docker compose -f docker-compose.dev.yml stop
```

不要为了重新启动而删除数据卷。`down -v` 会删除 Compose 管理的数据卷。

## 配置与数据注意事项

- 动态文字面试默认启用；可设置 `APP_INTERVIEW_DYNAMIC_ENABLED=false` 关闭入口能力。
- 简历分析、文字评价、语音评价、知识库向量化及能力记忆派生的消息配置见 [.env.example](.env.example)。相关任务默认使用 RabbitMQ；不能因此认为项目已经不使用 Redis Stream。
- 数据库结构迁移位于 [db/migration](app/src/main/resources/db/migration)。生产环境应通过迁移管理结构并使用 `ddl-auto=validate`，不要依赖自动建表。
- 旧单用户数据升级不能直接按全新空库启动：先备份数据库、对象存储与密钥，准备承接账号，再按迁移要求配置 `APP_LEGACY_OWNER_USERNAME`。具体方案见[用户与能力记忆设计](docs/superpowers/specs/2026-10-19-user-ability-memory-system-design.md)。
- 旧固定题面试继续使用兼容流程，不会因启用动态面试而自动重写历史记录。
- Agent 离线评测不是当前建设重点，相关功能默认关闭，不作为本项目当前已经完成的评测闭环宣传。

## 项目结构

```text
app/src/main/java/interview/guide/
├── common/                 # AI 调用、结构化输出、异步模板、通用配置
├── infrastructure/         # 文件存储、Redis、映射与导出
└── modules/
    ├── auth/               # 登录、账号、邀请码与权限
    ├── interview/          # 规则规划、出题、动态运行时与评价
    ├── memory/             # 能力证据、更新建议与长期画像
    ├── resume/             # 简历分析
    ├── voiceinterview/     # 语音面试
    ├── knowledgebase/      # RAG 与知识库
    ├── interviewschedule/  # 面试安排
    └── llmprovider/        # 模型供应商配置
app/src/main/resources/
├── prompts/                # StringTemplate 提示词
└── db/migration/           # 数据库版本迁移
frontend/src/               # 页面、组件、API 与类型
docs/superpowers/           # 功能设计、实施计划与验证记录
```

## 设计与验证记录

- [用户与能力记忆系统](docs/superpowers/specs/2026-10-19-user-ability-memory-system-design.md)
- [覆盖优先的出题规划](docs/superpowers/specs/2026-10-28-coverage-first-question-planning-design.md)
- [动态追问设计](docs/superpowers/specs/2026-10-29-dynamic-interview-follow-up-design.md)
- [动态追问实施计划](docs/superpowers/plans/2026-10-29-dynamic-interview-follow-up.md)
- [动态追问验证记录](docs/superpowers/plans/2026-10-29-dynamic-interview-follow-up-verification.md)

上述设计记录用于理解具体约束；实际行为以当前代码和配置为准。本页不再使用原项目截图展示本仓库的新功能，后续可补充本项目实际运行截图。

## 来源与许可

感谢 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 及其贡献者提供的基础能力，也感谢本项目使用的开源依赖。

本仓库是基于原项目的二次开发，不是从零实现全部功能。保留原项目来源与 [GNU Affero General Public License v3.0](LICENSE) 许可；具体权利与义务以许可证全文为准。
