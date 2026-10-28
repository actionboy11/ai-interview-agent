# Coverage-first Question Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 综合面试先覆盖合法分类再用剩余题量强化，同时明确主问题、追问、总作答项。

**Architecture:** 以无I/O的共用覆盖策略服务固定和自适应规划，并在计划校验器中检查同一覆盖不变量。
只读统计实际题目列表，扩展DTO但保持原作答索引和totalQuestions含义；前端集中格式化计数。

**Tech Stack:** Java 21 / Spring Boot / Jackson / MapStruct / JUnit 5 / Mockito / AssertJ；React / TypeScript / Vite / Node test。

**Spec:** `docs/superpowers/specs/2026-10-28-coverage-first-question-planning-design.md`

## Global Constraints

- 主问题预算来自questionCount；totalQuestions继续代表含追问的总作答项，不改答案索引、评分或报告JSON。
- 新计划version=2；旧计划、三场已有面试、能力画像及答案不得重写。
- 覆盖逻辑适用于所有Skill，不硬编码Java六类；无画像和故障回退也必须覆盖。
- 不增加模型调用，不做语义去重、动态追问、难度政策调整、专项模式或数据库计数迁移。
- 事务只放Service层；模型调用保持在事务外；返回Result，不返回Entity。
- 仅提交本任务拥有的文件/片段，保留用户已有13处修改、已暂存LRUCache等；不清理用户临时文件。
- 启动前后端、执行会产生模型费用的端到端面试和变更实际数据均不属于自动验证步骤。

## Review Focus

1. 零追问/两追问/缺失追问：统计必须基于真实题目，不能默认乘二（Task 2/3）。
2. 旧题目缺失或损坏、旧API缺字段：计数未知而不是0，不影响旧总项展示（Task 2/3）。
3. 自定义分类、必考超预算、分类很少：保证确定性和容量可行，不能死循环（Task 1）。
4. 画像故障或模型结构失败后的固定回退：不退回漏分类的旧分配（Task 1/4）。
5. 既有面试恢复/详情/评分：只增加展示字段，不改存储计划、题号、分数或用户归属（Task 2/4）。

---

### Task 1: Version 2 覆盖优先策略与校验

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/planning/QuestionCoveragePolicy.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/planning/AdaptiveAllocationPolicy.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/planning/QuestionPlanValidator.java`
- Test: `app/src/test/java/interview/guide/modules/interview/planning/AdaptiveAllocationPolicyTest.java`
- Test: `app/src/test/java/interview/guide/modules/interview/planning/QuestionPlanningServiceTest.java`
- Create test: `app/src/test/java/interview/guide/modules/interview/planning/QuestionCoveragePolicyTest.java`
- Create test: `app/src/test/java/interview/guide/modules/interview/planning/QuestionPlanValidatorTest.java`

**Interfaces:**
- Consumes: `QuestionPlanningCommand`, `AbilityProfileResponse`；不改变规划调用签名。
- Produces: `QuestionCoveragePolicy.requiredCategories(QuestionPlanningCommand): List<SkillCategoryDTO>`，先必考，按优先级、历史覆盖、key稳定排序截取min(N,C)类；N>=C返回全部。
- Produces: `QuestionCoveragePolicy.categoryCap(QuestionPlanningCommand): int`，max(ceil(N*0.3),ceil(N/C))。
- Produces: 新的 `QuestionPlan(version=2, ...)`；旧version=1读取和执行路径不变。

- [ ] 写失败测试：Java六类6题每类一道；8题历史覆盖PROJECT=3/JAVA=2/MYSQL=2/REDIS=2/SPRING=2/SYSTEM_DESIGN_SCENARIO=1，PROJECT为弱项、MYSQL与REDIS为优势，仍覆盖全部六类，不能产生项目3+系统设计3。
- [ ] 写失败测试：固定/无画像/画像查询异常回退同样覆盖；分类顺序打乱结果相同；N<C按必考→核心→普通选择；必考数>N抛BusinessException；单类/3..20题/无弱项/无优势/无未知类预算守恒。
- [ ] 写失败测试：version=2缺任一应覆盖类、重复slotId、非法类别、超容量、无事实简历题均拒绝；version=1不套用新的覆盖要求；不在校验器中修改计划。
- [ ] 运行 `./gradlew :app:test --tests '*QuestionCoveragePolicyTest' --tests '*AdaptiveAllocationPolicyTest' --tests '*QuestionPlanValidatorTest' --tests '*QuestionPlanningServiceTest' --no-daemon`，确认因旧行为而失败。
- [ ] 实现共用覆盖策略。先安排requiredCategories，首题必考标MANDATORY，其余BASELINE，slot总数不超过N；重复categoryKey继续按现有参数校验拒绝，不能让重复配置产生双重必考。
- [ ] 只对剩余R用4:3:1:1分配基础/弱项/探索/优势预算，以最大余数法计算，余数相同按该顺序决胜；无对应候选的权重先并入基础。分配时保持本场容量，未用名额归基础补齐。
- [ ] 基础追加排序先本场已分配数、再历史覆盖、方向优先级、key；弱项保留等级/置信度/最近评价排序，优势使用原有有效排序，不能让历史少考类连占全部基础题。
- [ ] 固定路径复用覆盖阶段，剩余全部基础追加；保留FIXED模式与fallbackReason。分类确定后按现有60%/40%资格规则分配有事实支撑的简历来源，不允许简历目标挤掉覆盖。
- [ ] 更新旧测试中与批准设计冲突的用途比例断言，不删除仍有效的资格、难度、来源、幂等断言；重跑定向测试全部通过。
- [ ] 只提交本任务文件：`feat: prioritize category coverage in interview plans`。

### Task 2: 后端统一只读题数统计与DTO

**Files:**
- Create: `app/src/main/java/interview/guide/modules/interview/model/InterviewQuestionCountsDTO.java`
- Create: `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionCountService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/SessionListItemDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewHistoryItemDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewDetailDTO.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewHistoryService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- Modify: `app/src/main/java/interview/guide/infrastructure/mapper/InterviewMapper.java`
- Modify as needed: `app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java`（列表与简历历史映射调用点）
- Create test: `app/src/test/java/interview/guide/modules/interview/service/InterviewQuestionCountServiceTest.java`
- Create test: `app/src/test/java/interview/guide/modules/interview/service/InterviewQuestionCountsResponseTest.java`

**Interfaces:**
- Produces: `InterviewQuestionCountsDTO(Integer mainQuestionCount, Integer followUpQuestionCount)`，两个null表示未知。
- Produces: `InterviewQuestionCountService.fromJson(String): InterviewQuestionCountsDTO` 与 `fromQuestions(List<InterviewQuestionDTO>): InterviewQuestionCountsDTO`；服务只解析、不访问Repository。
- DTO新增同名可空顶层字段，保留旧字段、旧Java构造器兼容入口（委托到新构造器）。计数以实际题目为准。

- [ ] 写失败测试：6+6、8+8、6+0、6+12、部分缺追问；null/空白/损坏JSON为未知，合法[]为0+0，旧题目未提供isFollowUp时按兼容主问题处理，非法数组元素不产生伪造计数。
- [ ] 写失败测试：会话创建/恢复、会话列表、简历关联历史、详情四类响应均提供正确计数；损坏列表依旧返回旧totalQuestions而不是抛500或猜主问题数。
- [ ] 写失败测试：调用列表统计不额外访问Repository、不触发答案集合懒加载；DTO JSON仍含原字段，currentQuestionIndex与totalQuestions不变。
- [ ] 运行 `./gradlew :app:test --tests '*InterviewQuestionCount*' --no-daemon`，确认失败。
- [ ] 实现共用统计和DTO字段，Service负责JSON解析，MapStruct负责映射；不要在每个DTO或前端重复解析规则。细节响应已解析的题目应复用，避免再解析一次。
- [ ] 更新所有DTO构造/映射调用点，历史列表基于本次已读Entity.questionsJson统计，不增加逐条数据库查询；保留现有生成中会话过滤与归属校验。
- [ ] 重跑定向测试及 `./gradlew :app:compileJava`，全部通过；只提交拥有的修改：`feat: expose main and follow-up interview counts`。

### Task 3: 前端题数文案与追问归属

**Files:**
- Modify: `frontend/src/types/interview.ts`
- Modify: `frontend/src/api/interview.ts`
- Modify: `frontend/src/api/history.ts`
- Create: `frontend/src/utils/interviewQuestionCounts.ts`
- Create test: `frontend/src/utils/interviewQuestionCounts.test.ts`
- Modify: `frontend/src/pages/InterviewHubPage.tsx`
- Modify: `frontend/src/components/UnifiedInterviewModal.tsx`
- Modify: `frontend/src/pages/InterviewHistoryPage.tsx`
- Modify: `frontend/src/components/InterviewChatPanel.tsx`
- Modify: `frontend/src/pages/InterviewPage.tsx`
- Modify: `frontend/src/components/InterviewPanel.tsx`
- Modify: `frontend/src/components/InterviewDetailPanel.tsx`

**Interfaces:**
- Consumes: DTO可空/可缺的mainQuestionCount、followUpQuestionCount，保留totalQuestions。
- Produces: `formatInterviewQuestionCounts(input: {totalQuestions?: number | null; mainQuestionCount?: number | null; followUpQuestionCount?: number | null}): string`。
- 文案：已知计数为“6道主问题 + 6道追问，共12个作答项”；未知细分为“12个作答项”；全部未知为“题数未知”。
- InterviewQuestion增加兼容字段 `isFollowUp?: boolean`、`parentQuestionIndex?: number | null`。

- [ ] 写失败测试：6+6、零追问、两追问、null/undefined计数、旧响应、非法负数和细分总和不一致；不可信细分降级总项而不是展示矛盾数值；真实0可展示。
- [ ] 写失败测试：追问归属辅助函数以parentQuestionIndex关联非连续题号，缺元数据时不假设奇偶配对；不重排、不重编号原始作答项。
- [ ] 运行 `cd frontend; pnpm test` 确认新测试失败；实现formatter及归属辅助逻辑后重跑通过。
- [ ] 创建入口改“主问题数量”，说明追问另计且按实际生成；不硬编码必定一追问，也不为此增加额外模型调用或新配置API。
- [ ] 历史聚合项透传新字段，历史/详情/简历关联列表复用formatter；作答页进度改“作答项”，分母仍totalQuestions。
- [ ] 详情通过questions已有元数据标注“第X道主问题的追问”；保持展开、定位、答案匹配和原始题号兼容。
- [ ] 运行 `pnpm test` 与 `pnpm run build`；浏览器使用已存在的三场只读验证展示，不创建新面试，不触发AI评价或修改用户密码。
- [ ] 只提交拥有的前端改动：`feat: clarify interview main and follow-up counts`。

### Task 4: 全链路回归、独立审查与交付

**Files:**
- Modify tests as needed: `app/src/test/java/interview/guide/modules/ownership/TextInterviewOwnershipIntegrationTest.java`
- Modify tests as needed: `app/src/test/java/interview/guide/modules/interview/planning/QuestionPlanningServiceTest.java`
- Create: `docs/superpowers/plans/2026-10-28-coverage-first-question-planning-verification.md`

**Interfaces:** 仅消费Task1..3成品，不新增产品行为。

- [ ] 加回归断言：现有version=1计划读取/总结/继续作答保持原JSON和题序，新增计数不能触发重新规划；新version=2回退也覆盖六类。
- [ ] 以历史题目和创建时画像作为测试夹具重放第三场，而非使用后来更新的画像；不读取真实密码或把用户原始回答写入夹具，使用最小分类/分数/时间数据。
- [ ] `./gradlew :app:test --no-daemon` 完整运行，记录通过/失败/跳过；`cd frontend; pnpm test; pnpm run build` 记录输出。
- [ ] 独立审查：覆盖条件、回退、排序确定性、来源事实、旧DTO兼容、未知计数、N+1查询、用户原修改保护；问题修复须先写回归测试。
- [ ] 对照git diff确保无迁移、无生产数据更改、无旧面试重写、无密钥进入版本控制、无用户暂存改动被混入提交。
- [ ] 写验证报告与限制，标明本批不保证语义去重、动态深度或题面质量；向用户报告完成与测试证据，不自动合并、推送或重启服务。

## Self-review

- 覆盖设计的分配、回退、来源、版本、边界分别落在Task1；统计与兼容落在Task2；全部前端入口落在Task3；历史不变与整体验证落在Task4。
- Review Focus五项均有对应红绿测试步骤。
- 所有新的对外字段同名，未知使用null/undefined；Java构造兼容与前端旧响应兼容分别验证。
- 现有规划比例测试需要针对新批准策略更新，而非维持与覆盖优先冲突的旧预算断言。

## Execution handoff

沿用用户此前选择的Subagent-driven执行方式；本计划等待用户复核确认后，再读取对应执行技能并建立隔离工作区。
执行前应检查当前HEAD、工作区差异和已有附件，选择可恢复的隔离方式，不丢弃主工作区未提交改动。
