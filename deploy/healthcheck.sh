#!/bin/bash
# ============================================================
# JeePay 国际支付 - 健康检查
# 用法：bash deploy/healthcheck.sh
# ============================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✅ $*${NC}"; }
fail() { echo -e "${RED}❌ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f $ENV_FILE ]] && { set -a; . "$ENV_FILE"; set +a; }

PAYMENT_PORT="${PAYMENT_PORT:-9216}"
MANAGER_PORT="${MANAGER_PORT:-9217}"
MERCHANT_PORT="${MERCHANT_PORT:-9218}"
UI_MANAGER_PORT="${UI_MANAGER_PORT:-8083}"
UI_MERCHANT_PORT="${UI_MERCHANT_PORT:-8082}"

# ---------- 容器存活检查 ----------
echo "===== 容器存活检查 ====="
CONTAINERS=(jeepay-mysql jeepay-redis jeepay-rocketmq-namesrv jeepay-rocketmq-broker jeepay-manager jeepay-payment jeepay-merchant)
for c in "${CONTAINERS[@]}"; do
  if docker ps --format '{{.Names}}' | grep -qx "$c"; then
    status=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo "running")
    if [[ "$status" == "healthy" || "$status" == "running" ]]; then
      ok "$c ($status)"
    else
      warn "$c 状态 $status"
    fi
  else
    fail "$c 未运行"
  fi
done

# ---------- Spring 健康端点 ----------
echo
echo "===== Spring Boot 健康端点 ====="
check_http() {
  local name="$1" url="$2"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || echo "000")
  if [[ "$code" == "200" ]]; then
    ok "$name -> $url (200)"
  else
    fail "$name -> $url (HTTP $code)"
  fi
}
check_http "payment   actuator" "http://127.0.0.1:${PAYMENT_PORT}/actuator/health"
check_http "manager   actuator" "http://127.0.0.1:${MANAGER_PORT}/actuator/health"
check_http "merchant  actuator" "http://127.0.0.1:${MERCHANT_PORT}/actuator/health"

# ---------- 前端 ----------
echo
echo "===== 前端 nginx ====="
check_http "ui-manager  " "http://127.0.0.1:${UI_MANAGER_PORT}/"
check_http "ui-merchant " "http://127.0.0.1:${UI_MERCHANT_PORT}/"

echo
ok "健康检查执行完毕（如有 ❌ 请查看：docker logs <容器名> --tail 200）"
