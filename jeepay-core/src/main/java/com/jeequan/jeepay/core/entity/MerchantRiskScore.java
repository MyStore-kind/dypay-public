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
 * 商户风险评分历史
 * 每日一条快照，便于查看商户风险趋势
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "商户风险评分历史")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_merchant_risk_score")
public class MerchantRiskScore extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<MerchantRiskScore> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 风险等级
    public static final String TIER_LOW = "low";
    public static final String TIER_MID = "mid";
    public static final String TIER_HIGH = "high";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String mchNo;
    private Date scoreDate;

    private Integer riskScore;
    private String riskTier;

    private BigDecimal chargebackRate;
    private BigDecimal disputeRate;
    private BigDecimal refundRate;
    private BigDecimal successRate;
    private BigDecimal highRiskCardRate;
    private Integer totalOrders30d;
    private Long totalAmount30d;

    private String evaluationWindow;
    /** 评分明细 JSON */
    private String scoreDetail;
}
