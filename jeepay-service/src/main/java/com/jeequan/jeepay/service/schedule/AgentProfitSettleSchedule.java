/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.AgentInfo;
import com.jeequan.jeepay.service.impl.AgentSettleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 代理商分润对账调度（任务 #3）
 *
 * 触发：每天凌晨 02:00
 * 注意：错开 01:00 的通道快照任务、错开 02:00 的商户评分（这俩是只读聚合，
 *      本作业涉及写库，cron 偏移 10 分钟到 02:10 避免锁竞争）
 *
 * T0：实时结算，不在此处理
 * T1：每天跑一次（处理昨日明细）
 * T7：仅每周一跑（处理过去 7 天）
 * T30：仅每月 1 号跑（处理上月）
 *
 * 异常处理：单周期失败不阻塞其他周期；单代理商失败已在 service 内吞掉
 *
 * @author 国际支付改造组
 */
@Component
public class AgentProfitSettleSchedule {

    private static final Logger logger = LoggerFactory.getLogger(AgentProfitSettleSchedule.class);

    @Autowired
    private AgentSettleService agentSettleService;

    /**
     * 每天 02:10 触发
     * 单次入口集中调度，便于运维只看一条日志即可了解全周期结算情况
     */
    @Scheduled(cron = "0 10 2 * * *")
    public void runDaily() {
        long startTs = System.currentTimeMillis();
        logger.info("[AgentSettleSchedule] 开始执行代理商分润对账");

        // T1：每天都要跑
        runSafely(AgentInfo.CYCLE_T1);

        java.time.LocalDate today = java.time.LocalDate.now();

        // T7：仅周一跑一次（与 computeWindow 的"上周一→本周一"窗口对齐）
        if (today.getDayOfWeek() == java.time.DayOfWeek.MONDAY) {
            runSafely(AgentInfo.CYCLE_T7);
        }

        // T30：每月 1 号跑一次（处理上月）
        if (today.getDayOfMonth() == 1) {
            runSafely(AgentInfo.CYCLE_T30);
        }

        logger.info("[AgentSettleSchedule] 全部周期执行完毕 耗时={}ms",
                System.currentTimeMillis() - startTs);
    }

    /** 包一层 try-catch，单周期异常不影响后续周期 */
    private void runSafely(String cycle) {
        try {
            int count = agentSettleService.settleByCycle(cycle);
            logger.info("[AgentSettleSchedule] 周期 {} 完成 结算单数={}", cycle, count);
        } catch (Exception e) {
            logger.error("[AgentSettleSchedule] 周期 {} 异常", cycle, e);
        }
    }
}
