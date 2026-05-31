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

/**
 * 拒付惩罚配置（每商户独立）
 *
 * 设计要点：
 * - 与 t_risk_threshold_config（全局阈值）解耦，这里只放"每商户的动作参数"
 * - mch_no = __GLOBAL__ 为兜底默认，配置查找时按 商户号 → __GLOBAL__ 顺序
 * - 默认倍数 3.00，扣款源 available → pending，不封号
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Schema(description = "拒付惩罚配置")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_chargeback_penalty_config")
public class ChargebackPenaltyConfig extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<ChargebackPenaltyConfig> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    /** 全局兜底配置的商户号 */
    public static final String GLOBAL_MCH_NO = "__GLOBAL__";

    /** 扣款来源 */
    public static final String SOURCE_AVAILABLE = "available";
    public static final String SOURCE_PENDING = "pending";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String mchNo;

    private Byte enabled;
    private BigDecimal penaltyMultiplier;
    private String deductSourcePriority;
    private Byte allowNegative;

    private Byte autoFreezeOnChargeback;
    private Long minAlertBalance;

    private String remark;
}
