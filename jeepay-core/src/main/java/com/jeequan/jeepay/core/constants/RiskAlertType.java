/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.core.constants;

/**
 * 风险告警事件类型
 *
 * 设计要点：
 * - 枚举名同时作为 t_risk_threshold_config 中目标配置的 key 后缀（小写）
 *   例：CHARGEBACK_YELLOW_LINE -> notify.targets.chargeback_yellow_line
 * - 同时作为 t_sys_config 中模板的 key 后缀（小写）
 *   例：CHARGEBACK_YELLOW_LINE -> risk_alert_template_chargeback_yellow_line
 *
 * 新增告警类型时：补一个枚举值即可，模板与目标在运营后台配置，零代码改动。
 *
 * @author 反风控改造组
 */
public enum RiskAlertType {

    /** 拒付率黄线（接近 0.7%） */
    CHARGEBACK_YELLOW_LINE("拒付率黄线告警"),

    /** 拒付率红线（接近 0.9%，Visa 警戒线） */
    CHARGEBACK_RED_LINE("拒付率红线告警"),

    /** 商户被暂停 */
    MERCHANT_SUSPENDED("商户已暂停"),

    /** 通道账号被自动限流 */
    CHANNEL_ACCOUNT_THROTTLED("通道账号限流"),

    /** 同卡号高频使用 */
    HIGH_FREQUENCY_CARD("高频用卡告警"),

    /** 日交易突增 */
    DAILY_VOLUME_SPIKE("日交易突增"),

    /** 代理商被冻结（分润结算时检测到） */
    AGENT_FROZEN("代理商冻结告警");

    private final String displayName;

    RiskAlertType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 配置 key 后缀（小写枚举名） */
    public String configSuffix() {
        return name().toLowerCase();
    }
}
