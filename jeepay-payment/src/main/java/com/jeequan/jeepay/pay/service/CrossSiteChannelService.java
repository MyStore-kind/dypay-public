/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.service;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.CrossSiteNotifyRecord;
import com.jeequan.jeepay.core.entity.CrossSitePushRecord;
import com.jeequan.jeepay.pay.channel.paypal.PayPalKit;
import com.jeequan.jeepay.service.impl.ChannelAccountService;
import com.jeequan.jeepay.service.impl.CrossSiteNotifyService;
import com.jeequan.jeepay.service.impl.CrossSitePushService;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨站收银台 - 通道对接服务
 *
 * 职责：
 *  - 根据风控决策（pass / 3ds）创建 Stripe PaymentIntent 或 PayPal Order
 *  - 把通道 ID 与 client_secret 写回 CrossSitePushRecord
 *  - Stripe Webhook 事件回调处理（payment_intent.succeeded / payment_failed）
 *
 * 选通道策略（M0 简化版）：
 *  - 默认使用 stripe（实际生产可走 ChannelRouterService 智能选）
 *  - 若 ChannelAccount 不存在则报错，运营需先在风控中心配置账号
 *
 * 与 jeepay-payment 模块的 StripePaymentService 区别：
 *  - 那个是商户支付应用走的（依赖 PayOrder + MchApp）
 *  - 本服务只面向跨站托管收银台，独立于商户 app
 *  - 共享 StripeKit 的密钥配置约定与 metadata key
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Service
public class CrossSiteChannelService {

    private static final Logger logger = LoggerFactory.getLogger(CrossSiteChannelService.class);

    /** Stripe metadata key（与 StripeKit 保持一致，方便互查） */
    private static final String META_PAY_TOKEN = "dypay_pay_token";
    private static final String META_CROSS_SITE_ID = "dypay_cross_site_id";
    private static final String META_CLIENT_ID = "dypay_client_id";

    @Autowired private ChannelAccountService channelAccountService;
    @Autowired private CrossSitePushService crossSitePushService;
    @Autowired private CrossSiteNotifyService crossSiteNotifyService;

    // ============================================
    // 1. 准备付款（前端进入收银台后调用）
    // ============================================

    /**
     * 为已通过风控的订单准备 Stripe PaymentIntent，返回 client_secret 给前端 Elements 使用
     */
    @Transactional(rollbackFor = Exception.class)
    public PrepareResult prepareStripe(CrossSitePushRecord rec) {
        if (rec == null) return PrepareResult.fail("订单不存在");
        if (CrossSitePushRecord.STATE_PAID.equals(rec.getState())) {
            return PrepareResult.fail("订单已付款");
        }
        if (CrossSitePushRecord.STATE_EXPIRED.equals(rec.getState())) {
            return PrepareResult.fail("订单已过期");
        }
        if (CrossSitePushRecord.DECISION_REJECT.equals(rec.getRiskDecision())) {
            return PrepareResult.fail("风控拒绝");
        }

        // 幂等：已创建过 PaymentIntent 则直接返回（避免 Stripe 多扣）
        if (rec.getChannelIntentId() != null && rec.getChannelClientSecret() != null) {
            return PrepareResult.ok(rec.getChannelProvider(), rec.getChannelIntentId(),
                    rec.getChannelClientSecret(), publishableKey("stripe"));
        }

        ChannelAccount acc = pickAccount("stripe");
        if (acc == null) return PrepareResult.fail("无可用 Stripe 账号");
        JSONObject cfg;
        try { cfg = JSONObject.parseObject(acc.getConfigParams()); }
        catch (Exception e) { return PrepareResult.fail("Stripe 账号配置无效"); }
        String secretKey = cfg.getString("secretKey");
        String pubKey = cfg.getString("publishableKey");
        if (secretKey == null || secretKey.isEmpty()) return PrepareResult.fail("Stripe secretKey 缺失");

        // 风控决策 → 3DS 策略
        boolean force3ds = CrossSitePushRecord.DECISION_3DS.equals(rec.getRiskDecision());

        Stripe.apiKey = secretKey;
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put(META_PAY_TOKEN, rec.getPayToken());
            metadata.put(META_CROSS_SITE_ID, String.valueOf(rec.getId()));
            metadata.put(META_CLIENT_ID, rec.getClientId());

            PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure tdsEnum =
                    force3ds
                            ? PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY
                            : PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.AUTOMATIC;

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(rec.getAmount())
                    .setCurrency(rec.getCurrency() == null ? "usd" : rec.getCurrency().toLowerCase())
                    .setDescription(rec.getSubject() == null ? ("DYPAY-" + rec.getPayToken()) : rec.getSubject())
                    .setReceiptEmail(rec.getCustomerEmail()) // 可为 null
                    .putAllMetadata(metadata)
                    .setPaymentMethodOptions(
                            PaymentIntentCreateParams.PaymentMethodOptions.builder()
                                    .setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                                            .setRequestThreeDSecure(tdsEnum).build())
                                    .build())
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true).build())
                    .build();

            PaymentIntent pi = PaymentIntent.create(params);
            rec.setChannelProvider("stripe");
            rec.setChannelIntentId(pi.getId());
            rec.setChannelClientSecret(pi.getClientSecret());
            if (CrossSitePushRecord.STATE_AWAITING_PAY.equals(rec.getState())
                    || CrossSitePushRecord.STATE_VERIFIED.equals(rec.getState())) {
                rec.setState(CrossSitePushRecord.STATE_PAYING);
            }
            crossSitePushService.updateById(rec);

            logger.info("[CrossSite#Stripe] 创建 PaymentIntent ok payToken={} pi={} force3ds={}",
                    rec.getPayToken(), pi.getId(), force3ds);
            return PrepareResult.ok("stripe", pi.getId(), pi.getClientSecret(), pubKey);
        } catch (StripeException e) {
            logger.error("[CrossSite#Stripe] 创建 PaymentIntent 失败 payToken={}", rec.getPayToken(), e);
            return PrepareResult.fail("通道异常，请稍后再试");
        }
    }

    /**
     * PayPal 预生成订单
     * 真正调用 PayPal Orders API 创建订单，前端用返回的 orderID 让 PayPal Buttons 完成 approve
     */
    @Transactional(rollbackFor = Exception.class)
    public PrepareResult preparePaypal(CrossSitePushRecord rec) {
        if (rec == null) return PrepareResult.fail("订单不存在");
        if (CrossSitePushRecord.STATE_PAID.equals(rec.getState())) {
            return PrepareResult.fail("订单已付款");
        }
        if (CrossSitePushRecord.STATE_EXPIRED.equals(rec.getState())) {
            return PrepareResult.fail("订单已过期");
        }
        if (CrossSitePushRecord.DECISION_REJECT.equals(rec.getRiskDecision())) {
            return PrepareResult.fail("风控拒绝");
        }

        // 幂等：已有 orderId 直接返回
        if ("paypal".equals(rec.getChannelProvider()) && rec.getChannelIntentId() != null) {
            return PrepareResult.ok("paypal", rec.getChannelIntentId(), null, publishableKey("paypal"));
        }

        ChannelAccount acc = pickAccount("paypal");
        if (acc == null) return PrepareResult.fail("无可用 PayPal 账号");
        JSONObject cfg;
        try { cfg = JSONObject.parseObject(acc.getConfigParams()); }
        catch (Exception e) { return PrepareResult.fail("PayPal 账号配置无效"); }
        String pubKey = cfg.getString("clientId");
        if (pubKey == null || pubKey.isEmpty()) return PrepareResult.fail("PayPal clientId 缺失");

        try {
            // returnUrl/cancelUrl 用收银台自身（前端会接管跳转）
            String returnUrl = rec.getReturnUrl() == null ? "" : rec.getReturnUrl();
            String cancelUrl = returnUrl;  // 简化：取消也回 returnUrl，B 端按 state 区分
            com.paypal.sdk.models.Order order = PayPalKit.createOrder(
                    cfg, rec.getAmount(), rec.getCurrency(),
                    rec.getPayToken(),  // 用 pay_token 作 customId，方便 Webhook 反查
                    returnUrl, cancelUrl);
            rec.setChannelProvider("paypal");
            rec.setChannelIntentId(order.getId());
            if (CrossSitePushRecord.STATE_AWAITING_PAY.equals(rec.getState())
                    || CrossSitePushRecord.STATE_VERIFIED.equals(rec.getState())) {
                rec.setState(CrossSitePushRecord.STATE_PAYING);
            }
            crossSitePushService.updateById(rec);
            logger.info("[CrossSite#PayPal] 创建订单 ok payToken={} orderId={}",
                    rec.getPayToken(), order.getId());
            return PrepareResult.ok("paypal", order.getId(), null, pubKey);
        } catch (Exception e) {
            logger.error("[CrossSite#PayPal] 创建订单失败 payToken={}", rec.getPayToken(), e);
            return PrepareResult.fail("通道异常，请稍后再试");
        }
    }

    /**
     * PayPal 客户端 Approve 后由前端回调本接口，后端做 capture 完成扣款
     * @return 成功 true / 失败 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean capturePaypal(CrossSitePushRecord rec) {
        if (rec == null || !"paypal".equals(rec.getChannelProvider())
                || rec.getChannelIntentId() == null) {
            return false;
        }
        if (CrossSitePushRecord.STATE_PAID.equals(rec.getState())) return true; // 幂等

        ChannelAccount acc = pickAccount("paypal");
        if (acc == null) return false;
        JSONObject cfg;
        try { cfg = JSONObject.parseObject(acc.getConfigParams()); }
        catch (Exception e) { return false; }

        try {
            com.paypal.sdk.models.Order order = PayPalKit.captureOrder(cfg, rec.getChannelIntentId());
            String status = order.getStatus() == null ? null : order.getStatus().toString();
            if ("COMPLETED".equalsIgnoreCase(status)) {
                markPaid(rec);
                return true;
            } else {
                markFailed(rec, "PayPal status=" + status);
                return false;
            }
        } catch (Exception e) {
            logger.error("[CrossSite#PayPal] capture 失败 payToken={}", rec.getPayToken(), e);
            markFailed(rec, "PayPal capture 异常");
            return false;
        }
    }

    // ============================================
    // 2. Stripe Webhook 处理
    // ============================================

    /**
     * 处理 Stripe Webhook 事件
     * 必须用原始 payload 验签
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handleStripeWebhook(String payload, String signatureHeader) {
        ChannelAccount acc = pickAccount("stripe");
        if (acc == null) {
            logger.error("[CrossSite#Stripe Webhook] 无可用账号");
            return false;
        }
        JSONObject cfg;
        try { cfg = JSONObject.parseObject(acc.getConfigParams()); }
        catch (Exception e) { return false; }
        String secret = cfg.getString("webhookSecret");
        if (secret == null || secret.isEmpty()) {
            logger.error("[CrossSite#Stripe Webhook] webhookSecret 未配置");
            return false;
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, secret);
        } catch (SignatureVerificationException e) {
            logger.error("[CrossSite#Stripe Webhook] 签名校验失败", e);
            return false;
        } catch (Exception e) {
            logger.error("[CrossSite#Stripe Webhook] 解析失败", e);
            return false;
        }

        String type = event.getType();
        JSONObject rawObj = JSONObject.parseObject(payload);
        JSONObject obj = rawObj.getJSONObject("data") == null ? null
                : rawObj.getJSONObject("data").getJSONObject("object");
        if (obj == null) return true; // 不识别也回 200 防风暴

        String intentId = obj.getString("id");
        JSONObject metadata = obj.getJSONObject("metadata");
        String payToken = metadata == null ? null : metadata.getString(META_PAY_TOKEN);
        if (payToken == null) {
            logger.info("[CrossSite#Stripe Webhook] 非跨站事件，忽略 type={} intent={}", type, intentId);
            return true;
        }

        CrossSitePushRecord rec = crossSitePushService.loadByPayToken(payToken);
        if (rec == null) {
            logger.warn("[CrossSite#Stripe Webhook] 找不到记录 payToken={}", payToken);
            return true;
        }

        switch (type) {
            case "payment_intent.succeeded":
                markPaid(rec);
                break;
            case "payment_intent.payment_failed":
                markFailed(rec, obj.getString("last_payment_error"));
                break;
            case "payment_intent.canceled":
                markFailed(rec, "Stripe: canceled");
                break;
            default:
                // 其他事件不处理，仅记日志
                logger.info("[CrossSite#Stripe Webhook] 忽略 type={} payToken={}", type, payToken);
        }
        return true;
    }

    // ============================================
    // 3. 状态机
    // ============================================

    public void markPaid(CrossSitePushRecord rec) {
        if (CrossSitePushRecord.STATE_PAID.equals(rec.getState())) return; // 幂等
        rec.setState(CrossSitePushRecord.STATE_PAID);
        rec.setPaidAt(new Date());
        crossSitePushService.updateById(rec);
        // 入队通知 B 站
        crossSiteNotifyService.enqueue(rec, CrossSiteNotifyRecord.EVENT_PAID);
        logger.info("[CrossSite] 标记 PAID payToken={} amount={}", rec.getPayToken(), rec.getAmount());
    }

    public void markFailed(CrossSitePushRecord rec, String reason) {
        if (CrossSitePushRecord.STATE_PAID.equals(rec.getState())) return;
        rec.setState(CrossSitePushRecord.STATE_FAILED);
        rec.setFailedReason(reason);
        crossSitePushService.updateById(rec);
        crossSiteNotifyService.enqueue(rec, CrossSiteNotifyRecord.EVENT_FAILED);
        logger.info("[CrossSite] 标记 FAILED payToken={} reason={}", rec.getPayToken(), reason);
    }

    // ============================================
    // 辅助
    // ============================================

    /** 选一个 ifCode=xxx 的健康账号 */
    private ChannelAccount pickAccount(String ifCode) {
        List<ChannelAccount> list = channelAccountService.listAvailable(ifCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 取通道发布密钥（公钥）；供前端使用 */
    public String publishableKey(String ifCode) {
        ChannelAccount acc = pickAccount(ifCode);
        if (acc == null) return null;
        try {
            JSONObject cfg = JSONObject.parseObject(acc.getConfigParams());
            if ("stripe".equals(ifCode)) return cfg.getString("publishableKey");
            if ("paypal".equals(ifCode)) return cfg.getString("clientId");
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 返回给前端 / Controller 的结果
     */
    public static class PrepareResult {
        public boolean success;
        public String message;
        public String provider;
        public String intentId;
        public String clientSecret;
        public String publishableKey;

        public static PrepareResult ok(String provider, String intentId, String clientSecret, String pubKey) {
            PrepareResult r = new PrepareResult();
            r.success = true; r.provider = provider; r.intentId = intentId;
            r.clientSecret = clientSecret; r.publishableKey = pubKey;
            return r;
        }

        public static PrepareResult fail(String msg) {
            PrepareResult r = new PrepareResult();
            r.success = false; r.message = msg;
            return r;
        }
    }
}
