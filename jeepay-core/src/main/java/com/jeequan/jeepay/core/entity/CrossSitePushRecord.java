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
 * A/B 站联动 - 跨站推送流水
 * UNIQUE(client_id, order_id) 实现幂等
 *
 * @author 反风控改造组
 */
@Schema(description = "跨站推送流水")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_cross_site_push_record")
public class CrossSitePushRecord extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<CrossSitePushRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    public static final String STATE_RECEIVED = "received";
    public static final String STATE_VERIFIED = "verified";
    public static final String STATE_AWAITING_PAY = "awaiting_pay";
    public static final String STATE_PAYING = "paying";
    public static final String STATE_PAID = "paid";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_REJECTED = "rejected";
    public static final String STATE_EXPIRED = "expired";

    /** 风控决策 */
    public static final String DECISION_PASS = "pass";
    public static final String DECISION_3DS = "3ds";
    public static final String DECISION_REJECT = "reject";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String clientId;
    private String orderId;
    private Long amount;
    private String currency;

    private String ip;
    private String deviceFingerprint;
    /** A 站收银台前端采集的浏览器指纹 JSON */
    private String browserFingerprint;
    private Date collectedAt;
    /** 风控评分 0-100 */
    private Integer riskScoreSnapshot;
    /** 风控决策 pass/3ds/reject */
    private String riskDecision;
    private String userAgent;

    private String nonce;
    private Long ts;
    private String sign;
    private String rawPayload;

    private String state;
    private String payOrderId;
    /** 收银台 token（短随机串，URL 安全） */
    private String payToken;
    private String returnUrl;
    private String notifyUrl;
    private String subject;
    private String customerEmail;
    private Date expireAt;
    private String rejectReason;

    // ===== 通道字段（cross_site_channel_patch.sql 增加） =====
    /** 实际走的通道 stripe / paypal */
    private String channelProvider;
    /** Stripe PaymentIntent ID 或 PayPal Order ID */
    private String channelIntentId;
    /** Stripe client_secret（前端 Elements 需要） */
    private String channelClientSecret;
    private Date paidAt;
    private String failedReason;

    private Date createdAt;
    private Date updatedAt;
}
