# 生产演示环境部署

## 安全组

入方向只开放 TCP 80、443；TCP 22 仅对管理来源或阿里云 Workbench 开放。不要开放 5432、6379、5672、15672、8080、9000、9001。

## 首次部署

```bash
git clone https://github.com/actionboy11/ai-interview-agent.git /opt/ai-interview-agent
cd /opt/ai-interview-agent
cp .env.production.example .env.production
chmod 600 .env.production
```

生成演示密码哈希，并将完整输出用单引号包裹后写入 `DEMO_PASSWORD_HASH`：

```bash
docker run --rm caddy:2-alpine caddy hash-password
```

为 PostgreSQL、RabbitMQ、MinIO 和 Provider 加密分别生成独立随机值，填写 DashScope Key，并验证配置：

```bash
bash deploy/verify-production-config.sh .env.production
```

串行构建以降低内存峰值：

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml build app
docker compose --env-file .env.production -f docker-compose.prod.yml build frontend
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

## 检查

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml ps
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=200 app caddy frontend
docker stats --no-stream
```

访问 `https://47-76-185-182.sslip.io`。无凭据应返回 401；正确凭据应能加载页面。验证简历分析、SSE 问答和语音 WebSocket。

## 备份与恢复

```bash
bash deploy/backup.sh --env-file .env.production --output-dir /opt/ai-interview-backups
```

默认保留 7 天。应定期将备份复制到本机。恢复 PostgreSQL：

```bash
cat /opt/ai-interview-backups/postgres-TIMESTAMP.dump | \
  docker compose --env-file .env.production -f docker-compose.prod.yml exec -T postgres \
  pg_restore -U interview -d interview_guide --clean --if-exists
```

MinIO 目录可使用 `minio/mc` 的 `mc mirror` 反向同步回 Bucket。恢复前先停止业务写入并保存一份当前备份。

## 更新与回滚

更新前先备份，然后：

```bash
git pull --ff-only origin master
docker compose --env-file .env.production -f docker-compose.prod.yml build app
docker compose --env-file .env.production -f docker-compose.prod.yml build frontend
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

若失败，切回上一个已验证提交并重新构建。普通 `docker compose down` 不删除数据卷；生产环境禁止执行 `docker compose down -v`。

## 试用额度与停机

定期查看阿里云“费用与成本 → 我的试用”。暂时不展示时可执行：

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml stop
```

停机是否继续消耗 ECS 额度以阿里云试用页面显示为准；不要假设停止操作一定停止计费。
