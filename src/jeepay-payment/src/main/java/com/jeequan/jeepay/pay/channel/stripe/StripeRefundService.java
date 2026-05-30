/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.entity.RefundOrder;
import com.jeequan.jeepay.pay.channel.IRefundService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.refund.RefundOrderRQ;
import com.jeequan.jeepay.pay.rqrs.refund.RefundOrderRS;
import com.stripe.model.Refund;
import org.springframework.stereotype.Service;

/**
 * Stripe 退款服务
 *
 * 支持场景：
 * - 全额退款
 * - 部分退款（多次部分退款需保证总额不超过原订单）
 *
 * 注意事项：
 * - Stripe 退款是异步的：状态可能为 pending，需等待 charge.refunded Webhook 确认
 * - 退款使用 PaymentIntent ID 即可，不需要 Charge ID
 * - 退款窗口期：信用卡通常 180 天
 */
@Service
public class StripeRefundService implements IRefundService {

    @Override
    public String getIfCode() {
        return StripeConfig.IF_CODE;
    }

    /**
     * 退款前置校验
     * Stripe 通道不限制 wayCode，统一支持
     */
    @Override
    public String preCheck(RefundOrderRQ bizRQ, RefundOrder refundOrder, PayOrder payOrder) {
        // 校验原订单为 Stripe 通道
        if (!StripeConfig.IF_CODE.equals(payOrder.getIfCode())) {
            return "原订单非 Stripe 通道，无法使用 Stripe 退款";
        }
        // 校验退款金额
        if (refundOrder.getRefundAmount() == null || refundOrder.getRefundAmount() <= 0) {
            return "退款金额必须大于 0";
        }
        if (refundOrder.getRefundAmount() > payOrder.getAmount()) {
            return "退款金额不能大于原订单金额";
        }
        return null;
    }

    /**
     * 调起退款
     * 注意：返回的 ChannelRetMsg 状态可能为 WAITING（异步退款），需等待 Webhook 更新
     */
    @Override
    public ChannelRetMsg refund(RefundOrderRQ bizRQ, RefundOrder refundOrder, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) throws Exception {
        // 1. 解析渠道配置
        String configStr = mchAppConfigContext
                .getNormalMchParamsByIfCode(getIfCode())
                .toString();
        JSONObject config = StripeKit.parseConfig(configStr);

        // 2. 调用 Stripe 退款
        // 原渠道订单号即为 PaymentIntent ID
        Refund refund = StripeKit.createRefund(
                config,
                payOrder.getChannelOrderNo(),
                refundOrder.getRefundAmount(),
                refundOrder.getRefundOrderId()
        );

        // 3. 转换 Stripe 退款状态为 JeePay 渠道状态
        ChannelRetMsg channelRetMsg = new ChannelRetMsg();
        channelRetMsg.setChannelOrderId(refund.getId());

        // Stripe 退款状态：succeeded / pending / failed / canceled / requires_action
        switch (refund.getStatus()) {
            case "succeeded":
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
                break;
            case "failed":
            case "canceled":
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
                channelRetMsg.setChannelErrMsg(refund.getFailureReason());
                break;
            default:
                // pending / requires_action：等待 Webhook 通知
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
                break;
        }

        return channelRetMsg;
    }

    /**
     * 退款查询
     * 用于主动查询退款状态（兜底，避免 Webhook 漏发）
     */
    @Override
    public ChannelRetMsg query(RefundOrder refundOrder, MchAppConfigContext mchAppConfigContext) throws Exception {
        // TODO 实现 Stripe 退款查询（通过 Refund.retrieve(refundId)）
        // 留作后续优化项
        return null;
    }
}
