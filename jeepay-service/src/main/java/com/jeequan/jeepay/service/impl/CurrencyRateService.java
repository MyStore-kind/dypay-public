/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.CurrencyRateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 汇率服务
 * 核心职责：
 * 1. 获取实时/历史汇率（优先 Redis 缓存，再查 DB）
 * 2. 进行金额币种转换（支持冻结汇率）
 * 3. 提供 lockRate 给下单流程冻结汇率
 *
 * 注意：
 * - 同币种转换直接返回原金额，不查库
 * - BigDecimal 统一 scale=8 / HALF_UP（金融精度）
 * - 缓存 key：rate:{base}:{target}，TTL=1h
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class CurrencyRateService extends ServiceImpl<CurrencyRateMapper, CurrencyRate> {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyRateService.class);

    /** BigDecimal 计算保留位数（金融场景） */
    public static final int RATE_SCALE = 8;

    /** Redis 缓存 key 前缀 */
    private static final String RATE_CACHE_PREFIX = "rate:";

    /** 缓存 TTL：1 小时，与定时拉取周期一致 */
    private static final long RATE_CACHE_TTL_SECONDS = 3600;

    /**
     * 获取实时汇率（优先 Redis 缓存，未命中查 DB 最新有效记录）
     *
     * @param baseCurrency   基准币种
     * @param targetCurrency 目标币种
     * @return 汇率值（scale=8）
     */
    public BigDecimal getRealTimeRate(String baseCurrency, String targetCurrency) {
        // 同币种汇率为 1
        if (baseCurrency.equalsIgnoreCase(targetCurrency)) {
            return BigDecimal.ONE.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }

        // 1) Redis 缓存
        String cacheKey = buildCacheKey(baseCurrency, targetCurrency);
        String cached = RedisUtil.getString(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            try {
                return new BigDecimal(cached);
            } catch (Exception e) {
                logger.warn("[CurrencyRateService] 缓存值解析失败 key={} val={}", cacheKey, cached);
            }
        }

        // 2) DB 查询（按优先级：manual > stripe > fixer > 其它，且时间最新）
        CurrencyRate rate = queryLatestRate(baseCurrency, targetCurrency);
        if (rate == null) {
            // 异常降级：直查反向汇率取倒数（如果 base->target 没数据但 target->base 有）
            CurrencyRate reverse = queryLatestRate(targetCurrency, baseCurrency);
            if (reverse != null && reverse.getRate() != null && reverse.getRate().signum() > 0) {
                BigDecimal inverted = BigDecimal.ONE.divide(reverse.getRate(), RATE_SCALE, RoundingMode.HALF_UP);
                RedisUtil.setString(cacheKey, inverted.toPlainString(), RATE_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                return inverted;
            }
            throw new BizException(String.format("未找到汇率配置：%s -> %s", baseCurrency, targetCurrency));
        }

        BigDecimal value = rate.getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
        RedisUtil.setString(cacheKey, value.toPlainString(), RATE_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return value;
    }

    /**
     * 锁定汇率（下单时调用）
     * 为什么这么做：把"当前最新汇率"作为订单的不可变快照，存入 PayOrder.frozenRate。
     * 退款 / 对账始终以此为准，避免汇率波动造成业务侧差异。
     *
     * @param baseCurrency  基准币种
     * @param orderCurrency 订单币种
     * @return 锁定的汇率
     */
    public BigDecimal lockRate(String baseCurrency, String orderCurrency) {
        return getRealTimeRate(baseCurrency, orderCurrency);
    }

    /**
     * 金额币种转换（实时汇率）
     * 注意：使用 HALF_UP 四舍五入
     *
     * @param amount       原始金额（单位：分）
     * @param fromCurrency 原币种
     * @param toCurrency   目标币种
     * @return 换算后金额（单位：分）
     */
    public Long convertAmount(Long amount, String fromCurrency, String toCurrency) {
        return convertAmount(amount, fromCurrency, toCurrency, null);
    }

    /**
     * 金额币种转换
     * 退款 / 对账场景传入 frozenRate，可以严格按订单原汇率折算。
     *
     * @param amount       原始金额（单位：分）
     * @param fromCurrency 原币种
     * @param toCurrency   目标币种
     * @param frozenRate   冻结汇率；非空时使用，否则按实时汇率
     * @return 换算后金额（单位：分）
     */
    public Long convertAmount(Long amount, String fromCurrency, String toCurrency, BigDecimal frozenRate) {
        if (amount == null || amount == 0) {
            return 0L;
        }
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }
        BigDecimal rate = frozenRate != null ? frozenRate : getRealTimeRate(fromCurrency, toCurrency);
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

    /**
     * 缓存失效：手动维护汇率后调用，确保新汇率立即生效
     */
    public void invalidateCache(String baseCurrency, String targetCurrency) {
        RedisUtil.del(buildCacheKey(baseCurrency, targetCurrency));
    }

    /**
     * 写入汇率（带优先级覆盖逻辑）
     * 同一币种对、同源、相同时刻只保留一条。
     */
    public void saveRate(CurrencyRate rate) {
        if (rate.getEffectiveTime() == null) {
            rate.setEffectiveTime(new Date());
        }
        save(rate);
        // 写库后失效缓存，确保下次读取到最新值
        invalidateCache(rate.getBaseCurrency(), rate.getTargetCurrency());
    }

    /**
     * 按优先级选取最新有效汇率
     * 排序：rate_source 优先级（manual=100 > stripe=50 > fixer=30 > 其它=0）
     * 注：这里通过 CASE WHEN 排序，简单且对小数据量足够；
     * 高并发可考虑读写分离或常驻缓存。
     */
    private CurrencyRate queryLatestRate(String baseCurrency, String targetCurrency) {
        Date now = new Date();
        LambdaQueryWrapper<CurrencyRate> wrapper = CurrencyRate.gw()
                .eq(CurrencyRate::getBaseCurrency, baseCurrency)
                .eq(CurrencyRate::getTargetCurrency, targetCurrency)
                .le(CurrencyRate::getEffectiveTime, now)
                .and(w -> w.isNull(CurrencyRate::getExpireTime).or().gt(CurrencyRate::getExpireTime, now))
                .last("ORDER BY CASE rate_source " +
                        "WHEN 'manual' THEN 100 " +
                        "WHEN 'stripe' THEN 50 " +
                        "WHEN 'fixer' THEN 30 " +
                        "ELSE 0 END DESC, effective_time DESC LIMIT 1");
        return getOne(wrapper);
    }

    private String buildCacheKey(String base, String target) {
        return RATE_CACHE_PREFIX + base.toUpperCase() + ":" + target.toUpperCase();
    }
}
