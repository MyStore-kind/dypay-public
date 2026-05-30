/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.AgentInfo;
import com.jeequan.jeepay.core.entity.AgentProfitRecord;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.service.mapper.AgentProfitRecordMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 代理商分润服务
 * 核心职责：根据订单计算多级代理商分润并落库
 * 注意：
 * - 仅在订单成功后调用，失败/退款订单不分润
 * - 分润金额按订单结算金额计算，避免汇率波动导致差异
 * - 多级分润系数：
 *     直接代理（一级返佣对象）    -> profit_rate × 1.0
 *     上级代理（二级返佣对象）    -> profit_rate × {@link #LEVEL2_FACTOR}
 *     上上级代理（三级返佣对象）  -> profit_rate × {@link #LEVEL3_FACTOR}
 *   注意"代理商层级"与"链路位置"是两件事：
 *     直接代理可能本身是二级代理，但相对于此订单它就是 idx=0 的直接返佣对象。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class AgentProfitService extends ServiceImpl<AgentProfitRecordMapper, AgentProfitRecord> {

    /** 二级返佣系数（上级拿 30%） */
    private static final BigDecimal LEVEL2_FACTOR = new BigDecimal("0.30");
    /** 三级返佣系数（上上级拿 10%） */
    private static final BigDecimal LEVEL3_FACTOR = new BigDecimal("0.10");

    @Autowired
    private AgentInfoService agentInfoService;

    @Autowired
    private MchInfoService mchInfoService;

    /**
     * 计算并记录代理商分润（多级代理）
     * 为什么使用事务：保证 1~3 条分润记录的原子性，避免部分写入造成对账差异
     *
     * @param payOrder 支付订单（必须为已成功状态）
     */
    @Transactional(rollbackFor = Exception.class)
    public void calculateAndRecordProfit(PayOrder payOrder) {
        // 1. 获取商户信息
        MchInfo mchInfo = mchInfoService.getById(payOrder.getMchNo());
        if (mchInfo == null || StringUtils.isBlank(mchInfo.getAgentNo())) {
            // 无关联代理商，跳过分润
            return;
        }

        // 2. 获取代理商层级链（顺序：[直接代理, 父代理, 祖代理]，最多 3 个）
        List<AgentInfo> agentChain = agentInfoService.getAgentChain(mchInfo.getAgentNo());

        // 3. 确定分润基数（优先用结算金额，规避汇率波动）
        Long baseAmount = payOrder.getSettlementAmount() != null
                ? payOrder.getSettlementAmount()
                : payOrder.getAmount();
        String profitCurrency = StringUtils.isNotBlank(payOrder.getSettlementCurrency())
                ? payOrder.getSettlementCurrency()
                : payOrder.getCurrency();

        // 4. 批量收集，事务内一次性 saveBatch，减少 SQL 往返
        List<AgentProfitRecord> toInsert = new ArrayList<>(3);
        for (int idx = 0; idx < agentChain.size() && idx < 3; idx++) {
            AgentInfo agent = agentChain.get(idx);
            // 跳过停用/冻结的代理商
            if (agent.getState() == null || agent.getState() != AgentInfo.STATE_ENABLE) {
                continue;
            }

            BigDecimal factor = factorOf(idx);
            // 当前层级的实际分润比例 = 代理商配置比例 × 系数
            BigDecimal effectiveRate = agent.getProfitRate() == null
                    ? BigDecimal.ZERO
                    : agent.getProfitRate().multiply(factor);
            Long profitAmount = calculateProfitAmount(baseAmount, effectiveRate);
            if (profitAmount <= 0) {
                continue;
            }

            toInsert.add(new AgentProfitRecord()
                    .setAgentNo(agent.getAgentNo())
                    .setMchNo(payOrder.getMchNo())
                    .setPayOrderId(payOrder.getPayOrderId())
                    .setOrderAmount(payOrder.getAmount())
                    .setOrderCurrency(payOrder.getCurrency())
                    .setProfitAmount(profitAmount)
                    .setProfitCurrency(profitCurrency)
                    .setProfitRate(effectiveRate)
                    .setState(AgentProfitRecord.STATE_PENDING));
        }
        if (!toInsert.isEmpty()) {
            // MyBatis-Plus batch insert，事务保证原子性
            saveBatch(toInsert);
        }
    }

    /** 按链路位置返回分润系数 */
    private BigDecimal factorOf(int idx) {
        switch (idx) {
            case 0:  return BigDecimal.ONE;
            case 1:  return LEVEL2_FACTOR;
            default: return LEVEL3_FACTOR;
        }
    }

    /**
     * 计算分润金额
     * 注意：使用 RoundingMode.DOWN 向下取整，平台不承担舍入损失
     *
     * @param orderAmount 订单金额（分）
     * @param profitRate  分润比例（百分比，已乘过层级系数）
     * @return 分润金额（分）
     */
    private Long calculateProfitAmount(Long orderAmount, BigDecimal profitRate) {
        if (orderAmount == null || profitRate == null) {
            return 0L;
        }
        return new BigDecimal(orderAmount)
                .multiply(profitRate)
                .divide(new BigDecimal(100), 0, RoundingMode.DOWN)
                .longValue();
    }
}
