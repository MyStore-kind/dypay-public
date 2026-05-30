/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.OrderRiskRecord;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.exception.BizException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 订单前置风控钩子服务
 *
 * 设计思路（无侵入扩展）：
 * - 不直接修改 JeePay 的 AbstractPayOrderController
 * - 在订单 save 之前调用本服务做风险评估
 * - 调用方在 jeepay-payment 模块的 AbstractPayOrderController.unifiedOrder
 *   的 `payOrderService.save(payOrder)` 之前插入一行：
 *       orderRiskHookService.preCheck(payOrder, request);
 *
 * 职责：
 * 1. 从订单/请求中提取设备指纹、卡信息、买家信息
 * 2. 调用 OrderRiskService 做风险评分
 * 3. 根据 risk_action 决定放行/3DS/拒绝
 * 4. 持久化风控记录到 t_order_risk_record
 * 5. 将 risk_score/risk_action 回写到 PayOrder
 *
 * 注意：本服务不调用通道路由，路由由独立 ChannelAccountRouteHook 处理
 *
 * @author 反风控改造组
 */
@Service
public class OrderRiskHookService {

    private static final Logger logger = LoggerFactory.getLogger(OrderRiskHookService.class);

    @Autowired
    private OrderRiskService orderRiskService;

    @Autowired
    private MchInfoService mchInfoService;

    /**
     * 订单前置风控检查
     *
     * @param payOrder    待保存的订单（仅在内存中）
     * @param extraInfo   附加数据（卡信息/IP/UA/设备指纹/邮箱）
     *                    约定字段：ip, ipCountry, deviceFingerprint, userAgent,
     *                    cardBin, cardLast4, cardCountry, cardType, cardBrand,
     *                    buyerEmail, buyerPhone, buyerName
     * @throws BizException 风险动作为 reject 时抛出，终止下单
     */
    public OrderRiskRecord preCheck(PayOrder payOrder, JSONObject extraInfo) {
        if (payOrder == null) return null;

        // 1. 构建初始风控记录
        OrderRiskRecord record = new OrderRiskRecord()
                .setPayOrderId(payOrder.getPayOrderId())
                .setMchNo(payOrder.getMchNo());

        if (extraInfo != null) {
            record.setIp(extraInfo.getString("ip"));
            record.setIpCountry(extraInfo.getString("ipCountry"));
            record.setIpRiskLevel(extraInfo.getString("ipRiskLevel"));
            record.setDeviceFingerprint(extraInfo.getString("deviceFingerprint"));
            record.setUserAgent(extraInfo.getString("userAgent"));
            record.setCardBin(extraInfo.getString("cardBin"));
            record.setCardLast4(extraInfo.getString("cardLast4"));
            record.setCardCountry(extraInfo.getString("cardCountry"));
            record.setCardType(extraInfo.getString("cardType"));
            record.setCardBrand(extraInfo.getString("cardBrand"));
            record.setBuyerEmail(extraInfo.getString("buyerEmail"));
            record.setBuyerPhone(extraInfo.getString("buyerPhone"));
            record.setBuyerName(extraInfo.getString("buyerName"));
        }

        // 2. 调用评估
        try {
            orderRiskService.evaluate(record);
        } catch (Exception e) {
            // 风控异常不能阻断业务，默认放行但记录日志
            logger.error("[OrderRiskHook] 评估异常 payOrderId={}", payOrder.getPayOrderId(), e);
            record.setRiskScore(0);
            record.setRiskAction(OrderRiskRecord.ACTION_PASS);
        }

        // 3. 落库（风控记录是审计与申诉证据，必须保存）
        try {
            orderRiskService.save(record);
        } catch (Exception e) {
            logger.error("[OrderRiskHook] 风控记录入库失败 payOrderId={}", payOrder.getPayOrderId(), e);
        }

        // 4. 回写到 PayOrder（jeepay-payment 后续保存订单时一起入库）
        try {
            payOrder.setRiskScore(record.getRiskScore());
            payOrder.setRiskAction(record.getRiskAction());
        } catch (Exception e) {
            logger.warn("[OrderRiskHook] 回写 PayOrder 风控字段失败", e);
        }

        // 5. 风控决策：拒绝则直接抛异常
        if (OrderRiskRecord.ACTION_REJECT.equals(record.getRiskAction())) {
            String reason = StringUtils.isNotBlank(record.getRiskFactors())
                    ? "命中风控规则：" + record.getRiskFactors()
                    : "订单风险评分超阈值";
            throw new BizException(reason);
        }

        return record;
    }

    /**
     * 提取商户风险等级
     * 用途：传给 ChannelAccountRouteHook 做账号路由
     */
    public String getMerchantRiskTier(String mchNo) {
        MchInfo m = mchInfoService.getById(mchNo);
        if (m == null || m.getRiskTier() == null) return "mid";
        return m.getRiskTier();
    }
}
