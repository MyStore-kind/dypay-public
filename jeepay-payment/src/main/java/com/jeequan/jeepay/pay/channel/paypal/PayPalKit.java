/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.exception.BizException;
import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.controllers.PaymentsController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderApplicationContext;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCreateInput;
import com.paypal.sdk.models.PurchaseUnitRequest;
import com.paypal.sdk.models.Refund;
import com.paypal.sdk.models.RefundRequest;
import com.paypal.sdk.models.CapturesRefundInput;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PayPal 通道工具类（基于官方 paypal-server-sdk）
 *
 * 关键设计：
 * - 使用官方 SDK 调用 Orders / Payments API
 * - 客户端按 clientId 缓存，避免重复构造
 * - Webhook 校验官方 SDK 未直接暴露统一方法，仍用 REST 接口 + RestTemplate
 *
 * 金额单位换算：JeePay 用分，PayPal 用主单位字符串（带小数点）
 */
public class PayPalKit {

    private static final Logger logger = LoggerFactory.getLogger(PayPalKit.class);

    /** SDK Client 缓存：clientId+env -> Client */
    private static final ConcurrentHashMap<String, PaypalServerSdkClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    /** RestTemplate 用于 Webhook 校验（SDK 未提供统一封装） */
    private static final RestTemplate REST = new RestTemplate();

    public static JSONObject parseConfig(String configJsonStr) {
        if (StringUtils.isBlank(configJsonStr)) {
            throw new BizException("PayPal 渠道未配置参数");
        }
        try {
            return JSONObject.parseObject(configJsonStr);
        } catch (Exception e) {
            throw new BizException("PayPal 渠道配置解析失败");
        }
    }

    /**
     * 是否沙箱
     */
    public static boolean isSandbox(JSONObject config) {
        return "true".equalsIgnoreCase(config.getString(PayPalConfig.FIELD_SANDBOX));
    }

    public static String getBaseUrl(JSONObject config) {
        return isSandbox(config) ? PayPalConfig.API_SANDBOX : PayPalConfig.API_LIVE;
    }

    /**
     * 获取 SDK 客户端（按 clientId+环境 缓存）
     * 为什么缓存：避免每次请求都重建 HttpClient 与 OAuth 状态
     */
    public static PaypalServerSdkClient getClient(JSONObject config) {
        String clientId = config.getString(PayPalConfig.FIELD_CLIENT_ID);
        String clientSecret = config.getString(PayPalConfig.FIELD_CLIENT_SECRET);
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new BizException("PayPal Client ID/Secret 未配置");
        }
        Environment env = isSandbox(config) ? Environment.SANDBOX : Environment.PRODUCTION;
        String cacheKey = clientId + "@" + env.name();
        return CLIENT_CACHE.computeIfAbsent(cacheKey, k -> new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, clientSecret).build())
                .environment(env)
                .build());
    }

    /**
     * 创建 PayPal Order（基于官方 SDK）
     */
    public static Order createOrder(JSONObject config, Long amount, String currency,
                                    String payOrderId, String returnUrl, String cancelUrl) {
        PaypalServerSdkClient client = getClient(config);
        OrdersController orders = client.getOrdersController();

        // amount 主单位
        String value = convertToMajorUnit(amount, currency);

        AmountWithBreakdown amountObj = new AmountWithBreakdown.Builder()
                .currencyCode(currency.toUpperCase())
                .value(value)
                .build();

        PurchaseUnitRequest unit = new PurchaseUnitRequest.Builder(amountObj)
                .customId(payOrderId)  // 关键：用于 Webhook 关联
                .build();

        OrderApplicationContext appCtx = new OrderApplicationContext.Builder()
                .returnUrl(returnUrl)
                .cancelUrl(cancelUrl)
                .userAction(com.paypal.sdk.models.OrderApplicationContextUserAction.PAY_NOW)
                .build();

        OrderRequest req = new OrderRequest.Builder(CheckoutPaymentIntent.CAPTURE, Arrays.asList(unit))
                .applicationContext(appCtx)
                .build();

        OrdersCreateInput input = new OrdersCreateInput.Builder(req)
                .paypalRequestId(payOrderId)  // 幂等键
                .build();

        try {
            ApiResponse<Order> resp = orders.ordersCreate(input);
            return resp.getResult();
        } catch (ApiException e) {
            logger.error("[PayPal] 创建订单 API 异常 payOrderId={}", payOrderId, e);
            throw new BizException("PayPal 下单失败：" + e.getMessage());
        } catch (Exception e) {
            logger.error("[PayPal] 创建订单失败 payOrderId={}", payOrderId, e);
            throw new BizException("PayPal 下单失败：" + e.getMessage());
        }
    }

    /**
     * 退款（基于 capture_id）
     */
    public static Refund refund(JSONObject config, String captureId, Long refundAmount,
                                String currency, String refundOrderId) {
        PaypalServerSdkClient client = getClient(config);
        PaymentsController payments = client.getPaymentsController();

        Money money = new Money.Builder()
                .currencyCode(currency.toUpperCase())
                .value(convertToMajorUnit(refundAmount, currency))
                .build();

        RefundRequest req = new RefundRequest.Builder()
                .amount(money)
                .invoiceId(refundOrderId)
                .build();

        CapturesRefundInput input = new CapturesRefundInput.Builder(captureId)
                .paypalRequestId(refundOrderId)
                .body(req)
                .build();

        try {
            ApiResponse<Refund> resp = payments.capturesRefund(input);
            return resp.getResult();
        } catch (ApiException e) {
            logger.error("[PayPal] 退款 API 异常 captureId={}", captureId, e);
            throw new BizException("PayPal 退款失败：" + e.getMessage());
        } catch (Exception e) {
            logger.error("[PayPal] 退款失败 captureId={}", captureId, e);
            throw new BizException("PayPal 退款失败：" + e.getMessage());
        }
    }

    /**
     * Webhook 签名校验（REST 直接调用 verify-webhook-signature）
     * 注意：SDK 暂未封装该接口，使用 RestTemplate 调用
     */
    public static boolean verifyWebhook(JSONObject config, Map<String, String> headers, String body) {
        try {
            PaypalServerSdkClient client = getClient(config);
            // 复用 SDK 内部的 access token：但 SDK 没有暴露 getter，需走 RestTemplate 独立认证
            // 这里使用一次性 OAuth 调用获取 token（频率低，可接受）
            String token = obtainAccessToken(config);

            String url = getBaseUrl(config) + "/v1/notifications/verify-webhook-signature";
            JSONObject req = new JSONObject();
            req.put("auth_algo", headers.get("PAYPAL-AUTH-ALGO"));
            req.put("cert_url", headers.get("PAYPAL-CERT-URL"));
            req.put("transmission_id", headers.get("PAYPAL-TRANSMISSION-ID"));
            req.put("transmission_sig", headers.get("PAYPAL-TRANSMISSION-SIG"));
            req.put("transmission_time", headers.get("PAYPAL-TRANSMISSION-TIME"));
            req.put("webhook_id", config.getString(PayPalConfig.FIELD_WEBHOOK_ID));
            req.put("webhook_event", JSONObject.parseObject(body));

            HttpHeaders h = new HttpHeaders();
            h.set("Authorization", "Bearer " + token);
            h.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = REST.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(req.toJSONString(), h), String.class);
            JSONObject result = JSONObject.parseObject(resp.getBody());
            return "SUCCESS".equalsIgnoreCase(result.getString("verification_status"));
        } catch (Exception e) {
            logger.error("[PayPal] Webhook 校验失败", e);
            return false;
        }
    }

    /**
     * 直接 OAuth 获取 access_token（仅用于 Webhook 校验）
     * SDK 的 ClientCredentialsAuth 内部会管理 token，但未暴露读取接口
     */
    private static String obtainAccessToken(JSONObject config) {
        String clientId = config.getString(PayPalConfig.FIELD_CLIENT_ID);
        String clientSecret = config.getString(PayPalConfig.FIELD_CLIENT_SECRET);
        String url = getBaseUrl(config) + "/v1/oauth2/token";
        String basicAuth = java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + basicAuth);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> resp = REST.exchange(url, HttpMethod.POST,
                new HttpEntity<>("grant_type=client_credentials", headers), String.class);
        return JSONObject.parseObject(resp.getBody()).getString("access_token");
    }

    /**
     * 金额从分转主单位字符串
     */
    public static String convertToMajorUnit(Long minorAmount, String currency) {
        if (minorAmount == null) return "0.00";
        if (isZeroDecimalCurrency(currency)) {
            return String.valueOf(minorAmount);
        }
        return new BigDecimal(minorAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean isZeroDecimalCurrency(String currency) {
        if (currency == null) return false;
        String c = currency.toUpperCase();
        return "JPY".equals(c) || "KRW".equals(c) || "VND".equals(c);
    }
}
