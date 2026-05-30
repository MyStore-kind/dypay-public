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
import com.jeequan.jeepay.service.impl.OrderRiskHookService;
import com.jeequan.jeepay.service.impl.RiskDecision;
import com.stripe.model.PaymentIntent;
import org.springframework.beans.factory.annotation.Autowired;
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
 * - PaymentIntent 支持智能 3D Secure：根据风控决策动态切换
 */
@Service("stripePaymentByCardService")
public class StripeCard extends StripePaymentService {

    @Autowired
    private OrderRiskHookService orderRiskHookService;

    @Override
    public String preCheck(UnifiedOrderRQ rq, PayOrder payOrder) {
        return super.preCheck(rq, payOrder);
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {
        // 1. 解析渠道配置
        JSONObject config = getChannelConfig(mchAppConfigContext);

        // 2. 读取风控决策（preCheck 已落库 risk_action/risk_score）
        // 为什么这么做：高风险订单强制 3DS 转移拒付责任，低风险走 automatic 提升转化
        RiskDecision decision = orderRiskHookService.getDecision(payOrder);
        boolean forceThreeDS = decision != null && decision.isForceThreeDS();

        // 3. 创建 PaymentIntent
        PaymentIntent intent = StripeKit.createPaymentIntent(
                config,
                payOrder.getAmount(),
                payOrder.getCurrency(),
                payOrder.getPayOrderId(),
                payOrder.getMchNo(),
                payOrder.getSubject(),
                forceThreeDS
        );

        // 4. 构建响应：返回 client_secret 供前端使用
        // 为什么不直接返回 PaymentIntent：避免泄漏敏感信息（如 metadata 中的内部数据）
        StripeCardOrderRS res = new StripeCardOrderRS();
        res.setClientSecret(intent.getClientSecret());
        res.setIntentId(intent.getId());
        res.setPublishableKey(config.getString("publishableKey"));

        ChannelRetMsg channelRetMsg = new ChannelRetMsg();
        channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
        channelRetMsg.setChannelOrderId(intent.getId());
        res.setChannelRetMsg(channelRetMsg);

        return res;
    }
}

