/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.ChargebackRecord;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.ChargebackService;
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
 * 拒付（Chargeback）管理
 * 运营在此查看拒付列表、提交申诉证据、跟踪结果
 */
@Tag(name = "拒付管理")
@RestController
@RequestMapping("/api/risk/chargeback")
public class ChargebackController extends CommonCtrl {

    @Autowired private ChargebackService chargebackService;

    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_LIST')")
    @RequestMapping(value = "", method = RequestMethod.GET)
    public ApiPageRes<ChargebackRecord> list() {
        ChargebackRecord q = getObject(ChargebackRecord.class);
        LambdaQueryWrapper<ChargebackRecord> wrapper = ChargebackRecord.gw();
        if (StringUtils.isNotEmpty(q.getMchNo())) wrapper.eq(ChargebackRecord::getMchNo, q.getMchNo());
        if (StringUtils.isNotEmpty(q.getAccountId())) wrapper.eq(ChargebackRecord::getAccountId, q.getAccountId());
        if (StringUtils.isNotEmpty(q.getIfCode())) wrapper.eq(ChargebackRecord::getIfCode, q.getIfCode());
        if (StringUtils.isNotEmpty(q.getState())) wrapper.eq(ChargebackRecord::getState, q.getState());
        if (StringUtils.isNotEmpty(q.getPayOrderId())) wrapper.eq(ChargebackRecord::getPayOrderId, q.getPayOrderId());
        // 排序：ChargebackRecord 无 createdAt 字段，按主键 id 倒序（id 自增 = 创建顺序）
        wrapper.orderByDesc(ChargebackRecord::getId);
        IPage<ChargebackRecord> pages = chargebackService.page(getIPage(true), wrapper);
        return ApiPageRes.pages(pages);
    }

    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_LIST')")
    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public ApiRes<ChargebackRecord> detail(@PathVariable Long id) {
        ChargebackRecord r = chargebackService.getById(id);
        if (r == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        return ApiRes.ok(r);
    }

    /** 即将超时的拒付提醒（默认 48 小时内） */
    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_LIST')")
    @RequestMapping(value = "/expiring", method = RequestMethod.GET)
    public ApiRes<List<ChargebackRecord>> expiring() {
        return ApiRes.ok(chargebackService.listExpiringSoon(48));
    }

    /** 标记证据已提交 */
    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_EDIT')")
    @MethodLog(remark = "提交拒付证据")
    @RequestMapping(value = "/{id}/submitEvidence", method = RequestMethod.POST)
    public ApiRes submitEvidence(@PathVariable Long id) {
        chargebackService.markEvidenceSubmitted(id, getCurrentUser().getSysUser().getRealname());
        return ApiRes.ok();
    }

    /** 标记最终结果（won/lost/expired） */
    @PreAuthorize("hasAuthority('ENT_CHARGEBACK_EDIT')")
    @MethodLog(remark = "标记拒付结果")
    @RequestMapping(value = "/{id}/resolve/{result}", method = RequestMethod.POST)
    public ApiRes resolve(@PathVariable Long id, @PathVariable String result) {
        chargebackService.markResolved(id, result);
        return ApiRes.ok();
    }
}
