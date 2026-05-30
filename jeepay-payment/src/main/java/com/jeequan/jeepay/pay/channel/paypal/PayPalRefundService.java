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
import org.springframework.stereotype.Service;

/**
 * PayPal 退款服务
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
        String configStr = mchAppConfigContext.getNormalMchParamsByIfCode(getIfCode()).toString();
        JSONObject config = PayPalKit.parseConfig(configStr);

        // 原渠道订单号即 capture_id
        String currency = payOrder.getCurrency() == null ? "USD" : payOrder.getCurrency();
        JSONObject result = PayPalKit.refund(config, payOrder.getChannelOrderNo(),
                refundOrder.getRefundAmount(),
                currency,
                refundOrder.getRefundOrderId());

        ChannelRetMsg ret = new ChannelRetMsg();
        ret.setChannelOrderId(result.getString("id"));
        String status = result.getString("status");
        switch (status == null ? "" : status) {
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
        // 占位：可调用 GET /v2/payments/refunds/{refundId} 查询
        return null;
    }
}
