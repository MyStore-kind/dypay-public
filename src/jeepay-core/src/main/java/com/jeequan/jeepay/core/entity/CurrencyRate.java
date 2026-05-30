/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 * <p>
 * 基于 JeePay 扩展，遵循 GNU LESSER GENERAL PUBLIC LICENSE 3.0.
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
 * 汇率表
 * 存储多种汇率源的实时与历史汇率，支持订单使用历史汇率结算
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Schema(description = "汇率表")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_currency_rate")
public class CurrencyRate extends BaseModel implements Serializable {

    // gw
    public static final LambdaQueryWrapper<CurrencyRate> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 汇率来源常量
    public static final String SOURCE_MANUAL = "manual";   // 手动维护
    public static final String SOURCE_API = "api";         // 通用API
    public static final String SOURCE_FIXER = "fixer";     // Fixer.io
    public static final String SOURCE_STRIPE = "stripe";   // Stripe 平台汇率
    public static final String SOURCE_PAYPAL = "paypal";   // PayPal 平台汇率

    /**
     * ID
     */
    @Schema(title = "id", description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 基准币种（如：USD）
     */
    @Schema(title = "baseCurrency", description = "基准币种")
    private String baseCurrency;

    /**
     * 目标币种（如：CNY）
     */
    @Schema(title = "targetCurrency", description = "目标币种")
    private String targetCurrency;

    /**
     * 汇率（1 基准币种 = rate 目标币种）
     * 注意事项：保留 8 位小数，金融场景对精度要求高
     */
    @Schema(title = "rate", description = "汇率")
    private BigDecimal rate;

    /**
     * 汇率来源
     * 为什么记录来源：审计与对账，不同源的汇率可能不一致
     */
    @Schema(title = "rateSource", description = "汇率来源")
    private String rateSource;

    /**
     * 生效时间
     */
    @Schema(title = "effectiveTime", description = "生效时间")
    private Date effectiveTime;

    /**
     * 失效时间（NULL 表示长期有效）
     */
    @Schema(title = "expireTime", description = "失效时间")
    private Date expireTime;
}
