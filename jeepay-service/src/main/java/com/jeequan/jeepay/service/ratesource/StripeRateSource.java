/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.ratesource;

import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.entity.SysConfig;
import com.jeequan.jeepay.service.impl.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * Stripe 汇率源实现
 * 说明：Stripe 本身没有公开的"通道汇率"查询接口；通常做法是在创建 PaymentIntent
 * 时通过 `automatic_payment_methods` 让 Stripe 决定结算汇率，并从交易回执中读取
 * `exchange_rate` 字段。这里给出占位实现，业务接入 Stripe 后可在
 * fetchRates 中调用商户已存的 secret key 进行查询。
 *
 * 为什么留占位：避免无意义的 HTTP 调用浪费配额，且默认 enabled=false。
 * 注意事项：真实实现需考虑 Stripe 多账号场景，按商户维度选择 secret_key。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Component
public class StripeRateSource implements IRateSource {

    private static final Logger logger = LoggerFactory.getLogger(StripeRateSource.class);

    private static final String CFG_ENABLE = "rate.source.stripe.enable";

    @Autowired
    private SysConfigService sysConfigService;

    @Override
    public String sourceCode() {
        return CurrencyRate.SOURCE_STRIPE;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public boolean enabled() {
        SysConfig cfg = sysConfigService.getById(CFG_ENABLE);
        // 默认 false：避免无 Stripe 集成的环境误调用
        return cfg != null && "true".equalsIgnoreCase(cfg.getConfigVal());
    }

    @Override
    public List<CurrencyRate> fetchRates(String baseCurrency, List<String> targets) {
        // 占位实现：实际接入需调用 Stripe Balance / Charge API 读取 exchange_rate 字段
        // 当前直接返回空集合，调度任务会自动跳过本源并使用 Fixer/手动数据
        logger.debug("[StripeRateSource] 默认占位实现，base={} targets={}", baseCurrency, targets);
        return Collections.emptyList();
    }
}
