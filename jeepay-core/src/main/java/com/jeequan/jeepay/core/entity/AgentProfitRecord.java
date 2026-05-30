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
 * 代理商分润记录表
 * 记录每笔订单产生的代理商分润明细，支持对账与报表
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Schema(description = "代理商分润记录表")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_agent_profit_record")
public class AgentProfitRecord extends BaseModel implements Serializable {

    // gw
    public static final LambdaQueryWrapper<AgentProfitRecord> gw() {
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID = 1L;

    // 分润状态常量
    public static final byte STATE_PENDING = 0;  // 待结算
    public static final byte STATE_SETTLED = 1;  // 已结算
    public static final byte STATE_FROZEN = 2;   // 已冻结（如订单退款、风控等）

    /**
     * 记录ID（自增）
     */
    @Schema(title = "recordId", description = "记录ID")
    @TableId(value = "record_id", type = IdType.AUTO)
    private Long recordId;

    /**
     * 代理商号
     */
    @Schema(title = "agentNo", description = "代理商号")
    private String agentNo;

    /**
     * 商户号
     */
    @Schema(title = "mchNo", description = "商户号")
    private String mchNo;

    /**
     * 支付订单号
     */
    @Schema(title = "payOrderId", description = "支付订单号")
    private String payOrderId;

    /**
     * 订单金额（单位：分）
     */
    @Schema(title = "orderAmount", description = "订单金额")
    private Long orderAmount;

    /**
     * 订单币种（ISO 4217）
     */
    @Schema(title = "orderCurrency", description = "订单币种")
    private String orderCurrency;

    /**
     * 分润金额（单位：分）
     * 注意：分润币种与订单币种可能不同，按商户结算币种计算
     */
    @Schema(title = "profitAmount", description = "分润金额")
    private Long profitAmount;

    /**
     * 分润币种
     */
    @Schema(title = "profitCurrency", description = "分润币种")
    private String profitCurrency;

    /**
     * 分润比例（百分比）
     * 保留下单时的快照，避免后续代理商费率调整影响历史记录
     */
    @Schema(title = "profitRate", description = "分润比例")
    private BigDecimal profitRate;

    /**
     * 状态 0-待结算 1-已结算 2-已冻结
     */
    @Schema(title = "state", description = "状态")
    private Byte state;

    /**
     * 结算日期
     * 为什么单独存日期：方便按日聚合统计与对账
     */
    @Schema(title = "settleDate", description = "结算日期")
    private Date settleDate;

    /**
     * 关联结算单ID（任务 #3 新增）
     * 为什么：建立结算单→明细的反向追溯，对账时可由结算单号查到所有原始分润记录
     */
    @Schema(title = "settleId", description = "关联结算单ID")
    private Long settleId;

    /**
     * 创建时间
     * 为什么：结算作业按 created_at 划窗口（T1/T7/T30），无此字段无法按周期聚合
     */
    @Schema(title = "createdAt", description = "创建时间")
    private Date createdAt;

    /**
     * 更新时间
     */
    @Schema(title = "updatedAt", description = "更新时间")
    private Date updatedAt;
}
