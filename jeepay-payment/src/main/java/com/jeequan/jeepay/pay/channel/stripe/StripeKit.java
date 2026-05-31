/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.exception.BizException;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Stripe 通道工具类
 * 封装 Stripe SDK 的核心调用，提供给 PaymentService / RefundService / NoticeService 使用
 *
 * 设计要点：
 * - Stripe SDK 通过静态 Stripe.apiKey 设置密钥，需每次调用前覆盖（多商户隔离）
 * - 金额单位：Stripe 接收最小货币单位（美元为美分），与 JeePay 的"分"一致，无需换算
 * - 例外：零小数币种（如 JPY）Stripe 同样要求传入整数，调用方需保证传入整数
 */
public class StripeKit {

    private static final Logger logger = LoggerFactory.getLogger(StripeKit.class);

    /**
     * 解析渠道配置（JSON 字符串 -> 配置对象）
     * 为什么独立方法：所有通道操作都需要先解析配置，避免重复代码
     */
    public static JSONObject parseConfig(String configJsonStr) {
        if (StringUtils.isBlank(configJsonStr)) {
            throw new BizException("Stripe 渠道未配置参数");
        }
        try {
            return JSONObject.parseObject(configJsonStr);
        } catch (Exception e) {
            throw new BizException("Stripe 渠道配置解析失败");
        }
    }

    /**
     * 设置 Stripe API Key
     * 注意：Stripe SDK 是静态变量，并发场景下需在每次调用前重置
     * 高并发优化方向：可改为 RequestOptions.builder().setApiKey() 方式（待优化）
     */
    private static void setApiKey(JSONObject config) {
        String secretKey = config.getString(StripeConfig.FIELD_SECRET_KEY);
        if (StringUtils.isBlank(secretKey)) {
            throw new BizException("Stripe Secret Key 未配置");
        }
        Stripe.apiKey = secretKey;
    }

    /**
     * 创建 PaymentIntent（支付意图）
     * Stripe 推荐使用 PaymentIntent 替代旧版 Charge API，支持 SCA（强客户认证）
     *
     * @param config    渠道配置 JSON
     * @param amount    金额（最小货币单位，如美分）
     * @param currency  币种（ISO 4217，小写如 usd）
     * @param payOrderId    JeePay 订单号
     * @param mchNo     商户号
     * @param description   订单描述
     * @param forceThreeDS  是否强制 3DS（风控决策）
     * @return PaymentIntent 对象
     */
    public static PaymentIntent createPaymentIntent(JSONObject config, Long amount, String currency,
                                                     String payOrderId, String mchNo, String description,
                                                     boolean forceThreeDS) {
        setApiKey(config);
        try {
            // 元数据：在 Stripe 侧记录 JeePay 订单号，便于 Webhook 回查
            Map<String, String> metadata = new HashMap<>();
            metadata.put(StripeConfig.METADATA_PAY_ORDER_ID, payOrderId);
            metadata.put(StripeConfig.METADATA_MCH_NO, mchNo);

            // 关键：根据风控决策动态切换 3DS 策略
            // forceThreeDS=true：request_three_d_secure="any"  -> 总是要求 3DS（高风险订单）
            //   作用：把拒付责任转移给发卡行（liability shift），保护商户资金
            // forceThreeDS=false：request_three_d_secure="automatic" -> Stripe Radar 智能判断
            //   作用：低风险订单不打扰用户，提升转化率
            String threeDSMode = forceThreeDS ? "any" : "automatic";

            PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure threeDSEnum =
                    forceThreeDS
                            ? PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY
                            : PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.AUTOMATIC;

            PaymentIntentCreateParams.PaymentMethodOptions.Card cardOpts =
                    PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                            .setRequestThreeDSecure(threeDSEnum)
                            .build();

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amount)
                    .setCurrency(currency.toLowerCase())
                    .setDescription(description)
                    .putAllMetadata(metadata)
                    .setPaymentMethodOptions(
                            PaymentIntentCreateParams.PaymentMethodOptions.builder()
                                    .setCard(cardOpts)
                                    .build()
                    )
                    // 自动确认：使用 Stripe Elements 在前端完成卡信息收集与确认
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build()
                    )
                    .build();
            logger.info("[Stripe] 创建 PaymentIntent payOrderId={}, threeDSMode={}", payOrderId, threeDSMode);
            return PaymentIntent.create(params);
        } catch (StripeException e) {
            // 安全加固 S7: 异常信息仅记录到日志，对外返回通用 message，避免泄露 Stripe 内部错误码与堆栈
            logger.error("[Stripe] 创建 PaymentIntent 失败, payOrderId={}", payOrderId, e);
            throw new BizException("操作失败，请联系管理员");
        }
    }

    /**
     * 兼容旧调用：未指定 3DS 时默认走 automatic
     */
    public static PaymentIntent createPaymentIntent(JSONObject config, Long amount, String currency,
                                                     String payOrderId, String mchNo, String description) {
        return createPaymentIntent(config, amount, currency, payOrderId, mchNo, description, false);
    }

    /**
     * 查询 PaymentIntent
     */
    public static PaymentIntent queryPaymentIntent(JSONObject config, String intentId) {
        setApiKey(config);
        try {
            return PaymentIntent.retrieve(intentId);
        } catch (StripeException e) {
            // 安全加固 S7: 异常信息仅记录到日志，对外返回通用 message
            logger.error("[Stripe] 查询 PaymentIntent 失败, intentId={}", intentId, e);
            throw new BizException("操作失败，请联系管理员");
        }
    }

    /**
     * 创建退款
     * 注意：Stripe 退款以 PaymentIntent 为单位，不需要 Charge ID
     *
     * @param config        渠道配置
     * @param intentId      PaymentIntent ID
     * @param refundAmount  退款金额（最小货币单位）
     * @param refundOrderId JeePay 退款订单号
     */
    public static Refund createRefund(JSONObject config, String intentId, Long refundAmount, String refundOrderId) {
        setApiKey(config);
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("jeepay_refund_id", refundOrderId);

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(intentId)
                    .setAmount(refundAmount)
                    .putAllMetadata(metadata)
                    .build();
            return Refund.create(params);
        } catch (StripeException e) {
            // 安全加固 S7: 异常信息仅记录到日志，对外返回通用 message
            logger.error("[Stripe] 创建退款失败, intentId={}, refundOrderId={}", intentId, refundOrderId, e);
            throw new BizException("操作失败，请联系管理员");
        }
    }

    /**
     * 校验 Webhook 签名并解析事件
     * 注意：必须使用原始 payload（HttpServletRequest.getReader 读取的原文）
     *      不能使用 JSON 反序列化后的对象重新序列化
     *
     * @param payload   请求原文
     * @param sigHeader Stripe-Signature 请求头
     * @param config    渠道配置
     * @return Stripe Event 对象
     */
    public static Event verifyAndParseEvent(String payload, String sigHeader, JSONObject config) {
        String webhookSecret = config.getString(StripeConfig.FIELD_WEBHOOK_SECRET);
        if (StringUtils.isBlank(webhookSecret)) {
            throw new BizException("Stripe Webhook Secret 未配置");
        }
        try {
            return Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            logger.error("[Stripe] Webhook 签名校验失败", e);
            throw new BizException("Stripe Webhook 签名无效");
        }
    }
}
