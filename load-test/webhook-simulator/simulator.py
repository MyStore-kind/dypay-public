"""
Stripe Webhook 模拟器
====================
按照配置比例向被测系统的 Webhook 端点发送事件，用于压测场景中模拟真实 Stripe 回调流量。

事件比例：
- 80% payment_intent.succeeded
- 10% payment_intent.payment_failed
- 5%  charge.refunded
- 3%  charge.dispute.created
- 2%  radar.early_fraud_warning
"""
import os
import time
import json
import uuid
import random
import hmac
import hashlib
import argparse
from concurrent.futures import ThreadPoolExecutor

import requests

# 事件类型与权重
EVENT_DISTRIBUTION = [
    ("payment_intent.succeeded", 80),
    ("payment_intent.payment_failed", 10),
    ("charge.refunded", 5),
    ("charge.dispute.created", 3),
    ("radar.early_fraud_warning", 2),
]

# Webhook 签名密钥（占位符）
STRIPE_WEBHOOK_SECRET = os.getenv("STRIPE_WEBHOOK_SECRET", "${STRIPE_WEBHOOK_SECRET}")
TARGET_URL = os.getenv("WEBHOOK_TARGET_URL", "http://localhost:8080/api/v1/webhooks/stripe")


def pick_event_type() -> str:
    """根据权重随机挑选事件类型"""
    types, weights = zip(*EVENT_DISTRIBUTION)
    return random.choices(types, weights=weights, k=1)[0]


def build_payload(event_type: str) -> dict:
    """构造 Stripe 风格的事件 payload"""
    payment_intent_id = f"pi_{uuid.uuid4().hex[:24]}"
    charge_id = f"ch_{uuid.uuid4().hex[:24]}"
    amount = random.choice([999, 1999, 4999, 9900, 19900, 49900])

    data_object = {
        "id": payment_intent_id if "payment_intent" in event_type else charge_id,
        "object": "payment_intent" if "payment_intent" in event_type else "charge",
        "amount": amount,
        "currency": random.choice(["usd", "eur", "gbp", "sgd"]),
        "status": "succeeded" if "succeeded" in event_type else "failed",
        "metadata": {"merchant_id": f"M{random.randint(1, 10):04d}"},
    }

    return {
        "id": f"evt_{uuid.uuid4().hex[:24]}",
        "object": "event",
        "type": event_type,
        "created": int(time.time()),
        "livemode": False,
        "api_version": "2024-04-10",
        "data": {"object": data_object},
    }


def sign_payload(payload: str, secret: str) -> str:
    """生成 Stripe-Signature 头"""
    ts = int(time.time())
    signed = f"{ts}.{payload}".encode()
    sig = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    return f"t={ts},v1={sig}"


def send_one():
    """发送单个 webhook 事件"""
    event_type = pick_event_type()
    payload_dict = build_payload(event_type)
    payload_str = json.dumps(payload_dict, separators=(",", ":"))
    headers = {
        "Content-Type": "application/json",
        "Stripe-Signature": sign_payload(payload_str, STRIPE_WEBHOOK_SECRET),
        "User-Agent": "Stripe/1.0 LoadTestSimulator",
    }
    try:
        r = requests.post(TARGET_URL, data=payload_str, headers=headers, timeout=5)
        return event_type, r.status_code
    except Exception as e:
        return event_type, f"ERR:{e}"


def main():
    parser = argparse.ArgumentParser(description="Stripe Webhook 模拟器")
    parser.add_argument("--rps", type=int, default=50, help="每秒请求数")
    parser.add_argument("--duration", type=int, default=600, help="持续秒数")
    parser.add_argument("--workers", type=int, default=20, help="并发线程数")
    args = parser.parse_args()

    print(f"[Simulator] target={TARGET_URL} rps={args.rps} duration={args.duration}s")
    stats: dict = {}
    end_at = time.time() + args.duration

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        while time.time() < end_at:
            tick_start = time.time()
            futures = [pool.submit(send_one) for _ in range(args.rps)]
            for f in futures:
                etype, code = f.result()
                key = f"{etype}|{code}"
                stats[key] = stats.get(key, 0) + 1
            # 节流到 1 秒
            elapsed = time.time() - tick_start
            if elapsed < 1.0:
                time.sleep(1.0 - elapsed)

    print("\n[Simulator] 完成，统计：")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
