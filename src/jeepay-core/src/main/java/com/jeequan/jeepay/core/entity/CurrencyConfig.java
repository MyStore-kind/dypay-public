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

/**
 * <p>
 * 支持币种配置表
 * 定义系统支持的币种及其属性（符号、小数位、限额）
 * 注意：日元等无小数位币种需特殊处理
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Schema(description = "支持币种配置表")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_currency_config")
public class CurrencyConfig extends BaseModel implements Serializable {

    // gw
    public static final LambdaQueryWrapper<CurrencyConfig> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 状态常量
    public static final byte STATE_DISABLE = 0;  // 停用
    public static final byte STATE_ENABLE = 1;   // 启用

    /**
     * 币种代码（ISO 4217 标准，如：USD、EUR、CNY）
     */
    @Schema(title = "currency", description = "币种代码")
    @TableId(value = "currency", type = IdType.INPUT)
    private String currency;

    /**
     * 币种名称（如：美元、欧元）
     */
    @Schema(title = "currencyName", description = "币种名称")
    private String currencyName;

    /**
     * 币种符号（如：$、€、¥）
     */
    @Schema(title = "currencySymbol", description = "币种符号")
    private String currencySymbol;

    /**
     * 小数位数
     * 注意事项：日元(JPY)、韩元(KRW)等为 0，多数币种为 2，比特币等为 8
     * 影响金额展示与转换计算
     */
    @Schema(title = "decimalPlaces", description = "小数位数")
    private Byte decimalPlaces;

    /**
     * 最小金额（单位：分）
     * 用于订单金额校验，防止异常金额
     */
    @Schema(title = "minAmount", description = "最小金额")
    private Long minAmount;

    /**
     * 最大金额（单位：分）
     */
    @Schema(title = "maxAmount", description = "最大金额")
    private Long maxAmount;

    /**
     * 状态 0-停用 1-启用
     */
    @Schema(title = "state", description = "状态")
    private Byte state;

    /**
     * 排序
     */
    @Schema(title = "sortNum", description = "排序")
    private Integer sortNum;
}
