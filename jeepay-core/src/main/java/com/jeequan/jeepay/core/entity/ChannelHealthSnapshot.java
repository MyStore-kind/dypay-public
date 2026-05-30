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
 * 通道健康度快照（时序数据）
 * 每小时一条，用于绘制趋势图与历史回溯
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "通道健康度快照")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_channel_health_snapshot")
public class ChannelHealthSnapshot extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<ChannelHealthSnapshot> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 统计窗口常量
    public static final String WINDOW_1H = "1H";
    public static final String WINDOW_24H = "24H";
    public static final String WINDOW_7D = "7D";
    public static final String WINDOW_30D = "30D";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String accountId;
    private Date snapshotTime;
    private String windowType;

    // 计数指标
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private Integer chargebackCount;
    private Integer disputeCount;
    private Integer refundCount;
    private Integer threeDsCount;

    // 比率指标
    private BigDecimal successRate;
    private BigDecimal chargebackRate;
    private BigDecimal disputeRate;
    private BigDecimal refundRate;
    private BigDecimal threeDsRate;

    // 金额指标
    private Long totalAmount;
}
