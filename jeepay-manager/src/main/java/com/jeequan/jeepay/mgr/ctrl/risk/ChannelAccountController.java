/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.ChannelAccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通道账号池管理
 * 运营在此 CRUD 通道账号、修改额度/优先级/白黑名单等
 */
@Tag(name = "通道账号池管理")
@RestController
@RequestMapping("/api/channelAccount")
public class ChannelAccountController extends CommonCtrl {

    @Autowired private ChannelAccountService channelAccountService;

    @PreAuthorize("hasAuthority('ENT_CHANNEL_ACCOUNT_LIST')")
    @RequestMapping(value = "", method = RequestMethod.GET)
    public ApiPageRes<ChannelAccount> list() {
        ChannelAccount q = getObject(ChannelAccount.class);
        LambdaQueryWrapper<ChannelAccount> wrapper = ChannelAccount.gw();
        if (StringUtils.isNotEmpty(q.getIfCode())) wrapper.eq(ChannelAccount::getIfCode, q.getIfCode());
        if (q.getState() != null) wrapper.eq(ChannelAccount::getState, q.getState());
        if (q.getHealthStatus() != null) wrapper.eq(ChannelAccount::getHealthStatus, q.getHealthStatus());
        if (StringUtils.isNotEmpty(q.getRiskTier())) wrapper.eq(ChannelAccount::getRiskTier, q.getRiskTier());
        wrapper.orderByDesc(ChannelAccount::getCreatedAt);
        IPage<ChannelAccount> pages = channelAccountService.page(getIPage(true), wrapper);
        return ApiPageRes.pages(pages);
    }

    @PreAuthorize("hasAuthority('ENT_CHANNEL_ACCOUNT_ADD')")
    @MethodLog(remark = "新增通道账号")
    @RequestMapping(value = "", method = RequestMethod.POST)
    public ApiRes add() {
        ChannelAccount a = getObject(ChannelAccount.class);
        // 注意：账号 ID 由运营手填（如 stripe_acct_001），保证可读性
        if (StringUtils.isBlank(a.getAccountId())) {
            return ApiRes.customFail("账号 ID 不能为空");
        }
        boolean ok = channelAccountService.save(a);
        return ok ? ApiRes.ok() : ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_CREATE);
    }

    @PreAuthorize("hasAnyAuthority('ENT_CHANNEL_ACCOUNT_VIEW', 'ENT_CHANNEL_ACCOUNT_EDIT')")
    @RequestMapping(value = "/{accountId}", method = RequestMethod.GET)
    public ApiRes<ChannelAccount> detail(@PathVariable String accountId) {
        ChannelAccount a = channelAccountService.getById(accountId);
        if (a == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        return ApiRes.ok(a);
    }

    @PreAuthorize("hasAuthority('ENT_CHANNEL_ACCOUNT_EDIT')")
    @MethodLog(remark = "修改通道账号")
    @RequestMapping(value = "/{accountId}", method = RequestMethod.PUT)
    public ApiRes update(@PathVariable String accountId) {
        ChannelAccount a = getObject(ChannelAccount.class);
        a.setAccountId(accountId);
        boolean ok = channelAccountService.updateById(a);
        return ok ? ApiRes.ok() : ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_UPDATE);
    }

    @PreAuthorize("hasAuthority('ENT_CHANNEL_ACCOUNT_DEL')")
    @MethodLog(remark = "删除通道账号")
    @RequestMapping(value = "/{accountId}", method = RequestMethod.DELETE)
    public ApiRes delete(@PathVariable String accountId) {
        channelAccountService.removeById(accountId);
        return ApiRes.ok();
    }
}
