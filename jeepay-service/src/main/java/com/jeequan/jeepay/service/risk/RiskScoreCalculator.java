/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商户风险评分计算器（纯函数）
 *
 * 设计要点：
 * - 不依赖 Spring，任何静态方法都可在单测里直接调用
 * - 输入指标全部由外部聚合好后传入，本类只负责加权计算
 * - 权重与红线值集中管理，便于调整
 *
 * 评分公式（0-100，越大越危险）：
 *   riskScore = 0.30 × chargebackScore        (拒付率 / 红线 × 100，封顶 100)
 *             + 0.20 × disputeScore           (投诉率)
 *             + 0.15 × refundScore            (退款率)
 *             + 0.15 × (100 - successScore)   (成功率反向)
 *             + 0.10 × highRiskCardScore      (高风险卡 BIN 占比，目前置 0)
 *             + 0.10 × historyScore           (上次评分，缺失默认 50)
 *
 * @author 反风控改造组
 */
public final class RiskScoreCalculator {

    // === 红线参考值（百分比基准），与技术方案 V3 对齐 ===
    /** 拒付率红线 1% */
    public static final BigDecimal RED_CHARGEBACK = new BigDecimal("1.0");
    /** 投诉率红线 1% */
    public static final BigDecimal RED_DISPUTE    = new BigDecimal("1.0");
    /** 退款率红线 8% */
    public static final BigDecimal RED_REFUND     = new BigDecimal("8.0");
    /** 失败率红线 15%（成功率反向） */
    public static final BigDecimal RED_FAIL       = new BigDecimal("15.0");
    /** 高风险卡占比红线 30% */
    public static final BigDecimal RED_HIGH_RISK_CARD = new BigDecimal("30.0");

    // === 权重 ===
    public static final double W_CHARGEBACK = 0.30;
    public static final double W_DISPUTE    = 0.20;
    public static final double W_REFUND     = 0.15;
    public static final double W_FAIL       = 0.15;
    public static final double W_CARD       = 0.10;
    public static final double W_HISTORY    = 0.10;

    // === 等级阈值 ===
    public static final int TIER_HIGH_LINE = 70;
    public static final int TIER_MID_LINE  = 40;

    /** 历史评分缺失时的默认值（mid 中位） */
    public static final int DEFAULT_HISTORY_SCORE = 50;

    private RiskScoreCalculator() { /* 工具类禁实例化 */ }

    /**
     * 计算综合风险评分
     *
     * @param chargebackRate    拒付率（百分比，如 0.85 表示 0.85%）
     * @param disputeRate       投诉率
     * @param refundRate        退款率
     * @param successRate       成功率
     * @param highRiskCardRate  高风险卡 BIN 占比
     * @param historyScore      上一次评分（null -> 50）
     * @return 0-100 的整数评分
     */
    public static int calculate(BigDecimal chargebackRate,
                                BigDecimal disputeRate,
                                BigDecimal refundRate,
                                BigDecimal successRate,
                                BigDecimal highRiskCardRate,
                                Integer historyScore) {
        double cb   = scaleToScore(chargebackRate, RED_CHARGEBACK);
        double dp   = scaleToScore(disputeRate,    RED_DISPUTE);
        double rf   = scaleToScore(refundRate,     RED_REFUND);
        // 失败率 = 100 - successRate；successRate 为空时按 100 视为完美（失败率 0）
        BigDecimal failRate = (successRate == null)
                ? BigDecimal.ZERO
                : new BigDecimal("100").subtract(successRate);
        double fl   = scaleToScore(failRate, RED_FAIL);
        double card = scaleToScore(highRiskCardRate, RED_HIGH_RISK_CARD);
        double hist = (historyScore == null) ? DEFAULT_HISTORY_SCORE : historyScore;

        double total = cb * W_CHARGEBACK
                + dp * W_DISPUTE
                + rf * W_REFUND
                + fl * W_FAIL
                + card * W_CARD
                + hist * W_HISTORY;

        return clamp((int) Math.round(total));
    }

    /**
     * 评分到等级映射
     */
    public static String scoreToTier(int score) {
        if (score > TIER_HIGH_LINE) return "high";
        if (score >= TIER_MID_LINE) return "mid";
        return "low";
    }

    /**
     * 单项得分：min(value / threshold, 1) × 100
     * 注意：value、threshold 任一为空或阈值为 0 时返回 0，避免除零
     */
    public static double scaleToScore(BigDecimal value, BigDecimal threshold) {
        if (value == null || threshold == null || threshold.signum() == 0) return 0d;
        if (value.signum() <= 0) return 0d;
        double ratio = value.divide(threshold, 6, RoundingMode.HALF_UP).doubleValue();
        if (ratio > 1) ratio = 1;
        return ratio * 100d;
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }
}
