/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 风险黑名单 Mapper
 *
 * @author 反风控改造组
 */
public interface RiskBlacklistMapper extends BaseMapper<RiskBlacklist> {

    /**
     * 命中校验：按类型+值查询是否在生效黑名单中
     * 生效条件：state=1 且 (expire_at IS NULL OR expire_at > NOW())
     * 返回非空即命中
     */
    RiskBlacklist hitCheck(@Param("listType") String listType,
                           @Param("listValue") String listValue);

    /**
     * 批量命中校验（一次性查多类型）
     * 为什么：订单风控会同时校验 IP/EMAIL/DEVICE/CARD_BIN，单次查询节省 RTT
     */
    List<RiskBlacklist> batchHitCheck(@Param("items") List<RiskBlacklist> items);

    /**
     * 命中计数累加（命中后异步调用）
     */
    int incrementHitCount(@Param("id") Long id);
}
