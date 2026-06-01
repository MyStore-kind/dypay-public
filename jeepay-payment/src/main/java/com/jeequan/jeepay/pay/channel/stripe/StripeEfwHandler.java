/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import com.jeequan.jeepay.service.impl.RiskBlacklistService;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import com.jeequan.jeepay.service.notify.RiskAlertNotifier;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.radar.EarlyFraudWarning;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * R3：Stripe Radar Early Fraud Warning（EFW）自动冻结卡 BIN 处理器
 *
 * 处理链路：
 *   Stripe Webhook (radar.early_fraud_warning)
 *     -> StripeChannelNoticeService.doNotice（已完成签名校验）
 *     -> 本 handler.handle(verifiedEvent)
 *     -> 取出 efw -> 反查 charge -> 取 card.iin（BIN 前 6 位）
 *     -> 写 t_risk_blacklist (list_type=card_bin, ttl=freezeMinutes)
 *     -> 可选触发 RiskAlertNotifier
 *
 * ⚠️ 安全前置：调用方必须先用 Stripe 官方签名算法校验事件（见 StripeKit.verifyAndParseEvent）。
 *   本 handler 不再做二次签名校验——重复校验会浪费 CPU，且会让"已验证 Event 是可信入参"
 *   的契约变得含糊。如果有人在签名校验前调用本方法，那是调用方的 bug，而不是本类应该处理的场景。
 *
 * 失败容忍：
 *   - SDK 反查 charge 失败（网络/apiKey 未设置/资源不存在）→ 仅 log，不抛
 *   - BIN 提取不到 → 仅 log，不抛
 *   - 黑名单写入失败 → 仅 log，不抛
 *   为什么：webhook 必须 30s 内 200 返回；EFW 是辅助信号，丢一次不致命，
 *           Stripe 会重试，且本环节不影响支付主链路。
 *
 * @author 反风控改造组（R3）
 */
@Component
public class StripeEfwHandler {

    private static final Logger logger = LoggerFactory.getLogger(StripeEfwHandler.class);

    /** 默认冻结时长（分钟）—— 与 risk_v3_patch.sql 中 KV 默认值保持一致 */
    private static final int DEFAULT_FREEZE_MINUTES = 30;

    @Autowired
    private RiskThresholdConfigService riskCfg;

    @Autowired
    private RiskBlacklistService riskBlacklistService;

    @Autowired
    private RiskAlertNotifier riskAlertNotifier;

    /**
     * 处理一个已通过签名校验的 EFW 事件。
     *
     * @param verifiedEvent Stripe SDK 解析后的 Event（已验签）；可为 null（兜底跳过）
     */
    public void handle(Event verifiedEvent) {
        if (verifiedEvent == null) {
            return;
        }
        // 1) 总开关：运营可在 t_risk_threshold_config 临时关闭，无须重启
        if (!riskCfg.getBoolean("stripe.efw.enabled", true)) {
            logger.info("[StripeEFW] 开关关闭，忽略 event={}", verifiedEvent.getId());
            return;
        }

        // 2) 取冻结时长
        int freezeMinutes = riskCfg.getNumber("stripe.efw.freeze_minutes",
                BigDecimal.valueOf(DEFAULT_FREEZE_MINUTES)).intValue();
        if (freezeMinutes <= 0) freezeMinutes = DEFAULT_FREEZE_MINUTES;

        // 3) 从 event 取 EFW id —— 优先用 SDK 反序列化，失败兜底走 JSON
        String efwId = extractEfwId(verifiedEvent);
        if (StringUtils.isBlank(efwId)) {
            logger.warn("[StripeEFW] 未取到 EFW id，跳过 event={}", verifiedEvent.getId());
            return;
        }

        // 4) 反查 charge id：EFW payload 上一般直接带 charge 字符串，先尝试就地取
        String chargeId = extractChargeIdFromEvent(verifiedEvent);

        // 5) 若 payload 没有，再调 EarlyFraudWarning.retrieve 反查（外部 SDK 调用，必须 try/catch）
        if (StringUtils.isBlank(chargeId)) {
            try {
                EarlyFraudWarning efw = EarlyFraudWarning.retrieve(efwId);
                if (efw != null) {
                    chargeId = efw.getCharge();
                }
            } catch (Exception e) {
                // 常见原因：Stripe.apiKey 尚未在本进程设置（首次 webhook 早于任何支付）
                // 或者 EFW 已被删除 / 网络异常
                logger.warn("[StripeEFW] EarlyFraudWarning.retrieve 失败 efwId={} err={}",
                        efwId, e.getMessage());
            }
        }

        if (StringUtils.isBlank(chargeId)) {
            logger.warn("[StripeEFW] 未取到 chargeId，跳过 efwId={}", efwId);
            return;
        }

        // 6) Charge.retrieve 拿 card.iin（BIN 前 6 位）
        String bin = null;
        String last4 = null;
        try {
            Charge charge = Charge.retrieve(chargeId);
            if (charge != null && charge.getPaymentMethodDetails() != null
                    && charge.getPaymentMethodDetails().getCard() != null) {
                Charge.PaymentMethodDetails.Card card = charge.getPaymentMethodDetails().getCard();
                // iin = Issuer Identification Number（即 BIN，通常 6 位，部分卡组 8 位）
                bin = card.getIin();
                last4 = card.getLast4();
            }
        } catch (Exception e) {
            logger.warn("[StripeEFW] Charge.retrieve 失败 chargeId={} err={}", chargeId, e.getMessage());
        }

        if (StringUtils.isBlank(bin)) {
            logger.warn("[StripeEFW] 未取到 BIN，跳过 efwId={} chargeId={}", efwId, chargeId);
            return;
        }

        // 7) 写入临时黑名单（幂等：重复 EFW 会延长 expire_at 而不是失败）
        String reason = "stripe_efw:" + efwId;
        boolean ok;
        try {
            ok = riskBlacklistService.addTemporary(
                    RiskBlacklist.TYPE_CARD_BIN, bin, freezeMinutes,
                    "stripe_efw", reason);
        } catch (Exception e) {
            ok = false;
            logger.error("[StripeEFW] 写黑名单异常 bin={} efwId={}", bin, efwId, e);
        }
        logger.info("[StripeEFW] 冻结卡 BIN bin={} ttlMin={} efwId={} chargeId={} ok={}",
                bin, freezeMinutes, efwId, chargeId, ok);

        // 8) 同步告警（运营可关）
        if (riskCfg.getBoolean("stripe.efw.notify_alert", true)) {
            try {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("bin", bin);
                ctx.put("last4", last4 == null ? "" : last4);
                ctx.put("efwId", efwId);
                ctx.put("chargeId", chargeId);
                ctx.put("freezeMinutes", freezeMinutes);
                ctx.put("source", "stripe_efw");
                // 暂复用 HIGH_FREQUENCY_CARD（语义最接近：用卡风险预警）；
                // TODO：后续在 RiskAlertType 中新增 STRIPE_EFW 枚举并切过去，
                //       同时在运营后台维护 risk_alert_template_stripe_efw 模板。
                riskAlertNotifier.send(RiskAlertType.HIGH_FREQUENCY_CARD, ctx);
            } catch (Exception e) {
                logger.warn("[StripeEFW] 告警发送异常（已忽略） efwId={} err={}", efwId, e.getMessage());
            }
        }
    }

    /** 从 Event 取 EFW 主体 id：先尝试 SDK 反序列化，失败回退到 raw payload */
    private String extractEfwId(Event event) {
        try {
            Object obj = event.getDataObjectDeserializer().getObject().orElse(null);
            if (obj instanceof EarlyFraudWarning) {
                return ((EarlyFraudWarning) obj).getId();
            }
        } catch (Exception ignored) {
            // SDK 版本与 webhook API version 不一致时可能抛 EventDataObjectDeserializationException
        }
        // 兜底：从 raw json 拿
        try {
            String raw = event.toJson();
            if (raw == null) return null;
            JSONObject root = JSONObject.parseObject(raw);
            JSONObject data = root == null ? null : root.getJSONObject("data");
            JSONObject o = data == null ? null : data.getJSONObject("object");
            return o == null ? null : o.getString("id");
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 Event payload 直接读 charge id（多数 EFW webhook 已带，不必额外 retrieve） */
    private String extractChargeIdFromEvent(Event event) {
        try {
            Object obj = event.getDataObjectDeserializer().getObject().orElse(null);
            if (obj instanceof EarlyFraudWarning) {
                String c = ((EarlyFraudWarning) obj).getCharge();
                if (StringUtils.isNotBlank(c)) return c;
            }
        } catch (Exception ignored) {}
        try {
            String raw = event.toJson();
            if (raw == null) return null;
            JSONObject root = JSONObject.parseObject(raw);
            JSONObject data = root == null ? null : root.getJSONObject("data");
            JSONObject o = data == null ? null : data.getJSONObject("object");
            return o == null ? null : o.getString("charge");
        } catch (Exception e) {
            return null;
        }
    }
}
