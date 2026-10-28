# 覆盖优先规划与题数口径验证记录

日期：2026-10-28。基于已批准的[设计](../specs/2026-10-28-coverage-first-question-planning-design.md)和[实施计划](2026-10-28-coverage-first-question-planning.md)。

## 范围与证据来源

Task1–3 已完成规划、兼容统计和前端展示，并经控制器安排的独立审查。Task4 只新增 `CoverageFirstInterviewRegressionIntegrationTest` 和本验证记录，没有修改产品行为。该测试复用现有 H2 工作流环境，保留真实会话服务、历史读取、规划器、校验器、JPA 计划持久化与计数服务；模型生成、能力画像查询和 Redis 使用测试替身。

新增回归包含三个执行用例：

- 保存 V1 的八道主问题计划，保留 PROJECT×3、SYSTEM_DESIGN_SCENARIO×3、JAVA、MYSQL 的旧顺序及十六个作答项。冷恢复、历史列表、详情和继续提交追问后，原始问题 JSON、计划 JSON 和计划版本逐字不变；原有答案、题号、追问父题号及下一题索引保持原语义。读取和作答路径若调用重新规划会立即失败。
- 用前两场各六道主问题及对应追问形成真实持久化历史，经创建入口构建历史覆盖统计，再调用真实规划服务。八个 V2 题槽前六个覆盖六类，实际生成列表仍按计划顺序保存；创建响应统计为八主、八追问、十六作答项。
- 在同一创建夹具中让画像查询失败，验证固定回退计划仍以 V2 持久化六类覆盖，保留故障原因，并正常提供实际题数。

第三场夹具采用合成数据：前两场分类覆盖为 PROJECT=3、JAVA/MYSQL/REDIS/SPRING 各2、SYSTEM_DESIGN_SCENARIO=1；创建前快照包含 PROJECT/WEAK/35、MYSQL/STRONG/85、REDIS/PROFICIENT/75，评估/确认时间为 2026-10-28T00:00:00Z，模拟创建时间为 01:00:00Z。题面仅使用 `first-0` 等合成标识，没有用户原始题面或答案。创建后再提供 02:00:00Z 的 PROJECT/STRONG/90 画像，冷恢复仍读取已保存计划，不调用重新规划。

这是针对已知偏斜的结构性重放，不是从真实数据库提取的第三场逐值快照；没有读取真实用户、密码或后续画像来冒充创建时输入。

## 验证命令与结果

后端执行使用工作区外部状态不变的临时 Gradle init 脚本，将测试 JVM 最大堆设为 1 GiB。

```powershell
.\gradlew.bat :app:test --tests '*CoverageFirstInterviewRegressionIntegrationTest' --no-daemon -I .superpowers/sdd/2026-10-28-coverage-first-question-planning/test-heap.init.gradle
.\gradlew.bat :app:test --no-daemon -I .superpowers/sdd/2026-10-28-coverage-first-question-planning/test-heap.init.gradle
```

聚焦回归：3 tests，0 skipped，0 failures，0 errors，`BUILD SUCCESSFUL`（32s）。首次编译因测试方法缺少 `IOException` 声明失败，修正后通过；编译错误不计为行为 RED。Task4 是已有功能的回归/特征测试，没有为制造 RED 而改写产品；功能开发的 RED/GREEN 证据保留于前序任务记录。

完整后端：`BUILD SUCCESSFUL in 3m 34s`，exit 0。完成后逐个汇总133个新生成的 JUnit XML：688 tests，639执行通过，49 skipped，0 failures，0 errors；包含 `a422237` 的旧详情兼容修复及新增三个跨层用例。计数与旧响应两套测试共39项也全部执行通过。

49项跳过均为原有禁用项：Provider enabled 规则占位1项、需真实Redis的限流集成3项、待修配置的语音集成10项、待适配构造器的语音服务32项，以及 Dashscope/语音提示/暂停服务占位各1项。本批没有新增跳过。

```powershell
cd frontend
$env:pnpm_config_verify_deps_before_run='warn'
pnpm test
pnpm run build
```

前端：33 tests，33 passed，0 failed/skipped/cancelled；`tsc && vite build` exit 0，Vite 构建25.37s。依赖校验仅作当前进程环境覆盖，没有安装依赖、批准安装脚本或修改 lockfile。

浏览器证据由控制器提供：在 GET-only 的 5175 预览夹具中检查创建文案、计数展示、旧响应降级、追问归属标签；浏览器 console errors 为 `[]`。该证据来自合成夹具，没有真实登录或业务数据，本任务没有重复浏览器操作。

## 覆盖对应与审查边界

分类覆盖、固定/无画像/故障回退、残余预算、确定排序、整数容量、来源事实与版本兼容由现有 `QuestionCoveragePolicyTest`、`AdaptiveAllocationPolicyTest`、`QuestionPlanValidatorTest` 和 `QuestionPlanningServiceTest` 覆盖；Task4 不重复这些矩阵。实际题数、未知旧数据、旧 DTO 构造、索引兼容、单次 JSON 读取和历史列表无新增逐条查询由 `InterviewQuestionCountServiceTest`、`InterviewQuestionCountsResponseTest` 覆盖。前端共享格式化和父题关系通过现有33项套件覆盖。

相对实施前基线检查变更路径：没有数据库迁移、实体计数列、密钥、生产配置或真实数据修改；Task4 没有新增产品源码变更。工作区开始时没有待暂存用户改动，本任务提交仅包含新回归类和本记录。Task4 没有重新生成旧面试、保存报告或能力记忆，没有启动真实数据库、模型或外部服务，也没有合并、推送或重启服务。控制器继续负责 Task4 独立审查及最终整分支审查。

## 已知警告与限制

前端保留现有警告：node_modules 与 lockfile 不同步、Browserslist 数据过旧、三个滚动条 `:where()` CSS 压缩语法警告，以及超过500 kB 的构建分块。构建成功不代表这些既存警告已经修复。后端出现 Mockito/OpenJDK bootstrap classpath 的 CDS 警告。

本批保证分类覆盖约束、计划保存和题数口径可解释，不保证语义去重、动态追问深度或题面质量。原有禁用测试和真实 LLM/S3/Redis/PostgreSQL 链路不在本次离线验证范围；合成 H2 与前端夹具不能替代真实账户和真实历史的端到端验收。
