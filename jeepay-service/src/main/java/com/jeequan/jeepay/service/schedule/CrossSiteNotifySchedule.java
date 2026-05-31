/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.CrossSiteNotifyRecord;
import com.jeequan.jeepay.service.impl.CrossSiteNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跨站通知调度器：每 30s 扫一批 due 通知
 *
 * 设计：
 *   - 单实例运行（多实例靠 fetch+update 的乐观锁/SELECT FOR UPDATE 兜底，本期略）
 *   - batchSize=50，足够覆盖大多数场景；如积压可考虑分片
 *
 * @author 反风控改造组
 */
@Component
public class CrossSiteNotifySchedule {

    private static final Logger logger = LoggerFactory.getLogger(CrossSiteNotifySchedule.class);

    @Autowired private CrossSiteNotifyService notifyService;

    @Scheduled(cron = "${schedule.crossSiteNotify.cron:0/30 * * * * *}")
    public void run() {
        List<CrossSiteNotifyRecord> batch;
        try {
            batch = notifyService.fetchDuePending(50);
        } catch (Exception e) {
            logger.error("[CrossSiteNotifySchedule] 拉取失败", e);
            return;
        }
        if (batch.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        int ok = 0, fail = 0;
        for (CrossSiteNotifyRecord rec : batch) {
            try {
                notifyService.sendOne(rec);
                if (rec.getState() == CrossSiteNotifyRecord.STATE_SUCCESS) ok++;
                else if (rec.getState() == CrossSiteNotifyRecord.STATE_FAIL) fail++;
            } catch (Exception e) {
                logger.error("[CrossSiteNotifySchedule] 单条投递异常 id={}", rec.getId(), e);
            }
        }
        logger.info("[CrossSiteNotifySchedule] 处理 {} 条 成功={} 终态失败={} 耗时={}ms",
                batch.size(), ok, fail, System.currentTimeMillis() - t0);
    }
}
