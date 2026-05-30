/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jeequan.jeepay.core.entity.CurrencyConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 支持币种配置表 Mapper 接口
 * </p>
 *
 * @author 国际支付改造组
 * @since 2026-05-30
 */
public interface CurrencyConfigMapper extends BaseMapper<CurrencyConfig> {

    /**
     * 查询全部启用币种（按 sort_num 升序）
     * 用于商户后台下拉框
     */
    List<CurrencyConfig> selectEnabledList();

    /**
     * 按币种代码列表查询配置（用于校验商户支持币种）
     */
    List<CurrencyConfig> selectByCurrencies(@Param("currencies") List<String> currencies);
}
