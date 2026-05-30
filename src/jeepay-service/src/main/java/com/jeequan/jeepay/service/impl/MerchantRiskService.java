/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.MerchantRiskScore;
import com.jeequan.jeepay.service.mapper.MerchantRiskScoreMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 商户风险评分服务
 *
 * 评分模型（0-100，越大风险越高）：
 *   risk_score = 30 × 拒付率得分
 *              + 20 × 投诉率得分
 *              + 15 × 退款率得分
 *              + 15 × (100-成功率)得分
 *              + 10 × 高风险卡占比得分
 *              + 10 × 历史评分回归
 *
 * 注意：评分模型不调用阈值配置（评分本身是中性数据）
 * 等级划分阈值由 RiskThresholdConfigService 提供
 *
 * @author 反风控改造组
 */
@Service
public class MerchantRiskService extends ServiceImpl<MerchantRiskScoreMapper, MerchantRiskScore> {

    /**
     * 计算商户风险评分
     * 输入指标已计算好，本方法只做加权评分
     */
    public MerchantRiskScore calculateScore(String mchNo,
                                            BigDecimal chargebackRate,
                                            BigDecimal disputeRate,
                                            BigDecimal refundRate,
                                            BigDecimal successRate,
                                            BigDecimal highRiskCardRate,
                                            Integer totalOrders30d,
                                            Long totalAmount30d,
                                            Integer historyScore) {
        // 每项得分 = min(指标 / 红线值, 1) × 100
        // 红线参考值：拒付 1%、投诉 1%、退款 8%、成功率<85%
        double cbScore = score(chargebackRate, new BigDecimal("1.0"));
        double dpScore = score(disputeRate, new BigDecimal("1.0"));
        double rfScore = score(refundRate, new BigDecimal("8.0"));
        double scScore = score(new BigDecimal("100").subtract(successRate == null ? BigDecimal.ZERO : successRate), new BigDecimal("15.0"));
        double cardScore = score(highRiskCardRate, new BigDecimal("30.0"));
        double histScore = historyScore == null ? 50 : historyScore;

        double total = cbScore * 0.3 + dpScore * 0.2 + rfScore * 0.15
                + scScore * 0.15 + cardScore * 0.1 + histScore * 0.1;

        int score = (int) Math.round(total);
        if (score > 100) score = 100;
        if (score < 0) score = 0;

        String tier = scoreToTier(score);

        Map<String, Object> detail = new HashMap<>();
        detail.put("chargeback", cbScore);
        detail.put("dispute", dpScore);
        detail.put("refund", rfScore);
        detail.put("success", scScore);
        detail.put("card", cardScore);
        detail.put("history", histScore);

        return new MerchantRiskScore()
                .setMchNo(mchNo)
                .setScoreDate(new Date())
                .setRiskScore(score)
                .setRiskTier(tier)
                .setChargebackRate(chargebackRate)
                .setDisputeRate(disputeRate)
                .setRefundRate(refundRate)
                .setSuccessRate(successRate)
                .setHighRiskCardRate(highRiskCardRate)
                .setTotalOrders30d(totalOrders30d)
                .setTotalAmount30d(totalAmount30d)
                .setEvaluationWindow("30D")
                .setScoreDetail(toJson(detail));
    }

    /**
     * 单项得分计算
     * 公式：min(value/threshold, 1) × 100
     */
    private double score(BigDecimal value, BigDecimal threshold) {
        if (value == null || threshold == null || threshold.signum() == 0) return 0;
        double ratio = value.divide(threshold, 4, RoundingMode.HALF_UP).doubleValue();
        if (ratio > 1) ratio = 1;
        return ratio * 100;
    }

    /**
     * 评分到等级的映射
     * 默认：>=70 high, >=40 mid, 其他 low
     * 注意：等级阈值后续可改为读取 RiskThresholdConfig
     */
    public String scoreToTier(int score) {
        if (score >= 70) return MerchantRiskScore.TIER_HIGH;
        if (score >= 40) return MerchantRiskScore.TIER_MID;
        return MerchantRiskScore.TIER_LOW;
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }
}
