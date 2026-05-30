/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.jeequan.jeepay.core.entity.CurrencyConfig;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.entity.SysConfig;
import com.jeequan.jeepay.service.impl.CurrencyConfigService;
import com.jeequan.jeepay.service.impl.CurrencyRateService;
import com.jeequan.jeepay.service.impl.SysConfigService;
import com.jeequan.jeepay.service.ratesource.IRateSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 多币种汇率定时拉取调度
 * 频率：默认每 1 小时（cron 可改 sys_config）
 *
 * 流程：
 * 1. 读取启用的币种列表与基准币种（USD 枢轴）；
 * 2. 遍历所有启用的 IRateSource，逐源拉取并入库；
 * 3. 异常自动降级：单源失败不影响整体，业务读取自动回退到历史汇率。
 *
 * 为什么以 USD 为枢轴：
 * - 国际市场报价以 USD 为主；
 * - 任意币种对都可通过 USD 三角换算（base->USD->target），减少存储量。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Component
public class CurrencyRateSchedule {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyRateSchedule.class);

    /** 基准币种配置项 */
    private static final String CFG_BASE = "rate.base.currency";
    /** 支持币种列表配置项 */
    private static final String CFG_SUPPORT = "rate.support.currencies";

    /** 默认基准币种（USD） */
    private static final String DEFAULT_BASE = "USD";
    /** 默认支持币种（10 种） */
    private static final String DEFAULT_SUPPORT = "USD,EUR,JPY,CNY,GBP,HKD,SGD,AUD,CAD,KRW";

    @Autowired
    private List<IRateSource> rateSources;

    @Autowired
    private CurrencyRateService currencyRateService;

    @Autowired
    private CurrencyConfigService currencyConfigService;

    @Autowired
    private SysConfigService sysConfigService;

    /**
     * 每小时整点拉取一次汇率
     * cron 表达式：每小时 15 分（错开整点的通道健康度任务）
     * 注意：失败重试由源实现自己控制，本调度只负责轮询。
     */
    @Scheduled(cron = "${jeepay.schedule.rate.cron:0 15 * * * ?}")
    public void pullRates() {
        try {
            String baseCurrency = readConfig(CFG_BASE, DEFAULT_BASE);
            List<String> targets = resolveTargets();
            logger.info("[CurrencyRateSchedule] 开始拉取汇率 base={} targets={}", baseCurrency, targets);

            int totalSaved = 0;
            // 按优先级从低到高执行：高优先级源后写，保证 effective_time 最新
            List<IRateSource> sorted = rateSources.stream()
                    .filter(IRateSource::enabled)
                    .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                    .collect(Collectors.toList());

            for (IRateSource source : sorted) {
                try {
                    List<CurrencyRate> fetched = source.fetchRates(baseCurrency, targets);
                    if (fetched == null || fetched.isEmpty()) {
                        logger.info("[CurrencyRateSchedule] 源 {} 无返回，跳过", source.sourceCode());
                        continue;
                    }
                    for (CurrencyRate rate : fetched) {
                        // 注意：手动源返回的是 DB 已有记录，避免重复入库
                        if (CurrencyRate.SOURCE_MANUAL.equals(rate.getRateSource()) && rate.getId() != null) {
                            continue;
                        }
                        currencyRateService.saveRate(rate);
                        totalSaved++;
                    }
                    logger.info("[CurrencyRateSchedule] 源 {} 写入 {} 条", source.sourceCode(), fetched.size());
                } catch (Exception e) {
                    // 单源故障不影响整体
                    logger.error("[CurrencyRateSchedule] 源 {} 异常", source.sourceCode(), e);
                }
            }

            logger.info("[CurrencyRateSchedule] 拉取完成，累计写入 {} 条", totalSaved);
        } catch (Exception e) {
            // 调度任务异常吞掉，避免阻塞 Spring 调度线程
            logger.error("[CurrencyRateSchedule] 调度异常", e);
        }
    }

    /**
     * 解析支持币种列表
     * 优先使用 sys_config 配置；否则使用 t_currency_config 中所有启用币种；
     * 兜底使用 10 种默认币种。
     */
    private List<String> resolveTargets() {
        String configured = readConfig(CFG_SUPPORT, null);
        if (configured != null && !configured.trim().isEmpty()) {
            return Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        // DB fallback
        List<CurrencyConfig> enabled = currencyConfigService.list(
                CurrencyConfig.gw().eq(CurrencyConfig::getState, CurrencyConfig.STATE_ENABLE));
        if (!enabled.isEmpty()) {
            return enabled.stream().map(CurrencyConfig::getCurrency).collect(Collectors.toList());
        }
        return Arrays.asList(DEFAULT_SUPPORT.split(","));
    }

    private String readConfig(String key, String defaultVal) {
        SysConfig cfg = sysConfigService.getById(key);
        if (cfg == null || cfg.getConfigVal() == null || cfg.getConfigVal().isEmpty()) {
            return defaultVal;
        }
        return cfg.getConfigVal();
    }
}
