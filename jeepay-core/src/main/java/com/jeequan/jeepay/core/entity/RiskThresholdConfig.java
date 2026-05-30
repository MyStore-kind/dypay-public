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
 * 风险阈值配置表
 * 系统所有阈值与触发动作的统一配置入口
 * 设计核心：运营在后台可调整任意阈值与动作
 *
 * 配置 key 规范：
 *   {group}.{indicator}.{level}
 *   例：channel.chargeback_rate.warning
 *
 * 动作类型：
 *   notify - 仅告警通知
 *   limit  - 限流（降低流量）
 *   suspend- 暂停账号/商户
 *   switch - 切换通道
 *   reject - 拒绝交易
 *   3ds    - 强制 3DS
 *   none   - 仅记录不动作
 * </p>
 *
 * @author 反风控改造组
 */
@Schema(description = "风险阈值配置")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_risk_threshold_config")
public class RiskThresholdConfig extends BaseModel implements Serializable {

    public static final LambdaQueryWrapper<RiskThresholdConfig> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 值类型
    public static final String VALUE_TYPE_STRING = "string";
    public static final String VALUE_TYPE_NUMBER = "number";
    public static final String VALUE_TYPE_BOOLEAN = "boolean";
    public static final String VALUE_TYPE_JSON = "json";

    // 动作类型
    public static final String ACTION_NOTIFY = "notify";
    public static final String ACTION_LIMIT = "limit";
    public static final String ACTION_SUSPEND = "suspend";
    public static final String ACTION_SWITCH = "switch";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_3DS = "3ds";
    public static final String ACTION_NONE = "none";

    // 分组
    public static final String GROUP_CHANNEL = "channel";
    public static final String GROUP_MERCHANT = "merchant";
    public static final String GROUP_ORDER = "order";
    public static final String GROUP_BLACKLIST = "blacklist";
    public static final String GROUP_NOTIFY = "notify";

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;
    private String valueType;

    private String groupKey;
    private String groupName;

    private String configName;
    private String configDesc;

    private String actionType;
    private Byte actionEnabled;

    private Integer sortNum;
}
