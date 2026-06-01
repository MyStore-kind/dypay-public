#!/bin/bash
# ============================================================
# JeePay 国际支付 - 数据库幂等初始化
# 用 sys_config 表的 `db_patch_version` 字段记录已执行版本，避免重复执行。
# ============================================================
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}" >&2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$SCRIPT_DIR/.env"

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

DB_NAME="${MYSQL_DATABASE:-jeepaydb}"
MYSQL_CT="jeepay-mysql"

mysql_exec() {
  docker exec -i "$MYSQL_CT" mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "$@"
}

# ---------- 等待 MySQL ----------
log "确认 MySQL 健康"
for i in $(seq 1 12); do
  if docker exec "$MYSQL_CT" mysqladmin ping -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
    break
  fi
  sleep 5
  [[ $i -eq 12 ]] && { err "MySQL 未就绪"; exit 1; }
done

# ---------- 创建 db_patch_version 标记表（首次） ----------
mysql_exec "$DB_NAME" <<'SQL' || true
CREATE TABLE IF NOT EXISTS db_patch_version (
  patch_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '补丁脚本文件名',
  applied_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间'
) COMMENT='SQL 补丁执行记录（幂等控制）';
SQL

# ---------- 待执行 SQL 列表（顺序敏感） ----------
# 注意：
# 1. jeepay-origin/init.sql 仅首次需要；如目标库已存在 t_sys_user 则会被幂等表跳过
# 2. risk_v3_patch.sql：本批次新增，R1 商户日额熔断 + R3 Stripe EFW 卡 BIN 冻结的 schema
# 3. post_install_hardening.sql 故意不加入清单 —— 它含占位（:NEW_BCRYPT_HASH / :NEW_SALT），
#    必须运营手工编辑后单独执行：
#       mysql -uroot -p${MYSQL_ROOT_PASSWORD} ${MYSQL_DATABASE} < sql/post_install_hardening.sql
#    不挂自动化的原因：占位守卫会触发 1/0 错误中止，会把本脚本的幂等链路弄脏。
# 顺序与 docs/14-生产部署补丁序列与回滚SOP.md 保持一致
SQL_FILES=(
  "sql/jeepay-origin/init.sql"
  "sql/international_payment_patch.sql"
  "sql/risk_control_patch.sql"
  "sql/risk_circuit_breaker_patch.sql"
  "sql/permission_menu_patch.sql"
  "sql/cross_site_patch.sql"
  "sql/cross_site_channel_patch.sql"
  "sql/cross_site_hosted_patch.sql"
  "sql/cross_site_menu_patch.sql"
  "sql/chargeback_penalty_patch.sql"
  "sql/chargeback_penalty_menu_patch.sql"
  "sql/mch_balance_patch.sql"
  "sql/risk_v3_patch.sql"
)

apply_sql() {
  local rel="$1"
  local abs="$PROJECT_DIR/$rel"
  local id
  id="$(basename "$rel")"
  if [[ ! -f $abs ]]; then
    warn "SQL 不存在，跳过：$rel"
    return
  fi
  # 幂等检查
  local exist
  exist=$(mysql_exec -N -B "$DB_NAME" -e "SELECT COUNT(*) FROM db_patch_version WHERE patch_id='${id}'" 2>/dev/null || echo 0)
  if [[ "$exist" -gt 0 ]]; then
    warn "已执行，跳过：$id"
    return
  fi
  log "执行：$rel"
  if mysql_exec "$DB_NAME" < "$abs"; then
    mysql_exec "$DB_NAME" -e "INSERT INTO db_patch_version(patch_id) VALUES('${id}')"
    log "完成：$id"
  else
    err "执行失败：$id（请人工排查后再次运行本脚本继续）"
    exit 1
  fi
}

# ---------- 主流程 ----------
# 确保库存在
mysql_exec -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

for f in "${SQL_FILES[@]}"; do
  apply_sql "$f"
done

log "数据库初始化完毕"
mysql_exec -N -B "$DB_NAME" -e "SELECT patch_id, applied_at FROM db_patch_version ORDER BY applied_at"
