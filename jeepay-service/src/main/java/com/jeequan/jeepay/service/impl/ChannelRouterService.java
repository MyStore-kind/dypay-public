/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 通道智能路由服务
 *
 * 核心职责：从可用账号池中选择最优账号
 *
 * 路由策略（按优先级）：
 * 1. 过滤：账号资格（业务隔离、限额、白黑名单）
 * 2. 排序：健康度优先（成功率高 + 拒付率低）
 * 3. 排序：优先级 priority
 * 4. 选择：Top1 / 加权随机（运营可配置）
 *
 * 注意：本服务不修改账号状态，仅返回选择结果
 *
 * @author 反风控改造组
 */
@Service
public class ChannelRouterService {

    @Autowired
    private ChannelAccountService channelAccountService;

    private final Random random = new Random();

    /**
     * 选择最优账号
     *
     * @param ifCode 通道编码（stripe/paypal）
     * @param mccCode 商户 MCC
     * @param country 消费者国家
     * @param currency 币种
     * @param amount 金额
     * @param merchantTier 商户风险等级
     * @return 选中的账号
     * @throws BizException 无可用账号
     */
    public ChannelAccount route(String ifCode, String mccCode, String country,
                                String currency, Long amount, String merchantTier) {
        // 1. 查询所有候选
        List<ChannelAccount> candidates = channelAccountService.listAvailable(ifCode);
        if (candidates.isEmpty()) {
            throw new BizException(String.format("通道 %s 暂无可用账号", ifCode));
        }

        // 2. 过滤资格
        List<ChannelAccount> eligible = candidates.stream()
                .filter(a -> channelAccountService.isEligible(a, mccCode, country, currency, amount, merchantTier))
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new BizException(String.format("通道 %s 无满足条件的账号（MCC/国家/币种/额度）", ifCode));
        }

        // 3. 排序：健康度（成功率倒序 → 拒付率正序）→ 优先级倒序
        eligible.sort(Comparator
                .comparing(ChannelAccount::getHealthStatus, Comparator.reverseOrder())  // 健康在前
                .thenComparing(ChannelAccount::getSuccessRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ChannelAccount::getChargebackRate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChannelAccount::getPriority, Comparator.reverseOrder()));

        // 4. 加权随机选择（在 Top3 内）
        // 设计理由：避免所有流量集中到单一账号，分散风险
        return weightedPick(eligible.subList(0, Math.min(3, eligible.size())));
    }

    /**
     * 加权随机选择
     */
    private ChannelAccount weightedPick(List<ChannelAccount> list) {
        if (list.size() == 1) return list.get(0);
        int totalWeight = list.stream().mapToInt(a -> a.getWeight() == null ? 100 : a.getWeight()).sum();
        if (totalWeight <= 0) return list.get(0);

        int pick = random.nextInt(totalWeight);
        int cursor = 0;
        for (ChannelAccount a : list) {
            cursor += (a.getWeight() == null ? 100 : a.getWeight());
            if (pick < cursor) return a;
        }
        return list.get(0);
    }
}
