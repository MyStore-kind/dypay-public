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
import java.util.Date;

/**
 * <p>
 * 风险告警投递日志
 * 为什么单独建表：
 *  - 告警重试 3 次仍失败时落库，避免静默丢失
 *  - 成功也写一条，便于运营查"我有没有收到通知"
 * </p>
 *
 * @author 反风控改造组
 * @since 2026-05-30
 */
@Schema(description = "风险告警日志")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_risk_alert_log")
public class RiskAlertLog extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<RiskAlertLog> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    /** 0-投递失败 1-投递成功 */
    public static final byte STATE_FAIL = 0;
    public static final byte STATE_SUCCESS = 1;

    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    /** 告警类型，对应 RiskAlertType 枚举名 */
    private String alertType;

    /** 渠道：telegram / email */
    private String channel;

    /** 目标：chat_id 或邮箱 */
    private String target;

    private String title;
    private String content;

    private Byte state;
    private Integer retryCount;
    private String errorMsg;

    private Date createdAt;
}
