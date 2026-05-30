/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.PayOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通道账号路由钩子服务（无侵入扩展）
 *
 * 调用时机：在 JeePay 的 AbstractPayOrderController 中
 *   `payOrderService.save(payOrder)` 之前调用 assignAccount(payOrder)
 *
 * 职责：
 * - 根据订单 ifCode + 商户风险等级 + 币种 + 金额 选择最优账号
 * - 将选中的 accountId 回写到 PayOrder
 *
 * @author 反风控改造组
 */
@Service
public class ChannelAccountRouteHook {

    private static final Logger logger = LoggerFactory.getLogger(ChannelAccountRouteHook.class);

    @Autowired
    private ChannelRouterService channelRouterService;

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private MchInfoService mchInfoService;

    /**
     * 给订单分配通道账号
     *
     * @param payOrder 订单（必须已设置 ifCode、currency、amount、mchNo）
     * @return 选中的账号 ID（失败返回 null，业务方决定如何处理）
     */
    public String assignAccount(PayOrder payOrder) {
        if (payOrder == null) return null;
        String ifCode = payOrder.getIfCode();
        if (ifCode == null) return null;

        // 商户属性
        MchInfo mch = mchInfoService.getById(payOrder.getMchNo());
        String mccCode = mch == null ? null : mch.getMccCode();
        String tier = mch == null || mch.getRiskTier() == null ? "mid" : mch.getRiskTier();

        // 币种（国家信息暂未在 PayOrder 收集）
        String currency = payOrder.getCurrency();
        String country = null;

        try {
            ChannelAccount account = channelRouterService.route(
                    ifCode, mccCode, country, currency, payOrder.getAmount(), tier);
            payOrder.setAccountId(account.getAccountId());
            return account.getAccountId();
        } catch (Exception e) {
            // 路由失败不阻塞业务，由 JeePay 默认通道配置兜底
            logger.warn("[ChannelRouteHook] 路由失败 mchNo={} ifCode={}: {}",
                    payOrder.getMchNo(), ifCode, e.getMessage());
            return null;
        }
    }

    /**
     * 支付成功后累加账号已使用额度
     * 在 PayOrderProcessService.confirmSuccess 之后调用
     */
    public void onPaySuccess(PayOrder payOrder) {
        if (payOrder == null || payOrder.getAccountId() == null) return;
        try {
            channelAccountService.incrementUsage(payOrder.getAccountId(), payOrder.getAmount());
        } catch (Exception e) {
            logger.warn("[ChannelRouteHook] 累加额度失败 accountId={}", payOrder.getAccountId(), e);
        }
    }
}
