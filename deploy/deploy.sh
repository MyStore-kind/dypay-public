#!/bin/bash
# ============================================================
# JeePay 国际支付 - 一键部署主脚本
# 用法：bash deploy/deploy.sh
# ============================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}" >&2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$SCRIPT_DIR/.env"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.deploy.yml"

cd "$PROJECT_DIR"

# ---------- 选择 compose 命令 ----------
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  err "未安装 docker compose，请先执行 bash deploy/setup-server.sh"
  exit 1
fi

# ---------- 校验 .env ----------
if [[ ! -f $ENV_FILE ]]; then
  err ".env 不存在，请执行：cp deploy/.env.example deploy/.env 并填写"
  exit 1
fi

# 必填项检查
required=(MYSQL_ROOT_PASSWORD JEEPAY_JWT_SECRET STRIPE_API_KEY STRIPE_WEBHOOK_SECRET)
missing=()
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
for k in "${required[@]}"; do
  v="${!k:-}"
  if [[ -z "$v" || "$v" == *"请改为"* || "$v" == *"xxxxx"* ]]; then
    missing+=("$k")
  fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
  err "下列必填项未配置或仍是占位符：${missing[*]}"
  err "请编辑 $ENV_FILE 后重试"
  exit 1
fi
log ".env 校验通过"

# ---------- Docker 运行检查 ----------
if ! docker info >/dev/null 2>&1; then
  err "Docker 服务未运行：systemctl start docker"
  exit 1
fi
log "Docker 服务运行中"

# ---------- 拉镜像 ----------
log "拉取镜像（耗时取决于网络）"
$DC --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull || warn "部分镜像可能需要本地构建"

# ---------- 构建 + 启动 ----------
log "构建并启动容器"
$DC --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build

# ---------- 等待 MySQL ----------
log "等待 MySQL 健康（最多 60 秒）"
for i in $(seq 1 12); do
  if docker exec jeepay-mysql mysqladmin ping -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
    log "MySQL 已就绪"
    break
  fi
  printf "."
  sleep 5
  if [[ $i -eq 12 ]]; then
    err "MySQL 60 秒内未就绪，请查看：docker logs jeepay-mysql"
    exit 1
  fi
done

# ---------- 数据库初始化 ----------
if [[ -x "$SCRIPT_DIR/init-database.sh" ]]; then
  log "执行数据库初始化"
  bash "$SCRIPT_DIR/init-database.sh" || {
    err "数据库初始化失败，详见上方日志"
    exit 1
  }
fi

# ---------- 输出访问地址 ----------
PUB="${PUBLIC_BASE_URL:-http://<服务器IP>}"
echo
echo "================ 部署完成 ================"
echo "运营后台前端 ：${PUB}:${UI_MANAGER_PORT:-8083}    (默认 jeepay/jeepay123)"
echo "商户后台前端 ：${PUB}:${UI_MERCHANT_PORT:-8082}"
echo "运营后台 API ：${PUB}:${MANAGER_PORT:-9217}/actuator/health"
echo "商户后台 API ：${PUB}:${MERCHANT_PORT:-9218}/actuator/health"
echo "支付网关 API ：${PUB}:${PAYMENT_PORT:-9216}/actuator/health"
echo "可观测性     ：docker compose --profile observability up -d  (Prometheus 9090 / Grafana 3000)"
echo "=========================================="
echo
warn "排查指引：docker ps   |   docker logs -f jeepay-manager   |   bash deploy/healthcheck.sh"
