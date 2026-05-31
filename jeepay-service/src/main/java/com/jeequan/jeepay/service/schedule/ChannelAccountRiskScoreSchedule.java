/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.service.impl.ChannelAccountService;
import com.jeequan.jeepay.service.impl.RiskCircuitBreakerEngine;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 渠道账号风险评分作业（P1-2）
 *
 * 与 MerchantRiskScoreSchedule 的关系：
 *   - MerchantRiskScoreSchedule = 算"商户"的分
 *   - 本类 = 算"通道账号"的分
 * 两者口径独立，但共享 RiskCircuitBreakerEngine 的熔断动作。
 *
 * 评分公式（权重和 = 100）：
 *   分 = 100 - chargebackRate × 30 - disputeRate × 20 - refundRate × 10
 *           + (successRate - 80) × 0.5   ← 成功率 < 80% 时扣分
 *
 * 阈值（运营可在 t_risk_threshold_config 调整）：
 *   channel.account.critical.threshold = 30   评分 ≤ 30 → SUSPEND
 *   channel.account.warning.threshold  = 60   评分 ≤ 60 → THROTTLE
 *   channel.account.alert.threshold    = 75   评分 ≤ 75 → ALERT
 *
 * cron 默认凌晨 3:30 执行，与商户评分错开 30 分钟，避免互相阻塞 DB
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Component
public class ChannelAccountRiskScoreSchedule {

    private static final Logger logger = LoggerFactory.getLogger(ChannelAccountRiskScoreSchedule.class);

    @Autowired private ChannelAccountService channelAccountService;
    @Autowired private RiskCircuitBreakerEngine circuitBreakerEngine;
    @Autowired private RiskThresholdConfigService thresholdConfig;

    @Scheduled(cron = "${schedule.channelAccountRiskScore.cron:0 30 3 * * *}")
    public void run() {
        long t0 = System.currentTimeMillis();
        try {
            List<ChannelAccount> accounts = channelAccountService.list(
                    ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));
            int ok = 0, fail = 0, triggered = 0;
            for (ChannelAccount a : accounts) {
                try {
                    if (evaluateOne(a)) triggered++;
                    ok++;
                } catch (Exception e) {
                    fail++;
                    logger.error("[ChannelAccountScore] 单账号评分失败 accountId={}", a.getAccountId(), e);
                }
            }
            logger.info("[ChannelAccountScore] 完成：总数={} 成功={} 失败={} 触发熔断={} 耗时={}ms",
                    accounts.size(), ok, fail, triggered, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            logger.error("[ChannelAccountScore] 任务整体异常", e);
        }
    }

    /**
     * 评估单个账号
     * 返回 true=触发了熔断或限流
     */
    public boolean evaluateOne(ChannelAccount a) {
        BigDecimal cb = nullSafe(a.getChargebackRate());
        BigDecimal dp = nullSafe(a.getDisputeRate());
        BigDecimal rf = nullSafe(a.getRefundRate());
        BigDecimal ss = a.getSuccessRate() == null ? new BigDecimal("100") : a.getSuccessRate();

        // 评分计算
        BigDecimal score = new BigDecimal("100")
                .subtract(cb.multiply(new BigDecimal("30")))
                .subtract(dp.multiply(new BigDecimal("20")))
                .subtract(rf.multiply(new BigDecimal("10")));
        // 成功率 < 80% 时再扣分
        if (ss.compareTo(new BigDecimal("80")) < 0) {
            score = score.subtract(new BigDecimal("80").subtract(ss).multiply(new BigDecimal("0.5")));
        }
        int s = score.intValue();
        if (s < 0) s = 0; else if (s > 100) s = 100;

        // 把最新口径写回 ChannelAccount（success_rate 等字段由健康度作业填，本任务不动）
        try {
            ChannelAccount upd = new ChannelAccount()
                    .setAccountId(a.getAccountId())
                    .setLastHealthCheckAt(new Date());
            channelAccountService.updateById(upd);
        } catch (Exception e) {
            logger.error("[ChannelAccountScore] 写回最后检查时间失败 accountId={}", a.getAccountId(), e);
        }

        // 阈值联动
        BigDecimal critical = thresholdConfig.getNumber(
                "channel.account.critical.threshold", new BigDecimal("30"));
        BigDecimal warning = thresholdConfig.getNumber(
                "channel.account.warning.threshold", new BigDecimal("60"));
        BigDecimal alert = thresholdConfig.getNumber(
                "channel.account.alert.threshold", new BigDecimal("75"));

        JSONObject metrics = new JSONObject();
        metrics.put("score", s);
        metrics.put("chargebackRate", cb);
        metrics.put("disputeRate", dp);
        metrics.put("refundRate", rf);
        metrics.put("successRate", ss);

        if (s <= critical.intValue()) {
            circuitBreakerEngine.triggerForAccount(
                    a.getAccountId(), RiskCircuitBreakerEngine.ACTION_SUSPEND,
                    "账号风险分 " + s + " ≤ critical " + critical, metrics);
            return true;
        } else if (s <= warning.intValue()) {
            circuitBreakerEngine.triggerForAccount(
                    a.getAccountId(), RiskCircuitBreakerEngine.ACTION_THROTTLE,
                    "账号风险分 " + s + " ≤ warning " + warning, metrics);
            return true;
        } else if (s <= alert.intValue()) {
            circuitBreakerEngine.triggerForAccount(
                    a.getAccountId(), RiskCircuitBreakerEngine.ACTION_ALERT,
                    "账号风险分 " + s + " ≤ alert " + alert, metrics);
            return true;
        }
        return false;
    }

    private BigDecimal nullSafe(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
