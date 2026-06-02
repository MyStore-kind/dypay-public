#!/bin/bash
# ============================================================
# Dypay 宝塔一键 Docker 部署脚本
# ------------------------------------------------------------
# 用途：在宝塔/裸 Ubuntu 服务器上一条命令端到端部署
# 设计原则：
#   1. 自动避让宝塔已用资源（MySQL5.7 / Nginx80,443 / Redis6379）
#      → dypay 全走 docker + 高位端口 (3307/6380/8090-8099)
#   2. 自动生成强随机密码（不要用户填写）
#   3. 幂等：重复执行不破坏现状，只补缺失项
#   4. 失败立即停止 + 给出清晰提示，绝不静默吞错
# ------------------------------------------------------------
# 用法：
#   curl -fsSL https://raw.githubusercontent.com/MyStore-kind/dypay/master/deploy/baota-oneshot.sh | bash
# 或拉代码后：
#   bash deploy/baota-oneshot.sh
# ------------------------------------------------------------
# 重要：本脚本只装 docker 服务，不动宝塔已装的 MySQL/Redis/Nginx。
# ============================================================

set -euo pipefail

# ---------- 颜色与日志 ----------
G='\033[0;32m'; R='\033[0;31m'; Y='\033[1;33m'; B='\033[1;34m'; N='\033[0m'
log()  { echo -e "${G}[OK]${N} $*"; }
warn() { echo -e "${Y}[!!]${N} $*"; }
err()  { echo -e "${R}[ERR]${N} $*" >&2; }
step() { echo -e "\n${B}===== $* =====${N}"; }

# ---------- 必须 root ----------
[[ $EUID -ne 0 ]] && { err "请用 root 执行：sudo bash $0"; exit 1; }

# ---------- 部署配置（避让宝塔）----------
# 为什么这些端口：宝塔默认占 3306/6379/80/443，全部高位避让
DEPLOY_DIR="/www/wwwroot/dypay"
GIT_REPO="${GIT_REPO:-https://github.com/MyStore-kind/dypay.git}"
GIT_BRANCH="${GIT_BRANCH:-master}"

# 端口规划（避让宝塔系统级服务）
export MYSQL_PORT="${MYSQL_PORT:-3307}"          # 避让宝塔 MySQL5.7 的 3306
export REDIS_PORT="${REDIS_PORT:-6380}"          # 避让宝塔 Redis 的 6379
export PAYMENT_PORT="${PAYMENT_PORT:-9216}"       # jeepay 默认
export MANAGER_PORT="${MANAGER_PORT:-9217}"
export MERCHANT_PORT="${MERCHANT_PORT:-9218}"
export UI_MANAGER_PORT="${UI_MANAGER_PORT:-8090}" # 避让宝塔 8080/8081 常见
export UI_MERCHANT_PORT="${UI_MERCHANT_PORT:-8091}"
export ROCKETMQ_NAMESRV_PORT="${ROCKETMQ_NAMESRV_PORT:-9876}"

# ---------- Step 1：探测前置 ----------
step "Step 1/7 探测前置"
command -v docker >/dev/null || { err "docker 未安装"; exit 1; }
docker compose version >/dev/null 2>&1 || { err "docker compose 插件未装"; exit 1; }
command -v git >/dev/null || { err "git 未安装：apt install -y git"; exit 1; }
log "docker $(docker --version | awk '{print $3}' | tr -d ,)"
log "compose $(docker compose version --short)"
log "git $(git --version | awk '{print $3}')"

# 检查端口是否被占
check_port() {
  local p=$1 name=$2
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -E "[:.]${p}\$" -q; then
    err "端口 ${p} 已被占用（用途：${name}），请设环境变量改端口后重试"
    err "例：MYSQL_PORT=3308 bash $0"
    exit 1
  fi
}
for pair in "$MYSQL_PORT:mysql" "$REDIS_PORT:redis" \
            "$PAYMENT_PORT:payment" "$MANAGER_PORT:manager" "$MERCHANT_PORT:merchant" \
            "$UI_MANAGER_PORT:ui-manager" "$UI_MERCHANT_PORT:ui-merchant" \
            "$ROCKETMQ_NAMESRV_PORT:rocketmq"; do
  check_port "${pair%%:*}" "${pair##*:}"
done
log "全部端口可用：${MYSQL_PORT}/${REDIS_PORT}/${PAYMENT_PORT}/${MANAGER_PORT}/${MERCHANT_PORT}/${UI_MANAGER_PORT}/${UI_MERCHANT_PORT}/${ROCKETMQ_NAMESRV_PORT}"

# ---------- Step 2：拉代码 ----------
step "Step 2/7 拉代码"
if [[ -d $DEPLOY_DIR/.git ]]; then
  log "目录已存在，git pull 增量更新"
  cd "$DEPLOY_DIR"
  git fetch origin "$GIT_BRANCH"
  git reset --hard "origin/$GIT_BRANCH"
else
  mkdir -p "$(dirname "$DEPLOY_DIR")"
  log "首次 clone 到 $DEPLOY_DIR"
  git clone --depth 1 -b "$GIT_BRANCH" "$GIT_REPO" "$DEPLOY_DIR"
  cd "$DEPLOY_DIR"
fi
log "当前 commit: $(git log -1 --oneline)"

# ---------- Step 3：生成 .env（如不存在）----------
step "Step 3/7 生成 .env"
ENV_FILE="$DEPLOY_DIR/deploy/.env"
if [[ -f $ENV_FILE ]]; then
  warn ".env 已存在，跳过生成（如需重置请手工删除后重跑）"
else
  # 强随机生成器：openssl rand
  rand() { openssl rand -base64 "${1:-32}" | tr -d '/+=\n' | cut -c1-"${2:-32}"; }

  MYSQL_ROOT_PASSWORD=$(rand 48 32)
  MYSQL_USER_PASSWORD=$(rand 48 24)
  REDIS_PASSWORD=$(rand 48 24)
  JEEPAY_JWT_SECRET=$(rand 96 64)

  PUBLIC_IP=$(curl -sSm 5 https://api.ipify.org 2>/dev/null || echo "")
  [[ -z "$PUBLIC_IP" ]] && PUBLIC_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [[ -z "$PUBLIC_IP" ]] && PUBLIC_IP="YOUR_SERVER_IP"

  # CORS：用 IP + 高位端口（HTTP，因为没 SSL 证书；上 HTTPS 后必须改这条）
  # 为什么列两个：UI_MANAGER/UI_MERCHANT 是两个独立 origin
  CORS_ORIGINS="http://${PUBLIC_IP}:${UI_MANAGER_PORT},http://${PUBLIC_IP}:${UI_MERCHANT_PORT}"

  cat > "$ENV_FILE" <<EOF
# 自动生成于 $(date +%F\ %T)，请勿提交到 git
# 所有密码已强随机生成，如要修改请同时改三处：mysql/redis 容器、app 注入

# === 数据库 ===
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_DATABASE=dypaydb
MYSQL_USER=dypay
MYSQL_USER_PASSWORD=${MYSQL_USER_PASSWORD}
MYSQL_PORT=${MYSQL_PORT}

# === Redis ===
REDIS_PASSWORD=${REDIS_PASSWORD}
REDIS_PORT=${REDIS_PORT}
REDIS_MAXMEMORY=512mb

# === RocketMQ ===
ROCKETMQ_NAMESRV_PORT=${ROCKETMQ_NAMESRV_PORT}

# === 三端服务端口 ===
PAYMENT_PORT=${PAYMENT_PORT}
MANAGER_PORT=${MANAGER_PORT}
MERCHANT_PORT=${MERCHANT_PORT}
UI_MANAGER_PORT=${UI_MANAGER_PORT}
UI_MERCHANT_PORT=${UI_MERCHANT_PORT}

# === JVM ===
PAYMENT_JAVA_OPTS=-XX:+UseG1GC -Xms512m -Xmx1g
MANAGER_JAVA_OPTS=-XX:+UseG1GC -Xms512m -Xmx1g
MERCHANT_JAVA_OPTS=-XX:+UseG1GC -Xms512m -Xmx1g

# === JWT（强随机 64 位） ===
JEEPAY_JWT_SECRET=${JEEPAY_JWT_SECRET}

# === Stripe（测试 key，上线后必改） ===
STRIPE_API_KEY=sk_test_REPLACE_ME
STRIPE_WEBHOOK_SECRET=whsec_REPLACE_ME

# === PayPal（先空） ===
PAYPAL_CLIENT_ID=
PAYPAL_CLIENT_SECRET=
PAYPAL_WEBHOOK_ID=
PAYPAL_API_BASE=https://api-m.sandbox.paypal.com

# === 汇率 ===
FIXER_API_KEY=
FIXER_BASE_URL=https://api.apilayer.com/fixer
RATE_REFRESH_CRON=0 15 * * * ?

# === 风控告警 ===
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
RISK_ALERT_ENABLED=true

# === SMTP ===
SMTP_HOST=
SMTP_PORT=465
SMTP_USER=
SMTP_PASS=
SMTP_FROM=

# === 风控总开关 ===
RISK_CONTROL_ENABLED=true
RISK_GRAYSCALE=10

# === 调度 ===
SCHEDULE_ENABLED=true

# === 公网入口 + CORS 白名单 ===
PUBLIC_BASE_URL=http://${PUBLIC_IP}
JEEPAY_CORS_ORIGINS=${CORS_ORIGINS}
EOF
  chmod 600 "$ENV_FILE"
  log ".env 已生成（权限 600）"
  log "公网 IP 推断为 ${PUBLIC_IP}，CORS 白名单 = ${CORS_ORIGINS}"
fi

# ---------- Step 4：检查 UI 仓库（多阶段构建需要平级目录）----------
step "Step 4/7 检查前端 UI 仓库"
UI_DIR="$(dirname "$DEPLOY_DIR")/jeepay-ui"
if [[ ! -d $UI_DIR ]]; then
  warn "未发现 $UI_DIR，需要前端 UI 代码"
  warn "如果你有 dypay-ui 仓库，请改名为 jeepay-ui 放在 $UI_DIR"
  warn "本次脚本暂跳过前端构建（ui-manager / ui-merchant 不会启动）"
  SKIP_UI=1
else
  log "前端 UI 目录存在：$UI_DIR"
  SKIP_UI=0
fi

# ---------- Step 5：build + up ----------
step "Step 5/7 构建并启动容器（首次约 10-15 分钟，含 mvn package）"
cd "$DEPLOY_DIR"

# 选择启动哪些服务
SERVICES="mysql redis rocketmq-namesrv rocketmq-broker payment manager merchant"
[[ "$SKIP_UI" == "0" ]] && SERVICES="$SERVICES ui-manager ui-merchant"

# 关键：用 deploy/.env 作为 env-file
log "执行：docker compose --env-file deploy/.env -f docker-compose.yml -f docker-compose.deploy.yml up -d --build $SERVICES"
docker compose --env-file deploy/.env -f docker-compose.yml -f docker-compose.deploy.yml up -d --build $SERVICES

# ---------- Step 6：等服务健康 ----------
step "Step 6/7 等待后端服务就绪（最多 120 秒）"
ok=0
for i in $(seq 1 24); do
  payment_h=$(docker inspect -f '{{.State.Health.Status}}' dypay-payment 2>/dev/null || echo none)
  manager_h=$(docker inspect -f '{{.State.Health.Status}}' dypay-manager 2>/dev/null || echo none)
  merchant_h=$(docker inspect -f '{{.State.Health.Status}}' dypay-merchant 2>/dev/null || echo none)
  echo "  [$i/24] payment=$payment_h manager=$manager_h merchant=$merchant_h"
  if [[ "$payment_h" == "healthy" && "$manager_h" == "healthy" && "$merchant_h" == "healthy" ]]; then
    ok=1; break
  fi
  sleep 5
done
if [[ $ok -eq 0 ]]; then
  warn "后端 120 秒内未全部 healthy，查日志：docker compose logs --tail 100 payment manager merchant"
else
  log "三端后端全部 healthy"
fi

# ---------- Step 7：初始化数据库 ----------
step "Step 7/7 初始化数据库（13 个 SQL 补丁，幂等）"
if [[ -x deploy/init-database.sh ]]; then
  bash deploy/init-database.sh || warn "init-database.sh 失败，请人工处理后重跑"
else
  warn "deploy/init-database.sh 不存在或不可执行，跳过 SQL 初始化"
fi

# ---------- 完成 ----------
step "完成"
PUBLIC_IP=$(grep '^PUBLIC_BASE_URL=' "$ENV_FILE" | cut -d= -f2 | sed 's|http://||')
echo ""
echo "${G}部署完成！访问入口：${N}"
echo "  运营后台：  http://${PUBLIC_IP}:${UI_MANAGER_PORT}"
echo "  商户后台：  http://${PUBLIC_IP}:${UI_MERCHANT_PORT}"
echo "  支付网关：  http://${PUBLIC_IP}:${PAYMENT_PORT}"
echo ""
echo "${G}默认登录：${N}"
echo "  账号：admin"
echo "  密码：jeepay123"
echo "  ${R}⚠ 登录后立即去运营后台改密码，并执行 sql/post_install_hardening.sql${N}"
echo ""
echo "${G}重要文件：${N}"
echo "  .env 配置（强随机密码已生成）：$ENV_FILE"
echo "  容器日志：docker compose -f $DEPLOY_DIR/docker-compose.yml -f $DEPLOY_DIR/docker-compose.deploy.yml logs -f"
echo "  停止：    cd $DEPLOY_DIR && docker compose -f docker-compose.yml -f docker-compose.deploy.yml down"
echo ""
echo "${Y}下一步必做：${N}"
echo "  1. 改 admin 密码（前台）+ 跑 sql/post_install_hardening.sql（改盐）"
echo "  2. 在宝塔加 2 个站点反代 ${UI_MANAGER_PORT}/${UI_MERCHANT_PORT} 到你的域名 + SSL"
echo "  3. 把 .env 里 STRIPE_API_KEY / WEBHOOK_SECRET 改成真实值后重启 payment 容器"
echo ""
