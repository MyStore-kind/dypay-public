/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.RiskThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 熔断/降流引擎
 *
 * 核心职责：
 * - 监听通道账号、商户指标
 * - 对照运营配置的阈值
 * - 触发对应动作（告警/限流/暂停）
 *
 * 设计原则：
 * - 系统不内置任何"应该怎么做"的逻辑
 * - 全部读取 RiskThresholdConfig 的运营配置
 * - 触发动作可独立开关（action_enabled）
 *
 * @author 反风控改造组
 */
@Service
public class CircuitBreakerEngine {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerEngine.class);

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private NotificationService notificationService;

    /**
     * 检查通道账号是否需要触发动作
     * 由调度任务每次更新健康度后调用
     */
    public void checkChannelAccount(ChannelAccount account) {
        if (account == null) return;

        // 拒付率检查
        checkChannelMetric(
                account, "chargeback_rate", account.getChargebackRate(),
                "channel.chargeback_rate.warning",
                "channel.chargeback_rate.critical");

        // 投诉率检查
        checkChannelMetric(
                account, "dispute_rate", account.getDisputeRate(),
                "channel.dispute_rate.warning",
                "channel.dispute_rate.critical");

        // 退款率检查
        checkChannelMetric(
                account, "refund_rate", account.getRefundRate(),
                "channel.refund_rate.warning",
                "channel.refund_rate.critical");

        // 成功率检查（反向：低于阈值才触发）
        checkChannelSuccessRate(account);
    }

    private void checkChannelMetric(ChannelAccount account, String metricName,
                                    BigDecimal currentValue,
                                    String warningKey, String criticalKey) {
        if (currentValue == null) return;

        BigDecimal warning = thresholdConfig.getNumber(warningKey, null);
        BigDecimal critical = thresholdConfig.getNumber(criticalKey, null);

        if (critical != null && currentValue.compareTo(critical) >= 0) {
            triggerCriticalAction(account, metricName, currentValue, criticalKey);
        } else if (warning != null && currentValue.compareTo(warning) >= 0) {
            triggerWarningAction(account, metricName, currentValue, warningKey);
        }
    }

    private void checkChannelSuccessRate(ChannelAccount account) {
        if (account.getSuccessRate() == null) return;
        BigDecimal warning = thresholdConfig.getNumber("channel.success_rate.warning", null);
        BigDecimal critical = thresholdConfig.getNumber("channel.success_rate.critical", null);
        if (critical != null && account.getSuccessRate().compareTo(critical) < 0) {
            triggerCriticalAction(account, "success_rate", account.getSuccessRate(), "channel.success_rate.critical");
        } else if (warning != null && account.getSuccessRate().compareTo(warning) < 0) {
            triggerWarningAction(account, "success_rate", account.getSuccessRate(), "channel.success_rate.warning");
        }
    }

    private void triggerWarningAction(ChannelAccount account, String metric, BigDecimal value, String configKey) {
        // 黄线只告警（更新健康度为警告）
        if (account.getHealthStatus() != null && account.getHealthStatus() != ChannelAccount.HEALTH_WARNING) {
            channelAccountService.updateHealthStatus(account.getAccountId(), ChannelAccount.HEALTH_WARNING);
        }

        if (!thresholdConfig.isActionEnabled(configKey)) {
            logger.info("[Circuit] {} 触发黄线但动作禁用：{}={}", account.getAccountId(), metric, value);
            return;
        }
        notificationService.notify(
                "通道账号黄线告警",
                String.format("账号 %s 的 %s 当前值 %s 超过黄线阈值", account.getAccountId(), metric, value));
    }

    private void triggerCriticalAction(ChannelAccount account, String metric, BigDecimal value, String configKey) {
        // 红线动作：根据配置 action_type 执行
        String action = thresholdConfig.getActionType(configKey);
        boolean enabled = thresholdConfig.isActionEnabled(configKey);

        // 始终通知
        notificationService.notify(
                "通道账号红线告警",
                String.format("账号 %s 的 %s 当前值 %s 已超过红线（动作：%s 启用：%s）",
                        account.getAccountId(), metric, value, action, enabled));

        if (!enabled) {
            logger.warn("[Circuit] {} 触发红线但动作禁用：{}={}", account.getAccountId(), metric, value);
            return;
        }

        switch (action == null ? "" : action) {
            case RiskThresholdConfig.ACTION_LIMIT:
                channelAccountService.updateHealthStatus(account.getAccountId(), ChannelAccount.HEALTH_LIMITED);
                break;
            case RiskThresholdConfig.ACTION_SUSPEND:
                ChannelAccount upd = new ChannelAccount()
                        .setAccountId(account.getAccountId())
                        .setState(ChannelAccount.STATE_FROZEN);
                channelAccountService.updateById(upd);
                break;
            case RiskThresholdConfig.ACTION_NOTIFY:
            default:
                // 仅通知，已在上方处理
                break;
        }
    }

    /**
     * 检查商户是否需要触发动作
     */
    public void checkMerchant(MchInfo merchant, BigDecimal chargebackRate, BigDecimal disputeRate, int riskScore) {
        BigDecimal cbCritical = thresholdConfig.getNumber("merchant.chargeback_rate.critical", null);
        BigDecimal dpCritical = thresholdConfig.getNumber("merchant.dispute_rate.critical", null);
        BigDecimal scoreThreshold = thresholdConfig.getNumber("merchant.high_risk_score.threshold", null);

        boolean shouldAlert = false;
        StringBuilder reason = new StringBuilder();

        if (cbCritical != null && chargebackRate != null && chargebackRate.compareTo(cbCritical) >= 0) {
            shouldAlert = true;
            reason.append("拒付率 ").append(chargebackRate).append("% 超红线 ").append(cbCritical).append("%；");
        }
        if (dpCritical != null && disputeRate != null && disputeRate.compareTo(dpCritical) >= 0) {
            shouldAlert = true;
            reason.append("投诉率 ").append(disputeRate).append("% 超红线 ").append(dpCritical).append("%；");
        }
        if (scoreThreshold != null && riskScore >= scoreThreshold.intValue()) {
            shouldAlert = true;
            reason.append("评分 ").append(riskScore).append(" 超阈值；");
        }

        if (shouldAlert) {
            notificationService.notify(
                    "商户风险告警",
                    String.format("商户 %s：%s", merchant.getMchNo(), reason));

            // 自动暂停（仅当商户开启了 auto_suspend）
            if (merchant.getAutoSuspendEnabled() != null && merchant.getAutoSuspendEnabled() == 1) {
                logger.warn("[Circuit] 商户 {} 触发自动暂停", merchant.getMchNo());
                // TODO 调用 MchInfoService 修改 state（避免循环依赖，此处仅记录）
            }
        }
    }
}
