/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.merchant;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.MchBalanceRecord;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.MchBalanceService;
import com.jeequan.jeepay.service.impl.MchInfoService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户余额管理（P1-3）
 *
 * URL: /api/mchInfo/balance
 *   GET  /{mchNo}              查商户余额三栏 + 配置
 *   POST /{mchNo}/topup        充值（available +）
 *   POST /{mchNo}/adjust       调账（available ±delta，需 operator）
 *   POST /{mchNo}/freeze       冻结
 *   POST /{mchNo}/unfreeze     解冻
 *   GET  /{mchNo}/records      余额流水分页
 *
 * 权限：复用 ENT_MCH_INFO 系列权限
 */
@Tag(name = "商户余额管理")
@RestController
@RequestMapping("/api/mchInfo/balance")
public class MchBalanceController extends CommonCtrl {

    /**
     * 充值/调账金额上限：1 亿元（按分计 = 10,000,000,000 分）
     * 超过此值的操作运营无权直接执行，需走线下流程
     */
    private static final long MAX_AMOUNT_LIMIT = 10_000_000_000L;

    @Autowired private MchInfoService mchInfoService;
    @Autowired private MchBalanceService mchBalanceService;

    @PreAuthorize("hasAuthority('ENT_MCH_INFO_VIEW')")
    @GetMapping("/{mchNo}")
    public ApiRes<JSONObject> detail(@PathVariable String mchNo) {
        MchInfo m = mchInfoService.getById(mchNo);
        if (m == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        JSONObject r = new JSONObject();
        r.put("mchNo", mchNo);
        r.put("mchName", m.getMchName());
        r.put("settlementCurrency", m.getSettlementCurrency());
        r.put("settleDelayDays", m.getSettleDelayDays());
        r.put("balanceAvailable", nullSafe(m.getBalanceAvailable()));
        r.put("balancePending", nullSafe(m.getBalancePending()));
        r.put("balanceFrozen", nullSafe(m.getBalanceFrozen()));
        return ApiRes.ok(r);
    }

    @PreAuthorize("hasAuthority('ENT_MCH_BALANCE_OPERATE')")
    @MethodLog(remark = "商户余额充值")
    @PostMapping("/{mchNo}/topup")
    public ApiRes topup(@PathVariable String mchNo) {
        JSONObject body = getReqParamJSON();
        Long amount = body.getLong("amount");
        if (amount == null || amount <= 0) return ApiRes.customFail("amount 必填且 > 0");
        if (amount > MAX_AMOUNT_LIMIT) {
            return ApiRes.customFail("单笔充值不得超过 " + (MAX_AMOUNT_LIMIT / 100) + " 元，请走线下流程");
        }
        String currency = body.getString("currency");
        String remark = body.getString("remark");
        String operator = currentOperator();
        mchBalanceService.topup(mchNo, amount, currency, operator, remark);
        return ApiRes.ok();
    }

    @PreAuthorize("hasAuthority('ENT_MCH_BALANCE_OPERATE')")
    @MethodLog(remark = "商户余额调账")
    @PostMapping("/{mchNo}/adjust")
    public ApiRes adjust(@PathVariable String mchNo) {
        JSONObject body = getReqParamJSON();
        Long delta = body.getLong("delta");
        if (delta == null || delta == 0) return ApiRes.customFail("delta 必填且非零");
        if (Math.abs(delta) > MAX_AMOUNT_LIMIT) {
            return ApiRes.customFail("单笔调账绝对值不得超过 " + (MAX_AMOUNT_LIMIT / 100) + " 元");
        }
        String currency = body.getString("currency");
        String remark = body.getString("remark");
        if (StringUtils.isBlank(remark)) return ApiRes.customFail("调账必须填 remark");
        String operator = currentOperator();
        mchBalanceService.adjust(mchNo, delta, currency, operator, remark);
        return ApiRes.ok();
    }

    @PreAuthorize("hasAuthority('ENT_MCH_BALANCE_OPERATE')")
    @MethodLog(remark = "商户余额冻结")
    @PostMapping("/{mchNo}/freeze")
    public ApiRes freeze(@PathVariable String mchNo) {
        JSONObject body = getReqParamJSON();
        Long amount = body.getLong("amount");
        if (amount == null || amount <= 0) return ApiRes.customFail("amount 必填且 > 0");
        if (amount > MAX_AMOUNT_LIMIT) {
            return ApiRes.customFail("单笔冻结不得超过 " + (MAX_AMOUNT_LIMIT / 100) + " 元");
        }
        String reason = body.getString("reason");
        if (StringUtils.isBlank(reason)) return ApiRes.customFail("冻结必须填 reason");
        // H4 修复：把 operator 也带进流水，方便追溯
        String operator = currentOperator();
        String auditReason = String.format("[op=%s] %s", operator, reason);
        mchBalanceService.freeze(mchNo, amount, auditReason);
        return ApiRes.ok();
    }

    @PreAuthorize("hasAuthority('ENT_MCH_BALANCE_OPERATE')")
    @MethodLog(remark = "商户余额解冻")
    @PostMapping("/{mchNo}/unfreeze")
    public ApiRes unfreeze(@PathVariable String mchNo) {
        JSONObject body = getReqParamJSON();
        Long amount = body.getLong("amount");
        if (amount == null || amount <= 0) return ApiRes.customFail("amount 必填且 > 0");
        if (amount > MAX_AMOUNT_LIMIT) {
            return ApiRes.customFail("单笔解冻不得超过 " + (MAX_AMOUNT_LIMIT / 100) + " 元");
        }
        String reason = body.getString("reason");
        String operator = currentOperator();
        String auditReason = String.format("[op=%s] %s", operator, reason == null ? "" : reason);
        mchBalanceService.unfreeze(mchNo, amount, auditReason);
        return ApiRes.ok();
    }

    @PreAuthorize("hasAuthority('ENT_MCH_INFO_VIEW')")
    @GetMapping("/{mchNo}/records")
    public ApiPageRes<MchBalanceRecord> records(@PathVariable String mchNo) {
        MchBalanceRecord q = getObject(MchBalanceRecord.class);
        LambdaQueryWrapper<MchBalanceRecord> w = MchBalanceRecord.gw()
                .eq(MchBalanceRecord::getMchNo, mchNo);
        if (StringUtils.isNotEmpty(q.getType())) w.eq(MchBalanceRecord::getType, q.getType());
        w.orderByDesc(MchBalanceRecord::getId);
        IPage<MchBalanceRecord> page = mchBalanceService.page(getIPage(true), w);
        return ApiPageRes.pages(page);
    }

    // ============================================
    private long nullSafe(Long v) { return v == null ? 0L : v; }

    private String currentOperator() {
        try {
            if (getCurrentUser() != null && getCurrentUser().getSysUser() != null) {
                return getCurrentUser().getSysUser().getRealname();
            }
        } catch (Exception ignored) {}
        return "system";
    }
}
