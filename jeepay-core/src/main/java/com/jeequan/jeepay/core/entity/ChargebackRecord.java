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
 * 拒付记录（Chargeback）
 * 完整记录每笔拒付，包含证据材料用于通道方申诉
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "拒付记录")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_chargeback_record")
public class ChargebackRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<ChargebackRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 状态常量
    public static final String STATE_RECEIVED = "received";       // 收到拒付通知
    public static final String STATE_UNDER_REVIEW = "under_review"; // 准备证据中
    public static final String STATE_RESPONDED = "responded";      // 已提交证据
    public static final String STATE_WON = "won";                  // 申诉胜
    public static final String STATE_LOST = "lost";                // 申诉败
    public static final String STATE_EXPIRED = "expired";          // 超时未应

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String payOrderId;
    private String channelChargebackId;
    private String mchNo;
    private String accountId;
    private String ifCode;

    private Long chargebackAmount;
    private String chargebackCurrency;
    private String chargebackReasonCode;
    private String chargebackReasonDesc;
    private String chargebackType;

    private String state;
    private Date evidenceDueAt;
    private Date evidenceSubmittedAt;
    private Date resolvedAt;

    // 证据快照
    private String customerIp;
    private String customerEmail;
    private String customerName;
    private String shippingAddress;
    private String billingAddress;
    private String receiptUrl;
    private String serviceDocumentation;
    private String communicationLog;
    /** 证据文件列表 JSON */
    private String evidenceFiles;

    private String remark;
}
