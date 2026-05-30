#!/bin/bash
# ============================================================
# JeePay 国际支付 - 紧急回滚
# 用法：bash deploy/rollback.sh
# ============================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}" >&2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$SCRIPT_DIR/.env"

cd "$PROJECT_DIR"
[[ -f $ENV_FILE ]] && { set -a; . "$ENV_FILE"; set +a; }

if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi

warn "即将停止全部容器（保留数据卷）"
read -rp "确认继续？输入 yes 回车继续，其它任何输入取消：" ans
[[ "$ans" == "yes" ]] || { warn "已取消"; exit 0; }

# ---------- 数据库备份建议 ----------
BACKUP_DIR="$PROJECT_DIR/backup"
mkdir -p "$BACKUP_DIR"
if docker ps --format '{{.Names}}' | grep -qx jeepay-mysql; then
  ts=$(date +%Y%m%d-%H%M%S)
  dump="$BACKUP_DIR/jeepaydb-${ts}.sql"
  log "导出数据库快照：$dump"
  docker exec jeepay-mysql sh -c \
    "exec mysqldump -uroot -p\"${MYSQL_ROOT_PASSWORD}\" --single-transaction --routines --triggers ${MYSQL_DATABASE:-jeepaydb}" \
    > "$dump" || warn "mysqldump 失败，可手动执行：docker exec jeepay-mysql mysqldump ..."
fi

# ---------- 停止 ----------
log "停止容器"
$DC --env-file "$ENV_FILE" down

# ---------- 数据库回滚选项 ----------
echo
echo "数据库回滚选项："
echo "  1) 保留数据（默认，仅停服）"
echo "  2) 完全清空数据卷（mysql/redis/rocketmq 全部丢失，需重新初始化）"
read -rp "选择 [1/2]：" opt
case "${opt:-1}" in
  2)
    warn "删除数据卷"
    $DC --env-file "$ENV_FILE" down -v
    log "数据卷已删除"
    ;;
  *)
    log "已保留数据卷，下次启动直接：bash deploy/deploy.sh"
    ;;
esac

echo
log "回滚完成。备份目录：$BACKUP_DIR"
echo "如需还原数据库快照："
echo "  docker exec -i jeepay-mysql mysql -uroot -p<密码> ${MYSQL_DATABASE:-jeepaydb} < backup/<dump 文件>"
