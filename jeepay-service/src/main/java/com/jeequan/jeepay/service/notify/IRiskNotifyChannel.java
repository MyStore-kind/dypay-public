/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.notify;

import com.jeequan.jeepay.core.constants.RiskAlertType;

/**
 * 风险告警渠道抽象
 *
 * 设计要点：
 *  - 一个事件类型可同时触达多个渠道，由 RiskAlertNotifier 按订阅分发
 *  - 渠道实现要保证幂等：重试时不重复发送告警
 *  - 单次发送失败抛 RuntimeException，由 Notifier 统一重试 + 落库
 *
 * @author 反风控改造组
 */
public interface IRiskNotifyChannel {

    /** 渠道标识，例如 "telegram" / "email" */
    String channelName();

    /**
     * 判断此事件类型是否在本渠道下有可用目标（如未配置则跳过）
     */
    boolean isEnabled(RiskAlertType type);

    /**
     * 实际发送
     *
     * @param type    告警类型
     * @param title   标题
     * @param body    正文（已套用模板）
     * @return 实际触达的目标列表（用逗号拼接，便于落库）
     */
    String send(RiskAlertType type, String title, String body);
}
