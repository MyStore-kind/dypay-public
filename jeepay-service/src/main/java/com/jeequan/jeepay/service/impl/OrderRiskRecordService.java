/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 * 占位实现：实际数据由 ChargebackService 写入，此类提供查询补充
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.OrderRiskRecord;
import com.jeequan.jeepay.service.mapper.OrderRiskRecordMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 订单风控记录查询服务
 * （写入由 OrderRiskService.evaluate 后保存）
 *
 * @author 反风控改造组
 */
@Service
public class OrderRiskRecordService extends ServiceImpl<OrderRiskRecordMapper, OrderRiskRecord> {

    public Optional<OrderRiskRecord> findByPayOrderId(String payOrderId) {
        return Optional.ofNullable(getOne(OrderRiskRecord.gw()
                .eq(OrderRiskRecord::getPayOrderId, payOrderId)
                .last("LIMIT 1")));
    }
}
