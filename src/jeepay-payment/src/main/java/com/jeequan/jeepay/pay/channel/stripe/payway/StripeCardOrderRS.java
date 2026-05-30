/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe.payway;

import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Stripe 信用卡支付下单响应
 *
 * 前端使用方式：
 *   const stripe = Stripe(publishableKey);
 *   const { error } = await stripe.confirmCardPayment(clientSecret, {
 *     payment_method: { card: elements.getElement('card') }
 *   });
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StripeCardOrderRS extends AbstractRS {

    /** Stripe PaymentIntent ID（同时也是渠道订单号） */
    private String intentId;

    /** 客户端密钥，前端 Stripe.js 使用此密钥确认支付 */
    private String clientSecret;

    /** Stripe Publishable Key，前端初始化 Stripe SDK 使用 */
    private String publishableKey;
}
