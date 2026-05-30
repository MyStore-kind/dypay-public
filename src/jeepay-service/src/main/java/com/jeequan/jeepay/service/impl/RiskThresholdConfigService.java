/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.RiskThresholdConfig;
import com.jeequan.jeepay.service.mapper.RiskThresholdConfigMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 风险阈值配置服务
 * 系统所有风控相关阈值的统一入口
 *
 * 设计要点：
 * - 内存缓存：阈值是高频读取，缓存避免每次查库
 * - 缓存自动刷新：60秒过期，运营修改后最多 60 秒生效
 * - 提供类型安全的读取方法（getNumber / getBoolean / getString）
 *
 * 注意：系统不内置任何阈值判断逻辑，所有阈值由运营在后台维护
 *
 * @author 反风控改造组
 */
@Service
public class RiskThresholdConfigService extends ServiceImpl<RiskThresholdConfigMapper, RiskThresholdConfig> {

    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 缓存：key -> value */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    /** 缓存：key -> action_type */
    private final ConcurrentHashMap<String, String> actionCache = new ConcurrentHashMap<>();
    /** 缓存：key -> action_enabled */
    private final ConcurrentHashMap<String, Boolean> actionEnabledCache = new ConcurrentHashMap<>();
    /** 缓存上次刷新时间 */
    private final AtomicLong lastLoadTime = new AtomicLong(0);

    /**
     * 获取字符串配置
     * 注意：返回 defaultValue 时不会抛错，调用方需自行处理空值场景
     */
    public String getString(String key, String defaultValue) {
        refreshIfExpired();
        String v = cache.get(key);
        return StringUtils.isBlank(v) ? defaultValue : v;
    }

    /**
     * 获取数值配置（用 BigDecimal 避免精度问题）
     */
    public BigDecimal getNumber(String key, BigDecimal defaultValue) {
        String v = getString(key, null);
        if (StringUtils.isBlank(v)) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v);
        } catch (Exception e) {
            // 配置值非法时返回默认值，避免影响业务
            return defaultValue;
        }
    }

    /**
     * 获取布尔配置
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (StringUtils.isBlank(v)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * 获取动作类型（如某项阈值触发后的动作：notify/limit/suspend）
     */
    public String getActionType(String key) {
        refreshIfExpired();
        return actionCache.get(key);
    }

    /**
     * 动作是否启用（运营可临时关闭某个自动动作，只记录不执行）
     */
    public boolean isActionEnabled(String key) {
        refreshIfExpired();
        return Boolean.TRUE.equals(actionEnabledCache.get(key));
    }

    /**
     * 按分组查询配置（用于后台配置界面分组展示）
     */
    public List<RiskThresholdConfig> listByGroup(String groupKey) {
        return list(RiskThresholdConfig.gw().eq(RiskThresholdConfig::getGroupKey, groupKey)
                .orderByAsc(RiskThresholdConfig::getSortNum));
    }

    /**
     * 手动触发缓存刷新（运营修改配置后调用）
     */
    public void refreshCache() {
        lastLoadTime.set(0);
        refreshIfExpired();
    }

    private synchronized void refreshIfExpired() {
        long now = System.currentTimeMillis();
        if (now - lastLoadTime.get() < CACHE_TTL_MS) {
            return;
        }
        List<RiskThresholdConfig> all = list();
        cache.clear();
        actionCache.clear();
        actionEnabledCache.clear();
        for (RiskThresholdConfig c : all) {
            cache.put(c.getConfigKey(), c.getConfigValue());
            if (StringUtils.isNotBlank(c.getActionType())) {
                actionCache.put(c.getConfigKey(), c.getActionType());
            }
            actionEnabledCache.put(c.getConfigKey(), c.getActionEnabled() != null && c.getActionEnabled() == 1);
        }
        lastLoadTime.set(now);
    }
}
