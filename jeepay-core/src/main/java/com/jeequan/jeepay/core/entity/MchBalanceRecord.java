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
 * 商户余额变动流水
 *
 * 设计要点：
 * - 流水不可修改，只追加
 * - 三栏（available / pending / frozen）的变动量分别记录，方向用正负号
 * - 每条流水包含变动后的余额快照，便于对账与排查
 * - type 字段语义化（topup / order_credit / settle / chargeback / refund / freeze ...）
 */
@Schema(description = "商户余额变动流水")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_mch_balance_record")
public class MchBalanceRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<MchBalanceRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // type 常量
    public static final String TYPE_TOPUP        = "topup";          // 运营充值
    public static final String TYPE_ORDER_CREDIT = "order_credit";   // 订单收款入账（pending +）
    public static final String TYPE_SETTLE       = "settle";         // T+N 结算（pending → available）
    public static final String TYPE_CHARGEBACK   = "chargeback";     // 拒付扣款
    public static final String TYPE_REFUND       = "refund";         // 退款
    public static final String TYPE_FREEZE       = "freeze";         // 冻结
    public static final String TYPE_UNFREEZE     = "unfreeze";       // 解冻
    public static final String TYPE_WITHDRAW     = "withdraw";       // 提现
    public static final String TYPE_ADJUST_PLUS  = "adjust_plus";    // 调账加
    public static final String TYPE_ADJUST_MINUS = "adjust_minus";   // 调账减

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String mchNo;
    private String type;

    /** 可用余额变动（正/负） */
    private Long amountAvailable;
    /** 未下发变动 */
    private Long amountPending;
    /** 冻结变动 */
    private Long amountFrozen;
    private String currency;

    private Long balanceAvailableAfter;
    private Long balancePendingAfter;
    private Long balanceFrozenAfter;

    private String payOrderId;
    private String refundOrderId;
    private Long chargebackId;
    private Long penaltyRecordId;

    private String operator;
    private String remark;
    private Date createdAt;
}
