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
import java.util.List;

/**
 * <p>
 * 代理商分润服务
 * 核心职责：根据订单计算多级代理商分润并落库
 * 注意：
 * - 仅在订单成功后调用，失败/退款订单不分润
 * - 分润金额按订单结算金额计算，避免汇率波动导致差异
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class AgentProfitService extends ServiceImpl<AgentProfitRecordMapper, AgentProfitRecord> {

    @Autowired
    private AgentInfoService agentInfoService;

    @Autowired
    private MchInfoService mchInfoService;

    /**
     * 计算并记录代理商分润（多级代理）
     * 为什么使用事务：保证多级分润记录的原子性，避免部分写入
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

        // 2. 获取代理商层级链
        List<AgentInfo> agentChain = agentInfoService.getAgentChain(mchInfo.getAgentNo());

        // 3. 逐级计算分润
        Long baseAmount = payOrder.getSettlementAmount() != null
                ? payOrder.getSettlementAmount()
                : payOrder.getAmount();
        String profitCurrency = StringUtils.isNotBlank(payOrder.getSettlementCurrency())
                ? payOrder.getSettlementCurrency()
                : payOrder.getCurrency();

        for (AgentInfo agent : agentChain) {
            // 跳过停用/冻结的代理商
            if (agent.getState() != AgentInfo.STATE_ENABLE) {
                continue;
            }

            // 计算分润金额
            Long profitAmount = calculateProfitAmount(baseAmount, agent.getProfitRate());
            if (profitAmount <= 0) {
                continue;
            }

            // 构建分润记录
            AgentProfitRecord record = new AgentProfitRecord()
                    .setAgentNo(agent.getAgentNo())
                    .setMchNo(payOrder.getMchNo())
                    .setPayOrderId(payOrder.getPayOrderId())
                    .setOrderAmount(payOrder.getAmount())
                    .setOrderCurrency(payOrder.getCurrency())
                    .setProfitAmount(profitAmount)
                    .setProfitCurrency(profitCurrency)
                    .setProfitRate(agent.getProfitRate())
                    .setState(AgentProfitRecord.STATE_PENDING);

            save(record);
        }
    }

    /**
     * 计算分润金额
     * 注意：使用 RoundingMode.DOWN 向下取整，平台不承担舍入损失
     *
     * @param orderAmount 订单金额（分）
     * @param profitRate  分润比例（百分比）
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
