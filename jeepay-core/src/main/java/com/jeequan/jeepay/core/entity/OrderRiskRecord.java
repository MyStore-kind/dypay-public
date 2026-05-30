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

/**
 * <p>
 * 订单风控记录
 * 每笔订单一条，记录风控决策过程与设备指纹
 * 用途：拒付申诉证据、风险溯源
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "订单风控记录")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_order_risk_record")
public class OrderRiskRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<OrderRiskRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 风控动作常量
    public static final String ACTION_PASS = "pass";
    public static final String ACTION_3DS = "3ds";
    public static final String ACTION_REJECT = "reject";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String payOrderId;
    private String mchNo;
    private String accountId;

    private Integer riskScore;
    private String riskAction;
    /** 触发的规则明细 JSON */
    private String riskFactors;

    // 设备指纹
    private String ip;
    private String ipCountry;
    private String ipRiskLevel;
    private String deviceFingerprint;
    private String userAgent;

    // 卡信息（脱敏）
    private String cardBin;
    private String cardLast4;
    private String cardCountry;
    private String cardType;
    private String cardBrand;

    // 买家信息
    private String buyerEmail;
    private String buyerPhone;
    private String buyerName;

    // 3DS
    private Byte threeDsTriggered;
    private String threeDsResult;
}
