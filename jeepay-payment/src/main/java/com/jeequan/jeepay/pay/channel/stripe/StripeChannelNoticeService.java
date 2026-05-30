/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.entity.RefundOrder;
import com.jeequan.jeepay.pay.channel.IChannelNoticeService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.service.impl.ChargebackService;
import com.jeequan.jeepay.service.impl.RefundOrderService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.tuple.MutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Stripe 异步回调（Webhook）处理
 *
 * 关键设计：
 * 1. 必须读取原始 payload 用于签名校验（不能用 JSON 反序列化后重新序列化）
 * 2. 通过 metadata 中的 jeepay_order_id 关联 JeePay 订单
 * 3. 处理重复通知：JeePay 框架层已通过订单状态校验保证幂等
 *
 * Stripe 要求：
 * - Webhook 必须在 30 秒内返回 2xx，否则会重试
 * - 重试机制：3 天内最多重试若干次
 *
 * 已接入事件：
 *  - payment_intent.succeeded / payment_intent.payment_failed / payment_intent.requires_action
 *  - charge.refunded（更新 RefundOrder）
 *  - charge.dispute.* + radar.early_fraud_warning（转发 ChargebackService）
 */
@Service
public class StripeChannelNoticeService implements IChannelNoticeService {

    private static final Logger logger = LoggerFactory.getLogger(StripeChannelNoticeService.class);

    /** Webhook 签名请求头 */
    private static final String STRIPE_SIGNATURE_HEADER = "Stripe-Signature";

    @Autowired
    private ChargebackService chargebackService;

    @Autowired
    private RefundOrderService refundOrderService;

    @Override
    public String getIfCode() {
        return StripeConfig.IF_CODE;
    }

    /**
     * 解析回调参数
     * Stripe Webhook URL 不带订单号，需从 payload 的 metadata 中提取
     */
    @Override
    public MutablePair<String, Object> parseParams(HttpServletRequest request, String urlOrderId, NoticeTypeEnum noticeTypeEnum) {
        try {
            String payload = readRequestBody(request);
            String sigHeader = request.getHeader(STRIPE_SIGNATURE_HEADER);

            // 直接用 fastjson 提取，避免不同 SDK 版本差异
            JSONObject root = JSONObject.parseObject(payload);
            String eventType = root == null ? null : root.getString("type");

            String payOrderId = extractPayOrderIdFromPayload(root, eventType);

            // 拒付 / 退款 / 早期欺诈预警类事件未必能直接拿到 jeepay_order_id
            // 这些事件我们不依赖 JeePay 的订单状态机更新，统一在 doNotice 内由 ChargebackService 反查
            // 返回一个占位 ID 让框架跳过订单更新，doNotice 内自行处理
            if (payOrderId == null) {
                if (isAuxiliaryEvent(eventType)) {
                    payOrderId = "STRIPE_AUX_" + (root == null ? "" : root.getString("id"));
                } else {
                    logger.warn("[Stripe Webhook] 未能提取 jeepay_order_id type={}", eventType);
                    return null;
                }
            }

            StripeNoticeWrapper wrapper = new StripeNoticeWrapper(payload, sigHeader, root);
            return MutablePair.of(payOrderId, wrapper);
        } catch (Exception e) {
            logger.error("[Stripe Webhook] 参数解析异常", e);
            return null;
        }
    }

    /**
     * 处理通知并返回订单状态
     */
    @Override
    public ChannelRetMsg doNotice(HttpServletRequest request, Object params, PayOrder payOrder,
                                  MchAppConfigContext mchAppConfigContext, NoticeTypeEnum noticeTypeEnum) {
        try {
            StripeNoticeWrapper wrapper = (StripeNoticeWrapper) params;

            // 1. 签名校验：失败直接 400（关键，防止伪造通知）
            String configStr = mchAppConfigContext
                    .getNormalMchParamsByIfCode(getIfCode())
                    .toString();
            JSONObject config = StripeKit.parseConfig(configStr);
            Event verifiedEvent;
            try {
                verifiedEvent = StripeKit.verifyAndParseEvent(wrapper.payload, wrapper.sigHeader, config);
            } catch (Exception sigEx) {
                logger.error("[Stripe Webhook] 签名校验失败", sigEx);
                ChannelRetMsg ret = new ChannelRetMsg();
                ret.setChannelState(ChannelRetMsg.ChannelState.SYS_ERROR);
                ret.setResponseEntity(textResp("invalid signature", HttpStatus.BAD_REQUEST));
                return ret;
            }

            ChannelRetMsg channelRetMsg = new ChannelRetMsg();
            channelRetMsg.setResponseEntity(textResp("success"));

            String eventType = verifiedEvent.getType();

            // ============ 支付意图相关事件 ============
            if (StripeConfig.EVENT_PAYMENT_INTENT_SUCCEEDED.equals(eventType)) {
                PaymentIntent intent = extractPaymentIntent(verifiedEvent, wrapper.root);
                if (intent != null) {
                    channelRetMsg.setChannelOrderId(intent.getId());
                    channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
                }
            } else if (StripeConfig.EVENT_PAYMENT_INTENT_FAILED.equals(eventType)) {
                PaymentIntent intent = extractPaymentIntent(verifiedEvent, wrapper.root);
                if (intent != null) {
                    channelRetMsg.setChannelOrderId(intent.getId());
                    channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
                    channelRetMsg.setChannelErrMsg(intent.getLastPaymentError() != null
                            ? intent.getLastPaymentError().getMessage() : "支付失败");
                }
            } else if (StripeConfig.EVENT_PAYMENT_INTENT_REQUIRES_ACTION.equals(eventType)) {
                // 3DS 跳转中：不修改订单状态，等待最终结果事件
                // 为什么这么做：requires_action 是中间态，不能误判为失败
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);

            // ============ 退款事件 ============
            } else if (StripeConfig.EVENT_CHARGE_REFUNDED.equals(eventType)) {
                handleRefundEvent(wrapper.root);
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);

            // ============ 拒付 / 早期欺诈预警 ============
            } else if (StripeConfig.EVENT_DISPUTE_CREATED.equals(eventType)
                    || StripeConfig.EVENT_DISPUTE_FUNDS_WITHDRAWN.equals(eventType)
                    || StripeConfig.EVENT_DISPUTE_UPDATED.equals(eventType)
                    || StripeConfig.EVENT_EARLY_FRAUD_WARNING.equals(eventType)) {
                // 转发到统一拒付处理服务（落库 + 状态更新）
                // 为什么不在这里更新订单：拒付不改变原订单成功状态，由运营决定是否退款
                chargebackService.onChargebackEvent("stripe", wrapper.root);
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);

            } else {
                logger.info("[Stripe Webhook] 忽略事件: {}", eventType);
                channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
            }

            return channelRetMsg;
        } catch (Exception e) {
            logger.error("[Stripe Webhook] 处理异常", e);
            ChannelRetMsg ret = new ChannelRetMsg();
            ret.setChannelState(ChannelRetMsg.ChannelState.SYS_ERROR);
            ret.setResponseEntity(textResp("fail"));
            return ret;
        }
    }

    /**
     * 退款事件处理：回写 RefundOrder 状态
     * Stripe 的 charge.refunded 事件中 data.object 是 Charge，其 refunds.data[] 包含每笔退款
     */
    private void handleRefundEvent(JSONObject root) {
        try {
            JSONObject charge = root.getJSONObject("data").getJSONObject("object");
            if (charge == null) return;
            // refunds.data[] 列出 charge 上所有退款（含本次新退款）
            JSONObject refunds = charge.getJSONObject("refunds");
            if (refunds == null) return;
            for (Object r : refunds.getJSONArray("data")) {
                JSONObject refund = (JSONObject) r;
                JSONObject meta = refund.getJSONObject("metadata");
                String refundOrderId = meta == null ? null : meta.getString("jeepay_refund_id");
                if (refundOrderId == null) continue;

                RefundOrder ro = refundOrderService.getById(refundOrderId);
                if (ro == null) continue;

                String status = refund.getString("status");
                // succeeded -> 成功；failed/canceled -> 失败；其他保持中
                if ("succeeded".equals(status) && ro.getState() != RefundOrder.STATE_SUCCESS) {
                    refundOrderService.updateIng2Success(refundOrderId, refund.getString("id"));
                } else if (("failed".equals(status) || "canceled".equals(status))
                        && ro.getState() != RefundOrder.STATE_FAIL) {
                    refundOrderService.updateIng2Fail(refundOrderId, refund.getString("id"),
                            refund.getString("failure_reason"), refund.getString("failure_reason"));
                }
                // 注意：通道健康度刷新由独立定时任务聚合，不在每次 Webhook 触发
                // 这样可避免短时间内大量退款触发频繁的快照计算
            }
        } catch (Exception e) {
            logger.error("[Stripe Webhook] 退款事件处理异常", e);
        }
    }

    /** 提取 PaymentIntent：优先用 SDK 反序列化，失败则从 JSON 兜底 */
    private PaymentIntent extractPaymentIntent(Event verifiedEvent, JSONObject root) {
        try {
            PaymentIntent intent = (PaymentIntent) verifiedEvent.getDataObjectDeserializer()
                    .getObject().orElse(null);
            if (intent != null) return intent;
        } catch (Exception ignored) {}
        // 兜底：SDK 版本不兼容时直接构造空 PaymentIntent（仅用 ID）
        return null;
    }

    /** 辅助事件：拒付、早期预警、退款，不直接关联 PayOrder 主状态 */
    private boolean isAuxiliaryEvent(String eventType) {
        return StripeConfig.EVENT_CHARGE_REFUNDED.equals(eventType)
                || StripeConfig.EVENT_DISPUTE_CREATED.equals(eventType)
                || StripeConfig.EVENT_DISPUTE_FUNDS_WITHDRAWN.equals(eventType)
                || StripeConfig.EVENT_DISPUTE_UPDATED.equals(eventType)
                || StripeConfig.EVENT_EARLY_FRAUD_WARNING.equals(eventType);
    }

    @Override
    public ResponseEntity doNotifyOrderStateUpdateFail(HttpServletRequest request) {
        return textResp("order_update_fail", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity doNotifyOrderNotExists(HttpServletRequest request) {
        // 拒付/退款等辅助事件可能找不到 PayOrder（占位 ID）：返回 success 避免重试
        return textResp("success");
    }

    // ============= 辅助方法 =============

    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * 从 payload JSON 中提取 jeepay 订单号
     * - payment_intent.* / charge.refunded：data.object.metadata.jeepay_order_id
     * - charge.dispute.*：data.object.metadata 没有，需后续反查
     */
    private String extractPayOrderIdFromPayload(JSONObject root, String eventType) {
        try {
            if (root == null) return null;
            JSONObject data = root.getJSONObject("data");
            if (data == null) return null;
            JSONObject obj = data.getJSONObject("object");
            if (obj == null) return null;
            JSONObject metadata = obj.getJSONObject("metadata");
            if (metadata == null) return null;
            return metadata.getString(StripeConfig.METADATA_PAY_ORDER_ID);
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<String> textResp(String body) {
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<String> textResp(String body, HttpStatus status) {
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Webhook 数据包装类
     * 用于在 parseParams 和 doNotice 之间传递原始 payload / 签名 / 已解析 JSON
     */
    private static class StripeNoticeWrapper {
        final String payload;
        final String sigHeader;
        final JSONObject root;

        StripeNoticeWrapper(String payload, String sigHeader, JSONObject root) {
            this.payload = payload;
            this.sigHeader = sigHeader;
            this.root = root;
        }
    }
}
