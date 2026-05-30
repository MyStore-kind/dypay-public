/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.paypal;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.entity.RefundOrder;
import com.jeequan.jeepay.pay.channel.IChannelNoticeService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.service.impl.ChargebackService;
import com.jeequan.jeepay.service.impl.RefundOrderService;
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * PayPal Webhook 处理
 *
 * 已接入事件：
 *  - CHECKOUT.ORDER.APPROVED           买家批准（尚未捕获资金）
 *  - PAYMENT.CAPTURE.COMPLETED         资金到账（最终成功）
 *  - PAYMENT.CAPTURE.DENIED            资金捕获失败
 *  - PAYMENT.CAPTURE.REFUNDED          退款完成（回写 RefundOrder）
 *  - CUSTOMER.DISPUTE.CREATED/UPDATED/RESOLVED  拒付事件（转发 ChargebackService）
 *  - RISK.DISPUTE.CREATED              风险拒付（同上转发）
 */
@Service
public class PayPalChannelNoticeService implements IChannelNoticeService {

    private static final Logger logger = LoggerFactory.getLogger(PayPalChannelNoticeService.class);

    @Autowired
    private ChargebackService chargebackService;

    @Autowired
    private RefundOrderService refundOrderService;

    @Override
    public String getIfCode() {
        return PayPalConfig.IF_CODE;
    }

    @Override
    public MutablePair<String, Object> parseParams(HttpServletRequest request, String urlOrderId, NoticeTypeEnum noticeTypeEnum) {
        try {
            String payload = readBody(request);
            JSONObject event = JSONObject.parseObject(payload);

            // 收集所有 PAYPAL-* header（用于后续校验）
            Map<String, String> headers = new HashMap<>();
            Enumeration<String> names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                String n = names.nextElement();
                if (n.toUpperCase().startsWith("PAYPAL-")) {
                    headers.put(n.toUpperCase(), request.getHeader(n));
                }
            }

            String eventType = event.getString("event_type");

            // 提取 jeepay 订单号
            String payOrderId = extractPayOrderId(event);
            if (payOrderId == null) {
                // 拒付/退款类辅助事件未必能直接提取订单号，用占位 ID 跳过订单更新
                if (isAuxiliaryEvent(eventType)) {
                    payOrderId = "PAYPAL_AUX_" + event.getString("id");
                } else {
                    logger.warn("[PayPal Webhook] 未能提取订单号 eventId={}, type={}",
                            event.getString("id"), eventType);
                    return null;
                }
            }

            return MutablePair.of(payOrderId, new Wrapper(payload, headers, event));
        } catch (Exception e) {
            logger.error("[PayPal Webhook] 参数解析异常", e);
            return null;
        }
    }

    @Override
    public ChannelRetMsg doNotice(HttpServletRequest request, Object params, PayOrder payOrder,
                                  MchAppConfigContext mchAppConfigContext, NoticeTypeEnum noticeTypeEnum) {
        try {
            Wrapper w = (Wrapper) params;
            String configStr = mchAppConfigContext.getNormalMchParamsByIfCode(getIfCode()).toString();
            JSONObject config = PayPalKit.parseConfig(configStr);

            // 1. 签名校验（向 PayPal 服务端发请求）
            // 为什么这么做：PayPal Webhook 没有简单的 HMAC，必须用 verify-webhook-signature 接口
            if (!PayPalKit.verifyWebhookSignature(config, w.headers, w.payload)) {
                logger.error("[PayPal Webhook] 签名校验失败 payOrderId={}",
                        payOrder == null ? "n/a" : payOrder.getPayOrderId());
                ChannelRetMsg fail = new ChannelRetMsg();
                fail.setChannelState(ChannelRetMsg.ChannelState.SYS_ERROR);
                fail.setResponseEntity(text("invalid signature", HttpStatus.BAD_REQUEST));
                return fail;
            }

            // 2. 事件分发
            ChannelRetMsg ret = new ChannelRetMsg();
            ret.setResponseEntity(text("success"));

            String eventType = w.event.getString("event_type");
            JSONObject resource = w.event.getJSONObject("resource");

            if (PayPalConfig.EVENT_CAPTURE_COMPLETED.equals(eventType)) {
                ret.setChannelOrderId(resource.getString("id"));
                ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
            } else if (PayPalConfig.EVENT_CAPTURE_DENIED.equals(eventType)) {
                ret.setChannelOrderId(resource.getString("id"));
                ret.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
                ret.setChannelErrMsg("PayPal capture denied");

            // ============ 退款事件 ============
            } else if (PayPalConfig.EVENT_CAPTURE_REFUNDED.equals(eventType)) {
                handleRefundEvent(resource);
                ret.setChannelState(ChannelRetMsg.ChannelState.WAITING);

            // ============ 拒付事件 ============
            } else if (PayPalConfig.EVENT_DISPUTE_CREATED.equals(eventType)
                    || PayPalConfig.EVENT_DISPUTE_RESOLVED.equals(eventType)
                    || PayPalConfig.EVENT_DISPUTE_UPDATED.equals(eventType)
                    || PayPalConfig.EVENT_RISK_DISPUTE_CREATED.equals(eventType)) {
                // 转发到统一拒付处理服务（落库 + 状态更新）
                chargebackService.onChargebackEvent("paypal", w.event);
                ret.setChannelState(ChannelRetMsg.ChannelState.WAITING);

            } else {
                logger.info("[PayPal Webhook] 忽略事件: {}", eventType);
                ret.setChannelState(ChannelRetMsg.ChannelState.WAITING);
            }
            return ret;
        } catch (Exception e) {
            logger.error("[PayPal Webhook] 处理异常", e);
            ChannelRetMsg ret = new ChannelRetMsg();
            ret.setChannelState(ChannelRetMsg.ChannelState.SYS_ERROR);
            ret.setResponseEntity(text("fail"));
            return ret;
        }
    }

    /**
     * 退款事件回写 RefundOrder
     * PayPal PAYMENT.CAPTURE.REFUNDED 的 resource 直接是 Refund 对象
     */
    private void handleRefundEvent(JSONObject resource) {
        try {
            if (resource == null) return;
            String invoiceId = resource.getString("invoice_id");
            if (invoiceId == null) return;

            RefundOrder ro = refundOrderService.getById(invoiceId);
            if (ro == null) return;

            String status = resource.getString("status");
            if ("COMPLETED".equalsIgnoreCase(status) && ro.getState() != RefundOrder.STATE_SUCCESS) {
                refundOrderService.updateIng2Success(invoiceId, resource.getString("id"));
            } else if (("FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status))
                    && ro.getState() != RefundOrder.STATE_FAIL) {
                refundOrderService.updateIng2Fail(invoiceId, resource.getString("id"),
                        status, "PayPal refund " + status);
            }
            // 通道健康度刷新由独立定时任务聚合（与 Stripe 保持一致）
        } catch (Exception e) {
            logger.error("[PayPal Webhook] 退款事件处理异常", e);
        }
    }

    /** 拒付/退款类辅助事件：不直接关联 PayOrder 主状态 */
    private boolean isAuxiliaryEvent(String eventType) {
        return PayPalConfig.EVENT_CAPTURE_REFUNDED.equals(eventType)
                || PayPalConfig.EVENT_DISPUTE_CREATED.equals(eventType)
                || PayPalConfig.EVENT_DISPUTE_RESOLVED.equals(eventType)
                || PayPalConfig.EVENT_DISPUTE_UPDATED.equals(eventType)
                || PayPalConfig.EVENT_RISK_DISPUTE_CREATED.equals(eventType);
    }

    @Override
    public ResponseEntity doNotifyOrderStateUpdateFail(HttpServletRequest request) {
        return text("order_update_fail", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity doNotifyOrderNotExists(HttpServletRequest request) {
        return text("success");
    }

    // ===== 辅助 =====

    private String readBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 从 PayPal 事件 resource.custom_id 中提取 jeepay 订单号
     * 注意：custom_id 在 purchase_units[0] 里，但 CAPTURE 事件中直接在 resource.custom_id
     */
    private String extractPayOrderId(JSONObject event) {
        try {
            JSONObject resource = event.getJSONObject("resource");
            if (resource == null) return null;

            String customId = resource.getString("custom_id");
            if (customId != null) return customId;

            // 兜底：CHECKOUT.ORDER.* 事件在 purchase_units 里
            if (resource.containsKey("purchase_units")) {
                return resource.getJSONArray("purchase_units").getJSONObject(0).getString("custom_id");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private ResponseEntity<String> text(String body) {
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<String> text(String body, HttpStatus status) {
        return ResponseEntity.status(status).body(body);
    }

    private static class Wrapper {
        final String payload;
        final Map<String, String> headers;
        final JSONObject event;
        Wrapper(String payload, Map<String, String> headers, JSONObject event) {
            this.payload = payload; this.headers = headers; this.event = event;
        }
    }
}
