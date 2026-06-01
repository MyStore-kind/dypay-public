# 生产部署补丁序列与回滚 SOP

> **版本**：V3.0.0-rc1
> **适用环境**：生产
> **编写日期**：2026-06-01
> **前置阅读**：`10-灰度上线SOP.md`、`11-宝塔服务器部署手册.md`
> **重要说明**：本文档基于代码核对（`@TableName` / Mapper XML / Schedule 引用）产出，是 SQL 补丁的唯一权威执行顺序。

---

## 一、为什么需要本文档（背景）

复盘期间发现：

1. 项目交付清单（`00-项目交付清单.md`）声称"4 套 SQL 补丁"，但 `sql/` 目录下实际有 **11 个补丁文件**，存在文档与现实脱节。
2. 工作区根目录存在一份**来自另一个 jeepay 二开分支**的 `create_tables.sql`。两边都是 jeepay 二开，但**采用了不同的命名约定**：本项目用 `t_agent_info` / `t_channel_health_snapshot` / `t_risk_threshold_config`，那边用 `t_agent` / `t_channel_health_metric` / `t_risk_rule` / `t_risk_event`。代码核对显示，`create_tables.sql` 中的 7 张表**无一被本项目 Java 代码引用**（`@TableName` / Mapper XML / Schedule 全部 0 命中）。该文件已重命名为 `create_tables.sql.DEPRECATED-from-other-jeepay-fork.bak` 隔离，**禁止在本项目任何环境执行**。
3. 多个补丁文件含 `DROP TABLE IF EXISTS` 后接 `CREATE TABLE`，**重复执行会清空已有数据**，本文档下方给出每个补丁的幂等性结论与生产可用性结论。

---

## 二、补丁文件清单（按真实执行顺序）

| 序号 | 文件 | 体积 | 主要内容 | 幂等性 | 生产可用 |
|---|---|---|---|---|---|
| 0 | `sql/jeepay-origin/init.sql` | — | JeePay 原生 schema（仅全新部署需要） | 否（DROP 重建） | **仅首次部署** |
| 0' | `sql/jeepay-origin/patch.sql` | — | JeePay 原生历史补丁 | 否 | **仅首次部署** |
| 1 | `sql/international_payment_patch.sql` | 15.5K | 代理商/币种/汇率/分润等核心表 + PayOrder/RefundOrder/MchInfo 字段扩展 | ⚠️ 含 `DROP TABLE`，**已上线后禁跑** | ⚠️ |
| 2 | `sql/risk_control_patch.sql` | 24K | 风控 7 张表 + 26 项默认阈值 + 通道账号表 | ⚠️ 含 `DROP TABLE` | ⚠️ |
| 3 | `sql/risk_circuit_breaker_patch.sql` | 2.6K | 熔断 ALTER + 阈值补充 + 菜单 | ✅ 仅 ALTER/INSERT IGNORE | ✅ |
| 4 | `sql/permission_menu_patch.sql` | 4.5K | 14 个新菜单 + 权限点 | ✅ INSERT IGNORE | ✅ |
| 5 | `sql/cross_site_patch.sql` | 2.5K | A/B 站联动主表 | ⚠️ 含 `DROP TABLE` | ⚠️ |
| 6 | `sql/cross_site_channel_patch.sql` | 2.6K | A/B 站通道扩展 | ⚠️ 含 `DROP TABLE` | ⚠️ |
| 7 | `sql/cross_site_hosted_patch.sql` | 1.8K | A/B 站托管模式 ALTER | ✅ 仅 ALTER | ✅ |
| 8 | `sql/cross_site_menu_patch.sql` | 1.1K | A/B 站菜单 | ✅ INSERT IGNORE | ✅ |
| 9 | `sql/chargeback_penalty_patch.sql` | 6K | 拒付罚金配置表 + 记录表 + ALTER | ⚠️ 含 `DROP TABLE` | ⚠️ |
| 10 | `sql/chargeback_penalty_menu_patch.sql` | 1.1K | 拒付罚金菜单 | ✅ INSERT IGNORE | ✅ |
| 11 | `sql/mch_balance_patch.sql` | 5K | 商户余额流水表 + ALTER + 历史数据回填 | ⚠️ 含 `DROP TABLE` + `UPDATE` 回填 | ⚠️ |

**⚠️ 标记说明**：含 `DROP TABLE IF EXISTS` 的补丁，**首次部署使用，已有生产数据时严禁全文执行**。可用拆分手法：仅取 `ALTER` / `INSERT IGNORE` 段、跳过 `DROP/CREATE` 段。

---

## 三、生产首次部署执行顺序

> 前置：MySQL 8.0+ / Redis 6+ / RocketMQ 已就绪，业务系统暂未启动。

### Step 0 ｜全量备份（必做，无例外）

```bash
mysqldump --single-transaction --routines --triggers \
  -u root -p jeepay > /backup/jeepay_$(date +%Y%m%d_%H%M%S).sql
ls -lh /backup/jeepay_*.sql   # 校验文件存在且 > 0
```

**为什么这么做**：补丁含 DROP/UPDATE，是不可逆操作；备份是唯一兜底。
**注意事项**：备份完成前**禁止**进入 Step 1。

### Step 1 ｜原生基线（仅全新数据库）

```bash
mysql -u root -p jeepay < sql/jeepay-origin/init.sql
mysql -u root -p jeepay < sql/jeepay-origin/patch.sql
```

**注意事项**：如果是基于已有 JeePay 数据库升级，**跳过本步骤**。

### Step 2 ｜按序执行 11 个补丁

```bash
cd my-international-payment

mysql -u root -p jeepay < sql/international_payment_patch.sql
mysql -u root -p jeepay < sql/risk_control_patch.sql
mysql -u root -p jeepay < sql/risk_circuit_breaker_patch.sql
mysql -u root -p jeepay < sql/permission_menu_patch.sql
mysql -u root -p jeepay < sql/cross_site_patch.sql
mysql -u root -p jeepay < sql/cross_site_channel_patch.sql
mysql -u root -p jeepay < sql/cross_site_hosted_patch.sql
mysql -u root -p jeepay < sql/cross_site_menu_patch.sql
mysql -u root -p jeepay < sql/chargeback_penalty_patch.sql
mysql -u root -p jeepay < sql/chargeback_penalty_menu_patch.sql
mysql -u root -p jeepay < sql/mch_balance_patch.sql
```

**为什么是这个顺序**：
- 1 → 2：风控表 `t_channel_account` 在 risk_control_patch 中创建，被后续拒付罚金补丁 ALTER（第 9 个补丁的 line 112）。
- 1/2 → 3：熔断补丁 ALTER 的是 `t_mch_info`，需 international_payment 完成字段扩展。
- 2 → 9：chargeback_penalty 依赖 risk_control 已有的 `t_channel_account`。
- 5 → 6 → 7：cross_site 三个补丁均 ALTER `t_cross_site_push_record`，顺序错会找不到字段。
- 1 → 11：mch_balance 回填依赖 `t_pay_order` 已扩展的字段。

### Step 3 ｜执行结果校验

```sql
-- 必须存在的核心新表（用代码核对结论列出，共 18 张）
SHOW TABLES LIKE 't_agent_info';
SHOW TABLES LIKE 't_agent_profit_record';
SHOW TABLES LIKE 't_agent_settle_record';
SHOW TABLES LIKE 't_currency_rate';
SHOW TABLES LIKE 't_currency_config';
SHOW TABLES LIKE 't_channel_account';
SHOW TABLES LIKE 't_channel_health_snapshot';
SHOW TABLES LIKE 't_merchant_risk_score';
SHOW TABLES LIKE 't_order_risk_record';
SHOW TABLES LIKE 't_risk_blacklist';
SHOW TABLES LIKE 't_chargeback_record';
SHOW TABLES LIKE 't_risk_threshold_config';
SHOW TABLES LIKE 't_risk_alert_log';
SHOW TABLES LIKE 't_cross_site_push_record';
SHOW TABLES LIKE 't_cross_site_client';
SHOW TABLES LIKE 't_cross_site_notify_record';
SHOW TABLES LIKE 't_chargeback_penalty_config';
SHOW TABLES LIKE 't_chargeback_penalty_record';
SHOW TABLES LIKE 't_mch_balance_record';

-- 必须不存在的污染表（来自 create_tables.sql，若误执行会出现）
SHOW TABLES LIKE 't_agent';            -- ❌ 应不存在
SHOW TABLES LIKE 't_risk_rule';        -- ❌ 应不存在
SHOW TABLES LIKE 't_risk_event';       -- ❌ 应不存在
SHOW TABLES LIKE 't_channel_health_metric';  -- ❌ 应不存在
SHOW TABLES LIKE 't_statistics_merchant';    -- ❌ 应不存在
SHOW TABLES LIKE 't_agent_profit_config';    -- ❌ 应不存在

-- 默认阈值条数
SELECT COUNT(*) FROM t_risk_threshold_config;   -- 应 >= 26
```

---

## 四、已有生产数据库的升级路径（最危险路径）

> 适用场景：jeepay 原版已经在线上跑，要增量上 V3.0 二开能力。

**禁止直接跑 1/2/5/6/9/11**（含 DROP TABLE），必须拆分：

1. 用 `grep -n 'DROP TABLE\|CREATE TABLE\|ALTER TABLE\|INSERT' sql/xxx_patch.sql` 找出每段范围。
2. 仅执行 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`（搭配 `international_payment_patch.sql` 内置的 `AddColumnIfNotExists` 存储过程已自动处理幂等）+ `INSERT IGNORE`。
3. 跳过所有 `DROP TABLE IF EXISTS`。

**建议**：先在测试库**完整 dry-run 一遍**，记录哪些段报错（多半是 IF NOT EXISTS 已存在），形成定制版升级脚本，再上灰度/预发，最后上生产。

---

## 五、回滚预案（三档）

### 第一档：秒级软回滚（配置）

应用层关闭风控总开关：

```bash
# 方式 A：改 application.yml 后重启 manager
risk.enabled: false

# 方式 B：DB 改配置 + 调接口刷新缓存（推荐，无需重启）
UPDATE t_sys_config SET config_val='false' WHERE config_key='risk_control_enabled';
curl -X POST http://manager-host/api/sys/config/refresh
```

**适用**：风控误杀严重影响交易，但 schema 本身没问题。

### 第二档：配置级回滚（代码）

```bash
# Git 回滚到上一个稳定 tag
cd my-international-payment && git checkout v3.1.x
mvn clean package -DskipTests -P prod
# 重启所有服务
```

**适用**：发现某个 Service 有 bug，schema 保留。

### 第三档：数据级回滚（最重）

```bash
# 1. 停所有 jeepay 服务
systemctl stop jeepay-manager jeepay-payment jeepay-merchant

# 2. 恢复备份
mysql -u root -p jeepay < /backup/jeepay_YYYYMMDD_HHMMSS.sql

# 3. 回滚代码到上一个 tag
cd my-international-payment && git checkout v3.1.x

# 4. 重启
systemctl start jeepay-manager jeepay-payment jeepay-merchant
```

**适用**：schema 错乱、数据被污染、必须把数据库扔回上线前状态。
**代价**：回滚时段内的真实交易数据**全部丢失**，必须人工对账。

---

## 六、上线 Checklist（On-call 必读）

### T-2d

- [ ] 测试库完整跑过 11 个补丁，零报错
- [ ] 测试库验证 18 张核心表全部存在
- [ ] 测试库压测通过：TP95 < 1s / TP99 < 2s / 错误率 < 1% / 风控钩子 ≤ 50ms
- [ ] 5 类生产密钥**已申请且已实际验签通过**：
  - [ ] `STRIPE_WEBHOOK_SECRET`
  - [ ] `PAYPAL_WEBHOOK_ID`
  - [ ] `PAYPAL_API_BASE_URL = https://api-m.paypal.com`（**注意：不是 sandbox**）
  - [ ] `FIXER_API_KEY`
  - [ ] `TELEGRAM_BOT_TOKEN`（如用 TG 告警）
- [ ] 23 项风控阈值已逐项与运营负责人核对（非默认值的写入 `t_risk_threshold_config`）
- [ ] 多副本部署确认：仅一台 manager 配置 `SCHEDULE_ENABLED=true`

### T-1d

- [ ] **MySQL 全量备份完成**，备份文件 size > 0，且演练过恢复
- [ ] Redis、RocketMQ 健康检查通过
- [ ] Prometheus / Grafana 看板可访问
- [ ] 回滚脚本（第三档）演练过一次

### T-0（上线日）

- [ ] 灰度 `grayscale_percent` 设置为 10
- [ ] 按 SOP 顺序执行 11 个补丁
- [ ] 执行 Step 3 校验 SQL，全部通过
- [ ] 启动 manager → payment → merchant → ui-manager → ui-merchant
- [ ] 健康检查端点全部 200：
  - `GET /actuator/health`
  - `GET /api/risk/circuit-breaker/status`
- [ ] 真实 1 笔小额测试单跑通（Stripe + PayPal 各 1 笔）
- [ ] 10% 灰度观察 24h → 30% → 50% → 100%

### 应急联系

- [ ] On-call 主：__________
- [ ] On-call 副：__________
- [ ] DBA：__________
- [ ] 业务方：__________

---

## 七、本文档变更记录

| 日期 | 版本 | 变更 | 责任人 |
|---|---|---|---|
| 2026-06-01 | v1.0 | 首版。基于代码核对剔除污染文件，输出 11 补丁正式序列与三档回滚。 | （待填） |
