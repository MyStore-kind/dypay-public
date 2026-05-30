/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * <p>
 * 汇率表 Mapper 接口
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
public interface CurrencyRateMapper extends BaseMapper<CurrencyRate> {

    /**
     * 查询最新汇率（按 rate_source 优先级回退）
     * 优先级：stripe > fixer > api > manual
     * 为什么这么做：通道方汇率最准确，手动维护作为兜底
     */
    CurrencyRate selectLatestRate(@Param("baseCurrency") String baseCurrency,
                                   @Param("targetCurrency") String targetCurrency,
                                   @Param("rateSource") String rateSource);

    /**
     * 查询指定时间点的有效汇率（用于退款按下单汇率结算）
     */
    CurrencyRate selectRateByTime(@Param("baseCurrency") String baseCurrency,
                                   @Param("targetCurrency") String targetCurrency,
                                   @Param("targetTime") Date targetTime);

    /**
     * 分页查询历史汇率
     */
    IPage<CurrencyRate> selectRatePage(Page<CurrencyRate> page,
                                        @Param("baseCurrency") String baseCurrency,
                                        @Param("targetCurrency") String targetCurrency,
                                        @Param("rateSource") String rateSource);
}
