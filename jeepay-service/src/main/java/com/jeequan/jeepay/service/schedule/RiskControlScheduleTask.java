/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.ChannelHealthSnapshot;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.service.impl.ChannelAccountService;
import com.jeequan.jeepay.service.impl.ChannelHealthService;
import com.jeequan.jeepay.service.impl.ChargebackService;
import com.jeequan.jeepay.service.impl.CircuitBreakerEngine;
import com.jeequan.jeepay.service.impl.MchInfoService;
import com.jeequan.jeepay.service.impl.MerchantRiskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 反风控调度任务集合
 *
 * 任务列表：
 * - 每 5 分钟：刷新通道账号实时指标（30D 窗口）
 * - 每小时：生成所有通道账号的 1H/24H 健康度快照
 * - 每天凌晨：生成 7D/30D 快照 + 商户风险评分
 * - 每小时：扫描即将超时的拒付，发送提醒
 *
 * 注意：任务异常不应阻塞主业务，仅记录日志
 *
 * @author 反风控改造组
 */
@Component
public class RiskControlScheduleTask {

    private static final Logger logger = LoggerFactory.getLogger(RiskControlScheduleTask.class);

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private ChannelHealthService channelHealthService;

    @Autowired
    private CircuitBreakerEngine circuitBreakerEngine;

    @Autowired
    private ChargebackService chargebackService;

    @Autowired
    private MchInfoService mchInfoService;

    @Autowired
    private MerchantRiskService merchantRiskService;

    /**
     * 每 5 分钟刷新通道账号实时指标
     * 任务粒度：所有启用的账号
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void refreshChannelMetrics() {
        try {
            List<ChannelAccount> accounts = channelAccountService.list(
                    ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));
            for (ChannelAccount a : accounts) {
                try {
                    ChannelHealthSnapshot snap = channelHealthService.generateAndSave(
                            a.getAccountId(), ChannelHealthSnapshot.WINDOW_30D);
                    // 触发熔断引擎检查（读取最新快照后的账号数据）
                    ChannelAccount latest = channelAccountService.getById(a.getAccountId());
                    circuitBreakerEngine.checkChannelAccount(latest);
                } catch (Exception e) {
                    logger.error("[Schedule] 刷新账号指标失败 accountId={}", a.getAccountId(), e);
                }
            }
            logger.info("[Schedule] 通道账号指标刷新完成，账号数={}", accounts.size());
        } catch (Exception e) {
            logger.error("[Schedule] 通道账号指标刷新任务异常", e);
        }
    }

    /**
     * 每小时生成 1H/24H 健康度快照
     */
    @Scheduled(cron = "0 0 * * * *")
    public void generateHourlySnapshots() {
        try {
            List<ChannelAccount> accounts = channelAccountService.list(
                    ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));
            for (ChannelAccount a : accounts) {
                try {
                    channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_1H);
                    channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_24H);
                } catch (Exception e) {
                    logger.error("[Schedule] 小时快照生成失败 accountId={}", a.getAccountId(), e);
                }
            }
        } catch (Exception e) {
            logger.error("[Schedule] 小时快照任务异常", e);
        }
    }

    /**
     * 每天凌晨 1 点生成 7D/30D 快照
     * 注意：避开业务高峰，凌晨执行
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void generateDailySnapshots() {
        try {
            List<ChannelAccount> accounts = channelAccountService.list(
                    ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));
            for (ChannelAccount a : accounts) {
                try {
                    channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_7D);
                    channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_30D);
                } catch (Exception e) {
                    logger.error("[Schedule] 日级快照生成失败 accountId={}", a.getAccountId(), e);
                }
            }
        } catch (Exception e) {
            logger.error("[Schedule] 日级快照任务异常", e);
        }
    }

    /**
     * 每天凌晨 2 点为所有启用商户生成风险评分
     * 注意：错开 1 点的通道快照任务，让 30D 数据先聚合完毕
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void generateDailyMerchantScores() {
        try {
            List<MchInfo> mchList = mchInfoService.list();
            int success = 0, fail = 0;
            for (MchInfo m : mchList) {
                try {
                    merchantRiskService.evaluateAndSaveDailyScore(m.getMchNo());
                    success++;
                } catch (Exception e) {
                    fail++;
                    logger.error("[Schedule] 商户评分失败 mchNo={}", m.getMchNo(), e);
                }
            }
            logger.info("[Schedule] 商户每日评分完成 总数={} 成功={} 失败={}", mchList.size(), success, fail);
        } catch (Exception e) {
            logger.error("[Schedule] 商户评分任务异常", e);
        }
    }

    /**
     * 每小时扫描即将超时的拒付（48 小时内）
     */
    @Scheduled(cron = "0 30 * * * *")
    public void scanExpiringChargebacks() {
        try {
            chargebackService.listExpiringSoon(48).forEach(cb -> {
                logger.warn("[Schedule] 拒付即将超时：pay_order_id={}, due_at={}",
                        cb.getPayOrderId(), cb.getEvidenceDueAt());
                // 由 CircuitBreakerEngine 或独立通知逻辑发出告警，此处仅记录
            });
        } catch (Exception e) {
            logger.error("[Schedule] 拒付超时扫描异常", e);
        }
    }
}
