/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.PayOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 支付成功事件监听器
 *
 * 设计目的（无侵入扩展）：
 * - 不修改 JeePay 的 PayOrderProcessService.confirmSuccess()
 * - 通过 Spring 事件机制接收 PaySuccessEvent
 * - 触发：累加通道账号额度、计算代理商分润
 *
 * 触发方式：
 *   在 jeepay-payment 的 PayOrderProcessService.confirmSuccess() 末尾插入：
 *       applicationEventPublisher.publishEvent(new PaySuccessEvent(payOrder));
 *
 * 异步执行：避免阻塞支付主流程
 * 异常隔离：每个 listener 失败不影响其他 listener
 *
 * @author 反风控改造组
 */
@Component
public class PaySuccessEventListener {

    private static final Logger logger = LoggerFactory.getLogger(PaySuccessEventListener.class);

    @Autowired
    private ChannelAccountRouteHook channelAccountRouteHook;

    @Autowired
    private AgentProfitService agentProfitService;

    /**
     * 累加通道账号已使用额度（同步执行，金额准确性优先）
     */
    @EventListener
    public void handleChannelUsage(PaySuccessEvent event) {
        try {
            channelAccountRouteHook.onPaySuccess(event.getPayOrder());
        } catch (Exception e) {
            logger.error("[PaySuccessEvent] 累加通道额度失败 payOrderId={}",
                    event.getPayOrder().getPayOrderId(), e);
        }
    }

    /**
     * 触发代理商分润（异步执行，分润延后不影响支付流程）
     */
    @Async
    @EventListener
    public void handleAgentProfit(PaySuccessEvent event) {
        try {
            agentProfitService.calculateAndRecordProfit(event.getPayOrder());
        } catch (Exception e) {
            logger.error("[PaySuccessEvent] 代理商分润计算失败 payOrderId={}",
                    event.getPayOrder().getPayOrderId(), e);
        }
    }

    /**
     * 支付成功事件
     * 继承 ApplicationEvent 以便 Spring 事件机制正确分发
     */
    public static class PaySuccessEvent extends ApplicationEvent {
        private final PayOrder payOrder;

        public PaySuccessEvent(PayOrder payOrder) {
            super(payOrder == null ? new Object() : payOrder);
            this.payOrder = payOrder;
        }

        public PayOrder getPayOrder() { return payOrder; }
    }
}
