/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.entity.RefundOrder;
import com.jeequan.jeepay.pay.channel.IRefundService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.refund.RefundOrderRQ;
import com.paypal.sdk.models.Refund;
import org.springframework.stereotype.Service;

/**
 * PayPal 退款服务（基于官方 SDK）
 *
 * 注意：PayPal 退款基于 capture_id，不是 order_id
 * 我们在订单成功时把 capture_id 存入 PayOrder.channelOrderNo
 */
@Service
public class PayPalRefundService implements IRefundService {

    @Override
    public String getIfCode() {
        return PayPalConfig.IF_CODE;
    }

    @Override
    public String preCheck(RefundOrderRQ bizRQ, RefundOrder refundOrder, PayOrder payOrder) {
        if (!PayPalConfig.IF_CODE.equals(payOrder.getIfCode())) {
            return "原订单非 PayPal 通道";
        }
        if (refundOrder.getRefundAmount() == null || refundOrder.getRefundAmount() <= 0) {
            return "退款金额必须大于 0";
        }
        if (refundOrder.getRefundAmount() > payOrder.getAmount()) {
            return "退款金额不能大于原订单金额";
        }
        return null;
    }

    @Override
    public ChannelRetMsg refund(RefundOrderRQ bizRQ, RefundOrder refundOrder, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) throws Exception {

        // 0. 幂等校验：已完成/已失败的退款不再调用 PayPal API
        // 为什么这么做：JeePay 框架在通道异常时会重试，需避免重复扣商户款
        if (refundOrder.getState() == RefundOrder.STATE_SUCCESS) {
            ChannelRetMsg ret = new ChannelRetMsg();
            ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
            ret.setChannelOrderId(refundOrder.getChannelOrderNo());
            return ret;
        }
        if (refundOrder.getState() == RefundOrder.STATE_FAIL) {
            ChannelRetMsg ret = new ChannelRetMsg();
            ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
            ret.setChannelErrMsg("退款单已是失败状态");
            return ret;
        }

        String configStr = mchAppConfigContext.getNormalMchParamsByIfCode(getIfCode()).toString();
        JSONObject config = PayPalKit.parseConfig(configStr);

        // 原渠道订单号即 capture_id
        // PayPalKit.refund 内部已使用 refundOrderId 作为 paypalRequestId（幂等键），
        // PayPal 端会对相同 paypalRequestId 返回同一笔退款，进一步保证幂等
        String currency = payOrder.getCurrency() == null ? "USD" : payOrder.getCurrency();
        Refund result = PayPalKit.refund(config, payOrder.getChannelOrderNo(),
                refundOrder.getRefundAmount(),
                currency,
                refundOrder.getRefundOrderId());

        ChannelRetMsg ret = new ChannelRetMsg();
        ret.setChannelOrderId(result.getId());

        // Refund 状态：COMPLETED / FAILED / CANCELLED / PENDING
        String status = result.getStatus() == null ? "" : result.getStatus().name();
        switch (status) {
            case "COMPLETED":
                ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
                break;
            case "FAILED":
            case "CANCELLED":
                ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
                break;
            default:
                ret.setChannelState(ChannelRetMsg.ChannelState.WAITING);
                break;
        }
        return ret;
    }

    @Override
    public ChannelRetMsg query(RefundOrder refundOrder, MchAppConfigContext mchAppConfigContext) throws Exception {
        // 占位：可调用 PaymentsController.refundsGet(refundId) 查询
        return null;
    }
}
