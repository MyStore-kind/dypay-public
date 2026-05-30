/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.RiskThresholdConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 风险阈值配置 Mapper
 *
 * @author 反风控改造组
 */
public interface RiskThresholdConfigMapper extends BaseMapper<RiskThresholdConfig> {

    /**
     * 按 config_key 查询单条配置（最高频接口）
     * 注意：上层应配合 Redis 缓存，避免每次决策都查库
     */
    RiskThresholdConfig selectByConfigKey(@Param("configKey") String configKey);

    /**
     * 按分组查询配置（运营后台分组展示）
     */
    List<RiskThresholdConfig> selectByGroupKey(@Param("groupKey") String groupKey);

    /**
     * 全量加载启用配置（系统启动预热缓存）
     */
    List<RiskThresholdConfig> selectAllEnabled();
}
