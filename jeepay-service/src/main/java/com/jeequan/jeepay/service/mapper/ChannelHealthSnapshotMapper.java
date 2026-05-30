/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.ChannelHealthSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.Map;

/**
 * 通道健康度快照 Mapper
 *
 * 注意：聚合 SQL 在 xml 中实现，避免 Java 中拼接复杂 SQL
 */
public interface ChannelHealthSnapshotMapper extends BaseMapper<ChannelHealthSnapshot> {

    /** 聚合订单核心指标（来自 t_pay_order + t_order_risk_record） */
    Map<String, Object> aggregatePayOrderMetrics(@Param("accountId") String accountId,
                                                  @Param("startTime") Date startTime,
                                                  @Param("endTime") Date endTime);

    /** 退款笔数 */
    Integer countRefundOrders(@Param("accountId") String accountId,
                              @Param("startTime") Date startTime,
                              @Param("endTime") Date endTime);

    /** 拒付笔数 */
    Integer countChargebacks(@Param("accountId") String accountId,
                             @Param("startTime") Date startTime,
                             @Param("endTime") Date endTime);

    /** 投诉笔数（拒付+主动dispute） */
    Integer countDisputes(@Param("accountId") String accountId,
                          @Param("startTime") Date startTime,
                          @Param("endTime") Date endTime);
}
