/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.ratesource;

import com.jeequan.jeepay.core.entity.CurrencyRate;

import java.util.List;

/**
 * <p>
 * 汇率源 SPI 接口
 * 为什么这么设计：将不同汇率来源（Fixer / Stripe / 手动）抽象为同一接口，
 * 定时调度只需轮询所有启用的实现即可，便于扩展（如未来加入欧央行、Wise 等）。
 * 注意事项：实现类应自处理异常（捕获网络错误并返回空集合），不要向外抛出，
 * 避免某个源故障阻塞整个拉取流程。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
public interface IRateSource {

    /**
     * 汇率来源标识（对应 t_currency_rate.rate_source）
     */
    String sourceCode();

    /**
     * 优先级：数值越大优先级越高
     * 选取策略：manual=100 > stripe=50 > fixer=30 > 其它
     * 当同一时刻多源给出同一币种对的汇率时，以优先级最高者为准。
     */
    int priority();

    /**
     * 是否启用（从 sys_config 读取开关）
     */
    boolean enabled();

    /**
     * 拉取汇率
     *
     * @param baseCurrency 基准币种（USD 等枢轴币种）
     * @param targets      目标币种列表
     * @return 汇率快照列表，已填充 baseCurrency / targetCurrency / rate / rateSource / effectiveTime
     */
    List<CurrencyRate> fetchRates(String baseCurrency, List<String> targets);
}
