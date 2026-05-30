/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.CurrencyRateMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * <p>
 * 汇率服务
 * 核心职责：获取实时/历史汇率，进行金额币种转换
 * 注意：
 * - 同币种转换直接返回原金额，不查库
 * - 缓存策略待接入 Redis 后补充（避免高频查库）
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class CurrencyRateService extends ServiceImpl<CurrencyRateMapper, CurrencyRate> {

    /**
     * 获取实时汇率（数据库中最新有效的汇率）
     *
     * @param baseCurrency   基准币种
     * @param targetCurrency 目标币种
     * @return 汇率值
     */
    public BigDecimal getRealTimeRate(String baseCurrency, String targetCurrency) {
        // 同币种汇率为 1
        if (baseCurrency.equalsIgnoreCase(targetCurrency)) {
            return BigDecimal.ONE;
        }

        Date now = new Date();
        // 查询最新生效的汇率记录
        LambdaQueryWrapper<CurrencyRate> wrapper = CurrencyRate.gw()
                .eq(CurrencyRate::getBaseCurrency, baseCurrency)
                .eq(CurrencyRate::getTargetCurrency, targetCurrency)
                .le(CurrencyRate::getEffectiveTime, now)
                .and(w -> w.isNull(CurrencyRate::getExpireTime).or().gt(CurrencyRate::getExpireTime, now))
                .orderByDesc(CurrencyRate::getEffectiveTime)
                .last("LIMIT 1");

        CurrencyRate rate = getOne(wrapper);
        if (rate == null) {
            throw new BizException(String.format("未找到汇率配置：%s -> %s", baseCurrency, targetCurrency));
        }
        return rate.getRate();
    }

    /**
     * 金额币种转换
     * 注意：使用 HALF_UP 四舍五入，符合常规财务计算习惯
     *
     * @param amount       原始金额（单位：分）
     * @param fromCurrency 原币种
     * @param toCurrency   目标币种
     * @return 换算后金额（单位：分）
     */
    public Long convertAmount(Long amount, String fromCurrency, String toCurrency) {
        if (amount == null || amount == 0) {
            return 0L;
        }
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }
        BigDecimal rate = getRealTimeRate(fromCurrency, toCurrency);
        return new BigDecimal(amount)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 金额币种转换（带指定汇率）
     * 用于退款等场景，需使用订单创建时的历史汇率
     */
    public Long convertAmountWithRate(Long amount, BigDecimal rate) {
        if (amount == null || rate == null) {
            return 0L;
        }
        return new BigDecimal(amount)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
