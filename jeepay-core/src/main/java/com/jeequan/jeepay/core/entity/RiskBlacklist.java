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
 * 风险黑名单
 * 多类型统一存储：卡BIN/卡号/IP/邮箱/设备指纹/手机/国家
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "风险黑名单")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_risk_blacklist")
public class RiskBlacklist extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<RiskBlacklist> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 黑名单类型
    public static final String TYPE_CARD_BIN = "card_bin";
    public static final String TYPE_CARD_NUMBER = "card_number";
    public static final String TYPE_IP = "ip";
    public static final String TYPE_EMAIL = "email";
    public static final String TYPE_DEVICE = "device";
    public static final String TYPE_PHONE = "phone";
    public static final String TYPE_COUNTRY = "country";

    // 来源
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_CHARGEBACK = "chargeback";
    public static final String SOURCE_RISK_RULE = "risk_rule";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String listType;
    private String listValue;
    private String reason;
    private String source;

    private Integer hitCount;
    private Date lastHitAt;

    private Date expireAt;
    private Byte state;
    private String createdBy;
}
