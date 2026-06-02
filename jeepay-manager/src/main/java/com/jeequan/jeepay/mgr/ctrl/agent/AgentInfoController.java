/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.mgr.ctrl.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jeequan.jeepay.core.aop.MethodLog;
import com.jeequan.jeepay.core.constants.ApiCodeEnum;
import com.jeequan.jeepay.core.entity.AgentInfo;
import com.jeequan.jeepay.core.model.ApiPageRes;
import com.jeequan.jeepay.core.model.ApiRes;
import com.jeequan.jeepay.mgr.ctrl.CommonCtrl;
import com.jeequan.jeepay.service.impl.AgentInfoService;
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
 * 代理商管理（3 级层级体系）
 */
@Tag(name = "代理商管理")
@RestController
@RequestMapping("/api/agentInfo")
public class AgentInfoController extends CommonCtrl {

    @Autowired private AgentInfoService agentInfoService;

    @PreAuthorize("hasAuthority('ENT_AGENT_LIST')")
    @RequestMapping(value = "", method = RequestMethod.GET)
    public ApiPageRes<AgentInfo> list() {
        AgentInfo q = getObject(AgentInfo.class);
        LambdaQueryWrapper<AgentInfo> wrapper = AgentInfo.gw();
        if (StringUtils.isNotEmpty(q.getAgentNo())) wrapper.eq(AgentInfo::getAgentNo, q.getAgentNo());
        if (StringUtils.isNotEmpty(q.getAgentName())) wrapper.like(AgentInfo::getAgentName, q.getAgentName());
        if (StringUtils.isNotEmpty(q.getParentAgentNo())) wrapper.eq(AgentInfo::getParentAgentNo, q.getParentAgentNo());
        if (q.getAgentLevel() != null) wrapper.eq(AgentInfo::getAgentLevel, q.getAgentLevel());
        if (q.getState() != null) wrapper.eq(AgentInfo::getState, q.getState());
        // 排序：AgentInfo 无 createdAt 字段，按主键 agentNo 倒序近似（业务键通常递增）
        wrapper.orderByDesc(AgentInfo::getAgentNo);
        IPage<AgentInfo> pages = agentInfoService.page(getIPage(true), wrapper);
        return ApiPageRes.pages(pages);
    }

    @PreAuthorize("hasAuthority('ENT_AGENT_ADD')")
    @MethodLog(remark = "新增代理商")
    @RequestMapping(value = "", method = RequestMethod.POST)
    public ApiRes add() {
        AgentInfo a = getObject(AgentInfo.class);
        // 注意：层级由 service 根据 parent 自动计算
        try {
            boolean ok = agentInfoService.createAgent(a);
            return ok ? ApiRes.ok() : ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_CREATE);
        } catch (Exception e) {
            return ApiRes.customFail(e.getMessage());
        }
    }

    @PreAuthorize("hasAnyAuthority('ENT_AGENT_VIEW', 'ENT_AGENT_EDIT')")
    @RequestMapping(value = "/{agentNo}", method = RequestMethod.GET)
    public ApiRes<AgentInfo> detail(@PathVariable String agentNo) {
        AgentInfo a = agentInfoService.getById(agentNo);
        if (a == null) return ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_SELETE);
        return ApiRes.ok(a);
    }

    @PreAuthorize("hasAuthority('ENT_AGENT_EDIT')")
    @MethodLog(remark = "修改代理商")
    @RequestMapping(value = "/{agentNo}", method = RequestMethod.PUT)
    public ApiRes update(@PathVariable String agentNo) {
        AgentInfo a = getObject(AgentInfo.class);
        a.setAgentNo(agentNo);
        boolean ok = agentInfoService.updateById(a);
        return ok ? ApiRes.ok() : ApiRes.fail(ApiCodeEnum.SYS_OPERATION_FAIL_UPDATE);
    }

    @PreAuthorize("hasAuthority('ENT_AGENT_DEL')")
    @MethodLog(remark = "删除代理商")
    @RequestMapping(value = "/{agentNo}", method = RequestMethod.DELETE)
    public ApiRes delete(@PathVariable String agentNo) {
        agentInfoService.removeById(agentNo);
        return ApiRes.ok();
    }

    /** 查询代理商完整层级链（从当前向上追溯） */
    @PreAuthorize("hasAuthority('ENT_AGENT_LIST')")
    @RequestMapping(value = "/chain/{agentNo}", method = RequestMethod.GET)
    public ApiRes<List<AgentInfo>> chain(@PathVariable String agentNo) {
        return ApiRes.ok(agentInfoService.getAgentChain(agentNo));
    }
}
