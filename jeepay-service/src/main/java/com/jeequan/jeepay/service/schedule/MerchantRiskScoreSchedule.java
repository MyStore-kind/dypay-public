/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.MerchantRiskScore;
import com.jeequan.jeepay.service.impl.MchInfoService;
import com.jeequan.jeepay.service.impl.MerchantRiskService;
import com.jeequan.jeepay.service.impl.RiskCircuitBreakerEngine;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import com.jeequan.jeepay.service.risk.RiskScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商户风险评分作业（任务 #17）
 *
 * 每天凌晨 03:00 执行：
 * - 遍历所有 state=1 的启用商户
 * - 用 {@link MerchantRiskService#evaluateAndSaveDailyScore} 计算最近 30 天指标并落历史表
 * - 同步更新 t_mch_info 的 current_risk_score / risk_tier
 * - 命中 auto_suspend_threshold 时调用 {@link RiskCircuitBreakerEngine} 触发熔断
 *
 * cron 可通过配置 schedule.merchantRiskScore.cron 覆盖，默认凌晨 3 点；
 * 与已有 RiskControlScheduleTask 的"凌晨 2 点评分"任务并存，本类承担"评分后联动"职责。
 *
 * @author 反风控改造组
 */
@Component
public class MerchantRiskScoreSchedule {

    private static final Logger logger = LoggerFactory.getLogger(MerchantRiskScoreSchedule.class);

    @Autowired private MchInfoService mchInfoService;
    @Autowired private MerchantRiskService merchantRiskService;
    @Autowired private RiskCircuitBreakerEngine circuitBreakerEngine;
    @Autowired private RiskThresholdConfigService thresholdConfig;

    /**
     * 每天 03:00 执行；cron 可由 Spring Properties 覆盖
     */
    @Scheduled(cron = "${schedule.merchantRiskScore.cron:0 0 3 * * *}")
    public void run() {
        long t0 = System.currentTimeMillis();
        try {
            // 仅评估启用商户
            List<MchInfo> list = mchInfoService.list(MchInfo.gw().eq(MchInfo::getState, (byte) 1));
            int ok = 0, fail = 0, triggered = 0;
            for (MchInfo m : list) {
                try {
                    boolean fired = evaluateOne(m);
                    if (fired) triggered++;
                    ok++;
                } catch (Exception e) {
                    fail++;
                    logger.error("[MerchantRiskScoreSchedule] 单商户评分失败 mchNo={}", m.getMchNo(), e);
                }
            }
            logger.info("[MerchantRiskScoreSchedule] 完成：总数={} 成功={} 失败={} 触发熔断={} 耗时={}ms",
                    list.size(), ok, fail, triggered, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            logger.error("[MerchantRiskScoreSchedule] 任务整体异常", e);
        }
    }

    /**
     * 评估单个商户（独立方法便于运营手动重跑或测试）
     *
     * @return 是否触发了熔断
     */
    public boolean evaluateOne(MchInfo m) {
        // 1. 评分（含历史回归：取上一条评分作为 historyScore）
        Integer historyScore = lookupHistoryScore(m.getMchNo());
        MerchantRiskScore today = merchantRiskService.evaluateAndSaveDailyScore(m.getMchNo());

        // 若需要使用历史评分覆盖（evaluateAndSaveDailyScore 当前传 null），
        // 这里二次计算确保 historyScore 生效；并对外暴露 RiskScoreCalculator 的统一口径。
        int score = RiskScoreCalculator.calculate(
                today.getChargebackRate(),
                today.getDisputeRate(),
                today.getRefundRate(),
                today.getSuccessRate(),
                today.getHighRiskCardRate(),
                historyScore);
        String tier = RiskScoreCalculator.scoreToTier(score);

        // 写回评分记录与最新口径
        today.setRiskScore(score);
        today.setRiskTier(tier);
        merchantRiskService.updateById(today);

        // 2. 同步 t_mch_info 的当前评分与等级
        try {
            MchInfo upd = new MchInfo()
                    .setMchNo(m.getMchNo())
                    .setCurrentRiskScore(score)
                    .setRiskTier(tier);
            mchInfoService.updateById(upd);
        } catch (Exception e) {
            logger.error("[MerchantRiskScoreSchedule] 更新 t_mch_info 失败 mchNo={}", m.getMchNo(), e);
        }

        // 3. 阈值联动 - 触发熔断 / 限流 / 告警
        return triggerIfNeeded(m, today, score);
    }

    /**
     * 查询历史评分；查不到时返回 50（中位），与 RiskScoreCalculator 默认值对齐
     */
    private Integer lookupHistoryScore(String mchNo) {
        MerchantRiskScore last = merchantRiskService.getOne(
                MerchantRiskScore.gw()
                        .eq(MerchantRiskScore::getMchNo, mchNo)
                        .orderByDesc(MerchantRiskScore::getScoreDate)
                        .last("LIMIT 1"));
        return last == null ? RiskScoreCalculator.DEFAULT_HISTORY_SCORE : last.getRiskScore();
    }

    /**
     * 根据阈值决定触发哪种动作。
     *
     * 注意：阈值与动作完全由 t_risk_threshold_config 控制：
     *   merchant.auto_suspend.threshold     -> 评分超此值，按配置的 action_type 触发
     *   merchant.chargeback_rate.critical   -> 拒付率超红线，触发限流/暂停
     *   merchant.high_risk_score.threshold  -> 已有，仅告警
     */
    private boolean triggerIfNeeded(MchInfo m, MerchantRiskScore today, int score) {
        // 评分维度
        BigDecimal suspendScoreThreshold = thresholdConfig.getNumber(
                "merchant.auto_suspend.threshold", new BigDecimal("90"));
        BigDecimal alertScoreThreshold = thresholdConfig.getNumber(
                "merchant.high_risk_score.threshold", new BigDecimal("70"));

        // 拒付率维度
        BigDecimal cbCritical = thresholdConfig.getNumber(
                "merchant.chargeback_rate.critical", new BigDecimal("0.9"));
        BigDecimal cbWarning  = thresholdConfig.getNumber(
                "merchant.chargeback_rate.warning",  new BigDecimal("0.7"));

        JSONObject metrics = new JSONObject();
        metrics.put("riskScore", score);
        metrics.put("chargebackRate", today.getChargebackRate());
        metrics.put("disputeRate",    today.getDisputeRate());
        metrics.put("refundRate",     today.getRefundRate());
        metrics.put("successRate",    today.getSuccessRate());

        boolean fired = false;

        // 评分超 auto_suspend_threshold：按运营配置的 action_type 执行；
        // 默认动作为 SUSPEND（兜底，确保高分商户一定被处理）
        if (score >= suspendScoreThreshold.intValue()) {
            String action = mapConfigAction(
                    thresholdConfig.getActionType("merchant.auto_suspend.threshold"),
                    RiskCircuitBreakerEngine.ACTION_SUSPEND);
            // 商户必须开启 auto_suspend_enabled 才允许真的冻结，否则降级为告警
            if (RiskCircuitBreakerEngine.ACTION_SUSPEND.equals(action)
                    && (m.getAutoSuspendEnabled() == null || m.getAutoSuspendEnabled() != 1)) {
                action = RiskCircuitBreakerEngine.ACTION_ALERT;
            }
            circuitBreakerEngine.triggerForMerchant(
                    m.getMchNo(), action,
                    "评分 " + score + " 超 auto_suspend 阈值 " + suspendScoreThreshold,
                    metrics);
            fired = true;
        } else if (today.getChargebackRate() != null
                && today.getChargebackRate().compareTo(cbCritical) >= 0) {
            // 拒付率红线 → 限流
            circuitBreakerEngine.triggerForMerchant(
                    m.getMchNo(), RiskCircuitBreakerEngine.ACTION_THROTTLE,
                    "拒付率 " + today.getChargebackRate() + "% 超红线 " + cbCritical + "%",
                    metrics);
            fired = true;
        } else if (score >= alertScoreThreshold.intValue()
                || (today.getChargebackRate() != null
                    && today.getChargebackRate().compareTo(cbWarning) >= 0)) {
            // 黄线告警
            circuitBreakerEngine.triggerForMerchant(
                    m.getMchNo(), RiskCircuitBreakerEngine.ACTION_ALERT,
                    "评分 " + score + " 或拒付率超黄线",
                    metrics);
            fired = true;
        }
        return fired;
    }

    /**
     * RiskThresholdConfig 的小写 action 映射到 RiskCircuitBreakerEngine 的常量
     */
    private String mapConfigAction(String configAction, String fallback) {
        if (configAction == null) return fallback;
        switch (configAction) {
            case "notify": return RiskCircuitBreakerEngine.ACTION_ALERT;
            case "limit":  return RiskCircuitBreakerEngine.ACTION_THROTTLE;
            case "switch": return RiskCircuitBreakerEngine.ACTION_SWITCH_CHANNEL;
            case "suspend": return RiskCircuitBreakerEngine.ACTION_SUSPEND;
            default: return fallback;
        }
    }
}
