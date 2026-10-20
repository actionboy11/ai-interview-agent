# 业务数据归属升级操作手册

V3 为简历、文字面试、语音面试、知识库、RAG 会话和面试日程增加不可为空的
`owner_user_id`。全部旧根记录归属一个明确指定的现有账号；不根据历史语音
`user_id`、文件名或其他字段猜测所有者。V1/V2 的历史迁移不能修改。

## 有旧业务数据：必须分两阶段部署

1. **先部署 Tasks 1–4 的版本（仅包含 V1/V2，无 V3）。** 已有 Hibernate 库会在
   V1 建立 Flyway baseline，然后应用 V2 身份表。此版本包含 `InitialAdminBootstrap`。
2. 设置完整的 `APP_AUTH_BOOTSTRAP_USERNAME`、`APP_AUTH_BOOTSTRAP_PASSWORD` 和
   `APP_AUTH_BOOTSTRAP_DISPLAY_NAME`，启动阶段一版本，等待 bootstrap 成功提交。
   将 `APP_LEGACY_OWNER_USERNAME` 设为这个账号的用户名。已有管理员账号也可复用；
   bootstrap 不会重置其密码。同名普通用户不能作为 bootstrap 管理员复用。
3. 确认账号存在且用户名正确。归一化规则与登录一致：NFKC、去除首尾空白、
   ROOT 小写、再次 NFKC；规范化登录名必须匹配唯一一条 `users` 记录。
   保留账号 ID 与六个根表记录数用于升级后核对，不输出密码或环境文件。
4. 停止业务写入，停止旧应用及后台任务，备份 PostgreSQL（含 Flyway 历史、身份表和
   业务数据），验证备份可恢复。应保留阶段一制品与配置。
5. **再部署包含 V3 的完整归属功能版本**，保留 `APP_LEGACY_OWNER_USERNAME`。
   仅有 Task 6 的中间提交不适合单独上线：所有业务写入还必须包含 Tasks 7–8 的
   所有者赋值和访问检查，否则非空约束会拒绝旧写入路径。
6. 检查 V3 成功、Hibernate `validate` 成功、六个根表无空所有者且旧记录全部归属预期
   账号。核对记录数、简历关联和 RAG 会话关联后，再恢复流量并验证账号隔离。

不能在同一次首次部署中依赖 `InitialAdminBootstrap` 为 V3 创建账号：Flyway 在
JPA 初始化和 `ApplicationRunner` 之前运行；有旧数据时，V3 会先失败，bootstrap
没有机会执行。不要临时关闭外键/非空约束来绕过升级门槛。

## 全新数据库

无旧业务根记录的空库可一次应用 V1、V2、V3，不要求提前存在账号，
`APP_LEGACY_OWNER_USERNAME` 可以留空。身份 bootstrap 随后运行。生产仍使用
`spring.jpa.hibernate.ddl-auto=validate`，不依赖 Hibernate 自动升级结构。

## 配置与 SQL 安全

`.env` 中的 `APP_LEGACY_OWNER_USERNAME` 填写普通用户名，不是 UUID 或编码后的文本。
`OwnershipMigrationConfiguration` 直接从 `systemEnvironment` 原始属性源读取该环境变量，
不经过 `Environment.getProperty`、Spring Binder 或 application.yml 的占位符解析。
用户名中的 `${...}`（包括可解析的属性名和默认值表达式）必须保持字面值，不能解析成
其他账号；不支持用 application.yml、`-D` 或启动参数中的同名属性替代此环境变量。
自定义器复用登录名归一化器，将 UTF-8 字节编码成仅含 `[0-9a-f]` 的
`legacy-owner-username` Flyway placeholder。V3 通过 PostgreSQL `decode` 和
`convert_from` 恢复用户名，再进行普通 SQL 值比较。

Flyway placeholder 是文本替换，不是绑定参数；不能把环境原值直接放进单引号或
dollar quote。用户名可包含引号、`${...}`、dollar quote 和 Unicode，安全编码使它们
不会变成 SQL 语法。不要覆盖该 placeholder 为原始文本；独立 Flyway 工具必须走同样的
归一化/UTF-8 hex 流程。自定义器不记录原值或编码值；Flyway 失败诊断可能包含展开后的
SQL，应限制迁移诊断日志的访问。

未设置或全空白配置编码为空串；有旧根记录时会拒绝迁移。非法的非空用户名（如含控制
字符或超长）在运行迁移前拒绝启动。账号不存在或出现损坏数据导致不唯一时，V3 也会拒绝。

## 失败与回退

V3 在 PostgreSQL 单个事务中执行，锁住六个根表并在解析旧所有者时锁住用户表。
回填、约束和索引中任一步失败，整个 V3 回滚；之前成功的 V1/V2 保留。先检查错误，
修正配置或在阶段一版本中创建匹配账号，然后重新启动。不要修改已发布迁移或盲目执行
`flyway repair`；正常 PostgreSQL 事务回滚无需手动把 V3 标记为成功。

V3 成功后没有自动 down migration。全局文件哈希唯一性变为
`(owner_user_id, file_hash)`：不同用户可保存相同哈希，同用户仍拒绝重复。
因此不能直接回滚到按全局哈希查询、且不写 owner 的旧应用。需要回退时先停止全部写入，
从升级前已验证的完整备份恢复数据库及阶段一制品；升级后新增数据需要另行保全和处理。
