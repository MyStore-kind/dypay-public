/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.service.impl.ChargebackService;
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
 * - 每天凌晨：商户风险评分
 * - 每小时：扫描即将超时的拒付，发送提醒
 *
 * 注意：通道账号健康度相关调度已拆分至 ChannelHealthSchedule，
 *       本类只保留与商户/拒付相关的调度，避免单类承担过多职责
 *
 * @author 反风控改造组
 */
@Component
public class RiskControlScheduleTask {

    private static final Logger logger = LoggerFactory.getLogger(RiskControlScheduleTask.class);

    @Autowired
    private ChargebackService chargebackService;

    @Autowired
    private MchInfoService mchInfoService;

    @Autowired
    private MerchantRiskService merchantRiskService;

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
