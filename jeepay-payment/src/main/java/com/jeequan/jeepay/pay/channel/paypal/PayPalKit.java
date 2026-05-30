/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.exception.BizException;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PayPal 通道工具类（基于 REST API）
 *
 * 关键设计：
 * - 不引入 PayPal SDK（已停止维护），直接 HTTP 调用
 * - access_token 内存缓存（按 client_id），有效期 9 小时
 * - 金额单位转换：JeePay 用分，PayPal 用主单位字符串（带小数点）
 */
public class PayPalKit {

    private static final Logger logger = LoggerFactory.getLogger(PayPalKit.class);

    private static final RestTemplate REST = new RestTemplate();

    /** access_token 缓存：clientId -> {token, expireAt} */
    private static final ConcurrentHashMap<String, TokenCache> TOKEN_CACHE = new ConcurrentHashMap<>();

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

    /** 是否沙箱环境 */
    public static String getBaseUrl(JSONObject config) {
        boolean sandbox = "true".equalsIgnoreCase(config.getString(PayPalConfig.FIELD_SANDBOX));
        return sandbox ? PayPalConfig.API_SANDBOX : PayPalConfig.API_LIVE;
    }

    /**
     * 获取 access_token（带缓存）
     * 为什么缓存：PayPal token 9 小时有效，频繁请求会被限流
     */
    public static String getAccessToken(JSONObject config) {
        String clientId = config.getString(PayPalConfig.FIELD_CLIENT_ID);
        String clientSecret = config.getString(PayPalConfig.FIELD_CLIENT_SECRET);
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new BizException("PayPal Client ID/Secret 未配置");
        }

        TokenCache cached = TOKEN_CACHE.get(clientId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt > now + 60_000L) {
            return cached.token;
        }

        // 请求新 token
        String url = getBaseUrl(config) + "/v1/oauth2/token";
        String basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + basicAuth);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Accept", "application/json");

        HttpEntity<String> request = new HttpEntity<>("grant_type=client_credentials", headers);
        try {
            ResponseEntity<String> resp = REST.exchange(url, HttpMethod.POST, request, String.class);
            JSONObject body = JSONObject.parseObject(resp.getBody());
            String token = body.getString("access_token");
            int expiresIn = body.getIntValue("expires_in");
            TOKEN_CACHE.put(clientId, new TokenCache(token, now + expiresIn * 1000L));
            return token;
        } catch (Exception e) {
            logger.error("[PayPal] 获取 access_token 失败", e);
            throw new BizException("PayPal 认证失败：" + e.getMessage());
        }
    }

    /**
     * 创建 PayPal Order
     *
     * @param amount   金额（分）
     * @param currency 币种（大写 ISO 4217）
     * @return PayPal Order JSON（含 id 和 links）
     */
    public static JSONObject createOrder(JSONObject config, Long amount, String currency,
                                          String payOrderId, String returnUrl, String cancelUrl) {
        String token = getAccessToken(config);
        String url = getBaseUrl(config) + "/v2/checkout/orders";

        // 构造请求体
        JSONObject body = new JSONObject();
        body.put("intent", "CAPTURE");

        JSONArray units = new JSONArray();
        JSONObject unit = new JSONObject();
        unit.put("custom_id", payOrderId);  // 关键：用于 Webhook 回查
        JSONObject amountObj = new JSONObject();
        amountObj.put("currency_code", currency.toUpperCase());
        amountObj.put("value", convertToMajorUnit(amount, currency));
        unit.put("amount", amountObj);
        units.add(unit);
        body.put("purchase_units", units);

        JSONObject appCtx = new JSONObject();
        appCtx.put("return_url", returnUrl);
        appCtx.put("cancel_url", cancelUrl);
        appCtx.put("user_action", "PAY_NOW");
        body.put("application_context", appCtx);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("PayPal-Request-Id", payOrderId);  // 幂等性

        try {
            ResponseEntity<String> resp = REST.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body.toJSONString(), headers), String.class);
            return JSONObject.parseObject(resp.getBody());
        } catch (Exception e) {
            logger.error("[PayPal] 创建订单失败 payOrderId={}", payOrderId, e);
            throw new BizException("PayPal 下单失败：" + e.getMessage());
        }
    }

    /**
     * 退款（基于 capture_id）
     */
    public static JSONObject refund(JSONObject config, String captureId, Long refundAmount,
                                     String currency, String refundOrderId) {
        String token = getAccessToken(config);
        String url = getBaseUrl(config) + "/v2/payments/captures/" + captureId + "/refund";

        JSONObject body = new JSONObject();
        JSONObject amountObj = new JSONObject();
        amountObj.put("currency_code", currency.toUpperCase());
        amountObj.put("value", convertToMajorUnit(refundAmount, currency));
        body.put("amount", amountObj);
        body.put("invoice_id", refundOrderId);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("PayPal-Request-Id", refundOrderId);

        try {
            ResponseEntity<String> resp = REST.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body.toJSONString(), headers), String.class);
            return JSONObject.parseObject(resp.getBody());
        } catch (Exception e) {
            logger.error("[PayPal] 退款失败 captureId={}", captureId, e);
            throw new BizException("PayPal 退款失败：" + e.getMessage());
        }
    }

    /**
     * Webhook 签名校验
     * 注意事项：PayPal 使用服务端 API 校验签名（不是本地计算）
     * 必须传入完整的 headers 和 webhook_id
     */
    public static boolean verifyWebhook(JSONObject config, Map<String, String> headers, String body) {
        String token = getAccessToken(config);
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

        try {
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
     * 金额从分转主单位字符串
     * 零小数币种（JPY）直接整数；其余两位小数
     */
    public static String convertToMajorUnit(Long minorAmount, String currency) {
        if (minorAmount == null) return "0.00";
        if (isZeroDecimalCurrency(currency)) {
            // PayPal JPY 也用整数字符串
            return String.valueOf(minorAmount);
        }
        return new BigDecimal(minorAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean isZeroDecimalCurrency(String currency) {
        if (currency == null) return false;
        String c = currency.toUpperCase();
        return "JPY".equals(c) || "KRW".equals(c) || "VND".equals(c);
    }

    private static class TokenCache {
        final String token;
        final long expireAt;
        TokenCache(String token, long expireAt) { this.token = token; this.expireAt = expireAt; }
    }
}
