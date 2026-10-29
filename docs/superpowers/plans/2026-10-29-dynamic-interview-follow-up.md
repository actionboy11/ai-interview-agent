# 文字面试有边界动态追问 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为新文字面试实现固定主问题、有预算的动态追问、可恢复作答及按主问题组评价与记忆证据，完整保留旧固定面试行为。

**Architecture:** PostgreSQL 保存不可变主问题池、只追加的实际问答序列、运行版本与唯一决策任务；Redis Stream 至少一次投递，短事务认领/应用、事务外模型调用。动态模式使用独立作答及评价适配，前端轮询数据库状态；旧模式继续原路径。

**Tech Stack:** Spring Boot 4.1.0 / Java 21 / Gradle / Spring AI 2.0.0 / PostgreSQL / Redis Stream / React 18 / TypeScript / Vite / pnpm。

**Spec:** `docs/superpowers/specs/2026-10-29-dynamic-interview-follow-up-design.md`（用户于 2026-10-29 批准）。

## Global Constraints

- 主问题计划及顺序固定；每组最多 2 次追问、整场最多 N 次，只在成功发布追问时消耗预算。
- 单次决策总 deadline 默认 30 秒，包含排队、模型调用和结构化修复；重新投递不重置 deadline。
- 回答充分不追问；明确不会和跳过都结束当前组，但分别记录。系统失败保留答案、提示并进入下一主问题，不套固定追问，不因故障扣分。
- 主问题与 2–4 条可观察目标一次生成；只发布当前问题，未发布主问题池不占实际索引。已提交答案不允许回改。
- 动态执行模式独立于 V1/V2 规划版本；旧客户端缺省创建固定模式，新前端闭环完成后默认声明动态。旧面试不补题、不重写、不重算评分或画像。
- 无证据分数为 null，而不是 0；动态主问题组等权计分，每组只计一份记忆证据，明确不会即使无文本也保留不足证据。画像仍通过原有建议确认流程更新。
- 模型通过 LlmProviderRegistry 和 StructuredOutputInvoker；外部调用不在事务内；生产/消费使用 AbstractStreamProducer/Consumer；Controller 只委托，统一 Result/BusinessException。
- 不实现严格语义去重、语音动态追问、跨场覆盖改造、整场重排或离线评测平台；不安装额外产品依赖。
- 开始实施前读 AGENTS.md 及 `.claude/rules/backend.md`、`ai-and-async.md`、`frontend.md`；用 using-git-worktrees 建立隔离工作区。保留主目录全部暂存、未暂存和未跟踪文件，尤其 InterviewQuestionService 的用户改动。
- 中间提交动态开关默认关闭，不在现有真实数据库执行迁移/造题；全部闭环验收后才开放入口。迁移和并发验证使用隔离测试数据。

## Review Focus

1. 双标签页与“保存草稿”迟到：正式提交后不能覆写答案或改变追问上下文（Task 3/6/9）。
2. DB 提交后队列断线、租约过期和结果迟到：答案不丢失、不重复插题、30 秒后能自动推进（Task 5/10）。
3. 最后一题及等待中提前交卷：不能提前误完成，也不能交卷后追加题目/重复评价（Task 3/5/10）。
4. 空文本的“不会”与全跳过：前者有不足证据，后者不是 0 分，追问不能重复累积证据（Task 7/8/9）。
5. 旧 V2 不等于动态、旧缓存和跨用户任务：模式/版本/归属不混用，旧报告不变（Task 1/6/10）。

## 文件组织与依赖

下文 `D` 是精确目录 `app/src/main/java/interview/guide/modules/interview/dynamic/`，`DT` 是 `app/src/test/java/interview/guide/modules/interview/dynamic/`；路径中的 `D/`、`DT/` 必须按此展开，不是新顶层目录。
动态子模块按现有 MVC 分层设 `model/`、`dto/`、`repository/`、`service/`、`messaging/`。一类一文件；不把全部新流程塞进 InterviewSessionService。

依赖：Task1 → Task2/3；Task2+3 → Task4/5；Task2..5 → Task6；Task1+3 → Task7 → Task8；Task6+7+8 → Task9 → Task10。按编号串行提交更易验证，不能先接通未完成的动态入口。
本计划是一个闭环，不是多个独立项目：缺少 Task7/8 的追问将破坏已确认的评价与记忆语义，因此不能作为“第二批全部完成”交付。

## 共享接口与数据约定

- `InterviewExecutionMode { LEGACY_FIXED, DYNAMIC_FOLLOW_UP }`；`DynamicPhase { WAITING_ANSWER, DECISION_PENDING, COMPLETED }`；`SubmissionIntent { ANSWER, DONT_KNOW, SKIP }`。
- `DynamicSubmitRequest(UUID requestId, int questionIndex, long expectedVersion, SubmissionIntent submissionIntent, String answer)`；ANSWER 要求非空，其他意图允许 null，但不能伪造用户说过的文本。
- `DynamicMainQuestion(String slotId, String question, String type, String category, String topicSummary, List<AssessmentTarget> assessmentTargets, String difficulty, List<String> profileFocus)`；`AssessmentTarget(String id, String description)`。
- `DynamicRuntimeSnapshot(String sessionId, long version, DynamicPhase phase, int plannedMainQuestionCount, int currentMainOrdinal, int completedMainCount, int usedFollowUps, int totalQuestions, int mainQuestionCount, int followUpQuestionCount, InterviewQuestionDTO currentQuestion, UUID pendingDecisionId, String noticeCode)`；序号为一基展示，完成时 currentQuestion 为空，阶段是唯一完成信号。
- `DynamicSubmissionReceipt(UUID requestId, UUID decisionId, DynamicRuntimeSnapshot runtime)`；重复请求保存同一原提交结果标识，但返回最新 runtime，不要求返回过时快照。
- `FollowUpModelDecision(Action action, Reason reasonCode, String reasonSummary, List<String> coveredTargetIds, List<String> missingTargetIds, String followUpQuestion)`；Action 为 FOLLOW_UP/NEXT_MAIN，Reason 为 IMPORTANT_GAP/SUFFICIENT/NO_USEFUL_FOLLOW_UP/USER_DONT_KNOW。
- `DynamicGroupEvaluation(String slotId, Integer score, String evidenceSummary, List<Integer> supportingQuestionIndexes, List<String> observedTargetIds)`；单项反馈仍使用原始 questionIndex。提交意图/组结束原因必须进入评价输入，不能仅把空答案传给旧 QaRecord。
- 所有持久化 owner 来源是服务端可信用户/会话，不相信请求或模型；时间使用注入 Clock，deadline 以 Instant 保存。

---

### Task 1: 持久化执行模式、运行状态、提交与任务唯一性

**Files:** Create `D/model/InterviewExecutionMode.java`、`DynamicPhase.java`、`SubmissionIntent.java`、`DynamicGroupEndReason.java`、`DynamicMainQuestion.java`、`AssessmentTarget.java`、`DynamicInterviewRuntimeEntity.java`、`FollowUpDecisionEntity.java`、`DynamicEvaluationOutboxEntity.java`；Create `D/repository/DynamicInterviewRuntimeRepository.java`、`FollowUpDecisionRepository.java`、`DynamicEvaluationOutboxRepository.java`；Modify `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`、`InterviewAnswerEntity.java`；Create `app/src/main/resources/db/migration/V11__create_dynamic_interview_runtime.sql`；Test `DT/DynamicRuntimePersistenceTest.java`、`DT/DynamicMigrationPostgresTest.java`。

**Interfaces:** runtime 每会话唯一、带 @Version；仓库 `findBySessionIdForUpdate(String): Optional<DynamicInterviewRuntimeEntity>`。决策每正式源答案唯一，保存请求 ID/请求指纹、上下文、deadline、尝试令牌和应用版本；请求 ID 在同一归属内唯一并防止跨会话复用。评价 outbox 每会话/冻结版本唯一；主问题组结束原因存 runtime 的版本化 JSON 或单独实体，实施者选择但不能仅存在内存。

- [ ] 写失败测试 `legacyV2HasFixedExecutionMode`，断言缺省模式为 LEGACY_FIXED；`duplicateSourceAnswerAndRequestAreRejected` 断言源答案和请求复用被数据库拒绝；`crossOwnerRuntimeAndDeleteCascade` 断言复合归属 FK 和删除清理。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicRuntimePersistenceTest' --no-daemon`，确认缺失新行为为 RED。
- [ ] 实现以上类型/仓库/迁移。runtime 保存主问题快照、政策(2/N/30秒)、当前slot/实际索引/预算；显式提交意图与 requestId 可空兼容旧数据。决策/任务 FK、CHECK/唯一约束必须在 PostgreSQL 实际成立。
- [ ] 重跑聚焦测试 GREEN；Postgres 测试连接仅允许显式隔离测试库，未配置时明确跳过并列入最终验收未完成项，绝不回退到产品 `.env`。迁移编号实施前重新确认无冲突。
- [ ] 只提交本任务路径：`feat: persist dynamic interview execution state`。

### Task 2: 动态主问题池与考查目标生成

**Files:** Create `D/service/DynamicMainQuestionGenerator.java`、`DynamicInterviewCreationService.java`、`DynamicInterviewProperties.java`；Create `app/src/main/resources/prompts/interview/dynamic-main-questions.st`；Modify `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java`、`planning/service/InterviewQuestionPlanPersistenceService.java`、`app/src/main/resources/application.yml`；Test `DT/DynamicMainQuestionGeneratorTest.java`、`DT/DynamicInterviewCreationTest.java`。

**Interfaces:** `DynamicMainQuestionGenerator.generate(String provider, SkillDTO skill, String resumeText, QuestionPlan plan): List<DynamicMainQuestion>`；`DynamicInterviewCreationService.create(CreateInterviewRequest request): DynamicRuntimeSnapshot`。Task1 定义的主问题目标快照由新生成器保存；创建服务使用现有画像/历史/规划上下文，不复制为第二套分配规则。

- [ ] 写失败测试 `eightSlotsGenerateEightMainsWithoutStaticFollowUps`、`requiresTwoToFourUniqueTargets`、`creationOnlyPublishesFirstMainAtIndexZero`、`invalidOutputDiscardsShell`，断言槽顺序与V2计划不变、未来主问题不进入 questionsJson、目标不向客户端公开。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicMainQuestionGeneratorTest' --tests '*DynamicInterviewCreationTest' --no-daemon`，确认 RED。
- [ ] 实现一次模型调用生成主问题及目标；校验 slot/category 与原计划匹配，目标ID在组内唯一，失败只走统一有限重试/已有固定分配回退约束。事务外生成，短事务保存完整池和第一个实际问题；失败清理壳记录，不发布半成品。
- [ ] 重跑 GREEN，同时运行现有 `*QuestionPlanningServiceTest`、`*QuestionPlanValidatorTest`。新增配置 `app.interview.dynamic.enabled=false`、`max-follow-ups-per-main=2`、`decision-timeout=30s`，整场上限固定取N并保存快照。
- [ ] 提交 `feat: generate immutable dynamic interview main pool`。

### Task 3: 纯推进政策与事务性作答状态机

**Files:** Create `D/dto/DynamicSubmitRequest.java`、`DynamicRuntimeSnapshot.java`、`DynamicSubmissionReceipt.java`；Create `D/service/DynamicAdvancePolicy.java`、`DynamicInterviewCommandService.java`、`DynamicInterviewQueryService.java`；Test `DT/DynamicAdvancePolicyTest.java`、`DT/DynamicInterviewSubmissionTest.java`。

**Interfaces:** `DynamicInterviewCommandService.submit(UUID ownerId, String sessionId, DynamicSubmitRequest request): DynamicSubmissionReceipt`；`complete(UUID ownerId, String sessionId, long expectedVersion): DynamicRuntimeSnapshot`；`saveDraft(UUID ownerId, String sessionId, int questionIndex, long expectedVersion, String answer): void`；`DynamicInterviewQueryService.get(UUID ownerId, String sessionId): DynamicRuntimeSnapshot`。事务 Service 使用 Task1 行锁；纯 policy 不查DB/不调模型；状态推进实现集中复用，供 Task5 应用/超时调用。

- [ ] 写失败测试 `answerCreatesSinglePendingDecision`、`dontKnowAndSkipEndGroupWithoutModel`、`twoPerGroupAndNPerSession`、`lastMainWaitsForDecision`、`duplicateRequestReturnsLatestRuntime`、`staleDraftCannotOverwriteSubmittedAnswer`、`sameRequestDifferentPayloadRejected`。断言每次追加索引末尾+1、追问 parentIndex 始终为主问题、原问题不重排。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicAdvancePolicyTest' --tests '*DynamicInterviewSubmissionTest' --no-daemon`，确认 RED。
- [ ] 实现事务保存答案+唯一决策+deadline+阶段；显式退出或无预算直接关闭组、发布下一主问题。结束整场同事务冻结上下文、取消未应用任务、写唯一评价outbox；query纯读，无模型/状态推进副作用。
- [ ] GREEN 后加入 `completeWhileDecisionPendingFencesLateAppend` 和跨用户负例；同请求已成功的重试优先按指纹去重，不能先因旧 expectedVersion 拒绝合法网络重试。
- [ ] 提交 `feat: add versioned dynamic interview state machine`。

### Task 4: 有边界的结构化追问决策

**Files:** Create `D/model/FollowUpModelDecision.java`、`FollowUpContext.java`；Create `D/service/FollowUpDecisionService.java`、`FollowUpDecisionValidator.java`；Create `app/src/main/resources/prompts/interview/dynamic-follow-up.st`；Test `DT/FollowUpDecisionServiceTest.java`、`DT/FollowUpDecisionValidatorTest.java`。

**Interfaces:** `FollowUpDecisionService.decide(String provider, FollowUpContext context, Instant deadline): FollowUpModelDecision`；`FollowUpDecisionValidator.validate(FollowUpContext context, FollowUpModelDecision decision): void`。FollowUpContext 仅当前主问题组快照、目标、所有正式问答/意图、相关画像摘要与剩余预算，引用 Task2 目标ID。

- [ ] 写失败测试 `sufficientAnswerReturnsNextMain`、`importantGapProducesFollowUp`、`priorAnswersContributeToCoverage`、`naturalDontKnowDiffersFromNegativeSentence`、`rejectsUnknownTargetAndExactRepeatedQuestion`、`answerPromptInjectionCannotChangePlan`。用模型替身验证输入/输出约束，不能把替身断言当真实模型质量证明。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*FollowUpDecisionServiceTest' --tests '*FollowUpDecisionValidatorTest' --no-daemon`，确认 RED。
- [ ] 实现 registry/invoker 和 .st；模型建议不能修改预算/类别/目标。FOLLOW_UP 必须有合法缺口且题面1–1000字，NEXT_MAIN题面为空，理由摘要最多500字；目标ID不重复且已覆盖/缺失集合不交叠。答案长度设定集中配置，过长明确校验失败而非静默截断破坏证据。
- [ ] 重跑 GREEN；模型调用及结构化修复受剩余deadline约束，若现有invoker不支持deadline，只做通用能力最小扩展并增加 `StructuredOutputInvokerDeadlineTest`；超时释放消费者等待，迟到结果仍由Task5 fence，不能仅设置前端等待时间。
- [ ] 提交 `feat: add bounded answer-aware follow-up decisions`。

### Task 5: Redis Stream、补投、租约与评价可靠投递

**Files:** Create `D/messaging/FollowUpDecisionMessage.java`、`FollowUpDecisionProducer.java`、`FollowUpDecisionConsumer.java`；Create `D/service/DynamicDecisionDispatchService.java`、`DynamicDecisionWorker.java`、`DynamicDecisionRecoveryService.java`、`DynamicEvaluationDispatchService.java`；Modify `app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java`、`app/src/main/java/interview/guide/modules/interview/messaging/InterviewEvaluationTaskPublisher.java`、`app/src/main/java/interview/guide/modules/interview/messaging/rabbit/InterviewEvaluationRabbitProducer.java`；Test `DT/DynamicDecisionDeliveryTest.java`、`DT/DynamicDecisionRecoveryTest.java`、`DT/DynamicDecisionRedisIntegrationTest.java`。

**Interfaces:** `DynamicDecisionWorker.process(UUID decisionId): void`；`DynamicDecisionRecoveryService.recover(Instant now): void`；publisher新增 `publish(String sessionId, UUID messageId): PublishReceipt` 保留旧 `publish(String)`；动态评价outbox重投使用同一messageId。生产/消费继承通用Stream模板；message只含decisionId，不传答案和owner。

- [ ] 写失败测试 `commitThenPublishFailureIsRecovered`、`redeliveryDoesNotAppendTwice`、`expiredLeaseRejectsOldAttempt`、`queueWaitCountsTowardThirtySeconds`、`timeoutMovesNextWithoutBudgetCharge`、`deletedSessionIsAcked`、`evaluationRetryUsesStableMessageId`。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicDecisionDeliveryTest' --tests '*DynamicDecisionRecoveryTest' --no-daemon`，确认 RED。
- [ ] 实现短事务认领+令牌、事务外调用Task4、短事务条件应用；deadline后结果不可应用，重投不能重置deadline。恢复器每1秒扫描有界批次的超时任务，每2秒补投待投递任务，避免循环查库和热重试；定时任务重叠依赖DB认领安全。
- [ ] GREEN并运行隔离真实Redis测试，证明已ACK消息丢失也能靠DB超时收敛；队列默认三次重试不得延长deadline。账号失效/会话删除/提前完成在调用前和应用前都校验，过期任务ACK。固定路径publisher和Rabbit行为回归不变。
- [ ] 提交 `feat: recover dynamic follow-up and evaluation delivery`。

### Task 6: 动态API、模式协商和旧缓存隔离

**Files:** Create `D/DynamicInterviewController.java`；Modify `app/src/main/java/interview/guide/modules/interview/InterviewController.java`、`model/CreateInterviewRequest.java`、`model/InterviewSessionDTO.java`、`service/InterviewSessionService.java`、`infrastructure/redis/InterviewSessionCache.java`；Test `DT/DynamicInterviewApiTest.java`、`DT/DynamicLegacyCompatibilityTest.java`。

同任务修改会话元数据映射：`app/src/main/java/interview/guide/modules/interview/model/SessionListItemDTO.java`、`InterviewHistoryItemDTO.java`、`InterviewDetailDTO.java`，以及 `app/src/main/java/interview/guide/modules/interview/service/InterviewHistoryService.java`、`app/src/main/java/interview/guide/modules/resume/service/ResumeHistoryService.java`、`app/src/main/java/interview/guide/infrastructure/mapper/InterviewMapper.java`。本文并列裸文件名均继承前一个同组文件目录；带 `model/`、`service/` 的相对项继承当前业务模块目录，`infrastructure/` 继承 `app/src/main/java/interview/guide/`。

**Interfaces:** 创建请求新增可空 executionMode，旧Java构造器委托LEGACY_FIXED；创建/恢复响应增加可空 executionMode，缺省固定。新增 `GET /api/interview/sessions/{sessionId}/dynamic`、`POST .../dynamic/answers`、`PUT .../dynamic/draft`、`POST .../dynamic/complete`，返回Result封装Task3 DTO；complete请求含expectedVersion。

创建接口保留 InterviewSessionDTO 外形：Task2创建结果转换为仅含已发布题目的DTO并标明executionMode，新前端据此请求dynamic运行快照。历史/详情/会话列表补充executionMode与plannedMainQuestionCount（固定模式可空），列表通过批量读取runtime元数据获取，不新增逐条查询。

- [ ] 写失败测试 `missingModeCreatesFixed`、`declaredDynamicUsesNewFlow`、`oldAnswerEndpointRejectsDynamic`、`cachedLegacyDtoCannotDriveDynamic`、`getRuntimeHasNoSideEffects`、`csrfAndOwnershipEnforced`；旧API不得对动态会话运行 index+1，也不能绕过新草稿/完成控制。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicInterviewApiTest' --tests '*DynamicLegacyCompatibilityTest' --no-daemon`，确认 RED。
- [ ] 实现模式委托与@RateLimit，dynamic GET直接读DB状态，动态写后缓存清理失败不回滚。旧入口的question/answers/draft/complete/report/恢复均检查持久化模式并正确分流或明确拒绝；暂不打开动态创建开关。
- [ ] GREEN并运行现有文字面试归属与旧V1/V2兼容测试，DTO中不泄露主问题池/目标/内部错误。
- [ ] 提交 `feat: expose dynamic interview runtime API`。

### Task 7: 主问题组正式评价、可空分数与报告导出

**Files:** Create `D/model/DynamicGroupEvaluation.java`、`DynamicInterviewEvaluationSnapshot.java`；Create `D/service/DynamicInterviewEvaluationService.java`、`DynamicReportAggregation.java`；Create `app/src/main/resources/prompts/interview/dynamic-evaluation.st`；Modify `app/src/main/java/interview/guide/modules/interview/service/AnswerEvaluationService.java`、`InterviewPersistenceService.java`、`InterviewSessionService.java`、`model/InterviewReportDTO.java`、`model/InterviewDetailDTO.java`、`infrastructure/export/PdfExportService.java`、`infrastructure/mapper/InterviewMapper.java`；Test `DT/DynamicInterviewEvaluationTest.java`、`DT/DynamicReportPersistenceTest.java`、`DT/DynamicReportExportTest.java`。

**Interfaces:** `DynamicInterviewEvaluationService.evaluate(String provider, DynamicInterviewEvaluationSnapshot snapshot): InterviewReportDTO`；`DynamicReportAggregation.aggregate(List<DynamicGroupEvaluation> groups):` 含可空总分/类别分的聚合结果record（同文件命名AggregationResult）。InterviewReportDTO及嵌套score改Integer，新增可空groupEvaluations和executionMode，保留旧构造入口；共享语音EvaluationReport不改为动态语义。组评价也保存独立版本快照供记忆读取，不从导航reason冒充正式评价。

- [ ] 写失败测试 `oneGroupWithTwoFollowUpsHasOneWeight`，用组分40/80断言总分60而非按作答项加权；`allSkipHasNullScore`、`dontKnowWithoutTextIsObservedGap`、`systemFailureDoesNotInventZeroAnswer`、`oldReportJsonRoundTripsUnchanged`。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicInterviewEvaluationTest' --tests '*DynamicReportPersistenceTest' --tests '*DynamicReportExportTest' --no-daemon`，确认 RED。
- [ ] 实现模型整组评价与逐项反馈：模型不能评价未发布未来主问题；未观察项score=null，明确不会依实际考查点形成不足评价，不预设所有情况固定0分。校验所有slot/目标/来源索引、分数范围。后端按有效组平均并四舍五入到整数，类别questionCount表示有效主问题组数量（动态报告提供明确说明），totalQuestions仍是实际作答项数。
- [ ] 持久化报告/组评价/逐项反馈与评价完成状态同事务；组评价以带schemaVersion的报告扩展字段保存并冻结，Task8只读取正式完成的快照；保留精确reportJson幂等，禁止重算旧报告。Pdf和详情对于null显示“未评估”，0仍为真实0；旧报告缺新增字段可读。InterviewMapper现有nullScoreToZero仅保留旧模式语义，动态映射不得套用。跨所有映射调用点检查Integer自动拆箱风险。
- [ ] GREEN后重跑既有精确报告、导出和语音测试；提交 `feat: evaluate dynamic interviews by main question group`。

### Task 8: 组级记忆证据、可追溯来源与建议确认

**Files:** Create `D/service/DynamicAbilityEvidenceSourceService.java`、`D/model/DynamicGroupEvidenceSource.java`；Create `app/src/main/resources/db/migration/V12__store_dynamic_ability_evidence_sources.sql`；Modify `app/src/main/java/interview/guide/modules/memory/service/AbilityEvidenceDerivationService.java`、`AbilityProfileCommandService.java`、`model/UserAbilityEvidenceEntity.java`、`dto/AbilityEvidenceResponse.java`、`mapper/AbilityMemoryMapper.java`；Test `DT/DynamicAbilityEvidenceTest.java`、`DT/DynamicAbilityProposalDecisionTest.java`。

**Interfaces:** `DynamicAbilityEvidenceSourceService.sources(String sessionId): List<DynamicGroupEvidenceSource>`；DynamicGroupEvidenceSource同目录model独立record，含slotId、主问题答案锚点、支持答案ID列表、categoryKey、有效组分数、主题/评价摘要/发生时间。来源必须引用同会话/同owner/同类别答案；扩展支持来源表与唯一约束，仍沿用主问题答案作为existing evidence唯一锚点。

- [ ] 写失败测试 `threeAnswersCreateOneEvidence`、`emptyDontKnowCreatesGapEvidence`、`skipAndSystemMissingTargetsCreateNoWeakEvidence`、`categoryProposalAveragesGroups`、`retryDoesNotDuplicate`、`acceptEditRejectRetainSupportingSources`、`foreignAnswerSourceRejected`。
- [ ] 运行 `.\gradlew.bat :app:test --tests '*DynamicAbilityEvidenceTest' --tests '*DynamicAbilityProposalDecisionTest' --no-daemon`，确认 RED。
- [ ] 实现仅在正式报告持久化完成后读取组评价；动态路径不套原非空文本validEvidence，也不把追问逐条平均。来源组无有效score不产证据，已有回答有有效证据仍保留；建议摘要限定具体观察点，不能把未考查部分描述为不会。确认动作沿用现有更新画像算法，不新增直接写画像路径。
- [ ] 迁移在隔离Postgres验证支持来源归属及删除清理；GREEN并重跑原记忆生命周期/画像查询/confirmedSessionCounts测试，组计数不伪装多场确认。
- [ ] 提交 `feat: derive traceable group-level ability evidence`。

### Task 9: 前端动态流程、意图区分、计数及未知评分

**Files:** Create `frontend/src/types/dynamicInterview.ts`、`api/dynamicInterview.ts`、`utils/dynamicInterviewState.ts`、`utils/dynamicInterviewState.test.ts`、`hooks/useDynamicInterview.ts`、`components/DynamicInterviewPanel.tsx`；Modify `frontend/src/api/interview.ts`、`api/history.ts`、`types/interview.ts`、`pages/InterviewPage.tsx`、`InterviewHubPage.tsx`、`InterviewHistoryPage.tsx`、`components/UnifiedInterviewModal.tsx`、`InterviewPanel.tsx`、`InterviewDetailPanel.tsx`、`AbilityProposalCard.tsx`、`utils/score.ts`；Create `frontend/src/utils/dynamicInterviewPresentation.test.ts`。

**Interfaces:** `dynamicInterviewApi.get/submit/saveDraft/complete`对应Task6；`useDynamicInterview(sessionId)`封装2秒轮询、版本防倒退、请求ID重试复用、卸载取消；纯状态函数只接收单调版本快照，不以totalQuestions判断完成。动态面板通过新hook运行，旧面板不复制动态分支。

- [ ] 写失败测试 `pendingIsNotCompleted`、`lateLowerVersionIgnored`、`retryKeepsRequestId`、`sessionChangeDropsOldPoll`、`dontKnowAndSkipSendDistinctIntents`、`unknownScoreNotZero`、`mainProgressNotAnswerCount`；以纯reducer/请求客户端替身测试真实逻辑，不只检查源码字符串。
- [ ] 运行 `cd frontend; pnpm test`，确认 RED。沿用现有环境所需的进程级 `pnpm_config_verify_deps_before_run=warn`，不安装/批准依赖脚本。
- [ ] 实现轮询等待、显式“我不会”“跳过本题”及确认提示、禁重复提交、等待期间提前交卷；网络断线显示可恢复状态而非假定未提交。保存草稿只能对WAITING_ANSWER当前版本，不让延迟草稿覆盖已提交。
- [ ] 所有文字创建/恢复入口透传模式，包括通用、简历与复用弹窗；接入Task7可空评分和组说明、Task8来源展示，历史平均分分母排除无有效评分的面试，不能以`score || 0`混入未知值。创建文案为“主问题数量；追问按回答动态生成，最多每题2次且整场不超过主问题数”；旧会话保持原计数与流程。
- [ ] `pnpm test`、`pnpm run build` GREEN；使用隔离GET/POST替身页面验证等待/故障/0与null分数/旧响应，不动真实用户数据。动态启用仍等Task10。
- [ ] 提交 `feat: present resumable dynamic interview flow`。

### Task 10: 跨层回归、真实基础设施验收与受控启用

**Files:** Create `DT/DynamicInterviewJourneyTest.java`、`DT/DynamicInterviewPostgresConcurrencyTest.java`、`DT/DynamicInterviewEndToEndCompatibilityTest.java`；Create `docs/superpowers/plans/2026-10-29-dynamic-interview-follow-up-verification.md`；Modify `app/src/main/resources/application.yml`（只在验收通过后将新能力默认开启，旧客户端仍fixed）。

**Interfaces:** 只消费Task1..9；Postgres/Redis测试使用显式隔离schema/namespace及测试账号。若环境缺失，保留开关关闭，报告缺项，不能以H2通过宣称并发/迁移已验收。

- [ ] 写跨层回归：N=3最小完整流程，8主+0/多追问流程，最终主问题充分/不会/失败，等待交卷；断言计划不可变、原索引稳定、提交/追问/组评价/记忆全链路一致。
- [ ] 用真实隔离Postgres双连接屏障测试提交与提交、应用与超时、应用与交卷/删除竞争，断言最多一次发布/完成；配合真实Redis证明重投/断线恢复。不得为测试删除广泛用户数据或停止其他项目容器。
- [ ] 运行 `.\gradlew.bat :app:compileJava`、`.\gradlew.bat :app:test --no-daemon`；如已知512MiB测试堆不足，使用工作区外/ignored临时init脚本设1GiB并明确记录，不改产品堆配置。统计JUnit XML的执行/跳过/失败，原49项跳过不可声称已覆盖。
- [ ] 运行 `cd frontend; pnpm test; pnpm run build`，逐条确认退出码；记录原有构建警告，不借机更新依赖。检查diff无密钥、用户既有改动、旧报告重算或未授权真实LLM调用。
- [ ] 按用户既有Subagent-driven选择完成各任务审查及最终整分支审查；修复先加回归。所有闭环和真实基础设施验收GREEN后才默认启用新客户端声明的动态模式，新增开关路由测试并重跑受影响测试。
- [ ] 写验证报告区分模型替身/真实基础设施/浏览器证据、覆盖和限制；提交 `test: verify dynamic interviews and legacy compatibility`。交付时不自动合并、推送、迁移真实库或重启前后端；真实模型体验验收需用户另行授权。

## Self-review

- Spec 1–4 → Task1/2/6，5–7 → Task3/4/5，8 → Task7/8，9 → Task6/9，10 → 所有任务及Task10，11 → Task10；无遗漏的独立子项目。
- 五项Review Focus各落到具名测试；真实Postgres/Redis缺失不得静默以跳过充当验收。
- record/接口跨任务命名一致；provider+deadline由Task4提供，认领/应用由Task5围绕Task3推进规则实现；Task7正式评价是Task8唯一能力证据来源。
- 每任务包含RED、实现、GREEN与独立提交；任务只修改自己拥有的文件，跨任务文件修改串行，不合并用户暂存内容。
- 此轮只写设计/计划，不执行其中任何产品步骤。实施前依据当时仓库状态确认迁移编号和文件冲突。

## Execution handoff

用户此前已选择 Subagent-driven，保留该方式：用户复核本计划后，再读取对应实施技能，建立隔离工作区，逐任务实施与审查。
设计于2026-10-29已获批准；本计划仍等待复核，不因设计批准而自动开始写业务代码。
