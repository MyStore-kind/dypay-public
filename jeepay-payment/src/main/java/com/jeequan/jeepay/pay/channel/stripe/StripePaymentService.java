/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.pay.channel.AbstractPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import org.springframework.stereotype.Service;

/**
 * Stripe 支付服务
 * 国际信用卡/电子钱包支付通道
 *
 * 接入说明：
 * 1. 在管理后台配置 Stripe 通道参数（Secret Key / Publishable Key / Webhook Secret）
 * 2. 在 Stripe Dashboard 配置 Webhook URL：{paySiteUrl}/api/pay/notify/stripe
 * 3. 支持的事件：payment_intent.succeeded、payment_intent.payment_failed、charge.refunded
 */
@Service
public class StripePaymentService extends AbstractPaymentService {

    @Override
    public String getIfCode() {
        return StripeConfig.IF_CODE;
    }

    /**
     * 是否支持指定支付方式
     * 实际支付逻辑由具体 payway 实现（StripeCard / StripeWallet）
     */
    @Override
    public boolean isSupport(String wayCode) {
        return StripeConfig.WAY_CODE_CARD.equals(wayCode)
                || StripeConfig.WAY_CODE_WALLET.equals(wayCode);
    }

    /**
     * 前置参数校验
     * 注意：Stripe 要求币种为小写 ISO 4217（如 usd），但 JeePay 内部保留大写
     */
    @Override
    public String preCheck(UnifiedOrderRQ bizRQ, PayOrder payOrder) {
        // 校验金额 > 0
        if (payOrder.getAmount() == null || payOrder.getAmount() <= 0) {
            return "订单金额必须大于 0";
        }
        // 校验币种已配置
        if (payOrder.getCurrency() == null) {
            return "Stripe 支付必须指定币种";
        }
        return null;
    }

    /**
     * 调起支付
     * 默认实现：抛异常提示由 payway 子类实现
     * 真实分发由 PayMchNotifyService 等通过 wayCode 路由到具体 payway 子类
     */
    @Override
    public AbstractRS pay(UnifiedOrderRQ bizRQ, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) throws Exception {
        throw new UnsupportedOperationException("请通过具体 payway 实现调用，如 StripeCard / StripeWallet");
    }

    /**
     * 获取渠道配置 JSON（供 payway 子类使用）
     * 为什么独立提取：子类需要解析配置，但配置加载逻辑由父类统一负责
     */
    public JSONObject getChannelConfig(MchAppConfigContext mchAppConfigContext) {
        String configStr = mchAppConfigContext
                .getNormalMchParamsByIfCode(getIfCode())
                .toString();
        return StripeKit.parseConfig(configStr);
    }
}
