/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal.payway;

import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * PayPal 钱包下单响应
 *
 * 前端使用：直接跳转 approveUrl 让用户去 PayPal 完成支付
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PayPalWalletOrderRS extends AbstractRS {

    /** PayPal Order ID */
    private String paypalOrderId;

    /** 跳转链接（买家批准地址） */
    private String approveUrl;
}
