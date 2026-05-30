/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.MerchantRiskScore;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.Map;

/**
 * 商户风险评分 Mapper（包含聚合 SQL）
 */
public interface MerchantRiskScoreMapper extends BaseMapper<MerchantRiskScore> {

    /**
     * 聚合商户最近 N 天指标（用于每日评分）
     * 返回字段：total/success/refund_count/total_amount
     */
    Map<String, Object> aggregateMerchantMetrics(@Param("mchNo") String mchNo,
                                                 @Param("startTime") Date startTime,
                                                 @Param("endTime") Date endTime);

    /** 商户最近 N 天 chargeback 笔数 */
    Integer countMerchantChargebacks(@Param("mchNo") String mchNo,
                                     @Param("startTime") Date startTime,
                                     @Param("endTime") Date endTime);
}
