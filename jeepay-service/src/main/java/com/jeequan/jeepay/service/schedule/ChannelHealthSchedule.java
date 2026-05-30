/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.ChannelHealthSnapshot;
import com.jeequan.jeepay.service.impl.ChannelAccountService;
import com.jeequan.jeepay.service.impl.ChannelHealthService;
import com.jeequan.jeepay.service.impl.CircuitBreakerEngine;
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
 * 通道健康度调度任务
 *
 * 核心职责：
 *   1. 每小时对所有启用账号聚合 1H / 24H / 7D / 30D 四个窗口的健康指标
 *   2. 时序快照入 t_channel_health_snapshot
 *   3. 回写 t_channel_account 的实时快照字段（chargeback_rate、success_rate 等）
 *   4. 根据黄线/红线驱动 health_status：1 健康 / 2 警告 / 3 限流
 *   5. 同步触发 CircuitBreakerEngine（红线动作 + 通知）
 *
 * health_status 联动规则（与路由器约定）：
 *   - 默认 = 1 健康
 *   - 命中黄线（拒付率>黄线 或 成功率<黄线） → 2 警告（路由仍可选，但排在健康账号之后）
 *   - 命中红线（拒付率>红线 或 成功率<红线） → 3 限流（路由器 listAvailable 自动排除）
 *
 * 为什么独立一个调度类：
 *   - 通道健康度是反风控核心闭环，独立类便于运维隔离观察与告警
 *   - 与商户/拒付调度解耦，互不影响
 *
 * 阈值来源：RiskThresholdConfigService（运营可热修改），硬编码默认值兜底
 *
 * @author 反风控改造组
 */
@Component
public class ChannelHealthSchedule {

    private static final Logger logger = LoggerFactory.getLogger(ChannelHealthSchedule.class);

    // 阈值 Key（与 risk_control_patch.sql 初始化保持一致）
    private static final String KEY_CB_WARN  = "channel.chargeback_rate.warning";
    private static final String KEY_CB_CRIT  = "channel.chargeback_rate.critical";
    private static final String KEY_SR_WARN  = "channel.success_rate.warning";
    private static final String KEY_SR_CRIT  = "channel.success_rate.critical";

    // 兜底默认值（业务方案文档约定值，配置缺失时使用）
    private static final BigDecimal DEFAULT_CB_WARN = new BigDecimal("0.7");
    private static final BigDecimal DEFAULT_CB_CRIT = new BigDecimal("0.9");
    private static final BigDecimal DEFAULT_SR_WARN = new BigDecimal("90.0");
    private static final BigDecimal DEFAULT_SR_CRIT = new BigDecimal("85.0");

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private ChannelHealthService channelHealthService;

    @Autowired
    private CircuitBreakerEngine circuitBreakerEngine;

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

    /**
     * 每小时整点（0 分 0 秒）执行：
     *   - 4 个窗口快照 → t_channel_health_snapshot
     *   - 30D 数据回写 ChannelAccount（路由器读取这份数据做决策）
     *   - 黄线/红线判定 → 更新 health_status
     *   - 调用 CircuitBreakerEngine 触发通知/限流/暂停动作
     *
     * 为什么整点：与 1H 窗口对齐，方便后续看板查询自然小时聚合数据
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runHourly() {
        long t0 = System.currentTimeMillis();
        List<ChannelAccount> accounts = channelAccountService.list(
                ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));

        if (accounts == null || accounts.isEmpty()) {
            logger.info("[ChannelHealthSchedule] 无启用账号，跳过");
            return;
        }

        // 读取阈值（一次性，本轮内共享）
        BigDecimal cbWarn = thresholdConfig.getNumber(KEY_CB_WARN, DEFAULT_CB_WARN);
        BigDecimal cbCrit = thresholdConfig.getNumber(KEY_CB_CRIT, DEFAULT_CB_CRIT);
        BigDecimal srWarn = thresholdConfig.getNumber(KEY_SR_WARN, DEFAULT_SR_WARN);
        BigDecimal srCrit = thresholdConfig.getNumber(KEY_SR_CRIT, DEFAULT_SR_CRIT);

        int success = 0, fail = 0;
        for (ChannelAccount a : accounts) {
            try {
                processSingle(a, cbWarn, cbCrit, srWarn, srCrit);
                success++;
            } catch (Exception e) {
                fail++;
                logger.error("[ChannelHealthSchedule] 账号健康度处理失败 accountId={}", a.getAccountId(), e);
            }
        }
        logger.info("[ChannelHealthSchedule] 完成 总数={} 成功={} 失败={} 耗时={}ms",
                accounts.size(), success, fail, System.currentTimeMillis() - t0);
    }

    /**
     * 处理单个账号：四窗口快照 + 健康状态联动
     * 为什么按窗口逐次调用：复用 ChannelHealthService.generateAndSave，
     * 保持其内部"30D 同步回写 ChannelAccount"的副作用一次生效
     */
    private void processSingle(ChannelAccount a, BigDecimal cbWarn, BigDecimal cbCrit,
                               BigDecimal srWarn, BigDecimal srCrit) {
        // 1. 短窗口先算，便于观察实时趋势（1H、24H）
        channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_1H);
        channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_24H);
        channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_7D);
        // 2. 30D 是判定窗口（与 Visa/Mastercard 红线标准一致），最后算并回写账号快照字段
        ChannelHealthSnapshot snap30d =
                channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_30D);

        // 3. 重新查最新账号（30D 快照已回写 chargeback_rate/success_rate 等）
        ChannelAccount latest = channelAccountService.getById(a.getAccountId());
        if (latest == null) return;

        // 4. 计算 health_status（红线优先于黄线，黄线优先于健康）
        byte newStatus = decideHealthStatus(latest, cbWarn, cbCrit, srWarn, srCrit);
        byte oldStatus = latest.getHealthStatus() == null ? ChannelAccount.HEALTH_OK : latest.getHealthStatus();

        if (newStatus != oldStatus) {
            channelAccountService.updateHealthStatus(latest.getAccountId(), newStatus);
            logger.warn("[ChannelHealthSchedule] 健康状态切换 accountId={} {} → {} cbRate={} successRate={}",
                    latest.getAccountId(), oldStatus, newStatus,
                    latest.getChargebackRate(), latest.getSuccessRate());
            latest.setHealthStatus(newStatus);
        }

        // 5. last_health_check_at 已由 30D 回写时更新，无需重复

        // 6. 通知 / 限流 / 暂停 等动作仍走 CircuitBreakerEngine（保留与单元解耦）
        // 为什么不在此处直接处理通知：避免与运营 action_enabled 开关逻辑重复实现
        try {
            circuitBreakerEngine.checkChannelAccount(latest);
        } catch (Exception e) {
            logger.warn("[ChannelHealthSchedule] 熔断引擎触发失败 accountId={}", latest.getAccountId(), e);
        }
    }

    /**
     * 健康状态决策：
     *   红线（拒付率 ≥ critical 或 成功率 < critical） → 3 限流
     *   黄线（拒付率 ≥ warning  或 成功率 < warning ） → 2 警告
     *   否则                                        → 1 健康
     *
     * 注意：成功率方向相反（越低越差），与拒付率（越高越差）分开处理
     */
    private byte decideHealthStatus(ChannelAccount a,
                                    BigDecimal cbWarn, BigDecimal cbCrit,
                                    BigDecimal srWarn, BigDecimal srCrit) {
        BigDecimal cb = a.getChargebackRate();
        BigDecimal sr = a.getSuccessRate();
        Integer txn = a.getTotalTransactions30d();

        // 样本量过低时不参与降级，避免新账号被冤枉
        // 为什么阈值用 50：通道方风控通常也要求 30~100 笔后才看比率
        if (txn == null || txn < 50) {
            return ChannelAccount.HEALTH_OK;
        }

        boolean cbCritHit = cb != null && cbCrit != null && cb.compareTo(cbCrit) >= 0;
        boolean srCritHit = sr != null && srCrit != null && sr.compareTo(srCrit) < 0;
        if (cbCritHit || srCritHit) {
            return ChannelAccount.HEALTH_LIMITED;
        }

        boolean cbWarnHit = cb != null && cbWarn != null && cb.compareTo(cbWarn) >= 0;
        boolean srWarnHit = sr != null && srWarn != null && sr.compareTo(srWarn) < 0;
        if (cbWarnHit || srWarnHit) {
            return ChannelAccount.HEALTH_WARNING;
        }

        return ChannelAccount.HEALTH_OK;
    }

    /**
     * 兜底任务：每 10 分钟对启用账号刷新 30D 快照，
     * 避免运营临时新增账号 / 调整阈值后必须等下个整点才生效
     *
     * 为什么不直接缩短整点任务频率：1H/24H/7D 高频聚合成本较高，
     * 10 分钟级只跑 30D（最少 SQL），既保证响应又控制 DB 压力
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void refreshMetrics() {
        try {
            List<ChannelAccount> accounts = channelAccountService.list(
                    ChannelAccount.gw().eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE));
            BigDecimal cbWarn = thresholdConfig.getNumber(KEY_CB_WARN, DEFAULT_CB_WARN);
            BigDecimal cbCrit = thresholdConfig.getNumber(KEY_CB_CRIT, DEFAULT_CB_CRIT);
            BigDecimal srWarn = thresholdConfig.getNumber(KEY_SR_WARN, DEFAULT_SR_WARN);
            BigDecimal srCrit = thresholdConfig.getNumber(KEY_SR_CRIT, DEFAULT_SR_CRIT);

            for (ChannelAccount a : accounts) {
                try {
                    channelHealthService.generateAndSave(a.getAccountId(), ChannelHealthSnapshot.WINDOW_30D);
                    ChannelAccount latest = channelAccountService.getById(a.getAccountId());
                    if (latest == null) continue;
                    byte ns = decideHealthStatus(latest, cbWarn, cbCrit, srWarn, srCrit);
                    if (latest.getHealthStatus() == null || ns != latest.getHealthStatus()) {
                        channelAccountService.updateHealthStatus(latest.getAccountId(), ns);
                    }
                } catch (Exception e) {
                    logger.error("[ChannelHealthSchedule.refreshMetrics] 失败 accountId={}", a.getAccountId(), e);
                }
            }
        } catch (Exception e) {
            logger.error("[ChannelHealthSchedule.refreshMetrics] 任务异常", e);
        }
    }

    /**
     * @return 调度运行时间戳（便于运维探针检查任务存活）
     */
    public Date heartbeat() {
        return new Date();
    }
}
