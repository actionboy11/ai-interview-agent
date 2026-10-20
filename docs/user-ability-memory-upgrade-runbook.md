# 业务数据归属升级操作手册

V3 为简历、文字面试、语音面试、知识库、RAG 会话和面试日程增加不可为空的
`owner_user_id`。全部旧根记录归属一个明确指定的现有账号；不根据历史语音
`user_id`、文件名或其他字段猜测所有者。V1/V2 的历史迁移不能修改。

## 有旧业务数据：必须分两阶段部署

1. **先部署 Tasks 1–4 的版本（仅包含 V1/V2，无 V3）。** 已有 Hibernate 库会在
   V1 建立 Flyway baseline，然后应用 V2 身份表。此版本包含 `InitialAdminBootstrap`。
   使用生产 Compose 时，先将本次 `app.environment` 中的四个 `APP_AUTH_BOOTSTRAP_*` /
   `APP_LEGACY_OWNER_USERNAME` 映射同步到阶段一部署配置；不要提前复制 V3 或使用新版应用。
2. 设置完整的 `APP_AUTH_BOOTSTRAP_USERNAME`、`APP_AUTH_BOOTSTRAP_PASSWORD` 和
   `APP_AUTH_BOOTSTRAP_DISPLAY_NAME`，启动阶段一版本，等待 bootstrap 成功提交。
   将 `APP_LEGACY_OWNER_USERNAME` 设为这个账号的用户名。已有管理员账号也可复用；
   bootstrap 不会重置其密码。同名普通用户不能作为 bootstrap 管理员复用。
   若目标用户名包含 `${...}`，不要假设旧 bootstrap 属性绑定也会保留字面表达式：
   按下方「通过 API 创建字面用户名」流程，先 bootstrap 普通名称的管理员，再创建并核对
   目标账号；随后清空全部三项 bootstrap 配置，再执行旧所有者预检。
3. 确认账号存在且用户名正确。归一化规则与登录一致：NFKC、去除首尾空白、
   ROOT 小写、再次 NFKC；规范化登录名必须匹配唯一一条 `users` 记录。
   保留账号 ID 与六个根表记录数用于升级后核对，不输出密码或环境文件。
   **生产停机前必须完成下方 Compose 字面值预检；失败时先修正配置，不能继续停机升级。**
4. 停止业务写入，停止旧应用及后台任务，备份 PostgreSQL（含 Flyway 历史、身份表和
   业务数据），验证备份可恢复。应保留阶段一制品与配置。
5. **再部署包含 V3 的完整归属功能版本**，保留 `APP_LEGACY_OWNER_USERNAME`。
   仅有 Task 6 的中间提交不适合单独上线：所有业务写入还必须包含 Tasks 7–8 的
   所有者赋值和访问检查，否则非空约束会拒绝旧写入路径。
6. 检查 V3 成功、Hibernate `validate` 成功、六个根表无空所有者且旧记录全部归属预期
   账号。核对记录数、简历关联和 RAG 会话关联后，再恢复流量并验证账号隔离。

旧版知识库向量没有 `ownerUserId` 元数据，新版本检索会排除这些向量。数据所有者登录后，
对本人每个旧知识库调用 `POST /api/knowledgebase/{id}/revectorize`；使用登录会话 Cookie，
先取 `GET /api/auth/csrf`，再随 POST 发送返回的 CSRF header/token。接口只接受本人知识库，
返回 `Result.code == 200` 表示异步任务已提交；继续查询该知识库详情的 `vectorStatus`，
等待 `COMPLETED` 后再验证检索。`FAILED` 时检查 `vectorError` 并由所有者重试。

不能在同一次首次部署中依赖 `InitialAdminBootstrap` 为 V3 创建账号：Flyway 在
JPA 初始化和 `ApplicationRunner` 之前运行；有旧数据时，V3 会先失败，bootstrap
没有机会执行。不要临时关闭外键/非空约束来绕过升级门槛。

## 通过 API 创建字面用户名（阶段一仍运行时）

以下 Bash/Python 3 示例使用当前 Task 4 接口。生产入口还有 Caddy Basic Auth，故分别
询问网关凭据与应用 ADMIN 凭据；密码均隐藏输入，不放入命令行或环境变量。用户名、显示名
请不含首尾空白；`${...}`、引号、dollar 和 Unicode 可直接输入。引号 heredoc 阻止 shell
展开，JSON 由 `json.dumps` 构造；私有临时目录保存 cookie jar 与请求体，退出时 trap 清理。
每次 POST 前都 GET `/api/auth/csrf`，取返回的 `headerName`/`token` 并同时发送 cookie。
所有响应都检查 HTTP 成功及 `Result.code == 200`，不能只看 HTTP 200。

```bash
(
set -eu
set +x
umask 077
auth_dir=$(mktemp -d)
trap 'rm -f -- "$auth_dir"/*.json "$auth_dir"/*.cookies; rmdir -- "$auth_dir"' EXIT
python3 - "$auth_dir" <<'PY'
import getpass, http.cookiejar, json, sys, uuid, warnings
from pathlib import Path
from urllib import error, parse, request

private_dir = Path(sys.argv[1])
warnings.simplefilter("error", getpass.GetPassWarning)  # Abort rather than echo without a TTY.
ask = getpass.getpass  # Also works through /dev/tty while this program uses a heredoc.
base = ask("HTTPS site origin (without /api): ").rstrip("/")
origin = parse.urlsplit(base)
if origin.scheme != "https" or not origin.netloc or origin.path or origin.query or origin.fragment or origin.username:
    raise SystemExit("use the trusted HTTPS site origin without credentials or a path")
gateway = request.HTTPPasswordMgrWithDefaultRealm()
gateway.add_password(None, base, ask("Caddy username: "), ask("Caddy password: "))

def client(label):
    jar = http.cookiejar.MozillaCookieJar(str(private_dir / (label + ".cookies")))
    return request.build_opener(request.HTTPCookieProcessor(jar), request.HTTPBasicAuthHandler(gateway)), jar

def body_file(payload):
    path = private_dir / "request.json"
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path

def call(browser, method, path, payload=None):
    opener, jar = browser
    headers, data = {"Accept": "application/json"}, None
    if method == "POST":
        csrf = call(browser, "GET", "/api/auth/csrf")
        headers.update({"Content-Type": "application/json", csrf["headerName"]: csrf["token"]})
        data = body_file(payload).read_bytes()
    try:
        with opener.open(request.Request(base + path, data=data, headers=headers, method=method), timeout=30) as response:
            result = json.load(response)
    except (error.URLError, ValueError):
        raise SystemExit("HTTP/JSON request failed; do not continue the upgrade") from None
    jar.save(ignore_discard=True, ignore_expires=True)
    if not isinstance(result, dict) or result.get("code") != 200:
        raise SystemExit("Result was not successful; stop and inspect the account state")
    return result.get("data")

admin_browser = client("admin")
admin_name = ask("Bootstrap ADMIN username: ")
admin_password = ask("Bootstrap ADMIN password: ")
admin = call(admin_browser, "POST", "/api/auth/login", {
    "username": admin_name, "password": admin_password})
if not isinstance(admin, dict) or admin.get("role") != "ADMIN":
    raise SystemExit("an application ADMIN is required")
admin_id = admin["id"]
call(admin_browser, "GET", "/api/auth/csrf")  # Login invalidates the previous token.
if admin["mustChangePassword"]:
    call(admin_browser, "POST", "/api/auth/change-password", {
        "currentPassword": admin_password, "newPassword": ask("New permanent ADMIN password: ")})
    admin = call(admin_browser, "GET", "/api/auth/me")
    if admin["id"] != admin_id or admin["role"] != "ADMIN" or admin["mustChangePassword"]:
        raise SystemExit("ADMIN password-change verification failed")

owner_name = ask("Literal legacy-owner username: ")
display_name = ask("Owner displayName: ")
role = ask("Owner role [USER; use ADMIN only if intended]: ").strip().upper() or "USER"
if not owner_name or owner_name != owner_name.strip() or not display_name or display_name != display_name.strip() or role not in ("USER", "ADMIN"):
    raise SystemExit("invalid username/displayName/role input")
owner_password = ask("New owner temporary password (nonblank, at most 72 UTF-8 bytes): ")
created = call(admin_browser, "POST", "/api/admin/users", {
    "username": owner_name, "password": owner_password, "displayName": display_name, "role": role})
uuid.UUID(created["id"])
if created.get("status") != "ACTIVE" or created.get("mustChangePassword") is not True:
    raise SystemExit("unexpected newly-created account state")
owner_browser = client("owner")  # A separate cookie jar preserves the ADMIN session.
logged_in = call(owner_browser, "POST", "/api/auth/login", {
    "username": owner_name, "password": owner_password})
call(owner_browser, "GET", "/api/auth/csrf")
current = call(owner_browser, "GET", "/api/auth/me")
for identity in (created, logged_in, current):
    if identity["id"] != created["id"] or identity["username"] != owner_name or identity["displayName"] != display_name or identity["role"] != role:
        raise SystemExit("created/login/me identity mismatch; stop the upgrade")
call(owner_browser, "POST", "/api/auth/logout", {})
call(admin_browser, "POST", "/api/auth/logout", {})
print(json.dumps({key: created[key] for key in ("id", "username", "displayName", "role")}, ensure_ascii=False))
PY
)
```

`CurrentUserResponse`/`AdminUserResponse` 不返回 `normalizedUsername`；`username` 是保存的
显示拼写。上面的目标账号登录会调用服务器 `UsernameNormalizer`，随后核对创建、登录和
`/me` 的同一 UUID，验证归一化查找确实命中该账号，而非假造响应字段。保存成功输出的
非秘密 UUID/用户名/显示名/角色，并核对实际意图；失败时不要继续迁移或盲目重复创建。

bootstrap 创建的账号通常 `mustChangePassword=true`，所以示例实际调用
`POST /api/auth/change-password`（`currentPassword`、`newPassword`），再 GET `/api/auth/me`
确认标记清除；已改过密码且标记为 false 时跳过。当前后端不以此标记阻挡 admin API，
前端会要求改密。新目标账号的临时密码标记不妨碍 V3 归属；用户后续登录前端仍需改密。
V3 只要求匹配的现有用户，`USER` 足够；不要仅为旧数据归属授予 `ADMIN`。

在 `.env.production` 中按 Compose 单引号规则填写已核对的 `APP_LEGACY_OWNER_USERNAME`，
用编辑器清空**全部三项** bootstrap 配置（不要打印旧密码行）：

```dotenv
APP_AUTH_BOOTSTRAP_USERNAME=''
APP_AUTH_BOOTSTRAP_PASSWORD=''
APP_AUTH_BOOTSTRAP_DISPLAY_NAME=''
```

随后执行下方现有 `verify-legacy-owner-environment.py`，输入同一原始字面用户名；只有
预检退出码为 0 才能停写、备份和部署。用户名或身份不一致时停止，先核对账号和配置。

## Compose 配置与停机前预检

生产命令与 `docs/deployment.md` 一致，显式使用 `.env.production`：项目目录的 `.env`
不会自动把变量注入容器。`docker-compose.prod.yml` 已明确映射旧所有者与三个 bootstrap
变量；四项均无默认账号或密码。阶段一 bootstrap 成功后可清空 bootstrap 配置，
但非空旧库升级必须保留 `APP_LEGACY_OWNER_USERNAME`。

生产计划使用 Docker Compose 5。`.env.production` 的用户名值必须用单引号包裹，
不要用未引用或双引号值，也不要 `source`/`eval` 环境文件。例如字面用户名
`${REVIEW_TEST_VALUE:default-user}` 写成：

```dotenv
APP_LEGACY_OWNER_USERNAME='${REVIEW_TEST_VALUE:default-user}'
```

含单引号的用户名 `O'Brien` 写成 `APP_LEGACY_OWNER_USERNAME='O\'Brien'`。这些是互斥示例，
只填写实际账号的一项；不要自行把 `$` 加倍。Compose 的单引号 dotenv 值不会执行插值，
其中的单引号使用反斜杠转义；这是 Docker 官方的
[dotenv 规则](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。
应用仍会做登录名归一化，但 `${...}` 的内容不得替换成其他属性或默认账号。

在生产仓库目录、旧服务仍运行时执行以下预检。脚本会提示输入人工核对过的原始用户名
（不是密码，输入不回显），不经 shell 求值，可包含 `$`、引号和 Unicode。脚本与下面的
Bash 包装函数都排除当前 shell 的四个同名变量覆盖；预检和后续部署必须使用同一个
`.env.production` 与 Compose 文件。Python 3 脚本只运行只读的 Compose 配置解析，
不创建容器、不运行 Flyway、不停止服务，也不输出用户名、密码、完整 JSON 或其他环境。

```bash
python3 deploy/verify-legacy-owner-environment.py --env-file .env.production
# Only continue after exit code 0.
production_compose() {
  env -u APP_LEGACY_OWNER_USERNAME -u APP_AUTH_BOOTSTRAP_USERNAME \
    -u APP_AUTH_BOOTSTRAP_PASSWORD -u APP_AUTH_BOOTSTRAP_DISPLAY_NAME \
    docker compose --env-file .env.production -f docker-compose.prod.yml "$@"
}
```

Compose 5 的 `config --format json` 为了能再次序列化会把输出中的每个 `$` 加倍，
这是[已核对的上游实现](https://github.com/docker/compose/blob/v5.1.4/cmd/compose/config.go#L178-L180)。
预检只撤销这一次输出转义，再逐字比较容器模型中的用户名；它不会展开 `${...}`，
也不会修改环境文件。实际部署直接使用原 Compose 文件，不能把渲染 JSON 当作新配置。
脚本要求 Compose 5；更新 Compose 后应先运行配置回归测试再升级生产。

只有预检退出码为 0 才能进入停写与备份步骤。若 bootstrap 用户名仍非空，预检还要求它与
目标原始用户名一致；已清空时只核对旧数据所有者。变更文件、shell、Compose 版本或账号后
重新预检。之后按上述阶段二门槛，使用 `production_compose build app`、
`production_compose build frontend` 和 `production_compose up -d`，不要改回另一套参数。
不要把 `config --format json` 的完整输出显示到终端或保存到未受保护文件。

## 全新数据库

无旧业务根记录的空库可一次应用 V1、V2、V3，不要求提前存在账号，
`APP_LEGACY_OWNER_USERNAME` 可以留空。身份 bootstrap 随后运行。生产仍使用
`spring.jpa.hibernate.ddl-auto=validate`，不依赖 Hibernate 自动升级结构。

## 配置与 SQL 安全

`APP_LEGACY_OWNER_USERNAME` 填写普通用户名，不是 UUID 或编码后的文本。生产使用上面的
单引号 `.env.production` 配置；本地 `bootRun` 从 `.env` 注入进程环境。
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
