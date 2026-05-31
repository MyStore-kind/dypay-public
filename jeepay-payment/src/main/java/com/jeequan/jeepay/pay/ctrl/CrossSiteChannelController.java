/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.ctrl;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.pay.service.CrossSiteChannelService;
import com.jeequan.jeepay.service.impl.CrossSitePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨站收银台 - 通道入口
 *
 * URL：
 *   POST /api/cross-site/pay/{payToken}/prepareStripe
 *   POST /api/cross-site/pay/{payToken}/preparePaypal
 *   POST /api/cross-site/pay/webhook/stripe   ← Stripe 配置该地址
 *   GET  /api/cross-site/pay/{payToken}/status
 *
 * 部署在 jeepay-payment 模块（端口 9216 等），与商户支付接口同一进程便于使用 stripe-java
 *
 * @author 反风控改造组
 */
@RestController
@RequestMapping("/api/cross-site/pay")
public class CrossSiteChannelController {

    private static final Logger logger = LoggerFactory.getLogger(CrossSiteChannelController.class);

    @Autowired private CrossSiteChannelService channelService;
    @Autowired private CrossSitePushService pushService;

    /** 创建 Stripe PaymentIntent，返回 client_secret 给前端 */
    @PostMapping("/{payToken}/prepareStripe")
    public ApiRes<Object> prepareStripe(@PathVariable String payToken) {
        CrossSitePushRecord rec = pushService.loadByPayToken(payToken);
        CrossSiteChannelService.PrepareResult r = channelService.prepareStripe(rec);
        return toApiRes(r);
    }

    /** 拉起 PayPal SDK 所需的 clientId 与 orderId */
    @PostMapping("/{payToken}/preparePaypal")
    public ApiRes<Object> preparePaypal(@PathVariable String payToken) {
        CrossSitePushRecord rec = pushService.loadByPayToken(payToken);
        CrossSiteChannelService.PrepareResult r = channelService.preparePaypal(rec);
        return toApiRes(r);
    }

    /** PayPal 前端 onApprove 后调用：服务端 capture 完成扣款 */
    @PostMapping("/{payToken}/capturePaypal")
    public ApiRes<Object> capturePaypal(@PathVariable String payToken) {
        CrossSitePushRecord rec = pushService.loadByPayToken(payToken);
        boolean ok = channelService.capturePaypal(rec);
        JSONObject resp = new JSONObject();
        resp.put("ok", ok);
        resp.put("state", rec == null ? null : rec.getState());
        return ok ? ApiRes.ok(resp) : ApiRes.customFail("capture 失败");
    }

    /**
     * Stripe Webhook 入口
     * 不能用 RequestBody JSON 自动反序列化（会破坏签名所需的原始字节）
     */
    @PostMapping("/webhook/stripe")
    public String stripeWebhook(@RequestBody String payload,
                                @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        boolean ok = channelService.handleStripeWebhook(payload, sig);
        // 永远返回 200 + JSON，避免 Stripe 重试风暴
        if (!ok) logger.warn("[CrossSite#StripeWebhook] handled=false");
        return "{\"received\":true}";
    }

    /**
     * PayPal Webhook 入口
     *
     * 在 PayPal Dashboard 配置：
     *   Endpoint URL: https://pay.dypay.com/api/cross-site/pay/webhook/paypal
     *   Events: PAYMENT.CAPTURE.COMPLETED / PAYMENT.CAPTURE.DENIED /
     *           PAYMENT.CAPTURE.REFUNDED / CHECKOUT.ORDER.APPROVED /
     *           CUSTOMER.DISPUTE.CREATED
     *
     * PayPal 通过 HTTP Header 传 6 个签名字段，我们透传给 verifyWebhookSignature
     */
    @PostMapping("/webhook/paypal")
    public String paypalWebhook(@RequestBody String payload,
                                javax.servlet.http.HttpServletRequest req) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        // PayPal 6 个签名相关头都用大写传，统一收集
        String[] keys = {
                "PAYPAL-AUTH-ALGO", "PAYPAL-CERT-URL",
                "PAYPAL-TRANSMISSION-ID", "PAYPAL-TRANSMISSION-SIG",
                "PAYPAL-TRANSMISSION-TIME", "PAYPAL-AUTH-VERSION"
        };
        for (String k : keys) {
            String v = req.getHeader(k);
            if (v != null) headers.put(k, v);
        }
        boolean ok = channelService.handlePaypalWebhook(payload, headers);
        if (!ok) logger.warn("[CrossSite#PaypalWebhook] handled=false");
        // 同样永远返回 200 防风暴
        return "{\"received\":true}";
    }

    /** 查询订单当前状态（前端付款完成后轮询） */
    @GetMapping("/{payToken}/status")
    public ApiRes<Object> status(@PathVariable String payToken) {
        CrossSitePushRecord rec = pushService.loadByPayToken(payToken);
        if (rec == null) return ApiRes.customFail("订单不存在");
        JSONObject resp = new JSONObject();
        resp.put("state", rec.getState());
        resp.put("paid_at", rec.getPaidAt() == null ? null : rec.getPaidAt().getTime());
        resp.put("return_url", rec.getReturnUrl());
        resp.put("decision", rec.getRiskDecision());
        return ApiRes.ok(resp);
    }

    private ApiRes<Object> toApiRes(CrossSiteChannelService.PrepareResult r) {
        if (r == null || !r.success) {
            return ApiRes.customFail(r == null ? "未知错误" : r.message);
        }
        JSONObject d = new JSONObject();
        d.put("provider", r.provider);
        d.put("publishable_key", r.publishableKey);
        if (r.intentId != null) d.put("intent_id", r.intentId);
        if (r.clientSecret != null) d.put("client_secret", r.clientSecret);
        return ApiRes.ok(d);
    }
}
