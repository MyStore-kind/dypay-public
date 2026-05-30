/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.OrderRiskRecord;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.OrderRiskRecordMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 订单前置风控钩子服务。
 *
 * 设计思路（无侵入扩展）：
 * - 不直接修改 JeePay 的 AbstractPayOrderController
 * - 在订单 save 之前调用本服务做风险评估
 * - 调用方在 jeepay-payment 模块的 AbstractPayOrderController.unifiedOrder
 *   的 `payOrderService.save(payOrder)` 之前插入一行：
 *       orderRiskHookService.preCheck(payOrder, request);
 *
 * 执行流程（任务 #19）：
 *   1. 黑名单命中（CARD_BIN / IP / EMAIL / DEVICE_ID）→ BLOCK
 *   2. 高频卡号（10 分钟窗口内同一 cardBin 在 ≥3 个商户出现）→ BLOCK
 *   3. 多维评分（{@link OrderRiskService#evaluate} + IP 国家与商户历史偏差）
 *   4. 智能 3DS 触发（高风险商户 / 大额 / 评分超阈值）→ forceThreeDS=true
 *   5. 不命中 → ALLOW，写入 OrderRiskRecord 留痕
 *
 * 返回 {@link RiskDecision}（action + forceThreeDS + hitRule + reason）；
 * 当 action=REJECT 时同步抛出 BizException，调用方无需自行判定。
 *
 * @author 反风控改造组
 */
@Service
public class OrderRiskHookService {

    private static final Logger logger = LoggerFactory.getLogger(OrderRiskHookService.class);

    /** 高频卡号窗口（分钟） */
    private static final int CARD_BIN_WINDOW_MINUTES = 10;
    /** 同 cardBin 跨商户阈值 key（默认 3） */
    private static final String CFG_CARD_BIN_MERCHANTS = "order.same_card_merchants.threshold";
    /** 大额强制 3DS 阈值（分）key，默认 5000.00 货币单位（即 500000 分） */
    private static final String CFG_FORCE_3DS_AMOUNT = "order.force_3ds.amount";
    /** 强制 3DS 评分阈值 key（与 OrderRiskService 共享） */
    private static final String CFG_3DS_SCORE = "order.risk_score.3ds";
    /** REJECT 评分阈值 key */
    private static final String CFG_REJECT_SCORE = "order.risk_score.reject";

    @Autowired private OrderRiskService orderRiskService;
    @Autowired private OrderRiskRecordService orderRiskRecordService;
    @Autowired private OrderRiskRecordMapper orderRiskRecordMapper;
    @Autowired private BlacklistService blacklistService;
    @Autowired private MchInfoService mchInfoService;
    @Autowired private RiskThresholdConfigService thresholdConfig;
    /** 熔断引擎：用于在订单创建前快速拒绝处于熔断/限流态的商户（任务 #18 集成点） */
    @Autowired private RiskCircuitBreakerEngine circuitBreakerEngine;

    /**
     * 订单前置风控检查。
     * 内部已包含落库与 PayOrder 字段回写。
     *
     * @param payOrder    待保存的订单（仅在内存中）
     * @param extraInfo   附加数据：ip, ipCountry, ipRiskLevel, deviceFingerprint, userAgent,
     *                    cardBin, cardLast4, cardCountry, cardType, cardBrand,
     *                    buyerEmail, buyerPhone, buyerName
     * @return 风控决策（ALLOW / REVIEW；含 forceThreeDS）
     * @throws BizException 命中 BLOCK 规则时抛出，终止下单
     */
    @Transactional(rollbackFor = Exception.class)
    public RiskDecision preCheck(PayOrder payOrder, JSONObject extraInfo) {
        if (payOrder == null) return RiskDecision.pass();

        // 0. 商户熔断 / 限流前置校验：处于 SUSPEND 态直接拒；处于 THROTTLE 仅日志，
        //    具体 QPS 限制由路由层结合 isThrottled() 实现，本钩子只承担硬拒判定
        try {
            if (circuitBreakerEngine.isCircuitBroken(payOrder.getMchNo())
                    && !circuitBreakerEngine.isThrottled(payOrder.getMchNo())) {
                throw new BizException("商户已被风险熔断，暂不可受理");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            // Redis 异常时降级，不阻塞业务
            logger.warn("[OrderRiskHook] 熔断态查询异常，降级放行 mchNo={}", payOrder.getMchNo(), e);
        }

        // 1. 构造记录骨架
        OrderRiskRecord record = buildRecord(payOrder, extraInfo);

        // 2. 黑名单命中 → BLOCK
        Optional<RiskBlacklist> hit = checkBlacklist(record);
        if (hit.isPresent()) {
            RiskBlacklist b = hit.get();
            String hitRule = "BLACKLIST_" + (b.getListType() == null ? "UNKNOWN" : b.getListType().toUpperCase());
            String reason = "命中黑名单：" + b.getListType() + ":" + b.getListValue();
            record.setRiskScore(100);
            record.setRiskAction(OrderRiskRecord.ACTION_REJECT);
            record.setRiskFactors(buildFactors(hitRule, reason, null));
            persistAndWriteBack(payOrder, record);
            throw new BizException(reason);
        }

        // 3. 高频卡号（10 分钟窗口内同一 cardBin 出现在 ≥N 个商户）
        if (StringUtils.isNotBlank(record.getCardBin())) {
            try {
                int threshold = thresholdConfig.getNumber(CFG_CARD_BIN_MERCHANTS, new BigDecimal("3")).intValue();
                Integer cnt = orderRiskRecordMapper.countDistinctMerchantsByCardBin(
                        record.getCardBin(), CARD_BIN_WINDOW_MINUTES);
                if (cnt != null && cnt >= threshold) {
                    String hitRule = "HIGH_FREQ_CARD_BIN";
                    String reason = "卡 BIN " + record.getCardBin() + " 在 " + CARD_BIN_WINDOW_MINUTES
                            + " 分钟内已跨越 " + cnt + " 个商户";
                    record.setRiskScore(100);
                    record.setRiskAction(OrderRiskRecord.ACTION_REJECT);
                    record.setRiskFactors(buildFactors(hitRule, reason, cnt));
                    persistAndWriteBack(payOrder, record);
                    throw new BizException(reason);
                }
            } catch (BizException be) {
                throw be;
            } catch (Exception e) {
                // 查询异常不阻断业务，继续走评分
                logger.warn("[OrderRiskHook] 高频卡号查询异常 payOrderId={}", payOrder.getPayOrderId(), e);
            }
        }

        // 4. 多维评分
        try {
            orderRiskService.evaluate(record);
        } catch (Exception e) {
            logger.error("[OrderRiskHook] 评分异常 payOrderId={}", payOrder.getPayOrderId(), e);
            record.setRiskScore(0);
            record.setRiskAction(OrderRiskRecord.ACTION_PASS);
        }

        // 5. 商户上下文加权（风险等级 + IP 国家偏差）
        int delta = scoreMerchantContext(record);
        if (delta > 0) {
            int total = (record.getRiskScore() == null ? 0 : record.getRiskScore()) + delta;
            if (total > 100) total = 100;
            record.setRiskScore(total);
            BigDecimal rejectT = thresholdConfig.getNumber(CFG_REJECT_SCORE, new BigDecimal("60"));
            BigDecimal threeDsT = thresholdConfig.getNumber(CFG_3DS_SCORE, new BigDecimal("30"));
            if (total >= rejectT.intValue()) {
                record.setRiskAction(OrderRiskRecord.ACTION_REJECT);
            } else if (total >= threeDsT.intValue()
                    && !OrderRiskRecord.ACTION_REJECT.equals(record.getRiskAction())) {
                record.setRiskAction(OrderRiskRecord.ACTION_3DS);
            }
        }

        // 6. 智能 3DS（不覆盖 REJECT）
        boolean forceThreeDS = shouldForce3DS(payOrder, record);
        if (forceThreeDS && !OrderRiskRecord.ACTION_REJECT.equals(record.getRiskAction())) {
            record.setRiskAction(OrderRiskRecord.ACTION_3DS);
            record.setThreeDsTriggered((byte) 1);
        }

        // 7. 落库 + 回写
        persistAndWriteBack(payOrder, record);

        // 8. REJECT → 抛异常阻断
        if (OrderRiskRecord.ACTION_REJECT.equals(record.getRiskAction())) {
            String reason = StringUtils.isNotBlank(record.getRiskFactors())
                    ? "命中风控规则：" + record.getRiskFactors()
                    : "订单风险评分超阈值";
            throw new BizException(reason);
        }

        // 9. 构造决策（forceThreeDS 即使评分未达阈值也需通道层执行）
        RiskDecision decision = RiskDecision.fromRecord(record);
        if (forceThreeDS) {
            decision.setForceThreeDS(true);
        }
        return decision;
    }

    /**
     * 构造初始风控记录
     */
    private OrderRiskRecord buildRecord(PayOrder payOrder, JSONObject extra) {
        OrderRiskRecord record = new OrderRiskRecord()
                .setPayOrderId(payOrder.getPayOrderId())
                .setMchNo(payOrder.getMchNo());
        if (extra == null) return record;
        record.setIp(extra.getString("ip"));
        record.setIpCountry(extra.getString("ipCountry"));
        record.setIpRiskLevel(extra.getString("ipRiskLevel"));
        record.setDeviceFingerprint(extra.getString("deviceFingerprint"));
        record.setUserAgent(extra.getString("userAgent"));
        record.setCardBin(extra.getString("cardBin"));
        record.setCardLast4(extra.getString("cardLast4"));
        record.setCardCountry(extra.getString("cardCountry"));
        record.setCardType(extra.getString("cardType"));
        record.setCardBrand(extra.getString("cardBrand"));
        record.setBuyerEmail(extra.getString("buyerEmail"));
        record.setBuyerPhone(extra.getString("buyerPhone"));
        record.setBuyerName(extra.getString("buyerName"));
        return record;
    }

    /**
     * 多类型黑名单检查（按 CARD_BIN > IP > EMAIL > DEVICE 顺序）
     */
    private Optional<RiskBlacklist> checkBlacklist(OrderRiskRecord r) {
        Optional<RiskBlacklist> hit;
        if (StringUtils.isNotBlank(r.getCardBin())
                && (hit = blacklistService.check(RiskBlacklist.TYPE_CARD_BIN, r.getCardBin())).isPresent()) {
            return hit;
        }
        if (StringUtils.isNotBlank(r.getIp())
                && (hit = blacklistService.check(RiskBlacklist.TYPE_IP, r.getIp())).isPresent()) {
            return hit;
        }
        if (StringUtils.isNotBlank(r.getBuyerEmail())
                && (hit = blacklistService.check(RiskBlacklist.TYPE_EMAIL, r.getBuyerEmail())).isPresent()) {
            return hit;
        }
        if (StringUtils.isNotBlank(r.getDeviceFingerprint())
                && (hit = blacklistService.check(RiskBlacklist.TYPE_DEVICE, r.getDeviceFingerprint())).isPresent()) {
            return hit;
        }
        return Optional.empty();
    }

    /**
     * 商户上下文评分：
     * - 商户 risk_tier=high → +15
     * - 本次订单 IP 国家与商户历史主导国家偏差 → +10
     */
    private int scoreMerchantContext(OrderRiskRecord record) {
        int delta = 0;
        try {
            MchInfo mch = mchInfoService.getById(record.getMchNo());
            if (mch != null && "high".equalsIgnoreCase(mch.getRiskTier())) {
                delta += 15;
            }
            if (StringUtils.isNotBlank(record.getIpCountry())) {
                String dominant = orderRiskRecordMapper.findDominantIpCountry(record.getMchNo());
                if (StringUtils.isNotBlank(dominant)
                        && !dominant.equalsIgnoreCase(record.getIpCountry())) {
                    delta += 10;
                }
            }
        } catch (Exception e) {
            logger.warn("[OrderRiskHook] 商户上下文评分异常 mchNo={}", record.getMchNo(), e);
        }
        return delta;
    }

    /**
     * 智能 3DS 触发：商户 high 等级 / 大额订单 / 评分超 3DS 阈值
     */
    private boolean shouldForce3DS(PayOrder payOrder, OrderRiskRecord record) {
        try {
            MchInfo mch = mchInfoService.getById(payOrder.getMchNo());
            if (mch != null && "high".equalsIgnoreCase(mch.getRiskTier())) return true;
        } catch (Exception ignored) { }

        BigDecimal amountTh = thresholdConfig.getNumber(CFG_FORCE_3DS_AMOUNT, new BigDecimal("500000"));
        if (payOrder.getAmount() != null && payOrder.getAmount() > amountTh.longValue()) return true;

        BigDecimal scoreTh = thresholdConfig.getNumber(CFG_3DS_SCORE, new BigDecimal("30"));
        return record.getRiskScore() != null && record.getRiskScore() >= scoreTh.intValue();
    }

    /**
     * 风控记录落库 + 回写 PayOrder 风控字段
     */
    private void persistAndWriteBack(PayOrder payOrder, OrderRiskRecord record) {
        try {
            orderRiskRecordService.save(record);
        } catch (Exception e) {
            logger.error("[OrderRiskHook] 风控记录入库失败 payOrderId={}", payOrder.getPayOrderId(), e);
        }
        try {
            payOrder.setRiskScore(record.getRiskScore());
            payOrder.setRiskAction(record.getRiskAction());
        } catch (Exception e) {
            logger.warn("[OrderRiskHook] 回写 PayOrder 风控字段失败", e);
        }
    }

    /**
     * 构造 risk_factors JSON
     */
    private String buildFactors(String hitRule, String reason, Object value) {
        JSONObject j = new JSONObject();
        j.put("hitRule", hitRule);
        j.put("reason", reason);
        if (value != null) j.put("value", value);
        return j.toJSONString();
    }

    /**
     * 商户风险等级。用途：传给 ChannelAccountRouteHook 做账号路由
     */
    public String getMerchantRiskTier(String mchNo) {
        MchInfo m = mchInfoService.getById(mchNo);
        if (m == null || m.getRiskTier() == null) return "mid";
        return m.getRiskTier();
    }

    /**
     * 通道层只读决策获取（preCheck 已写过的订单）。
     *
     * 注意与 preCheck 的区别：
     * - preCheck：订单保存前调用，会持久化记录并对 reject 抛异常
     * - getDecision：通道层调用，仅读取已落库的字段返回决策对象（不再评分）
     */
    public RiskDecision getDecision(PayOrder payOrder) {
        if (payOrder == null) return RiskDecision.pass();
        OrderRiskRecord shadow = new OrderRiskRecord()
                .setPayOrderId(payOrder.getPayOrderId())
                .setRiskAction(payOrder.getRiskAction())
                .setRiskScore(payOrder.getRiskScore());
        return RiskDecision.fromRecord(shadow);
    }
}
