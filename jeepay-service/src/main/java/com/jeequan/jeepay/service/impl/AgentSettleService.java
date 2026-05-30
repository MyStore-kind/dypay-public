/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.constants.RiskAlertType;
import com.jeequan.jeepay.core.entity.AgentInfo;
import com.jeequan.jeepay.core.entity.AgentProfitRecord;
import com.jeequan.jeepay.core.entity.AgentSettleRecord;
import com.jeequan.jeepay.service.mapper.AgentSettleRecordMapper;
import com.jeequan.jeepay.service.notify.RiskAlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理商分润结算服务（任务 #3）
 *
 * 职责：
 *  - 按结算周期聚合分润明细
 *  - 校验是否达到最低结算金额
 *  - 生成结算单 + 批量更新分润明细状态
 *  - 检测冻结代理商，触发告警
 *
 * 设计要点：
 *  - 整个结算操作在 @Transactional 内完成，保证"结算单写入 + 明细更新"原子
 *  - 不直接执行打款（state=0 待打款），由人工或独立打款服务推进
 *  - 不达标的金额自动滚入下一周期（state 维持 0-待结算）
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class AgentSettleService extends ServiceImpl<AgentSettleRecordMapper, AgentSettleRecord> {

    private static final Logger logger = LoggerFactory.getLogger(AgentSettleService.class);

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyyMMdd");

    @Autowired
    private AgentInfoService agentInfoService;

    @Autowired
    private AgentProfitService agentProfitService;

    @Autowired
    private RiskAlertNotifier riskAlertNotifier;

    /**
     * 执行某个结算周期的所有代理商结算
     *
     * @param cycle 结算周期：T1 / T7 / T30
     * @return 本次生成的结算单数
     */
    public int settleByCycle(String cycle) {
        // 1. 计算结算窗口
        Window window = computeWindow(cycle);
        if (window == null) {
            return 0;
        }

        // 2. 查询此周期下所有启用 + 冻结的代理商（停用代理商的待结算先跳过）
        List<AgentInfo> agents = agentInfoService.list(
                AgentInfo.gw().eq(AgentInfo::getSettlementCycle, cycle));
        if (agents.isEmpty()) {
            return 0;
        }

        int generated = 0;
        for (AgentInfo agent : agents) {
            try {
                if (settleOneAgent(agent, cycle, window)) {
                    generated++;
                }
            } catch (Exception e) {
                // 单个代理商失败不影响其他，错误落日志便于排查
                logger.error("[AgentSettle] 代理商结算失败 agentNo={} cycle={}",
                        agent.getAgentNo(), cycle, e);
            }
        }
        logger.info("[AgentSettle] 周期 {} 结算完成 代理商总数={} 生成结算单={}",
                cycle, agents.size(), generated);
        return generated;
    }

    /**
     * 单个代理商结算
     * 事务：保证结算单 INSERT 与明细 UPDATE 原子
     *
     * @return 是否生成结算单（不达标返回 false）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleOneAgent(AgentInfo agent, String cycle, Window window) {
        // 1. 查待结算的明细
        List<AgentProfitRecord> records = agentProfitService.list(
                Wrappers.<AgentProfitRecord>lambdaQuery()
                        .eq(AgentProfitRecord::getAgentNo, agent.getAgentNo())
                        .eq(AgentProfitRecord::getState, AgentProfitRecord.STATE_PENDING)
                        .ge(AgentProfitRecord::getCreatedAt, window.start)
                        .lt(AgentProfitRecord::getCreatedAt, window.endExclusive));
        if (records.isEmpty()) {
            return false;
        }

        // 2. 聚合（用 BigDecimal 避免大金额溢出 long 风险）
        BigDecimal totalBd = BigDecimal.ZERO;
        String currency = null;
        for (AgentProfitRecord r : records) {
            if (r.getProfitAmount() != null) {
                totalBd = totalBd.add(BigDecimal.valueOf(r.getProfitAmount()));
            }
            if (currency == null) currency = r.getProfitCurrency();
        }
        long totalAmount = totalBd.longValueExact();

        // 3. 冻结代理商：明细置 state=2，并触发告警
        if (agent.getState() != null && agent.getState() == AgentInfo.STATE_FROZEN) {
            freezeRecords(records);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("agentNo", agent.getAgentNo());
            ctx.put("agentName", agent.getAgentName());
            ctx.put("cycle", cycle);
            ctx.put("amount", totalAmount);
            ctx.put("recordCount", records.size());
            riskAlertNotifier.send(RiskAlertType.AGENT_FROZEN, ctx);
            logger.warn("[AgentSettle] 代理商已冻结，跳过结算 agentNo={} amount={}",
                    agent.getAgentNo(), totalAmount);
            return false;
        }

        // 4. 停用代理商：不冻结不结算，留到运营人工处理
        if (agent.getState() == null || agent.getState() != AgentInfo.STATE_ENABLE) {
            logger.info("[AgentSettle] 代理商非启用状态，跳过 agentNo={} state={}",
                    agent.getAgentNo(), agent.getState());
            return false;
        }

        // 5. 校验最低结算金额
        long minAmount = agent.getMinSettlementAmount() == null ? 0L : agent.getMinSettlementAmount();
        if (totalAmount < minAmount) {
            // 不达标 -> 维持 state=0，自动滚入下一周期
            logger.info("[AgentSettle] 未达最低结算金额，延后 agentNo={} amount={} min={}",
                    agent.getAgentNo(), totalAmount, minAmount);
            return false;
        }

        // 6. 生成结算单
        AgentSettleRecord settle = new AgentSettleRecord()
                .setSettleNo(buildSettleNo(cycle, agent.getAgentNo()))
                .setAgentNo(agent.getAgentNo())
                .setSettlementCycle(cycle)
                .setPeriodStart(window.start)
                .setPeriodEnd(window.endInclusive)
                .setRecordCount(records.size())
                .setTotalAmount(totalAmount)
                .setCurrency(currency == null ? "CNY" : currency)
                .setState(AgentSettleRecord.STATE_PENDING_PAYOUT);
        save(settle);

        // 7. 批量更新明细：state=1 已结算 + settle_id + settle_date
        List<Long> ids = new ArrayList<>(records.size());
        for (AgentProfitRecord r : records) ids.add(r.getRecordId());
        AgentProfitRecord update = new AgentProfitRecord()
                .setState(AgentProfitRecord.STATE_SETTLED)
                .setSettleDate(new Date())
                .setSettleId(settle.getSettleId());
        agentProfitService.update(update,
                Wrappers.<AgentProfitRecord>lambdaUpdate()
                        .in(AgentProfitRecord::getRecordId, ids));

        logger.info("[AgentSettle] 生成结算单 settleNo={} agentNo={} amount={} records={}",
                settle.getSettleNo(), agent.getAgentNo(), totalAmount, records.size());
        return true;
    }

    /** 将冻结代理商的所有待结算明细置为 STATE_FROZEN */
    private void freezeRecords(List<AgentProfitRecord> records) {
        if (records.isEmpty()) return;
        List<Long> ids = new ArrayList<>(records.size());
        for (AgentProfitRecord r : records) ids.add(r.getRecordId());
        AgentProfitRecord upd = new AgentProfitRecord().setState(AgentProfitRecord.STATE_FROZEN);
        agentProfitService.update(upd,
                Wrappers.<AgentProfitRecord>lambdaUpdate()
                        .in(AgentProfitRecord::getRecordId, ids));
    }

    /**
     * 计算结算窗口：
     *  - T1：昨天 00:00 ~ 今天 00:00
     *  - T7：过去 7 天（上周一 00:00 ~ 本周一 00:00）
     *  - T30：上个月 1 号 00:00 ~ 本月 1 号 00:00
     */
    public Window computeWindow(String cycle) {
        LocalDate today = LocalDate.now();
        LocalDate start;
        LocalDate endExclusive;
        switch (cycle) {
            case AgentInfo.CYCLE_T1:
                start = today.minusDays(1);
                endExclusive = today;
                break;
            case AgentInfo.CYCLE_T7:
                // 取上周一为 start，本周一为 endExclusive
                endExclusive = today.with(java.time.DayOfWeek.MONDAY);
                start = endExclusive.minusDays(7);
                break;
            case AgentInfo.CYCLE_T30:
                start = today.withDayOfMonth(1).minusMonths(1);
                endExclusive = today.withDayOfMonth(1);
                break;
            default:
                return null; // T0 不在本作业处理
        }
        return new Window(toDate(start), toDate(endExclusive), toDate(endExclusive.minusDays(1)));
    }

    private static Date toDate(LocalDate ld) {
        return Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 结算单号：settle_{cycle}_{agentNo}_{yyyyMMdd}，可对账人工追溯 */
    private String buildSettleNo(String cycle, String agentNo) {
        return "settle_" + cycle + "_" + agentNo + "_" + DATE_FMT.format(new Date());
    }

    /** 结算窗口 [start, endExclusive)；endInclusive 用于结算单记录 period_end */
    public static class Window {
        public final Date start;
        public final Date endExclusive;
        public final Date endInclusive;

        public Window(Date start, Date endExclusive, Date endInclusive) {
            this.start = start;
            this.endExclusive = endExclusive;
            this.endInclusive = endInclusive;
        }
    }
}
