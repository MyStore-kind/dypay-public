# RC2 部署 Checklist（v3.1.0-rc2）

> 适用：你昨天已经把 v3.0.0-rc1 部到 `187.127.100.8`，本轮新增的 5 个 commit 要叠加部署。

---

## 一、本轮要部署的东西概览

### 后端 5 个新 commit

```
8e6f8b9 跨站 PayPal capture + 客户端凭据管理
b6ee079 chore: 补提之前未入库的安全加固
fdd1fcf 跨站托管收银台 + 异步通知 + Stripe 集成
7e71eca 上游关停 Webhook + 渠道评分 + 路径对齐
21bc44e 拒付惩罚扣 N 倍引擎
```

### 前端 2 个新 commit

```
a4d1d78 跨站客户端 / 流水 / 通知 3 个管理页
35a4a35 拒付惩罚配置 + 流水查询页
```

### 涉及的服务

| 服务 | 端口 | 是否需要重启 |
|---|---|---|
| jeepay-manager | 8083 | ✅ 必须 |
| jeepay-payment | 9216 | ✅ 必须 |
| jeepay-merchant | 8084 | ⚪ 可选（本轮无改动） |
| MySQL | 3306 | ❌ 不重启，但要跑 SQL 补丁 |
| Redis | 6379 | ❌ 不动 |
| nginx（前端） | 80 | ⚠️ 重新发布 dist 后 reload |

---

## 二、新增 SQL 补丁（必须按顺序跑）

```bash
# 在服务器上
cd /www/wwwroot/my-international-payment
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/chargeback_penalty_patch.sql
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/chargeback_penalty_menu_patch.sql
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/cross_site_patch.sql
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/cross_site_hosted_patch.sql
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/cross_site_channel_patch.sql
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb < sql/cross_site_menu_patch.sql

# 给超级管理员加新菜单权限
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb <<'EOF'
INSERT IGNORE INTO t_sys_role_ent_rela (role_id, ent_id, sys_type)
SELECT 'ROLE_SUPER_ADMIN', ent_id, 'MGR'
FROM t_sys_entitlement
WHERE ent_id LIKE 'ENT_CHARGEBACK_PENALTY%' OR ent_id LIKE 'ENT_CROSS_SITE%';
EOF
```

> ⚠️ 如果某条 SQL 报"Column already exists / Table already exists"，说明前一轮已经跑过，跳过即可。

---

## 三、代码上传到服务器

### 方案 A：用 SCP 直接传（最快）

```bash
# 本地，环境变量先设
set DEPLOY_PASSWORD=你的服务器root密码

# 后端
cd C:\Users\惠普\Desktop\新建文件夹\my-international-payment

# 1) 排除 target 等编译产物，整包压缩上传
tar --exclude='target' --exclude='node_modules' --exclude='.git' \
    -czf /tmp/dypay-backend.tar.gz .

# 2) scp 上去
scp /tmp/dypay-backend.tar.gz root@187.127.100.8:/tmp/

# 3) ssh 上去解压覆盖
ssh root@187.127.100.8 "
  cd /www/wwwroot/my-international-payment &&
  tar -xzf /tmp/dypay-backend.tar.gz --strip-components=0
"
```

### 方案 B：用 rsync（推荐，能增量同步）

```bash
# 后端
rsync -avz --delete \
  --exclude='target' --exclude='.git' --exclude='deploy/.env*' \
  C:/Users/惠普/Desktop/新建文件夹/my-international-payment/ \
  root@187.127.100.8:/www/wwwroot/my-international-payment/

# 前端源码
rsync -avz --delete \
  --exclude='node_modules' --exclude='dist' --exclude='.git' \
  C:/Users/惠普/Desktop/新建文件夹/jeepay-ui/ \
  root@187.127.100.8:/www/wwwroot/jeepay-ui/
```

### 方案 C：用之前 stage*.py 的同款套路

如果你昨天用过某个 stageNN_xx.py 部署成功了，直接复用那个脚本，把它的 `tarball/source` 路径改一下即可。

---

## 四、后端编译 + 重启

```bash
ssh root@187.127.100.8

cd /www/wwwroot/my-international-payment

# 1) 编译（约 3-5 分钟）
mvn -pl jeepay-core,jeepay-service,jeepay-manager,jeepay-payment -am \
    clean package -DskipTests

# 2) 重启 manager
pkill -f 'jeepay-manager.*\.jar' || true
sleep 2
nohup java -jar -Xms512m -Xmx1024m \
  -Dspring.profiles.active=prod \
  jeepay-manager/target/jeepay-manager-*.jar \
  > /var/log/jeepay-manager.log 2>&1 &

# 3) 重启 payment
pkill -f 'jeepay-payment.*\.jar' || true
sleep 2
nohup java -jar -Xms512m -Xmx1024m \
  -Dspring.profiles.active=prod \
  jeepay-payment/target/jeepay-payment-*.jar \
  > /var/log/jeepay-payment.log 2>&1 &

# 4) 等 30s 后看启动日志
sleep 30
tail -50 /var/log/jeepay-manager.log
tail -50 /var/log/jeepay-payment.log
```

**关键启动日志（必须都出现才算成功）**：
- `Tomcat started on port 8083`（manager）
- `Tomcat started on port 9216`（payment）
- `Started JeepayManagerApplication in xx seconds`
- 无 `BeanCreationException` / `Failed to start` 等关键字

---

## 五、前端编译 + 发布

```bash
ssh root@187.127.100.8

cd /www/wwwroot/jeepay-ui/jeepay-ui-manager

# 1) 装依赖（首次或 lock 变动时）
yarn install   # 或 npm ci

# 2) 编译
yarn build     # 或 npm run build → 输出到 dist/

# 3) 替换 nginx 站点目录（路径按你昨天的部署调整）
NGINX_ROOT=/www/wwwroot/dypay-manager-ui  # 改成你实际路径
rm -rf $NGINX_ROOT/*
cp -r dist/* $NGINX_ROOT/

# 4) reload nginx
nginx -t && nginx -s reload
```

---

## 六、Stripe Webhook 配置（仅首次）

登录 https://dashboard.stripe.com → Developers → Webhooks → Add endpoint：

| 项 | 值 |
|---|---|
| Endpoint URL | `https://pay.dypay.com/api/cross-site/pay/webhook/stripe` |
| Listen | Events on your account |
| Events | `payment_intent.succeeded`<br>`payment_intent.payment_failed`<br>`payment_intent.canceled` |

拿到 `whsec_xxx` 写入 `t_channel_account.config_params.webhookSecret`：

```sql
UPDATE t_channel_account
SET config_params = JSON_SET(config_params, '$.webhookSecret', 'whsec_xxxxxxxx')
WHERE account_id = 'stripe_acct_001';
```

---

## 七、冒烟测试（部署完必跑）

### 1. 运营后台登录
- 浏览器开 `http://187.127.100.8:8083`
- 用 jeepay/jeepay123（或你改过的密码）登录
- **左侧菜单**应该看到：
  - 风控中心 → 拒付惩罚配置（新）
  - 风控中心 → 拒付扣款流水（新）
  - 风控中心 → A/B站联动 → 客户端凭据（新）
  - 风控中心 → A/B站联动 → 跨站订单流水（新）
  - 风控中心 → A/B站联动 → 通知投递记录（新）

### 2. 数据库自检
```sql
-- 表都建好了？
SHOW TABLES LIKE 't_chargeback_penalty%';   -- 应有 2 张
SHOW TABLES LIKE 't_cross_site_%';          -- 应有 3 张

-- 全局兜底配置默认 3 倍？
SELECT * FROM t_chargeback_penalty_config WHERE mch_no='__GLOBAL__';

-- t_mch_info 新字段？
DESC t_mch_info;  -- 应有 balance_available / balance_pending / balance_frozen

-- t_channel_account 新字段？
DESC t_channel_account;  -- 应有 upstream_risk_rules / closed_by_upstream / circuit_callback_*
```

### 3. 端到端跨站测试

a) 后台创一个测试客户端：
```
风控中心 → A/B站联动 → 客户端凭据 → 新增
client_id: TEST-CLIENT-001
client_secret: AUTO（留空让系统生成）
保存 → 弹窗里复制 secret（仅一次！）
```

b) 本地跑这个 Python 脚本（替换 secret）：
```python
import hmac, hashlib, time, secrets, json, urllib.request
SECRET = "弹窗复制的 secret"
p = {
    "client_id":"TEST-CLIENT-001",
    "order_id":"TEST-"+secrets.token_hex(4),
    "amount":1999, "currency":"USD",
    "subject":"E2E 测试",
    "return_url":"https://example.com/r",
    "notify_url":"https://webhook.site/你的-uuid",
    "ts":int(time.time()*1000),
    "nonce":secrets.token_hex(16),
}
fields=["amount","client_id","currency","nonce","notify_url",
        "order_id","return_url","subject","ts"]
s="&".join(f"{k}={p[k]}" for k in sorted(fields))
p["sign"]=hmac.new(SECRET.encode(),s.encode(),hashlib.sha256).hexdigest()
req=urllib.request.Request(
    "http://187.127.100.8:8083/api/anon/cross-site/order/create",
    data=json.dumps(p).encode(),
    headers={"Content-Type":"application/json"})
print(urllib.request.urlopen(req).read().decode())
```

c) 拿返回的 `pay_url`，浏览器打开应该看到收银台 UI，选 Stripe 后用测试卡 `4242 4242 4242 4242` 付款。

d) 付款完成 → 看 webhook.site 是否收到通知 → 后台 `通知投递记录` 应该有一条 state=2（成功）。

---

## 八、回滚预案

如果发现严重 bug 需要回滚：

```bash
# 1. 后端代码回滚到上一个 RC
cd /www/wwwroot/my-international-payment
git reset --hard v3.1.0-rc1   # 或你昨天部的那个 commit

# 2. 重新编译重启（重复步骤四）

# 3. SQL 回滚（如果是结构问题）
mysql -uroot -p${MYSQL_ROOT_PASSWORD} jeepaydb <<'EOF'
-- 删新表（谨慎，会丢数据）
DROP TABLE IF EXISTS t_chargeback_penalty_record;
DROP TABLE IF EXISTS t_chargeback_penalty_config;
DROP TABLE IF EXISTS t_cross_site_notify_record;
DROP TABLE IF EXISTS t_cross_site_push_record;
DROP TABLE IF EXISTS t_cross_site_client;
-- 删菜单
DELETE FROM t_sys_entitlement WHERE ent_id LIKE 'ENT_CHARGEBACK_PENALTY%';
DELETE FROM t_sys_entitlement WHERE ent_id LIKE 'ENT_CROSS_SITE%';
EOF
```

---

## 九、上线 Checklist（逐项 ✅ 后再下一步）

- [ ] SQL 6 个补丁全部执行成功
- [ ] 超管角色已加新权限
- [ ] 后端代码上传到服务器
- [ ] `mvn package` 编译成功（无 ERROR）
- [ ] jeepay-manager 启动成功（看到 8083 端口监听）
- [ ] jeepay-payment 启动成功（看到 9216 端口监听）
- [ ] 前端 `yarn build` 编译成功
- [ ] nginx 站点目录已替换 + reload
- [ ] 浏览器能登录后台
- [ ] 左侧菜单看到 5 个新菜单
- [ ] 拒付惩罚配置页能打开（即便没数据也能渲染）
- [ ] 跨站客户端凭据页能新增 + 弹窗显示明文 secret
- [ ] Python 创单脚本返回 `pay_url`
- [ ] 浏览器打开 `pay_url` 看到收银台
- [ ] Stripe 测试卡付款成功
- [ ] webhook.site 收到通知

---

## 十、有问题怎么找我

1. 把对应步骤的报错完整复制给我
2. 同时提供 `tail -100 /var/log/jeepay-manager.log` 输出
3. 提供 `curl -v http://localhost:8083/api/anon/...` 测试请求的完整响应

我会基于真实错误日志精确定位。

---

## 附：Git 推到远端（可选，先不做）

后端仓库**没有 remote**，前端仓库**指错远端**（指向 JeePay 公共上游）。
要推之前必须先：

```bash
# 后端 — 设你自己的私有远端
cd C:/Users/惠普/Desktop/新建文件夹/my-international-payment
git remote add origin <你的私有 git 地址>
git push -u origin master --tags

# 前端 — 改远端到你的私有仓库
cd C:/Users/惠普/Desktop/新建文件夹/jeepay-ui
git remote set-url origin <你的私有 git 地址>
git push -u origin main --tags
```

> 推之前我必须问你私有仓库地址，否则容易把代码推到错误的位置。
