/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.RiskThresholdConfig;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.RiskThresholdConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 风险阈值配置管理
 *
 * 运营核心操作入口：调整所有红线、阈值、触发动作
 */
@Tag(name = "风险阈值配置")
@RestController
@RequestMapping("/api/riskThresholdConfig")
public class RiskThresholdConfigController extends CommonCtrl {

    @Autowired private RiskThresholdConfigService thresholdConfigService;

    /** 分页列表（支持按分组筛选） */
    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_LIST')")
    @RequestMapping(value = "", method = RequestMethod.GET)
    public ApiPageRes<RiskThresholdConfig> list() {
        RiskThresholdConfig q = getObject(RiskThresholdConfig.class);
        LambdaQueryWrapper<RiskThresholdConfig> wrapper = RiskThresholdConfig.gw();
        if (StringUtils.isNotEmpty(q.getGroupKey())) {
            wrapper.eq(RiskThresholdConfig::getGroupKey, q.getGroupKey());
        }
        if (StringUtils.isNotEmpty(q.getConfigKey())) {
            wrapper.likeRight(RiskThresholdConfig::getConfigKey, q.getConfigKey());
        }
        wrapper.orderByAsc(RiskThresholdConfig::getGroupKey)
                .orderByAsc(RiskThresholdConfig::getSortNum);
        IPage<RiskThresholdConfig> pages = thresholdConfigService.page(getIPage(true), wrapper);
        return ApiPageRes.pages(pages);
    }

    /** 按分组查询（前端分组展示） */
    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_LIST')")
    @RequestMapping(value = "/group/{groupKey}", method = RequestMethod.GET)
    public ApiRes<List<RiskThresholdConfig>> listByGroup(@PathVariable String groupKey) {
        return ApiRes.ok(thresholdConfigService.listByGroup(groupKey));
    }

    /** 修改单项配置（运营调整阈值或动作开关后立即刷新缓存） */
    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_EDIT')")
    @MethodLog(remark = "修改风险阈值配置")
    @RequestMapping(value = "/{configKey}", method = RequestMethod.PUT)
    public ApiRes update(@PathVariable String configKey) {
        RiskThresholdConfig c = getObject(RiskThresholdConfig.class);
        c.setConfigKey(configKey);
        boolean ok = thresholdConfigService.updateById(c);
        if (!ok) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_UPDATE);
        // 强制刷新内存缓存，使修改即时生效
        thresholdConfigService.refreshCache();
        return ApiRes.ok();
    }

    /** 手动刷新缓存（罕见场景：DB 被外部直接修改） */
    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_EDIT')")
    @MethodLog(remark = "刷新风险阈值缓存")
    @RequestMapping(value = "/refreshCache", method = RequestMethod.POST)
    public ApiRes refreshCache() {
        thresholdConfigService.refreshCache();
        return ApiRes.ok();
    }
}
