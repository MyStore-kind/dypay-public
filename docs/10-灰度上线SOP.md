# 10 - 灰度上线 SOP（国际支付反风控版）

> 适用范围：任务 #06/#07 反风控 + 国际化通道（Stripe / PayPal）全量改造。
> 责任部门：DevOps、风控、业务运营。
> 文档版本：v1.0 | 维护：DevOps 小组。

---

## 0. 总览

```
准备阶段(T-1d) → 蓝绿部署(T0) → 灰度 10% → 30% → 50% → 100%
   每阶段 24h 观察窗 → 任一红线触发 → 回滚剧本
```

每阶段进入下一档前必须完成 **指标核对** + **业务方书面 OK**。

---

## 1. 上线前 Check List（T-1d 完成）

### 1.1 SQL 补丁执行顺序

按 **从小到大** 命名顺序执行；执行前 **强制全量 mysqldump 备份**：

```bash
# 0. 备份（必须）
docker exec jeepay-mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers jeepaydb' \
  | gzip > backup_$(date +%Y%m%d_%H%M%S).sql.gz

# 1. 按序执行补丁
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < sql/patch/01_currency_rate.sql
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < sql/patch/02_risk_rule.sql
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < sql/patch/03_channel_health.sql
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < sql/patch/04_agent_profit.sql
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < sql/patch/05_sys_config_risk.sql
```

回滚 SQL 路径：`sql/patch/rollback/`（每个 patch 都有同名 `*_rollback.sql`）。

### 1.2 配置项核对

在 `.env` 或宝塔 app 模板中确认以下密钥已设置（不能为空字符串）：

| 类别 | 变量名 | 验证方式 |
|---|---|---|
| Stripe | `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` | `curl -u $KEY: https://api.stripe.com/v1/balance` 返回 200 |
| PayPal | `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` / `PAYPAL_WEBHOOK_ID` | OAuth `/v1/oauth2/token` 返回 access_token |
| Telegram | `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | `getMe` 返回 ok=true，并 sendMessage 自测 |
| SMTP | `SMTP_HOST/PORT/USER/PASS/FROM` | manager 容器内执行 `swaks --to ops@... --server $SMTP_HOST:$SMTP_PORT` |
| 汇率 | `FIXER_API_KEY` | `curl "https://api.apilayer.com/fixer/latest?base=USD&symbols=CNY" -H "apikey: $KEY"` |

### 1.3 风控阈值 23 项默认值核对

在 `sys_config` 表中确认 `risk_*` 系列 23 条默认值。运营提供的 Excel 与 DB 必须 **一字不差**：

```sql
SELECT config_key, config_val, remark
FROM t_sys_config
WHERE config_key LIKE 'risk_%'
ORDER BY config_key;
-- 期望 23 行
```

### 1.4 通道账号池

最低门槛：**至少 1 个 Stripe 账号 + 1 个 PayPal 账号 处于 enabled 状态**。

```sql
SELECT if_code, COUNT(*) AS enabled_cnt
FROM t_pay_interface_config
WHERE state = 1
GROUP BY if_code;
-- 期望: stripe>=1, paypal>=1
```

### 1.5 监控/告警就绪

- Prometheus `/targets` 三个服务全部 UP
- Grafana 看板 `risk-control.json` 已导入
- 告警通道（Telegram / 邮件）测试消息已收到

---

## 2. 灰度阶段

### 2.1 灰度方式

在路由器（`PayChannelRouter` 或等价类）外层加灰度开关字段；按 **`hash(mch_no) % 100`** 取模决定是否走新链路：

```yaml
# sys_config 中加：
grayscale_enabled: true
grayscale_percent: 10   # 10 / 30 / 50 / 100
grayscale_whitelist: "" # 逗号分隔 mch_no，强制命中（早期内测用）
grayscale_blacklist: "" # 逗号分隔 mch_no，强制不命中（VIP 客户保护）
```

判定伪代码（不改业务代码，仅做路由层 if-else）：

```
if mch_no in whitelist: 走新链路
elif mch_no in blacklist: 走旧链路
elif hash(mch_no) % 100 < grayscale_percent: 走新链路
else: 走旧链路
```

### 2.2 各阶段计划

| 阶段 | 比例 | 观察期 | 进入下一阶段条件 |
|---|---|---|---|
| Phase 1 | 10%  | 24h | 拒付率 < 0.3%、投诉率 < 0.5%、通道 P99 < 5s、无 P0 告警 |
| Phase 2 | 30%  | 24h | 同上 + 熔断触发次数 ≤ 旧链路 + 10% |
| Phase 3 | 50%  | 24h | 同上 + 商户工单数无明显上升 |
| Phase 4 | 100% | 持续 | 同上 + 业务方书面确认 |

每阶段切换命令（manager 容器内）：

```bash
# 例：升至 30%
curl -X POST http://manager:9217/api/sys/config/update \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -d '{"configKey":"grayscale_percent","configVal":"30"}'
```

### 2.3 各阶段关注指标

主看板：`Grafana → International Payment → Risk Control`

- **业务指标**：成功率、拒付率（chargeback rate）、投诉率（dispute rate）、平均客单价漂移
- **风控指标**：命中规则 TopN、商户风控评分分布、自动拦单数
- **通道指标**：各通道健康分（ChannelHealthSchedule 计算）、熔断次数、超时率
- **基础设施**：JVM 堆、Redis 内存、MySQL 慢 SQL、MQ 堆积

---

## 3. 回滚剧本

### 3.1 触发条件（任一即回滚）

- 拒付率 > 1%（持续 30 min）
- P0/P1 告警 ≥ 2 条
- 通道熔断 > 5 次/10min
- 商户大面积投诉（≥ 3 个 VIP 商户）

### 3.2 业务回滚开关（最快，30 秒内生效）

```bash
# 1. 关闭灰度（瞬时回退到旧链路）
curl -X POST .../sys/config/update -d '{"configKey":"grayscale_percent","configVal":"0"}'

# 2. 紧急关闭风控钩子（保流，但放弃风控保护）
curl -X POST .../sys/config/update -d '{"configKey":"risk_control_enabled","configVal":"false"}'
```

### 3.3 配置回滚（按 git tag）

```bash
# 假设上线 tag 为 v3.1.0，回退到 v3.0.x
cd /www/wwwroot/jeepay
git fetch --tags
git checkout v3.0.5     # 上一个稳定 tag

# 重启业务容器（基础设施不动）
docker compose up -d --force-recreate payment manager merchant
```

### 3.4 数据库回滚

> 仅在 **数据层确实出问题** 才执行；优先用 3.2/3.3 业务回滚。

```bash
# 方案 A: 反向 patch（推荐，无数据丢失）
for f in 05 04 03 02 01; do
  docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb \
    < sql/patch/rollback/${f}_*_rollback.sql
done

# 方案 B: 全量恢复（数据丢失风险，需停业务）
docker compose stop payment manager merchant
gunzip < backup_YYYYMMDD_HHMMSS.sql.gz \
  | docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb
docker compose start payment manager merchant
```

反向操作模板（每个 patch 都需配套提供）：

```sql
-- rollback 模板（示例）
-- 1. DROP 新增列前，先用 SELECT 验证无业务依赖
-- 2. 备份新增表 → 再 DROP
CREATE TABLE _bak_t_risk_rule AS SELECT * FROM t_risk_rule;
DROP TABLE t_risk_rule;

-- 3. 还原 sys_config 中本次新增的 key
DELETE FROM t_sys_config WHERE config_key IN (
  'risk_max_amount_per_day', 'risk_max_count_per_hour' /* ...23 项 */
);
```

### 3.5 回滚验证

- 拒付率回落至基线（24h 内）
- 旧通道流量 100% 通过
- Telegram/邮件告警自动恢复（无新告警）

---

## 4. 应急联系人模板（占位，由业务方填写）

| 角色 | 姓名 | 手机 | Telegram | 备份联系人 |
|---|---|---|---|---|
| 业务总负责 | TBD | TBD | TBD | TBD |
| 风控接口人 | TBD | TBD | TBD | TBD |
| 财务/对账 | TBD | TBD | TBD | TBD |
| DevOps 值班 | TBD | TBD | TBD | TBD |
| Stripe 客户经理 | TBD | TBD | — | TBD |
| PayPal 客户经理 | TBD | TBD | — | TBD |

> 在 `conf/oncall.yml` 中维护实际数据，本文档保持模板。
