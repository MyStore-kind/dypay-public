/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.service.impl.CrossSitePushService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A/B 站联动 Webhook 入口（P2）
 *
 * URL: POST /api/anon/cross-site/order/push
 * Body: JSON
 *   {
 *     "client_id": "B-CLIENT-001",
 *     "order_id":  "B-ORDER-XYZ",
 *     "amount":    9999,            // 分
 *     "currency":  "USD",
 *     "ip":        "1.2.3.4",
 *     "device_fingerprint": "fp-xxx",
 *     "user_agent": "...",
 *     "ts":        1780000000000,
 *     "nonce":     "rand-32-bytes",
 *     "sign":      "<HMAC-SHA256 hex>"
 *   }
 *
 * 业务流：B 下单 → 推送至本接口 → A 端创建支付订单 → 客户跳转 A 端付款
 *
 * @author 反风控改造组
 */
@Tag(name = "A/B 站联动")
@RestController
@RequestMapping("/api/anon/cross-site")
public class CrossSitePushController {

    private static final Logger logger = LoggerFactory.getLogger(CrossSitePushController.class);

    @Autowired private CrossSitePushService crossSitePushService;

    /** 收银台前端基地址（如 https://pay.dypay.com）；默认相对路径 */
    @Value("${crossSite.cashier.baseUrl:}")
    private String cashierBaseUrl;

    // ============================================
    // 方案 A：B 站已采集 IP/指纹 → 直接推送
    // ============================================
    @PostMapping("/order/push")
    public ApiRes<Object> push(@RequestBody String rawBody, HttpServletRequest request) {
        JSONObject payload;
        try { payload = JSONObject.parseObject(rawBody); }
        catch (Exception e) { return ApiRes.customFail("invalid_json"); }
        CrossSitePushRecord rec = crossSitePushService.receive(payload, extractClientIp(request));
        return buildResp(rec);
    }

    // ============================================
    // 方案 E：B 站只在后端创单，客户跳转到 A 站收银台
    // ============================================
    @PostMapping("/order/create")
    public ApiRes<Object> create(@RequestBody String rawBody, HttpServletRequest request) {
        JSONObject payload;
        try { payload = JSONObject.parseObject(rawBody); }
        catch (Exception e) { return ApiRes.customFail("invalid_json"); }
        CrossSitePushRecord rec = crossSitePushService.createOrder(payload, extractClientIp(request));
        JSONObject resp = (JSONObject) buildRespRaw(rec);
        if (rec.getPayToken() != null) {
            resp.put("pay_url", buildPayUrl(request, rec.getPayToken()));
            resp.put("expire_at", rec.getExpireAt() == null ? null : rec.getExpireAt().getTime());
        }
        return ApiRes.ok(resp);
    }

    /**
     * 收银台前端调用：上报浏览器指纹
     * Body: { "fingerprint": {...} }
     */
    @PostMapping("/order/{payToken}/collect")
    public ApiRes<Object> collect(@PathVariable String payToken,
                                  @RequestBody String rawBody,
                                  HttpServletRequest request) {
        JSONObject body;
        try { body = rawBody == null || rawBody.isEmpty() ? new JSONObject() : JSONObject.parseObject(rawBody); }
        catch (Exception e) { body = new JSONObject(); }
        JSONObject fp = body.getJSONObject("fingerprint");
        if (fp == null) fp = body; // 兼容直接传指纹对象

        CrossSitePushRecord rec = crossSitePushService.collectFingerprint(
                payToken, extractClientIp(request), request.getHeader("User-Agent"), fp);
        if (rec == null) return ApiRes.customFail("pay_token 无效");

        JSONObject resp = new JSONObject();
        resp.put("decision", rec.getRiskDecision());
        resp.put("score", rec.getRiskScoreSnapshot());
        resp.put("state", rec.getState());
        return ApiRes.ok(resp);
    }

    /** 收银台前端用：查询订单基础信息（脱敏，不返回内部 ID） */
    @GetMapping("/order/{payToken}")
    public ApiRes<Object> view(@PathVariable String payToken) {
        CrossSitePushRecord rec = crossSitePushService.loadByPayToken(payToken);
        if (rec == null) return ApiRes.customFail("订单不存在");
        JSONObject resp = new JSONObject();
        resp.put("amount", rec.getAmount());
        resp.put("currency", rec.getCurrency());
        resp.put("subject", rec.getSubject());
        resp.put("state", rec.getState());
        resp.put("expire_at", rec.getExpireAt() == null ? null : rec.getExpireAt().getTime());
        return ApiRes.ok(resp);
    }

    // ============================================
    // 辅助
    // ============================================
    private ApiRes<Object> buildResp(CrossSitePushRecord rec) {
        return ApiRes.ok(buildRespRaw(rec));
    }

    private Object buildRespRaw(CrossSitePushRecord rec) {
        JSONObject resp = new JSONObject();
        resp.put("id", rec.getId());
        resp.put("state", rec.getState());
        if (rec.getPayOrderId() != null) resp.put("pay_order_id", rec.getPayOrderId());
        if (rec.getRejectReason() != null) resp.put("reject_reason", rec.getRejectReason());
        return resp;
    }

    private String buildPayUrl(HttpServletRequest request, String token) {
        if (cashierBaseUrl != null && !cashierBaseUrl.isEmpty()) {
            return cashierBaseUrl.replaceAll("/$", "") + "/cashier/" + token;
        }
        // 兜底：用当前请求的 scheme + host 构造
        String scheme = request.getScheme();
        String host = request.getHeader("Host");
        return scheme + "://" + host + "/cashier/" + token;
    }

    /** 多层代理时取真实 IP（优先 X-Forwarded-For） */
    private String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty() && !"unknown".equalsIgnoreCase(xff)) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) return real;
        return req.getRemoteAddr();
    }
}
