/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jeequan.jeepay.core.model.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p>
 * 通道账号池
 * 每个支付通道可挂多个账号，由路由器根据健康度/额度/优先级智能选择
 * 设计目标：稀释流量，避免单账号触发通道方风控
 * </p>
 *
 * @author 反风控改造组
 * @since 2026-05-30
 */
@Schema(description = "通道账号池")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_channel_account")
public class ChannelAccount extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<ChannelAccount> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 健康状态常量
    public static final byte HEALTH_ERROR = 0;     // 异常
    public static final byte HEALTH_OK = 1;        // 健康
    public static final byte HEALTH_WARNING = 2;   // 警告
    public static final byte HEALTH_LIMITED = 3;   // 限流

    // 状态常量
    public static final byte STATE_DISABLE = 0;
    public static final byte STATE_ENABLE = 1;
    public static final byte STATE_FROZEN = 2;

    // 风险等级
    public static final String TIER_LOW = "low";
    public static final String TIER_MID = "mid";
    public static final String TIER_HIGH = "high";

    @TableId(value = "account_id", type = IdType.INPUT)
    private String accountId;

    private String ifCode;
    private String accountName;
    /** 通道密钥等参数 JSON */
    private String configParams;

    // 额度（运营配置）
    private Long dailyLimitAmount;
    private Long monthlyLimitAmount;
    private Long singleLimitAmount;

    // 实时累计（系统更新）
    private Long currentDailyAmount;
    private Long currentMonthlyAmount;
    private Date lastResetDate;

    // 风险指标快照（调度任务更新）
    private BigDecimal chargebackRate;
    private BigDecimal disputeRate;
    private BigDecimal refundRate;
    private BigDecimal successRate;
    private Integer totalTransactions30d;
    private Date lastHealthCheckAt;

    // 业务隔离
    private String riskTier;
    private String mccWhitelist;
    private String mccBlacklist;
    private String countryWhitelist;
    private String countryBlacklist;
    private String currencyWhitelist;

    // 路由控制
    private Integer priority;
    private Integer weight;

    // 状态
    private Byte healthStatus;
    private Byte state;
    private String remark;
}
