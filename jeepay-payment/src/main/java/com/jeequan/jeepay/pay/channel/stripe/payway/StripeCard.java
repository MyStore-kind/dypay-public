/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe.payway;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.pay.channel.stripe.StripeKit;
import com.jeequan.jeepay.pay.channel.stripe.StripePaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import com.stripe.model.PaymentIntent;
import org.springframework.stereotype.Service;

/**
 * Stripe 信用卡支付
 *
 * 业务流程：
 * 1. 后端创建 PaymentIntent，返回 client_secret
 * 2. 前端使用 Stripe.js + Elements 收集卡信息
 * 3. 前端调用 stripe.confirmCardPayment(clientSecret) 完成支付
 * 4. 支付成功后 Stripe 通过 Webhook 通知后端
 *
 * 注意事项：
 * - 不在后端收集卡号，避免 PCI DSS 合规问题
 * - PaymentIntent 自动支持 3D Secure（SCA 强客户认证）
 */
@Service("stripePaymentByCardService") // bean name 全局唯一
public class StripeCard extends StripePaymentService {

    @Override
    public String preCheck(UnifiedOrderRQ rq, PayOrder payOrder) {
        return super.preCheck(rq, payOrder);
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {
        // 1. 解析渠道配置
        JSONObject config = getChannelConfig(mchAppConfigContext);

        // 2. 创建 PaymentIntent
        PaymentIntent intent = StripeKit.createPaymentIntent(
                config,
                payOrder.getAmount(),
                payOrder.getCurrency(),
                payOrder.getPayOrderId(),
                payOrder.getMchNo(),
                payOrder.getSubject()
        );

        // 3. 构建响应：返回 client_secret 供前端使用
        // 为什么不直接返回 PaymentIntent：避免泄漏敏感信息（如 metadata 中的内部数据）
        StripeCardOrderRS res = new StripeCardOrderRS();
        res.setClientSecret(intent.getClientSecret());
        res.setIntentId(intent.getId());
        res.setPublishableKey(config.getString("publishableKey"));

        // 4. 设置渠道返回信息
        ChannelRetMsg channelRetMsg = new ChannelRetMsg();
        channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING); // 等待用户在前端完成支付
        channelRetMsg.setChannelOrderId(intent.getId());
        res.setChannelRetMsg(channelRetMsg);

        return res;
    }
}
