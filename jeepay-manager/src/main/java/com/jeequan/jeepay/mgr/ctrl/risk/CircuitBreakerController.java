/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.RiskCircuitBreakerEngine;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风险熔断管理（任务 #18）
 *
 * 提供给运营的两类操作：
 * - 查询商户 / 通道账号当前熔断状态及快照
 * - 手动解除熔断（写日志）
 *
 * @author 反风控改造组
 */
@Tag(name = "风险熔断管理")
@RestController
@RequestMapping("/api/risk/circuitBreaker")
public class CircuitBreakerController extends CommonCtrl {

    @Autowired private RiskCircuitBreakerEngine circuitBreakerEngine;

    /**
     * 查询熔断状态（含 Redis 快照）
     *
     * @param targetType MERCHANT / ACCOUNT
     * @param targetId   商户号 / 通道账号 ID
     */
    @PreAuthorize("hasAuthority('ENT_RISK_CIRCUIT_BREAKER')")
    @RequestMapping(value = "/status", method = RequestMethod.GET)
    public ApiRes status(@RequestParam String targetType, @RequestParam String targetId) {
        JSONObject ret = new JSONObject();
        boolean broken;
        if (RiskCircuitBreakerEngine.TARGET_MERCHANT.equalsIgnoreCase(targetType)) {
            broken = circuitBreakerEngine.isCircuitBroken(targetId);
            ret.put("throttled", circuitBreakerEngine.isThrottled(targetId));
        } else {
            broken = circuitBreakerEngine.isAccountCircuitBroken(targetId);
        }
        ret.put("broken", broken);
        ret.put("snapshot", circuitBreakerEngine.getSnapshot(targetType, targetId));
        return ApiRes.ok(ret);
    }

    /**
     * 运营手动解除熔断
     *
     * 注意：解除仅清掉 Redis 状态；若商户 / 账号已被 state 冻结，
     * 需运营在商户管理或通道账号管理页面单独启用，避免误恢复。
     */
    @PreAuthorize("hasAuthority('ENT_RISK_CIRCUIT_BREAKER')")
    @MethodLog(remark = "运营手动解除风险熔断")
    @RequestMapping(value = "/release", method = RequestMethod.POST)
    public ApiRes release(@RequestParam String targetType, @RequestParam String targetId) {
        if (StringUtils.isBlank(targetType) || StringUtils.isBlank(targetId)) {
            return ApiRes.customFail("targetType/targetId 不能为空");
        }
        // operator：取当前登录用户的真实姓名，便于审计；异常时降级为 "unknown"
        String operator = "unknown";
        try {
            if (getCurrentUser() != null && getCurrentUser().getSysUser() != null) {
                operator = getCurrentUser().getSysUser().getRealname();
            }
        } catch (Exception ignore) { /* SecurityContext 缺失时静默处理 */ }
        boolean ok = circuitBreakerEngine.release(targetType, targetId, operator);
        return ok ? ApiRes.ok() : ApiRes.customFail("不支持的 targetType 或解除失败");
    }
}
