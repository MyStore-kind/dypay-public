# Stripe Webhook 模拟器

## 功能
按以下比例向被测系统发送 Stripe 风格的 Webhook 事件，用于压测中模拟真实回调流量：

| 事件类型 | 占比 |
|---------|------|
| `payment_intent.succeeded` | 80% |
| `payment_intent.payment_failed` | 10% |
| `charge.refunded` | 5% |
| `charge.dispute.created` | 3% |
| `radar.early_fraud_warning` | 2% |

## 运行
```bash
pip install -r requirements.txt

export STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET}
export WEBHOOK_TARGET_URL=http://localhost:8080/api/v1/webhooks/stripe

python simulator.py --rps 50 --duration 600 --workers 20
```

## 参数
| 参数 | 含义 | 默认 |
|------|------|------|
| `--rps` | 每秒发送事件数 | 50 |
| `--duration` | 持续秒数 | 600 |
| `--workers` | 并发线程数 | 20 |

## 说明
- 所有签名使用 `${STRIPE_WEBHOOK_SECRET}` 占位符，请通过环境变量注入。
- 签名算法对齐 Stripe 官方：`HMAC-SHA256(timestamp.payload, secret)`。
- 模拟器与 Gatling 主链路压测**并行运行**，复现下单 + Webhook 双向流量。
