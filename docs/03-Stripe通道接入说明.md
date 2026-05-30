# Stripe 通道接入说明

> 阶段：国际四方支付改造 - 第二阶段
> 通道编码：`stripe`
> 适用场景：国际信用卡 / 借记卡 / 电子钱包支付

---

## 一、Stripe 账号准备

### 1.1 注册账号
1. 访问 https://stripe.com 注册账号
2. 完成商户认证（KYC：营业执照、法人身份等）
3. 默认会进入 **Test Mode**（测试模式），无需认证即可对接

### 1.2 获取 API 密钥
在 Stripe Dashboard → Developers → API keys 获取：

| 密钥 | 用途 | 安全级别 |
|------|------|----------|
| `Publishable Key` | 前端 Stripe.js 使用 | 公开（前端可见） |
| `Secret Key` | 后端调用 API 使用 | **必须保密** |
| `Webhook Secret` | 校验 Webhook 签名 | **必须保密** |

测试密钥格式：
- Publishable：`pk_test_xxx`
- Secret：`sk_test_xxx`

生产密钥格式：
- Publishable：`pk_live_xxx`
- Secret：`sk_live_xxx`

---

## 二、JeePay 配置

### 2.1 添加 Maven 依赖
在 `jeepay-payment/pom.xml` 中新增：

```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>26.8.0</version>
</dependency>
```

### 2.2 数据库初始化
执行 `sql/international_payment_patch.sql` 中的 Stripe 相关 SQL：

```sql
-- 已包含在补丁脚本中
INSERT INTO t_pay_interface_define VALUES
('stripe', 'Stripe', 'stripe', 'Stripe 国际支付', 1, 'https://stripe.com', NULL, NULL, 1, NOW(), NOW());

INSERT INTO t_pay_way VALUES
('stripe_card', 'Stripe 信用卡', 'stripe', 1, NOW(), NOW()),
('stripe_wallet', 'Stripe 电子钱包', 'stripe', 1, NOW(), NOW());
```

### 2.3 后台配置通道参数
在 JeePay 管理后台进入：**商户管理 → 商户应用 → 通道配置 → Stripe**

填写以下参数（JSON 存储在 `t_pay_interface_config.if_params`）：

```json
{
  "secretKey": "sk_test_xxxxxxxxxxxxxxx",
  "publishableKey": "pk_test_xxxxxxxxxxxxxxx",
  "webhookSecret": "whsec_xxxxxxxxxxxxxxx"
}
```

---

## 三、Webhook 配置

### 3.1 在 Stripe Dashboard 配置 Webhook URL
访问：Stripe Dashboard → Developers → Webhooks → Add endpoint

**Endpoint URL**：
```
https://你的支付网关域名/api/pay/notify/stripe
```

> 本地测试可用 Stripe CLI：`stripe listen --forward-to localhost:9216/api/pay/notify/stripe`

### 3.2 订阅事件
选择以下事件类型：

| 事件 | 用途 |
|------|------|
| `payment_intent.succeeded` | 支付成功 |
| `payment_intent.payment_failed` | 支付失败 |
| `charge.refunded` | 退款成功 |

### 3.3 获取 Webhook Secret
配置完成后，Stripe 会生成一个 `whsec_xxx` 密钥，复制到 JeePay 后台 `webhookSecret` 字段。

---

## 四、支付流程

### 4.1 后端：商户调用 JeePay 下单接口
```
POST /api/pay/unifiedOrder
Body:
{
  "mchOrderNo": "M20260530001",
  "wayCode": "stripe_card",
  "amount": 1000,
  "currency": "USD",
  "subject": "测试订单",
  "notifyUrl": "https://商户回调地址",
  "returnUrl": "https://支付完成跳转地址"
}
```

### 4.2 JeePay 响应
```json
{
  "code": 0,
  "data": {
    "payOrderId": "P20260530001",
    "orderState": 1,
    "payData": {
      "intentId": "pi_xxxxxxxxxxxxx",
      "clientSecret": "pi_xxxxxxxxxxxxx_secret_xxxxx",
      "publishableKey": "pk_test_xxx"
    }
  }
}
```

### 4.3 前端：调用 Stripe.js 完成支付
```html
<script src="https://js.stripe.com/v3/"></script>
<script>
  const stripe = Stripe(payData.publishableKey);
  const elements = stripe.elements({ clientSecret: payData.clientSecret });
  const paymentElement = elements.create('payment');
  paymentElement.mount('#payment-element');

  // 用户点击支付按钮
  const { error } = await stripe.confirmPayment({
    elements,
    confirmParams: { return_url: 'https://你的页面/payment-complete' }
  });

  if (error) {
    console.error('支付失败:', error.message);
  }
</script>
```

### 4.4 Webhook 异步通知
Stripe 通过 `POST /api/pay/notify/stripe` 通知 JeePay：
- 系统校验签名
- 提取 `metadata.jeepay_order_id`
- 更新订单状态为成功
- 触发分账（若配置代理商）

---

## 五、退款流程

### 5.1 商户调用退款接口
```
POST /api/pay/refundOrder
Body:
{
  "mchRefundNo": "R20260530001",
  "payOrderId": "P20260530001",
  "refundAmount": 500,
  "refundReason": "用户申请退款",
  "notifyUrl": "https://商户回调地址"
}
```

### 5.2 退款规则
- ✅ 支持全额退款
- ✅ 支持部分退款（可多次，总额不超过原订单）
- ⏰ 退款窗口期：信用卡通常 180 天
- 🔄 退款状态可能为 `pending`，需等待 `charge.refunded` Webhook 确认

---

## 六、测试用例

### 6.1 测试卡号
Stripe 提供测试卡号用于沙箱测试：

| 卡号 | 场景 |
|------|------|
| `4242 4242 4242 4242` | 支付成功 |
| `4000 0000 0000 0002` | 卡被拒（generic_decline） |
| `4000 0025 0000 3155` | 需要 3D Secure 认证 |
| `4000 0000 0000 9995` | 余额不足 |

- 有效期：任意未来日期（如 `12/30`）
- CVV：任意 3 位数字（如 `123`）
- 邮编：任意 5 位（如 `12345`）

### 6.2 测试货币
建议在测试环境覆盖：
- USD（美元）
- EUR（欧元）
- JPY（日元，零小数位特殊场景）
- GBP（英镑）

---

## 七、注意事项与坑

### 7.1 金额单位
- Stripe 接收**最小货币单位**（美元为美分，1 USD = 100）
- JeePay 内部金额也是分，**无需额外换算**
- 例外：JPY、KRW 等零小数币种，Stripe 同样要求传整数（如 1000 日元传 `1000`）

### 7.2 PCI DSS 合规
- ❌ **绝不在后端收集卡号**，必须使用前端 Stripe Elements
- ❌ **绝不存储卡号到数据库**
- ✅ 后端只接收 PaymentIntent ID 和 metadata

### 7.3 Webhook 签名校验
- **必须使用原始 payload**，不能用 JSON 反序列化后再序列化
- 签名校验失败说明请求被篡改，**直接丢弃**

### 7.4 幂等性
- Stripe 可能因网络重试发送重复 Webhook
- JeePay 框架层已通过订单状态校验保证幂等
- 业务层无需额外处理

### 7.5 多商户隔离
- 每个商户使用独立的 Stripe Account
- `Stripe.apiKey` 是 SDK 全局静态变量，高并发场景需注意（已在 `StripeKit` 中每次调用前重置）

---

## 八、上线检查清单

- [ ] 切换为生产 API Key（`sk_live_xxx`）
- [ ] 配置生产 Webhook URL（HTTPS 必须）
- [ ] 在 Stripe Dashboard 提交激活账户（KYC）
- [ ] 配置支付方式（卡 / Apple Pay / Google Pay 等）
- [ ] 配置 Stripe 财务结算账户（接收资金）
- [ ] 测试覆盖：支付成功 / 失败 / 3DS / 退款 / Webhook 重试
- [ ] 监控告警：Webhook 失败率、支付失败率
