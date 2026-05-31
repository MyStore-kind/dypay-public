# DYPAY 跨站联动接入手册（Cross-Site Integration Guide）

> 适用对象：**B 站**（商户）后端开发者
> A 站 = DYPAY 收款方；B 站 = 商户站点（其客户在 B 下单）
> 版本：v1.0 | 2026-05-31

---

## 1. 接入模式选择

DYPAY 提供两种接入模式，**99% 的商户用模式 E（推荐）**：

| 模式 | 谁采集 IP/指纹 | B 站工作量 | 推荐 |
|---|---|---|---|
| **A. 直推模式** | B 站自己采集 → 推送 | 高（要做前端集成） | ⚪ 同公司多套系统联动用 |
| **E. 托管收银台** ⭐ | DYPAY 在 A 站收银台采 | 低（仅一个 HTTP POST） | ✅ 商户首选 |

下面只描述**模式 E**。模式 A 见 [`appendix-A.md`](#).

---

## 2. 模式 E：完整流程

```
   B 站客户点付款
        │
        ▼
   [B 站后端]  ── POST /api/anon/cross-site/order/create ──► [A 站]
        │                                                       │
        │   ◄─────────── { pay_url, pay_token } ────────────────┘
        │
        ▼  302 redirect to pay_url
   [客户浏览器]  ──► A 站收银台（HTTPS，DYPAY 域名）
                     │
                     │ 自动采集指纹 + IP + UA
                     │ 风控评估
                     ▼
                  通过 / 3DS / 拒绝
                     │
                     ▼ 客户付款
                     │
                     ▼
                  [A 站] ─── 异步 Webhook ──► B 站 notify_url
                  [A 站] ─── 302 ──► B 站 return_url
```

---

## 3. API 参考

### 3.1 创建跨站订单

**Endpoint**

```
POST https://pay.dypay.com/api/anon/cross-site/order/create
Content-Type: application/json
```

**Request Body**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `client_id` | string | ✅ | DYPAY 颁发的客户端 ID |
| `order_id` | string | ✅ | B 站订单号（同 client_id + order_id 幂等） |
| `amount` | int | ✅ | 金额（**分**），>0 |
| `currency` | string | ✅ | ISO 4217，如 `USD` / `EUR` |
| `subject` | string | 推荐 | 商品名（展示在收银台） |
| `customer_email` | string | 可选 | 客户邮箱（预填收银台） |
| `return_url` | string | ✅ | 付款完成后客户跳回的 URL |
| `notify_url` | string | ✅ | 异步通知 Webhook URL |
| `ts` | int | ✅ | 当前时间戳（**毫秒**），与服务器偏差需 <5 分钟 |
| `nonce` | string | ✅ | 随机串（16+ 字符），10 分钟内不可复用 |
| `sign` | string | ✅ | HMAC-SHA256 签名（见 §4） |

**Response**

```json
{
  "code": 0,
  "data": {
    "id": 1234,
    "state": "verified",
    "pay_url": "https://pay.dypay.com/cashier/abc123...",
    "expire_at": 1780000000000
  }
}
```

**B 站收到 `pay_url` 后**：直接 302 重定向客户到该 URL，无需任何前端代码。

### 3.2 异步通知（A → B）

A 站会在订单状态变化时 POST 到 `notify_url`：

```http
POST {your-notify-url}
Content-Type: application/json
X-DYPAY-Signature: <HMAC-SHA256 hex>

{
  "client_id": "B-CLIENT-001",
  "order_id":  "B-ORDER-XYZ",
  "pay_order_id": "P-DYPAY-AAA",
  "state": "paid",
  "amount": 9999,
  "currency": "USD",
  "ts": 1780000000000
}
```

**B 站必须**：
- 验证 `X-DYPAY-Signature`（同算法，密钥同 `client_secret`）
- 返回 `200 OK` 与字符串 `success`
- 否则 A 站会在 24h 内按指数退避重试

### 3.3 错误码

| code | 含义 |
|---|---|
| 0 | 成功 |
| 4001 | 参数缺失或非法 |
| 4003 | 签名校验失败 |
| 4004 | 时间戳偏移过大或 nonce 重放 |
| 4005 | 客户端未启用 / IP 不在白名单 |
| 5000 | 服务异常 |

---

## 4. 签名算法

**算法**：HMAC-SHA256，输出 **hex 小写**

**参与签名字段**（必须按以下集合，按 ASCII 升序排序）：

```
amount, client_id, currency, nonce, notify_url, order_id, return_url, subject, ts
```

> 注：`customer_email` 不参与签名（可选字段）
> 缺失字段跳过，不要写 `key=`

**拼接格式**：`k1=v1&k2=v2&...`

**示例待签名串**（按 ASCII 升序）：

```
amount=9999&client_id=B-CLIENT-001&currency=USD&nonce=abc123def456&notify_url=https://b.com/notify&order_id=ORDER-001&return_url=https://b.com/return&subject=Premium Plan&ts=1780000000000
```

**签名**：
```
sign = HMAC-SHA256(client_secret, signString).hex().lowercase()
```

---

## 5. SDK 示例

### 5.1 Python

```python
import hmac, hashlib, time, json, secrets, requests

CLIENT_ID     = "B-CLIENT-001"
CLIENT_SECRET = "your-32-byte-secret-here"
DYPAY_BASE    = "https://pay.dypay.com"

def create_dypay_order(order_id, amount, currency, subject,
                       return_url, notify_url, customer_email=None):
    payload = {
        "client_id":   CLIENT_ID,
        "order_id":    order_id,
        "amount":      amount,
        "currency":    currency,
        "subject":     subject,
        "return_url":  return_url,
        "notify_url":  notify_url,
        "ts":          int(time.time() * 1000),
        "nonce":       secrets.token_hex(16),
    }
    if customer_email:
        payload["customer_email"] = customer_email

    # 签名（不含 customer_email）
    sign_fields = ["amount","client_id","currency","nonce",
                   "notify_url","order_id","return_url","subject","ts"]
    sign_str = "&".join(f"{k}={payload[k]}" for k in sorted(sign_fields)
                        if k in payload)
    payload["sign"] = hmac.new(
        CLIENT_SECRET.encode(), sign_str.encode(), hashlib.sha256
    ).hexdigest()

    r = requests.post(f"{DYPAY_BASE}/api/anon/cross-site/order/create",
                      json=payload, timeout=15)
    return r.json()

# 用法
resp = create_dypay_order(
    order_id="ORDER-001",
    amount=9999,
    currency="USD",
    subject="Premium Plan",
    return_url="https://b.com/orders/ORDER-001/done",
    notify_url="https://b.com/dypay/notify"
)
# 重定向客户
pay_url = resp["data"]["pay_url"]
# return redirect(pay_url) in Flask / Django
```

### 5.2 PHP

```php
<?php
class DypayClient {
    const BASE = 'https://pay.dypay.com';
    private $clientId, $clientSecret;

    public function __construct($id, $secret) {
        $this->clientId = $id;
        $this->clientSecret = $secret;
    }

    public function createOrder($params) {
        $params['client_id'] = $this->clientId;
        $params['ts']        = intval(microtime(true) * 1000);
        $params['nonce']     = bin2hex(random_bytes(16));

        $signFields = ['amount','client_id','currency','nonce',
                       'notify_url','order_id','return_url','subject','ts'];
        $signParams = array_intersect_key($params, array_flip($signFields));
        ksort($signParams);
        $signStr = http_build_query($signParams, '', '&', PHP_QUERY_RFC3986);
        // PHP 默认会 url-encode；如果 DYPAY 要求不 encode：
        // $signStr = implode('&', array_map(fn($k) => "$k=" . $signParams[$k], array_keys($signParams)));
        $params['sign'] = hash_hmac('sha256', $signStr, $this->clientSecret);

        $ch = curl_init(self::BASE . '/api/anon/cross-site/order/create');
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => json_encode($params),
            CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 15,
        ]);
        $resp = curl_exec($ch);
        curl_close($ch);
        return json_decode($resp, true);
    }
}

$client = new DypayClient('B-CLIENT-001', 'your-secret');
$resp = $client->createOrder([
    'order_id'   => 'ORDER-001',
    'amount'     => 9999,
    'currency'   => 'USD',
    'subject'    => 'Premium Plan',
    'return_url' => 'https://b.com/return',
    'notify_url' => 'https://b.com/notify',
]);
header('Location: ' . $resp['data']['pay_url']);
```

> ⚠️ **PHP 签名陷阱**：`http_build_query` 默认会做 URL 编码，可能与服务端不一致。如签名校验失败，改用纯字符串拼接（见注释）。

### 5.3 Node.js

```javascript
const crypto = require('crypto');
const axios  = require('axios');

const CLIENT_ID = 'B-CLIENT-001';
const SECRET    = 'your-secret';
const BASE      = 'https://pay.dypay.com';

async function createOrder(params) {
  const payload = {
    client_id: CLIENT_ID,
    ts:        Date.now(),
    nonce:     crypto.randomBytes(16).toString('hex'),
    ...params,
  };
  const fields = ['amount','client_id','currency','nonce',
                  'notify_url','order_id','return_url','subject','ts'];
  const signStr = fields
    .filter(k => payload[k] !== undefined)
    .sort()
    .map(k => `${k}=${payload[k]}`)
    .join('&');
  payload.sign = crypto.createHmac('sha256', SECRET).update(signStr).digest('hex');

  const { data } = await axios.post(
    BASE + '/api/anon/cross-site/order/create', payload, { timeout: 15000 }
  );
  return data;
}

// 用法（Express）
app.post('/checkout', async (req, res) => {
  const result = await createOrder({
    order_id: 'ORDER-' + Date.now(),
    amount: 9999,
    currency: 'USD',
    subject: 'Premium Plan',
    return_url: 'https://b.com/return',
    notify_url: 'https://b.com/notify',
  });
  res.redirect(result.data.pay_url);
});
```

### 5.4 Go

```go
package main

import (
    "bytes"
    "crypto/hmac"
    "crypto/rand"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "net/http"
    "sort"
    "strings"
    "time"
)

const (
    ClientID = "B-CLIENT-001"
    Secret   = "your-secret"
    Base     = "https://pay.dypay.com"
)

func CreateOrder(orderID, currency, subject, returnURL, notifyURL string, amount int64) (map[string]interface{}, error) {
    nonce := make([]byte, 16)
    rand.Read(nonce)
    payload := map[string]interface{}{
        "client_id":  ClientID,
        "order_id":   orderID,
        "amount":     amount,
        "currency":   currency,
        "subject":    subject,
        "return_url": returnURL,
        "notify_url": notifyURL,
        "ts":         time.Now().UnixMilli(),
        "nonce":      hex.EncodeToString(nonce),
    }

    fields := []string{"amount","client_id","currency","nonce",
        "notify_url","order_id","return_url","subject","ts"}
    sort.Strings(fields)
    var parts []string
    for _, k := range fields {
        if v, ok := payload[k]; ok {
            parts = append(parts, fmt.Sprintf("%s=%v", k, v))
        }
    }
    h := hmac.New(sha256.New, []byte(Secret))
    h.Write([]byte(strings.Join(parts, "&")))
    payload["sign"] = hex.EncodeToString(h.Sum(nil))

    body, _ := json.Marshal(payload)
    resp, err := http.Post(Base+"/api/anon/cross-site/order/create",
        "application/json", bytes.NewReader(body))
    if err != nil { return nil, err }
    defer resp.Body.Close()
    var out map[string]interface{}
    json.NewDecoder(resp.Body).Decode(&out)
    return out, nil
}
```

---

## 6. 接入测试 Checklist

- [ ] 拿到 DYPAY 颁发的 `client_id` + `client_secret`
- [ ] 把 B 站出口 IP 加入白名单（联系 DYPAY 配置）
- [ ] 用沙箱环境跑通 §3.1 创单 → 客户跳转 → 付款 → 收到 §3.2 通知 全流程
- [ ] 验证 `notify_url` 必须返回 `success` 字符串
- [ ] 处理重复通知（同 `pay_order_id` 只入账一次）
- [ ] 处理超时订单（30 分钟未付款，状态变 `expired`）

## 7. 常见问题

**Q: 客户在 B 站和 A 站登录态如何打通？**
A: 不打通。A 站收银台是匿名页面，靠 `pay_token` 识别订单；客户付完跳回 B 站再走 B 站登录态。

**Q: 怎么测试 ts 偏移？**
A: 服务器要求 ts 与 UTC 偏差 < 5 分钟。如果服务器时间漂了，所有签名都会失败 — 先校准 NTP。

**Q: nonce 怎么生成？**
A: 16 字节随机即可。**不要**用时间戳或自增 ID 当 nonce。

**Q: 签名结果不对？**
A: 99% 的问题是字段顺序错。务必按 ASCII 升序排序后拼接。把你拼出的 `signStr` 打日志和服务端对比第一次就能定位。

**Q: 我能在自家收银台采指纹然后用 push 接口吗？**
A: 可以，见模式 A 附录文档。需要你的前端开发能力。

---

## 8. 联系与支持

- 接入工单：dev@dypay.com
- 紧急故障：on-call 电话见合同
- API 状态页：https://status.dypay.com
