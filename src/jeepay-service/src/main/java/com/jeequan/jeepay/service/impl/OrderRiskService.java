/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.OrderRiskRecord;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import com.jeequan.jeepay.service.mapper.OrderRiskRecordMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 订单风控服务
 *
 * 核心职责：
 * 1. 订单创建前评估风险，返回评分与建议动作
 * 2. 黑名单命中直接拒绝
 * 3. 设备/卡/IP 数据落库供后续分析
 *
 * 设计原则：
 * - 评分规则可配置（通过 RiskThresholdConfigService 读取权重）
 * - 不做最终拦截决策，返回 risk_action 由调用方决定
 *
 * @author 反风控改造组
 */
@Service
public class OrderRiskService extends ServiceImpl<OrderRiskRecordMapper, OrderRiskRecord> {

    @Autowired
    private BlacklistService blacklistService;

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

    /**
     * 评估订单风险
     *
     * @param record 已填充设备指纹、卡信息、买家信息的记录
     * @return 完整评分后的记录（含 riskScore、riskAction）
     */
    public OrderRiskRecord evaluate(OrderRiskRecord record) {
        int score = 0;
        Map<String, Object> factors = new HashMap<>();

        // 1. 黑名单检查（命中直接最高分）
        Optional<RiskBlacklist> hit = checkBlacklist(record);
        if (hit.isPresent()) {
            record.setRiskScore(100);
            record.setRiskAction(OrderRiskRecord.ACTION_REJECT);
            factors.put("blacklist_hit", hit.get().getListType() + ":" + hit.get().getListValue());
            record.setRiskFactors(toJson(factors));
            return record;
        }

        // 2. 卡类型评分
        if ("prepaid".equalsIgnoreCase(record.getCardType())) {
            score += 20;
            factors.put("prepaid_card", 20);
        }

        // 3. IP 国家与卡国家不一致
        if (StringUtils.isNotBlank(record.getIpCountry()) && StringUtils.isNotBlank(record.getCardCountry())
                && !record.getIpCountry().equalsIgnoreCase(record.getCardCountry())) {
            score += 15;
            factors.put("country_mismatch", 15);
        }

        // 4. IP 风险等级
        if ("high".equalsIgnoreCase(record.getIpRiskLevel())) {
            score += 20;
            factors.put("high_risk_ip", 20);
        } else if ("mid".equalsIgnoreCase(record.getIpRiskLevel())) {
            score += 10;
            factors.put("mid_risk_ip", 10);
        }

        // 5. 一次性邮箱（简单判断，可扩展）
        if (isDisposableEmail(record.getBuyerEmail())) {
            score += 15;
            factors.put("disposable_email", 15);
        }

        record.setRiskScore(score);
        record.setRiskFactors(toJson(factors));

        // 6. 根据阈值配置决定动作（运营在后台调整）
        BigDecimal rejectThreshold = thresholdConfig.getNumber("order.risk_score.reject", new BigDecimal("60"));
        BigDecimal threeDsThreshold = thresholdConfig.getNumber("order.risk_score.3ds", new BigDecimal("30"));

        if (score >= rejectThreshold.intValue()) {
            record.setRiskAction(OrderRiskRecord.ACTION_REJECT);
        } else if (score >= threeDsThreshold.intValue()) {
            record.setRiskAction(OrderRiskRecord.ACTION_3DS);
        } else {
            record.setRiskAction(OrderRiskRecord.ACTION_PASS);
        }

        return record;
    }

    /**
     * 多类型黑名单批量检查
     */
    private Optional<RiskBlacklist> checkBlacklist(OrderRiskRecord r) {
        if (r.getCardBin() != null) {
            Optional<RiskBlacklist> b = blacklistService.check(RiskBlacklist.TYPE_CARD_BIN, r.getCardBin());
            if (b.isPresent()) return b;
        }
        if (r.getIp() != null) {
            Optional<RiskBlacklist> b = blacklistService.check(RiskBlacklist.TYPE_IP, r.getIp());
            if (b.isPresent()) return b;
        }
        if (r.getBuyerEmail() != null) {
            Optional<RiskBlacklist> b = blacklistService.check(RiskBlacklist.TYPE_EMAIL, r.getBuyerEmail());
            if (b.isPresent()) return b;
        }
        if (r.getDeviceFingerprint() != null) {
            Optional<RiskBlacklist> b = blacklistService.check(RiskBlacklist.TYPE_DEVICE, r.getDeviceFingerprint());
            if (b.isPresent()) return b;
        }
        return Optional.empty();
    }

    /**
     * 查询订单的风控记录
     */
    public Optional<OrderRiskRecord> findByPayOrderId(String payOrderId) {
        return Optional.ofNullable(getOne(OrderRiskRecord.gw()
                .eq(OrderRiskRecord::getPayOrderId, payOrderId)
                .last("LIMIT 1")));
    }

    // ===== 辅助方法 =====

    /**
     * 简易一次性邮箱判断
     * 注意事项：实际项目应接入第三方库（如 disposable-email-domains）
     */
    private boolean isDisposableEmail(String email) {
        if (StringUtils.isBlank(email)) return false;
        String[] disposable = {"tempmail", "10minutemail", "guerrillamail", "mailinator", "yopmail"};
        String lower = email.toLowerCase();
        for (String d : disposable) {
            if (lower.contains(d)) return true;
        }
        return false;
    }

    private String toJson(Map<String, Object> map) {
        // 简单序列化，避免引入额外依赖
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append("\"").append(v).append("\"");
            }
            first = false;
        }
        return sb.append("}").toString();
    }
}
