/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 上游关停回执处理（P1）
 *
 * 当三方公司（Stripe/PayPal/其他）主动通知账号被关停 / 限流时，
 * 通过 Webhook 入口调用本服务，完成：
 *   1. HMAC-SHA256 验签（防伪造）
 *   2. 标记 channel_account.closed_by_upstream=1
 *   3. health_status -> HEALTH_ERROR（路由层立即剔除）
 *   4. state -> STATE_FROZEN（运营手动解除前不参与任何业务）
 *   5. 落原始 payload + 时间戳便于排查
 *   6. 触发熔断引擎 SUSPEND 动作（产生告警 + Redis 标记）
 *
 * 设计：
 *   - 验签密钥从 ChannelAccount.config_params(JSON) 的 upstream_webhook_secret 读取
 *   - 不抛异常：失败仅记日志，不要让上游 Webhook 重试风暴
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Service
public class UpstreamCallbackService {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamCallbackService.class);

    @Autowired private ChannelAccountService channelAccountService;
    @Autowired private RiskCircuitBreakerEngine circuitBreakerEngine;
    @Autowired private NotificationService notificationService;

    /**
     * 处理上游关停回执。
     *
     * @param accountId    通道账号 ID（必填）
     * @param signature    上游签名（HMAC-SHA256 hex）
     * @param rawPayload   原始 JSON 报文（用于签名校验，必须与上游计算时完全一致）
     * @return true=处理成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(String accountId, String signature, String rawPayload) {
        if (accountId == null || accountId.isEmpty() || rawPayload == null) {
            logger.warn("[UpstreamCB] 参数缺失 accountId={} payloadNull={}", accountId, rawPayload == null);
            return false;
        }

        ChannelAccount acc = channelAccountService.getById(accountId);
        if (acc == null) {
            logger.warn("[UpstreamCB] 账号不存在 accountId={}", accountId);
            return false;
        }

        // ===== 1. 验签 =====
        // 验签密钥放在 config_params(JSON) 的 upstream_webhook_secret 字段
        // 注：仅在密钥存在时才强制验签，便于灰度上线
        String secret = extractWebhookSecret(acc.getConfigParams());
        if (secret != null && !secret.isEmpty()) {
            String expected = hmacSha256Hex(secret, rawPayload);
            if (!expected.equalsIgnoreCase(signature)) {
                logger.error("[UpstreamCB] 验签失败 accountId={} expected={} got={}",
                        accountId, expected, signature);
                return false;
            }
        } else {
            logger.warn("[UpstreamCB] 未配置 upstream_webhook_secret，跳过验签 accountId={}", accountId);
        }

        // ===== 2. 解析 payload（容错） =====
        JSONObject payload;
        try { payload = JSONObject.parseObject(rawPayload); }
        catch (Exception e) { payload = new JSONObject(); payload.put("rawText", rawPayload); }

        String reason = payload.getString("reason");
        if (reason == null) reason = "上游主动关停";

        // ===== 3. 更新账号状态 =====
        ChannelAccount upd = new ChannelAccount()
                .setAccountId(accountId)
                .setClosedByUpstream((byte) 1)
                .setCircuitCallbackReceivedAt(new Date())
                .setCircuitCallbackPayload(rawPayload.length() > 60000
                        ? rawPayload.substring(0, 60000) : rawPayload)
                .setHealthStatus(ChannelAccount.HEALTH_ERROR)
                .setState(ChannelAccount.STATE_FROZEN);
        channelAccountService.updateById(upd);

        // ===== 4. 触发熔断（告警 + Redis 标记，便于路由层快速判断） =====
        JSONObject metrics = new JSONObject();
        metrics.put("source", "upstream_callback");
        metrics.put("ifCode", acc.getIfCode());
        metrics.put("payloadKeys", payload.keySet());
        circuitBreakerEngine.triggerForAccount(
                accountId,
                RiskCircuitBreakerEngine.ACTION_SUSPEND,
                "上游关停回执：" + reason,
                metrics);

        // ===== 5. 告警 =====
        notificationService.notify(
                "【严重】上游通道账号被关停",
                String.format("账号 %s (%s) 收到上游关停回执，已自动冻结。原因：%s",
                        accountId, acc.getIfCode(), reason));

        logger.warn("[UpstreamCB] 已处理 accountId={} ifCode={} reason={}",
                accountId, acc.getIfCode(), reason);
        return true;
    }

    /** 从 config_params JSON 中提取 upstream_webhook_secret */
    private String extractWebhookSecret(String configParams) {
        if (configParams == null || configParams.isEmpty()) return null;
        try {
            JSONObject obj = JSONObject.parseObject(configParams);
            return obj.getString("upstream_webhook_secret");
        } catch (Exception e) {
            return null;
        }
    }

    /** HMAC-SHA256 → hex 字符串（小写） */
    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 计算失败", e);
        }
    }
}
