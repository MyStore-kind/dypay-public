/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.ratesource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.entity.SysConfig;
import com.jeequan.jeepay.service.impl.CurrencyRateService;
import com.jeequan.jeepay.service.impl.SysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 手动汇率源实现
 * 数据来源：t_currency_rate 中 rate_source='manual' 的记录（运营在后台维护）
 *
 * 为什么需要：
 * 1. 三方 API 全部不可用时作为兜底；
 * 2. 部分非主流币种 API 不提供；
 * 3. 商户协议中约定了固定汇率（如包销）。
 *
 * 优先级最高（100），覆盖所有其它源。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Component
public class ManualRateSource implements IRateSource {

    private static final String CFG_ENABLE = "rate.source.manual.enable";

    @Autowired
    private SysConfigService sysConfigService;

    @Autowired
    private CurrencyRateService currencyRateService;

    @Override
    public String sourceCode() {
        return CurrencyRate.SOURCE_MANUAL;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean enabled() {
        SysConfig cfg = sysConfigService.getById(CFG_ENABLE);
        // 默认 true：手动维护永远可用
        return cfg == null || "true".equalsIgnoreCase(cfg.getConfigVal());
    }

    @Override
    public List<CurrencyRate> fetchRates(String baseCurrency, List<String> targets) {
        // 注意：手动源不"拉取"新数据，而是返回 DB 中已经存在的最新 manual 记录
        // 这样定时任务统一从 IRateSource 取数，逻辑一致
        List<CurrencyRate> result = new ArrayList<>();
        Date now = new Date();
        for (String target : targets) {
            if (target.equalsIgnoreCase(baseCurrency)) {
                continue;
            }
            LambdaQueryWrapper<CurrencyRate> wrapper = CurrencyRate.gw()
                    .eq(CurrencyRate::getBaseCurrency, baseCurrency)
                    .eq(CurrencyRate::getTargetCurrency, target)
                    .eq(CurrencyRate::getRateSource, CurrencyRate.SOURCE_MANUAL)
                    .le(CurrencyRate::getEffectiveTime, now)
                    .and(w -> w.isNull(CurrencyRate::getExpireTime).or().gt(CurrencyRate::getExpireTime, now))
                    .orderByDesc(CurrencyRate::getEffectiveTime)
                    .last("LIMIT 1");
            CurrencyRate cr = currencyRateService.getOne(wrapper);
            if (cr != null) {
                result.add(cr);
            }
        }
        return result;
    }
}
