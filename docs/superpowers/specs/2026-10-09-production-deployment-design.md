# AI Interview Platform 生产演示环境设计

## 1. 目标

将 AI Interview Platform 部署到阿里云香港 ECS，向面试官或其他受邀访问者提供一个可通过 HTTPS 链接访问的在线演示环境。

部署应满足以下目标：

- 使用当前 2 vCPU、4 GiB 内存、40 GiB 系统盘的 Ubuntu 22.04 ECS。
- 复用项目现有 Docker 镜像与 Docker Compose，不购买托管数据库或中间件。
- 使用免费 IP 映射域名和自动 HTTPS，不要求当前购买个人域名。
- 通过 HTTP Basic Auth 限制访问，避免匿名用户消耗大模型额度。
- 支持 REST、SSE、大文件上传和语音面试 WebSocket。
- 数据库、缓存、消息队列、对象存储和后端端口不得暴露到公网。
- 对容器和 JVM 进行资源约束，使完整服务能在 4 GiB 主机上稳定启动。

## 2. 非目标

本次不实现以下能力：

- 项目内部的注册、登录、用户体系和多租户隔离。
- 自动扩容、高可用、跨节点容灾或 Kubernetes。
- 使用云厂商托管 PostgreSQL、Redis、RabbitMQ 或对象存储。
- 正式生产环境的完整监控、告警与日志平台。
- 长期品牌域名。后续购买域名时只替换入口域名，不改变内部拓扑。

## 3. 总体架构

```text
Browser
  │
  │ HTTPS + HTTP Basic Auth
  ▼
Caddy :443
  │
  ▼
Frontend Nginx :80 (internal only)
  ├── /            React static files
  ├── /api/*       Spring Boot :8080
  └── /ws/*        Spring Boot :8080 (WebSocket upgrade)

Spring Boot
  ├── PostgreSQL + pgvector
  ├── Redis
  ├── RabbitMQ
  └── MinIO
```

Caddy 是唯一公网应用入口。阿里云安全组只允许 SSH、HTTP 和 HTTPS；Compose 只发布 Caddy 的 80/443 端口，其他服务仅加入 Docker 内部网络。

第一版域名采用公网 IP 派生的 `47-76-185-182.sslip.io`。Caddy 使用 ACME HTTP-01 自动申请和续期证书。若免费 DNS 或证书签发受限，可以临时切换到自有域名；不得退回长期裸 HTTP 部署，因为语音采集依赖浏览器安全上下文。

## 4. 入口认证与访问边界

Caddy 在所有路径之前执行 HTTP Basic Auth：

- 用户名由 `DEMO_USERNAME` 提供，默认建议为 `demo`。
- 密码只以 Caddy 支持的哈希形式写入服务器 `.env.production`。
- 明文密码、密码哈希和 API Key 均不得提交到 Git。
- 前端页面、REST API、SSE、WebSocket、Swagger 和管理接口使用同一个认证边界。

Basic Auth 是演示环境的外围访问控制，不声称替代业务用户认证。受邀访问者获得链接和演示凭据后，可以操作同一套共享演示数据。

## 5. 网络与反向代理

### 5.1 Caddy

Caddy 负责：

- 监听主机 80/443。
- 自动 HTTPS 和 HTTP 到 HTTPS 跳转。
- HTTP Basic Auth。
- 将通过认证的流量转发给内部 `frontend:80`。
- 传递真实客户端 IP、Host 和 Forwarded 头。

### 5.2 Frontend Nginx

前端 Nginx 负责：

- 托管 React 静态文件并支持 SPA fallback。
- 将 `/api/` 转发到 `app:8080`。
- 对 SSE 关闭代理缓冲并保持足够长的读取超时。
- 将 `/ws/` 转发到 `app:8080`，设置 HTTP/1.1、`Upgrade` 和 `Connection` 头。
- 保持 50 MiB 上传限制，与后端上传限制一致。

### 5.3 WebSocket 地址

当前后端返回硬编码的 `ws://localhost:8080`，前端也包含相同 fallback，无法在公网使用。

上线后由前端根据浏览器当前 Origin 生成同源地址：

- HTTPS 页面使用 `wss://<current-host>/ws/voice-interview/{sessionId}`。
- HTTP 本地开发页面使用 `ws://<current-host>/ws/voice-interview/{sessionId}`。
- 不再信任后端返回的 localhost 地址。

后端响应保留 `webSocketUrl` 字段以避免破坏接口结构，并改为返回 `/ws/voice-interview/{sessionId}` 相对路径。前端统一把相对路径解析为当前页面的 `ws://` 或 `wss://` 同源地址；即使历史数据或旧后端返回 localhost 绝对地址，前端也不得在非本地环境连接 localhost。该行为由前后端测试锁定。

## 6. Compose 与资源管理

新增独立的生产编排文件，不改变本地混合开发方式。生产服务包括：

- `caddy`
- `frontend`
- `app`
- `postgres`
- `redis`
- `rabbitmq`
- `minio`
- `createbuckets`

资源预算以 3.5 GiB 可用物理内存和 4 GiB Swap 为边界：

| 服务 | 目标内存边界 | 说明 |
| --- | ---: | --- |
| Spring Boot | 约 1.25 GiB | JVM 最大堆约 1 GiB，并限制 Metaspace |
| PostgreSQL | 约 512 MiB | 降低 shared buffers 和连接数 |
| RabbitMQ | 约 384 MiB | 设置内存水位并持久化消息 |
| Redis | 约 192 MiB | 主要承担缓存和限流，异步默认走 RabbitMQ |
| MinIO | 约 384 MiB | 单节点演示存储 |
| Caddy + Nginx | 约 128 MiB | 静态资源和入口代理 |

容器限制用于防止单服务挤占整台主机，但不得设置得低到触发持续 OOM。首次上线后使用 `docker stats` 观察实际峰值，再进行一次小幅调整。

构建阶段可能超过运行时内存预算。服务器已有 4 GiB Swap；构建采用串行方式，避免并行构建 Java 与前端镜像。若服务器构建仍不稳定，再将镜像构建迁移到 GitHub Actions/镜像仓库，这不是第一版的前置条件。

## 7. 配置与密钥

新增 `.env.production.example` 记录变量名和安全说明，但只使用占位值。服务器实际文件命名为 `.env.production`，权限设置为仅 root 可读。

必须独立生成：

- PostgreSQL 密码。
- RabbitMQ 用户名和密码。
- MinIO Access Key 与 Secret Key。
- Provider 配置加密密钥。
- Caddy Basic Auth 密码哈希。
- DashScope 或其他模型 Provider API Key。

四类异步任务在生产环境显式设置为 `rabbitmq`，不依赖代码默认值。运行时 Provider 配置目录挂载到持久化卷，避免容器重建后丢失。

## 8. 数据持久化与备份

使用 Docker named volumes 持久化：

- PostgreSQL 数据。
- Redis 数据。
- RabbitMQ 数据。
- MinIO 对象。
- Caddy 证书和状态。
- 应用运行时 Provider 配置。

第一版备份采用服务器本地定时导出：

- PostgreSQL 使用 `pg_dump` 生成压缩备份。
- MinIO 数据采用归档或 `mc mirror` 备份。
- 仅保留有限天数，防止 40 GiB 系统盘被占满。

本地备份不能防止整台 ECS 丢失。演示期内重要数据仍应保留本地副本；后续可再接入 OSS 或云备份，但不在本次范围内。

## 9. 健康检查与启动顺序

- PostgreSQL、Redis、RabbitMQ 和 MinIO 配置健康检查。
- `app` 等待基础设施健康以及 Bucket 初始化完成。
- `frontend` 等待 `app` 启动。
- Caddy 在前端可用后提供入口。
- Spring Boot 健康检查通过后才视为部署成功。

验收检查包括：

1. HTTP 自动跳转 HTTPS。
2. 未提供凭据时返回 401。
3. 正确凭据可以加载前端。
4. REST API 可访问。
5. SSE 能持续返回且不被缓冲。
6. WebSocket 能升级并完成一次语音会话连接。
7. 四类 RabbitMQ 生产者和消费者均启动。
8. PostgreSQL、Redis、RabbitMQ、MinIO 与 8080 端口无法从公网直接访问。
9. 重启 Compose 后业务数据和证书仍保留。

## 10. 部署与回滚

部署流程：

1. 本地完成代码、配置和测试。
2. 推送到 GitHub `master`。
3. ECS 克隆仓库并创建 `.env.production`。
4. 阿里云安全组开放 80/443，SSH 仅保留必要来源。
5. 串行构建镜像并启动生产 Compose。
6. 完成端到端验收。

更新流程使用 Git 拉取和 Compose 重建。更新前保留数据库备份；若新版本失败，回到上一个已验证 Git 提交并重新构建。Named volumes 不随普通 `docker compose down` 删除，禁止在生产环境执行 `down -v`。

## 11. 风险与应对

| 风险 | 应对 |
| --- | --- |
| sslip.io 或 ACME 签发不可用 | 切换 nip.io 或自有域名，不暴露裸 HTTP 语音站点 |
| 免费 ECS 额度耗尽 | 在阿里云试用控制台监控额度，必要时停机或迁移 |
| 4 GiB 内存不足 | JVM/容器限制、4 GiB Swap、串行构建、观察 `docker stats` |
| API Key 被滥用 | Basic Auth、现有限流、仅向受邀者发放凭据、监控模型额度 |
| 共享演示数据互相影响 | 明确演示环境属性，定期清理并保留可恢复样例数据 |
| 服务器或磁盘丢失 | 数据库和对象存储定期导出到本地 |

## 12. 成功标准

当受邀访问者能够通过 HTTPS 链接输入演示凭据，完成至少一次简历上传/分析、文字面试或知识库问答，并且语音面试 WebSocket 可建立连接，同时所有基础设施端口保持公网不可达时，本次部署视为完成。
