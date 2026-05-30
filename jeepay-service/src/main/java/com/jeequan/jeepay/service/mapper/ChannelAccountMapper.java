/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通道账号池 Mapper
 *
 * @author 反风控改造组
 */
public interface ChannelAccountMapper extends BaseMapper<ChannelAccount> {

    /**
     * 按通道编码查询可用账号（启用 + 健康）
     * 用于路由器选号：state=1 且 health_status IN (1,2)
     */
    List<ChannelAccount> selectAvailableAccounts(@Param("ifCode") String ifCode);

    /**
     * 按风险等级 + MCC 过滤可用账号
     * MCC 黑白名单为字符串 like 匹配（逗号分隔），由 Service 层进一步精确判定
     */
    List<ChannelAccount> selectByRiskTierAndMcc(@Param("ifCode") String ifCode,
                                                 @Param("riskTier") String riskTier,
                                                 @Param("mcc") String mcc);

    /**
     * 按剩余额度排序（额度大者靠前），用于灰度路由
     * 计算：daily_limit - current_daily 与 monthly_limit - current_monthly 取较小
     */
    List<ChannelAccount> selectOrderByRemainingQuota(@Param("ifCode") String ifCode);
}
