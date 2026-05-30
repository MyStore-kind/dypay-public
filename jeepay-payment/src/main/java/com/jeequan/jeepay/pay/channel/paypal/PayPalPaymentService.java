/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.pay.channel.AbstractPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import org.springframework.stereotype.Service;

/**
 * PayPal 支付服务
 * 国际电子钱包通道
 */
@Service
public class PayPalPaymentService extends AbstractPaymentService {

    @Override
    public String getIfCode() {
        return PayPalConfig.IF_CODE;
    }

    @Override
    public boolean isSupport(String wayCode) {
        return PayPalConfig.WAY_CODE_WALLET.equals(wayCode)
                || PayPalConfig.WAY_CODE_CARD.equals(wayCode);
    }

    @Override
    public String preCheck(UnifiedOrderRQ bizRQ, PayOrder payOrder) {
        if (payOrder.getAmount() == null || payOrder.getAmount() <= 0) return "订单金额必须大于 0";
        if (payOrder.getCurrency() == null) return "PayPal 支付必须指定币种";
        return null;
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ bizRQ, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {
        throw new UnsupportedOperationException("请通过 payway 子类调用 (PayPalWallet)");
    }

    /** 暴露给 payway 子类使用的配置读取 */
    public JSONObject getChannelConfig(MchAppConfigContext mchAppConfigContext) {
        String configStr = mchAppConfigContext.getNormalMchParamsByIfCode(getIfCode()).toString();
        return PayPalKit.parseConfig(configStr);
    }
}
