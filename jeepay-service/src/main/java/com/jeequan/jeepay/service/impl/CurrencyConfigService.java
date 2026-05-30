/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.CurrencyConfig;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.CurrencyConfigMapper;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 币种配置服务
 * 核心职责：管理系统支持的币种、校验订单金额合法性
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Service
public class CurrencyConfigService extends ServiceImpl<CurrencyConfigMapper, CurrencyConfig> {

    /**
     * 校验币种是否启用
     */
    public void validateCurrencyEnabled(String currency) {
        CurrencyConfig config = getById(currency);
        if (config == null) {
            throw new BizException("不支持的币种：" + currency);
        }
        if (config.getState() != CurrencyConfig.STATE_ENABLE) {
            throw new BizException("币种已停用：" + currency);
        }
    }

    /**
     * 校验订单金额是否在合法范围内
     * 注意事项：JPY 等无小数位币种由调用方在传入金额时已折算为最小单位
     *
     * @param currency 币种
     * @param amount   金额（单位：分）
     */
    public void validateAmount(String currency, Long amount) {
        CurrencyConfig config = getById(currency);
        if (config == null) {
            throw new BizException("不支持的币种：" + currency);
        }
        if (amount == null || amount < config.getMinAmount()) {
            throw new BizException(String.format("订单金额低于最小限额：%d", config.getMinAmount()));
        }
        if (amount > config.getMaxAmount()) {
            throw new BizException(String.format("订单金额超过最大限额：%d", config.getMaxAmount()));
        }
    }
}
