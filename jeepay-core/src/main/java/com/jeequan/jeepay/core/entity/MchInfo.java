/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
 * 商户信息表
 * </p>
 *
 * @author [mybatis plus generator]
 * @since 2021-04-27
 */
@Schema(description = "商户信息表")
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("t_mch_info")
public class MchInfo extends BaseModel implements Serializable {

    //gw
    public static final LambdaQueryWrapper<MchInfo> gw(){
        return new LambdaQueryWrapper<>();
    }

    private static final long serialVersionUID=1L;

    public static final byte TYPE_NORMAL = 1; //商户类型： 1-普通商户
    public static final byte TYPE_ISVSUB = 2; //商户类型： 2-特约商户


    /**
     * 商户号
     */
    @Schema(title = "mchNo", description = "商户号")
    @TableId(value = "mch_no", type = IdType.INPUT)
    private String mchNo;

    /**
     * 商户名称
     */
    @Schema(title = "mchName", description = "商户名称")
    private String mchName;

    /**
     * 商户简称
     */
    @Schema(title = "mchShortName", description = "商户简称")
    private String mchShortName;

    /**
     * 类型: 1-普通商户, 2-特约商户(服务商模式)
     */
    @Schema(title = "type", description = "类型: 1-普通商户, 2-特约商户(服务商模式)")
    private Byte type;

    /**
     * 服务商号
     */
    @Schema(title = "isvNo", description = "服务商号")
    private String isvNo;

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
     * 商户状态: 0-停用, 1-正常
     */
    @Schema(title = "state", description = "商户状态: 0-停用, 1-正常")
    private Byte state;

    /**
     * 商户备注
     */
    @Schema(title = "remark", description = "商户备注")
    private String remark;

    /**
     * 初始用户ID（创建商户时，允许商户登录的用户）
     */
    @Schema(title = "initUserId", description = "初始用户ID（创建商户时，允许商户登录的用户）")
    private Long initUserId;

    /**
     * 创建者用户ID
     */
    @Schema(title = "createdUid", description = "创建者用户ID")
    private Long createdUid;

    /**
     * 创建者姓名
     */
    @Schema(title = "createdBy", description = "创建者姓名")
    private String createdBy;

    /**
     * 创建时间
     */
    @Schema(title = "createdAt", description = "创建时间")
    private Date createdAt;

    /**
     * 更新时间
     */
    @Schema(title = "updatedAt", description = "更新时间")
    private Date updatedAt;

    // ===== 国际四方扩展字段 =====
    // 对应 international_payment_patch.sql / risk_control_patch.sql 的 ALTER TABLE 扩展

    /** 所属代理商号 */
    @Schema(title = "agentNo", description = "所属代理商号")
    private String agentNo;

    /** 商户结算币种 */
    @Schema(title = "settlementCurrency", description = "结算币种")
    private String settlementCurrency;

    /** 支持币种列表（逗号分隔） */
    @Schema(title = "supportCurrencies", description = "支持币种列表")
    private String supportCurrencies;

    /** MCC 行业代码 */
    @Schema(title = "mccCode", description = "MCC行业代码")
    private String mccCode;

    /** 风险等级 low/mid/high */
    @Schema(title = "riskTier", description = "风险等级")
    private String riskTier;

    /** 当前风险评分 */
    @Schema(title = "currentRiskScore", description = "当前风险评分")
    private Integer currentRiskScore;

    /** 日交易限额（分，0=不限） */
    @Schema(title = "dailyLimitAmount", description = "日交易限额")
    private Long dailyLimitAmount;

    /** 单笔限额（分，0=不限） */
    @Schema(title = "singleLimitAmount", description = "单笔限额")
    private Long singleLimitAmount;

    /** 超阈值自动暂停 0-否 1-是 */
    @Schema(title = "autoSuspendEnabled", description = "超阈值自动暂停")
    private Byte autoSuspendEnabled;

    /** 商户自定义拒付率告警阈值(%)，与 t_risk_threshold_config 中全局值二选一 */
    @Schema(title = "chargebackAlertThreshold", description = "拒付率告警阈值(%)")
    private java.math.BigDecimal chargebackAlertThreshold;

    /** 商户自定义拒付率自动暂停阈值(%) */
    @Schema(title = "autoSuspendThreshold", description = "拒付率自动暂停阈值(%)")
    private java.math.BigDecimal autoSuspendThreshold;

    // ===== R1 日交易额熔断（risk_v3_patch.sql 增加） =====
    // 设计要点：
    //   两列均允许 NULL，语义="回落到 t_risk_threshold_config 的全局默认"。
    //   不在 Java 侧给默认值，避免运营在表里清空后被实体覆盖回去。
    //   命名遵循 *_threshold / *_seconds 习惯，与 auto_suspend_threshold 风格一致；
    //   MyBatis-Plus 默认下划线转驼峰，schema 列 daily_amount_threshold_usd / daily_amount_circuit_seconds
    //   无需 @TableField 显式映射。

    /** 商户级日交易额熔断阈值(USD)，NULL=使用全局默认（merchant.daily_amount.threshold_usd） */
    @Schema(title = "dailyAmountThresholdUsd", description = "商户级日交易额熔断阈值(USD)，NULL=回落全局默认")
    private java.math.BigDecimal dailyAmountThresholdUsd;

    /** 商户级日额熔断时长(秒)，NULL=使用全局默认（merchant.daily_amount.circuit_seconds） */
    @Schema(title = "dailyAmountCircuitSeconds", description = "商户级日额熔断时长(秒)，NULL=回落全局默认")
    private Integer dailyAmountCircuitSeconds;

    // ===== 商户余额（拒付惩罚扣款来源，chargeback_penalty_patch.sql 增加） =====
    // 设计：单位为"分"。available 第一优先扣，pending 第二优先扣，frozen 仅审计

    /** 可用余额（分） */
    @Schema(title = "balanceAvailable", description = "可用余额（分），拒付扣款第一优先级")
    private Long balanceAvailable;

    /** 未下发余额（分） */
    @Schema(title = "balancePending", description = "未下发余额（分），拒付扣款第二优先级")
    private Long balancePending;

    /** 已冻结余额（分） */
    @Schema(title = "balanceFrozen", description = "已冻结余额（分），仅审计")
    private Long balanceFrozen;

    /** T+N 结算延迟天数（默认 T+1，由 mch_balance_patch.sql 增加） */
    @Schema(title = "settleDelayDays", description = "结算延迟天数 T+N，默认 1")
    private Integer settleDelayDays;

}
