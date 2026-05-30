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
 * 代理商分润结算单
 * 每个结算周期对达标的代理商生成一条；与 t_agent_profit_record 是 1:N
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Schema(description = "代理商分润结算单")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_agent_settle_record")
public class AgentSettleRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<AgentSettleRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    /** 状态：0-待打款 1-已打款 2-已冻结 3-已驳回 */
    public static final byte STATE_PENDING_PAYOUT = 0;
    public static final byte STATE_PAID = 1;
    public static final byte STATE_FROZEN = 2;
    public static final byte STATE_REJECTED = 3;

    @TableId(value = "settle_id", type = IdType.AUTO)
    private Long settleId;

    /** 结算单号 settle_{cycle}_{agentNo}_{yyyyMMdd}，对账方便 */
    private String settleNo;

    private String agentNo;

    /** T1/T7/T30 */
    private String settlementCycle;

    private Date periodStart;
    private Date periodEnd;

    private Integer recordCount;

    /** 单位：分 */
    private Long totalAmount;

    private String currency;

    private Byte state;

    private Date settleDate;
    private String remark;

    private Date createdAt;
    private Date updatedAt;
}
