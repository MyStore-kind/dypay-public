/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

/**
 * Stripe 配置项常量
 * 注意：所有 Stripe 通道配置统一从 PayInterfaceConfig 中的 JSON 读取
 * 字段名必须与前端配置表单字段保持一致
 */
public class StripeConfig {

    /** Secret Key（服务端密钥），用于调用 Stripe API */
    public static final String FIELD_SECRET_KEY = "secretKey";

    /** Publishable Key（前端公开密钥），用于前端 Stripe.js 收集卡信息 */
    public static final String FIELD_PUBLISHABLE_KEY = "publishableKey";

    /** Webhook Secret，用于校验 Stripe 异步通知签名 */
    public static final String FIELD_WEBHOOK_SECRET = "webhookSecret";

    /** Stripe 接口 if_code */
    public static final String IF_CODE = "stripe";

    /** 支持的支付方式 wayCode */
    public static final String WAY_CODE_CARD = "stripe_card";       // 信用卡
    public static final String WAY_CODE_WALLET = "stripe_wallet";   // 电子钱包

    /** Stripe PaymentIntent 状态 */
    public static final String INTENT_STATUS_SUCCEEDED = "succeeded";
    public static final String INTENT_STATUS_PROCESSING = "processing";
    public static final String INTENT_STATUS_REQUIRES_ACTION = "requires_action";
    public static final String INTENT_STATUS_CANCELED = "canceled";

    /** Webhook 事件类型 */
    public static final String EVENT_PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";
    public static final String EVENT_PAYMENT_INTENT_FAILED = "payment_intent.payment_failed";
    public static final String EVENT_PAYMENT_INTENT_REQUIRES_ACTION = "payment_intent.requires_action";
    public static final String EVENT_CHARGE_REFUNDED = "charge.refunded";
    /** 拒付相关事件 */
    public static final String EVENT_DISPUTE_CREATED = "charge.dispute.created";
    public static final String EVENT_DISPUTE_FUNDS_WITHDRAWN = "charge.dispute.funds_withdrawn";
    public static final String EVENT_DISPUTE_UPDATED = "charge.dispute.updated";
    /** Stripe Radar 早期欺诈预警（在拒付前预警，可主动退款止损） */
    public static final String EVENT_EARLY_FRAUD_WARNING = "radar.early_fraud_warning";

    /** 元数据 key：用于在 Stripe 侧关联 JeePay 订单号 */
    public static final String METADATA_PAY_ORDER_ID = "jeepay_order_id";
    public static final String METADATA_MCH_NO = "jeepay_mch_no";
}
