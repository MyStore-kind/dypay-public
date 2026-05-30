/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.ratesource;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.CurrencyRate;
import com.jeequan.jeepay.core.entity.SysConfig;
import com.jeequan.jeepay.service.impl.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * Fixer.io 汇率源实现（通过 apilayer 网关）
 * 接口示例：GET https://api.apilayer.com/fixer/latest?base=USD&symbols=CNY,EUR
 * 注意事项：
 * - apikey 通过 Header `apikey` 传递，从 sys_config 读取，不硬编码。
 * - 免费档不支持自定义 base，本实现兼容性写法：base 不传或传 USD。
 * - 网络异常一律吞掉并返回空，由调度任务降级为使用历史汇率。
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
@Component
public class FixerRateSource implements IRateSource {

    private static final Logger logger = LoggerFactory.getLogger(FixerRateSource.class);

    /** Fixer API Key 配置项 */
    private static final String CFG_KEY = "rate.fixer.api.key";
    /** Fixer API URL 配置项 */
    private static final String CFG_URL = "rate.fixer.api.url";
    /** Fixer 启用开关 */
    private static final String CFG_ENABLE = "rate.source.fixer.enable";

    /** HTTP 超时（毫秒），避免阻塞调度线程 */
    private static final int HTTP_TIMEOUT_MS = 8000;

    @Autowired
    private SysConfigService sysConfigService;

    @Override
    public String sourceCode() {
        return CurrencyRate.SOURCE_FIXER;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean enabled() {
        return "true".equalsIgnoreCase(readConfig(CFG_ENABLE, "true"));
    }

    @Override
    public List<CurrencyRate> fetchRates(String baseCurrency, List<String> targets) {
        List<CurrencyRate> result = new ArrayList<>();
        String apiKey = readConfig(CFG_KEY, "");
        String apiUrl = readConfig(CFG_URL, "https://api.apilayer.com/fixer/latest");
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("[FixerRateSource] api key 未配置，跳过拉取");
            return result;
        }

        try {
            String symbols = String.join(",", targets);
            String url = apiUrl + "?base=" + baseCurrency + "&symbols=" + symbols;

            HttpResponse response = HttpRequest.get(url)
                    .header("apikey", apiKey)
                    .timeout(HTTP_TIMEOUT_MS)
                    .execute();

            if (!response.isOk()) {
                logger.warn("[FixerRateSource] HTTP {} body={}", response.getStatus(), response.body());
                return result;
            }

            JSONObject body = JSONObject.parseObject(response.body());
            if (!body.getBooleanValue("success")) {
                logger.warn("[FixerRateSource] API 返回失败：{}", body.toJSONString());
                return result;
            }

            JSONObject rates = body.getJSONObject("rates");
            if (rates == null) {
                return result;
            }
            Date now = new Date();
            for (String target : targets) {
                if (target.equalsIgnoreCase(baseCurrency)) {
                    continue;
                }
                Object val = rates.get(target);
                if (val == null) {
                    continue;
                }
                // 注意：BigDecimal 直接使用 String 构造，避免 double 精度丢失
                BigDecimal rate = new BigDecimal(val.toString())
                        .setScale(8, java.math.RoundingMode.HALF_UP);
                CurrencyRate cr = new CurrencyRate()
                        .setBaseCurrency(baseCurrency)
                        .setTargetCurrency(target)
                        .setRate(rate)
                        .setRateSource(sourceCode())
                        .setEffectiveTime(now);
                result.add(cr);
            }
        } catch (Exception e) {
            // 异常降级：吞掉异常返回空，调度层会回退到上一次有效汇率
            logger.error("[FixerRateSource] 拉取汇率异常", e);
        }
        return result;
    }

    /** 读取 sys_config 配置，找不到则用默认值 */
    private String readConfig(String key, String defaultVal) {
        SysConfig cfg = sysConfigService.getById(key);
        if (cfg == null || cfg.getConfigVal() == null) {
            return defaultVal;
        }
        return cfg.getConfigVal();
    }
}
