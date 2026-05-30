/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.OrderRiskRecord;

/**
 * 风控决策结果（供通道层读取）
 *
 * 为什么独立成对象：
 * - 风控的输出不止是 reject/pass，还可能包含通道层需要的辅助决策（如强制 3DS、要求人工审核）
 * - 通道层（如 Stripe）需根据决策动态切换支付参数（request_three_d_secure 等）
 *
 * 注意：本对象由 {@link OrderRiskHookService} 构造，通道层只读不写。
 */
public class RiskDecision {

    /** 风控动作：pass / 3ds / reject */
    private String action;

    /** 风险评分（0-100） */
    private Integer score;

    /** 命中的风控规则明细 JSON */
    private String factors;

    /** 是否强制走 3DS：用于 Stripe 等支持动态 SCA 的通道 */
    private boolean forceThreeDS;

    public RiskDecision() {}

    public static RiskDecision pass() {
        RiskDecision d = new RiskDecision();
        d.action = OrderRiskRecord.ACTION_PASS;
        d.score = 0;
        d.forceThreeDS = false;
        return d;
    }

    public static RiskDecision fromRecord(OrderRiskRecord r) {
        RiskDecision d = new RiskDecision();
        if (r == null) {
            d.action = OrderRiskRecord.ACTION_PASS;
            d.score = 0;
            d.forceThreeDS = false;
            return d;
        }
        d.action = r.getRiskAction();
        d.score = r.getRiskScore();
        d.factors = r.getRiskFactors();
        // 关键：当风控动作为 3ds，或评分 >= 60，强制走 3DS
        // 为什么这么做：高风险订单走 3DS 可将拒付责任转移给发卡行，降低商户拒付率
        d.forceThreeDS = OrderRiskRecord.ACTION_3DS.equals(r.getRiskAction())
                || (r.getRiskScore() != null && r.getRiskScore() >= 60);
        return d;
    }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getFactors() { return factors; }
    public void setFactors(String factors) { this.factors = factors; }
    public boolean isForceThreeDS() { return forceThreeDS; }
    public void setForceThreeDS(boolean forceThreeDS) { this.forceThreeDS = forceThreeDS; }
}
