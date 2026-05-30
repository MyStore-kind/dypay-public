/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.pay.channel.stripe;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.pay.channel.IChannelNoticeService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.tuple.MutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
public class StripeChannelNoticeService implements IChannelNoticeService {

    private static final Logger logger = LoggerFactory.getLogger(StripeChannelNoticeService.class);

    /** Webhook 签名请求头 */
    private static final String STRIPE_SIGNATURE_HEADER = "Stripe-Signature";

    @Override
    public String getIfCode() {
        return StripeConfig.IF_CODE;
    }

    /**
     * 解析回调参数
     * Stripe Webhook URL 不带订单号，需从 payload 的 metadata 中提取
     *
     * @param urlOrderId 框架传入，Stripe 通道为空
     * @return [JeePay 订单号, payload+sig 包装]
     */
    @Override
    public MutablePair<String, Object> parseParams(HttpServletRequest request, String urlOrderId, NoticeTypeEnum noticeTypeEnum) {
        try {
            // 读取原始请求体
            String payload = readRequestBody(request);
            String sigHeader = request.getHeader(STRIPE_SIGNATURE_HEADER);

            // 从 payload 直接提取 JeePay 订单号（不依赖 Stripe SDK 反序列化）
            // 为什么用 fastjson 直接解析：避免不同版本 SDK 的 ApiResource.GSON 暴露差异
            String payOrderId = extractPayOrderIdFromPayload(payload);
            if (payOrderId == null) {
                logger.warn("[Stripe Webhook] 未能提取 jeepay_order_id");
                return null;
            }

            // 将 payload + sigHeader 一起传给 doNotice，便于二次校验
            StripeNoticeWrapper wrapper = new StripeNoticeWrapper(payload, sigHeader);
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

            // 1. 签名校验（关键步骤，防止伪造通知）
            String configStr = mchAppConfigContext
                    .getNormalMchParamsByIfCode(getIfCode())
                    .toString();
            JSONObject config = StripeKit.parseConfig(configStr);
            Event verifiedEvent = StripeKit.verifyAndParseEvent(wrapper.payload, wrapper.sigHeader, config);

            // 2. 处理事件
            ChannelRetMsg channelRetMsg = new ChannelRetMsg();
            channelRetMsg.setResponseEntity(textResp("success"));

            String eventType = verifiedEvent.getType();
            if (StripeConfig.EVENT_PAYMENT_INTENT_SUCCEEDED.equals(eventType)) {
                PaymentIntent intent = (PaymentIntent) verifiedEvent.getDataObjectDeserializer()
                        .getObject().orElse(null);
                if (intent != null) {
                    channelRetMsg.setChannelOrderId(intent.getId());
                    channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
                }
            } else if (StripeConfig.EVENT_PAYMENT_INTENT_FAILED.equals(eventType)) {
                PaymentIntent intent = (PaymentIntent) verifiedEvent.getDataObjectDeserializer()
                        .getObject().orElse(null);
                if (intent != null) {
                    channelRetMsg.setChannelOrderId(intent.getId());
                    channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
                    channelRetMsg.setChannelErrMsg(intent.getLastPaymentError() != null
                            ? intent.getLastPaymentError().getMessage() : "支付失败");
                }
            } else {
                // 未关心的事件：直接响应 success，避免 Stripe 重试
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

    @Override
    public ResponseEntity doNotifyOrderStateUpdateFail(HttpServletRequest request) {
        // 返回非 2xx 让 Stripe 重试
        return textResp("order_update_fail", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity doNotifyOrderNotExists(HttpServletRequest request) {
        // 订单不存在直接 success，避免 Stripe 持续重试
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
     * 从 payload JSON 中直接提取 jeepay 订单号
     * 路径：data.object.metadata.jeepay_order_id
     */
    private String extractPayOrderIdFromPayload(String payload) {
        try {
            JSONObject root = JSONObject.parseObject(payload);
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
     * 用于在 parseParams 和 doNotice 之间传递原始 payload 和签名头
     */
    private static class StripeNoticeWrapper {
        final String payload;
        final String sigHeader;

        StripeNoticeWrapper(String payload, String sigHeader) {
            this.payload = payload;
            this.sigHeader = sigHeader;
        }
    }
}
