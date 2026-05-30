/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

/**
 * PayPal 通道配置常量
 *
 * 说明：PayPal 采用 OAuth2 + REST API（v2 Orders / v2 Payments）
 *      不引入官方 SDK（兼容性差且依赖重），直接 HTTP 调用
 */
public class PayPalConfig {

    /** if_code */
    public static final String IF_CODE = "paypal";

    /** 支付方式 */
    public static final String WAY_CODE_WALLET = "paypal_wallet";

    /** 配置字段 */
    public static final String FIELD_CLIENT_ID = "clientId";
    public static final String FIELD_CLIENT_SECRET = "clientSecret";
    public static final String FIELD_SANDBOX = "sandbox";        // "true"/"false"
    public static final String FIELD_WEBHOOK_ID = "webhookId";

    /** API Base URL */
    public static final String API_LIVE = "https://api-m.paypal.com";
    public static final String API_SANDBOX = "https://api-m.sandbox.paypal.com";

    /** PayPal 订单状态 */
    public static final String ORDER_STATUS_CREATED = "CREATED";
    public static final String ORDER_STATUS_APPROVED = "APPROVED";
    public static final String ORDER_STATUS_COMPLETED = "COMPLETED";
    public static final String ORDER_STATUS_VOIDED = "VOIDED";

    /** Webhook 事件 */
    public static final String EVENT_ORDER_APPROVED = "CHECKOUT.ORDER.APPROVED";
    public static final String EVENT_CAPTURE_COMPLETED = "PAYMENT.CAPTURE.COMPLETED";
    public static final String EVENT_CAPTURE_DENIED = "PAYMENT.CAPTURE.DENIED";
    public static final String EVENT_CAPTURE_REFUNDED = "PAYMENT.CAPTURE.REFUNDED";
    /** 拒付事件（CUSTOMER.DISPUTE.* / RISK.DISPUTE.*） */
    public static final String EVENT_DISPUTE_CREATED = "CUSTOMER.DISPUTE.CREATED";
    public static final String EVENT_DISPUTE_RESOLVED = "CUSTOMER.DISPUTE.RESOLVED";
    public static final String EVENT_DISPUTE_UPDATED = "CUSTOMER.DISPUTE.UPDATED";
    public static final String EVENT_RISK_DISPUTE_CREATED = "RISK.DISPUTE.CREATED";

    /** 支付方式 - 新增信用卡（Advanced Card Processing） */
    public static final String WAY_CODE_CARD = "paypal_card";

    /** custom_id 用途：在 PayPal 侧关联 JeePay 订单号 */
    public static final String CUSTOM_ID_KEY = "jeepay_order_id";
}
