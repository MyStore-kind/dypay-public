/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.ChargebackPenaltyConfig;
import com.jeequan.jeepay.core.entity.ChargebackPenaltyRecord;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.ChargebackPenaltyService;
import com.jeequan.jeepay.service.mapper.ChargebackPenaltyConfigMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 拒付惩罚配置 + 扣款流水查询
 *
 * URL: /api/risk/chargebackPenalty
 *   - GET  /config         分页查询配置
 *   - GET  /config/{mch}   单个商户配置（含 __GLOBAL__ 兜底）
 *   - POST /config         新增 / 更新（按 mch_no upsert）
 *   - DEL  /config/{id}    删除（不允许删 __GLOBAL__）
 *   - GET  /record         分页查询扣款流水
 */
@Tag(name = "拒付惩罚配置")
@RestController
@RequestMapping("/api/risk/chargebackPenalty")
public class ChargebackPenaltyController extends CommonCtrl {

    @Autowired private ChargebackPenaltyConfigMapper configMapper;
    @Autowired private ChargebackPenaltyService penaltyService;

    // ============================================
    // 配置：CRUD
    // ============================================

    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_LIST')")
    @RequestMapping(value = "/config", method = RequestMethod.GET)
    public ApiPageRes<ChargebackPenaltyConfig> listConfig() {
        ChargebackPenaltyConfig q = getObject(ChargebackPenaltyConfig.class);
        LambdaQueryWrapper<ChargebackPenaltyConfig> w = ChargebackPenaltyConfig.gw();
        if (StringUtils.isNotEmpty(q.getMchNo())) w.like(ChargebackPenaltyConfig::getMchNo, q.getMchNo());
        if (q.getEnabled() != null) w.eq(ChargebackPenaltyConfig::getEnabled, q.getEnabled());
        w.orderByDesc(ChargebackPenaltyConfig::getId);
        IPage<ChargebackPenaltyConfig> pages = configMapper.selectPage(getIPage(true), w);
        return ApiPageRes.pages(pages);
    }

    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_LIST')")
    @RequestMapping(value = "/config/{mchNo}", method = RequestMethod.GET)
    public ApiRes<ChargebackPenaltyConfig> detail(@PathVariable String mchNo) {
        ChargebackPenaltyConfig c = configMapper.selectOne(ChargebackPenaltyConfig.gw()
                .eq(ChargebackPenaltyConfig::getMchNo, mchNo).last("LIMIT 1"));
        return c == null ? ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE) : ApiRes.ok(c);
    }

    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_EDIT')")
    @MethodLog(remark = "保存拒付惩罚配置")
    @RequestMapping(value = "/config", method = RequestMethod.POST)
    public ApiRes saveOrUpdate() {
        ChargebackPenaltyConfig in = getObject(ChargebackPenaltyConfig.class);
        if (StringUtils.isBlank(in.getMchNo())) return ApiRes.customFail("mch_no 不能为空");
        ChargebackPenaltyConfig existed = configMapper.selectOne(ChargebackPenaltyConfig.gw()
                .eq(ChargebackPenaltyConfig::getMchNo, in.getMchNo()).last("LIMIT 1"));
        if (existed == null) {
            configMapper.insert(in);
        } else {
            in.setId(existed.getId());
            configMapper.updateById(in);
        }
        return ApiRes.ok();
    }

    @PreAuthorize("hasAuthority('ENT_RISK_THRESHOLD_EDIT')")
    @MethodLog(remark = "删除拒付惩罚配置")
    @RequestMapping(value = "/config/{id}", method = RequestMethod.DELETE)
    public ApiRes delete(@PathVariable Long id) {
        ChargebackPenaltyConfig c = configMapper.selectById(id);
        if (c == null) return ApiRes.ok();
        if (ChargebackPenaltyConfig.GLOBAL_MCH_NO.equals(c.getMchNo())) {
            return ApiRes.customFail("全局兜底配置不允许删除");
        }
        configMapper.deleteById(id);
        return ApiRes.ok();
    }

    // ============================================
    // 流水：只读查询
    // ============================================

    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_LIST')")
    @RequestMapping(value = "/record", method = RequestMethod.GET)
    public ApiPageRes<ChargebackPenaltyRecord> listRecord() {
        ChargebackPenaltyRecord q = getObject(ChargebackPenaltyRecord.class);
        LambdaQueryWrapper<ChargebackPenaltyRecord> w = ChargebackPenaltyRecord.gw();
        if (StringUtils.isNotEmpty(q.getMchNo())) w.eq(ChargebackPenaltyRecord::getMchNo, q.getMchNo());
        if (StringUtils.isNotEmpty(q.getState())) w.eq(ChargebackPenaltyRecord::getState, q.getState());
        if (StringUtils.isNotEmpty(q.getPayOrderId())) w.eq(ChargebackPenaltyRecord::getPayOrderId, q.getPayOrderId());
        w.orderByDesc(ChargebackPenaltyRecord::getId);
        IPage<ChargebackPenaltyRecord> pages = penaltyService.page(getIPage(true), w);
        return ApiPageRes.pages(pages);
    }
}
