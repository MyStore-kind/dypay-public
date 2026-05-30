/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal.payway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.pay.channel.paypal.PayPalKit;
import com.jeequan.jeepay.pay.channel.paypal.PayPalPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import org.springframework.stereotype.Service;

/**
 * PayPal 钱包支付
 *
 * 业务流程：
 * 1. 后端调用 PayPal Orders v2 API 创建订单，获取 approve link
 * 2. 前端跳转到 approve link，买家在 PayPal 站点登录并批准
 * 3. PayPal 回调 return_url，前端调 capture 接口（或后端兜底捕获）
 * 4. Webhook 通知最终结果
 *
 * 注意：本实现返回 approve link，由前端做跳转
 */
@Service("paypalPaymentByWalletService")
public class PayPalWallet extends PayPalPaymentService {

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {
        JSONObject config = getChannelConfig(mchAppConfigContext);

        // 拼接 return/cancel URL（用商户的 returnUrl，业务侧已配置）
        String returnUrl = rq.getReturnUrl() == null ? "" : rq.getReturnUrl();
        String cancelUrl = returnUrl;

        // 币种从订单读取（PayOrder 已扩展 currency 字段，未设置时默认 USD）
        String currency = payOrder.getCurrency() == null ? "USD" : payOrder.getCurrency();

        JSONObject order = PayPalKit.createOrder(config, payOrder.getAmount(), currency,
                payOrder.getPayOrderId(), returnUrl, cancelUrl);

        // 提取 approve link
        String approveLink = null;
        JSONArray links = order.getJSONArray("links");
        if (links != null) {
            for (int i = 0; i < links.size(); i++) {
                JSONObject l = links.getJSONObject(i);
                if ("approve".equalsIgnoreCase(l.getString("rel"))) {
                    approveLink = l.getString("href");
                    break;
                }
            }
        }

        PayPalWalletOrderRS rs = new PayPalWalletOrderRS();
        rs.setPaypalOrderId(order.getString("id"));
        rs.setApproveUrl(approveLink);

        ChannelRetMsg msg = new ChannelRetMsg();
        msg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
        msg.setChannelOrderId(order.getString("id"));
        rs.setChannelRetMsg(msg);
        return rs;
    }
}
