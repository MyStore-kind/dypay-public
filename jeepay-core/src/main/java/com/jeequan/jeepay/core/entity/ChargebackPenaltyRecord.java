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
 * 拒付惩罚扣款流水
 *
 * 设计要点：
 * - 每次拒付扣款生成 1 条，UNIQUE(chargeback_record_id) 实现幂等
 * - 余额扣款前后快照都落库，方便财务对账与排查
 * - state = success / partial / failed / skipped
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Schema(description = "拒付惩罚扣款流水")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_chargeback_penalty_record")
public class ChargebackPenaltyRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<ChargebackPenaltyRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    public static final String STATE_SUCCESS = "success";
    public static final String STATE_PARTIAL = "partial";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_SKIPPED = "skipped";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long chargebackRecordId;
    private String payOrderId;
    private String mchNo;

    private Long principalAmount;
    private BigDecimal multiplierSnapshot;
    private Long expectedDeductAmount;
    private Long actualDeductAmount;

    private Long deductedFromAvailable;
    private Long deductedFromPending;

    private Long balanceAvailableBefore;
    private Long balanceAvailableAfter;
    private Long balancePendingBefore;
    private Long balancePendingAfter;

    private String state;
    private String reason;

    private Date createdAt;
}
