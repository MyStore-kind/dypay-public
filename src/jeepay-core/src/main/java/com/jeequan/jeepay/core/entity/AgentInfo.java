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

/**
 * <p>
 * 代理商信息表
 * 用于支持国际四方支付的多级代理商体系
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Schema(description = "代理商信息表")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_agent_info")
public class AgentInfo extends BaseModel implements Serializable {

    // gw
    public static final LambdaQueryWrapper<AgentInfo> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 代理商层级常量
    public static final byte LEVEL_FIRST = 1;   // 一级代理
    public static final byte LEVEL_SECOND = 2;  // 二级代理
    public static final byte LEVEL_THIRD = 3;   // 三级代理

    // 代理商状态常量
    public static final byte STATE_DISABLE = 0;  // 停用
    public static final byte STATE_ENABLE = 1;   // 启用
    public static final byte STATE_FROZEN = 2;   // 冻结

    // 结算周期常量
    public static final String CYCLE_T0 = "T0";   // 实时结算
    public static final String CYCLE_T1 = "T1";   // 次日结算
    public static final String CYCLE_T7 = "T7";   // 每周结算
    public static final String CYCLE_T30 = "T30"; // 每月结算

    /**
     * 代理商号
     */
    @Schema(title = "agentNo", description = "代理商号")
    @TableId(value = "agent_no", type = IdType.INPUT)
    private String agentNo;

    /**
     * 代理商名称
     */
    @Schema(title = "agentName", description = "代理商名称")
    private String agentName;

    /**
     * 代理商简称
     */
    @Schema(title = "agentShortName", description = "代理商简称")
    private String agentShortName;

    /**
     * 联系人姓名
     */
    @Schema(title = "contactName", description = "联系人姓名")
    private String contactName;

    /**
     * 联系人手机号
     */
    @Schema(title = "contactTel", description = "联系人手机号")
    private String contactTel;

    /**
     * 联系人邮箱
     */
    @Schema(title = "contactEmail", description = "联系人邮箱")
    private String contactEmail;

    /**
     * 上级代理商号（一级代理为 NULL）
     * 注意事项：递归查询上级时需判空，避免死循环
     */
    @Schema(title = "parentAgentNo", description = "上级代理商号")
    private String parentAgentNo;

    /**
     * 代理商层级 1-一级 2-二级 3-三级
     */
    @Schema(title = "agentLevel", description = "代理商层级")
    private Byte agentLevel;

    /**
     * 分润比例（百分比，如：0.5 表示 0.5%）
     * 为什么用 BigDecimal：避免浮点数精度丢失，金融场景必须使用
     */
    @Schema(title = "profitRate", description = "分润比例")
    private BigDecimal profitRate;

    /**
     * 结算周期 T0-实时 T1-次日 T7-每周 T30-每月
     */
    @Schema(title = "settlementCycle", description = "结算周期")
    private String settlementCycle;

    /**
     * 最低结算金额（单位：分）
     * 低于此金额不进行结算，累计到下一周期
     */
    @Schema(title = "minSettlementAmount", description = "最低结算金额")
    private Long minSettlementAmount;

    /**
     * 状态 0-停用 1-启用 2-冻结
     */
    @Schema(title = "state", description = "状态")
    private Byte state;

    /**
     * 备注
     */
    @Schema(title = "remark", description = "备注")
    private String remark;
}
