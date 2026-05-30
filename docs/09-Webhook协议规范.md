# 09 - Webhook 协议规范

> 国际四方支付系统（基于 JeePay v3.2.0 改造）商户回调（Webhook）协议说明文档
>
> 适用范围：支付成功通知、退款结果通知、转账结果通知、**拒付/投诉异步通知（新增）**
>
> 编写：任务 #10 | 最近更新：2026-05-30

---

## 1. 总览

商户在调用统一下单、退款、转账接口时通过 `notifyUrl` 字段指定回调地址。当订单状态发生变化（成功、失败、拒付等）时，支付网关将以 **HTTP POST + application/json** 方式向 `notifyUrl` 推送异步通知。

商户在收到回调并完成业务处理后，必须 **响应字符串 `success`**（HTTP 200）。其他任何响应（包括 200 但内容非 `success`、超时、网络异常）均视为失败，会触发重试。

---

## 2. 签名机制

JeePay 保留两种签名算法，由商户在「应用配置」中选择：

### 2.1 MD5 签名（默认）

```
sign = MD5( sortedQueryString + "&key=" + appSecret ).toUpperCase()
```

- 排序规则：除 `sign` 外的全部字段按 ASCII 升序排列，使用 `&` 拼接为 `k1=v1&k2=v2`
- 空值字段（null / 空字符串）不参与签名
- 嵌套对象按 JSON 字符串作为单值参与签名

### 2.2 RSA 签名（推荐用于国际通道）

```
sign = Base64( RSA_SHA256_Sign( sortedQueryString, merchantPrivateKey ) )
```

- 商户在控制台上传公钥，平台保管商户公钥用于验签
- 商户使用平台公钥验证回调签名

> 详细密钥配置参考 JeePay 原文档《商户接入指南》。

---

## 3. 推送字段表

### 3.1 支付成功通知（payOrderNotify）

| 字段                | 类型    | 必填 | 说明                                                                 |
| ------------------- | ------- | ---- | -------------------------------------------------------------------- |
| `payOrderId`        | string  | 是   | 平台支付订单号                                                       |
| `mchOrderNo`        | string  | 是   | 商户订单号                                                           |
| `mchNo`             | string  | 是   | 商户号                                                               |
| `appId`             | string  | 是   | 商户应用 ID                                                          |
| `wayCode`           | string  | 是   | 支付方式                                                             |
| `ifCode`            | string  | 是   | 支付接口代码（stripe / paypal / alipay 等）                          |
| `amount`            | long    | 是   | 支付金额（最小币种单位）                                             |
| `currency`          | string  | 是   | ISO 4217 三位货币代码                                                |
| `state`             | byte    | 是   | 订单状态：0 生成 / 1 支付中 / 2 成功 / 3 失败 / 4 已撤销 / 5 已退款 |
| `clientIp`          | string  | 否   | 客户端 IP                                                            |
| `successTime`       | long    | 否   | 支付成功时间戳（毫秒）                                               |
| **`frozen_rate`**   | decimal | 否   | **国际化扩展**：冻结分润比例（防拒付）                               |
| **`ip_country`**    | string  | 否   | **国际化扩展**：客户端国家                                           |
| **`device_id`**     | string  | 否   | **国际化扩展**：设备指纹                                             |
| **`card_brand`**    | string  | 否   | **国际化扩展**：卡品牌                                               |
| **`card_country`**  | string  | 否   | **国际化扩展**：卡发行国家                                           |
| `reqTime`           | long    | 是   | 平台发送时间戳                                                       |
| `sign`              | string  | 是   | 签名                                                                 |

### 3.2 退款通知（refundOrderNotify）

| 字段              | 类型   | 必填 | 说明                                                  |
| ----------------- | ------ | ---- | ----------------------------------------------------- |
| `refundOrderId`   | string | 是   | 平台退款单号                                          |
| `payOrderId`      | string | 是   | 关联支付单号                                          |
| `mchRefundNo`     | string | 是   | 商户退款单号                                          |
| `refundAmount`    | long   | 是   | 退款金额                                              |
| `currency`        | string | 是   | 货币代码                                              |
| `state`           | byte   | 是   | 0 生成 / 1 退款中 / 2 成功 / 3 失败 / 4 关闭          |
| `successTime`     | long   | 否   | 退款成功时间戳                                        |
| `errCode/errMsg`  | string | 否   | 失败原因                                              |
| `sign`            | string | 是   | 签名                                                  |

### 3.3 拒付 / 投诉通知（disputeNotify）—— 国际化新增

| 字段                  | 类型   | 必填 | 说明                                                                              |
| --------------------- | ------ | ---- | --------------------------------------------------------------------------------- |
| `disputeId`           | string | 是   | 平台拒付/争议单号                                                                 |
| `payOrderId`          | string | 是   | 关联支付单号                                                                      |
| `channelDisputeId`    | string | 是   | 上游通道（Stripe/PayPal）的争议 ID                                                |
| **`dispute_type`**    | string | 是   | 类型：`chargeback`（信用卡拒付） / `inquiry`（询问） / `complaint`（PayPal 投诉）|
| **`chargeback_status`** | string | 是 | 状态：`needs_response` / `under_review` / `won` / `lost` / `warning_closed`       |
| `reason`              | string | 否   | 原因代码：`fraudulent` / `product_not_received` / `duplicate` / `subscription_canceled` 等 |
| `amount`              | long   | 是   | 涉争金额                                                                          |
| `currency`            | string | 是   | 货币代码                                                                          |
| `evidenceDueBy`       | long   | 否   | 证据提交截止时间戳                                                                |
| `createdAt`           | long   | 是   | 争议创建时间戳                                                                    |
| `sign`                | string | 是   | 签名                                                                              |

> 商户收到 `needs_response` 后，应及时调用「拒付证据上传接口」补充证据；逾期 `evidenceDueBy` 通常判败。

---

## 4. 重试机制

当商户未在 5 秒内响应 `success` 时，触发重试。重试间隔（分钟）：

```
10 → 30 → 90 → 180 → 360 → 720 → 1440
```

合计 7 次重试，覆盖约 48 小时窗口。**拒付通知** 因时效性要求，最大重试次数缩减为 4 次（10/30/90/180 分钟）。

商户侧实现要点：
- 接收回调后立即落地（消息队列 / 数据库），异步处理业务再返回 `success`
- 必须做幂等：以 `payOrderId` / `refundOrderId` / `disputeId` 为幂等键
- 不要根据请求 IP 做白名单（CDN/网关层可能改变源 IP），应以签名验证为准

---

## 5. 示例 JSON

### 5.1 支付成功通知

```json
{
  "payOrderId": "P20260530120000001",
  "mchOrderNo": "M20260530000001",
  "mchNo": "M16800001",
  "appId": "60dc3618e1374546a9c2592436f7f3ce",
  "wayCode": "stripe_pc",
  "ifCode": "stripe",
  "amount": 1999,
  "currency": "USD",
  "state": 2,
  "clientIp": "203.0.113.42",
  "successTime": 1748606400000,
  "frozen_rate": "0.05",
  "ip_country": "US",
  "device_id": "fp_8f3c2e1a9b4d",
  "card_brand": "visa",
  "card_country": "US",
  "reqTime": 1748606401000,
  "sign": "A3F9C2E81B7D5F0A6C8E2D1F9B4A7C53"
}
```

### 5.2 退款失败通知

```json
{
  "refundOrderId": "R20260530130000001",
  "payOrderId": "P20260530120000001",
  "mchRefundNo": "MR20260530000001",
  "refundAmount": 500,
  "currency": "USD",
  "state": 3,
  "errCode": "REFUND_LIMIT_EXCEEDED",
  "errMsg": "Refund window exceeded 180 days",
  "reqTime": 1748610000000,
  "sign": "B4E0D3F92C8E6A1B7D9F3E2A0C5B8D64"
}
```

### 5.3 拒付通知（chargeback）

```json
{
  "disputeId": "D20260601090000001",
  "payOrderId": "P20260530120000001",
  "channelDisputeId": "dp_1Q9XYZabcDEF0123",
  "dispute_type": "chargeback",
  "chargeback_status": "needs_response",
  "reason": "fraudulent",
  "amount": 1999,
  "currency": "USD",
  "evidenceDueBy": 1749297600000,
  "createdAt": 1748764800000,
  "sign": "C5F1E4A03D9F7B2C8E0A4F3B1D6C9E75"
}
```

商户响应（必须）：

```
HTTP/1.1 200 OK
Content-Type: text/plain

success
```
